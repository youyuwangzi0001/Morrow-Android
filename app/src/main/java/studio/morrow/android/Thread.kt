package studio.morrow.android

/**
 * 对话域：消息模型与归并器，以及对话页、组件与输入框。模型侧只做纯归并，
 * 界面侧只订阅状态 —— 二者合在一处，改消息形状时不用跨文件对照。
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

// ── 原 thread/ThreadModel.kt ──

// ───── ThreadModel.kt ─────
/**
 * 对话页的数据模型。
 * —— 所以消息必须带**稳定且永不改变**的 id，且流式追加只修改最后一条
 */

/** 消息作者。`System` 是本地提示（比如「已离线」），不属于对话两边。 */
enum class Author { User, Assistant, System }

/**
 * 执行组里单个步骤的结局。
 *
 * 注意 [Running] 是必需的：工具调用在流式过程中会先出现、后完成，
 * 如果只有「成功/失败」两态，渲染时就得分「有没有 result」来判断，
 * 那种判断会散落在多个 Composable 里，改一处漏一处。
 */
enum class StepState { Running, Ok, Failed }

/** 执行组里的一步。`summary` 是一句话，不是命令原文 —— 手机上要结论不要日志。 */
data class ToolStep(
  val id: String,
  val label: String,
  val state: StepState,
  /**
   * 这一步产生的磁盘变化，例如「+12 −3」「新建」「删除」「退出码 0」。
   *
   * 手机用户最关心「它到底改了没改我的东西」，这部分信息来自桌面下发的
   * `toolResult` 计数，**不是**命令输出原文（原文不出桌面）。
   */
  val detail: String? = null,
  /** 是否改动了磁盘（写文件、删文件、执行命令）。界面把这类步骤标成实心点。 */
  val mutating: Boolean = false,
)

/**
 * 执行组：一次工具密集阶段的折叠体。
 *
 * `detail` 是展开后才看的内容（文件路径、命令、diff 摘要）。
 * 默认收起是硬要求（蓝图原文「默认只显示『N 个步骤 · 用时』」），
 * 但**必须能展开** —— 收起是为了不刷屏，不是为了藏起来。
 */
data class ToolGroup(
  val id: String,
  val steps: List<ToolStep>,
  val detail: String?,
  val elapsedMillis: Long,
) {
  val doneCount: Int get() = steps.count { it.state != StepState.Running }
  val hasFailure: Boolean get() = steps.any { it.state == StepState.Failed }
  val allDone: Boolean get() = steps.all { it.state != StepState.Running }
}

/** 计划卡：桌面版批准过的计划，手机上只读展示（批准在审批页做）。 */
data class PlanStep(val text: String, val state: StepState)

data class PlanCard(
  val id: String,
  val title: String,
  val steps: List<PlanStep>,
  val approved: Boolean,
)

/** 成品卡片：附在消息后面的产物。 */
data class ArtifactRef(
  val name: String,
  val format: String,
  val bytes: Long,
  val insertions: Int?,
  val deletions: Int?,
)

/**
 * 一条消息。
 *
 * [id] 必须**由服务端事件带下来**，不能在本地按索引生成 —— 流式补发、
 * 重连补漏都会让索引错位，key 一变 LazyColumn 就整列重建。
 */
data class ThreadMessage(
  val id: String,
  val author: Author,
  val text: String,
  val streaming: Boolean = false,
  val toolGroup: ToolGroup? = null,
  val plan: PlanCard? = null,
  val artifact: ArtifactRef? = null,
  val elapsedMillis: Long? = null,
  val tokensUsed: Long? = null,
  val failed: Boolean = false,
)

/** Composer 的四个发送模式，对应蓝图屏 3 底部的「工作 / 聊天 / 计划 / 研究 / 目标」。 */
enum class ComposerMode(val label: String) {
  Work("工作"),
  Chat("聊天"),
  Plan("计划"),
  Research("研究"),
  Goal("目标"),
}

/** `@` 文件面板里的一个候选。路径一律是**工作区相对路径**（蓝图 SEC2）。 */
data class WorkspaceFile(
  val path: String,
  val isDirectory: Boolean = false,
) {
  val name: String get() = path.substringAfterLast('/')
}

/**
 * Composer 的完整状态。
 *
 * [mentionQuery] 非 null 表示文件面板正在显示。之所以用 `String?` 而不是
 * `Boolean + String` 两个字段，是因为「面板开着但查询为空」和「面板关着」
 * 必须是两个可区分的状态 —— 前者要显示全部文件，后者什么都不显示。
 */
data class ComposerState(
  val draft: String = "",
  val mode: ComposerMode = ComposerMode.Work,
  val mentionQuery: String? = null,
  val mentionResults: List<WorkspaceFile> = emptyList(),
  val mentionSelected: Int = 0,
  val generating: Boolean = false,
  val sending: Boolean = false,
  val canSend: Boolean = false,
)

/**
 * 对话页的整页状态。
 *
 * 有意做成不可变 + 纯函数更新：这样「流式追加不整屏重组」可以靠
 * **引用相等**来验证（除最后一条外，所有消息对象 === 旧对象），
 * 不需要靠帧率这种不稳的指标去猜。
 */
data class ThreadState(
  val title: String,
  val subtitle: String,
  val messages: List<ThreadMessage> = emptyList(),
  val composer: ComposerState = ComposerState(),
  val atBottom: Boolean = true,
) {
  val isStreaming: Boolean get() = messages.lastOrNull()?.streaming == true
}

/**
 * 会话事件：把桌面事件流映射成对状态的操作。
 *
 * 只列出 C2 需要的四类。真实事件类型有 20 种（D1 卡负责全量映射），
 * 这里不做「预先把 20 种都写了」的假实现 —— 用不到的分支写出来也没人验证。
 */
