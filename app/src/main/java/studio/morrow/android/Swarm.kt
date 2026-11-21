package studio.morrow.android

/**
 * 成员协作域：成员模型、事件投影与协作页。
 *
 * 三个文件共享同一套成员状态语义（排队/运行/失败/完成的迁移规则），
 * 合并后「投影出来的字段」与「界面读的字段」可以逐行对照。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ── 原 swarm/SwarmModel.kt ──

// ───── SwarmModel.kt ─────
/**
 * 成员协作页的数据模型。
 * 这句话有一个直接后果：字段清单不能由我发明，必须与桌面
 */

/**
 * 成员状态七态，与桌面 `MemberProgressStatus` 逐字对应。
 *
 * `Exhausted` 容易被漏掉：它表示「该成员已无法继续」（上下文耗尽/重试上限），
 * 与 `Failed` 不同 —— 失败是这一次没成，耗尽是这个成员不能再用了。
 * 界面必须区分：前者可以重试，后者重试也没用。
 */
enum class MemberState(val label: String) {
  Queued("排队"),
  Running("执行中"),
  Completed("已完成"),
  Failed("失败"),
  Cancelled("已取消"),
  Exhausted("已耗尽"),
}

/** 桌面 `ToolStatus` 在成员操作里的用武之地：只要三态。 */
enum class OpState { Running, Ok, Failed }

/**
 * 一步工具操作，对应桌面 `MemberOperation`（保留 8 个有界面用途的字段）。
 */
data class MemberOp(
  val id: String,
  val name: String,
  val title: String,
  val state: OpState,
  val input: String? = null,
  val output: String? = null,
  val error: String? = null,
  /** 这次操作是否产生了新的可交付成果 */
  val effective: Boolean = false,
  /** 作用对象（路径/命令/成果名） */
  val target: String? = null,
)

/** 成员用量。桌面在 `SuperTask.usage`（`MemberTaskUsage`）。 */
data class MemberUsage(
  val tokens: Long = 0,
  val elapsedMillis: Long = 0,
)

/**
 * 卡住的判断依据。
 * 蓝图 C3 验收原文：**「卡住卡必须写明『卡住依据』」**。
 * 这一条不能靠写死一句「它卡住了」来满足 —— 那和没说一样。
 * 关键判断：**桌面没给 `reason` 时，不能编一句理由**。
 */
data class StuckBasis(
  /** 桌面 `StuckAlert.reason`。null 表示桌面没给。 */
  val reason: String? = null,
  val stepsSinceProgress: Int = 0,
  val repeatedCount: Int = 0,
  val lastOperation: String? = null,
  val evidence: List<MemberOp> = emptyList(),
) {
  /**
   * 界面上那行「卡住依据」。
   */
  fun describe(): String {
    reason?.takeIf { it.isNotBlank() }?.let { return it }

    val facts = buildList {
      if (stepsSinceProgress > 0) add("连续 $stepsSinceProgress 步无新成果")
      if (repeatedCount > 1) add("同一操作重复 $repeatedCount 次")
      lastOperation?.takeIf { it.isNotBlank() }?.let { add("停在 $it") }
    }
    if (facts.isNotEmpty()) return facts.joinToString(" · ")
    return "原因未上报"
  }

  /** 有没有任何可核对的依据。false 时界面要显式说明「未上报」。 */
  fun hasEvidence(): Boolean =
    !reason.isNullOrBlank() || stepsSinceProgress > 0 || repeatedCount > 1 || !lastOperation.isNullOrBlank()
}

/**
 * 一个协作成员，对应桌面 `MemberTaskProgress` 的 9 个字段 + 卡住 + 用量。
 *
 * 字段映射（逐项，供「逐字段核对」测试直接引用）：
 * `index / title / agent / agentTitle / status / stepCount / note / summary / error`
 * 加上 `operations`（桌面同名字段）与 `usage`（桌面在 `SuperTask.usage`）。
 */
data class MemberProgress(
  val index: Int,
  val title: String,
  val agent: String,
  val agentTitle: String? = null,
  val state: MemberState,
  val stepCount: Int? = null,
  /** 最近一步在做什么（桌面 `note`）。蓝图叫它「最近交接」。 */
  val note: String? = null,
  val summary: String? = null,
  val error: String? = null,
  val operations: List<MemberOp> = emptyList(),
  val usage: MemberUsage = MemberUsage(),
  val stuck: StuckBasis? = null,
  /** 桌面任务 id；干预时定位成员。null = 旧版桌面未下发。 */
  val taskId: String? = null,
) {
  /** 卡片标题行的「T1 · frontend」。`agent` 是英文角色名，与桌面一致。 */
  val label: String get() = "T$index · $agent"

  /** 最近一步操作（卡片「最近交接」那行的补充）。 */
  val lastOperation: MemberOp? get() = operations.lastOrNull()
}

