package studio.morrow.android

/**
 * 事件归并域：运行事件解析、UI 状态归并器，以及断线重连用的回放包。
 *
 * 三者共享同一套事件类型与序号语义：解析出来的序号要能直接喂给回放包，
 * 归并器又要能重放同一批事件而不重复渲染。放在一个文件里，这条时序才看得出来。
 */
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── 原 state/EventStream.kt ──

// ───── EventParser.kt ─────
/**
 * 从 wire 格式的 JSON 解析成 [EventAction]。
 * 手机端真正会改状态的只有 12 种，其余 16 种归 [EventAction.Ignore]：
 */
object EventParser {

  /** 解析结果里带上原始 seq，供补漏判定 */
  data class Parsed(val seq: Long, val sessionId: String, val action: EventAction)

  /**
   * 解析一条事件。
   *
   * @return null 表示这条根本无法解析（没有 type / 没有 sessionId）。
   *   调用方应把它计入 `unparsedCount` 而不是静默丢弃。
   */
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
          // 两个 delta 都为空：这是合法的「心跳」分片，不改变状态
          else -> EventAction.Ignore(type, "空分片（无 textDelta / thinkingDelta）")
        }
      }

      "message_completed" -> {
        val mid = raw["messageId"] as? String
        val msg = raw["message"] as? Map<*, *>
        val text = msg?.get("text") as? String ?: ""
        if (mid == null) EventAction.Unparsed(type, "缺 messageId")
        else EventAction.MessageCompleted(sessionId, mid, text)
      }

      "messages_replaced" -> {
        val list = raw["messages"] as? List<*>
        val msgs = list?.mapNotNull { item ->
          val m = item as? Map<*, *> ?: return@mapNotNull null
          val id = m["id"] as? String ?: return@mapNotNull null
          UiMessage(
            id = id,
            role = when (m["role"] as? String) {
              "user" -> MessageRole.User
              "system" -> MessageRole.System
              else -> MessageRole.Assistant
            },
            text = m["text"] as? String ?: "",
            createdAtMillis = (m["createdAt"] as? Number)?.toLong() ?: 0L,
          )
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
          // 桌面信封带 `at`；用它算"最早等待多久"，而不是拿 0 兜底 ——
          // 0 会被渲染层当成 1970 年，显示"已等 56 年"。
          requestedAtMillis = (raw["at"] as? Number)?.toLong() ?: 0L,
          // 审批页要展示的四段信息：标题、摘要、类别、风险。
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

      // 下面这些事件在手机上是**真实可见**的：用户要能从手机回答
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
        // 普通工具进度与成员无关；只有带 memberTask 时才影响状态
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
          ),
        )
      }
      "queue_updated" -> EventAction.Ignore(type, "派发队列属 D3")
      "compaction" -> EventAction.Ignore(type, "压缩提示手机不展示")
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

  /**
   * 解析全量快照。
   *
   * 每一段都独立容错：一段坏掉（缺 id、类型不对）只丢那一条，
   * 不能让整份快照变成 Unparsed —— 否则手机在首连时会一直空白。
   */
  private fun parseSnapshot(snap: Map<String, Any?>): SnapshotData {
    val sessions = (snap["sessions"] as? List<*>)?.mapNotNull { item ->
      val m = item as? Map<*, *> ?: return@mapNotNull null
      val id = m["id"] as? String ?: return@mapNotNull null
      val messages = (m["messages"] as? List<*>)?.mapNotNull { rawMsg ->
        val mm = rawMsg as? Map<*, *> ?: return@mapNotNull null
        val mid = mm["id"] as? String ?: return@mapNotNull null
        UiMessage(
          id = mid,
          role = when (mm["role"] as? String) {
            "user" -> MessageRole.User
            "system" -> MessageRole.System
            else -> MessageRole.Assistant
          },
          text = mm["text"] as? String ?: "",
          createdAtMillis = (mm["createdAt"] as? Number)?.toLong() ?: 0L,
        )
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

    // 复用增量事件的解析器：快照里的 artifacts / members 与
    // `artifacts_updated` / `super_updated` 是同一形状。
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
    )
  }

  /**
   * 认识的 type 全集。测试用它核对 28 种一个不漏。
   *
   * 这个集合是**手写**的，不是从 when 推出来的 ——
   * 目的就是让「新增了事件却忘了加进 when」被测试抓到。
   */
  val knownTypes: Set<String> = setOf(
    "run_started", "run_finished",
    "message_started", "message_delta", "message_completed", "messages_replaced",
    "session_updated",
    "snapshot",
    "permission_requested", "permission_resolved",
    "tool_started", "tool_progress", "tool_finished",
    "question_asked", "question_answered",
    "todos_updated", "artifacts_updated", "usage_updated",
    "research_updated", "super_updated", "collaboration_updated",
    "goal_updated", "plan_updated", "queue_updated",
    "compaction", "notice", "episodes_captured", "error", "demo_step",
  )
}

// ───── EventReducer.kt ─────
/**
 * RuntimeEvent 的手机端解析结果。
 */
sealed interface EventAction {
  /** 这一轮开始了 */
  data class RunStarted(val sessionId: String, val runId: String, val userText: String, val assistantMessageId: String, val at: Long) : EventAction

  /** 这一轮结束了（成功/失败/中断都算） */
  data class RunFinished(val sessionId: String, val runId: String, val reason: String? = null) : EventAction

  /** 一条消息开始生成 */
  data class MessageStarted(val sessionId: String, val messageId: String, val role: MessageRole) : EventAction

  /** 流式分片。**这是唯一需要合并的事件类型** */
  data class MessageDelta(val sessionId: String, val messageId: String, val kind: DeltaKind, val text: String) : EventAction

  /** 一条消息生成完毕（带最终全文） */
  data class MessageCompleted(val sessionId: String, val messageId: String, val text: String) : EventAction

  /** 整个消息列表被替换（重连对齐 / 压缩后用） */
  data class MessagesReplaced(val sessionId: String, val messages: List<UiMessage>) : EventAction

  /** 会话元信息更新（标题） */
  data class SessionUpdated(val sessionId: String, val title: String?) : EventAction

  /** 新增一条待审批 */
  data class PermissionRequested(
  val sessionId: String,
  val requestId: String,
  val toolName: String,
  /** 桌面信封的 `at`（毫秒）。0 = 旧事件没带时间，渲染层按"未知"处理。 */
  val requestedAtMillis: Long = 0L,
  val title: String = "",
  val summary: String = "",
  val category: String = "",
  val risk: String = "",
  /** null = 桌面没给；true/false 决定审批页是否显示「始终允许」 */
  val allowAlways: Boolean? = null,
) : EventAction

  /** 一条审批已解决（无论谁解决的） */
  data class PermissionResolved(val sessionId: String, val requestId: String) : EventAction

  /**
   * 不改变手机端状态。
   *
   * 用 data class 而不是 object 是为了在回放日志里能看出
   * 「这条事件被解析认识了，只是手机不关心」。
   */
  data class Ignore(val type: String, val why: String) : EventAction

  /**
   * 用量事件。**不是**一次就能定出 C1 的六个字段 ——
   * 它只往「今日累计」里加一笔 tokens，其余五项由别的动作改。
   */
  data class UsageReported(val tokens: Long, val hadPayload: Boolean) : EventAction

  /**
   * 产物整表。`items` 为 null = 这次事件没带可用数组（保持原列表不动）；
   * 空列表 = 桌面明确说现在没有产物。两者不能混。
   */
  data class ArtifactsReplaced(
    val sessionId: String,
    val items: List<IgnoreBridges.ArtifactView>?,
  ) : EventAction

