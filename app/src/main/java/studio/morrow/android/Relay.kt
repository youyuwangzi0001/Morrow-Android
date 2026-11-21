package studio.morrow.android

/**
 * 远程通道域：帧编解码、ECDH 与 AES-GCM 信封、配对流，以及 WebSocket 客户端与重连编排。
 *
 * 四段严格按依赖顺序排布（协议 → 加密 → 配对 → 连接）：上层只用下层的公开类型，
 * 反过来不成立。合并前它们是四个文件，改动一处帧格式要同时翻四个文件才能确认没有漏改。
 */
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

import studio.morrow.android.EventAction
import studio.morrow.android.EventParser
import studio.morrow.android.EventStreamViewModel

// ── 原 relay/RelayProtocol.kt ──

// ───── RelayProtocol.kt ─────
/**
 * 手机侧 relay 协议层（对应桌面 `Morrow/src/main/relay-channel.ts` 的握手部分）。
 * 手机侧对应的义务是**连上后立刻发 hello**，不要等别的帧。
 */
object RelayProtocol {

  const val VERSION = 1

  /** 桌面 `verifyRequest(deviceId, 'lan', ts, sig)` 里的 channel 字面量 */
  // 反向验证 C：若改成 "wss"，签名内容就与桌面 `lan` 通道不同，真实 hello 会被 4401 拒绝；
  // RelayProtocolVectorTest 与 CrossDeviceE2ETest 都会把这种改动打红。
  const val CHANNEL_LAN = "lan"

  // ── 桌面 close code（direct-channel.ts:51-55）──
  const val CLOSE_UNAUTHORIZED = 4401
  const val CLOSE_TAMPERED = 4402
  const val CLOSE_REPLAYED = 4403

  /** 正常关闭：1000 与 1001（服务端下线）都可重连 */
  const val CLOSE_NORMAL = 1000
  const val CLOSE_GOING_AWAY = 1001

  const val KIND_HELLO = "hello"
  const val KIND_WELCOME = "welcome"
  const val KIND_ACK = "ack"

  /**
   * 配对确认（握手前）。
   *
   * 桌面在 `hello` 之前只接受这一种帧：手机报出票据里的 nonce，桌面把设备写入
   * 设备表并回 `pair-confirm-ok`。之后手机才能用新 sessionKey 走 hello。
   *
   * 为什么不能走普通的 invoke：invoke 只在**已鉴权**的加密通道上可用，
   * 而配对确认恰恰发生在「桌面还不认识这台设备」的时候 —— 会死锁。
   */
  const val KIND_PAIR_CONFIRM = "pair-confirm"
  const val KIND_PAIR_CONFIRM_OK = "pair-confirm-ok"

  /** 桌面 `SNAPSHOT_REQUIRED = 'snapshot_required'`（replay-buffer.ts:29） */
  const val SNAPSHOT_REQUIRED = "snapshot_required"

  class ProtocolError(val code: String, message: String) : Exception(message)

  // ─────────────────────── hello 签名 ───────────────────────

  /**
   * 构造 hello 帧。`sessionKey` 来自 D1f 的配对结果。
   *
   * 签名与桌面 `PairingService.signRequest` 逐字节一致：
   * `base64url(HMAC-SHA256(sessionKey, deviceId + "\n" + channel + "\n" + timestamp))`
   */
  fun helloFrame(
    sessionKeyBase64: String,
    deviceId: String,
    timestampMs: Long,
    /**
     * 手机已经应用到的最大事件序号。
     *
     * 0 = 没有任何状态（首次配对 / 重装），桌面会改发全量快照；
     * >0 = 重连，桌面只补 seq 更大的事件。
     */
    lastSeq: Long = 0L,
  ): String {
    val ts = timestampMs.toString()
    val sig = sign(sessionKeyBase64, deviceId, CHANNEL_LAN, ts)
    // 手拼 JSON：字段固定且都是我方生成的安全字符，但 deviceId 与 sig 仍要转义
    return buildString {
      append("{")
      append("\"v\":").append(VERSION).append(",")
      append("\"kind\":\"").append(KIND_HELLO).append("\",")
      append("\"deviceId\":\"").append(escape(deviceId)).append("\",")
      append("\"ts\":\"").append(ts).append("\",")
      append("\"sig\":\"").append(escape(sig)).append("\"")
      // 0 时省略：与旧版桌面/旧测试向量逐字节兼容；桌面缺省按 0 处理。
      if (lastSeq > 0L) append(",\"lastSeq\":").append(lastSeq)
      append("}")
    }
  }

  /**
   * 配对确认帧（明文，握手前发送）。
   *
   * 只带 nonce：桌面据此在自己的票据表里找到那张票，把设备写进设备表。
   * 不带公钥 —— 票据里的 `pubKey` 就是本机公钥，再发一次只会多一个
   * 可被替换的字段（而桌面必须用票据里那一份去算 sessionKey 才有意义）。
   */
  fun pairConfirmFrame(nonce: String): String = buildString {
    append("{")
    append("\"v\":").append(VERSION).append(",")
    append("\"kind\":\"").append(KIND_PAIR_CONFIRM).append("\",")
    append("\"nonce\":\"").append(escape(nonce)).append("\"")
    append("}")
  }

  /**
   * 解析桌面对配对确认的回答。返回 null = 不是这一种帧。
   *
   * 只认 `pair-confirm-ok`；失败时桌面直接关连接（关闭码 4401），
   * 所以这里不需要处理「拒绝」这个形状 —— 那种情况根本收不到帧。
   */
  fun parsePairConfirmOk(text: String, expectedDeviceId: String): Boolean {
    val obj = try {
      Json.parseToJsonElement(text) as? JsonObject
    } catch (t: Throwable) {
      return false
    } ?: return false
    val kind = obj["kind"]?.jsonPrimitive?.content ?: return false
    if (kind != KIND_PAIR_CONFIRM_OK) return false
    val deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: return false
    // deviceId 必须与票据里的一致：不一致说明桌面确认的不是我们这张票
    return deviceId == expectedDeviceId
  }

  /**
   * 上行 invoke 帧（加密前的明文）。
   *
   * 与事件信封同版本号；`seq` 是**手机侧**的调用序号（桌面不据此补漏，
   * 只用它做日志与去重排查）。`id` 由调用方生成，桌面在 result 帧里原样带回。
   */
  fun invokeFrame(id: String, method: String, params: Map<String, Any?>, seq: Long): String = buildString {
    append("{")
    append("\"v\":").append(VERSION).append(",")
    append("\"kind\":\"invoke\",")
    append("\"seq\":").append(seq).append(",")
    append("\"id\":\"").append(escape(id)).append("\",")
    append("\"method\":\"").append(escape(method)).append("\",")
    append("\"params\":").append(paramsJson(params))
    append("}")
  }

  /** 递归序列化 invoke 参数。只支持 JSON 原生类型；其他一律按字符串转义。 */
  private fun paramsJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + escape(value) + "\""
    is Boolean -> value.toString()
    is Int, is Long -> value.toString()
    is Double -> if (value.isFinite()) value.toString() else "null"
    is Float -> if (value.isFinite()) value.toString() else "null"
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
      "\"" + escape(k?.toString() ?: "") + "\":" + paramsJson(v)
    }
    is Iterable<*> -> value.joinToString(",", "[", "]") { paramsJson(it) }
    else -> "\"" + escape(value.toString()) + "\""
  }

  /** 与桌面 `PairingService.signRequest` 同一算法 */
  fun sign(sessionKeyBase64: String, deviceId: String, channel: String, timestamp: String): String {
    val key = try {
      Base64.getDecoder().decode(sessionKeyBase64)
    } catch (t: Throwable) {
      throw ProtocolError("BAD_SESSION_KEY", "sessionKey 不是合法 base64")
    }
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    val msg = "$deviceId\n$channel\n$timestamp".toByteArray(Charsets.UTF_8)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(msg))
  }

  // ─────────────────────── welcome ───────────────────────

  /**
   * welcome 帧。**明文下发**（此时手机还没有 salt，无法解密），
   * 所以桌面源码里专门有一段注释说明「welcome 必须不含任何载荷」——
   * 初版把补漏事件放进 welcome，导致代码/路径/对话明文经过中继。
   */
  data class Welcome(
    val deviceId: String,
    /** TLS 证书指纹，用于首次信任校验 */
    val fingerprint: String,
    /** 16 字节连接 salt（base64），用于构造 SecureChannel */
    val salt: String,
    val newestSeq: Long,
    /** 需要补漏的事件条数；`snapshotRequired` 为 true 时不适用 */
    val replayCount: Int?,
    /** 桌面无法补漏（缓冲为空或 lastSeq 非法）→ 必须请求全量快照 */
    val snapshotRequired: Boolean,
  )

  fun parseWelcome(text: String): Welcome {
    val obj = JsonLite.parseObject(text)
      ?: throw ProtocolError("WELCOME_MALFORMED", "welcome 不是 JSON 对象")

    if (obj["kind"] != KIND_WELCOME) {
      throw ProtocolError("WELCOME_MALFORMED", "不是 welcome 帧（kind=${obj["kind"]}）")
    }
    val v = obj["v"]?.toIntOrNull()
    if (v != VERSION) {
      throw ProtocolError("PROTOCOL_VERSION", "协议版本 $v != $VERSION")
    }
    val deviceId = obj["deviceId"] ?: throw ProtocolError("WELCOME_MALFORMED", "缺 deviceId")
    val fingerprint = obj["fingerprint"] ?: throw ProtocolError("WELCOME_MALFORMED", "缺 fingerprint")
    val salt = obj["salt"] ?: throw ProtocolError("WELCOME_MALFORMED", "缺 salt")
    val newestSeq = obj["newestSeq"]?.toLongOrNull()
      ?: throw ProtocolError("WELCOME_MALFORMED", "缺 newestSeq")

    // salt 必须是 16 字节：SecureChannel 用它防 nonce 重用，
    // 长度不对必须在这里就拒绝，而不是等到构造 channel 时才炸
    val saltBytes = try {
      Base64.getDecoder().decode(salt)
    } catch (t: Throwable) {
      throw ProtocolError("WELCOME_MALFORMED", "salt 不是合法 base64")
    }
    if (saltBytes.size != SecureChannel.SALT_BYTES) {
      throw ProtocolError(
        "WELCOME_MALFORMED",
        "salt 必须是 ${SecureChannel.SALT_BYTES} 字节，实际 ${saltBytes.size}",
      )
    }

    // snapshotRequired 是字符串标记（桌面 `snapshotRequired: SNAPSHOT_REQUIRED`），
    // 不是布尔。写成 true 就永远匹配不上，这里两种都认，但以字符串为准。
    val snapshotFlag = obj["snapshotRequired"]
    val snapshotRequired = snapshotFlag != null &&
      (snapshotFlag == SNAPSHOT_REQUIRED || snapshotFlag.equals("true", ignoreCase = true))

    val replayCount = obj["replayCount"]?.toIntOrNull()

    // 两个字段都不在时无法判断该补漏还是该要全量 —— 宁可报错也不要默认成「不用补」
    if (!snapshotRequired && replayCount == null) {
      throw ProtocolError(
        "WELCOME_MALFORMED",
        "welcome 既没有 replayCount 也没有 snapshotRequired，无法决定补漏策略",
      )
    }

    return Welcome(deviceId, fingerprint, salt, newestSeq, replayCount, snapshotRequired)
  }

  // ─────────────────────── 帧分类 ───────────────────────

  /**
   * 一条入站帧的语义分类。
   */
  sealed class Incoming {
    data class Welcome(val raw: String) : Incoming()
    data class Secure(val frame: SecureChannel.SealedFrame) : Incoming()
    data class Malformed(val reason: String) : Incoming()
  }

  fun classify(text: String): Incoming {
    val obj = JsonLite.parseObject(text)
      ?: return Incoming.Malformed("不是 JSON 对象")

    // 先判欢迎帧（唯一明文帧）
    if (obj["kind"] == KIND_WELCOME) return Incoming.Welcome(text)

    // 再判加密帧：必须同时有 n 与 c，且有别的字段就视为畸形
    val n = obj["n"]
    val c = obj["c"]
    if (n != null && c != null) {
      if (obj.keys.any { it != "n" && it != "c" }) {
        // 加密帧里塞额外字段：这些字段不会被 AAD 认证覆盖，
        // 若被下游读取就等于「绕过认证的旁路」。
        return Incoming.Malformed("加密帧含多余字段：${obj.keys.filter { it != "n" && it != "c" }}")
      }
      return Incoming.Secure(SecureChannel.SealedFrame(n = n, c = c))
    }

    if (n != null || c != null) {
      return Incoming.Malformed("加密帧字段不完整（n=${n != null}, c=${c != null}）")
    }

    // 握手后桌面只应发明文 welcome；其他明文帧一律拒绝（防降级攻击）
    return Incoming.Malformed("握手后收到非加密、非 welcome 的帧（kind=${obj["kind"]}）")
  }

  // ─────────────────────── 断线归因 ───────────────────────

  /**
   * 断线原因分类。这个判断直接决定要不要自动重连。
   *
   * **三种关闭码必须不重连**：4401/4402/4403 分别对应
   * 「未授权」「密文被篡改」「重放」。若对它们自动重连，
   * 等于让攻击者可以反复试 —— 而这三条正是「试错了」的证据。
   */
  enum class Disconnect {
    /** 网络抖动、服务端下线、正常关闭 —— 可以重连 */
    RETRYABLE,

    /** 未授权（hello 被拒 / 超时 / 协议版本不对）—— 重连无用，要重新配对 */
    UNAUTHORIZED,

    /** 密文被篡改 —— 可能是攻击，不重连 */
    TAMPERED,

    /** 重放 —— 可能是攻击，不重连 */
    REPLAYED,

    /** 协议错误（welcome 畸形等）—— 重连也无用 */
    PROTOCOL,
  }

  data class DisconnectInfo(val kind: Disconnect, val reason: String) {
    val shouldRetry: Boolean get() = kind == Disconnect.RETRYABLE
  }

  fun classifyClose(code: Int, reason: String?): DisconnectInfo = when (code) {
    CLOSE_UNAUTHORIZED -> DisconnectInfo(Disconnect.UNAUTHORIZED, reason ?: "未授权")
    CLOSE_TAMPERED -> DisconnectInfo(Disconnect.TAMPERED, reason ?: "密文被篡改")
    CLOSE_REPLAYED -> DisconnectInfo(Disconnect.REPLAYED, reason ?: "重放被拒")
    CLOSE_NORMAL, CLOSE_GOING_AWAY -> DisconnectInfo(Disconnect.RETRYABLE, reason ?: "正常关闭")
    // 1006 等异常关闭（连接被重置、心跳超时）属于网络层，可重连
    in 1000..1999 -> DisconnectInfo(Disconnect.RETRYABLE, reason ?: "关闭码 $code")
    in 4000..4999 -> DisconnectInfo(Disconnect.PROTOCOL, reason ?: "应用层关闭 $code")
    else -> DisconnectInfo(Disconnect.RETRYABLE, reason ?: "关闭码 $code")
  }

  /** 网络层失败（`onFailure`）：默认可重连，但协议级异常不可 */
  fun classifyFailure(error: Throwable): DisconnectInfo = when (error) {
    is ProtocolError -> DisconnectInfo(Disconnect.PROTOCOL, error.message ?: error.code)
    else -> DisconnectInfo(Disconnect.RETRYABLE, error.message ?: error::class.java.simpleName)
  }

  /**
   * 收到 `snapshotRequired` 时要发什么。
   *
   * 桌面源码的理由：缓冲为空或 `lastSeq` 非法时无法补漏，
   * 只能让手机拿全量快照。这里返回的请求帧走**加密**通道
   * （`SecureChannel` 已就绪），因为请求内容含 lastSeq，属于会话元数据。
   */
  fun snapshotRequest(lastSeq: Long): String =
    """{"kind":"snapshot_request","lastSeq":$lastSeq}"""

  /**
   * 收到补漏事件后回 ack，桌面据此推进已确认序号
   * （`direct-channel.ts` 里 `inner.kind === 'ack'` 分支）。
   */
  fun ackFrame(seq: Long): String = """{"kind":"ack","seq":$seq}"""

  // ─────────────────────── 内部 ───────────────────────

  private fun escape(s: String): String {
    if (s.none { it == '"' || it == '\\' || it < ' ' }) return s
    val sb = StringBuilder(s.length + 8)
    for (ch in s) {
      when (ch) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
      }
    }
    return sb.toString()
  }

  /**
   * 极简 JSON 解析（与 `EcdhPairing.JsonLite` 同一套严格规则：
   * 一处的收紧影响到另一处而没人注意到。代价是两份实现，所以两边都有
   */
  internal object JsonLite {
    fun parseObject(text: String): Map<String, String>? {
      val t = text.trim()
      if (!t.startsWith("{") || !t.endsWith("}")) return null
      val body = t.substring(1, t.length - 1).trim()
      if (body.isEmpty()) return emptyMap()

      val out = LinkedHashMap<String, String>()
      var i = 0
      while (i < body.length) {
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != '"') return null
        val keyStart = i + 1
        var j = keyStart
        while (j < body.length && body[j] != '"') {
          if (body[j] == '\\') j++
          j++
        }
        if (j >= body.length) return null
        val key = body.substring(keyStart, j)
        i = j + 1

        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != ':') return null
        i++
        while (i < body.length && body[i].isWhitespace()) i++

        val value: String
        if (i < body.length && body[i] == '"') {
          val vs = i + 1
          var k = vs
          while (k < body.length && body[k] != '"') {
            if (body[k] == '\\') k++
            k++
          }
          if (k >= body.length) return null
          value = unescape(body.substring(vs, k))
          i = k + 1
        } else {
          val vs = i
          while (i < body.length && body[i] != ',' && body[i] != '}') i++
          val raw = body.substring(vs, i).trim()
          if (raw.isEmpty() || raw.startsWith("{") || raw.startsWith("[")) return null
          value = raw
        }

        out[key] = value

        while (i < body.length && body[i].isWhitespace()) i++
        if (i < body.length) {
          if (body[i] == ',') {
            i++
            var j2 = i
            while (j2 < body.length && body[j2].isWhitespace()) j2++
            if (j2 >= body.length) return null   // 尾随逗号
          } else {
            return null
          }
        }
      }
      return out
    }

    private fun unescape(s: String): String {
      if (!s.contains('\\')) return s
      val sb = StringBuilder(s.length)
      var i = 0
      while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
          when (val n = s[i + 1]) {
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'u' -> {
              if (i + 5 < s.length) {
                val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null) {
                  sb.append(code.toChar())
                  i += 6
                  continue
                }
              }
              sb.append(n)
            }
            else -> sb.append(n)
          }
          i += 2
        } else {
          sb.append(c)
          i++
        }
      }
      return sb.toString()
    }
  }
}