/** 卡片上可用的操作。 */
/** 一次上行干预（桌面白名单 `swarm.stopMember` / `swarm.retryMember` / `swarm.instruction`）。 */
sealed interface SwarmCommand {
  val projectId: String
  val sessionId: String
  val taskId: String
  val index: Int

  data class Stop(
    override val projectId: String,
    override val sessionId: String,
    override val taskId: String,
    override val index: Int,
  ) : SwarmCommand

  data class Retry(
    override val projectId: String,
    override val sessionId: String,
    override val taskId: String,
    override val index: Int,
  ) : SwarmCommand

  data class Instruction(
    override val projectId: String,
    override val sessionId: String,
    override val taskId: String,
    override val index: Int,
    val text: String,
  ) : SwarmCommand
}

/** 一次干预操作。 */
enum class MemberAction(val label: String) {
  Stop("停止"),
  Interject("插话"),
  ShowHandoff("查看交接"),
  Retry("重试"),
  NewInstruction("发新指令"),
  PauseAndWait("暂停等"),
}

/**
 * 某个状态下应该出现哪些操作。
 *
 * 这不是审美选择，是**语义约束**：给一个已完成的成员显示「停止」没有意义，
 * 给一个已耗尽的成员显示「重试」会误导（重试也会立刻耗尽）。
 * 写在这里而不是散在 Composable 里，是为了能直接断言。
 */
fun actionsFor(member: MemberProgress): List<MemberAction> {
  if (member.stuck != null) {
    // 卡住态：桌面原文「只提示，不自动停止」，所以给的都是人工干预手段
    return listOf(MemberAction.Retry, MemberAction.NewInstruction, MemberAction.PauseAndWait)
  }
  return when (member.state) {
    MemberState.Running -> listOf(MemberAction.Stop, MemberAction.Interject, MemberAction.ShowHandoff)
    MemberState.Queued -> listOf(MemberAction.Stop, MemberAction.NewInstruction)
    MemberState.Completed -> listOf(MemberAction.ShowHandoff)
    MemberState.Failed -> listOf(MemberAction.Retry, MemberAction.NewInstruction)
    MemberState.Cancelled -> listOf(MemberAction.Retry)
    // 耗尽：重试没用，只能改指令；桌面语义是「这个成员不能再用了」
    MemberState.Exhausted -> listOf(MemberAction.NewInstruction)
  }
}

/** 整页状态。 */
data class SwarmState(
  val goal: String,
  val planVersion: String,
  val members: List<MemberProgress> = emptyList(),
  val concurrencyLimit: Int = 4,
  val stuckAlertEnabled: Boolean = true,
    /** 协作项目 id（`super_updated.state.projectId`）；null = 干预不可用 */
    val projectId: String? = null,
    /** 协作会话 id（事件信封）；null = 干预不可用 */
    val sessionId: String? = null,
  /** 打开页面的时间，用于「操作后 2 秒内变化」这类断言的时间基准 */
  val openedAtMillis: Long = 0,
) {
  val completedCount: Int get() = members.count { it.state == MemberState.Completed }
  val runningCount: Int get() = members.count { it.state == MemberState.Running }
  val totalTokens: Long get() = members.sumOf { it.usage.tokens }
  val stuckMembers: List<MemberProgress> get() = members.filter { it.stuck != null }

  /** 顶栏副标题：「4 / 6 完成 · 计划 v2」。 */
  fun headline(): String = "$completedCount / ${members.size} 完成 · 计划 $planVersion"
}

// ───────────────────────── 事件 ─────────────────────────

/**
 * 成员协作页收到的四类事件。
 *
 * 只列 C3 需要的。桌面 `RuntimeEvent` 有 20 种（D1 卡负责全量映射），
 * 这里写全 20 种会有 16 种永远不被执行 —— 那是假实现。
 */
sealed interface SwarmEvent {
  /** 成员状态变化（含步数/note） */
  data class MemberUpdated(
    val index: Int,
    val state: MemberState? = null,
    val stepCount: Int? = null,
    val note: String? = null,
    val error: String? = null,
    val usage: MemberUsage? = null,
    val atMillis: Long,
  ) : SwarmEvent

  /** 新增一步操作 */
  data class OpAppended(val index: Int, val op: MemberOp, val atMillis: Long) : SwarmEvent

  /** 卡住提示出现 */
  data class StuckRaised(val index: Int, val basis: StuckBasis, val atMillis: Long) : SwarmEvent

  /** 卡住解除 */
  data class StuckResolved(val index: Int, val atMillis: Long) : SwarmEvent
}

/**
 * 一次干预操作的记录。
 *
 * 蓝图验收原文：**「重试/插话操作后 2 秒内卡片状态变化」**。
 * 「变化」必须能验证，所以每次操作要记下时间与它期望达成的状态。
 * 只记「点过了」是不够的：点了但卡片没变，等于没生效。
 */