  /** 协作状态整表。`members` 为空 = 协作结束。 */
  data class MembersReplaced(
    val members: List<IgnoreBridges.MemberView>,
    /** 协作总目标。快照与增量都带；空/缺 = 桌面没给（不编造） */
    val goal: String? = null,
    /** 协作所属项目；手机干预需要（旧版桌面可能缺）。 */
    val projectId: String? = null,
    /** 协作所属会话；重试/发指令需要。 */
    val sessionId: String? = null,
  ) : EventAction

  /**
   * 全量快照（桌面在首连或补漏不足时主动下发）。
   *
   * 与增量事件的区别：这是**替换**而不是合并 —— 快照之后手机不应再
   * 保留快照里没有的会话/审批。这样「桌面删了一个会话」在手机上才会消失。
   */
  data class Snapshot(val data: SnapshotData) : EventAction

  /** 单个成员的增量状态（`tool_progress.memberTask`），按 index 合并。 */
  data class MemberPatched(val merged: List<IgnoreBridges.MemberView>) : EventAction

  /** 无法解析（未知 type / 缺字段）。要计入错误，不能静默 */
  data class Unparsed(val type: String, val why: String) : EventAction

  /** `tool_started`：开始执行一次工具调用 */
  data class ToolStarted(val sessionId: String, val messageId: String, val call: UiToolCall) : EventAction

  /** `tool_finished`：一次工具调用结束（成功/失败/被拒/被中断） */
  data class ToolFinished(
    val sessionId: String,
    val messageId: String,
    val toolUseId: String,
    val status: ToolCallStatus,
    val diffAdded: Int? = null,
    val diffRemoved: Int? = null,
    val created: Boolean = false,
    val deleted: Boolean = false,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val durationMillis: Long? = null,
  ) : EventAction

  /** `todos_updated`：整表替换待办 */
  data class TodosUpdated(val sessionId: String, val todos: List<UiTodo>) : EventAction

  /** `plan_updated`：整表替换计划 */
  data class PlanUpdated(val sessionId: String, val plan: UiPlan?) : EventAction

  /** `goal_updated`：整表替换目标态 */
  data class GoalUpdated(val sessionId: String, val goal: UiGoal?) : EventAction

  /** `notice`：桌面提示条 */
  data class NoticeRaised(val sessionId: String, val level: String, val message: String, val atMillis: Long) : EventAction

  /** `error`：桌面这一轮出错 */
  data class ErrorRaised(val sessionId: String, val code: String, val message: String, val atMillis: Long) : EventAction
}

enum class DeltaKind { Text, Thinking }

/**
 * RuntimeEvent 归并器。
 */
object EventReducer {

  /**
   * 应用一条事件。
   *
   * 纯函数：不读时钟、不碰 Android API、不改入参。
   */
  fun apply(state: MorrowUiState, action: EventAction, seq: Long = 0L): MorrowUiState {
    // 快照是重新对齐：桌面进程重启后 seq 会从 1 重新开始，而手机内存里
    // 可能还留着上次运行的 lastSeq（例如 3496）。如果这里仍取 max，
    // 重连时手机会告诉桌面「我收到 3496 了」，桌面把 1–N 的事件全部跳过，
    // 断线期间的数据就永久丢了。所以快照必须**把 lastSeq 重置为快照自己的 seq**。
    if (action is EventAction.Snapshot) {
      val realigned = if (seq > 0L) state.copy(lastSeq = seq) else state
      return applyInner(realigned, action)
    }
    // seq 与 at 是两件事：seq 是事件序号（单调，用于补漏），at 是墙上时间。
    // 初版把 at 当 seq 写进 state，回放测试立刻抓到（lastSeq 报 4226 而真实最大 seq 是 3496）。
    // 现在 seq 由调用方单独传，与 action 内部的时间字段无关。
    val base = if (seq > 0L && seq != state.lastSeq) state.copy(lastSeq = maxOf(state.lastSeq, seq)) else state
    return applyInner(base, action)
  }