// ───── RelayEnvelope.kt ─────
/**
 * 传输信封（对应桌面 `Morrow/src/main/relay-crypto.ts`）。
 * ## 为什么手机端必须有这一层
 * ## 三条校验照抄桌面，一条都不能松
 * 1. `v` 必须**等于** 1（不是 `<=`）—— 新旧版本混跑时明确失败好过「尽力解析」；
 * 2. `seq` 必须是**正整数**（0 与负数说明服务端分配逻辑坏了）；
 */
object RelayEnvelope {

  const val VERSION = 1

  /** 协议允许的四类信封（与桌面 `KINDS` 逐一对应） */
  val KINDS = setOf("event", "invoke", "result", "ack")

  /** 信封校验失败的原因（与桌面错误码同名，便于两端对照日志） */
  const val ENVELOPE_INVALID = "ENVELOPE_INVALID"

  data class Parsed(
    val v: Int,
    val kind: String,
    /** 服务端分配的单调序号，**从信封取**，不从事件体取 */
    val seq: Long,
    val runId: String,
    val sessionId: String,
    /** 服务端时间戳（毫秒）。手机不信任本地时钟，所有时间判断都用它 */
    val at: Long,
    val body: Map<String, Any?>,
  )

  sealed interface Result {
    data class Ok(val envelope: Parsed) : Result

    /** 不是给归并器的（kind != event），但信封本身合法 */
    data class NotAnEvent(val kind: String, val envelope: Parsed? = null) : Result

    /** 信封不合法 */
    data class Invalid(val reason: String) : Result
  }

  private val json = Json { ignoreUnknownKeys = true }

  /** 从 JSON 字符串解析（真机路径：解密后的明文是一个 JSON 串） */
  fun parseJson(text: String): Result = try {
    val element = json.parseToJsonElement(text)
    val obj = element as? JsonObject ?: return Result.Invalid("$ENVELOPE_INVALID: 顶层不是对象")
    parse(toMap(obj))
  } catch (t: Throwable) {
    // 解析失败必须能被上层统计到。静默 return 会让「协议不对」看起来像「没有事件」。
    Result.Invalid("$ENVELOPE_INVALID: JSON 解析失败 ${t.message ?: t::class.simpleName}")
  }

  /** 从已解析的 Map 校验并拆出信封 */
  fun parse(raw: Map<String, Any?>): Result {
    val v = (raw["v"] as? Number)?.toInt()
      ?: return Result.Invalid("$ENVELOPE_INVALID: 缺 v")
    if (v != VERSION) return Result.Invalid("$ENVELOPE_INVALID: 协议版本 $v != $VERSION")

    val kind = raw["kind"] as? String ?: return Result.Invalid("$ENVELOPE_INVALID: 缺 kind")
    if (kind !in KINDS) return Result.Invalid("$ENVELOPE_INVALID: 未知 kind=$kind")

    val seqNum = raw["seq"] as? Number ?: return Result.Invalid("$ENVELOPE_INVALID: 缺 seq")
    val seq = seqNum.toLong()
    // 0 与负数说明服务端分配逻辑坏了。不能「按 1 处理」——那会让补漏永远对不上。
    if (seqNum.toDouble() != seq.toDouble() || seq < 1) {
      return Result.Invalid("$ENVELOPE_INVALID: seq 必须是正整数（实际 $seqNum）")
    }

    val runId = raw["runId"] as? String ?: return Result.Invalid("$ENVELOPE_INVALID: 缺 runId")
    val sessionId = raw["sessionId"] as? String ?: return Result.Invalid("$ENVELOPE_INVALID: 缺 sessionId")

    val atNum = raw["at"] as? Number ?: return Result.Invalid("$ENVELOPE_INVALID: 缺 at")
    val at = atNum.toDouble()
    if (at.isNaN() || at.isInfinite()) {
      return Result.Invalid("$ENVELOPE_INVALID: at 不是有限数")
    }

    if (!raw.containsKey("body")) return Result.Invalid("$ENVELOPE_INVALID: 缺 body")
    val body = raw["body"] as? Map<*, *>
      ?: return Result.Invalid("$ENVELOPE_INVALID: body 不是对象")
    @Suppress("UNCHECKED_CAST")
    val bodyMap = body as Map<String, Any?>

    if (kind != "event") {
      // 非事件也要把信封带出去：`result` 帧的 id / ok / error 都在 body 里，
      // 只回一个 kind 字符串会让上行调用永远收不到结果（按钮点了没反应）。
      return Result.NotAnEvent(
        kind,
        Parsed(v = v, kind = kind, seq = seq, runId = runId, sessionId = sessionId, at = at.toLong(), body = bodyMap),
      )
    }

    return Result.Ok(
      Parsed(
        v = v, kind = kind, seq = seq, runId = runId,
        sessionId = sessionId, at = at.toLong(), body = bodyMap,
      )
    )
  }

  /**
   * 把事件体与信封元数据合成 `EventParser` 需要的那一份 Map。
   *
   * 关键：**`seq` 与 `sessionId` / `runId` / `at` 以信封为准**，覆盖事件体里的同名字段。
   * 理由：信封上的值由服务端分配并参与补漏判定；事件体里的同名字段即使存在，
   * 也只是回显，不能作为判定依据（客户端不该能通过改 body 影响 seq）。
   */
  fun toEventMap(env: Parsed): Map<String, Any?> {
    val merged = LinkedHashMap<String, Any?>(env.body)
    merged["seq"] = env.seq
    // 事件体自己带了 sessionId 时也以内层为准会更好吗？不会 ——
    // 信封的 sessionId 是服务端路由的依据，内层若是空串会把事件挂到错误会话。
    if ((merged["sessionId"] as? String).isNullOrBlank() && env.sessionId.isNotBlank()) {
      merged["sessionId"] = env.sessionId
    }
    if ((merged["runId"] as? String).isNullOrBlank() && env.runId.isNotBlank()) {
      merged["runId"] = env.runId
    }
    if (merged["at"] == null && env.at > 0) merged["at"] = env.at
    return merged
  }

  // ── JsonObject → Map（只处理对象/数组/基本类型，够信封用） ──

  private fun toMap(obj: JsonObject): Map<String, Any?> =
    obj.entries.associate { (k, v) -> k to toAny(v) }

  private fun toAny(v: kotlinx.serialization.json.JsonElement): Any? = when (v) {
    is JsonObject -> toMap(v)
    is kotlinx.serialization.json.JsonArray -> v.map { toAny(it) }
    is JsonPrimitive -> when {
      v.isString -> v.content
      else -> v.content.toDoubleOrNull()?.let { d ->
        // 整数保持 Long，避免 1 变成 1.0 再去 toLong 的抖动
        if (d % 1.0 == 0.0 && !v.content.contains('.') && !v.content.contains('e', true)) {
          v.content.toLongOrNull() ?: d
        } else d
      } ?: when (v.content) {
        "true" -> true
        "false" -> false
        "null" -> null
        else -> v.content
      }
    }
    else -> null
  }
}

// ───── WebSocketFrames.kt ─────
/**
 * 最小 WebSocket（RFC 6455）帧编解码，只实现 relay 需要的部分。
 * ## 客户端必须做的三件事（服务端不做，反之亦然）
 * | 规则 | 客户端 | 服务端 |
 * | 发送时加掩码 | **必须** | 禁止 |
 * | 分片帧 | 必须支持重组 | 同 |
 */
object WebSocketFrames {

  const val OP_CONTINUATION = 0x0
  const val OP_TEXT = 0x1
  const val OP_BINARY = 0x2
  const val OP_CLOSE = 0x8
  const val OP_PING = 0x9
  const val OP_PONG = 0xA

  /** 控制帧载荷上限（RFC 6455 §5.5） */
  const val MAX_CONTROL_PAYLOAD = 125

  /** 单条消息上限。与桌面 `maxPayload: 8 * 1024 * 1024` 对齐 */
  const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

  class FrameError(message: String) : Exception(message)

  data class Frame(
    val fin: Boolean,
    val opcode: Int,
    val payload: ByteArray,
  ) {
    val isControl: Boolean get() = opcode and 0x8 != 0
  }

  /**
   * 编码一帧。**客户端发送必须掩码**（RFC 6455 §5.3），
   * 掩码键用 `random` 提供的 4 字节。
   */
  /**
   * 编码一帧。`masked=true` 时按 RFC 6455 §5.3 加掩码。
   * **客户端发送必须 masked=true，服务端必须 masked=false**。
   * 这个参数不能有默认值：有默认值的话，测试里写的「假服务端」
   */
  fun encode(
    opcode: Int,
    payload: ByteArray,
    random: java.util.Random,
    fin: Boolean = true,
    masked: Boolean = true,
  ): ByteArray {
    require(!(opcode and 0x8 != 0) || payload.size <= MAX_CONTROL_PAYLOAD) {
      "控制帧载荷不能超过 $MAX_CONTROL_PAYLOAD 字节"
    }
    val out = java.io.ByteArrayOutputStream(payload.size + 14)
    out.write((if (fin) 0x80 else 0x00) or (opcode and 0x0F))

    val maskBit = if (masked) 0x80 else 0x00
    when {
      payload.size <= 125 -> out.write(maskBit or payload.size)
      payload.size <= 0xFFFF -> {
        out.write(maskBit or 126)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
      }
      else -> {
        out.write(maskBit or 127)
        val len = payload.size.toLong()
        for (i in 7 downTo 0) out.write(((len ushr (8 * i)) and 0xFF).toInt())
      }
    }

    if (!masked) {
      out.write(payload, 0, payload.size)
      return out.toByteArray()
    }

    val mask = ByteArray(4).also { random.nextBytes(it) }
    out.write(mask, 0, 4)
    for (i in payload.indices) {
      out.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
    }
    return out.toByteArray()
  }

  /**
   * 从流里读一帧。超时由调用方在 socket 上设置。
   *
   * 服务端发来的帧**不能带掩码**——带了必须拒绝（见类注释）。
   */
  fun read(input: InputStream, expectMasked: Boolean = false): Frame {
    val b0 = input.read()
    if (b0 < 0) throw EOFException("连接已关闭")
    val fin = b0 and 0x80 != 0
    val rsv = b0 and 0x70
    if (rsv != 0) throw FrameError("RSV 位必须为 0（未协商扩展），实际 $rsv")
    val opcode = b0 and 0x0F

    val b1 = input.read()
    if (b1 < 0) throw EOFException("读数长度时连接关闭")
    val masked = b1 and 0x80 != 0
    if (masked != expectMasked) {
      throw FrameError(
        if (expectMasked) "本应带掩码的帧没有掩码" else "服务端帧不该带掩码",
      )
    }

    var len = (b1 and 0x7F).toLong()
    if (len == 126L) {
      len = ((readByte(input).toLong() shl 8) or readByte(input).toLong())
    } else if (len == 127L) {
      len = 0
      for (i in 0 until 8) len = (len shl 8) or readByte(input).toLong()
      if (len < 0) throw FrameError("64 位长度超出范围")
    }

    if (len > MAX_MESSAGE_BYTES) throw FrameError("帧过大：$len 字节")
    if (opcode and 0x8 != 0 && len > MAX_CONTROL_PAYLOAD) {
      throw FrameError("控制帧载荷超过 $MAX_CONTROL_PAYLOAD")
    }

    val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
    val payload = ByteArray(len.toInt())
    readFully(input, payload)
    if (mask != null) {
      for (i in payload.indices) {
        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
      }
    }
    return Frame(fin, opcode, payload)
  }

  /** close 帧载荷：2 字节大端关闭码 + 可选原因（UTF-8） */
  fun encodeClose(code: Int, reason: String): ByteArray {
    val r = reason.toByteArray(Charsets.UTF_8)
    val payload = ByteArray(2 + minOf(r.size, MAX_CONTROL_PAYLOAD - 2))
    payload[0] = ((code ushr 8) and 0xFF).toByte()
    payload[1] = (code and 0xFF).toByte()
    r.copyInto(payload, 2, 0, minOf(r.size, MAX_CONTROL_PAYLOAD - 2))
    return payload
  }

  /** 解析 close 帧载荷；空载荷表示「无关闭码」（按 1005 处理） */
  fun parseClose(payload: ByteArray): Pair<Int, String> {
    if (payload.isEmpty()) return 1005 to ""
    if (payload.size == 1) throw FrameError("close 帧载荷长度非法（1 字节）")
    val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    val reason = String(payload, 2, payload.size - 2, Charsets.UTF_8)
    return code to reason
  }

  /** 生成握手请求（RFC 6455 §4.1） */
  fun handshakeRequest(host: String, port: Int, path: String = "/", key: String): String = buildString {
    append("GET ").append(path).append(" HTTP/1.1\r\n")
    append("Host: ").append(host).append(':').append(port).append("\r\n")
    append("Upgrade: websocket\r\n")
    append("Connection: Upgrade\r\n")
    append("Sec-WebSocket-Key: ").append(key).append("\r\n")
    append("Sec-WebSocket-Version: 13\r\n")
    // Origin 必须带：桌面若将来加来源校验，缺了会被拒
    append("Origin: https://").append(host).append(':').append(port).append("\r\n")
    append("\r\n")
  }

  /**
   * 校验握手响应。
   *
   * `Sec-WebSocket-Accept` 必须是 `base64(SHA1(key + GUID))`。
   * **必须校验**：不校验的话，一个普通的 HTTP 200 页面也会被当成握手成功，
   * 之后就进入「读帧读不到、一直等到超时」这种最难查的状态。
   */
  fun verifyHandshakeResponse(responseText: String, key: String) {
    val lines = responseText.split("\r\n")
    val status = lines.firstOrNull() ?: throw FrameError("握手响应为空")
    if (!status.startsWith("HTTP/1.1 101")) {
      throw FrameError("握手失败：$status")
    }
    val headers = lines.drop(1)
      .filter { it.contains(':') }
      .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }

    val upgrade = headers["upgrade"]?.lowercase()
    if (upgrade != "websocket") throw FrameError("缺 Upgrade: websocket（实际 $upgrade）")
    val conn = headers["connection"]?.lowercase() ?: ""
    if (!conn.contains("upgrade")) throw FrameError("缺 Connection: Upgrade（实际 $conn）")

    val expected = acceptKey(key)
    val actual = headers["sec-websocket-accept"]
    if (actual != expected) {
      throw FrameError("Sec-WebSocket-Accept 不匹配（期望 $expected，实际 $actual）")
    }
  }

  /** `base64(SHA1(clientKey + GUID))`，GUID 是 RFC 6455 规定的固定串 */
  fun acceptKey(clientKey: String): String {
    val guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    val sha1 = java.security.MessageDigest.getInstance("SHA-1")
    val digest = sha1.digest((clientKey + guid).toByteArray(Charsets.US_ASCII))
    return java.util.Base64.getEncoder().encodeToString(digest)
  }

  private fun readByte(input: InputStream): Int {
    val b = input.read()
    if (b < 0) throw EOFException("连接已关闭")
    return b
  }

  private fun readFully(input: InputStream, buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
      val n = input.read(buf, off, buf.size - off)
      if (n < 0) throw EOFException("期望 ${buf.size} 字节，只读到 $off")
      off += n
    }
  }

  /**
   * 写一帧（不缓冲刷新，调用方负责）。
   *
   * `masked` 必须由调用方明确指定方向：客户端 true，服务端 false。
   */
  fun writeFrame(
    output: OutputStream,
    opcode: Int,
    payload: ByteArray,
    random: java.util.Random,
    masked: Boolean,
  ) {
    output.write(encode(opcode, payload, random, masked = masked))
    output.flush()
  }
}
// ── 原 relay/RelayCrypto.kt ──

// ───── EcdhPairing.kt ─────
/**
 * 手机侧配对与 ECDH（对应桌面 `Morrow/src/main/relay-pairing.ts`）。
 * ## 三个必须与桌面逐字节一致的点
 * 容易看成同一个，格式必须定死。
 * ## 为什么票据必须带 desktopPub
 * 手机要用「自己的私钥 + **桌面的公钥**」算 ECDH。只有自己的公钥
 */
object EcdhPairing {

  const val CURVE = "secp256r1"

  /** P-256 的坐标长度（字节） */
  const val COORD_BYTES = 32
  /** 未压缩点长度：1 字节前缀 + 2 × 32 */
  const val UNCOMPRESSED_BYTES = 65

  const val TICKET_INVALID = "TICKET_INVALID"
  const val TICKET_EXPIRED = "TICKET_EXPIRED"

  class PairingException(val code: String, message: String) : Exception(message)

  /**
   * 配对票据。字段与桌面 `PairTicket` 一一对应。
   *
   * `desktopPub` 是**公钥**，不是密钥，可以进二维码 —— 与 `pubKey` 同理。
   * 注意：票据里**绝不能**出现 sessionKey / 私钥。桌面有一条测试专门断言
   * 字段集合里没有名字含 secret/session/private 的键，这里同理。
   */
  data class Ticket(
    val deviceId: String,
    val pubKey: String,
    val desktopPub: String,
    val nonce: String,
    val exp: Long,
    val relayHint: String? = null,
    /**
     * 桌面 TLS 证书指纹（可选）。
     * 桌面 `PairTicket` 目前**不含**这个字段（`pairing.ts:92` 只有六个字段），
     */
    val desktopFingerprint: String? = null,
  )

  /** 手机侧的长期 ECDH 密钥对 */
  class Identity(val privateKey: ECPrivateKey, val publicKeyB64: String) {
    /**
     * 私钥的 base64（PKCS#8 DER）。
     * 用途只有一个：写进 [RelayCredentials.Stored]。凭据文件本身被
     */
    val privateKeyB64: String
      get() = java.util.Base64.getEncoder().encodeToString(privateKey.encoded)
  }

  // ─────────────────────── 密钥对 ───────────────────────

  /** 生成 P-256 密钥对，公钥导出为未压缩点（65 字节 base64） */
  fun generateIdentity(): Identity {
    val gen = KeyPairGenerator.getInstance("EC")
    gen.initialize(ECGenParameterSpec(CURVE))
    val kp = gen.generateKeyPair()
    val pub = kp.public as ECPublicKey
    return Identity(kp.private as ECPrivateKey, encodeUncompressed(pub))
  }

  /**
   * 把 Java 的公钥编码成未压缩点。
   * 所以必须显式左补零到 32 字节。
   */
  fun encodeUncompressed(pub: ECPublicKey): String {
    val x = fixedWidth(pub.w.affineX)
    val y = fixedWidth(pub.w.affineY)
    val out = ByteArray(UNCOMPRESSED_BYTES)
    out[0] = 0x04
    x.copyInto(out, 1)
    y.copyInto(out, 1 + COORD_BYTES)
    return Base64.getEncoder().encodeToString(out)
  }

  private fun fixedWidth(v: BigInteger): ByteArray {
    val raw = v.toByteArray()
    // BigInteger.toByteArray() 可能带一个前导 0x00 符号位，或不足 32 字节
    val out = ByteArray(COORD_BYTES)
    when {
      raw.size == COORD_BYTES -> raw.copyInto(out)
      raw.size == COORD_BYTES + 1 && raw[0] == 0.toByte() -> raw.copyInto(out, 0, 1, raw.size)
      raw.size < COORD_BYTES -> raw.copyInto(out, COORD_BYTES - raw.size)
      else -> throw PairingException(TICKET_INVALID, "坐标长度异常 ${raw.size}")
    }
    return out
  }

  /** 把 base64 的未压缩点还原成 Java 公钥 */
  fun decodePublicKey(b64: String): ECPublicKey {
    val raw = try {
      Base64.getDecoder().decode(b64)
    } catch (t: Throwable) {
      throw PairingException(TICKET_INVALID, "公钥不是合法 base64")
    }
    if (raw.size != UNCOMPRESSED_BYTES || raw[0] != 0x04.toByte()) {
      throw PairingException(
        TICKET_INVALID,
        "公钥必须是 65 字节未压缩点（04||X||Y），实际 ${raw.size} 字节",
      )
    }
    val x = BigInteger(1, raw.copyOfRange(1, 1 + COORD_BYTES))
    val y = BigInteger(1, raw.copyOfRange(1 + COORD_BYTES, UNCOMPRESSED_BYTES))

    val params: ECParameterSpec = curveParams()
    val spec = ECPublicKeySpec(ECPoint(x, y), params)
    return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
  }

  /** 取 P-256 的曲线参数：生成一对临时密钥，从公钥里读出来 */
  private fun curveParams(): ECParameterSpec {
    val gen = KeyPairGenerator.getInstance("EC")
    gen.initialize(ECGenParameterSpec(CURVE))
    return (gen.generateKeyPair().public as ECPublicKey).params
  }

  // ─────────────────────── ECDH ───────────────────────

  /**
   * 计算 ECDH 共享秘密。
   *
   * 返回的字节数与桌面 `createECDH('prime256v1').computeSecret()` 一致：
   * 都是 X 坐标的 32 字节定长大端表示。
   */
  fun sharedSecret(privateKey: ECPrivateKey, peerPubB64: String): ByteArray {
    val peer = decodePublicKey(peerPubB64)
    val ka = KeyAgreement.getInstance("ECDH")
    ka.init(privateKey)
    ka.doPhase(peer, true)
    return ka.generateSecret()
  }

  /**
   * 六位比对码。必须与桌面 `derivePairingCode` 逐字节一致：
   * `HMAC-SHA256(sharedSecret, "pair-code:<nonce>")` 取前 4 字节按**大端**读成
   * uint32，`% 1_000_000`，再左补零到 6 位。
   *
   * 这个码的作用是抓中间人：公钥被替换 → 共享秘密不同 → 两端码不同。
   */
  fun pairingCode(sharedSecret: ByteArray, nonce: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
    val digest = mac.doFinal("pair-code:$nonce".toByteArray(Charsets.UTF_8))
    // 大端读 uint32（Java 没有无符号类型，用 Long 承接避免负数）
    val n = ((digest[0].toLong() and 0xFF) shl 24) or
      ((digest[1].toLong() and 0xFF) shl 16) or
      ((digest[2].toLong() and 0xFF) shl 8) or
      (digest[3].toLong() and 0xFF)
    return (n % 1_000_000L).toString().padStart(6, '0')
  }

  /** 会话密钥：**就是**共享秘密的 base64（与桌面 `completePairing` 一致） */
  fun sessionKey(sharedSecret: ByteArray): String =
    Base64.getEncoder().encodeToString(sharedSecret)

  // ─────────────────────── 票据 ───────────────────────

  /**
   * 解析二维码内容。
   */
  fun parseTicket(qr: String, nowMillis: Long): Ticket {
    val json = try {
      String(Base64.getUrlDecoder().decode(qr), Charsets.UTF_8)
    } catch (t: Throwable) {
      throw PairingException(TICKET_INVALID, "二维码不是合法 base64url")
    }

    val obj = JsonLite.parseObject(json)
      ?: throw PairingException(TICKET_INVALID, "二维码内容不是 JSON 对象")

    val deviceId = obj["deviceId"] ?: throw PairingException(TICKET_INVALID, "缺 deviceId")
    val pubKey = obj["pubKey"] ?: throw PairingException(TICKET_INVALID, "缺 pubKey")
    val desktopPub = obj["desktopPub"]
      ?: throw PairingException(TICKET_INVALID, "缺 desktopPub（没有它算不出共享秘密）")
    val nonce = obj["nonce"] ?: throw PairingException(TICKET_INVALID, "缺 nonce")
    val expRaw = obj["exp"] ?: throw PairingException(TICKET_INVALID, "缺 exp")
    val exp = expRaw.toLongOrNull() ?: throw PairingException(TICKET_INVALID, "exp 不是整数")

    // 密钥材料泄漏守卫：票据里只允许公钥，绝不接受私钥/会话密钥
    val forbidden = obj.keys.filter { Regex("session|secret|private", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    if (forbidden.isNotEmpty()) {
      throw PairingException(TICKET_INVALID, "票据含密钥字段：${forbidden.joinToString(",")}")
    }

    if (nowMillis > exp) {
      throw PairingException(TICKET_EXPIRED, "票据已过期（exp=$exp now=$nowMillis）")
    }

    return Ticket(deviceId, pubKey, desktopPub, nonce, exp, obj["relayHint"], obj["fingerprint"])
  }

  /**
   * 手机侧完整配对计算：给出票据 + 自己的密钥对，产出六位码与 sessionKey。
   *
   * 把「算共享秘密 → 派生六位码 → 产出 sessionKey」收在一处，
   * 是为了不让调用方有机会只用其中一半（比如显示六位码但忘了校验过期）。
   */
  data class PairResult(val code: String, val sessionKey: String, val deviceId: String, val nonce: String)

  fun pair(ticket: Ticket, identity: Identity): PairResult {
    val shared = sharedSecret(identity.privateKey, ticket.desktopPub)
    return PairResult(
      code = pairingCode(shared, ticket.nonce),
      sessionKey = sessionKey(shared),
      deviceId = ticket.deviceId,
      nonce = ticket.nonce,
    )
  }

  /**
   * 极简 JSON 解析（只处理对象 + 字符串/数字值）。
   * 引入完整 JSON 库会多 1 个依赖和十几 KB。但正因如此，解析必须严格 ——
   * 遇到嵌套对象/数组一律拒绝，而不是「尽量解析」，否则畸形输入会被
   */
  internal object JsonLite {
    fun parseObject(text: String): Map<String, String>? {
      val t = text.trim()
      if (!t.startsWith("{") || !t.endsWith("}")) return null
      val body = t.substring(1, t.length - 1).trim()
      if (body.isEmpty()) return emptyMap()

      val out = LinkedHashMap<String, String>()
      var i = 0
      while (i < body.length) {
        // 读 key
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != '"') return null
        val keyStart = i + 1
        var j = keyStart
        while (j < body.length && body[j] != '"') {
          if (body[j] == '\\') j++   // 跳过转义
          j++
        }
        if (j >= body.length) return null
        val key = body.substring(keyStart, j)
        i = j + 1

        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != ':') return null
        i++
        while (i < body.length && body[i].isWhitespace()) i++

        // 读 value：只接受字符串或数字
        val value: String
        if (i < body.length && body[i] == '"') {
          val vs = i + 1
          var k = vs
          while (k < body.length && body[k] != '"') {
            if (body[k] == '\\') k++
            k++
          }
          if (k >= body.length) return null
          value = unescape(body.substring(vs, k))
          i = k + 1
        } else {
          val vs = i
          while (i < body.length && body[i] != ',' && body[i] != '}') i++
          val raw = body.substring(vs, i).trim()
          if (raw.isEmpty() || raw.startsWith("{") || raw.startsWith("[")) return null
          value = raw
        }

        out[key] = value

        while (i < body.length && body[i].isWhitespace()) i++
        if (i < body.length) {
          if (body[i] == ',') {
            i++
            // 尾随逗号守卫：`{...,"exp":1,}` 这种畸形 JSON 若照常跳出循环就会被接受。
            // 逗号后面必须还有内容，否则判非法 —— 宁可拒掉也不要「尽量解析」。
            var j2 = i
            while (j2 < body.length && body[j2].isWhitespace()) j2++
            if (j2 >= body.length) return null
          } else {
            return null
          }
        }
      }
      return out
    }

    private fun unescape(s: String): String {
      if (!s.contains('\\')) return s
      val sb = StringBuilder(s.length)
      var i = 0
      while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
          when (val n = s[i + 1]) {
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            else -> sb.append(n)
          }
          i += 2
        } else {
          sb.append(c)
          i++
        }
      }
      return sb.toString()
    }
  }
}

// ───── SecureChannel.kt ─────
/**
 * 应用层加密帧（对应桌面 `Morrow/src/main/relay-crypto.ts`）。
 * ## 必须与桌面**逐字节互通**
 * | 环节 | 必须一致的点 |
 * 手机必须能解开它。自洽往返即使全对，也可能两边都不兼容。
 */
