package studio.morrow.android

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

object RelayProtocol {

  const val VERSION = 1

  /** 桌面 `verifyRequest(deviceId, 'lan', ts, sig)` 里的 channel 字面量 */
  const val CHANNEL_LAN = "lan"

  const val CLOSE_UNAUTHORIZED = 4401
  const val CLOSE_TAMPERED = 4402
  const val CLOSE_REPLAYED = 4403

  /** 正常关闭：1000 与 1001（服务端下线）都可重连 */
  const val CLOSE_NORMAL = 1000
  const val CLOSE_GOING_AWAY = 1001

  const val KIND_HELLO = "hello"
  const val KIND_WELCOME = "welcome"
  const val KIND_ACK = "ack"

  const val KIND_PAIR_CONFIRM = "pair-confirm"
  const val KIND_PAIR_CONFIRM_OK = "pair-confirm-ok"

  /** 桌面 `SNAPSHOT_REQUIRED = 'snapshot_required'`（replay-buffer.ts:29） */
  const val SNAPSHOT_REQUIRED = "snapshot_required"

  class ProtocolError(val code: String, message: String) : Exception(message)

  fun helloFrame(
    sessionKeyBase64: String,
    deviceId: String,
    timestampMs: Long,
    lastSeq: Long = 0L,
  ): String {
    val ts = timestampMs.toString()
    val sig = sign(sessionKeyBase64, deviceId, CHANNEL_LAN, ts)
    return buildString {
      append("{")
      append("\"v\":").append(VERSION).append(",")
      append("\"kind\":\"").append(KIND_HELLO).append("\",")
      append("\"deviceId\":\"").append(escape(deviceId)).append("\",")
      append("\"ts\":\"").append(ts).append("\",")
      append("\"sig\":\"").append(escape(sig)).append("\"")
      if (lastSeq > 0L) append(",\"lastSeq\":").append(lastSeq)
      append("}")
    }
  }

  fun pairConfirmFrame(nonce: String): String = buildString {
    append("{")
    append("\"v\":").append(VERSION).append(",")
    append("\"kind\":\"").append(KIND_PAIR_CONFIRM).append("\",")
    append("\"nonce\":\"").append(escape(nonce)).append("\"")
    append("}")
  }

  fun parsePairConfirmOk(text: String, expectedDeviceId: String): Boolean {
    val obj = try {
      Json.parseToJsonElement(text) as? JsonObject
    } catch (t: Throwable) {
      return false
    } ?: return false
    val kind = obj["kind"]?.jsonPrimitive?.content ?: return false
    if (kind != KIND_PAIR_CONFIRM_OK) return false
    val deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: return false
    return deviceId == expectedDeviceId
  }

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

    val snapshotFlag = obj["snapshotRequired"]
    val snapshotRequired = snapshotFlag != null &&
      (snapshotFlag == SNAPSHOT_REQUIRED || snapshotFlag.equals("true", ignoreCase = true))

    val replayCount = obj["replayCount"]?.toIntOrNull()

    if (!snapshotRequired && replayCount == null) {
      throw ProtocolError(
        "WELCOME_MALFORMED",
        "welcome 既没有 replayCount 也没有 snapshotRequired，无法决定补漏策略",
      )
    }

    return Welcome(deviceId, fingerprint, salt, newestSeq, replayCount, snapshotRequired)
  }

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

    val n = obj["n"]
    val c = obj["c"]
    if (n != null && c != null) {
      if (obj.keys.any { it != "n" && it != "c" }) {
        // 若被下游读取就等于「绕过认证的旁路」。
        return Incoming.Malformed("加密帧含多余字段：${obj.keys.filter { it != "n" && it != "c" }}")
      }
      return Incoming.Secure(SecureChannel.SealedFrame(n = n, c = c))
    }

    if (n != null || c != null) {
      return Incoming.Malformed("加密帧字段不完整（n=${n != null}, c=${c != null}）")
    }

    return Incoming.Malformed("握手后收到非加密、非 welcome 的帧（kind=${obj["kind"]}）")
  }

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
    in 1000..1999 -> DisconnectInfo(Disconnect.RETRYABLE, reason ?: "关闭码 $code")
    in 4000..4999 -> DisconnectInfo(Disconnect.PROTOCOL, reason ?: "应用层关闭 $code")
    else -> DisconnectInfo(Disconnect.RETRYABLE, reason ?: "关闭码 $code")
  }

  /** 网络层失败（`onFailure`）：默认可重连，但协议级异常不可 */
  fun classifyFailure(error: Throwable): DisconnectInfo = when (error) {
    is ProtocolError -> DisconnectInfo(Disconnect.PROTOCOL, error.message ?: error.code)
    else -> DisconnectInfo(Disconnect.RETRYABLE, error.message ?: error::class.java.simpleName)
  }

  fun snapshotRequest(lastSeq: Long): String =
    """{"kind":"snapshot_request","lastSeq":$lastSeq}"""

  fun ackFrame(seq: Long): String = """{"kind":"ack","seq":$seq}"""

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
