package studio.morrow.android

object EventParser {

  /** 解析结果里带上原始 seq，供补漏判定 */
  data class Parsed(val seq: Long, val sessionId: String, val action: EventAction)

  fun parse(raw: Map<String, Any?>): Parsed? {
    val type = raw["type"] as? String ?: return null
    val seq = (raw["seq"] as? Number)?.toLong() ?: 0L
    val sessionId = raw["sessionId"] as? String ?: ""
    val runId = raw["runId"] as? String ?: ""
    val at = (raw["at"] as? Number)?.toLong() ?: 0L

    val action: EventAction = when (type) {
      "run_started" -> {
        val userMsg = raw["userMessage"] as? Map<*, *>
        val text = userMsg?.get("text") as? String ?: ""
        val assistantId = raw["assistantMessageId"] as? String
        if (assistantId == null) {
          EventAction.Unparsed(type, "缺 assistantMessageId")
        } else {
          EventAction.RunStarted(sessionId, runId, text, assistantId, at)
        }
      }

      "run_finished" -> EventAction.RunFinished(sessionId, runId, raw["reason"] as? String)

      "message_started" -> {
        val mid = raw["messageId"] as? String
        val role = when (raw["role"] as? String) {
          "user" -> MessageRole.User
          "system" -> MessageRole.System
          else -> MessageRole.Assistant
        }
        if (mid == null) EventAction.Unparsed(type, "缺 messageId")
        else EventAction.MessageStarted(sessionId, mid, role)
      }

      "message_delta" -> {
        val mid = raw["messageId"] as? String
        val textDelta = raw["textDelta"] as? String
        val thinkDelta = raw["thinkingDelta"] as? String
        when {
          mid == null -> EventAction.Unparsed(type, "缺 messageId")
          textDelta != null -> EventAction.MessageDelta(sessionId, mid, DeltaKind.Text, textDelta)
          thinkDelta != null -> EventAction.MessageDelta(sessionId, mid, DeltaKind.Thinking, thinkDelta)
          else -> EventAction.Ignore(type, "空分片（无 textDelta / thinkingDelta）")
        }
      }

      "message_completed" -> {
        val mid = raw["messageId"] as? String
        val msg = raw["message"] as? Map<*, *>
        val text = msg?.get("text") as? String ?: ""
        val rich = msg?.let { UiMessageCodec.fromWire(it) }
        if (mid == null) EventAction.Unparsed(type, "缺 messageId")
        else EventAction.MessageCompleted(
          sessionId = sessionId,
          messageId = mid,
          text = text,
          blocks = rich?.blocks ?: emptyList(),
          interrupted = rich?.interrupted ?: false,
        )
      }

      "messages_replaced" -> {
        val list = raw["messages"] as? List<*>
        val msgs = list?.mapNotNull { item ->
          val m = item as? Map<*, *> ?: return@mapNotNull null
          UiMessageCodec.fromWire(m)
        } ?: emptyList()
        EventAction.MessagesReplaced(sessionId, msgs)
      }

      "snapshot" -> {
        val snap = raw["snapshot"] as? Map<*, *>
        if (snap == null) EventAction.Unparsed(type, "缺 snapshot")
        else {
          @Suppress("UNCHECKED_CAST")
          EventAction.Snapshot(parseSnapshot(snap as Map<String, Any?>))
        }
      }

      "session_updated" -> EventAction.SessionUpdated(sessionId, raw["title"] as? String)

      "permission_requested" -> {
        val req = raw["request"] as? Map<*, *>
        val rid = req?.get("id") as? String
        if (rid == null) EventAction.Unparsed(type, "缺 request.id")
        else EventAction.PermissionRequested(
          sessionId = sessionId,
          requestId = rid,
          toolName = req["toolName"] as? String ?: "",
          requestedAtMillis = (raw["at"] as? Number)?.toLong() ?: 0L,
          title = req["title"] as? String ?: "",
          summary = req["summary"] as? String ?: "",
          category = req["category"] as? String ?: "",
          risk = req["risk"] as? String ?: "",
          allowAlways = req["allowAlways"] as? Boolean,
        )
      }

      "permission_resolved" -> {
        val rid = raw["requestId"] as? String
        if (rid == null) EventAction.Unparsed(type, "缺 requestId")
        else EventAction.PermissionResolved(sessionId, rid)
      }

      // 「电脑刚才执行了什么命令、动了哪些文件」。
      "tool_started" -> {
        val mid = raw["messageId"] as? String
        val tool = raw["tool"] as? Map<*, *>
        val callId = raw["toolUseId"] as? String
        when {
          mid == null -> EventAction.Unparsed(type, "缺 messageId")
          callId == null -> EventAction.Unparsed(type, "缺 toolUseId")
          tool == null -> EventAction.Ignore(type, "旧版桌面未下发工具摘要")
          else -> EventAction.ToolStarted(
            sessionId = sessionId,
            messageId = mid,
            call = UiToolCall(
              id = callId,
              name = tool["name"] as? String ?: "",
              title = tool["title"] as? String ?: "",
              category = tool["category"] as? String ?: "",
              status = ToolCallStatus.Running,
              startedAtMillis = at,
            ),
          )
        }
      }
      "tool_progress" -> {
        if (raw.containsKey("memberTask")) {
          val merged = IgnoreBridges.mergeMember(emptyList(), raw)
          if (merged.isEmpty()) EventAction.Ignore(type, "memberTask 缺 index，无法合并")
          else EventAction.MemberPatched(merged)
        } else EventAction.Ignore(type, "工具进度只在桌面显示百分比")
      }
      "tool_finished" -> {
        val mid = raw["messageId"] as? String
        val toolUseId = raw["toolUseId"] as? String
        val result = raw["toolResult"] as? Map<*, *>
        val interrupted = raw["interrupted"] as? Boolean == true
        val denied = raw["denied"] as? Boolean == true
        val status = when {
          interrupted -> ToolCallStatus.Interrupted
          denied -> ToolCallStatus.Denied
          result == null -> ToolCallStatus.Ok
          result["ok"] as? Boolean == true -> ToolCallStatus.Ok
          else -> ToolCallStatus.Failed
        }
        when {
          mid == null -> EventAction.Unparsed(type, "缺 messageId")
          toolUseId == null -> EventAction.Unparsed(type, "缺 toolUseId")
          else -> EventAction.ToolFinished(
            sessionId = sessionId,
            messageId = mid,
            toolUseId = toolUseId,
            status = status,
            diffAdded = (result?.get("diffAdded") as? Number)?.toInt(),
            diffRemoved = (result?.get("diffRemoved") as? Number)?.toInt(),
            created = result?.get("created") as? Boolean == true,
            deleted = result?.get("deleted") as? Boolean == true,
            exitCode = (result?.get("exitCode") as? Number)?.toInt(),
            timedOut = result?.get("timedOut") as? Boolean == true,
            durationMillis = (result?.get("durationMs") as? Number)?.toLong(),
            diff = raw["toolDiff"] as? String,
          )
        }
      }
      "question_asked" -> EventAction.Ignore(type, "提问 UI 未在 C 轨做（蓝图未列卡）")
      "question_answered" -> EventAction.Ignore(type, "同上")
      "todos_updated" -> {
        val list = raw["todos"] as? List<*>
        if (list == null) EventAction.Ignore(type, "载荷缺 todos")
        else EventAction.TodosUpdated(
          sessionId = sessionId,
          todos = list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            UiTodo(id = id, text = m["text"] as? String ?: "", status = m["status"] as? String ?: "")
          },
        )
      }
      "artifacts_updated" -> EventAction.ArtifactsReplaced(sessionId, IgnoreBridges.artifactsOf(raw))
      "usage_updated" -> {
        val u = raw["usage"] as? Map<*, *>
        if (u == null) EventAction.Ignore(type, "载荷缺 usage，无法计入用量")
        else {
          val i = (u["inputTokens"] as? Number)?.toLong() ?: 0L
          val o = (u["outputTokens"] as? Number)?.toLong() ?: 0L
          EventAction.UsageReported(i + o, true)
        }
      }
      "research_updated" -> EventAction.Ignore(type, "研究态手机不展示")
      "super_updated" -> EventAction.MembersReplaced(
        members = IgnoreBridges.membersOf(raw),
        goal = IgnoreBridges.goalOf(raw),
        projectId = IgnoreBridges.projectIdOf(raw),
        sessionId = IgnoreBridges.sessionIdOf(raw),
      )
      "collaboration_updated" -> EventAction.Ignore(type, "同上")
      "goal_updated" -> {
        val g = raw["goal"] as? Map<*, *>
        if (g == null) EventAction.GoalUpdated(sessionId, null)
        else EventAction.GoalUpdated(
          sessionId,
          UiGoal(
            status = g["status"] as? String ?: "",
            round = (g["round"] as? Number)?.toInt() ?: 0,
            maxRounds = (g["maxRounds"] as? Number)?.toInt() ?: 0,
            stopReason = g["stopReason"] as? String,
          ),
        )
      }
      "plan_updated" -> {
        val p = raw["plan"] as? Map<*, *>
        EventAction.PlanUpdated(
          sessionId = sessionId,
          plan = if (p == null) null else UiPlan(
            title = p["title"] as? String ?: "",
            stepCount = (p["steps"] as? List<*>)?.size ?: 0,
            planMode = raw["planMode"] as? Boolean == true,
            steps = (p["steps"] as? List<*>).orEmpty().mapIndexedNotNull { index, item ->
              val m = item as? Map<*, *> ?: return@mapIndexedNotNull null
              val text = (m["text"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
              UiPlanStep(id = m["id"] as? String ?: "step-$index", text = text)
            },
            summary = p["summary"] as? String,
            risks = p["risks"] as? String,
            status = p["status"] as? String,
          ),
        )
      }
      "queue_updated" -> EventAction.Ignore(type, "派发队列属 D3")
      "compaction" -> EventAction.Ignore(type, "压缩提示手机不展示")
      "studio_job" -> {
        val job = raw["job"] as? Map<*, *>
        val projectId = (job?.get("projectId") as? String)?.takeIf { it.isNotBlank() }
        val jobId = (job?.get("jobId") as? String)?.takeIf { it.isNotBlank() }
        if (job == null || projectId == null || jobId == null) {
          EventAction.Unparsed(type, "缺 job.projectId / jobId")
        } else {
          EventAction.StudioJobUpdated(
            projectId = projectId,
            jobId = jobId,
            kind = job["kind"] as? String ?: "",
            state = job["status"] as? String ?: "",
            progress = (job["progress"] as? Number)?.toInt()?.coerceIn(0, 100),
            label = job["label"] as? String ?: "",
            message = job["message"] as? String,
            atMillis = at,
          )
        }
      }
      "notice" -> EventAction.NoticeRaised(
        sessionId = sessionId,
        level = raw["level"] as? String ?: "info",
        message = raw["message"] as? String ?: "",
        atMillis = at,
      )
      "episodes_captured" -> EventAction.Ignore(type, "记忆片段手机不展示")
      "error" -> {
        val err = raw["error"] as? Map<*, *>
        EventAction.ErrorRaised(
          sessionId = sessionId,
          code = err?.get("code") as? String ?: "error",
          message = err?.get("message") as? String ?: "桌面这一轮出错",
          atMillis = at,
        )
      }
      "demo_step" -> EventAction.Ignore(type, "演示模式手机不做")

      else -> EventAction.Unparsed(type, "未知 type")
    }

    return Parsed(seq, sessionId, action)
  }

  private fun parseSnapshot(snap: Map<String, Any?>): SnapshotData {
    val sessions = (snap["sessions"] as? List<*>)?.mapNotNull { item ->
      val m = item as? Map<*, *> ?: return@mapNotNull null
      val id = m["id"] as? String ?: return@mapNotNull null
      val messages = (m["messages"] as? List<*>)?.mapNotNull { rawMsg ->
        val mm = rawMsg as? Map<*, *> ?: return@mapNotNull null
        UiMessageCodec.fromWire(mm)
      } ?: emptyList()
      SnapshotSession(
        id = id,
        title = m["title"] as? String ?: "",
        liveMessageId = (m["liveMessageId"] as? String)?.takeIf { it.isNotBlank() },
        messages = messages,
      )
    } ?: emptyList()

    val runs = (snap["runs"] as? List<*>)?.mapNotNull { item ->
      val m = item as? Map<*, *> ?: return@mapNotNull null
      val sessionId = m["sessionId"] as? String ?: return@mapNotNull null
      val runId = m["runId"] as? String ?: return@mapNotNull null
      SnapshotRun(
        sessionId = sessionId,
        runId = runId,
        startedAtMillis = (m["startedAtMillis"] as? Number)?.toLong() ?: 0L,
        stepCount = (m["stepCount"] as? Number)?.toInt() ?: 0,
      )
    } ?: emptyList()

    val approvals = (snap["approvals"] as? List<*>)?.mapNotNull { item ->
      val m = item as? Map<*, *> ?: return@mapNotNull null
      val requestId = (m["requestId"] as? String) ?: (m["id"] as? String) ?: return@mapNotNull null
      UiApproval(
        requestId = requestId,
        toolName = m["toolName"] as? String ?: "",
        sessionId = m["sessionId"] as? String ?: "",
        requestedAtMillis = (m["at"] as? Number)?.toLong()
          ?: (m["createdAt"] as? Number)?.toLong() ?: 0L,
        title = m["title"] as? String ?: "",
        summary = m["summary"] as? String ?: "",
        category = m["category"] as? String ?: "",
        risk = m["risk"] as? String ?: "",
        allowAlways = m["allowAlways"] as? Boolean,
      )
    } ?: emptyList()

    val artifacts = IgnoreBridges.artifactsOf(snap)
    val membersRaw = snap["members"] as? Map<*, *>
    val members = if (membersRaw == null) null
    else IgnoreBridges.membersOf(mapOf("state" to membersRaw))

    return SnapshotData(
      sessions = sessions,
      activeSessionId = (snap["activeSessionId"] as? String)?.takeIf { it.isNotBlank() },
      runs = runs,
      approvals = approvals,
      artifacts = artifacts,
      artifactsSessionId = (snap["artifactsSessionId"] as? String)?.takeIf { it.isNotBlank() },
      members = members,
      superGoal = (membersRaw?.get("goal") as? String)?.takeIf { it.isNotBlank() },
      superProjectId = (membersRaw?.get("projectId") as? String)?.takeIf { it.isNotBlank() },
      superSessionId = (snap["activeSessionId"] as? String)?.takeIf { it.isNotBlank() },
      // 告诉用户一个假事实。
      workspaceFiles = (snap["workspaceFiles"] as? List<*>)
        ?.mapNotNull { item ->
          ((item as? Map<*, *>)?.get("path") as? String)?.takeIf { it.isNotBlank() }
        },
    )
  }

  val knownTypes: Set<String> = setOf(
    "run_started", "run_finished",
    "message_started", "message_delta", "message_completed", "messages_replaced",
    "session_updated",
    "snapshot",
    "studio_job",
    "permission_requested", "permission_resolved",
    "tool_started", "tool_progress", "tool_finished",
    "question_asked", "question_answered",
    "todos_updated", "artifacts_updated", "usage_updated",
    "research_updated", "super_updated", "collaboration_updated",
    "goal_updated", "plan_updated", "queue_updated",
    "compaction", "notice", "episodes_captured", "error", "demo_step",
  )
}
