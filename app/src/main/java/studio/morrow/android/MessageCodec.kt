package studio.morrow.android

object UiMessageCodec {

  /** 单条消息最多解析多少块。超过的部分桌面已经换成提示块，这里再兜一层。 */
  private const val MAX_BLOCKS = 200

  fun fromWire(raw: Map<*, *>): UiMessage? {
    val id = raw["id"] as? String ?: return null
    val blocks = parseBlocks(raw["blocks"])
    return UiMessage(
      id = id,
      role = when (raw["role"] as? String) {
        "user" -> MessageRole.User
        "system" -> MessageRole.System
        else -> MessageRole.Assistant
      },
      text = raw["text"] as? String ?: "",
      createdAtMillis = (raw["createdAt"] as? Number)?.toLong() ?: 0L,
      blocks = blocks,
      interrupted = raw["interrupted"] as? Boolean == true,
      tokensUsed = (raw["tokensUsed"] as? Number)?.toLong()?.takeIf { it > 0L },
    )
  }

  fun parseBlocks(value: Any?): List<UiBlock> {
    val list = value as? List<*> ?: return emptyList()
    val out = ArrayList<UiBlock>(minOf(list.size, MAX_BLOCKS))
    for (item in list) {
      if (out.size >= MAX_BLOCKS) break
      val m = item as? Map<*, *> ?: continue
      when (m["type"] as? String) {
        "text" -> (m["text"] as? String)?.takeIf { it.isNotEmpty() }?.let { out += UiBlock.Text(it) }
        "thinking" -> (m["text"] as? String)?.takeIf { it.isNotEmpty() }?.let { out += UiBlock.Thinking(it) }
        "tool" -> toolOf(m)?.let { out += it }
        "error" -> {
          val message = m["message"] as? String ?: continue
          out += UiBlock.Error(message, m["code"] as? String)
        }
        else -> Unit // 未知块类型：跳过，不整条丢弃
      }
    }
    return out
  }

  private fun toolOf(m: Map<*, *>): UiBlock.Tool? {
    val toolId = m["id"] as? String ?: return null
    val result = m["result"] as? Map<*, *>
    val status = when (m["status"] as? String) {
      "running" -> ToolCallStatus.Running
      "failed" -> ToolCallStatus.Failed
      "denied" -> ToolCallStatus.Denied
      "interrupted", "cancelled" -> ToolCallStatus.Interrupted
      else -> ToolCallStatus.Ok
    }
    val startedAt = (m["startedAt"] as? Number)?.toLong() ?: 0L
    val finishedAt = (m["finishedAt"] as? Number)?.toLong()
    val call = UiToolCall(
      id = toolId,
      name = m["name"] as? String ?: "",
      title = m["title"] as? String ?: "",
      category = m["category"] as? String ?: "",
      status = status,
      diffAdded = (result?.get("diffAdded") as? Number)?.toInt(),
      diffRemoved = (result?.get("diffRemoved") as? Number)?.toInt(),
      created = result?.get("created") as? Boolean == true,
      deleted = result?.get("deleted") as? Boolean == true,
      exitCode = (result?.get("exitCode") as? Number)?.toInt(),
      timedOut = result?.get("timedOut") as? Boolean == true,
      durationMillis = (result?.get("durationMs") as? Number)?.toLong(),
      startedAtMillis = startedAt,
    )
    return UiBlock.Tool(call, diff = m["diff"] as? String)
  }

  fun mergeTools(fromBlocks: List<UiToolCall>, fromEvents: List<UiToolCall>): List<UiToolCall> {
    if (fromBlocks.isEmpty()) return fromEvents
    if (fromEvents.isEmpty()) return fromBlocks
    val byId = fromEvents.associateBy { it.id }
    val merged = fromBlocks.map { base ->
      val live = byId[base.id] ?: return@map base
      base.copy(
        // blocks 里的状态反而是旧的。
        title = live.title.ifBlank { base.title },
        status = live.status,
        durationMillis = live.durationMillis ?: base.durationMillis,
        exitCode = live.exitCode ?: base.exitCode,
      )
    }
    val known = merged.mapTo(HashSet()) { it.id }
    return merged + fromEvents.filter { it.id !in known }
  }
}