  private fun applyInner(state: MorrowUiState, action: EventAction): MorrowUiState {
    return when (action) {
      /**
       * 快照是**替换**：快照里没有的会话/审批必须消失，否则桌面删掉的东西
       * 会永远留在手机上。`today` 不在快照里（桌面没有跨会话的今日累计快照），
       * 保持原值 —— 已经收到的增量不该被一次重连清掉。
       */
      is EventAction.Snapshot -> {
        val d = action.data
        state.copy(
          sessions = d.sessions.associate {
            it.id to SessionUi(
              id = it.id,
              title = it.title,
              messages = it.messages,
              liveMessageId = it.liveMessageId,
            )
          },
          runs = d.runs.associate {
            it.sessionId to RunState(
              runId = it.runId,
              startedAtMillis = it.startedAtMillis,
              stepCount = it.stepCount,
            )
          },
          pendingApprovals = d.approvals.associateBy { it.requestId },
          activeSessionId = d.activeSessionId ?: d.sessions.firstOrNull()?.id,
          snapshotLoaded = true,
          artifacts = d.artifacts,
          artifactsSessionId = d.artifactsSessionId,
          members = d.members,
          superGoal = d.superGoal,
          superProjectId = d.superProjectId,
          superSessionId = d.superSessionId,
        )
      }

      is EventAction.RunStarted -> {
        val session = state.sessions[action.sessionId] ?: SessionUi(action.sessionId, "")
        // 用户消息立刻上屏（乐观，桌面也是先插 userMessage 再跑）
        val userMsg = UiMessage(
          id = "user-${action.runId}",
          role = MessageRole.User,
          text = action.userText,
          createdAtMillis = action.at,
        )
        // 助手占位消息：这样 delta 到达时找得到容器
        val assistantMsg = UiMessage(
          id = action.assistantMessageId,
          role = MessageRole.Assistant,
          text = "",
          streaming = true,
          createdAtMillis = action.at + 1,
        )
        val merged = (session.messages + userMsg + assistantMsg)
          .distinctBy { it.id }
          .sortedBy { it.createdAtMillis }
        state.copy(
          sessions = state.sessions + (action.sessionId to session.copy(
            messages = merged,
            liveMessageId = action.assistantMessageId,
          )),
          runs = state.runs + (action.sessionId to RunState(
            runId = action.runId,
            startedAtMillis = action.at,
          )),
          // 今日累计（跨会话）：C1 的 rounds 与 running 从这里涨。
          //
          // 这一段曾经**被弄丢过**：改 lastSeq 那轮替换了 copy 参数列表，
          // 把 today 一并覆盖掉了，于是 rounds / running 永远是 0，
          // 而 RunFinished 那边的 onRunFinished 还在 —— 表现为
          // 「running 一直是 0、completed 却会涨」这种自相矛盾的数字。
          // 抓到它的是投影层测试（今日统计给真实数字），不是归并层测试：
          // 归并层的断言只看消息与 runs，不看今日累计。
          today = IgnoreBridges.onRunStarted(state.today ?: IgnoreBridges.TodayAccumulator()),
        )
      }

      is EventAction.RunFinished -> {
        val session = state.sessions[action.sessionId]
        state.copy(
          // 只删这个会话的 run。别的会话在跑就继续留着 ——
          // 这是第 3 条验收的结构性保证
          runs = state.runs - action.sessionId,
          // 今日累计：running 减一，并按 reason 计入 completed / failed
          today = IgnoreBridges.onRunFinished(
            state.today ?: IgnoreBridges.TodayAccumulator(), action.reason,
          ),
          sessions = if (session == null) state.sessions else {
            state.sessions + (action.sessionId to session.copy(
              // 这一轮结束，不再有 live 消息
              liveMessageId = null,
              messages = session.messages.map {
                if (it.streaming && it.id == session.liveMessageId) it.copy(streaming = false) else it
              },
            ))
          },
        )
      }

      is EventAction.MessageStarted -> {
        val session = state.sessions[action.sessionId] ?: SessionUi(action.sessionId, "")
        if (session.messages.any { it.id == action.messageId }) {
          // 幂等：重复的 message_started 不能插两条
          state
        } else {
          state.copy(
            sessions = state.sessions + (action.sessionId to session.copy(
              messages = session.messages + UiMessage(
                id = action.messageId,
                role = action.role,
                text = "",
                streaming = action.role == MessageRole.Assistant,
                createdAtMillis = (session.messages.maxOfOrNull { it.createdAtMillis } ?: 0L) + 1,
              ),
              liveMessageId = if (action.role == MessageRole.Assistant) action.messageId else session.liveMessageId,
            ))
          )
        }
      }

      is EventAction.MessageDelta -> {
        // 会话还不存在（delta 先于任何事件到达，或服务端没发 run_started）：
        // 必须建一个空会话再放消息，不能 return state 丢掉这段文字。
        val session = state.sessions[action.sessionId]
          ?: SessionUi(action.sessionId, "")
        val idx = session.messages.indexOfFirst { it.id == action.messageId }
        if (idx < 0) {
          // delta 先于 message_started 到达（网络乱序）：补一条容器而不是丢弃。
          // 丢弃会让这段文字永久消失 —— 蓝图第 2 条验收「不丢失」
          val created = UiMessage(
            id = action.messageId,
            role = MessageRole.Assistant,
            text = if (action.kind == DeltaKind.Text) action.text else "",
            thinking = if (action.kind == DeltaKind.Thinking) action.text else null,
            streaming = true,
            createdAtMillis = (session.messages.maxOfOrNull { it.createdAtMillis } ?: 0L) + 1,
          )
          return state.copy(
            sessions = state.sessions + (action.sessionId to session.copy(
              messages = session.messages + created,
              liveMessageId = action.messageId,
            ))
          )
        }
        val msg = session.messages[idx]
        val updated = when (action.kind) {
          DeltaKind.Text -> msg.copy(text = msg.text + action.text)
          DeltaKind.Thinking -> msg.copy(thinking = (msg.thinking ?: "") + action.text)
        }
        state.copy(
          sessions = state.sessions + (action.sessionId to session.copy(
            messages = session.messages.toMutableList().also { it[idx] = updated },
          ))
        )
      }

      is EventAction.MessageCompleted -> {
        val session = state.sessions[action.sessionId] ?: return state
        val idx = session.messages.indexOfFirst { it.id == action.messageId }
        if (idx < 0) {
          state.copy(
            sessions = state.sessions + (action.sessionId to session.copy(
              messages = session.messages + UiMessage(
                id = action.messageId,
                role = MessageRole.Assistant,
                text = action.text,
                createdAtMillis = (session.messages.maxOfOrNull { it.createdAtMillis } ?: 0L) + 1,
              ),
            ))
          )
        } else {
          val msg = session.messages[idx]
          state.copy(
            sessions = state.sessions + (action.sessionId to session.copy(
              messages = session.messages.toMutableList().also {
                // 用最终全文覆盖累积分片：这是「对齐」而不是「追加」
                it[idx] = msg.copy(text = action.text, streaming = false)
              },
              liveMessageId = if (session.liveMessageId == action.messageId) null else session.liveMessageId,
            ))
          )
        }
      }

      is EventAction.MessagesReplaced -> {
        val session = state.sessions[action.sessionId] ?: SessionUi(action.sessionId, "")
        state.copy(
          sessions = state.sessions + (action.sessionId to session.copy(
            messages = action.messages.sortedBy { it.createdAtMillis },
            // 替换后 live 消息可能已不在列表里，要重新判定
            liveMessageId = action.messages.lastOrNull { it.streaming }?.id,
          ))
        )
      }

      is EventAction.SessionUpdated -> {
        val session = state.sessions[action.sessionId] ?: SessionUi(action.sessionId, "")
        state.copy(
          sessions = state.sessions + (action.sessionId to session.copy(
            title = action.title ?: session.title,
          ))
        )
      }

      is EventAction.PermissionRequested -> state.copy(
        pendingApprovals = state.pendingApprovals + (action.requestId to UiApproval(
          requestId = action.requestId,
          toolName = action.toolName,
          sessionId = action.sessionId,
          requestedAtMillis = action.requestedAtMillis,
          title = action.title,
          summary = action.summary,
          category = action.category,
          risk = action.risk,
          allowAlways = action.allowAlways,
        ))
      )

      is EventAction.PermissionResolved -> state.copy(
        pendingApprovals = state.pendingApprovals - action.requestId
      )

      // ── 三类 Ignore 事件的真正接线（D1 第二批） ──
      // 注意：今日累计是**跨会话**的，这几个分支改的是顶层字段，
      // 不碰 sessions —— C1 要的是「今天」而不是「这个会话」。

      is EventAction.UsageReported -> {
        val acc = state.today ?: IgnoreBridges.TodayAccumulator()
        state.copy(
          today = IgnoreBridges.accumulateUsage(
            acc,
            // 这里重建一个只有 usage 的载荷，让展开器只做它擅长的事
            mapOf("usage" to mapOf("inputTokens" to action.tokens, "outputTokens" to 0L)),
          ).copy(sawAnything = acc.sawAnything || action.hadPayload)
        )
      }

      is EventAction.ArtifactsReplaced -> {
        // null = 载荷不可用 → 原列表不动（不能当成「桌面删光了」）
        if (action.items == null) state
        else {
          val acc = state.today ?: IgnoreBridges.TodayAccumulator()
          state.copy(
            artifacts = action.items,
            artifactsSessionId = action.sessionId,
            today = IgnoreBridges.onArtifactsCount(acc, action.items.size),
          )
        }
      }

      is EventAction.MembersReplaced -> state.copy(
      members = action.members,
      superGoal = action.goal,
      superProjectId = action.projectId ?: state.superProjectId,
      superSessionId = action.sessionId ?: state.superSessionId,
    )

      is EventAction.MemberPatched -> {
        // 解析阶段拿不到已有列表，所以这里做真正的合并：
        // 把解析出的一条并进 state 里现有的成员列表。
        val existing = state.members ?: emptyList()
        val patched = action.merged.fold(existing) { acc, m ->
          IgnoreBridges.mergeMember(acc, mapOf("memberTask" to mapOf(
            "index" to m.index, "title" to m.title, "agent" to m.agent,
            "status" to m.state, "stepCount" to m.stepCount,
            "note" to m.note, "summary" to m.summary, "error" to m.error,
          )))
        }
        state.copy(members = patched)
      }

      is EventAction.Ignore -> state

      is EventAction.Unparsed -> state

      is EventAction.ToolStarted -> {
        val session = state.sessions[action.sessionId] ?: SessionUi(action.sessionId, "")
        val idx = session.messages.indexOfFirst { it.id == action.messageId }
        if (idx < 0) {
          // 工具事件先于消息体到达（桌面先发 tool_started 再补正文）：
          // 建一条空容器而不是丢弃，否则用户会看不到「正在改文件」这一步。
          val created = UiMessage(
            id = action.messageId,
            role = MessageRole.Assistant,
            text = "",
            streaming = true,
            createdAtMillis = action.call.startedAtMillis,
            tools = listOf(action.call),
          )
          state.copy(
            sessions = state.sessions + (action.sessionId to session.copy(
              messages = session.messages + created,
            )),
            runs = bumpRun(state.runs, action.sessionId, action.call),
          )
        } else {
          val msg = session.messages[idx]
          // 幂等：补漏重放同一条 tool_started 不重复加
          if (msg.tools.any { it.id == action.call.id }) state
          else state.copy(
            sessions = state.sessions + (action.sessionId to session.copy(
              messages = session.messages.toMutableList().also {
                it[idx] = msg.copy(tools = msg.tools + action.call)
              },
            )),
            runs = bumpRun(state.runs, action.sessionId, action.call),
          )
        }
      }

      is EventAction.ToolFinished -> {
        val session = state.sessions[action.sessionId] ?: return state
        val idx = session.messages.indexOfFirst { m -> m.tools.any { it.id == action.toolUseId } }
        // 找不到对应的 tool_started：可能断线期间开始、重连后才收到结束。
        // 优先挂到消息体上（消息可能已经在，只是缺这一步）；实在没有容器
        // 才补一条空消息 —— 否则会插入一条与既有消息同 id 的重复项，
        // LazyColumn 的 key 冲突，整列重建且内容错乱。
        val orphan = UiToolCall(
          id = action.toolUseId,
          name = "",
          title = "工具调用",
          category = "",
          status = action.status,
          diffAdded = action.diffAdded,
          diffRemoved = action.diffRemoved,
          created = action.created,
          deleted = action.deleted,
          exitCode = action.exitCode,
          timedOut = action.timedOut,
          durationMillis = action.durationMillis,
        )
        val patched = if (idx < 0) {
          val host = session.messages.indexOfFirst { it.id == action.messageId }
          if (host >= 0) {
            session.messages.toMutableList().also {
              it[host] = it[host].copy(tools = it[host].tools + orphan)
            }
          } else {
            session.messages + UiMessage(
              id = action.messageId,
              role = MessageRole.Assistant,
              text = "",
              tools = listOf(orphan),
            )
          }
        } else {
          val msg = session.messages[idx]
          session.messages.toMutableList().also {
            it[idx] = msg.copy(
              tools = msg.tools.map { call ->
                if (call.id != action.toolUseId) call
                else call.copy(
                  status = action.status,
                  diffAdded = action.diffAdded ?: call.diffAdded,
                  diffRemoved = action.diffRemoved ?: call.diffRemoved,
                  created = call.created || action.created,
                  deleted = call.deleted || action.deleted,
                  exitCode = action.exitCode ?: call.exitCode,
                  timedOut = action.timedOut,
                  durationMillis = action.durationMillis,
                )
              },
            )
          }
        }
        state.copy(
          sessions = state.sessions + (action.sessionId to session.copy(messages = patched)),
          runs = state.runs[action.sessionId]?.let { run ->
            state.runs + (action.sessionId to run.copy(
              stepCount = run.stepCount + 1,
              currentTool = null,
            ))
          } ?: state.runs,
        )
      }

      is EventAction.TodosUpdated -> state.copy(todos = action.todos)

      is EventAction.PlanUpdated -> state.copy(plan = action.plan)

      is EventAction.GoalUpdated -> state.copy(goal = action.goal)

      is EventAction.NoticeRaised -> state.copy(
        notices = (listOf(
          UiNotice(
            id = "notice-${action.atMillis}-${state.notices.size}",
            level = action.level,
            message = action.message,
            atMillis = action.atMillis,
          ),
        ) + state.notices).take(MAX_NOTICES),
      )

      is EventAction.ErrorRaised -> state.copy(
        notices = (listOf(
          UiNotice(
            id = "error-${action.atMillis}-${state.notices.size}",
            level = "error",
            message = action.message,
            atMillis = action.atMillis,
          ),
        ) + state.notices).take(MAX_NOTICES),
      )
    }
  }