sealed interface ThreadEvent {
  data class AppendDelta(val messageId: String, val delta: String) : ThreadEvent
  data class FinishMessage(
    val messageId: String,
    val elapsedMillis: Long,
    val tokensUsed: Long,
    val failed: Boolean = false,
  ) : ThreadEvent
  data class AddStep(val groupId: String, val step: ToolStep) : ThreadEvent
  data class StepDone(val groupId: String, val stepId: String, val state: StepState) : ThreadEvent
}

// ───── ThreadReducer.kt ─────
/**
 * 事件 → 状态的纯函数归并。
 */
object ThreadReducer {

  /**
   * 追加流式增量。
   *
   * 找不到 [ThreadEvent.AppendDelta.messageId] 时**返回原状态**（而不是抛异常或
   * 新建消息）：重连补漏时可能收到已过期消息的增量，静默忽略比崩溃合理，
   * 但也不能悄悄造一条空消息出来。
   */
  fun reduce(state: ThreadState, event: ThreadEvent): ThreadState = when (event) {
    is ThreadEvent.AppendDelta -> appendDelta(state, event)
    is ThreadEvent.FinishMessage -> finishMessage(state, event)
    is ThreadEvent.AddStep -> addStep(state, event)
    is ThreadEvent.StepDone -> stepDone(state, event)
  }

  private fun appendDelta(state: ThreadState, event: ThreadEvent.AppendDelta): ThreadState {
    val index = state.messages.indexOfLast { it.id == event.messageId }
    if (index < 0) return state

    val old = state.messages[index]
    val updated = old.copy(text = old.text + event.delta)

    val next = state.messages.toMutableList()
    next[index] = updated
    return state.copy(messages = next)
  }

  private fun finishMessage(state: ThreadState, event: ThreadEvent.FinishMessage): ThreadState {
    val index = state.messages.indexOfLast { it.id == event.messageId }
    if (index < 0) return state

    val old = state.messages[index]
    val updated = old.copy(
      streaming = false,
      elapsedMillis = event.elapsedMillis,
      tokensUsed = event.tokensUsed,
      failed = event.failed,
    )

    val next = state.messages.toMutableList()
    next[index] = updated
    return state.copy(messages = next)
  }

  /**
   * 往执行组里加一步。
   *
   * 只重建**包含该组的这一条消息**，其 [ToolGroup] 与 [ToolStep] 列表按需新建。
   * 同一条消息里的其他字段（text / plan / artifact）保持原引用。
   */
  private fun addStep(state: ThreadState, event: ThreadEvent.AddStep): ThreadState {
    val index = state.messages.indexOfLast { it.toolGroup?.id == event.groupId }
    if (index < 0) return state

    val old = state.messages[index]
    val group = old.toolGroup ?: return state
    if (group.steps.any { it.id == event.step.id }) return state // 幂等：补漏重放不重复加

    val updated = old.copy(toolGroup = group.copy(steps = group.steps + event.step))
    val next = state.messages.toMutableList()
    next[index] = updated
    return state.copy(messages = next)
  }

  private fun stepDone(state: ThreadState, event: ThreadEvent.StepDone): ThreadState {
    val index = state.messages.indexOfLast { m ->
      m.toolGroup?.steps?.any { it.id == event.stepId } == true
    }
    if (index < 0) return state

    val old = state.messages[index]
    val group = old.toolGroup ?: return state
    val stepIndex = group.steps.indexOfFirst { it.id == event.stepId }
    if (stepIndex < 0) return state

    val steps = group.steps.toMutableList()
    steps[stepIndex] = steps[stepIndex].copy(state = event.state)

    val updated = old.copy(toolGroup = group.copy(steps = steps))
    val next = state.messages.toMutableList()
    next[index] = updated
    return state.copy(messages = next)
  }

  // ───────────────────────── Composer 的归并 ─────────────────────────

  /**
   * 输入框文本变化，并重新计算 `@` 文件面板状态。
   * 规则（都是「用户能感知」的）：
   * - `@` 必须是**词首**才触发（前面是空白或行首）。否则 `a@b.com` 也会弹面板。
   * - `@` 与查询之间**不能有空格** —— 有空格说明用户已经认了某个词，不想再选。
   */
  fun onDraftChange(
    composer: ComposerState,
    text: String,
    files: List<WorkspaceFile>,
  ): ComposerState {
    val query = mentionQueryOf(text)
    val results = when {
      query == null -> emptyList()
      query.isEmpty() -> files
      else -> files.filter { it.path.contains(query, ignoreCase = true) }
    }
    return composer.copy(
      draft = text,
      mentionQuery = query,
      mentionResults = results,
      // 查询变了就把选中位重置到 0，否则会指向一个已经不存在的下标
      mentionSelected = if (query == composer.mentionQuery) composer.mentionSelected else 0,
      canSend = text.isNotBlank() && !composer.sending,
    )
  }

  /**
   * 从光标位置的文本里取出 `@` 查询词。没有进行中的 @ 时返回 null。
   *
   * 只看**最后一行最后一段**：多行输入里更早的 `@` 已经被用户确认过了。
   */
  fun mentionQueryOf(text: String): String? {
    val segment = text.substringAfterLast('\n')
    val at = segment.lastIndexOf('@')
    if (at < 0) return null
    // 词首判断：@ 前面必须是行首或空白
    if (at > 0 && !segment[at - 1].isWhitespace()) return null
    val query = segment.substring(at + 1)
    if (query.any { it.isWhitespace() }) return null
    return query
  }