class SecureChannel(
  sessionKeyBase64: String,
  connectionSaltBase64: String,
  private val deviceId: String,
) {

  companion object {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val SALT_BYTES = 16

    private const val VERSION = "morrow-frame:v1"

    /** 帧错误码，与桌面同名，便于两端对照日志 */
    const val FRAME_TAMPERED = "FRAME_TAMPERED"
    const val FRAME_REPLAYED = "FRAME_REPLAYED"
    const val FRAME_MALFORMED = "FRAME_MALFORMED"

    /** 方向。桌面用 `'phone-to-desktop' | 'desktop-to-phone'` 字符串 */
    const val P2D = "phone-to-desktop"
    const val D2P = "desktop-to-phone"
  }

  class FrameError(val code: String, message: String) : Exception(message)

  /** 线上帧：只有计数与密文（nonce / AAD 都可从计数重建，不必传） */
  data class SealedFrame(val n: String, val c: String)

  private val keys: Map<String, ByteArray>
  private val aadPrefix: Map<String, ByteArray>

  private val sendCounter = mutableMapOf(P2D to 0L, D2P to 0L)

  /**
   * 已接受的最大计数。**初值 -1**：0 表示「已接受过计数 0」，
   * 会让对端的第一帧被判重放（桌面修过这个 off-by-one）。
   */
  private val recvCounter = mutableMapOf(P2D to -1L, D2P to -1L)

  init {
    val ikm = decodeBase64(sessionKeyBase64)
    if (ikm.isEmpty()) throw FrameError(FRAME_MALFORMED, "sessionKey 不能为空")
    val salt = decodeBase64(connectionSaltBase64)
    if (salt.size != SALT_BYTES) {
      throw FrameError(FRAME_MALFORMED, "connectionSalt 必须是 16 字节（防止 nonce 重用）")
    }

    keys = mapOf(
      P2D to hkdfSha256(ikm, salt, "$VERSION:$P2D:$deviceId".toByteArray(), KEY_BYTES),
      D2P to hkdfSha256(ikm, salt, "$VERSION:$D2P:$deviceId".toByteArray(), KEY_BYTES),
    )
    aadPrefix = mapOf(
      P2D to "$VERSION|$deviceId|p2d|".toByteArray(),
      D2P to "$VERSION|$deviceId|d2p|".toByteArray(),
    )
  }

  // ─────────────────────── 加解密 ───────────────────────

  /** 加密一条消息，返回可直接 JSON 化的帧 */
  fun seal(direction: String, plaintext: String): SealedFrame =
    seal(direction, plaintext.toByteArray(Charsets.UTF_8))

  fun seal(direction: String, plaintext: ByteArray): SealedFrame {
    val counter = sendCounter.getValue(direction)
    sendCounter[direction] = counter + 1

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.ENCRYPT_MODE,
      SecretKeySpec(keyOf(direction), "AES"),
      GCMParameterSpec(TAG_BYTES * 8, nonceFor(counter)),
    )
    cipher.updateAAD(aadFor(direction, counter))
    val ct = cipher.doFinal(plaintext)   // Java 的 doFinal 输出已含 tag

    return SealedFrame(n = counter.toString(), c = encodeBase64Url(ct))
  }

  /** 解密一条帧。异常都转成带 code 的 [FrameError] */
  fun open(direction: String, frame: SealedFrame): ByteArray {
    val counter = frame.n.toLongOrNull()
      ?: throw FrameError(FRAME_MALFORMED, "计数不是整数")
    if (counter < 0) throw FrameError(FRAME_MALFORMED, "计数必须非负")

    // 防重放：必须**严格大于**已见最大计数
    val seen = recvCounter.getValue(direction)
    if (counter <= seen) {
      throw FrameError(FRAME_REPLAYED, "计数 $counter 不新于已见 $seen，拒绝重放")
    }

    val payload = try {
      decodeBase64Url(frame.c)
    } catch (t: Throwable) {
      throw FrameError(FRAME_MALFORMED, "密文不是合法 base64url")
    }
    if (payload.size < TAG_BYTES) {
      throw FrameError(FRAME_MALFORMED, "密文短于 GCM tag 长度")
    }

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE,
      SecretKeySpec(keyOf(direction), "AES"),
      GCMParameterSpec(TAG_BYTES * 8, nonceFor(counter)),
    )
    cipher.updateAAD(aadFor(direction, counter))
    val plain = try {
      cipher.doFinal(payload)   // Java 接受「密文||tag」的合并形式
    } catch (t: Throwable) {
      // GCM 认证失败：密钥不对（换设备/方向）或密文被改动
      throw FrameError(FRAME_TAMPERED, "GCM 认证失败：密文、AAD 或密钥不匹配")
    }

    // 只有解密成功才推进计数 —— 否则攻击者用一个伪造帧就能把合法帧全挤掉
    // （「先污染窗口再拒绝真消息」的拒绝服务）
    recvCounter[direction] = counter
    return plain
  }

  /**
   * 派生出的会话密钥（十六进制，**仅供测试与排错**）。
   *
   * 为什么要暴露：跨语言互通排查时，「密钥派生错了」与「AAD/密文布局错了」
   * 会表现成同一个症状（GCM 认证失败）。有了它就能一句话定位是哪一类。
   * 只在同包测试里用，不参与任何线上路径。
   */
  internal fun derivedKeyHexForTest(direction: String): String =
    keyOf(direction).joinToString("") { "%02x".format(it) }

  /** 当前发送计数（诊断用） */
  fun sentCount(direction: String): Long = sendCounter.getValue(direction)

  /** 当前已接受的最大计数（诊断用） */
  fun acceptedCount(direction: String): Long = recvCounter.getValue(direction)

  // ─────────────────────── 内部 ───────────────────────

  private fun keyOf(direction: String): ByteArray =
    keys[direction] ?: throw FrameError(FRAME_MALFORMED, "未知方向 $direction")

  private fun nonceFor(counter: Long): ByteArray {
    val nonce = ByteArray(NONCE_BYTES)
    // 前 4 字节为零（默认值），后 8 字节大端计数 —— 与桌面 nonceFor 逐字节一致
    for (i in 0 until 8) {
      nonce[NONCE_BYTES - 1 - i] = ((counter ushr (8 * i)) and 0xFF).toByte()
    }
    return nonce
  }

  private fun aadFor(direction: String, counter: Long): ByteArray {
    val prefix = aadPrefix.getValue(direction)
    val out = ByteArray(prefix.size + 8)
    prefix.copyInto(out, 0)
    for (i in 0 until 8) {
      out[out.size - 1 - i] = ((counter ushr (8 * i)) and 0xFF).toByte()
    }
    return out
  }

  /**
   * HKDF-SHA256（RFC 5869）。Android API 26 没有 `javax.crypto.KDF`，
   * 所以手写 Extract + Expand —— 步骤少，但**参数顺序与桌面 `hkdfSync` 必须一致**：
   * `hkdfSync('sha256', ikm, salt, info, 32)`。
   */
  private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    // Extract: PRK = HMAC(salt, ikm)
    val prk = hmacSha256(salt, ikm)

    // Expand: T(1) = HMAC(PRK, info || 0x01)，长度 32 只需一轮
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    mac.update(info)
    mac.update(0x01)
    val t1 = mac.doFinal()

    return if (length <= t1.size) t1.copyOf(length) else expandMore(prk, t1, info, length)
  }

  /** 极少走到（只有 length > 32 时）。保留完整实现，避免将来要更长密钥时静默截断 */
  private fun expandMore(prk: ByteArray, t1: ByteArray, info: ByteArray, length: Int): ByteArray {
    val out = ByteArray(length)
    t1.copyInto(out, 0)
    var filled = t1.size
    var prev = t1
    var counter = 2
    while (filled < length) {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(prk, "HmacSHA256"))
      mac.update(prev)
      mac.update(info)
      mac.update(counter.toByte())
      prev = mac.doFinal()
      val n = minOf(prev.size, length - filled)
      prev.copyInto(out, filled, 0, n)
      filled += n
      counter++
    }
    return out
  }

  private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    // 空 salt 按 RFC 5869 用全零（与 Node 的 hkdfSync 行为一致）
    val k = if (key.isEmpty()) ByteArray(32) else key
    mac.init(SecretKeySpec(k, "HmacSHA256"))
    return mac.doFinal(data)
  }

  private fun decodeBase64(s: String): ByteArray = try {
    Base64.getDecoder().decode(s)
  } catch (t: Throwable) {
    throw FrameError(FRAME_MALFORMED, "不是合法 base64")
  }

  /**
   * base64url **无填充**（与 Node 的 `Buffer.toString('base64url')` 一致）。
   * Java 的 `getUrlDecoder()` 接受带填充与不带填充两种，但编码时必须去掉 `=`，
   * 否则手机发出的帧桌面认不出。
   */
  private fun decodeBase64Url(s: String): ByteArray = try {
    Base64.getUrlDecoder().decode(s)
  } catch (t: Throwable) {
    throw FrameError(FRAME_MALFORMED, "不是合法 base64url")
  }

  private fun encodeBase64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

// ───── RelayCredentials.kt ─────
/**
 * 手机侧配凭据持久化：长期 ECDH 私钥 + sessionKey。
 * ## 为什么必须持久化
 * 不存的话，App 每次启动都要重新扫码配对。而配对票据 TTL 只有 90 秒 ——
 * **一、落盘必须加密。** 手机私钥泄漏 = 别人能算出 sessionKey；
 * 两者都不能明文落盘。
 */
class RelayCredentials(
  private val context: Context,
  private val keyAlias: String = DEFAULT_KEY_ALIAS,
  private val fileName: String = DEFAULT_FILE,
  private val keyProvider: SecretKeyProvider = AndroidKeystoreProvider(),
) {

  companion object {
    const val DEFAULT_KEY_ALIAS = "morrow_relay_credentials"
    const val DEFAULT_FILE = "relay-credentials.bin"

    /** 文件格式版本：将来换格式时用它判断，而不是靠"解析失败"猜 */
    private const val FORMAT_VERSION = 1
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    const val ERR_NO_KEYSTORE = "KEYSTORE_UNAVAILABLE"
    const val ERR_SAVE_FAILED = "SAVE_FAILED"
    const val ERR_CORRUPT = "CREDENTIALS_CORRUPT"
  }

  /** 持久化的凭据。两个字段都不可空——只有私钥没有 sessionKey 用不了，反之亦然 */
  data class Stored(
    val deviceId: String,
    val privateKeyB64: String,
    val publicKeyB64: String,
    val sessionKeyB64: String,
    /** 桌面证书指纹，连接时用于固定校验 */
    val desktopFingerprint: String,
    /**
     * 中继地址（配对票里的 `relayHint` 解析而来）。
     */
    val relayHost: String? = null,
    val relayPort: Int? = null,
  ) {
    /** 地址是否齐备（齐备才允许发起连接） */
    val hasEndpoint: Boolean get() = !relayHost.isNullOrEmpty() && (relayPort ?: 0) in 1..65535
  }

  sealed class Load {
    data class Ok(val stored: Stored) : Load()

    /** 没有存过（首次安装 / 已清除）—— 不是错误 */
    data object Empty : Load()

    /**
     * 存过但读不出来（Keystore 密钥被系统撤销、文件损坏、格式版本不符）。
     *
     * **必须与 [Empty] 分开**：两者对用户的意义不同 ——
     * Empty 是「去配对」，Corrupt 是「之前的配对失效了，得重新配对，
     * 而且这可能是系统清过凭据」。混成一个会让「配对莫名失效」无从解释。
     */
    data class Unreadable(val reason: String, val detail: String) : Load()
  }

  sealed class SaveResult {
    data object Ok : SaveResult()
    data class Failed(val reason: String, val detail: String) : SaveResult()
  }

  // ─────────────────────── 读 ───────────────────────

  fun load(): Load {
    val f = file()
    if (!f.exists()) return Load.Empty

    // 读路径**不能创建密钥**：`keyOrCreate` 在密钥不存在时会新建一把，
    // 于是「Keystore 密钥被系统撤销」会变成「用新密钥解旧文件 → 解密失败 →
    // 报成 CORRUPT」。用户看到的是「凭据损坏」，真实原因却是密钥没了。
    // 这里单独判断密钥是否已存在，缺失就报 NO_KEYSTORE（含义明确）。
    if (!keyProvider.hasKey(keyAlias)) {
      return Load.Unreadable(
        ERR_NO_KEYSTORE,
        "凭据文件存在但 Keystore 密钥不可用（可能被系统清除）",
      )
    }
    val key = keyProvider.keyOrCreate(keyAlias) ?: return Load.Unreadable(
      ERR_NO_KEYSTORE,
      "凭据文件存在但 Keystore 密钥不可用（可能被系统清除）",
    )

    return try {
      val encrypted = f.readBytes()
      val plain = decrypt(key, encrypted)
      parse(plain)
    } catch (t: Throwable) {
      // 损坏时**不删文件**：删掉会让「为什么我的配对没了」彻底无线索，
      // 而保留文件至少能让下次覆盖写掉它。用户重新配对即可恢复。
      Load.Unreadable(ERR_CORRUPT, t.message ?: t::class.java.simpleName)
    }
  }

  private fun parse(plain: ByteArray): Load {
    // 极简格式：每行 key=value，值都是安全字符（base64 / 指纹 / deviceId）
    val map = LinkedHashMap<String, String>()
    for (line in String(plain, Charsets.UTF_8).split('\n')) {
      if (line.isBlank()) continue
      val i = line.indexOf('=')
      if (i <= 0) return Load.Unreadable(ERR_CORRUPT, "凭据行格式非法")
      map[line.substring(0, i)] = line.substring(i + 1)
    }

    val v = map["v"]?.toIntOrNull()
    if (v != FORMAT_VERSION) {
      return Load.Unreadable(ERR_CORRUPT, "凭据格式版本 $v != $FORMAT_VERSION")
    }

    fun need(name: String): String? = map[name]?.takeIf { it.isNotEmpty() }

    val deviceId = need("deviceId") ?: return Load.Unreadable(ERR_CORRUPT, "缺 deviceId")
    val priv = need("privateKey") ?: return Load.Unreadable(ERR_CORRUPT, "缺 privateKey")
    val pub = need("publicKey") ?: return Load.Unreadable(ERR_CORRUPT, "缺 publicKey")
    val session = need("sessionKey") ?: return Load.Unreadable(ERR_CORRUPT, "缺 sessionKey")
    val fingerprint = need("desktopFingerprint")
      ?: return Load.Unreadable(ERR_CORRUPT, "缺 desktopFingerprint")
    // 地址可有可无（票据的 relayHint 可选）。有 host 就必须有 port 且合法 ——
    // 只写一半的地址比没有更危险：它会让人以为配好了。
    val relayHost = map["relayHost"]?.takeIf { it.isNotEmpty() }
    val relayPort = map["relayPort"]?.toIntOrNull()
    if (relayHost != null && (relayPort == null || relayPort !in 1..65535)) {
      return Load.Unreadable(ERR_CORRUPT, "有 relayHost 却没有合法的 relayPort")
    }

    // 形状校验：sessionKey 必须是 32 字节。
    // 不校验的话，一个被截断的 sessionKey 会一路传到 SecureChannel 才炸，
    // 而那时的错误信息（GCM 认证失败）完全指不出根因。
    val sessionBytes = runCatching { Base64.getDecoder().decode(session) }.getOrNull()
      ?: return Load.Unreadable(ERR_CORRUPT, "sessionKey 不是合法 base64")
    if (sessionBytes.size != 32) {
      return Load.Unreadable(ERR_CORRUPT, "sessionKey 必须是 32 字节，实际 ${sessionBytes.size}")
    }

    val pubBytes = runCatching { Base64.getDecoder().decode(pub) }.getOrNull()
      ?: return Load.Unreadable(ERR_CORRUPT, "publicKey 不是合法 base64")
    if (pubBytes.size != 65 || pubBytes[0] != 0x04.toByte()) {
      return Load.Unreadable(ERR_CORRUPT, "publicKey 必须是 65 字节未压缩点")
    }

    return Load.Ok(Stored(deviceId, priv, pub, session, fingerprint, relayHost, relayPort))
  }

  // ─────────────────────── 写 ───────────────────────

  fun save(stored: Stored): SaveResult {
    val key = keyProvider.keyOrCreate(keyAlias) ?: return SaveResult.Failed(
      ERR_NO_KEYSTORE,
      "Keystore 不可用，拒绝以明文持久化凭据",
    )

    val plain = buildString {
      append("v=").append(FORMAT_VERSION).append('\n')
      append("deviceId=").append(stored.deviceId).append('\n')
      append("privateKey=").append(stored.privateKeyB64).append('\n')
      append("publicKey=").append(stored.publicKeyB64).append('\n')
      append("sessionKey=").append(stored.sessionKeyB64).append('\n')
      append("desktopFingerprint=").append(stored.desktopFingerprint).append('\n')
      // 地址缺失时不写空行：读路径用 `takeIf { isNotEmpty }` 判断，
      // 写一个空的 `relayHost=` 会让「没有地址」与「地址是空串」混起来。
      //
      // 这两行在 E1b 加字段时漏了 —— 读路径能读、写路径不写，
      // 表现为「配对成功但重启后地址丢了，连不上」。测试是在
      // E2b 的 `配对成功后凭据真的写盘且能读回` 里抓到的。
      stored.relayHost?.let { append("relayHost=").append(it).append('\n') }
      stored.relayPort?.let { append("relayPort=").append(it).append('\n') }
    }.toByteArray(Charsets.UTF_8)

    return try {
      val encrypted = encrypt(key, plain)
      val target = file()
      target.parentFile?.mkdirs()
      // 临时文件 + 原子重命名：中途被杀不会留下半个文件
      val tmp = File(target.parentFile, "${target.name}.tmp")
      tmp.writeBytes(encrypted)
      if (!tmp.renameTo(target)) {
        // rename 失败（跨分区等）时退化为直接写，但**先删临时文件**
        target.writeBytes(encrypted)
        tmp.delete()
      }
      SaveResult.Ok
    } catch (t: Throwable) {
      SaveResult.Failed(ERR_SAVE_FAILED, t.message ?: t::class.java.simpleName)
    }
  }

  /** 清除凭据（撤销配对 / 用户主动断开） */
  fun clear(): Boolean {
    val f = file()
    val gone = if (f.exists()) f.delete() else true
    // 同时删掉 Keystore 密钥：留着它等于留着一把能解「将来某份旧文件」的钥匙
    keyProvider.delete(keyAlias)
    return gone
  }

  /** 凭据文件是否存在（用于判断「配过但读不出来」与「从没配过」） */
  fun exists(): Boolean = file().exists()

  // ─────────────────────── Keystore ───────────────────────

  private fun file(): File = File(context.filesDir, fileName)

  // ─────────────────────── AES-GCM ───────────────────────

  /** 输出格式：`1 字节版本 || 12 字节 IV || 密文+tag` */
  private fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val iv = cipher.iv
    val ct = cipher.doFinal(plain)
    val out = ByteArray(1 + iv.size + ct.size)
    out[0] = FORMAT_VERSION.toByte()
    iv.copyInto(out, 1)
    ct.copyInto(out, 1 + iv.size)
    return out
  }

  private fun decrypt(key: SecretKey, data: ByteArray): ByteArray {
    require(data.size > 1 + IV_BYTES) { "凭据文件过短" }
    val ver = data[0].toInt()
    require(ver == FORMAT_VERSION) { "凭据文件版本 $ver 不受支持" }
    val iv = data.copyOfRange(1, 1 + IV_BYTES)
    val ct = data.copyOfRange(1 + IV_BYTES, data.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    return cipher.doFinal(ct)
  }
}

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