  /** 记下「当前在跑哪个工具」，工位页的活动行用它。 */
  private fun bumpRun(
    runs: Map<String, RunState>,
    sessionId: String,
    call: UiToolCall,
  ): Map<String, RunState> {
    val run = runs[sessionId] ?: return runs
    return runs + (sessionId to run.copy(currentTool = call.title.ifBlank { call.name }))
  }

  /**
   * 切换前台会话。
   * > 快速连续切换时，较早的加载结果不能覆盖后选的会话
   */
  fun selectSession(state: MorrowUiState, sessionId: String?): MorrowUiState =
    state.copy(activeSessionId = sessionId)

  /**
   * 批量应用一串动作 —— 这就是「回放」。
   *
   * @return 最终状态
   */
  fun replay(initial: MorrowUiState, actions: List<EventAction>): MorrowUiState =
    actions.fold(initial) { s, a -> apply(s, a) }

  /**
   * **24ms 合并**：把一串动作里可合并的 delta 压成尽量少的动作。
   * 规则照抄桌面 `store.ts:520` 的 `flushPendingDeltas`：
   * 其它动作原样通过，并**打断合并链**（不能跨过它们合并）。
   */
  fun coalesce(actions: List<EventAction>): List<EventAction> {
    val out = ArrayList<EventAction>(actions.size)
    for (a in actions) {
      val last = out.lastOrNull()
      if (a is EventAction.MessageDelta && last is EventAction.MessageDelta &&
        last.sessionId == a.sessionId && last.messageId == a.messageId && last.kind == a.kind
      ) {
        out[out.size - 1] = last.copy(text = last.text + a.text)
      } else {
        out.add(a)
      }
    }
    return out
  }
}

// ───── EventStreamViewModel.kt ─────
/**
 * 把事件流推给 Compose 的那一层。
 */