data class Intervention(
  val action: MemberAction,
  val index: Int,
  val atMillis: Long,
  /** 该操作期望把成员带到的状态。null 表示不改状态（如「查看交接」）。 */
  val expectState: MemberState? = null,
  val note: String? = null,
)

// ───── SwarmReducer.kt ─────
/**
 * 成员协作页的状态归并。
 */
object SwarmReducer {

  /**
   * 应用一个事件。
   *
   * 找不到 `index` 的成员时**原样返回**（不新建）：成员列表由桌面下发，
   * 手机端不凭空造成员。重连补漏时可能收到已移除成员的事件，忽略即可。
   */
  fun reduce(state: SwarmState, event: SwarmEvent): SwarmState = when (event) {
    is SwarmEvent.MemberUpdated -> updateMember(state, event)
    is SwarmEvent.OpAppended -> appendOp(state, event)
    is SwarmEvent.StuckRaised -> raiseStuck(state, event)
    is SwarmEvent.StuckResolved -> resolveStuck(state, event)
  }

  private fun updateMember(state: SwarmState, e: SwarmEvent.MemberUpdated): SwarmState {
    val i = state.members.indexOfFirst { it.index == e.index }
    if (i < 0) return state

    val old = state.members[i]
    val updated = old.copy(
      // 每个字段单独判空：桌面事件是**局部**的（只带变化的字段），
      // 用整体替换会把没变的字段冲成 null —— 表现为「会话跑着跑着标题没了」。
      state = e.state ?: old.state,
      stepCount = e.stepCount ?: old.stepCount,
      note = e.note ?: old.note,
      error = e.error ?: old.error,
      usage = e.usage ?: old.usage,
    )

    // 状态离开「执行中」时清掉卡住提示：桌面语义是「卡住只提示，不自动停止」，
    // 一旦成员真的动了，旧提示会误导用户以为它还卡着。
    val cleared = if (e.state != null && e.state != MemberState.Running) {
      updated.copy(stuck = null)
    } else updated

    return state.copy(members = state.members.replaced(i, cleared))
  }

  private fun appendOp(state: SwarmState, e: SwarmEvent.OpAppended): SwarmState {
    val i = state.members.indexOfFirst { it.index == e.index }
    if (i < 0) return state

    val old = state.members[i]
    if (old.operations.any { it.id == e.op.id }) return state // 幂等：补漏重放不重复加

    val updated = old.copy(operations = old.operations + e.op)
    return state.copy(members = state.members.replaced(i, updated))
  }

  private fun raiseStuck(state: SwarmState, e: SwarmEvent.StuckRaised): SwarmState {
    val i = state.members.indexOfFirst { it.index == e.index }
    if (i < 0) return state

    // 已经卡住的成员再次收到提示时**合并证据**而不是覆盖：
    // 桌面可能分两次推（先 reason，后 evidence），覆盖会让先到的那条丢失。
    val old = state.members[i]
    val merged = if (old.stuck == null) e.basis else mergeBasis(old.stuck, e.basis)
    return state.copy(members = state.members.replaced(i, old.copy(stuck = merged)))
  }

  private fun mergeBasis(old: StuckBasis, incoming: StuckBasis): StuckBasis =
    StuckBasis(
      reason = incoming.reason ?: old.reason,
      stepsSinceProgress = maxOf(old.stepsSinceProgress, incoming.stepsSinceProgress),
      repeatedCount = maxOf(old.repeatedCount, incoming.repeatedCount),
      lastOperation = incoming.lastOperation ?: old.lastOperation,
      evidence = if (incoming.evidence.isNotEmpty()) incoming.evidence else old.evidence,
    )

  private fun resolveStuck(state: SwarmState, e: SwarmEvent.StuckResolved): SwarmState {
    val i = state.members.indexOfFirst { it.index == e.index }
    if (i < 0) return state
    val old = state.members[i]
    if (old.stuck == null) return state
    return state.copy(members = state.members.replaced(i, old.copy(stuck = null)))
  }

  // ───────────────────────── 干预操作 ─────────────────────────