  /**
   * 选中一个文件：把 `@查询` 替换成 `@路径 `（带尾空格）。
   *
   * 尾空格不是装饰 —— 没有它，用户接着打字会继续被当成查询的一部分，
   * 面板不会关、插入的路径也会被改坏。
   */
  fun selectMention(composer: ComposerState, file: WorkspaceFile): ComposerState {
    val text = composer.draft
    val segment = text.substringAfterLast('\n')
    val at = segment.lastIndexOf('@')
    if (at < 0) return composer

    val head = text.substring(0, text.length - segment.length) + segment.substring(0, at)
    val newText = "$head@${file.path} "
    return composer.copy(
      draft = newText,
      mentionQuery = null,
      mentionResults = emptyList(),
      mentionSelected = 0,
      canSend = newText.isNotBlank() && !composer.sending,
    )
  }

  /** 关闭面板（Esc 或点击空白）。不改变草稿。 */
  fun dismissMention(composer: ComposerState): ComposerState =
    composer.copy(mentionQuery = null, mentionResults = emptyList(), mentionSelected = 0)

  /** 上下移动选中项，循环不越界。 */
  fun moveMentionSelection(composer: ComposerState, delta: Int): ComposerState {
    if (composer.mentionResults.isEmpty()) return composer
    val n = composer.mentionResults.size
    val next = ((composer.mentionSelected + delta) % n + n) % n
    return composer.copy(mentionSelected = next)
  }

  // ───────────────────────── 发送 / 停止 ─────────────────────────

  /**
   * 点击发送。
   *
   * 返回 null 表示不该发（草稿为空、已经在发、正在生成）。
   * 调用方拿到 null 就什么都不做 —— 用一个明确的 null 而不是 Boolean，
   * 是为了让「为什么没发出去」在类型上就无处可藏。
   */
  fun onSend(composer: ComposerState): SendRequest? {
    if (composer.draft.isBlank()) return null
    if (composer.sending) return null
    if (composer.generating) return null
    return SendRequest(text = composer.draft.trim(), mode = composer.mode)
  }

  /** 发送后清空草稿。生成态由服务端事件驱动，这里不预设。 */
  fun afterSend(composer: ComposerState): ComposerState =
    composer.copy(draft = "", mentionQuery = null, mentionResults = emptyList(), canSend = false)
}

/** 一次发送请求。[text] 已 trim，[mode] 是发送时选中的模式。 */
data class SendRequest(val text: String, val mode: ComposerMode)

/**
 * 事件流状态 → 对话页的执行组。
 *
 * 一次助手消息里可能串了十几次工具调用，界面上合并成**一个**折叠组：
 * 默认只显示「N 个步骤 · 用时」，展开才看到每一步。
 * 只有 `Running` 与失败会让折叠标题变色，成功是默认态、不额外强调。
 */
object ToolProjection {

  /** 每步一行摘要。顺序：状态标记由界面画，这里只给文字。 */
  fun stepsOf(calls: List<UiToolCall>): List<ToolStep> = calls.map { call ->
    ToolStep(
      id = call.id,
      label = call.title.ifBlank { call.name.ifBlank { "工具调用" } },
      state = when (call.status) {
        ToolCallStatus.Running -> StepState.Running
        ToolCallStatus.Ok -> StepState.Ok
        ToolCallStatus.Failed, ToolCallStatus.Denied, ToolCallStatus.Interrupted -> StepState.Failed
      },
      detail = detailOf(call),
      mutating = call.mutating,
    )
  }

  /** 「+12 −3 · 新建 · 退出码 0」这行小字。没有可说的就返回 null，不占位置。 */
  fun detailOf(call: UiToolCall): String? {
    val parts = mutableListOf<String>()
    if (call.created) parts += "新建"
    if (call.deleted) parts += "删除"
    val added = call.diffAdded ?: 0
    val removed = call.diffRemoved ?: 0
    if (added > 0 || removed > 0) parts += "+$added −$removed"
    call.exitCode?.let {
      parts += if (it == 0) "退出码 0" else "退出码 $it"
    }
    if (call.timedOut) parts += "超时"
    when (call.status) {
      ToolCallStatus.Denied -> parts += "已拒绝"
      ToolCallStatus.Interrupted -> parts += "已中断"
      ToolCallStatus.Failed -> if (call.exitCode == null) parts += "失败"
      else -> Unit
    }
    call.durationMillis?.takeIf { it >= 1000 }?.let {
      parts += formatDurationSpoken(it)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
  }

  /**
   * 把一条消息的工具调用折成一个组。返回 null = 这条消息没有工具调用。
   *
   * 组 id 用首尾调用拼成：它只用于「展开/收起」的本地状态，
   * 只要在同一轮里稳定即可；重连重放时同一条消息的组 id 不变。
   */
  fun groupOf(messageId: String, calls: List<UiToolCall>): ToolGroup? {
    if (calls.isEmpty()) return null
    val elapsed = calls.mapNotNull { call -> call.durationMillis }.sum()
    return ToolGroup(
      id = "tools-$messageId",
      steps = stepsOf(calls),
      detail = null,
      elapsedMillis = elapsed,
    )
  }
}
// ── 原 thread/ThreadScreen.kt ──

// ───── ThreadScreen.kt ─────
/**
 * 对话页。
 * ## 为什么 stop 只在生成中渲染
 * 这里做得更彻底：停止按钮**只在 `generating == true` 时存在于组合树里**。
 */
@Composable
fun ThreadScreen(
  state: ThreadState,
  onBack: () -> Unit,
  onComposerTextChange: (String) -> Unit,
  onModeChange: (ComposerMode) -> Unit,
  onMentionSelect: (WorkspaceFile) -> Unit,
  onMentionDismiss: () -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
  /** 协作成员数量。为 0 时不显示入口（通往空页等于死路）。 */
  swarmMembers: Int = 0,
  /** 进入成员协作页。默认空实现只为测试方便；生产由 MorrowApp 传真回调。 */
  onOpenSwarm: () -> Unit = {},
  /** 供测试注入的初始展开集合。生产环境为空（全部收起）。 */
  initiallyExpanded: Set<String> = emptySet(),
  /** 发送失败提示（未连接 / 桌面拒绝）。null = 无 */
  sendError: String? = null,
) {
  val palette = MorrowTheme.palette
  val listState = rememberLazyListState()
  // 展开集合必须是**页面内状态**。写成 object 级别的全局变量时，
  // 两个会话页会互相影响：在 A 页展开的执行组，进 B 页也是展开的，
  // 而 B 页的 groupId 根本对不上 —— 表现为「有时展开有时不展开」，
  // 且完全无法从代码上看出原因。用 remember 绑定到当前组合。
  var expanded by remember { mutableStateOf(initiallyExpanded) }

  Column(
    modifier
      .fillMaxSize()
      .background(MorrowTheme.bg),
  ) {
    ThreadHeader(state, onBack, swarmMembers, onOpenSwarm)

    // 消息流
    if (state.messages.isEmpty()) {
      EmptyThread(
        mentionOpen = state.composer.mentionQuery != null,
        modifier = Modifier.weight(1f),
      )
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .testTag(ThreadTags.TRANSCRIPT),
        contentPadding = PaddingValues(
          start = MorrowSpacing.s4,
          end = MorrowSpacing.s4,
          top = MorrowSpacing.s3,
          bottom = MorrowSpacing.s4,
        ),
        verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s4),
      ) {
        items(state.messages, key = { it.id }) { message ->
          Box(Modifier.testTag(ThreadTags.message(message.id))) {
            when (message.author) {
              Author.User -> UserMessage(message)
              Author.Assistant -> AssistantMessage(
                message = message,
                expandedGroups = expanded,
                onToggleGroup = { id ->
                  expanded = if (id in expanded) expanded - id else expanded + id
                },
              )
              Author.System -> Body(message.text, palette.hint)
            }
          }
        }
        if (state.isStreaming) {
          item(key = "streaming-hint") { StreamingHint() }
        }
      }
    }

