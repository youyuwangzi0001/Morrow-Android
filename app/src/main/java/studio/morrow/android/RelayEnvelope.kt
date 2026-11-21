package studio.morrow.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.morrow.android.EventParser

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

  fun toEventMap(env: Parsed): Map<String, Any?> {
    val merged = LinkedHashMap<String, Any?>(env.body)
    merged["seq"] = env.seq
    if ((merged["sessionId"] as? String).isNullOrBlank() && env.sessionId.isNotBlank()) {
      merged["sessionId"] = env.sessionId
    }
    if ((merged["runId"] as? String).isNullOrBlank() && env.runId.isNotBlank()) {
      merged["runId"] = env.runId
    }
    if (merged["at"] == null && env.at > 0) merged["at"] = env.at
    return merged
  }

  private fun toMap(obj: JsonObject): Map<String, Any?> =
    obj.entries.associate { (k, v) -> k to toAny(v) }

  private fun toAny(v: kotlinx.serialization.json.JsonElement): Any? = when (v) {
    is JsonObject -> toMap(v)
    is kotlinx.serialization.json.JsonArray -> v.map { toAny(it) }
    is JsonPrimitive -> when {
      v.isString -> v.content
      else -> v.content.toDoubleOrNull()?.let { d ->
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