  /**
   * 用户点了一个操作。
   * （`stepCount` / `usage`），那些只有桌面知道。改了就变成编数据了。
   */
  fun intervene(
    state: SwarmState,
    index: Int,
    action: MemberAction,
    nowMillis: Long,
  ): Pair<SwarmState, Intervention> {
    val i = state.members.indexOfFirst { it.index == index }
    val record = Intervention(action = action, index = index, atMillis = nowMillis)
    if (i < 0) return state to record

    val member = state.members[i]

    // 「查看交接」不改变任何状态，只打开面板 —— 它不产生 Intervention 状态变化
    if (action == MemberAction.ShowHandoff) {
      return state to record
    }

    val (nextState, note) = when (action) {
      MemberAction.Stop -> MemberState.Cancelled to "已停止该成员"
      MemberAction.Interject -> member.state to "已插话，等待成员响应"
      MemberAction.Retry -> MemberState.Queued to "已重新排队"
      MemberAction.NewInstruction -> MemberState.Queued to "已下发新指令"
      MemberAction.PauseAndWait -> MemberState.Queued to "已暂停，等待你确认"
      MemberAction.ShowHandoff -> member.state to null
    }

    val updated = member.copy(
      state = nextState,
      note = note ?: member.note,
      // 重试/发新指令/暂停都意味着「不再卡住」，提示要撤掉，
      // 否则用户点完重试还看到红卡，会以为没生效
      stuck = if (action in RELEASING_ACTIONS) null else member.stuck,
    )

    return state.copy(members = state.members.replaced(i, updated)) to
      record.copy(expectState = nextState, note = note)
  }

  /** 这些操作会让成员离开卡住态。 */
  private val RELEASING_ACTIONS = setOf(
    MemberAction.Retry,
    MemberAction.NewInstruction,
    MemberAction.PauseAndWait,
  )

  /** 操作后 2 秒内是否已经变了。用于验证与告警，不参与渲染。 */
  fun changedWithin(intervention: Intervention, observedState: MemberState?, observedAtMillis: Long): Boolean {
    if (intervention.expectState == null) return true
    val within = observedAtMillis - intervention.atMillis <= 2000
    return within && observedState == intervention.expectState
  }
}

/** 不可变列表的定点替换。比 `toMutableList()` 少一次包装，且意图更明确。 */
private fun <T> List<T>.replaced(index: Int, value: T): List<T> {
  if (index !in indices) return this
  val out = ArrayList<T>(size)
  for (i in indices) out.add(if (i == index) value else this[i])
  return out
}
// ── 原 swarm/SwarmProjection.kt ──

/**
 * 桌面 `super_updated` → 协作页模型。
 *
 * 只做「字段搬运 + 状态枚举映射」，不做任何推断：
 * 桌面没给步数就保持 null（界面显示未知），没给目标就保持空
 * （界面显示空态），不拿成员数反推一句「协作进行中」冒充事实。
 */
object SwarmProjection {

  /** 用事件流覆盖本地基础状态（并发/卡住提醒等纯客户端设置沿用 [base]）。 */
  fun fromState(state: MorrowUiState, base: SwarmState): SwarmState = base.copy(
    goal = state.superGoal.orEmpty(),
    members = (state.members ?: emptyList()).map(::memberOf),
    projectId = state.superProjectId ?: base.projectId,
    sessionId = state.superSessionId ?: base.sessionId,
  )

  fun memberOf(view: IgnoreBridges.MemberView): MemberProgress = MemberProgress(
    index = view.index,
    title = view.title,
    agent = view.agent,
    agentTitle = view.agentTitle,
    state = stateOf(view.state),
    stepCount = view.stepCount,
    note = view.note,
    summary = view.summary,
    error = view.error,
    taskId = view.taskId,
  )

  /**
   * 桌面状态字面量 → 手机枚举。
   *
   * `done` 与 `completed` 都归完成：桌面历史上两个词都用过
   * （`SuperTaskStatus` 与成员视图分别出现）。未知状态归 Queued ——
   * 显示「排队」比显示「已完成」安全，用户不会误以为可以走了。
   */
  fun stateOf(raw: String): MemberState = when (raw) {
    "running" -> MemberState.Running
    "done", "completed", "succeeded" -> MemberState.Completed
    "failed", "error" -> MemberState.Failed
    "cancelled", "canceled" -> MemberState.Cancelled
    "exhausted" -> MemberState.Exhausted
    else -> MemberState.Queued
  }
}
// ── 原 swarm/SwarmScreen.kt ──

// ───── SwarmScreen.kt ─────
/**
 * 成员协作页。
 * 成员可能很多（集群上限 10），所以成员部分必须懒加载。
 */