    if (sendError != null) {
      Text(
        text = sendError,
        fontSize = MorrowType.xs,
        color = palette.rust,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s1)
          .testTag("thread-send-error"),
      )
    }

    Composer(
      state = state.composer,
      onTextChange = onComposerTextChange,
      onModeChange = onModeChange,
      onMentionSelect = onMentionSelect,
      onMentionDismiss = onMentionDismiss,
      onSend = onSend,
      onStop = onStop,
    )
  }
}

/** 顶栏：返回 + 标题 + 副标题（模型 · 权限 · 上下文占用）。 */
@Composable
private fun ThreadHeader(state: ThreadState, onBack: () -> Unit, swarmMembers: Int, onOpenSwarm: () -> Unit) {
  val palette = MorrowTheme.palette
  Column(Modifier.fillMaxWidth().background(palette.bgElev)) {
    Row(
      Modifier
        .fillMaxWidth()
        .height(56.dp)
        .padding(horizontal = MorrowSpacing.s2),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        Modifier
          .width(44.dp)
          .height(44.dp)
          .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
      ) {
        Text("‹", color = palette.text2, fontSize = MorrowType.title)
      }
      Spacer(Modifier.width(MorrowSpacing.s1))
      Column(Modifier.weight(1f)) {
        Text(
          text = state.title,
          color = palette.text,
          fontSize = MorrowType.body,
          fontWeight = FontWeight.Medium,
        )
        Text(
          text = state.subtitle,
          color = palette.hint,
          fontSize = MorrowType.xs,
          fontFamily = FontFamily.Monospace,
        )
      }
      // 「⋯」菜单在 C2 不做（会话重命名/导出属于后续卡）。
      // 这里刻意**不渲染**这个按钮，而不是渲染一个点了没反应的图标：
      // 空实现的按钮比没有按钮更糟 —— 用户会以为是应用卡住了。
      //
      // 取而代之，右侧放「协作」入口：它只在真有协作成员时出现。
      // 通往空页等于给死路（C1 的同一条判断）。
      if (swarmMembers > 0) {
        Row(
          Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(MorrowRadius.control))
            .clickable(onClick = onOpenSwarm)
            .testTag(ComposerTags.SWARM_ENTRY)
            .padding(horizontal = MorrowSpacing.s3),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "$swarmMembers 成员",
            color = palette.gold,
            fontSize = MorrowType.xs,
            fontFamily = FontFamily.Monospace,
          )
          Spacer(Modifier.width(2.dp))
          Text("›", color = palette.gold, fontSize = MorrowType.sm)
        }
      }
    }
    Hairline(palette.lineStrong)
  }
}

/**
 * 空态。
 *
 * 分两种说法（这是 C1 学到的）：面板开着时不说「说点什么」，
 * 因为用户此刻的注意力在文件列表上。
 */
@Composable
private fun EmptyThread(mentionOpen: Boolean, modifier: Modifier = Modifier) {
  val palette = MorrowTheme.palette
  // 必须用 weight(1f) 而不是 fillMaxSize：
  // fillMaxSize 会吃掉 Column 的全部剩余空间，把下面的 Composer 挤到屏幕外
  // （实测：加 Composer 之后，空态页面上 Composer 完全不可见）。
  Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = if (mentionOpen) "选一个文件加进上下文" else "这个会话还没有消息",
        color = palette.text2,
        fontSize = MorrowType.body,
      )
      Spacer(Modifier.height(MorrowSpacing.s2))
      Text(
        text = if (mentionOpen) "选中后会插入工作区相对路径" else "在下面说清想要的结果",
        color = palette.hint,
        fontSize = MorrowType.sm,
      )
    }
  }
}