// ───── SecretKeyProvider.kt ─────
/**
 * 对称密钥来源。生产用 Android Keystore，测试用 JVM 内存密钥。
 * 这一条必须写进报告，不能用「有测试」含糊带过。
 */
interface SecretKeyProvider {
  /**
   * 取已有密钥；没有就创建。返回 null 表示**不可用** ——
   * 调用方必须拒绝持久化，不能退化成明文写盘。
   */
  fun keyOrCreate(alias: String): SecretKey?

  /**
   * 密钥是否已存在（**不创建**）。
   *
   * 读路径必须用它而不是 `keyOrCreate`：后者在缺失时会新建一把，
   * 于是「密钥被系统撤销」会被误报成「凭据损坏」。
   */
  fun hasKey(alias: String): Boolean

  /** 删除密钥（清除凭据时调用） */
  fun delete(alias: String)

  /** 是否可用（用于启动时判断要不要提示用户「本机无法安全存储配对信息」） */
  fun isAvailable(): Boolean
}

/**
 * Android Keystore 实现。
 * 注意这里刻意**不用** `setUserAuthenticationRequired(true)`：
 * 安全性由「密钥不可导出 + 只在本机」承担，而不是靠每次认证。
 */
class AndroidKeystoreProvider : SecretKeyProvider {

  companion object {
    private const val PROVIDER = "AndroidKeyStore"
  }

  private fun keyStore(): java.security.KeyStore? = runCatching {
    java.security.KeyStore.getInstance(PROVIDER).apply { load(null) }
  }.getOrNull()

  override fun isAvailable(): Boolean = keyStore() != null

  override fun hasKey(alias: String): Boolean {
    val ks = keyStore() ?: return false
    return runCatching { ks.containsAlias(alias) }.getOrDefault(false)
  }