@Composable
fun SwarmScreen(
  state: SwarmState,
  onBack: () -> Unit,
  onAction: (index: Int, action: MemberAction) -> Unit,
  onConcurrencyChange: (Int) -> Unit,
  onStuckAlertToggle: (Boolean) -> Unit,
  onStopAll: () -> Unit,
  /** 「发新指令」输入框确认后回调；null = 该入口不可用。 */
  onInstruction: ((index: Int, text: String) -> Unit)? = null,
  modifier: Modifier = Modifier,
  /** 测试用：固定排序，不用时钟 */
  stuckFirst: Boolean = true,
  /**
   * 是否允许在手机上干预成员。
   *
   * 实时协作数据来自桌面时置 false：手机当前没有 `swarm.*` 上行指令，
   * 显示可点的「停止/重试」会让用户以为点了有效。等白名单补齐后再打开。
   */
  actionsEnabled: Boolean = true,
) {
  val p = MorrowTheme.palette
  var instructionFor by remember { mutableStateOf<Int?>(null) }
  var draft by remember { mutableStateOf("") }
  val ordered = if (stuckFirst) {
    state.members.sortedWith(compareByDescending<MemberProgress> { it.stuck != null }.thenBy { it.index })
  } else {
    state.members
  }

  Column(
    modifier
      .fillMaxSize()
      .background(MorrowTheme.bg)
      .testTag(SwarmTags.SCREEN),
  ) {
    SwarmHeader(state, onBack)

    if (!actionsEnabled) {
      Text(
        text = "实时状态来自桌面；停止 / 重试等干预请在桌面操作",
        color = p.hint,
        fontSize = MorrowType.xs,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s1)
          .testTag(SwarmTags.READONLY),
      )
    }

    if (state.members.isEmpty()) {
      EmptySwarm()
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().testTag(SwarmTags.LIST),
        contentPadding = PaddingValues(
          start = MorrowSpacing.s4,
          end = MorrowSpacing.s4,
          top = MorrowSpacing.s3,
          bottom = MorrowSpacing.s4,
        ),
        verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s3),
      ) {
        // 总目标卡固定第一条，但它随列表滚动 —— 列表不长时等于常驻。
        // 成员多了以后它会滚走，这是有意的：那时用户的操作对象是成员卡。
        item(key = "goal") { GoalCard(state) }
        items(ordered, key = { "m-${it.index}" }) { member ->
          MemberCard(
            member = member,
            onAction = { action ->
              // 「发新指令 / 插话」需要文本：先弹输入框，确认后再上行，
              // 不能直接按一个空文本的「已下发」处理。
              if (onInstruction != null && (action == MemberAction.NewInstruction || action == MemberAction.Interject)) {
                instructionFor = member.index
                draft = ""
              } else {
                onAction(member.index, action)
              }
            },
            stuckAlertEnabled = state.stuckAlertEnabled,
            actionsEnabled = actionsEnabled,
          )
        }
        item(key = "controls") {
          SwarmControls(
            state = state,
            onConcurrencyChange = onConcurrencyChange,
            onStuckAlertToggle = onStuckAlertToggle,
            onStopAll = onStopAll,
          )
        }
      }
    }
  }

  val target = instructionFor
  if (target != null) {
    AlertDialog(
      onDismissRequest = {
        instructionFor = null
        draft = ""
      },
      title = { Text("给 T$target 发新指令") },
      text = {
        OutlinedTextField(
          value = draft,
          onValueChange = { draft = it },
          placeholder = { Text("写清要它改什么、验收标准是什么") },
          minLines = 3,
          modifier = Modifier.fillMaxWidth().testTag(SwarmTags.INSTRUCTION_INPUT),
        )
      },
      confirmButton = {
        TextButton(
          enabled = draft.isNotBlank(),
          modifier = Modifier.testTag(SwarmTags.INSTRUCTION_SEND),
          onClick = {
            onInstruction?.invoke(target, draft.trim())
            instructionFor = null
            draft = ""
          },
        ) { Text("下发") }
      },
      dismissButton = {
        TextButton(onClick = {
          instructionFor = null
          draft = ""
        }) { Text("取消") }
      },
    )
  }
}

/** 顶栏：返回 + 标题 + 副标题（4 / 6 完成 · 计划 v2）。 */
@Composable
private fun SwarmHeader(state: SwarmState, onBack: () -> Unit) {
  val p = MorrowTheme.palette
  Column(Modifier.fillMaxWidth().background(p.bgElev)) {
    Row(
      Modifier
        .fillMaxWidth()
        .height(56.dp)
        .padding(horizontal = MorrowSpacing.s2),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        Modifier.width(44.dp).height(44.dp).clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
      ) {
        Text("‹", color = p.text2, fontSize = MorrowType.title)
      }
      Spacer(Modifier.width(MorrowSpacing.s1))
      Column(Modifier.weight(1f)) {
        Text(
          text = "成员协作",
          color = p.text,
          fontSize = MorrowType.body,
          fontWeight = FontWeight.Medium,
        )
        Text(
          text = state.headline(),
          color = p.hint,
          fontSize = MorrowType.xs,
          fontFamily = FontFamily.Monospace,
        )
      }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.lineStrong))
  }
}

/**
 * 空态。
 *
 * 两种说法分开（C1 学到的）：没有成员和有成员但在排队，
 * 用户该做的事完全不同。
 */
@Composable
private fun EmptySwarm() {
  val p = MorrowTheme.palette
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text("还没有协作成员", color = p.text2, fontSize = MorrowType.body)
      Spacer(Modifier.height(MorrowSpacing.s2))
      Text("在对话页让 Morrow 派发任务", color = p.hint, fontSize = MorrowType.sm)
    }
  }
}

