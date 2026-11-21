package studio.morrow.android

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
  data class MessageCompleted(
    val sessionId: String,
    val messageId: String,
    val text: String,
    /** 桌面下发的结构化块（表格 / 代码块 / 工具卡 / 错误）。空 = 旧桌面 */
    val blocks: List<UiBlock> = emptyList(),
    val interrupted: Boolean = false,
  ) : EventAction

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

  data class Ignore(val type: String, val why: String) : EventAction

  data class StudioJobUpdated(
    val projectId: String,
    val jobId: String,
    val kind: String,
    /** 桌面 `StudioJobStatus`：queued / running / done / failed / cancelled */
    val state: String,
    /** null = 桌面没有可用百分比（不要编一个 0） */
    val progress: Int?,
    val label: String,
    val message: String?,
    val atMillis: Long,
  ) : EventAction

  data class UsageReported(val tokens: Long, val hadPayload: Boolean) : EventAction

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
    /** 行级 unified diff 原文（只有文件写入类工具才有；null = 没有或超限） */
    val diff: String? = null,
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

object EventReducer {

  fun apply(state: MorrowUiState, action: EventAction, seq: Long = 0L, nowMillis: Long = 0L): MorrowUiState {
    if (action is EventAction.Snapshot) {
      val realigned = if (seq > 0L) state.copy(lastSeq = seq) else state
      return applyInner(realigned, action)
    }
    val base = if (seq > 0L && seq != state.lastSeq) state.copy(lastSeq = maxOf(state.lastSeq, seq)) else state

    // ── M-07：今日累计跨天重置 ──
    //
    //
    val aligned = if (nowMillis > 0L) {
      base.copy(today = IgnoreBridges.forToday(base.today, nowMillis))
    } else {
      base
    }
    return applyInner(aligned, action)
  }

  private fun applyInner(state: MorrowUiState, action: EventAction): MorrowUiState {
    return when (action) {
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
          workspaceFiles = d.workspaceFiles?.map { WorkspaceFile(path = it) },
          superGoal = d.superGoal,
          superProjectId = d.superProjectId,
          superSessionId = d.superSessionId,
        )
      }

      is EventAction.RunStarted -> {
        val session = state.sessions[action.sessionId] ?: SessionUi(action.sessionId, "")
        val userMsg = UiMessage(
          id = "user-${action.runId}",
          role = MessageRole.User,
          text = action.userText,
          createdAtMillis = action.at,
        )
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
          //
          today = IgnoreBridges.onRunStarted(state.today ?: IgnoreBridges.TodayAccumulator()),
        )
      }

      is EventAction.RunFinished -> {
        val session = state.sessions[action.sessionId]
        state.copy(
          // 这是第 3 条验收的结构性保证
          runs = state.runs - action.sessionId,
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
        val session = state.sessions[action.sessionId]
          ?: SessionUi(action.sessionId, "")
        val idx = session.messages.indexOfFirst { it.id == action.messageId }
        if (idx < 0) {
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
                blocks = action.blocks,
                interrupted = action.interrupted,
              ),
            ))
          )
        } else {
          val msg = session.messages[idx]
          val blockTools = action.blocks.filterIsInstance<UiBlock.Tool>().map { it.call }
          state.copy(
            sessions = state.sessions + (action.sessionId to session.copy(
              messages = session.messages.toMutableList().also {
                it[idx] = msg.copy(
                  text = action.text,
                  streaming = false,
                  blocks = action.blocks,
                  interrupted = action.interrupted,
                  tools = UiMessageCodec.mergeTools(blockTools, msg.tools),
                )
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

      is EventAction.UsageReported -> {
        val acc = state.today ?: IgnoreBridges.TodayAccumulator()
        state.copy(
          today = IgnoreBridges.accumulateUsage(
            acc,
            mapOf("usage" to mapOf("inputTokens" to action.tokens, "outputTokens" to 0L)),
          ).copy(sawAnything = acc.sawAnything || action.hadPayload)
        )
      }

      is EventAction.ArtifactsReplaced -> {
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

      is EventAction.StudioJobUpdated -> {
        val key = action.projectId to action.jobId
        val next = state.studioJobs + (key to StudioJobView(
          jobId = action.jobId,
          projectId = action.projectId,
          kind = action.kind,
          state = action.state,
          progress = action.progress,
          label = action.label,
          message = action.message,
          atMillis = action.atMillis,
        ))
        val kept = if (next.size <= MAX_STUDIO_JOBS) {
          next
        } else {
          next.entries
            .sortedByDescending { it.value.atMillis }
            .take(MAX_STUDIO_JOBS)
            .associate { it.key to it.value }
        }
        state.copy(studioJobs = kept)
      }

      //
      is EventAction.Unparsed -> state.copy(
        unparsedTypes = state.unparsedTypes + (
          action.type to (state.unparsedTypes[action.type] ?: 0) + 1
        ),
      )

      is EventAction.ToolStarted -> {
        val session = state.sessions[action.sessionId] ?: SessionUi(action.sessionId, "")
        val idx = session.messages.indexOfFirst { it.id == action.messageId }
        if (idx < 0) {
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
          diff = action.diff,
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
                  diff = action.diff ?: call.diff,
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

      is EventAction.TodosUpdated -> patchSession(state, action.sessionId) { it.copy(todos = action.todos) }

      is EventAction.PlanUpdated -> patchSession(state, action.sessionId) { it.copy(plan = action.plan) }

      is EventAction.GoalUpdated -> patchSession(state, action.sessionId) { it.copy(goal = action.goal) }

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

  private fun patchSession(
    state: MorrowUiState,
    sessionId: String,
    patch: (SessionUi) -> SessionUi,
  ): MorrowUiState {
    if (sessionId.isBlank()) return state
    val existing = state.sessions[sessionId] ?: SessionUi(id = sessionId, title = "")
    return state.copy(sessions = state.sessions + (sessionId to patch(existing)))
  }

  /** 记下「当前在跑哪个工具」，工位页的活动行用它。 */
  private fun bumpRun(    runs: Map<String, RunState>,
    sessionId: String,
    call: UiToolCall,
  ): Map<String, RunState> {
    val run = runs[sessionId] ?: return runs
    return runs + (sessionId to run.copy(currentTool = call.title.ifBlank { call.name }))
  }

  fun selectSession(state: MorrowUiState, sessionId: String?): MorrowUiState =
    state.copy(activeSessionId = sessionId)

  fun replay(initial: MorrowUiState, actions: List<EventAction>): MorrowUiState =
    actions.fold(initial) { s, a -> apply(s, a) }

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