class EventStreamViewModel(
  private val scope: CoroutineScope,
  private val applyMs: Long = DELTA_APPLY_MS,
  private val now: () -> Long = { System.currentTimeMillis() },
) {

  companion object {
    /** 与桌面 `store.ts:492` 的 `DELTA_APPLY_MS` 同值 */
    const val DELTA_APPLY_MS = 24L
  }

  /** 当前状态。Compose 读它会订阅；只在 flush 时整体替换一次。 */
  var state: MorrowUiState by mutableStateOf(MorrowUiState())
    private set

  /**
   * 最近一次上行指令的失败原因（审批已失效 / 运行已结束 / 桌面拒绝）。
   *
   * 与 [state] 分开：这是「操作反馈」不是「桌面状态」。混进 MorrowUiState
   * 会让快照/回放测试也要维护它，而那些测试与上行无关。
   */
  var lastInvokeError: String? by mutableStateOf(null)
    private set

  /**
   * 一批事件应用后的回调（生产 = 通知桥）。
   *
   * 参数是**应用前/后**的完整状态，外加原始事件列表（`run_finished`
   * 的 reason 不进入状态，但通知需要它区分「完成」与「卡住」）。
   * 只在状态真的变化时调用，避免空批次触发通知。
   */
  var onBatchApplied: ((before: MorrowUiState, after: MorrowUiState, events: List<Map<String, Any?>>) -> Unit)? = null

  fun onInvokeResult(ok: Boolean, error: String?) {
    lastInvokeError = if (ok) null else (error ?: "桌面未接受这次操作")
  }

  fun clearInvokeError() {
    lastInvokeError = null
  }

  /**
   * 作品库按需结果（`works.get`）。
   *
   * 不走事件归并：它没有 seq，也不参与补漏；由 MainActivity 在 result 帧
   * 到达后直接写入。null = 结果缺字段（保持「未知」）。
   */
  fun onWorksLoaded(works: List<WorkView>?) {
    if (works == null) return
    state = state.copy(works = works)
  }

  /** 已缓冲的 delta（按到达顺序） */
  private val pending = mutableListOf<PendingDelta>()

  /** 待执行的 flush 任务。非 null 表示已排程 */
  private var flushJob: Job? = null

  /** 上一次真正把状态交给 Compose 的序号（用于统计与测试） */
  var lastFlushAtMillis: Long = 0L
    private set

  /** 真正执行的 flush 次数（不是事件数）。测试用来验证「不是每条事件都重组」 */
  var flushCount: Int = 0
    private set

  /** 缓冲里的分片数（测试用） */
  val pendingCount: Int get() = pending.size

  private data class PendingDelta(
    val sessionId: String,
    val messageId: String,
    val kind: DeltaKind,
    val text: String,
  )

  /**
   * 收一条已解析的事件。
   *
   * - `MessageDelta` → 入缓冲 + 排程 24ms 后 flush
   * - 其它 → **先 flush**，再立即应用（顺序保证，见类注释）
   */
  fun onEvent(parsed: EventParser.Parsed) {
    val action = parsed.action

    if (action is EventAction.MessageDelta) {
      pending += PendingDelta(parsed.sessionId, action.messageId, action.kind, action.text)
      // seq 必须**立刻**推进，不能等 flush。
      //
      // 缓冲只推迟「什么时候上屏」，不改变「已经收到了」这个事实。
      // 若等 flush 才推进，断线时缓冲区里的那批 delta 的 seq 会丢失，
      // 重连后服务端按 lastSeq 重发它们 —— 用户看到一段文字重复出现。
      // 而如果 flush 前进程被杀，seq 已经推进反而是对的：那段文字本来就
      // 没显示过，宁可少显示也不要重复显示（重复更像 bug）。
      if (parsed.seq > 0L) {
        state = state.copy(lastSeq = maxOf(state.lastSeq, parsed.seq))
      }
      scheduleFlush()
      return
    }

    // 顺序保证：先落地已缓冲的正文，再应用这条事件
    flushNow()
    applyToState(parsed)
  }

  /** 只排程一次：已有定时任务时不再叠加（否则 40 条/秒会排 40 个 timer） */
  private fun scheduleFlush() {
    if (flushJob != null) return
    flushJob = scope.launch {
      delay(applyMs)
      flushJob = null
      flushNow()
    }
  }

  /**
   * 立即把缓冲合并后应用一次。
   * 合并规则照抄桌面 `flushPendingDeltas`：
   * **只有相邻且 (sessionId, messageId, kind) 都相同的分片才能合并**。
   */
  fun flushNow() {
    flushJob?.cancel()
    flushJob = null

    if (pending.isEmpty()) return

    val merged = mutableListOf<PendingDelta>()
    pending.forEach { item ->
      val last = merged.lastOrNull()
      if (last != null &&
        last.sessionId == item.sessionId &&
        last.messageId == item.messageId &&
        last.kind == item.kind
      ) {
        merged[merged.size - 1] = last.copy(text = last.text + item.text)
      } else {
        merged += item
      }
    }
    pending.clear()

    var next = state
    merged.forEach { d ->
      next = EventReducer.apply(
        next,
        EventAction.MessageDelta(d.sessionId, d.messageId, d.kind, d.text),
      )
    }
    state = next
    flushCount += 1
    lastFlushAtMillis = now()
  }

  /** 直接收一条原始 wire 事件（真机路径：socket → parse → 这里） */
  fun onRawEvent(raw: Map<String, Any?>) {
    val parsed = EventParser.parse(raw) ?: return
    onEvent(parsed)
  }

  /** 批量收原始事件（回放/补漏时用） */
  fun onRawEvents(list: List<Map<String, Any?>>) {
    val before = state
    list.forEach { onRawEvent(it) }
    flushNow()
    val after = state
    if (after !== before) onBatchApplied?.invoke(before, after, list)
  }

  private fun applyToState(parsed: EventParser.Parsed) {
    state = EventReducer.apply(state, parsed.action, parsed.seq)
  }

  /** 切前台会话。也要先 flush，否则切走时最后几十毫秒的字会丢在缓冲里。 */
  fun selectSession(sessionId: String?) {
    flushNow()
    state = EventReducer.selectSession(state, sessionId)
  }

  /**
   * 页面离开时调用：把缓冲落地（不能直接丢弃 —— 那会让用户最后看到的
   * 一段文字永远停在半句）并停掉定时任务。
   */
  fun onCleared() {
    flushNow()
  }
}

// ───── MorrowUiState.kt ─────
/**
 * 手机端的会话运行状态。
 * 不同会话可能有同名的 runId，判断「是不是当前这一轮」必须
 */
data class RunState(
  val runId: String,
  /** 本轮开始时刻 */
  val startedAtMillis: Long,
  /** 当前正在执行的工具名（没有则 null） */
  val currentTool: String? = null,
  /** 已完成步数 */
  val stepCount: Int = 0,
)

/**
 * 一条对话消息的手机端模型。
 */
data class UiMessage(
  val id: String,
  val role: MessageRole,
  val text: String,
  /** 推理过程（thinking delta 累积）。没有则 null */
  val thinking: String? = null,
  /**
   * 是否仍在流式生成。
   *
   * 这个字段的用途不是「显示光标」，而是**判断切回会话时要不要接上流**：
   * 桌面快照带 `liveMessageId` 就是干这件事的（`store.ts:1239`）。
   */
  val streaming: Boolean = false,
  /** 创建时刻，用于稳定排序 */
  val createdAtMillis: Long = 0L,
  /**
   * 这条消息生成过程中发生的工具调用，按发生顺序。
   *
   * 挂在消息上而不是会话上：同一条助手消息可能连续执行十几个工具，
   * 界面上它们属于这段回答，不该混进下一条消息里。
   */
  val tools: List<UiToolCall> = emptyList(),
)

enum class MessageRole { User, Assistant, System }

/**
 * 一次工具调用在手机上的投影。
 *
 * 手机不是桌面终端的镜像：用户要判断的是「电脑上动了什么」——
 * 执行了哪条命令、改了哪个文件、成功还是失败。原文（命令、文件内容、
 * 输出日志）留在桌面，手机上只留一句人话标题与几个计数。
 */
data class UiToolCall(
  /** `tool_started.block.id` / `tool_finished.toolUseId`，同一次调用两端一致 */
  val id: String,
  /** 工具名，例如 `run_command` / `edit_file` / `delete_file` */
  val name: String,
  /** 人话标题，例如「修改 src/a.ts」「删除 out/b.txt」 */
  val title: String,
  /** `read` / `write` / `exec` / `meta`：决定图标与颜色 */
  val category: String,
  val status: ToolCallStatus,
  /** 新增/删除行数（文件类工具才有；null = 这类工具没有这个概念） */
  val diffAdded: Int? = null,
  val diffRemoved: Int? = null,
  /** 新建 / 删除文件 */
  val created: Boolean = false,
  val deleted: Boolean = false,
  /** 命令退出码（`exec` 类才有） */
  val exitCode: Int? = null,
  val timedOut: Boolean = false,
  val durationMillis: Long? = null,
  val startedAtMillis: Long = 0L,
) {
  /** 这一步是不是「改了磁盘」——文件增删改与命令执行都算 */
  val mutating: Boolean get() = category == "write" || category == "exec"
}

enum class ToolCallStatus { Running, Ok, Failed, Denied, Interrupted }