  override fun keyOrCreate(alias: String): SecretKey? {
    val ks = keyStore() ?: return null

    val existing = runCatching { ks.getKey(alias, null) as? SecretKey }.getOrNull()
    if (existing != null) return existing

    return runCatching {
      val spec = android.security.keystore.KeyGenParameterSpec.Builder(
        alias,
        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
          android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(false)
        .build()

      val gen = javax.crypto.KeyGenerator.getInstance(
        android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, PROVIDER,
      )
      gen.init(spec)
      gen.generateKey()
      // 生成后重新取，确保拿到的是 Keystore 持久化的那一份
      java.security.KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as? SecretKey
    }.getOrNull()
  }

  override fun delete(alias: String) {
    runCatching {
      val ks = java.security.KeyStore.getInstance(PROVIDER).apply { load(null) }
      if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }
  }
}

/**
 * 仅供测试的 JVM 密钥来源：每次 `keyOrCreate` 返回同一把内存密钥，
 * 行为与 Keystore 一致（同 alias 同密钥、可删除）。
 *
 * 用固定字节而不是随机：让「加密结果不同」这种失败只可能来自 IV，
 * 而不是密钥本身，排错时少一个变量。
 */
class InMemoryKeyProvider(
  /** 模拟「Keystore 不可用」（用于验证拒绝明文降级的路径） */
  private val available: Boolean = true,
) : SecretKeyProvider {

  private val keys = mutableMapOf<String, SecretKey>()

  override fun isAvailable(): Boolean = available

  override fun hasKey(alias: String): Boolean = available && keys.containsKey(alias)

  override fun keyOrCreate(alias: String): SecretKey? {
    if (!available) return null
    return keys.getOrPut(alias) {
      javax.crypto.spec.SecretKeySpec(ByteArray(32) { (0x40 + it).toByte() }, "AES")
    }
  }

  override fun delete(alias: String) {
    keys.remove(alias)
  }

  /** 测试用：清空内存密钥，模拟「Keystore 密钥被系统撤销」 */
  fun forgetAll() = keys.clear()
}
// ── 原 relay/PairingFlow.kt ──

/**
 * 配对流程的状态机（纯逻辑，不碰 UI、不碰网络）。
 * 更重要的是**失败原因必须可判定**。用户看到「配对失败」时，
 * 有价值的只有四种情形：票据过期（重扫就行）/ 码错了（再输一次）/
 */
class PairingFlow(
  /** 票据 TTL。桌面 `TICKET_TTL_MS` 是 90 秒，这里必须一致 */
  private val ticketTtlMillis: Long = 90_000,
  /** 允许的码错误次数。超过就判失败，避免无限试码 */
  private val maxCodeAttempts: Int = 3,
) {

  /** 配对失败的六种可判定原因 */
  enum class Failure(val code: String, val userHint: String, val canRetryFromScan: Boolean) {
    /** 二维码不是合法票据（坏 base64 / 坏 JSON / 缺字段 / 含密钥字段） */
    TICKET_INVALID(
      "TICKET_INVALID",
      "这个二维码不是 Morrow 的配对码",
      canRetryFromScan = true,
    ),

    /** 票据已过期（TTL 90 秒）。这是最常发生的一种：用户扫码慢或在输码时接了个电话 */
    TICKET_EXPIRED(
      "TICKET_EXPIRED",
      "配对码已过期（有效期 90 秒），请回电脑上重新生成",
      canRetryFromScan = true,
    ),

    /** 六位码输错，且已用完尝试次数 */
    CODE_MISMATCH(
      "CODE_MISMATCH",
      "六位码不对。请核对电脑上显示的数字后重新配对",
      canRetryFromScan = true,
    ),

    /** 桌面明确拒绝（票据已被使用 / nonce 不匹配 / 签名不过） */
    DESKTOP_REJECTED(
      "DESKTOP_REJECTED",
      "电脑拒绝了这次配对。请确认电脑上仍显示着配对码",
      canRetryFromScan = true,
    ),

    /** 凭据写盘失败（Keystore 不可用 / 磁盘满） */
    SAVE_FAILED(
      "SAVE_FAILED",
      "配对成功但保存失败，请检查设备存储后重试",
      canRetryFromScan = true,
    ),

    /**
     * 用户主动取消。
     *
     * `canRetryFromScan = false`：这不是错误，界面不该给「重试」按钮，
     * 应该回到未配对状态。
     */
    CANCELLED("CANCELLED", "已取消配对", canRetryFromScan = false),
  }

  sealed class State {
    /** 什么都没开始 */
    object Idle : State()

    /** 相机开着，等识别二维码 */
    object Scanning : State()

    /**
     * 票据已解析。
     *
     * 携带 `expiresAtMillis`：界面要显示倒计时（「还有 62 秒」），
     * 否则用户不知道为什么突然说「过期了」。
     */
    data class TicketReady(
      val ticket: EcdhPairing.Ticket,
      val expectedCode: String,
      val expiresAtMillis: Long,
    ) : State()

    /** 等用户输六位码（或已经在输） */
    data class CodeEntering(
      val ticket: EcdhPairing.Ticket,
      val expectedCode: String,
      val expiresAtMillis: Long,
      val attemptsLeft: Int,
      /** 上一次输错时的提示，null = 还没输错过 */
      val lastMismatch: Boolean = false,
    ) : State()

    /** 码对上了，等桌面确认 */
    data class Verifying(
      val ticket: EcdhPairing.Ticket,
      val expectedCode: String,
    ) : State()

    /** 桌面确认了，正在写凭据 */
    data class Saving(
      val ticket: EcdhPairing.Ticket,
      val expectedCode: String,
      val sessionKey: String,
    ) : State()

    /** 完成 */
    data class Paired(val deviceId: String) : State()

    /** 终态失败。`atMillis` 用于界面显示「刚才出的问题」而不是陈年旧账 */
    data class Failed(val failure: Failure, val detail: String, val atMillis: Long) : State()
  }

  var state: State = State.Idle
    private set

  // ─────────────────────── 转换 ───────────────────────

  /** 用户点「去配对」/ 界面进入扫码页 */
  fun beginScan() {
    state = State.Scanning
  }

  /**
   * 相机识别到二维码内容。
   *
   * 解析失败**不抛出**，而是进 `Failed` —— 抛出会让调用方在 UI 回调里
   * 处理异常，而这类失败是常规路径（用户很容易扫到别的二维码）。
   */
  fun onQrScanned(qrText: String, identity: EcdhPairing.Identity, nowMillis: Long) {
    if (state !is State.Scanning && state !is State.Failed) {
      // 只有扫码中（或刚失败准备重扫）才接受二维码。
      // 在校验码阶段又扫到一张票，说明用户手里有两张票 —— 换票会让
      // 已经输了一半的码失去意义，宁可忽略。
      return
    }
    val ticket = try {
      EcdhPairing.parseTicket(qrText, nowMillis)
    } catch (e: EcdhPairing.PairingException) {
      state = State.Failed(
        failure = if (e.code == EcdhPairing.TICKET_EXPIRED) Failure.TICKET_EXPIRED else Failure.TICKET_INVALID,
        detail = e.message ?: e.code,
        atMillis = nowMillis,
      )
      return
    }

    // 票据里的 pubKey 必须是**本机的公钥**。
    //
    // 不校验的后果很具体：若用户在电脑上点了「配另一台手机」，或换机后
    // 用旧二维码，桌面签发的 pubKey 不是这台手机的 → 本机算出的共享秘密
    // 与桌面不同 → 六位码永远对不上。而界面只会说「六位码不对」，
    // 用户会反复重输同一串正确数字，永远配不上。
    //
    // 报 TICKET_INVALID（而不是 CODE_MISMATCH）：问题在票据，不在用户输入。
    if (ticket.pubKey != identity.publicKeyB64) {
      state = State.Failed(
        Failure.TICKET_INVALID,
        "这张配对码是给另一台手机的（票据里的公钥与本机不符），请在电脑上重新生成",
        nowMillis,
      )
      return
    }

    // 用票据 + 自己的私钥算出「本地期望的六位码」与 sessionKey。
    // 算不出来（桌面公钥非法）也归为 TICKET_INVALID —— 那是票据的问题，不是用户的问题。
    val pair = try {
      EcdhPairing.pair(ticket, identity)
    } catch (e: EcdhPairing.PairingException) {
      state = State.Failed(Failure.TICKET_INVALID, e.message ?: e.code, nowMillis)
      return
    }

    state = State.TicketReady(
      ticket = ticket,
      expectedCode = pair.code,
      expiresAtMillis = ticket.exp,
    )
  }

  /** 用户确认要输码（也可以直接进 CodeEntering —— 这只是个显式的过渡） */
  fun beginCodeEntry() {
    val s = state as? State.TicketReady ?: return
    state = State.CodeEntering(
      ticket = s.ticket,
      expectedCode = s.expectedCode,
      expiresAtMillis = s.expiresAtMillis,
      attemptsLeft = maxCodeAttempts,
    )
  }

  /**
   * 提交六位码。
   *
   * @param code 用户输入的六个数字（**已去空格**；去空格在 UI 层做）
   * @return 比对结果，供 UI 决定是否抖一下输入框
   */
  fun submitCode(code: String, nowMillis: Long, sessionKey: String): Boolean {
    val s = state as? State.CodeEntering ?: return false

    // 先判过期：票据过期就连码都不用比了。
    // 若先比码，用户输对了却因为过期而失败，会得到「码错了」的提示 —— 那是错的提示。
    if (nowMillis > s.expiresAtMillis) {
      state = State.Failed(
        Failure.TICKET_EXPIRED,
        "票据在输码期间过期（exp=${s.expiresAtMillis} now=$nowMillis）",
        nowMillis,
      )
      return false
    }

    if (code != s.expectedCode) {
      val left = s.attemptsLeft - 1
      state = if (left <= 0) {
        State.Failed(
          Failure.CODE_MISMATCH,
          "六位码连续输错 $maxCodeAttempts 次",
          nowMillis,
        )
      } else {
        s.copy(attemptsLeft = left, lastMismatch = true)
      }
      return false
    }

    state = State.Verifying(s.ticket, s.expectedCode)
    // 记下 sessionKey 供 Saving 用。放在这里而不是再算一次：
    // 重算需要私钥，而这一层不该持有私钥。
    pendingSessionKey = sessionKey
    return true
  }

  /** 桌面确认通过 */
  fun onVerified() {
    val s = state as? State.Verifying ?: return
    val key = pendingSessionKey ?: run {
      state = State.Failed(Failure.SAVE_FAILED, "内部状态丢失 sessionKey", 0)
      return
    }
    state = State.Saving(s.ticket, s.expectedCode, key)
  }

  /**
   * 桌面拒绝（票据已用 / nonce 不匹配）。
   */
  fun onRejected(detail: String, nowMillis: Long) {
    if (state !is State.Verifying && state !is State.Saving) return
    pendingSessionKey = null
    state = State.Failed(Failure.DESKTOP_REJECTED, detail, nowMillis)
  }

  /** 凭据已落盘 */
  fun onSaved() {
    val s = state as? State.Saving ?: return
    pendingSessionKey = null
    state = State.Paired(s.ticket.deviceId)
  }

  /** 凭据写盘失败 */
  fun onSaveFailed(detail: String, nowMillis: Long) {
    // Verifying 也要接：票据缺指纹、指纹格式非法这类问题在**进确认之前**就查出来了，
    // 那时状态是 Verifying（submitCode 成功后立刻置的），还不是 Saving。
    // 早先只认 Saving，于是这些失败被静默吞掉，界面停在「正在等电脑确认…」不动 ——
    // 用户既没拿到错误、也没法重试，是比「失败」更糟的状态。
    if (state !is State.Verifying && state !is State.Saving) return
    pendingSessionKey = null
    state = State.Failed(Failure.SAVE_FAILED, detail, nowMillis)
  }

  /** 用户取消（返回键 / 关闭扫码页） */
  fun cancel(nowMillis: Long) {
    pendingSessionKey = null
    state = State.Failed(Failure.CANCELLED, "用户取消", nowMillis)
  }

  /**
   * 回到 Idle 重新开始。
   *
   * 「重扫」走这条路：过期/失败之后调用 reset 再 beginScan。
   * 单独提供（而不是让 beginScan 自己清）是为了让界面能区分
   * 「第一次进扫码页」与「失败后重来」——后者要清掉错误提示。
   */
  fun reset() {
    pendingSessionKey = null
    state = State.Idle
  }

  private var pendingSessionKey: String? = null

  // ─────────────────────── 只读派生 ───────────────────────

  /** 是否处于可显示「去配对」入口的状态（即：还没配上、也还没在配） */
  val canStartPairing: Boolean
    get() = when (val s = state) {
      is State.Idle -> true
      is State.Failed -> s.failure != Failure.CANCELLED
      else -> false
    }

  /** 剩余有效秒数（仅 TicketReady / CodeEntering 有意义） */
  fun remainingSeconds(nowMillis: Long): Long? = when (val s = state) {
    is State.TicketReady -> ((s.expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
    is State.CodeEntering -> ((s.expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
    else -> null
  }

  /**
   * 是否该提示「已过期」。
   *
   * 与 `remainingSeconds() == 0` 等价，但单独暴露是因为界面每帧都要判断它，
   * 而算一次除法比算两次便宜 —— 更重要的是让「过期提示」有一个具名入口，
   * 避免各处自己写 `exp - now <= 0` 时把 `<` 写成 `<=`。
   */
  fun isExpired(nowMillis: Long): Boolean = when (val s = state) {
    is State.TicketReady -> nowMillis > s.expiresAtMillis
    is State.CodeEntering -> nowMillis > s.expiresAtMillis
    else -> false
  }
}

// ───── PairConfirmClient.kt ─────
/**
 * 配对确认：把票据 nonce 报给桌面，让桌面把本机写进设备表。
 *
 * ## 为什么这一步不能省
 *
 * 六位码是两端**各自算**出来的，桌面上那张票签好后一直等着被用掉。
 * 手机在本地比对通过、把凭据写盘，这只是「手机认为自己配上了」——
 * 桌面那边设备表还是空的，所以：
 * - 设置页的「已配对的手机」永远显示「还没有配对的手机」；
 * - 手机拿新 sessionKey 去 hello 会被 4401 拒（桌面的 `requirePaired` 找不到它）。
 *
 * ## 为什么是独立连接、握手前的一帧
 *
 * 桌面在 `hello` 之前只接受 `pair-confirm`。走已鉴权通道会死锁：
 * 要确认才能建通道，要建通道才能确认。所以这里自己开一条 TLS + WebSocket，
 * 发一帧，收一帧，关掉。**证书固定照旧生效** —— 指纹来自票据，
 * 与正式连接用的是同一套校验（`SSLSocketFactoryFactory`）。
 */
object PairConfirmClient {

  /** 结果：成功、桌面拒绝、或者网络层失败（三种处置完全不同） */
  sealed class Outcome {
    /** 桌面已把本机写入设备表 */
    data object Confirmed : Outcome()

    /** 桌面明确拒绝（票据已用 / 过期 / 不存在）。必须回电脑重新生成配对码。 */
    data class Rejected(val reason: String) : Outcome()

    /**
     * 没连上或没收到回答（网络断、端口不通、超时）。
     *
     * 与 Rejected 分开：这种情况重试可能有用，而 Rejected 重试一万次也一样。
     */
    data class Unreachable(val reason: String) : Outcome()
  }

  /**
   * 发送确认并等回答。**阻塞调用**，必须在 IO 线程上跑。
   *
   * @param expectedFingerprint 票据里的桌面证书指纹。没有它就无法做证书固定，
   *   调用方应当在此之前就拒绝配对（见 MainActivity 的保存前置校验）。
   */
  fun confirm(
    host: String,
    port: Int,
    expectedFingerprint: String,
    nonce: String,
    expectedDeviceId: String,
    connectTimeoutMs: Int = 8_000,
    readTimeoutMs: Int = 10_000,
  ): Outcome {
    var socket: SSLSocket? = null
    try {
      val factory = RelaySocketClient.SSLSocketFactoryFactory.create(expectedFingerprint, null)
      val raw = factory.createSocket() as SSLSocket
      socket = raw
      raw.soTimeout = readTimeoutMs
      raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
      raw.startHandshake()

      val output = raw.outputStream
      val input = raw.inputStream

      // WebSocket 握手：与正式连接同一条路径（同一个桌面服务端）
      val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
      output.write(WebSocketFrames.handshakeRequest(host, port, "/", key).toByteArray(Charsets.US_ASCII))
      output.flush()
      val responseText = readHttpResponseHead(input)
      WebSocketFrames.verifyHandshakeResponse(responseText, key)

      // 发确认帧（明文：桌面此时还没有会话密钥）
      WebSocketFrames.writeFrame(
        output,
        WebSocketFrames.OP_TEXT,
        RelayProtocol.pairConfirmFrame(nonce).toByteArray(Charsets.UTF_8),
        SecureRandom(),
        masked = true,
      )

      // 等回答。桌面成功时回 pair-confirm-ok；失败时直接关连接。
      while (true) {
        val frame = WebSocketFrames.read(input, expectMasked = false)
        when (frame.opcode) {
          WebSocketFrames.OP_TEXT -> {
            val text = String(frame.payload, Charsets.UTF_8)
            return if (RelayProtocol.parsePairConfirmOk(text, expectedDeviceId)) {
              Outcome.Confirmed
            } else {
              // 收到了帧但不是确认成功：桌面协议不一致（版本错配）
              Outcome.Rejected("桌面的回答不是配对确认（可能是版本不一致）")
            }
          }
          WebSocketFrames.OP_PING -> {
            WebSocketFrames.writeFrame(
              output,
              WebSocketFrames.OP_PONG,
              frame.payload,
              SecureRandom(),
              masked = true,
            )
          }
          WebSocketFrames.OP_PONG -> Unit
          WebSocketFrames.OP_CLOSE -> {
            val (code, reason) = WebSocketFrames.parseClose(frame.payload)
            return Outcome.Rejected(
              "电脑拒绝了配对（$code${if (reason.isBlank()) "" else " $reason"}）",
            )
          }
          else -> Unit
        }
      }
    } catch (e: java.security.cert.CertificateException) {
      // 指纹不匹配是中间人信号，不是「网络不好」
      return Outcome.Rejected(e.message ?: "电脑证书指纹不匹配")
    } catch (e: javax.net.ssl.SSLHandshakeException) {
      // JSSE 会把 TrustManager 抛的 CertificateException 包进握手异常，
      // 原始的 CertificateException 不会直接冒出来。指纹不匹配属于终局失败，
      // 归到 Unreachable 会让界面劝用户「检查网络」，而真正该做的是重新配对。
      var cause: Throwable? = e
      while (cause != null) {
        if (cause is java.security.cert.CertificateException) {
          return Outcome.Rejected(cause.message ?: "电脑证书指纹不匹配")
        }
        cause = cause.cause
      }
      return Outcome.Unreachable("TLS 握手失败：${e.message ?: e::class.java.simpleName}")
    } catch (e: Throwable) {
      return Outcome.Unreachable(e.message ?: e::class.java.simpleName)
    } finally {
      try {
        socket?.close()
      } catch (_: Throwable) {
      }
    }
  }

  /** 读 HTTP 响应头（与 RelaySocketClient 的实现同一规则，见那里的说明） */
  private fun readHttpResponseHead(input: InputStream): String {
    val sb = StringBuilder()
    var matched = 0
    while (true) {
      val b = input.read()
      if (b < 0) throw IOException("握手期间连接关闭")
      sb.append(b.toChar())
      matched = when {
        matched == 0 && b == '\r'.code -> 1
        matched == 1 && b == '\n'.code -> 2
        matched == 2 && b == '\r'.code -> 3
        matched == 3 && b == '\n'.code -> return sb.toString()
        b == '\r'.code -> 1
        else -> 0
      }
    }
  }
}
// ── 原 relay/RelayConnection.kt ──

// ───── RelaySocketClient.kt ─────
/**
 * 手机侧 relay 客户端：TLS 连接 → WebSocket 握手 → hello 鉴权 → welcome → 全帧解密。
 * ## 证书固定：为什么必须显式给指纹
 * 所以指纹必须**来自配对时的可信渠道**（配对票据 / 用户确认），
 */
/**
 * relay 客户端的最小契约。
 * 必须能用一个按脚本执行的替身来测 —— 否则要么起真 socket（慢且难控制
 * **只有 3 个方法**：连接、关闭、是否在跑。其余一切（TLS、帧、解密）
 */
interface RelayClient {
  /** 阻塞跑到连接结束。返回前必须调用过 listener.onClosed */
  fun runBlockingLoop()

  /** 关闭连接（可从任意线程调用，用于打断阻塞读） */
  fun close()

  /**
   * 上行发送一条明文 JSON（实现内部用 phone-to-desktop 方向加密）。
   *
   * 默认 false：只读的测试假实现不必实现它。生产实现是 [RelaySocketClient.send]。
   */
  fun send(json: String): Boolean = false
}

class RelaySocketClient(
  private val host: String,
  private val port: Int,
  private val deviceId: String,
  private val sessionKeyBase64: String,
  /** 桌面证书指纹（`SHA256:…` 或裸 hex/base64），来自配对 */
  private val expectedFingerprint: String,
  private val listener: Listener,
  /** 已应用到的最大事件序号；重连时告诉桌面从哪儿补。0 = 需要全量快照 */
  private val lastSeq: Long = 0L,
  private val random: Random = SecureRandom(),
  private val connectTimeoutMs: Int = 8_000,
  private val readTimeoutMs: Int = 30_000,
) : RelayClient {

  interface Listener {
    /** welcome 已解析、SecureChannel 已就绪 */
    fun onReady(welcome: RelayProtocol.Welcome)

    /** 一条解密后的 JSON（桌面事件信封） */
    fun onEvent(json: String)

    /** 连接结束。`shouldRetry` 由协议层决定 */
    fun onClosed(info: RelayProtocol.DisconnectInfo)
  }

  class FingerprintMismatch(message: String) : IOException(message)

  /** 最近一次实际观察到的服务器指纹（诊断用，握手失败时能打印出来对照） */
  @Volatile
  var observedFingerprint: String? = null
    private set

  @Volatile
  private var socket: SSLSocket? = null
  @Volatile
  private var output: OutputStream? = null
  @Volatile
  private var input: InputStream? = null
  @Volatile
  private var channel: SecureChannel? = null

  /** 写帧互斥：send 可能从主线程调用，而读循环在 IO 线程。 */
  private val writeLock = Any()

  /**
   * 上行发送一条明文 JSON（内部用 phone-to-desktop 方向加密）。
   *
   * 返回 false 表示「现在发不出去」：没有连接、握手未完成或 socket 已关闭。
   * **不排队** —— 排队会让重连后补发一条用户早已放弃的审批/停止指令，
   * 那比直接失败更危险（用户以为没发出去，实际上几秒后执行了）。
   */
  override fun send(json: String): Boolean {
    val ch = channel ?: return false
    val out = output ?: return false
    synchronized(writeLock) {
      return try {
        val sealed = ch.seal(SecureChannel.P2D, json)
        // n/c 都是 base64url，不含引号/控制字符，手拼是安全的。
        val frame = "{\"n\":\"" + sealed.n + "\",\"c\":\"" + sealed.c + "\"}"
        WebSocketFrames.writeFrame(
          out,
          WebSocketFrames.OP_TEXT,
          frame.toByteArray(Charsets.UTF_8),
          random,
          masked = true,
        )
        out.flush()
        true
      } catch (_: Throwable) {
        false
      }
    }
  }

  /** 关闭连接。可从任意线程调用（socket.close 会让阻塞中的 read 抛异常） */
  @Volatile
  private var closedByUs = false

  override fun close() {
    closedByUs = true
    try {
      socket?.close()
    } catch (_: Throwable) {
    }
  }

  /**
   * 阻塞式运行到连接结束。返回后不再有任何回调。
   *
   * 任何阶段失败都归到一次 `onClosed`，且分类由协议层给出 ——
   * 调用方只需看 `shouldRetry`。
   */
  override fun runBlockingLoop() {
    var info: RelayProtocol.DisconnectInfo
    try {
      connectAndHandshake()
      info = readLoop()
    } catch (e: FingerprintMismatch) {
      info = RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.UNAUTHORIZED, e.message ?: "证书指纹不匹配")
    } catch (e: CloseSignal) {
      // 握手阶段被服务端用 close 帧拒绝（最典型：hello 验签失败 → 4401）。
      // 必须走协议分类，不能落到下面的「网络错误」分支 ——
      // 否则未授权会被当成可重连，而 4401 恰恰是「不该再试」的信号。
      info = RelayProtocol.classifyClose(e.code, e.reason)
    } catch (e: RelayProtocol.ProtocolError) {
      info = RelayProtocol.classifyFailure(e)
    } catch (e: Throwable) {
      // 证书校验失败会被 TLS 栈包成 SSLHandshakeException，
      // 不能按「网络抖动」处理 —— 否则指纹不匹配会变成无限重连，
      // 而指纹不匹配正是中间人的信号。
      val certFailure = findCertificateCause(e)
      info = when {
        closedByUs -> RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.RETRYABLE, "主动关闭")
        certFailure != null -> RelayProtocol.DisconnectInfo(
          RelayProtocol.Disconnect.UNAUTHORIZED,
          certFailure.message ?: "证书校验失败",
        )
        else -> RelayProtocol.classifyFailure(e)
      }
    } finally {
      closeQuietly()
    }
    listener.onClosed(info)
  }

  /** TLS 栈会把证书错误层层包装，沿着 cause 链找出来 */
  private fun findCertificateCause(t: Throwable?): java.security.cert.CertificateException? {
    var cur = t
    var depth = 0
    while (cur != null && depth < 8) {
      if (cur is java.security.cert.CertificateException) return cur
      cur = cur.cause
      depth++
    }
    return null
  }

  // ─────────────────────── 连接与握手 ───────────────────────

  private fun connectAndHandshake() {
    val raw = SSLSocketFactoryFactory.create(expectedFingerprint, this)
    val s = raw.createSocket() as SSLSocket
    socket = s
    s.soTimeout = readTimeoutMs
    s.connect(InetSocketAddress(host, port), connectTimeoutMs)
    s.startHandshake()

    output = s.outputStream
    input = s.inputStream

    // ── WebSocket 握手 ──
    val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
    output!!.write(WebSocketFrames.handshakeRequest(host, port, "/", key).toByteArray(Charsets.US_ASCII))
    output!!.flush()

    val responseText = readHttpResponseHead(input!!)
    WebSocketFrames.verifyHandshakeResponse(responseText, key)

    // ── hello 鉴权 ──
    val hello = RelayProtocol.helloFrame(sessionKeyBase64, deviceId, System.currentTimeMillis(), lastSeq)
    WebSocketFrames.writeFrame(output!!, WebSocketFrames.OP_TEXT, hello.toByteArray(Charsets.UTF_8), random, masked = true)

    // ── 等 welcome（明文）──
    val welcome = awaitWelcome()
    listener.onReady(welcome)
    channel = SecureChannel(sessionKeyBase64, welcome.salt, deviceId)
  }

  private fun awaitWelcome(): RelayProtocol.Welcome {
    while (true) {
      val frame = WebSocketFrames.read(input!!, expectMasked = false)
      when (frame.opcode) {
        WebSocketFrames.OP_TEXT -> {
          val text = String(frame.payload, Charsets.UTF_8)
          // 只接受 welcome 作为第一帧。若先来加密帧，说明桌面状态错乱 —— 报错而不是硬解
          return try {
            RelayProtocol.parseWelcome(text)
          } catch (e: RelayProtocol.ProtocolError) {
            throw RelayProtocol.ProtocolError(
              "WELCOME_EXPECTED",
              "握手后第一帧必须是 welcome：${e.message}",
            )
          }
        }
        WebSocketFrames.OP_PING -> pong(frame.payload)
        WebSocketFrames.OP_PONG -> Unit
        WebSocketFrames.OP_CLOSE -> {
          val (code, reason) = WebSocketFrames.parseClose(frame.payload)
          throw CloseSignal(code, reason)
        }
        else -> Unit
      }
    }
  }

  // ─────────────────────── 读循环 ───────────────────────

  private fun readLoop(): RelayProtocol.DisconnectInfo {
    var lastSeq = 0L
    val pending = StringBuilder()   // 分片消息的重组缓冲

    while (true) {
      val frame = try {
        WebSocketFrames.read(input!!, expectMasked = false)
      } catch (e: java.io.EOFException) {
        return RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.RETRYABLE, "连接被对端关闭")
      } catch (e: WebSocketFrames.FrameError) {
        // 帧格式错误：可能是中间人改写，不重连
        return RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.TAMPERED, "帧格式错误：${e.message}")
      }

      when (frame.opcode) {
        WebSocketFrames.OP_PING -> {
          pong(frame.payload)
          continue
        }
        WebSocketFrames.OP_PONG -> continue
        WebSocketFrames.OP_CLOSE -> {
          val (code, reason) = WebSocketFrames.parseClose(frame.payload)
          // 回一个 close 帧（RFC 6455 §5.5.1 要求）
          try {
            WebSocketFrames.writeFrame(
              output!!, WebSocketFrames.OP_CLOSE,
              WebSocketFrames.encodeClose(code, ""), random, masked = true,
            )
          } catch (_: Throwable) {
          }
          return RelayProtocol.classifyClose(code, reason)
        }
        WebSocketFrames.OP_TEXT, WebSocketFrames.OP_CONTINUATION -> {
          pending.append(String(frame.payload, Charsets.UTF_8))
          if (!frame.fin) continue   // 等后续分片
          val text = pending.toString()
          pending.setLength(0)

          when (val incoming = RelayProtocol.classify(text)) {
            is RelayProtocol.Incoming.Welcome ->
              // 连接中途再来 welcome：协议错乱，不重连（否则可能被诱导反复切换密钥）
              return RelayProtocol.DisconnectInfo(
                RelayProtocol.Disconnect.PROTOCOL, "连接中途收到第二个 welcome",
              )

            is RelayProtocol.Incoming.Malformed ->
              return RelayProtocol.DisconnectInfo(
                RelayProtocol.Disconnect.TAMPERED, "畸形帧：${incoming.reason}",
              )

            is RelayProtocol.Incoming.Secure -> {
              val ch = channel
                ?: return RelayProtocol.DisconnectInfo(
                  RelayProtocol.Disconnect.PROTOCOL, "channel 未就绪却收到加密帧",
                )
              val plain = try {
                ch.open(SecureChannel.D2P, incoming.frame)
              } catch (e: SecureChannel.FrameError) {
                // 重放与篡改要分开报：两者的处置不同（重放可能只是我方计数回退，
                // 篡改一定意味着有人在动密文）
                return when (e.code) {
                  SecureChannel.FRAME_REPLAYED ->
                    RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.REPLAYED, e.message ?: "重放")
                  else ->
                    RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.TAMPERED, e.message ?: "密文校验失败")
                }
              }
              val json = String(plain, Charsets.UTF_8)
              lastSeq = maxOf(lastSeq, extractSeq(json) ?: lastSeq)
              listener.onEvent(json)
            }
          }
        }
        WebSocketFrames.OP_BINARY -> {
          // relay 只发明文 JSON 的 text 帧；二进制帧说明对端不是我们以为的那个
          return RelayProtocol.DisconnectInfo(
            RelayProtocol.Disconnect.PROTOCOL, "收到未约定的二进制帧",
          )
        }
        else -> return RelayProtocol.DisconnectInfo(
          RelayProtocol.Disconnect.PROTOCOL, "未知 opcode ${frame.opcode}",
        )
      }
    }
  }

  private fun pong(payload: ByteArray) {
    try {
      WebSocketFrames.writeFrame(output!!, WebSocketFrames.OP_PONG, payload, random, masked = true)
    } catch (_: Throwable) {
    }
  }

  /** 从事件信封里取 seq（与 D1d 的 `RelayEnvelope` 同一字段位置） */
  private fun extractSeq(json: String): Long? =
    RelayProtocol.JsonLite.parseObject(json)?.get("seq")?.toLongOrNull()

  private fun readHttpResponseHead(input: InputStream): String {
    val sb = StringBuilder()
    var matched = 0
    while (true) {
      val b = input.read()
      if (b < 0) throw IOException("握手期间连接关闭")
      sb.append(b.toChar())
      // 以 \r\n\r\n 结束
      matched = when {
        matched == 0 && b == '\r'.code -> 1
        matched == 1 && b == '\n'.code -> 2
        matched == 2 && b == '\r'.code -> 3
        matched == 3 && b == '\n'.code -> return sb.toString()
        b == '\r'.code -> 1
        else -> 0
      }
      if (sb.length > 16_384) throw IOException("握手响应头过长")
    }
  }

  private fun closeQuietly() {
    try {
      socket?.close()
    } catch (_: Throwable) {
    }
    socket = null
    input = null
    output = null
  }

  /** close 帧带来的「对端关闭」，走协议分类而不是当异常 */
  class CloseSignal(val code: Int, val reason: String) : Exception("close $code")

  // ─────────────────────── TLS 工厂 ───────────────────────

  /**
   * `internal` 而不是 `private`：`fingerprintEquals` / `normalize` 是纯函数，
   * 值得单独测 —— 它们的正确性直接决定「手机能不能连上这台电脑」。
   * 走真实 TLS 握手去测一个字符串归一化太绕，且 hex / base64url 的每个分支
   * 都得握手一次才能覆盖。
   */
  internal object SSLSocketFactoryFactory {

    /** SHA-256 摘要长度。写死而不是读算法，因为「指纹就是 32 字节」是协议约定 */
    private const val DIGEST_BYTES = 32

    /**
     * 构造只信任「指纹等于 expectedFingerprint」的 SSLContext。
     * 比对失败时**记录实际指纹**再抛异常 —— 否则用户看到的只有
     *
     * @param owner 观察到的指纹记在它上面（诊断用）。配对确认阶段还没有
     *   `RelaySocketClient` 实例，那时传 null：证书固定照样生效，
     *   只是失败信息里不带「实际指纹」这一项。
     */
    fun create(expectedFingerprint: String, owner: RelaySocketClient?): javax.net.ssl.SSLSocketFactory {
      val tm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
          val cert = chain?.firstOrNull()
            ?: throw CertificateException("服务端未提供证书")
          val actual = fingerprintOf(cert)
          if (owner != null) owner.observedFingerprint = actual
          if (!fingerprintEquals(actual, expectedFingerprint)) {
            throw CertificateException(
              "桌面证书指纹不匹配：期望 $expectedFingerprint，实际 $actual",
            )
          }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
      }
      val ctx = SSLContext.getInstance("TLS")
      ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
      return ctx.socketFactory
    }

    /**
     * 观察到的证书指纹，**格式与桌面完全一致**：`SHA256:` + `base64url(sha256(DER))` 无填充。
     */
    fun fingerprintOf(cert: X509Certificate): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
      return "SHA256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * 指纹比较：容忍四种书写（`SHA256:base64` / `SHA256:base64url` / 裸 base64 / 裸 hex），
     * 但**先归一化再比**，不做模糊匹配 —— `startsWith` 之类的写法会让
     * 「前缀相同」被当成相等，等于把 256 位强度降到几位。
     */
    fun fingerprintEquals(actual: String, expected: String): Boolean {
      val a = normalize(actual) ?: return false
      val b = normalize(expected) ?: return false
      return a.contentEquals(b)
    }

    /**
     * 这个字符串能不能被解析成一个合法指纹（32 字节的 sha256）。
     */
    fun canParseFingerprint(s: String): Boolean = normalize(s) != null

    private fun normalize(s: String): ByteArray? {
      val t = s.trim()
      val body = if (t.startsWith("SHA256:", ignoreCase = true)) t.substring(7) else t

      // 先试 hex：hex 只含 0-9a-f 且长度为偶数。
      // 必须先判 hex 再试 base64 —— 反过来的话，像 "abcdef0123456789"
      // 这样的 hex 串会被 base64 解码器"成功"解成别的字节（长度合法时它不报错），
      // 于是比对永远失败，而用户看不出为什么。
      val looksHex = body.length % 2 == 0 && body.length >= 32 &&
        body.all { Character.digit(it, 16) >= 0 }
      if (looksHex) {
        val out = ByteArray(body.length / 2)
        for (i in out.indices) {
          val hi = Character.digit(body[i * 2], 16)
          val lo = Character.digit(body[i * 2 + 1], 16)
          out[i] = ((hi shl 4) or lo).toByte()
        }
        return out.takeIf { it.size == DIGEST_BYTES }
      }

      // **必须两种字母表都试。**
      // 桌面 `certFingerprint()` 用的是 `digest('base64url')` —— 32 字节摘要有
      // 约 5/6 的概率含 `-` 或 `_`（实测 40 张真实证书 33 张命中，83%）。
      // 而 Java 的 `Base64.getDecoder()` 遇到这两个字符直接抛
      // IllegalArgumentException。只试标准解码器的后果：用户从桌面复制的
      // 指纹有八成永远「不匹配」，报错只说「证书不受信任」，而证书完全正确。
      val decoded = try {
        Base64.getDecoder().decode(body)
      } catch (_: Throwable) {
        try {
          // 标准解码器失败才走 url：`+` `/` 两者都不认，两个方向都得试
          Base64.getUrlDecoder().decode(body)
        } catch (_: Throwable) {
          null
        }
      } ?: return null

      // **长度必须正好 32 字节。** SHA-256 摘要没有别的长度。
      // 不卡这条的话，空串会解码成空数组，于是 `fingerprintEquals("", "")` 为 true
      // —— 一份没写指纹的凭据会被判成「指纹匹配」。这是典型的「两者都错等于对」。
      return decoded.takeIf { it.size == DIGEST_BYTES }
    }
  }
}