// ───── SwarmComponents.kt ─────
/**
 * 成员协作页的构件。
 * ## 三种卡片态在视觉上必须一眼分开
 */

object SwarmTags {
  const val SCREEN = "swarm-screen"
  /** 成员列表（LazyColumn）。滚动断言必须作用在它身上，不是外层 Column。 */
  const val LIST = "swarm-list"
  const val GOAL_CARD = "swarm-goal"
  const val CONTROLS = "swarm-controls"
  const val STOP_ALL = "swarm-stop-all"
    /** 实时只读提示条（手机暂无干预指令时显示） */
    const val READONLY = "swarm-readonly"
  const val INSTRUCTION_INPUT = "swarm-instruction-input"
  const val INSTRUCTION_SEND = "swarm-instruction-send"
  fun card(index: Int) = "swarm-card-$index"
  fun stuckBasis(index: Int) = "swarm-stuck-basis-$index"
  fun action(index: Int, action: MemberAction) = "swarm-action-$index-${action.name}"
  fun stepCount(index: Int) = "swarm-steps-$index"
  fun note(index: Int) = "swarm-note-$index"
}

// ───────────────────────── 总目标卡 ─────────────────────────

/**
 * 总目标卡：一句目标 + 四个读数。
 *
 * 目标用的是桌面 `SwarmState.goal` 原文，不截断成「…」——
 * 用户在通勤路上看这一屏，最需要确认的就是「它到底在做什么」，
 * 截断会把这句话的价值削掉一半。
 */
@Composable
fun GoalCard(state: SwarmState) {
  val p = MorrowTheme.palette
  Column(
    Modifier
      .fillMaxWidth()
      .testTag(SwarmTags.GOAL_CARD)
      .clip(RoundedCornerShape(MorrowRadius.card))
      .background(p.surface)
      .border(0.5.dp, p.lineStrong, RoundedCornerShape(MorrowRadius.card))
      .padding(MorrowSpacing.s4),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("总目标", color = p.hint, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
      Spacer(Modifier.weight(1f))
      Text(
        text = state.headline(),
        color = p.text2,
        fontSize = MorrowType.xs,
        fontFamily = FontFamily.Monospace,
      )
    }
    Spacer(Modifier.height(MorrowSpacing.s2))
    Text(
      text = state.goal,
      color = p.text,
      fontSize = MorrowType.ui,
      lineHeight = MorrowType.ui * MorrowType.lhCopy,
    )
    Spacer(Modifier.height(MorrowSpacing.s3))
    Row(horizontalArrangement = Arrangement.spacedBy(MorrowSpacing.s5)) {
      GoalStat("${state.completedCount} 完成", p.patina)
      GoalStat("${state.runningCount} 执行", p.gold)
      GoalStat(formatCompactTokens(state.totalTokens), p.text2)
      if (state.stuckMembers.isNotEmpty()) {
        GoalStat("${state.stuckMembers.size} 卡住", p.rust)
      }
    }
  }
}

@Composable
private fun GoalStat(text: String, color: Color) {
  Text(text = text, color = color, fontSize = MorrowType.sm, fontFamily = FontFamily.Monospace)
}

// ───────────────────────── 成员卡 ─────────────────────────

/**
 * 一个成员的卡。
 */