// ───── ThreadComponents.kt ─────
/**
 * 对话页的构件。
 */
object ThreadTags {
  const val TRANSCRIPT = "thread-transcript"
  const val COMPOSER = "thread-composer"
  const val MENTION_PANEL = "thread-mention"
  const val STOP_BUTTON = "thread-stop"
  const val SEND_BUTTON = "thread-send"
  fun group(id: String) = "thread-group-$id"
  fun groupDetail(id: String) = "thread-group-detail-$id"
  fun message(id: String) = "thread-message-$id"
}

// ───────────────────────────── 文本尺寸 ─────────────────────────────

/** 元信息：等宽 + hint 色。所有读数都走这里，字体族由 C1 的断言守着。 */
@Composable
internal fun Meta(text: String, color: Color? = null) {
  Text(
    text = text,
    color = color ?: MorrowTheme.hint,
    fontSize = MorrowType.xs,
    fontFamily = FontFamily.Monospace,
    lineHeight = MorrowType.xs * MorrowType.lhUi,
  )
}

/** 正文：15sp / 1.62 行高，与桌面 --font-ui-size + line-height 同源。 */
@Composable
internal fun Body(text: String, color: Color? = null) {
  Text(
    text = text,
    color = color ?: MorrowTheme.palette.text,
    fontSize = MorrowType.body,
    lineHeight = MorrowType.body * MorrowType.lhCopy,
  )
}

// ───────────────────────────── 通用小块 ─────────────────────────────

/** 细分隔线：0.5dp hairline（蓝图：3x 屏上 1dp 会显粗）。 */
@Composable
internal fun Hairline(color: Color = MorrowTheme.palette.line) {
  Box(
    Modifier
      .fillMaxWidth()
      .height(0.5.dp)
      .background(color)
  )
}

/** 状态点：运行中用金色并带光晕（蓝图 C2：金色只留主操作与运行中）。 */
@Composable
internal fun StateDot(color: Color, glow: Boolean = false, size: Int = 6) {
  Box(
    Modifier
      .size(size.dp)
      .clip(RoundedCornerShape(999.dp))
      .background(color)
      .then(if (glow) Modifier.border(2.dp, color.copy(alpha = 0.18f), RoundedCornerShape(999.dp)) else Modifier)
  )
}

/**
 * 微圆角按钮。
 *
 * 用户明确要求「和桌面版一致的微圆角矩形」，所以这里**不用胶囊形**。
 * C1 已经把「禁胶囊」写成可重跑的断言，这个构件沿用同一套令牌
 * （[MorrowRadius.control] = 5dp）。
 */
@Composable
internal fun ThreadAction(
  label: String,
  onClick: () -> Unit,
  primary: Boolean = false,
  enabled: Boolean = true,
  modifier: Modifier = Modifier,
  tag: String? = null,
) {
  val palette = MorrowTheme.palette
  val bg = when {
    !enabled -> palette.surfaceHover
    primary -> palette.gold
    else -> Color.Transparent
  }
  val fg = when {
    !enabled -> palette.hint
    primary -> palette.textInvert
    else -> palette.text2
  }
  Row(
    modifier
      .clip(RoundedCornerShape(MorrowRadius.control))
      .background(bg)
      .then(if (!primary && enabled) Modifier.border(1.dp, palette.lineStrong, RoundedCornerShape(MorrowRadius.control)) else Modifier)
      .clickable(enabled = enabled, onClick = onClick)
      .then(if (tag != null) Modifier.testTag(tag) else Modifier)
      .height(32.dp)
      .padding(horizontal = MorrowSpacing.s3),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      color = fg,
      fontSize = MorrowType.sm,
      fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
    )
  }
}

// ───────────────────────────── 消息气泡 ─────────────────────────────

/**
 * 用户消息：右对齐 + 浅底块。
 *
 * 用 [MorrowTheme.palette.userBubble] 而不是金色：金色是「运行中」与主操作专用，
 * 给用户消息上金色会让主操作失去唯一性。
 */
@Composable
internal fun UserMessage(message: ThreadMessage) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    Box(
      Modifier
        .widthIn()
        .clip(RoundedCornerShape(MorrowRadius.card))
        .background(MorrowTheme.palette.userBubble)
        .padding(horizontal = MorrowSpacing.s3, vertical = MorrowSpacing.s2),
    ) {
      Body(message.text)
    }
  }
}

/** 让用户气泡最宽占 82%，长文本自动换行不顶满屏。 */
@Composable
private fun Modifier.widthIn(): Modifier {
  val max = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.82f
  return this.width(max.dp.coerceAtMost(420.dp))
}

/**
 * 助手消息：**无气泡**，正文直接排在页面上。
 *
 * 蓝图原文「助手正文无气泡」。这不是偷懒：助手回复常常带代码块、表格、清单，
 * 气泡会把这些内容再往里缩一层，6 寸屏上每行少几个字就很难读。
 */
@Composable
internal fun AssistantMessage(
  message: ThreadMessage,
  expandedGroups: Set<String>,
  onToggleGroup: (String) -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    message.plan?.let {
      PlanCardView(it)
      Spacer(Modifier.height(MorrowSpacing.s2))
    }

    message.toolGroup?.let {
      ToolGroupView(it, expandedGroups.contains(it.id)) { onToggleGroup(it.id) }
      Spacer(Modifier.height(MorrowSpacing.s2))
    }

    if (message.text.isNotEmpty()) {
      Body(message.text)
    }

    message.artifact?.let {
      Spacer(Modifier.height(MorrowSpacing.s2))
      ArtifactCardView(it)
    }

    if (!message.streaming && (message.elapsedMillis != null || message.failed)) {
      Spacer(Modifier.height(MorrowSpacing.s2))
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (message.failed) {
          StateDot(MorrowTheme.palette.rust)
          Spacer(Modifier.width(MorrowSpacing.s1))
          Meta("失败", MorrowTheme.palette.rust)
        } else {
          Meta(
            buildString {
              append("用时 ")
              append(formatDurationSpoken(message.elapsedMillis ?: 0))
              message.tokensUsed?.let {
                append(" · 用 ")
                append(formatCompactTokens(it))
                append(" token")
              }
            }
          )
        }
      }
    }
  }
}