// ───── RelayConnectionController.kt ─────
/**
 * relay 连接的生命周期编排：读凭据 → 连接 → 断线分类 → 退避重连。
 * RelayConnectionController（本类，IO 线程）   ← 决定「连不连、要不要重试」
 * 把 4401 改成重试，`UNAUTHORIZED 不重连` 的测试必须红。
 */
class RelayConnectionController(
  private val scope: CoroutineScope,
  /** 读凭据。传函数而不是 [RelayCredentials] 实例：后者是 final 类，
   *  而这一层要能测「没配过 / 读不出来 / 正常」三种输入。 */
  private val loadCredentials: () -> RelayCredentials.Load,
  private val state: RelayConnectionState,
  /** 用凭据造客户端。测试可注入假实现，生产就是 `RelaySocketClient` */
  private val clientFactory: (RelayCredentials.Stored, RelaySocketClient.Listener) -> RelayClient,
  /** 事件出口：D1d 的 `RelayEventSink.onText`。**不直接改 UI 状态** */
  private val onEventJson: (String) -> Unit,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  /**
   * 连接就绪后的按需拉取（生产用于 `works.get`）。
   *
   * 用回调而不是把 RelayProtocol 塞进 controller：这一层不该知道
   * 「作品库」这种业务概念。回调抛错不能影响连接本身。
   */
  private val onReadyAction: () -> Unit = {},
  /** 退避序列（测试注入短序列，避免真等 30 秒） */
  private val backoffMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000),
) {

  companion object {
    /** 连续失败上限：超过就停在 Offline，等用户手动重试或下次进前台 */
    const val MAX_CONSECUTIVE_FAILURES = 6
  }

  private var job: Job? = null
  @Volatile
  private var client: RelayClient? = null

  /**
   * 上行发送入口（审批决定 / 停止运行）。
   *
   * 没有连接时返回 false。**不排队**：这些指令都有时效，重连后补发
   * 可能导致「用户已经忘记的审批突然生效」或「停掉新的一轮」。
   */
  fun send(json: String): Boolean = client?.send(json) ?: false

  /** 当前是否在跑（用于 onStart/onStop 幂等） */
  @Volatile
  var isRunning: Boolean = false
    private set

  /**
   * `stop()` 被调用过几次。
   *
   * 存在的唯一理由是可测性：无凭据时 `isRunning` 本来就是 false，
   * 所以「`onStop` 里有没有真的调用 stop()」用 `isRunning` 判断不出来
   * （删掉那行 `relayController.stop()` 测试照样绿）。计数能。
   */
  @Volatile
  var stopCallCount: Int = 0
    private set

  /**
   * 启动连接循环。重复调用无效（幂等）——
   * Activity 的 `onStart` 可能因配置变更连续触发，重复起循环会同时存在两条连接。
   */
  fun start() {
    if (isRunning) return
    isRunning = true
    job = scope.launch { runLoop() }
  }

  /** 停止：取消循环并关闭 socket。阻塞中的 read 会因 socket.close 抛异常退出 */
  fun stop() {
    stopCallCount++
    isRunning = false
    client?.close()
    client = null
    job?.cancel()
    job = null
  }

  /** 用户手动重试（界面上的「重试」按钮） */
  fun retryNow() {
    stop()
    start()
  }

  // ─────────────────────── 主循环 ───────────────────────

  private suspend fun runLoop() {
    val load = loadCredentials()

    val stored = when (load) {
      is RelayCredentials.Load.Empty -> {
        // 没配过：状态是 Unpaired，**不连接、不重试** —— 没有可连的目标
        state.markUnpaired()
        isRunning = false
        return
      }

      is RelayCredentials.Load.Unreadable -> {
        // 配过但读不出来（密钥被撤 / 文件损坏）。这与 Empty 不同：
        // 界面必须说「配对失效」，而不是「还没配过」——
        // 否则用户会以为自己没配过，不会想到要去重新扫码。
        state.markUnpaired()
        state.recordUnreadable(load.reason, load.detail)
        isRunning = false
        return
      }

      is RelayCredentials.Load.Ok -> load.stored
    }

    // 地址缺失 = 配对信息不完整。**不连接**，也不退回去猜一个默认地址：
    // 拿默认地址去连，失败时用户会去查网络、查防火墙，而真正的原因是票据没带地址。
    if (!stored.hasEndpoint) {
      state.recordUnreadable(
        "NO_ENDPOINT",
        "配对信息里没有中继地址（票据缺 relayHint），请重新配对",
      )
      isRunning = false
      return
    }

    var failures = 0
    while (scope.isActive && isRunning) {
      state.markConnecting(deviceName = stored.deviceId)

      val ended = connectOnce(stored)

      // 成功连上过（phase 已置 Online）就清零失败计数：
      // 若不清零，8 次抖动后成功连上、再断一次会从 30 秒开始等 ——
      // 用户要为一个已经恢复过的连接再等半分钟。
      // 注意：本循环里「成功」不会走到下一轮（下一轮只在 shouldRetry 时进入），
      // 所以清零发生在判断之前，对当前这轮的重试决策没有影响；
      // 它保证的是「重新 start() 之后从 1 秒开始」。
      if (state.connection.phase == ConnectionPhase.Online) {
        failures = 0
      }

      when {
        !isRunning -> return   // 被 stop() 打断，不算失败

        ended.shouldRetry -> {
          failures++
          if (failures >= MAX_CONSECUTIVE_FAILURES) {
            // 停下来等用户介入：一直重试会耗电，且用户看不到「它一直在试」
            state.giveUp(ended, failures)
            isRunning = false
            return
          }
          val wait = backoffMillis[minOf(failures - 1, backoffMillis.size - 1)]
          delay(wait)
        }

        else -> {
          // 不可重连：UNAUTHORIZED / TAMPERED / REPLAYED / PROTOCOL
          state.markClosed(ended)
          isRunning = false
          return
        }
      }
    }
  }

  /**
   * 跑一次完整连接，返回它如何结束。
   *
   * 用阻塞的 `runBlockingLoop` 放在 IO 作用域上：D1g 的设计就是
   * 「socket 读写占住调用线程」，这里把它交给 `scope` 的调度器。
   */
  private suspend fun connectOnce(stored: RelayCredentials.Stored): RelayProtocol.DisconnectInfo {
    var result: RelayProtocol.DisconnectInfo? = null

    val listener = object : RelaySocketClient.Listener {
      override fun onReady(welcome: RelayProtocol.Welcome) {
        state.markOnline(deviceName = stored.deviceId, nowMillis = nowMillis())
        // 连接就绪后拉一次按需数据。失败只影响那一个页面（显示未知），
        // 不能让作品库的异常把整条连接打断。
        try {
          onReadyAction()
        } catch (_: Throwable) {
        }
      }

      override fun onEvent(json: String) {
        state.markEvent(nowMillis())
        // 事件出口是 D1d 的 sink：它内部切主线程、做信封校验与去重。
        // 这一层不直接改任何 UI 状态（那会让「事件到了但界面不刷新」重现）。
        onEventJson(json)
      }

      override fun onClosed(info: RelayProtocol.DisconnectInfo) {
        result = info
      }
    }

    val c = clientFactory(stored, listener)
    client = c

    // socket 是阻塞读，必须挪到 IO 上（D1g 的设计：读写占住调用线程）。
    // `runInterruptible` 让 `stop()` 里的 socket.close 能打断阻塞读，
    // 而不是让整个作用域等一个已经在关闭的 socket。
    kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
      c.runBlockingLoop()
    }

    client = null
    // onClosed 一定会被调用（runBlockingLoop 的所有分支都走 finally → onClosed）。
    // 若没调用说明实现有 bug，这里用 PROTOCOL 兜底而不是假装成功。
    return result ?: RelayProtocol.DisconnectInfo(
      RelayProtocol.Disconnect.PROTOCOL,
      "客户端未上报断开原因（实现缺陷）",
    )
  }
}

// ───── RelayConnectionState.kt ─────
/**
 * relay 连接的可观察状态。
 */