@Composable
fun MemberCard(
  member: MemberProgress,
  onAction: (MemberAction) -> Unit,
  modifier: Modifier = Modifier,
  /**
   * 卡住提醒是否开启。
   */
  stuckAlertEnabled: Boolean = true,
  /** 手机端是否可干预。false = 隐藏操作按钮（只读实时状态） */
  actionsEnabled: Boolean = true,
) {
  val p = MorrowTheme.palette
  val isStuck = member.stuck != null
  // 强调只在"提醒开着"时生效
  val emphasize = isStuck && stuckAlertEnabled
  val accent = when {
    emphasize -> p.rust
    member.state == MemberState.Completed -> p.patina
    member.state == MemberState.Running -> p.gold
    member.state == MemberState.Failed || member.state == MemberState.Exhausted -> p.rust
    else -> p.hint
  }
  val bg = if (emphasize) p.rustDim else p.surface

  Row(
    modifier
      .fillMaxWidth()
      .testTag(SwarmTags.card(member.index))
      .clip(RoundedCornerShape(MorrowRadius.card))
      .background(bg)
      .border(
        width = if (emphasize) 1.dp else 0.5.dp,
        color = if (emphasize) p.rust.copy(alpha = 0.55f) else p.lineStrong,
        shape = RoundedCornerShape(MorrowRadius.card),
      ),
  ) {
    // 左侧竖条：三种态的颜色差异靠它，不靠整卡上色
    Box(
      Modifier
        .width(2.5.dp)
        .height(intrinsicHeightPlaceholder())
        .background(accent)
    )

    Column(Modifier.padding(MorrowSpacing.s3).fillMaxWidth()) {
      // ① 状态 + ② 身份
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier
            .width(5.dp)
            .height(5.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(accent)
        )
        Spacer(Modifier.width(MorrowSpacing.s2))
        Text(
          text = statusLabel(member, emphasize),
          color = accent,
          fontSize = MorrowType.xs,
          fontWeight = if (isStuck) FontWeight.Medium else FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        Text(
          text = member.label,
          color = p.hint,
          fontSize = MorrowType.xs,
          fontFamily = FontFamily.Monospace,
        )
      }

      Spacer(Modifier.height(MorrowSpacing.s2))

      // ③ 任务名
      Text(
        text = member.title,
        color = p.text,
        fontSize = MorrowType.body,
        fontWeight = FontWeight.Medium,
        lineHeight = MorrowType.body * MorrowType.lhUi,
      )

      // ④ 最近交接 / 错误 / 卡住依据 —— 三者互斥，优先级：卡住 > 错误 > note
      val detail = when {
        isStuck -> member.stuck!!.describe()
        member.error != null -> member.error
        else -> member.note
      }
      if (detail != null) {
        Spacer(Modifier.height(MorrowSpacing.s1))
        Text(
          text = detail,
          color = when {
            isStuck -> p.rust
            member.error != null -> p.rust
            else -> p.text2
          },
          fontSize = MorrowType.sm,
          lineHeight = MorrowType.sm * MorrowType.lhUi,
          modifier = Modifier
            .testTag(
              if (isStuck) SwarmTags.stuckBasis(member.index)
              else SwarmTags.note(member.index)
            ),
        )
      }

      // ⑤ 三个读数：步数 / 耗时 / token
      Spacer(Modifier.height(MorrowSpacing.s2))
      Row(horizontalArrangement = Arrangement.spacedBy(MorrowSpacing.s4)) {
        member.stepCount?.let {
          Readout("第 $it 步", SwarmTags.stepCount(member.index))
        }
        if (member.usage.elapsedMillis > 0) {
          Readout(formatDurationSpoken(member.usage.elapsedMillis), null)
        }
        if (member.usage.tokens > 0) {
          Readout("${formatCompactTokens(member.usage.tokens)} tok", null)
        }
      }

      // ⑥ 操作：不存在就不占位（空按钮比没按钮更糟）
      val actions = if (actionsEnabled) actionsFor(member) else emptyList()
      if (actions.isNotEmpty()) {
        Spacer(Modifier.height(MorrowSpacing.s3))
        Row(horizontalArrangement = Arrangement.spacedBy(MorrowSpacing.s2)) {
          actions.forEach { action ->
            MemberActionChip(
              action = action,
              // 卡住态的「重试」是主操作，其余是次要
              primary = isStuck && action == MemberAction.Retry,
              onClick = { onAction(action) },
              tag = SwarmTags.action(member.index, action),
            )
          }
        }
      }
    }
  }
}

/** 左侧竖条需要和卡片等高。用一个足够大的固定高度近似，视觉上被 Column 撑满裁掉。 */
private fun intrinsicHeightPlaceholder() = 200.dp

/**
 * 状态文案。
 *
 * `Exhausted` 单独一句话（「上下文已耗尽」）而不是「失败」：
 * 这两件事对用户的含义完全不同 —— 前者重试没用，后者重试可能有用。
 */
private fun statusLabel(member: MemberProgress, emphasizeStuck: Boolean): String = when {
  // 提醒关掉时不再打「卡住」标签 —— 那正是「提醒」的视觉形式。
  // 成员的真实状态照常显示（执行中 / 失败 / 已完成），依据文案也照常显示。
  member.stuck != null && emphasizeStuck -> "卡住"
  member.state == MemberState.Exhausted -> "上下文已耗尽"
  else -> member.state.label
}

@Composable
private fun Readout(text: String, tag: String?) {
  Text(
    text = text,
    color = MorrowTheme.palette.text3,
    fontSize = MorrowType.xs,
    fontFamily = FontFamily.Monospace,
    modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
  )
}

/**
 * 操作按钮。微圆角矩形（与 C1/C2 同一套令牌），不用胶囊。
 *
 * 危险操作（停止）用中性描边而不是红色填充：这一屏已经有铁锈色的卡住卡了，
 * 再给停止按钮上红，视觉上会分不清「哪里出事了」和「这里可以按」。
 */
