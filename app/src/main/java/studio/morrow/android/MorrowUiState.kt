package studio.morrow.android

import androidx.compose.runtime.mutableStateOf

data class RunState(
  val runId: String,
  /** 本轮开始时刻 */
  val startedAtMillis: Long,
  /** 当前正在执行的工具名（没有则 null） */
  val currentTool: String? = null,
  /** 已完成步数 */
  val stepCount: Int = 0,
)

sealed interface UiBlock {
  data class Text(val text: String) : UiBlock
  data class Thinking(val text: String) : UiBlock
  /** [diff] 是**行级 unified diff 原文**，只有文件写入类工具才有。 */
  data class Tool(val call: UiToolCall, val diff: String? = null) : UiBlock
  data class Error(val message: String, val code: String? = null) : UiBlock
}

data class UiMessage(
  val id: String,
  val role: MessageRole,
  val text: String,
  /** 推理过程（thinking delta 累积）。没有则 null */
  val thinking: String? = null,
  val streaming: Boolean = false,
  /** 创建时刻，用于稳定排序 */
  val createdAtMillis: Long = 0L,
  val tools: List<UiToolCall> = emptyList(),
  val blocks: List<UiBlock> = emptyList(),
  /** 用户中途停止（桌面 `ChatMessage.interrupted`）。 */
  val interrupted: Boolean = false,
  val tokensUsed: Long? = null,
)

enum class MessageRole { User, Assistant, System }

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
  val diff: String? = null,
) {
  /** 这一步是不是「改了磁盘」——文件增删改与命令执行都算 */
  val mutating: Boolean get() = category == "write" || category == "exec"
}

enum class ToolCallStatus { Running, Ok, Failed, Denied, Interrupted }

/** 手机端保留的提示条上限。超出后丢掉最旧的。 */
const val MAX_NOTICES = 5

/** 创作台任务状态的保留上限（超出按时间淘汰最旧的）。 */
const val MAX_STUDIO_JOBS = 60

/** 一条创作台任务在手机上的状态（桌面 `StudioJob` 的裁剪版）。 */
data class StudioJobView(
  val jobId: String,
  val projectId: String,
  val kind: String,
  /** queued / running / done / failed / cancelled */
  val state: String,
  /** null = 桌面没给百分比（不要编造 0） */
  val progress: Int?,
  val label: String,
  val message: String?,
  val atMillis: Long,
) {
  val isRunning: Boolean get() = state == "running" || state == "queued"

  val isFailed: Boolean get() = state == "failed" || state == "cancelled"
}

data class UiNotice(
  val id: String,
  val level: String,
  val message: String,
  val atMillis: Long,
)

/** `todos_updated` 的一条待办。 */
data class UiTodo(val id: String, val text: String, val status: String)

/** `plan_updated` 的计划步骤。`id` 缺失时由解析层按序号补，界面只当稳定 key 用。 */
data class UiPlanStep(val id: String, val text: String)

data class UiPlan(
  val title: String,
  val stepCount: Int,
  val planMode: Boolean,
  val steps: List<UiPlanStep> = emptyList(),
  val summary: String? = null,
  val risks: String? = null,
  val status: String? = null,
)

/** `goal_updated` 的目标态。 */
data class UiGoal(
  val status: String,
  val round: Int,
  val maxRounds: Int,
  val stopReason: String? = null,
)

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
  val blockingChecks: Int = 0,
  val stageStatuses: Map<String, StageStatusView> = emptyMap(),
)

/** 单个阶段的桌面侧真实状态（`works.get` 的 `stageStatuses[id]`）。 */
data class StageStatusView(
  /** 桌面原值：waiting / ready / running / review / done / failed / stale / skipped */
  val status: String,
  /** null = 桌面没给百分比 */
  val progress: Int?,
  val message: String?,
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
  val allowAlways: Boolean? = null,
)

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
  val workspaceFiles: List<String>? = null,
)

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
  val unparsedTypes: Map<String, Int> = emptyMap(),
  val workspaceFiles: List<WorkspaceFile>? = null,
  /** 是否已从「快照」对齐过。未对齐时界面应显示「正在同步」而不是空 */
  val snapshotLoaded: Boolean = false,

  val today: IgnoreBridges.TodayAccumulator? = null,

  val artifacts: List<IgnoreBridges.ArtifactView>? = null,

  val members: List<IgnoreBridges.MemberView>? = null,

  val superGoal: String? = null,

  /** 协作项目 id（`super_updated.state.projectId`）；null = 旧版桌面没给 */
  val superProjectId: String? = null,

  /** 协作会话 id（事件信封的 `sessionId`） */
  val superSessionId: String? = null,

  /** 桌面作品库摘要（`works.get` 按需拉取）。null = 还没拉到；空列表 = 明确没有。 */
  val works: List<WorkView>? = null,

  val studioJobs: Map<Pair<String, String>, StudioJobView> = emptyMap(),

  /** 最近一次产物更新属于哪个会话（C5 需要标注来源） */
  val artifactsSessionId: String? = null,

  val notices: List<UiNotice> = emptyList(),

) {
  /** 前台会话的状态。null = 没有或还没加载 */
  val active: SessionUi? get() = activeSessionId?.let { sessions[it] }

  /** 前台会话的 run。null = 前台没在跑 */
  val activeRun: RunState? get() = activeSessionId?.let { runs[it] }

  /** 前台会话的计划。null = 当前会话没有计划 */
  val activePlan: UiPlan? get() = active?.plan

  /** 前台会话的待办。null = 当前会话还没收到过 */
  val activeTodos: List<UiTodo>? get() = active?.todos

  /** 前台会话的目标。null = 当前会话没有目标模式 */
  val activeGoal: UiGoal? get() = active?.goal

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
  val liveMessageId: String? = null,
  val plan: UiPlan? = null,
  /** 这个会话的待办（`todos_updated`，整表替换）。null = 还没收到过 */
  val todos: List<UiTodo>? = null,
  /** 这个会话的目标态（`goal_updated`）。null = 没有目标模式 */
  val goal: UiGoal? = null,
) {
  /** 正在流式生成的消息 */
  val liveMessage: UiMessage? get() = liveMessageId?.let { id -> messages.find { it.id == id } }
}