class RelayConnectionState(
  /** 主线程投递器：生产用 Looper，测试可注入同步实现 */
  private val post: (() -> Unit) -> Unit = defaultMainPost(),
) {

  /**
   * 当前连接状态。**只能经 [writeOnMain] 修改。**
   *
   * 初始是 Unpaired：冷启动时还没读凭据，把「不知道」说成「在线」或
   * 「离线」都会误导用户（C1 的 `UnknownTodayBlock` 讨论过同一件事）。
   */
  var connection: Connection by mutableStateOf(Connection.unpaired)
    private set

  /** 最近一次断开的原因（诊断用；UI 只在 Offline 时可能显示一行小字） */
  var lastDisconnect: RelayProtocol.DisconnectInfo? by mutableStateOf(null)
    private set

  /** 本进程内最近一次收到事件的时刻（用于「上次同步 12 分钟前」） */
  var lastEventMillis: Long? by mutableStateOf(null)
    private set

  /** 是否已配对（有可用的凭据）。与 ConnectionPhase.Unpaired 同义，但更直白 */
  val isPaired: Boolean get() = connection.phase != ConnectionPhase.Unpaired

  // ─────────────────────── 写入（全部经主线程） ───────────────────────

  /**
   * 标记「已配对，开始连接」。
   *
   * `deviceName` 从凭据里拿不到（凭据只存 deviceId），所以这里留 null ——
   * 界面会退回显示「尚未连接电脑」或 deviceId，**不编一个假电脑名**。
   */
  fun markConnecting(deviceName: String? = null) = writeOnMain {
    connection = Connection(
      phase = ConnectionPhase.Connecting,
      deviceName = deviceName ?: connection.deviceName,
      sinceMillis = null,
      lastEventMillis = lastEventMillis,
      tierLabel = connection.tierLabel,
    )
  }

  /** welcome 已到，SecureChannel 就绪 */
  fun markOnline(deviceName: String?, nowMillis: Long, tierLabel: String? = null) = writeOnMain {
    connection = Connection(
      phase = ConnectionPhase.Online,
      deviceName = deviceName ?: connection.deviceName,
      // 在线时长从这一刻算起。不用上次的 sinceMillis —— 重连后应从新起点算，
      // 否则「在线 4h」会把中间断线的两小时也算进去。
      sinceMillis = nowMillis,
      lastEventMillis = lastEventMillis,
      tierLabel = tierLabel ?: connection.tierLabel,
    )
    lastDisconnect = null
  }

  /** 连接结束。`info.shouldRetry` 决定调用方是否再 markConnecting */
  fun markClosed(info: RelayProtocol.DisconnectInfo) = writeOnMain {
    lastDisconnect = info
    connection = Connection(
      phase = if (connection.phase == ConnectionPhase.Unpaired) {
        // 从没配上过就别显示「离线」—— 用户根本没配过，看到离线会去找网络问题
        ConnectionPhase.Unpaired
      } else {
        ConnectionPhase.Offline
      },
      deviceName = connection.deviceName,
      sinceMillis = null,
      // 保留 sinceMillis 之外的历史：离线时界面要显示「上次同步 N 分钟前」，
      // 这个时刻来自最后一次事件，不能让 markClosed 抹掉。
      lastEventMillis = lastEventMillis,
      tierLabel = null,
    )
  }

  /** 收到一条事件：只更新「最后事件时刻」，不改 phase */
  fun markEvent(nowMillis: Long) = writeOnMain {
    lastEventMillis = nowMillis
    if (connection.phase == ConnectionPhase.Online) {
      connection = connection.copy(lastEventMillis = nowMillis)
    }
  }

  /** 用户在设置里清除配对（或凭据被系统清掉） */
  fun markUnpaired() = writeOnMain {
    connection = Connection.unpaired
    lastDisconnect = null
    lastEventMillis = null
  }

  /**
   * 凭据存在但读不出来（Keystore 密钥被撤 / 文件损坏）。
   * 与 `markUnpaired` 的区别只在**给用户看的说法**：两者都是
   */
  fun recordUnreadable(reason: String, detail: String) = writeOnMain {
    lastUnreadable = UnreadableInfo(reason, detail)
    lastDisconnect = null
    connection = Connection.unpaired
  }

  /** 凭据读取失败的详情（null = 没发生过） */
  var lastUnreadable: UnreadableInfo? by mutableStateOf(null)
    private set

  data class UnreadableInfo(val reason: String, val detail: String)

  /**
   * 连续失败到达上限，停在这里等用户介入。
   *
   * 与 `markClosed` 的区别：这里保留「已试了 N 次」这个事实，
   * 界面可以说「已尝试 6 次，点击重试」—— 比静默停在 Offline 更有信息。
   */
  fun giveUp(info: RelayProtocol.DisconnectInfo, failures: Int) = writeOnMain {
    lastDisconnect = info
    lastGiveUpFailures = failures
    connection = Connection(
      phase = ConnectionPhase.Offline,
      deviceName = connection.deviceName,
      sinceMillis = null,
      lastEventMillis = lastEventMillis,
      tierLabel = null,
    )
  }

  /** 放弃时已尝试的次数（null = 没发生过） */
  var lastGiveUpFailures: Int? by mutableStateOf(null)
    private set

  /** 手动重试时清掉「已放弃」标记 */
  fun clearGiveUp() = writeOnMain {
    lastGiveUpFailures = null
  }

  /** 当前快照（给非 Compose 的调用方，例如通知文案） */
  fun snapshot(): Connection = connection

  /**
   * 所有写入统一走这里。
   *
   * 不做「已经在主线程就同步执行」之外的优化：`MainThreadHolder.post` 本身就是
   * 「主线程就同步跑、否则投递」，这在 D1d 有 6 项真实线程测试守着。
   */
  private inline fun writeOnMain(crossinline block: () -> Unit) {
    post { block() }
  }

  companion object {
    /**
     * 默认投递器：复用 D1d 的 `MainThreadHolder`（主线程同步执行、否则投递）。
     *
     * 用包装函数而不是直接把它当默认值：`MainThreadHolder` 是 `internal`，
     * 而这个类的构造函数参数是公开签名 —— 公开签名里出现 internal 类型会编译失败。
     */
    fun defaultMainPost(): (() -> Unit) -> Unit = { block ->
      MainThreadHolder.post(Runnable { block() })
    }
  }
}

// ───── RelayEventSink.kt ─────
/**
 * 从 relay 收到的原始文本 → [EventStreamViewModel]。
 * `Snapshot.withMutableSnapshot` 里），否则可能出现：
 * 解析失败（JSON 坏、信封不合法、事件类型不认识）**必须计数**。
 */
class RelayEventSink(
  private val vm: EventStreamViewModel,
  private val dispatch: Dispatcher = MainThreadDispatcher(),
) {

  /** 写入状态的执行方式 */
  interface Dispatcher {
    fun run(block: () -> Unit)
  }

  /** 切主线程（生产）。`mainExecutor` 在 Robolectric 里是同步执行的，测试可直接断言 */
  class MainThreadDispatcher(
    private val mainExecutor: (Runnable) -> Unit = { r -> MainThreadHolder.post(r) },
  ) : Dispatcher {
    override fun run(block: () -> Unit) = mainExecutor(Runnable(block))
  }

  /** 原地用快照包住（不依赖 Looper） */
  class SnapshotDispatcher : Dispatcher {
    override fun run(block: () -> Unit) {
      Snapshot.withMutableSnapshot { block() }
    }
  }

  /** 收到并成功应用的事件数 */
  var accepted: Int = 0
    private set

  /** 信封合法但不是运行时事件（invoke / result / ack） */
  var skippedNonEvent: Int = 0
    private set

  /** 一条上行 invoke 的应答。 */
  data class ResultFrame(
    val id: String,
    val ok: Boolean,
    /** 失败原因（桌面回帧的 `body.error.message`）；成功时为 null */
    val error: String?,
    /**
     * 成功结果的载荷（如 `works.get` 的 `{ works: [...] }`）。
     *
     * null = 没带；调用方不能把 null 当空列表。
     */
    val result: Map<String, Any?>? = null,
  )

  /** 最近一条结果（诊断用） */
  @Volatile
  var lastResult: ResultFrame? = null
    private set

  /** 结果回调。生产里用于把「审批已失效」这类失败显示给用户 */
  @Volatile
  var onResult: ((ResultFrame) -> Unit)? = null

  /** 信封不合法或 JSON 坏 */
  var invalid: Int = 0
    private set

  /** 信封合法、事件类型不认识（计入 Unparsed，不静默丢） */
  var unparsed: Int = 0
    private set

  /** 最近一次失败原因，便于把「为什么不动」显示在界面上/日志里 */
  var lastProblem: String? = null
    private set

  /** 全部失败（invalid + unparsed） */
  val failures: Int get() = invalid + unparsed

  /** 真机入口：一条解密后的明文 JSON */
  fun onText(text: String) {
    when (val r = RelayEnvelope.parseJson(text)) {
      is RelayEnvelope.Result.Ok -> applyEnvelope(r.envelope)
      is RelayEnvelope.Result.NotAnEvent -> {
        skippedNonEvent += 1
        if (r.kind == "result") acceptResult(r.envelope)
      }
      is RelayEnvelope.Result.Invalid -> {
        invalid += 1
        lastProblem = r.reason
      }
    }
  }

  /** 已解析成 Map 的信封（中继通道有时先解成对象再交过来） */
  fun onEnvelopeMap(raw: Map<String, Any?>) {
    when (val r = RelayEnvelope.parse(raw)) {
      is RelayEnvelope.Result.Ok -> applyEnvelope(r.envelope)
      is RelayEnvelope.Result.NotAnEvent -> {
        skippedNonEvent += 1
        if (r.kind == "result") acceptResult(r.envelope)
      }
      is RelayEnvelope.Result.Invalid -> {
        invalid += 1
        lastProblem = r.reason
      }
    }
  }

  /**
   * 收下一条 `result` 帧。
   *
   * 失败不再静默：调用方（审批按钮）需要知道「桌面说这条请求已经失效」，
   * 否则用户点了按钮、界面没变化，只能反复点。
   */
  private fun acceptResult(env: RelayEnvelope.Parsed?) {
    if (env == null) return
    val body = env.body
    val id = body["id"] as? String ?: ""
    val ok = body["ok"] == true
    val error = ((body["error"] as? Map<*, *>)?.get("message")) as? String
    // 结果载荷转成 Kotlin Map：下游（作品库）只认识 Kotlin 集合。
    val result = (body["result"] as? Map<*, *>)?.entries
      ?.associate { it.key.toString() to it.value }
    val frame = ResultFrame(id = id, ok = ok, error = error, result = result)
    lastResult = frame
    // 结果可能带状态之外的提示，但不应改 lastSeq —— result 的 seq 只用于日志。
    dispatch.run { onResult?.invoke(frame) }
  }

  private fun applyEnvelope(env: RelayEnvelope.Parsed) {
    val eventMap = RelayEnvelope.toEventMap(env)
    // 状态写入必须走 dispatch（主线程 / 快照），见类注释第二条
    dispatch.run {
      val parsed = EventParser.parse(eventMap)
      // `EventParser.parse` 对未知 type 返回的是**动作** `EventAction.Unparsed`，
      // 不是 null —— 初版这里判断 `parsed == null`，于是未知事件被当作正常
      // 事件接受（accepted++），unparsed 计数永远是 0。
      // 表现：真机上「界面不动」，日志里一条失败都没有。
      if (parsed == null || parsed.action is EventAction.Unparsed) {
        // 信封合法、seq 正常，但事件体里有手机不认识/不完整的 type。
        // **不能当作没收到** —— 计进 unparsed 并在界面/日志可见。
        unparsed += 1
        lastProblem = "UNPARSED: ${eventMap["type"] ?: "(无 type)"}"
        // 仍然把这条事件交给归并器（带 true seq），让 lastSeq 继续推进 ——
        // 否则补漏会一直重拉这一条，形成死循环。
        vm.onEvent(
          EventParser.Parsed(
            seq = env.seq,
            sessionId = env.sessionId,
            action = if (parsed != null) parsed.action else EventAction.Unparsed(
              (eventMap["type"] as? String) ?: "?",
              "事件体无法解析",
            ),
          )
        )
      } else {
        accepted += 1
        vm.onEvent(parsed)
      }
    }
  }
}

/** 主线程投递的默认实现（Android 上用 Looper，测试里由 Robolectric 同步执行） */
internal object MainThreadHolder {
  private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
  fun post(r: Runnable) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run() else handler.post(r)
  }
}

// ───── RelayHint.kt ─────
/**
 * 把票据里的 `relayHint` 解析成 host + port。
 * 某段现成代码定义 —— 这一点必须如实记下来，不能假装有权威来源。
 */
object RelayHint {

  data class Endpoint(val host: String, val port: Int)

  /**
   * @return 解析结果；`null` 表示这个 hint 不可用
   *
   * 返回 null 而不是抛异常：调用方（配对流程）对「没有地址」的处理
   * 是「配对成功但连不上，提示用户」，不是崩溃。
   */
  fun parse(hint: String?): Endpoint? {
    val raw = hint?.trim()
    if (raw.isNullOrEmpty()) return null

    // IPv6 形如 [::1]:7788 —— 冒号在方括号里，不能简单 split(":")
    val host: String
    val portText: String
    if (raw.startsWith("[")) {
      val close = raw.indexOf(']')
      if (close <= 1) return null
      host = raw.substring(1, close)
      // 右括号后必须是 `:port`
      if (close + 1 >= raw.length || raw[close + 1] != ':') return null
      portText = raw.substring(close + 2)
    } else {
      val idx = raw.lastIndexOf(':')
      if (idx <= 0) return null          // 没有冒号，或冒号在最前面（缺 host）
      host = raw.substring(0, idx)
      portText = raw.substring(idx + 1)
    }

    if (host.isBlank()) return null
    // 端口必须是纯数字：`toIntOrNull` 会把 "+80" 也接受，这里额外挡住
    if (portText.isEmpty() || portText.any { !it.isDigit() }) return null
    val port = portText.toIntOrNull() ?: return null
    if (port !in 1..65535) return null

    return Endpoint(host, port)
  }
}

// ───── PairRequest.kt ─────
/**
 * 手机 → 桌面的「配对请求」文本。
 * 桌面签发票据时必须知道**这台手机的公钥**：
 * - 票据里的 `pubKey` 必须是手机自己的公钥（`PairingFlow` 会校验归属，
 * 所以手机必须能把「我是谁 + 我的公钥」复制出来交给桌面。
 */
object PairRequest {
  /** 与桌面 `PAIR_REQUEST_PREFIX` 必须逐字一致（有测试守着） */
  const val PREFIX = "MORROWPAIR1."

  /**
   * 生成本机的配对请求文本。
   *
   * @param deviceId 设备标识；为空时用公钥前 12 位做标识 ——
   *   这样同一台手机重复复制得到的 deviceId 不变（桌面据此判断「是不是同一台」）。
   * @param publicKeyB64 未压缩点公钥（65 字节的 base64）
   */
  fun encode(deviceId: String?, publicKeyB64: String): String {
    val id = deviceId?.takeIf { it.isNotBlank() } ?: defaultDeviceId(publicKeyB64)
    // 手拼 JSON 而不是引 Gson：字段只有两个、值都是安全字符（base64 与短标识），
    // 而 Gson 会带来方法与反射开销（当前 APK 19.5 MB，不为两个字段加依赖）。
    // 代价是必须自己保证转义 —— 下面两个值都做了最小必要转义。
    val json = "{\"deviceId\":${quote(id)},\"pubKey\":${quote(publicKeyB64)}}"
    return PREFIX + base64Url(json.toByteArray(Charsets.UTF_8))
  }

  /**
   * 从公钥派生一个稳定的设备标识。
   *
   * 为什么不用随机 UUID：随机值每次点「复制」都不同，桌面那边会看到
   * 「又一台新手机」，已配对的旧条目永远留在列表里。
   * 公钥在这台手机上是不变的，所以派生值也不变。
   */
  fun defaultDeviceId(publicKeyB64: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(publicKeyB64.toByteArray(Charsets.UTF_8))
    val hex = digest.take(6).joinToString("") { "%02x".format(it) }
    return "phone-$hex"
  }

  private fun base64Url(bytes: ByteArray): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  /**
   * 最小 JSON 字符串转义。
   * 只处理「必须转义否则 JSON 无效」的字符。base64 公钥里不会出现这些，
   * 但 `deviceId` 来自调用方，不能假设它永远干净 —— 引号或反斜杠
   */
  private fun quote(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
      }
    }
    sb.append('"')
    return sb.toString()
  }
}