@Composable
private fun MemberActionChip(
  action: MemberAction,
  primary: Boolean,
  onClick: () -> Unit,
  tag: String,
) {
  val p = MorrowTheme.palette
  val bg = if (primary) p.gold else Color.Transparent
  val fg = if (primary) p.textInvert else p.text2

  Box(
    Modifier
      .clip(RoundedCornerShape(MorrowRadius.control))
      .background(bg)
      .then(
        if (!primary) Modifier.border(0.5.dp, p.lineStrong, RoundedCornerShape(MorrowRadius.control))
        else Modifier
      )
      .clickable(onClick = onClick)
      .testTag(tag)
      .padding(horizontal = MorrowSpacing.s3, vertical = MorrowSpacing.s1),
  ) {
    Text(
      text = action.label,
      color = fg,
      fontSize = MorrowType.xs,
      fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
    )
  }
}

// ───────────────────────── 并发与提醒控件 ─────────────────────────

/**
 * 底部控件：并发上限 / 卡住提醒 / 计划版本 / 停止全部。
 *
 * 「卡住提醒」开关的语义要说清：关掉它**不会**让卡住的成员继续跑
 * （桌面原文「只提示，不自动停止」），只是不再提醒。所以关掉时
 * 卡住卡仍然在列表里，只是不再有额外提示。这一点必须在文案上体现。
 */
@Composable
fun SwarmControls(
  state: SwarmState,
  onConcurrencyChange: (Int) -> Unit,
  onStuckAlertToggle: (Boolean) -> Unit,
  onStopAll: () -> Unit,
) {
  val p = MorrowTheme.palette
  Column(
    Modifier
      .fillMaxWidth()
      .testTag(SwarmTags.CONTROLS)
      .clip(RoundedCornerShape(MorrowRadius.card))
      .background(p.surface)
      .border(0.5.dp, p.line, RoundedCornerShape(MorrowRadius.card))
      .padding(MorrowSpacing.s3),
  ) {
    ControlRow("并发上限") {
      Stepper(state.concurrencyLimit, onConcurrencyChange)
    }
    Spacer(Modifier.height(MorrowSpacing.s2))
    ControlRow("卡住提醒") {
      Toggle(state.stuckAlertEnabled, onStuckAlertToggle)
    }
    Spacer(Modifier.height(MorrowSpacing.s2))
    ControlRow("计划版本") {
      Text(
        text = "${state.planVersion} · 局部修订",
        color = p.text2,
        fontSize = MorrowType.xs,
        fontFamily = FontFamily.Monospace,
      )
    }
    Spacer(Modifier.height(MorrowSpacing.s3))
    Box(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(MorrowRadius.control))
        .border(0.5.dp, p.rust.copy(alpha = 0.55f), RoundedCornerShape(MorrowRadius.control))
        .clickable(onClick = onStopAll)
        .testTag(SwarmTags.STOP_ALL)
        .padding(vertical = MorrowSpacing.s2),
      contentAlignment = Alignment.Center,
    ) {
      Text("停止全部成员", color = p.rust, fontSize = MorrowType.sm)
    }
  }
}

@Composable
private fun ControlRow(label: String, content: @Composable () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = MorrowTheme.palette.text2, fontSize = MorrowType.sm)
    Spacer(Modifier.weight(1f))
    content()
  }
}

/** 并发上限步进：1–8。上限 8 对应桌面集群成员上限。 */
@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
  val p = MorrowTheme.palette
  Row(verticalAlignment = Alignment.CenterVertically) {
    StepButton("−") { onChange((value - 1).coerceAtLeast(1)) }
    Text(
      text = value.toString(),
      color = p.text,
      fontSize = MorrowType.sm,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.padding(horizontal = MorrowSpacing.s3),
    )
    StepButton("+") { onChange((value + 1).coerceAtMost(8)) }
  }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
  val p = MorrowTheme.palette
  Box(
    Modifier
      .width(24.dp)
      .height(24.dp)
      .clip(RoundedCornerShape(MorrowRadius.xs))
      .border(0.5.dp, p.lineStrong, RoundedCornerShape(MorrowRadius.xs))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, color = p.text2, fontSize = MorrowType.sm)
  }
}

/** 开关。开启时金色（它是「主操作」级设置），关闭时中性。 */
@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
  val p = MorrowTheme.palette
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = if (on) "开" else "关",
      color = if (on) p.gold else p.hint,
      fontSize = MorrowType.xs,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.width(MorrowSpacing.s2))
    Box(
      Modifier
        .width(34.dp)
        .height(18.dp)
        .clip(RoundedCornerShape(999.dp))
        .background(if (on) p.gold.copy(alpha = 0.22f) else p.surfaceHover)
        .border(0.5.dp, if (on) p.gold else p.lineStrong, RoundedCornerShape(999.dp))
        .clickable { onChange(!on) },
      contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
      Box(
        Modifier
          .padding(horizontal = 2.dp)
          .width(14.dp)
          .height(14.dp)
          .clip(RoundedCornerShape(999.dp))
          .background(if (on) p.gold else p.hint)
      )
    }
  }
}