/** 手机端保留的提示条上限。超出后丢掉最旧的。 */
const val MAX_NOTICES = 5

/**
 * 桌面推来的一条提示/错误（`notice` / `error`）。
 *
 * 单独存起来而不是塞进消息：它不是对话内容，重启后也不该重放；
 * 用「最近 N 条」的环形列表，界面在顶部显示一条可关闭的提示条。
 */
data class UiNotice(
  val id: String,
  val level: String,
  val message: String,
  val atMillis: Long,
)

/** `todos_updated` 的一条待办。 */
data class UiTodo(val id: String, val text: String, val status: String)

/** `plan_updated` 的计划（手机只读展示）。 */
data class UiPlan(
  val title: String,
  val stepCount: Int,
  val planMode: Boolean,
)

/** `goal_updated` 的目标态。 */
data class UiGoal(
  val status: String,
  val round: Int,
  val maxRounds: Int,
  val stopReason: String? = null,
)

/**
 * 桌面作品库摘要（`works.get` 的结果）。
 *
 * 不含阶段明细与成片：手机作品页按 [StageCatalog] 现有的 10 段定义
 * 还原流水线，只有当前阶段与完成度来自桌面。
 */
data class WorkView(
  val id: String,
  val title: String,
  /** 'short' | 'novel' | 'drama'（未知值由投影层兜底） */
  val kind: String,
  val logline: String,
  val updatedAtMillis: Long,
  val stage: String,
  /** 0–100 */
  val progress: Int,
  val chapters: Int,
  val shots: Int,
  val assets: Int,
  val words: Long,
  /** 桌面 `works.get` 附带的成片清单（只有元数据，文件按需取）。 */
  val products: List<WorkProductView> = emptyList(),
)

/** 一件可预览的成片。 */
data class WorkProductView(
  val id: String,
  val label: String,
  /** `StudioDelivery['kind']`：mp4/comic/pdf/png/... */
  val kind: String,
  val bytes: Long,
  val createdAtMillis: Long,
)

/**
 * 一条待审批请求。C4 的 `ApprovalItem` 是它的渲染投影。
 *
 * 这里单独有一份，是因为归并层要能在还不该渲染时也持有它
 * （例如审批属于 A 会话、用户正在看 B 会话）。
 */
data class UiApproval(
  val requestId: String,
  val toolName: String,
  val sessionId: String,
  /** 请求到达时间（桌面信封 `at`）。0 = 未知，不能拿来算等待时长。 */
  val requestedAtMillis: Long = 0L,
  /** 审批页四段信息：做什么、影响什么、属于哪一类、风险多高 */
  val title: String = "",
  val summary: String = "",
  val category: String = "",
  val risk: String = "",
  /**
   * 桌面给出的「是否允许始终允许」。
   *
   * null = 没给（旧桌面）；true/false 才是明确结论。审批页不能把 null
   * 当成 true —— 危险动作没有「始终允许」是桌面安全模型的一部分。
   */
  val allowAlways: Boolean? = null,
)

// ───── 快照（手机端首连对齐） ─────

/** 快照里的一个会话。字段与增量事件同形，投影层不需要第二套解析。 */
data class SnapshotSession(
  val id: String,
  val title: String,
  val liveMessageId: String?,
  val messages: List<UiMessage>,
)

/** 快照里的一个运行中轮次。`stepCount` 为 0 = 桌面没给步数（未知）。 */
data class SnapshotRun(
  val sessionId: String,
  val runId: String,
  val startedAtMillis: Long,
  val stepCount: Int,
)

/**
 * 首连 / 补漏不足时桌面下发的全量状态。
 * 可空字段的语义必须保留：
 */
data class SnapshotData(
  val sessions: List<SnapshotSession>,
  val activeSessionId: String?,
  val runs: List<SnapshotRun>,
  val approvals: List<UiApproval>,
  val artifacts: List<IgnoreBridges.ArtifactView>?,
  val artifactsSessionId: String?,
  val members: List<IgnoreBridges.MemberView>?,
  /** 协作总目标（快照里的 `members.goal`） */
  val superGoal: String?,
  /** 协作项目 id（快照 `members.projectId`） */
  val superProjectId: String? = null,
  /** 协作会话 id（快照所属会话） */
  val superSessionId: String? = null,
)

/**
 * 手机端全部会话状态。
 * ## 这一层为什么不能直接塞进 Compose 的 State
 */
data class MorrowUiState(
  /** 所有已知会话的消息。键是 sessionId */
  val sessions: Map<String, SessionUi> = emptyMap(),
  /** 所有正在跑的轮次。键是 sessionId —— 一个会话同时只有一轮 */
  val runs: Map<String, RunState> = emptyMap(),
  /** 所有待审批。键是 requestId（同一条审批只属于一个会话） */
  val pendingApprovals: Map<String, UiApproval> = emptyMap(),
  /** 当前前台会话。null = 还没选 */
  val activeSessionId: String? = null,
  /** 最近一次应用的事件 seq。用于补漏判定 */
  val lastSeq: Long = 0L,
  /** 是否已从「快照」对齐过。未对齐时界面应显示「正在同步」而不是空 */
  val snapshotLoaded: Boolean = false,

  /**
   * 今日累计（跨会话）。
   * 而会话是按主题分的 —— 今天的数字必须跨会话加起来。
   */
  val today: IgnoreBridges.TodayAccumulator? = null,

  /**
   * 最近一次 `artifacts_updated` 给出的产物（**整表**，不是累加）。
   *
   * `null` = 还没收到过（不知道有没有产物）；`emptyList` = 桌面明确说
   * 现在没有产物。这两个在界面上必须是不同文案 —— 与 `today` 同一条规矩。
   */
  val artifacts: List<IgnoreBridges.ArtifactView>? = null,

  /**
   * 协作成员（来自 `super_updated` 整表 + `tool_progress.memberTask` 单条合并）。
   *
   * `null` = 不知道（还没收到 `super_updated`）；`emptyList` = 桌面说协作已结束。
   * 保持 null 语义的原因：C3 的空态文案是「没有正在协作的成员」，
   * 若把「不知道」也渲染成这句，用户会以为桌面那边真的没人干活。
   */
  val members: List<IgnoreBridges.MemberView>? = null,

    /**
     * 协作总目标（`super_updated.state.goal`）。
     *
     * 与 [members] 一起到达；单独存一份是为了协作页标题不必从成员列表
     * 反推目标。null = 桌面没给，不是「没有目标」。
     */
    val superGoal: String? = null,

    /** 协作项目 id（`super_updated.state.projectId`）；null = 旧版桌面没给 */
    val superProjectId: String? = null,

    /** 协作会话 id（事件信封的 `sessionId`） */
    val superSessionId: String? = null,

    /**
     * 桌面作品库摘要（`works.get` 按需拉取）。
     *
     * null = 还没拉到（不知道有没有作品）；空列表 = 桌面明确说没有作品。
     * 两者在作品页必须是不同文案 —— 「未知」不能写成「没有」。
     */
    val works: List<WorkView>? = null,

  /** 最近一次产物更新属于哪个会话（C5 需要标注来源） */
  val artifactsSessionId: String? = null,

  /**
   * 桌面推来的提示与错误（`notice` / `error`），最新的在前。
   *
   * 只留最近 [MAX_NOTICES] 条：提示条是「现在发生了什么」，
   * 不是历史记录；无上限会让一次长轮次攒出几百条且没人看。
   */
  val notices: List<UiNotice> = emptyList(),

  /** `todos_updated` 的当前待办（整表替换）。null = 还没收到过 */
  val todos: List<UiTodo>? = null,

  /** `plan_updated` 的计划。null = 没有计划 */
  val plan: UiPlan? = null,

  /** `goal_updated` 的目标态。null = 没有目标模式 */
  val goal: UiGoal? = null,
) {
  /** 前台会话的状态。null = 没有或还没加载 */
  val active: SessionUi? get() = activeSessionId?.let { sessions[it] }

  /** 前台会话的 run。null = 前台没在跑 */
  val activeRun: RunState? get() = activeSessionId?.let { runs[it] }

  /**
   * 某一轮是不是某个会话的。
   *
   * 这条判断必须 sessionId + runId 一起看：不同会话的 runId
   * 可能撞名（桌面生成 runId 用的是时间戳 + 随机后缀，撞名概率低但非零）。
   * 蓝图第 3 条验收要的就是这个语义。
   */
  fun isRunOf(sessionId: String, runId: String): Boolean =
    runs[sessionId]?.runId == runId

  /** 某个会话的待审批列表（按 requestId 稳定排序） */
  fun approvalsOf(sessionId: String): List<UiApproval> =
    pendingApprovals.values
      .filter { it.sessionId == sessionId }
      .sortedBy { it.requestId }

  /** 全部会话里有没有在跑的 */
  val anyRunning: Boolean get() = runs.isNotEmpty()
}

