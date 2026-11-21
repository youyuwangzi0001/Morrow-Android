package studio.morrow.android

object ReplayPack {

  /** 一个会话应有的最终内容，用来逐字段核对 */
  data class Expected(
    val sessionId: String,
    val title: String,
    val messageCount: Int,
    /** 每条消息的 (id, role, text, thinking) */
    val messages: List<ExpectedMessage>,
    /** 结束时是否还有在跑的轮次 */
    val runningAtEnd: Boolean,
  )

  data class ExpectedMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
    val thinking: String?,
  )

  data class Pack(
    val events: List<Map<String, Any?>>,
    val expected: List<Expected>,
    /** 合成包里的会话 id 列表 */
    val sessionIds: List<String>,
  )

  fun build(rounds: Int = 8): Pack {
    val events = mutableListOf<Map<String, Any?>>()
    val expected = mutableListOf<Expected>()
    var seq = 1L
    var clock = 1_000L

    fun next() = seq++
    fun tick(ms: Long = 3L): Long { clock += ms; return clock }

    val sessionIds = listOf("sess-A", "sess-B", "sess-C")

    sessionIds.forEachIndexed { si, sid ->
      val baseClock = clock
      val msgs = mutableListOf<ExpectedMessage>()
      var runOpen = false

      repeat(rounds) { r ->
        val runId = "run-$sid-$r"
        val assistantId = "asst-$sid-$r"
        val userText = "$sid 第 ${r + 1} 轮：把 Q3 销售明细做成复盘"

        // ── run_started ──
        events += mapOf(
          "type" to "run_started", "seq" to next(), "sessionId" to sid,
          "runId" to runId, "at" to tick(40),
          "userMessage" to mapOf("text" to userText, "role" to "user"),
          "assistantMessageId" to assistantId,
        )
        runOpen = true
        msgs += ExpectedMessage("user-$runId", MessageRole.User, userText, null)

        val chunks = 150 + (r * 17) % 380   // 150–530 片，3 会话 × 8 轮 → 5000+ 事件
        val thinkingChunks = 12
        val textBuilder = StringBuilder()
        val thinkBuilder = StringBuilder()

        events += mapOf(
          "type" to "message_delta", "seq" to next(), "sessionId" to sid,
          "runId" to runId, "at" to tick(),
          "messageId" to assistantId, "textDelta" to "先",
        )
        textBuilder.append("先")

        events += mapOf(
          "type" to "message_started", "seq" to next(), "sessionId" to sid,
          "runId" to runId, "at" to tick(),
          "messageId" to assistantId, "role" to "assistant",
        )

        // thinking 片段
        repeat(thinkingChunks) { i ->
          val t = "思考${i}；"
          thinkBuilder.append(t)
          events += mapOf(
            "type" to "message_delta", "seq" to next(), "sessionId" to sid,
            "runId" to runId, "at" to tick(),
            "messageId" to assistantId, "thinkingDelta" to t,
          )
        }

        // 正文分片
        repeat(chunks) { i ->
          val t = "字${i}"
          textBuilder.append(t)
          events += mapOf(
            "type" to "message_delta", "seq" to next(), "sessionId" to sid,
            "runId" to runId, "at" to tick(),
            "messageId" to assistantId, "textDelta" to t,
          )
        }

        if (r % 3 == 0) {
          events += mapOf(
            "type" to "tool_started", "seq" to next(), "sessionId" to sid,
            "runId" to runId, "at" to tick(),
            "messageId" to assistantId,
            "block" to mapOf("name" to "read_file"),
          )
          events += mapOf(
            "type" to "tool_progress", "seq" to next(), "sessionId" to sid,
            "runId" to runId, "at" to tick(),
            "messageId" to assistantId, "toolUseId" to "tu-1", "percent" to 42,
          )
          events += mapOf(
            "type" to "tool_finished", "seq" to next(), "sessionId" to sid,
            "runId" to runId, "at" to tick(),
            "messageId" to assistantId, "toolUseId" to "tu-1",
            "result" to mapOf("ok" to true, "output" to "…"),
          )
          events += mapOf(
            "type" to "todos_updated", "seq" to next(), "sessionId" to sid,
            "runId" to runId, "at" to tick(),
            "todos" to listOf(mapOf("text" to "读文件", "done" to true)),
          )
        }

        // 每 4 轮插一次审批
        if (r % 4 == 0) {
          val rid = "perm-$sid-$r"
          events += mapOf(
            "type" to "permission_requested", "seq" to next(), "sessionId" to sid,
            "runId" to runId, "at" to tick(),
            "request" to mapOf("id" to rid, "toolName" to "run_command"),
          )
          events += mapOf(
            "type" to "permission_resolved", "seq" to next(), "sessionId" to sid,
            "runId" to runId, "at" to tick(),
            "requestId" to rid, "decision" to ApprovalDecision.Once.wire,
          )
        }

        events += mapOf(
          "type" to "message_completed", "seq" to next(), "sessionId" to sid,
          "runId" to runId, "at" to tick(),
          "messageId" to assistantId,
          "message" to mapOf("text" to textBuilder.toString()),
        )
        msgs += ExpectedMessage(
          id = assistantId,
          role = MessageRole.Assistant,
          text = textBuilder.toString(),
          thinking = thinkBuilder.toString(),
        )

        // ── 用量与轮次结束 ──
        events += mapOf(
          "type" to "usage_updated", "seq" to next(), "sessionId" to sid,
          "runId" to runId, "at" to tick(),
          "usage" to mapOf("inputTokens" to 1000, "outputTokens" to chunks * 2),
        )
        events += mapOf(
          "type" to "run_finished", "seq" to next(), "sessionId" to sid,
          "runId" to runId, "at" to tick(),
          "reason" to "done",
          "usage" to mapOf("inputTokens" to 1000, "outputTokens" to chunks * 2),
        )
        runOpen = false
      }

      // 每会话最后一次标题更新
      events += mapOf(
        "type" to "session_updated", "seq" to next(), "sessionId" to sid,
        "runId" to "", "at" to tick(),
        "meta" to mapOf("id" to sid), "title" to "$sid 的作品复盘",
      )

      expected += Expected(
        sessionId = sid,
        title = "$sid 的作品复盘",
        messageCount = msgs.size,
        messages = msgs,
        runningAtEnd = runOpen,
      )
      clock = baseClock + 1
    }

    events += mapOf("type" to "totally_unknown_event", "seq" to next(), "sessionId" to "sess-A")

    return Pack(events = events, expected = expected, sessionIds = sessionIds)
  }
}