/** 正在生成的提示：一个金色点在呼吸，加一句「正在生成」。 */
@Composable
internal fun StreamingHint() {
  Row(verticalAlignment = Alignment.CenterVertically) {
    StateDot(MorrowTheme.palette.gold, glow = true)
    Spacer(Modifier.width(MorrowSpacing.s2))
    Meta("正在生成…", MorrowTheme.palette.gold)
  }
}

// ───── ThreadBlocks.kt ─────
/**
 * 执行组：默认收起，只显示「N 个步骤 · 用时」。
 */
@Composable
internal fun ToolGroupView(
  group: ToolGroup,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  val palette = MorrowTheme.palette
  val failedCount = group.steps.count { it.state == StepState.Failed }
  val accent = when {
    !group.allDone -> palette.gold
    group.hasFailure -> palette.rust
    else -> palette.patina
  }

  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(MorrowRadius.control))
      .background(palette.surfaceInset)
      .border(0.5.dp, palette.line, RoundedCornerShape(MorrowRadius.control))
      .testTag(ThreadTags.group(group.id))
      .clickable(onClick = onToggle)
      .padding(horizontal = MorrowSpacing.s3, vertical = MorrowSpacing.s2),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      StateDot(accent, glow = !group.allDone)
      Spacer(Modifier.width(MorrowSpacing.s2))
      Text(
        text = buildString {
          append(group.steps.size)
          append(" 个步骤")
          if (failedCount > 0) {
            append(" · ")
            append(failedCount)
            append(" 失败")
          }
          // 用时只在真有耗时时才写：所有工具都没报 durationMs 时
          // 写「0 秒」会让用户以为这一步瞬间完成，而事实是「不知道」。
          if (group.elapsedMillis > 0) {
            append(" · ")
            append(formatDurationSpoken(group.elapsedMillis))
          }
        },
        color = if (group.hasFailure) palette.rust else palette.text2,
        fontSize = MorrowType.sm,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.weight(1f))
      Text(
        text = if (expanded) "收起" else "展开",
        color = palette.hint,
        fontSize = MorrowType.xs,
      )
    }

    if (expanded) {
      Spacer(Modifier.height(MorrowSpacing.s2))
      Hairline(palette.line)
      Spacer(Modifier.height(MorrowSpacing.s2))
      Column(
        Modifier.testTag(ThreadTags.groupDetail(group.id)),
        verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s1),
      ) {
        group.steps.forEach { StepRow(it) }
        group.detail?.let {
          Spacer(Modifier.height(MorrowSpacing.s1))
          Text(
            text = it,
            color = palette.hint,
            fontSize = MorrowType.xs,
            fontFamily = FontFamily.Monospace,
            lineHeight = MorrowType.xs * MorrowType.lhUi,
          )
        }
      }
    }
  }
}

/** 展开后的单步。[StepState.Running] 用金色，失败用铁锈色，成功用青。 */
@Composable
private fun StepRow(step: ToolStep) {
  val palette = MorrowTheme.palette
  val color = when (step.state) {
    StepState.Ok -> palette.patina
    StepState.Failed -> palette.rust
    StepState.Running -> palette.gold
  }
  val mark = when (step.state) {
    StepState.Ok -> "✓"
    StepState.Failed -> "✕"
    StepState.Running -> "·"
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(mark, color = color, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
    Spacer(Modifier.width(MorrowSpacing.s2))
    Text(
      text = step.label,
      color = if (step.state == StepState.Running) palette.text2 else palette.text3,
      fontSize = MorrowType.base,
      lineHeight = MorrowType.base * MorrowType.lhUi,
    )
  }
}

// ───────────────────────────── 计划卡 ─────────────────────────────

/**
 * 计划卡：只读展示桌面批准过的计划。
 *
 * 「已批准」用 patina（青）而不是金色：金色在手机端只留给「运行中」与主操作
 * （蓝图 C2「金色只留两处」）。计划已批准是一件稳定的事实，不需要吸睛。
 */
@Composable
internal fun PlanCardView(plan: PlanCard) {
  val palette = MorrowTheme.palette
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(MorrowRadius.card))
      .background(palette.surface)
      .border(0.5.dp, palette.lineStrong, RoundedCornerShape(MorrowRadius.card))
      .padding(MorrowSpacing.s3),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "计划",
        color = palette.text2,
        fontSize = MorrowType.xs,
        fontWeight = FontWeight.Medium,
      )
      Spacer(Modifier.weight(1f))
      if (plan.approved) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("✓", color = palette.patina, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
          Spacer(Modifier.width(MorrowSpacing.s1))
          Text("已批准", color = palette.patina, fontSize = MorrowType.xs)
        }
      } else {
        Text("待批准", color = palette.amber, fontSize = MorrowType.xs)
      }
    }
    Spacer(Modifier.height(MorrowSpacing.s2))
    Text(
      text = plan.title,
      color = palette.text,
      fontSize = MorrowType.ui,
      fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(MorrowSpacing.s2))
    plan.steps.forEach { step ->
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
          text = when (step.state) {
            StepState.Ok -> "✓"
            StepState.Failed -> "✕"
            StepState.Running -> "·"
          },
          color = when (step.state) {
            StepState.Ok -> palette.patina
            StepState.Failed -> palette.rust
            StepState.Running -> palette.gold
          },
          fontSize = MorrowType.xs,
          fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(MorrowSpacing.s2))
        Text(
          text = step.text,
          color = palette.text2,
          fontSize = MorrowType.base,
          lineHeight = MorrowType.base * MorrowType.lhUi,
        )
      }
    }
  }
}