/** 单个会话的状态 */
data class SessionUi(
  val id: String,
  val title: String,
  /** 消息列表，按 createdAtMillis 稳定排序 */
  val messages: List<UiMessage> = emptyList(),
  /**
   * 正在流式生成的那条消息 id。
   *
   * 桌面对应 `SessionMeta.liveMessageId`（`types.ts:1121`）。
   * 切回会话时靠它接上流式状态 —— 没有这个字段，切回来会
   * 「从下一个分片重新开始」（桌面真实修过的缺陷，蓝图 §01 提到）。
   */
  val liveMessageId: String? = null,
) {
  /** 正在流式生成的消息 */
  val liveMessage: UiMessage? get() = liveMessageId?.let { id -> messages.find { it.id == id } }
}
// ── 原 state/Replay.kt ──

// ───── ReplayPack.kt ─────
/**
 * 事件回放包 —— 蓝图 D1 第一条验收要的「≥5000 条」。
 * 合成包能证明什么、不能证明什么：
 * | 能证明 | 不能证明 |
 * 所以报告里不能写「已用录制包验证」。要写「用合成包验证了归并逻辑，
 * ## 合成规则照着桌面真实分布来（不是随便造）
 */
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

  /**
   * 生成回放包。
   *
   * @param rounds 每个会话的轮数（默认 8 轮 × 3 会话，总事件数远超 5000）
   */
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

        // ── 一轮里第一片 delta 刻意早于 message_started（模拟乱序） ──
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

        // 每 3 轮插一次工具进度与待办（属于 Ignore，验证不认识的事件不破坏状态）
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
            "requestId" to rid, "decision" to "once",
          )
        }

        // ── message_completed（最终全文覆盖） ──
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

    // 末尾再插一条 noise（未知 type），验证它不会破坏状态
    events += mapOf("type" to "totally_unknown_event", "seq" to next(), "sessionId" to "sess-A")

    return Pack(events = events, expected = expected, sessionIds = sessionIds)
  }
}

// ───── IgnoreBridges.kt ─────
/**
 * 把三类「桌面在用、手机之前归 Ignore」的事件展开成展示模型。
 * 详细映射与降级规则见 `docs/cards/D1b-事件到模型对照表.md`。
 * 所以这里全程读原始 `Map` 并用 `containsKey` 判断 —— 否则
 */
object IgnoreBridges {

  // ════════════════════ 1. usage → 今日累计 ════════════════════

  /**
   * 今日累计。**不是**从单个 `usage_updated` 映射出来的 ——
   * 那个事件只带一次请求的用量，而 C1 要的是「今天」的六项聚合。
   *
   * 所以这里维护的是累加器：`usage_updated` 往里加一笔，
   * `run_started` / `run_finished` 改计数，`artifacts_updated` 改产物数。
   */
  data class TodayAccumulator(
    val rounds: Int = 0,
    val tokens: Long = 0L,
    val completed: Int = 0,
    val running: Int = 0,
    val failed: Int = 0,
    val artifacts: Int = 0,
    /** 是否收到过任何一条能证明「今天有活动」的事件 */
    val sawAnything: Boolean = false,
  ) {
    fun toStats(): TodayStatsSnapshot = TodayStatsSnapshot(
      rounds = rounds, tokens = tokens, completed = completed,
      running = running, failed = failed, artifacts = artifacts,
    )
  }

  /** 与 C1 `TodayStats` 同形（避免 state 层依赖 ui 层）。 */
  data class TodayStatsSnapshot(
    val rounds: Int,
    val tokens: Long,
    val completed: Int,
    val running: Int,
    val failed: Int,
    val artifacts: Int,
  )

  /**
   * `usage_updated` → 累加 tokens。
   *
   * 缺 `inputTokens` 或 `outputTokens` 时**另一项照常加**，不整条丢弃 ——
   * 老版本桌面可能只发其中一个。
   */
  fun accumulateUsage(acc: TodayAccumulator, raw: Map<String, Any?>): TodayAccumulator {
    val usage = raw["usage"] as? Map<*, *> ?: return acc   // 整个缺失：不动
    val input = (usage["inputTokens"] as? Number)?.toLong() ?: 0L
    val output = (usage["outputTokens"] as? Number)?.toLong() ?: 0L
    // 两个都是 0 且都没这个 key：说明是空载荷，不算「见过活动」
    val hasAny = usage.containsKey("inputTokens") || usage.containsKey("outputTokens")
    return acc.copy(
      tokens = acc.tokens + input + output,
      sawAnything = acc.sawAnything || hasAny,
    )
  }

  /** `run_started` → rounds + 1，running + 1。 */
  fun onRunStarted(acc: TodayAccumulator): TodayAccumulator = acc.copy(
    rounds = acc.rounds + 1,
    running = acc.running + 1,
    sawAnything = true,
  )

  /**
   * `run_finished` → running - 1，并计入 completed 或 failed。
   *
   * `running` 不能减成负数：重复的 run_finished（补漏重放）会把计数打穿。
   */
  fun onRunFinished(acc: TodayAccumulator, reason: String?): TodayAccumulator {
    // 桌面 RunFinishReason 是 'complete' | 'cancelled' | 'error' | 'max-steps'。
    // 只有 'complete' 算成功：'max-steps' 是撞到步数上限（任务没做完），
    // 'cancelled' 是用户取消 —— 两者都不能计入 completed，否则今日统计会把
    // 「没做完」和「做完了」混成一类。reason 缺失（老版本）按成功处理并计入。
    val isFailed = reason == "error" || reason == "max-steps" || reason == "cancelled"
    return acc.copy(
      running = maxOf(0, acc.running - 1),
      completed = acc.completed + if (isFailed) 0 else 1,
      failed = acc.failed + if (isFailed) 1 else 0,
      sawAnything = true,
    )
  }

  /** `artifacts_updated` → 产物数 = 数组长度（整表，不是累加）。 */
  fun onArtifactsCount(acc: TodayAccumulator, count: Int): TodayAccumulator =
    acc.copy(artifacts = count, sawAnything = acc.sawAnything || count > 0)

  // ════════════════════ 2. artifacts_updated → 产物列表 ════════════════════