// ───────────────────────────── 成品卡 ─────────────────────────────

/** 成品卡：文件名 + 增删行数 + 大小。格式标签用等宽。 */
@Composable
internal fun ArtifactCardView(ref: ArtifactRef) {
  val palette = MorrowTheme.palette
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(MorrowRadius.control))
      .background(palette.surfaceInset)
      .border(0.5.dp, palette.line, RoundedCornerShape(MorrowRadius.control))
      .padding(MorrowSpacing.s3),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = ref.name,
        color = palette.text,
        fontSize = MorrowType.base,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.weight(1f))
      if (ref.insertions != null) {
        Text("+${ref.insertions}", color = palette.patina, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(MorrowSpacing.s2))
      }
      if (ref.deletions != null) {
        Text("−${ref.deletions}", color = palette.rust, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
      }
    }
    Spacer(Modifier.height(MorrowSpacing.s1))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Meta(ref.format)
      Spacer(Modifier.width(MorrowSpacing.s2))
      Meta(formatSize(ref.bytes))
    }
  }
}

// ───────────────────────────── 数字格式化 ─────────────────────────────

/**
 * 用时。
 *
 * 规则：不足 1 分钟给「12 秒」，否则「3 分 12 秒」。
 * 不写成「0:12」这种钟表格式：手机上读「3 分 12 秒」比读「03:12」快，
 * 而且不必判断是分还是时。
 */
fun formatDurationSpoken(millis: Long): String {
  val totalSeconds = (millis + 500) / 1000
  if (totalSeconds < 60) return "$totalSeconds 秒"
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return if (seconds == 0L) "$minutes 分" else "$minutes 分 $seconds 秒"
}

/** token 数：小于 1000 原样，否则一位小数的 K；百万级用 M。 */
fun formatCompactTokens(tokens: Long): String = when {
  tokens < 1_000 -> tokens.toString()
  tokens < 1_000_000 -> {
    val k = tokens / 1000.0
    if (k >= 100) "${k.toInt()}K" else String.format(java.util.Locale.US, "%.1fK", k)
  }
  else -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0)
}

/**
 * 字节数。用的与工位页同一套口径（真实字节 ÷ 1024 逐级进位），
 * 两份实现不能各写一套 —— 同一个文件在两个页面显示不同大小会让人怀疑数据。
 */
fun formatSize(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
  val mb = kb / 1024.0
  return String.format(java.util.Locale.US, "%.1f MB", mb)
}

// ───── Composer.kt ─────
/**
 * Composer：输入 + 模式 + `@` 文件 + 发送/停止。
 * 2. **发送与停止是同一个位置的两个状态**：生成中显示「停止」，否则显示「↑」。
 */
private const val MAX_LINES = 5

object ComposerTags {
  const val FIELD = "composer-field"
  const val MODE_STRIP = "composer-mode"
  const val MENTION_ITEM = "composer-mention-item"
  const val SWARM_ENTRY = "composer-swarm-entry"
  fun mode(m: ComposerMode) = "composer-mode-${m.name}"
}

@Composable
fun Composer(
  state: ComposerState,
  onTextChange: (String) -> Unit,
  onModeChange: (ComposerMode) -> Unit,
  onMentionSelect: (WorkspaceFile) -> Unit,
  onMentionDismiss: () -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val palette = MorrowTheme.palette

  Column(
    modifier
      .fillMaxWidth()
      .background(palette.bgElev)
      .testTag(ThreadTags.COMPOSER),
  ) {
    Hairline(palette.lineStrong)

    // @ 文件面板：在输入框之上。
    //
    // 这里**没有**「离线待发送」条。蓝图 D3 要求它，但 D3 的 OutboxQueue
    // 还没落地 —— 没有数据源就不要先摆一段 `if (pendingCount > 0)` 的 UI：
    // 那是永远不会执行的分支，测试覆盖不到、用户看不到、代码却像做完了。
    // 等 D3 落地时，加字段、加渲染、加测试，一次做完。
    if (state.mentionQuery != null) {
      MentionPanel(
        results = state.mentionResults,
        selected = state.mentionSelected,
        query = state.mentionQuery,
        onSelect = onMentionSelect,
        onDismiss = onMentionDismiss,
      )
    }

    // 输入框
    Row(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s2),
      verticalAlignment = Alignment.Bottom,
    ) {
      Column(Modifier.weight(1f)) {
        Box(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp, max = (40 + 20 * (MAX_LINES - 1)).dp)
            .clip(RoundedCornerShape(MorrowRadius.control))
            .background(palette.surfaceInset)
            .border(0.5.dp, palette.line, RoundedCornerShape(MorrowRadius.control))
            .padding(horizontal = MorrowSpacing.s3, vertical = MorrowSpacing.s2),
        ) {
          if (state.draft.isEmpty()) {
            Text(
              text = "说清想要的结果，或者继续改…",
              color = palette.hint,
              fontSize = MorrowType.body,
            )
          }
          BasicTextField(
            value = TextFieldValue(state.draft),
            onValueChange = { onTextChange(it.text) },
            textStyle = TextStyle(
              color = palette.text,
              fontSize = MorrowType.body,
              lineHeight = MorrowType.body * MorrowType.lhCopy,
            ),
            cursorBrush = SolidColor(palette.gold),
            maxLines = MAX_LINES,
            modifier = Modifier
              .fillMaxWidth()
              .testTag(ComposerTags.FIELD),
          )
        }
        Spacer(Modifier.height(MorrowSpacing.s2))
        ModeStrip(state.mode, onModeChange)
      }

      Spacer(Modifier.width(MorrowSpacing.s2))

      // 发送 / 停止：同一位置的两个状态
      SendOrStop(
        generating = state.generating,
        canSend = state.canSend && state.draft.isNotBlank(),
        onSend = onSend,
        onStop = onStop,
      )
    }
  }
}