  /** 与 C1 `Artifact` 同形。`bytes` 为 null = 拿不到（path 型产物）。 */
  data class ArtifactView(
    val name: String,
    val format: String,
    val bytes: Long?,
    val atMillis: Long,
  )

  /** 桌面 `ArtifactKind` → 显示用格式标签（6 种，逐一对应）。 */
  private val KIND_TO_FORMAT = mapOf(
    "html" to "HTML",
    "markdown" to "MD",
    "code" to "CODE",
    "svg" to "SVG",
    "text" to "TXT",
    "file" to "FILE",
  )

  /**
   * `artifacts_updated` → 产物列表。
   *
   * **整表替换**。返回 null 表示「这次事件没带可用的数组」——
   * 调用方应保持原列表不动，不能当成「桌面删了全部产物」。
   */
  fun artifactsOf(raw: Map<String, Any?>): List<ArtifactView>? {
    val arr = raw["artifacts"] as? List<*> ?: return null
    return arr.mapNotNull { item ->
      val m = item as? Map<*, *> ?: return@mapNotNull null
      val title = (m["title"] as? String)?.takeIf { it.isNotBlank() }
      val path = m["path"] as? String
      val kind = m["kind"] as? String ?: "file"
      val name = title
        ?: path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
        ?: "未命名产物"
      // 优先 updatedAt，缺则 createdAt；都缺给 0（沉到列表底部，不假装刚创建）
      val at = (m["updatedAt"] as? Number)?.toLong()
        ?: (m["createdAt"] as? Number)?.toLong()
        ?: 0L
      // bytes：只有 inline 型（有 content）能算；path 型拿不到 → null（显示 —）
      val content = m["content"] as? String
      val bytes = content?.toByteArray(Charsets.UTF_8)?.size?.toLong()
      ArtifactView(
        name = name,
        format = KIND_TO_FORMAT[kind] ?: kind.uppercase(),
        bytes = bytes,
        atMillis = at,
      )
    }
  }

  // ════════════════════ 3. super_updated / memberTask → 成员 ════════════════════

  /** 与 C3 `MemberProgress` 同形（去掉 UI 层才需要的 operations / usage）。 */
  data class MemberView(
    val index: Int,
    val title: String,
    val agent: String,
    val agentTitle: String?,
    val state: String,        // 桌面原值 queued/running/... ，UI 层再转枚举
    val stepCount: Int?,
    val note: String?,
    val summary: String?,
    val error: String?,
    /** 桌面任务 id；手机干预（停止/重试/发指令）需要它。null = 旧版桌面未下发。 */
    val taskId: String? = null,
  )

  /** `works.get` 结果 → 作品摘要列表。缺 `works` 数组 = 未知（null），不是空列表。 */
  fun worksOf(raw: Map<String, Any?>): List<WorkView>? {
    val arr = raw["works"] as? List<*> ?: return null
    return arr.mapNotNull { item ->
      val m = item as? Map<*, *> ?: return@mapNotNull null
      val id = m["id"] as? String ?: return@mapNotNull null
      WorkView(
        id = id,
        title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: "未命名作品",
        kind = (m["kind"] as? String) ?: "short",
        logline = (m["logline"] as? String).orEmpty(),
        updatedAtMillis = (m["updatedAtMillis"] as? Number)?.toLong() ?: 0L,
        stage = (m["stage"] as? String) ?: "blueprint",
        progress = ((m["progress"] as? Number)?.toInt() ?: 0).coerceIn(0, 100),
        chapters = (m["chapters"] as? Number)?.toInt() ?: 0,
        shots = (m["shots"] as? Number)?.toInt() ?: 0,
        assets = (m["assets"] as? Number)?.toInt() ?: 0,
        words = (m["words"] as? Number)?.toLong() ?: 0L,
        products = (m["products"] as? List<*>).orEmpty().mapNotNull { item ->
          val pm = item as? Map<*, *> ?: return@mapNotNull null
          val productId = pm["id"] as? String ?: return@mapNotNull null
          WorkProductView(
            id = productId,
            label = (pm["label"] as? String)?.takeIf { it.isNotBlank() } ?: "未命名产物",
            kind = (pm["kind"] as? String) ?: "other",
            bytes = (pm["bytes"] as? Number)?.toLong() ?: 0L,
            createdAtMillis = (pm["createdAtMillis"] as? Number)?.toLong() ?: 0L,
          )
        },
      )
    }
  }

  /** `super_updated` → 协作总目标（手机不展示完整计划，只展示这一句）。 */
  fun goalOf(raw: Map<String, Any?>): String? {
    val state = raw["state"] as? Map<*, *> ?: return null
    return (state["goal"] as? String)?.takeIf { it.isNotBlank() }
  }

  /** `super_updated` → 协作项目/会话 id（干预时定位成员用）。 */
  fun projectIdOf(raw: Map<String, Any?>): String? =
    ((raw["state"] as? Map<*, *>)?.get("projectId") as? String)?.takeIf { it.isNotBlank() }

  fun sessionIdOf(raw: Map<String, Any?>): String? =
    (raw["sessionId"] as? String)?.takeIf { it.isNotBlank() }

  /** `super_updated` → 成员列表。`state` 为 null 或 `tasks` 空 → 返回空列表（协作结束）。 */
  fun membersOf(raw: Map<String, Any?>): List<MemberView> {
    val state = raw["state"] as? Map<*, *> ?: return emptyList()
    val tasks = state["tasks"] as? List<*> ?: return emptyList()
    return tasks.mapNotNull { t ->
      val m = t as? Map<*, *> ?: return@mapNotNull null
      val idx = (m["index"] as? Number)?.toInt() ?: return@mapNotNull null
      MemberView(
        index = idx,
        title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: "成员 $idx",
        agent = (m["agent"] as? String) ?: "general",
        agentTitle = m["agentTitle"] as? String,
        state = (m["status"] as? String) ?: "queued",
        stepCount = (m["stepCount"] as? Number)?.toInt(),
        note = m["note"] as? String,
        summary = m["summary"] as? String,
        error = m["error"] as? String,
        taskId = (m["taskId"] as? String)?.takeIf { it.isNotBlank() },
      )
    }
  }

  /**
   * `tool_progress.memberTask` → 单条合并。
   */
  fun mergeMember(existing: List<MemberView>, raw: Map<String, Any?>): List<MemberView> {
    val mt = raw["memberTask"] as? Map<*, *> ?: return existing
    val idx = (mt["index"] as? Number)?.toInt() ?: return existing

    val prev = existing.firstOrNull { it.index == idx }
    val merged = MemberView(
      index = idx,
      // 「没传」才回退；「传了空串」是桌面的真实意图（比如清理标题），照收
      title = if (mt.containsKey("title")) (mt["title"] as? String) ?: "" else prev?.title ?: "成员 $idx",
      agent = if (mt.containsKey("agent")) (mt["agent"] as? String) ?: "general" else prev?.agent ?: "general",
      agentTitle = if (mt.containsKey("agentTitle")) mt["agentTitle"] as? String else prev?.agentTitle,
      state = if (mt.containsKey("status")) (mt["status"] as? String) ?: "queued" else prev?.state ?: "queued",
      stepCount = if (mt.containsKey("stepCount")) (mt["stepCount"] as? Number)?.toInt() else prev?.stepCount,
      note = if (mt.containsKey("note")) mt["note"] as? String else prev?.note,
      summary = if (mt.containsKey("summary")) mt["summary"] as? String else prev?.summary,
      error = if (mt.containsKey("error")) mt["error"] as? String else prev?.error,
    )
    return if (prev == null) existing + merged
    else existing.map { if (it.index == idx) merged else it }
  }
}