/**
 * 发送与停止合体。
 * 生成中**必须**显示停止 —— 这条在 C2 里是硬要求。B 轨已经踩过一次
 * 只在 `generating == true` 时渲染，且点击直接给到 [onStop]，
 */
@Composable
private fun SendOrStop(
  generating: Boolean,
  canSend: Boolean,
  onSend: () -> Unit,
  onStop: () -> Unit,
) {
  val palette = MorrowTheme.palette
  if (generating) {
    Box(
      Modifier
        .width(44.dp)
        .height(40.dp)
        .clip(RoundedCornerShape(MorrowRadius.control))
        .background(palette.rustDim)
        .border(0.5.dp, palette.rust.copy(alpha = 0.5f), RoundedCornerShape(MorrowRadius.control))
        .clickable(onClick = onStop)
        .testTag(ThreadTags.STOP_BUTTON),
      contentAlignment = Alignment.Center,
    ) {
      Text("停止", color = palette.rust, fontSize = MorrowType.xs, fontWeight = FontWeight.Medium)
    }
  } else {
    Box(
      Modifier
        .width(44.dp)
        .height(40.dp)
        .clip(RoundedCornerShape(MorrowRadius.control))
        .background(if (canSend) palette.gold else palette.surfaceHover)
        .clickable(enabled = canSend, onClick = onSend)
        .testTag(ThreadTags.SEND_BUTTON),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        "↑",
        color = if (canSend) palette.textInvert else palette.hint,
        fontSize = MorrowType.h2,
        fontWeight = FontWeight.Medium,
      )
    }
  }
}

/**
 * 模式条：工作 / 聊天 / 计划 / 研究 / 目标。
 *
 * 选中的模式用金色文字 + 底部短线，不用填充块：
 * 五个填充块在 6 寸屏上会变成一排彩色积木，抢走输入框的注意力。
 */
@Composable
private fun ModeStrip(current: ComposerMode, onChange: (ComposerMode) -> Unit) {
  val palette = MorrowTheme.palette
  Row(
    Modifier.testTag(ComposerTags.MODE_STRIP),
    horizontalArrangement = Arrangement.spacedBy(MorrowSpacing.s3),
  ) {
    ComposerMode.entries.forEach { mode ->
      val active = mode == current
      Column(
        Modifier
          .clip(RoundedCornerShape(MorrowRadius.xs))
          .clickable { onChange(mode) }
          .testTag(ComposerTags.mode(mode))
          .padding(vertical = 2.dp),
      ) {
        Text(
          text = mode.label,
          color = if (active) palette.gold else palette.hint,
          fontSize = MorrowType.sm,
          fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
        Spacer(Modifier.height(3.dp))
        Box(
          Modifier
            .width(16.dp)
            .height(1.5.dp)
            .background(if (active) palette.gold else androidx.compose.ui.graphics.Color.Transparent)
        )
      }
    }
  }
}

/**
 * `@` 文件面板。
 *
 * 显示的是**工作区相对路径**（蓝图 SEC2：绝对路径在 Relay 层就被剥掉）。
 * 目录项右侧带 `/` 提示，避免用户以为能选一个文件却选到了目录。
 */
@Composable
private fun MentionPanel(
  results: List<WorkspaceFile>,
  selected: Int,
  query: String,
  onSelect: (WorkspaceFile) -> Unit,
  onDismiss: () -> Unit,
) {
  val palette = MorrowTheme.palette
  Column(
    Modifier
      .fillMaxWidth()
      .heightIn(max = 200.dp)
      .background(palette.surface)
      .testTag(ThreadTags.MENTION_PANEL),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s1),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = if (query.isEmpty()) "选择文件" else "匹配「$query」",
        color = palette.hint,
        fontSize = MorrowType.xs,
      )
      Spacer(Modifier.weight(1f))
      Text(
        text = "取消",
        color = palette.hint,
        fontSize = MorrowType.xs,
        modifier = Modifier.clickable(onClick = onDismiss),
      )
    }
    Hairline(palette.line)
    if (results.isEmpty()) {
      Box(Modifier.fillMaxWidth().padding(MorrowSpacing.s4)) {
        // 空结果有两种原因，说法必须不同：
        // ① 有文件但没匹配上 → 「没有匹配的文件」（用户改查询就行）
        // ② 根本没有文件列表（relay 还没接上） → 要告诉他为什么，否则
        //    用户会一直改查询，而问题不在查询上。
        // 这里用 query 是否为空区分：查询为空还空结果 = 压根没有候选。
        Text(
          text = if (query.isEmpty()) "文件列表还没同步过来" else "没有匹配的文件",
          color = palette.hint,
          fontSize = MorrowType.sm,
        )
      }
    } else {
      LazyColumn {
        items(results, key = { it.path }) { file ->
          val isSelected = results.indexOf(file) == selected
          Row(
            Modifier
              .fillMaxWidth()
              .background(if (isSelected) palette.surfaceHover else androidx.compose.ui.graphics.Color.Transparent)
              .clickable { onSelect(file) }
              .testTag(ComposerTags.MENTION_ITEM)
              .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = file.path,
              color = if (isSelected) palette.text else palette.text2,
              fontSize = MorrowType.sm,
              fontFamily = FontFamily.Monospace,
            )
          }
        }
      }
    }
  }
  Hairline(palette.line)
}

