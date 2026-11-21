package studio.morrow.android

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp

/** 消息作者。`System` 是本地提示（比如「已离线」），不属于对话两边。 */
enum class Author { User, Assistant, System }

enum class StepState { Running, Ok, Failed }

/** 执行组里的一步。`summary` 是一句话，不是命令原文 —— 手机上要结论不要日志。 */
data class ToolStep(
  val id: String,
  val label: String,
  val state: StepState,
  val detail: String? = null,
  /** 是否改动了磁盘（写文件、删文件、执行命令）。界面把这类步骤标成实心点。 */
  val mutating: Boolean = false,
  val diff: String? = null,
)

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
  /** 计划摘要（桌面 `SessionPlan.summary`）。null = 旧桌面没下发 */
  val summary: String? = null,
  /** 风险与前提（桌面 `SessionPlan.risks`）。null = 没有或旧桌面没下发 */
  val risks: String? = null,
)

/** 成品卡片：附在消息后面的产物。 */
data class ArtifactRef(
  val name: String,
  val format: String,
  val bytes: Long,
  val insertions: Int?,
  val deletions: Int?,
)

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
  val thinking: String? = null,
  val blockErrors: List<String> = emptyList(),
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

data class ThreadState(
  val title: String,
  val subtitle: String,
  val messages: List<ThreadMessage> = emptyList(),
  val composer: ComposerState = ComposerState(),
  val atBottom: Boolean = true,
) {
  val isStreaming: Boolean get() = messages.lastOrNull()?.streaming == true
}

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

object ThreadReducer {

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
      mentionSelected = if (query == composer.mentionQuery) composer.mentionSelected else 0,
      canSend = text.isNotBlank() && !composer.sending,
    )
  }

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

  fun onSend(composer: ComposerState): SendRequest? {
    if (composer.draft.isBlank()) return null
    if (composer.sending) return null
    if (composer.generating) return null
    return SendRequest(text = composer.draft.trim(), mode = composer.mode)
  }

  fun afterSend(composer: ComposerState): ComposerState =
    composer.copy(
      draft = "",
      mentionQuery = null,
      mentionResults = emptyList(),
      sending = false,
      canSend = false,
    )

  fun thinkingKey(messageId: String): String = "thinking-$messageId"
}

/** 一次发送请求。[text] 已 trim，[mode] 是发送时选中的模式。 */
data class SendRequest(val text: String, val mode: ComposerMode)

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
      diff = call.diff,
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

@Composable
fun ThreadScreen(
  state: ThreadState,
  onBack: () -> Unit,
  onComposerTextChange: (String) -> Unit,
  onModeChange: (ComposerMode) -> Unit,
  onMentionSelect: (WorkspaceFile) -> Unit,
  onMentionMove: (Int) -> Unit = {},
  /** M-20：回车选中当前高亮项（只在面板开着时生效）。 */
  onMentionConfirm: () -> Unit = {},
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
  onOpenSessions: (() -> Unit)? = null,
) {
  val palette = MorrowTheme.palette
  val listState = rememberLazyListState()
  var expanded by remember { mutableStateOf(initiallyExpanded) }
  var expandedMessages by remember { mutableStateOf(emptySet<String>()) }
  val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

  val shouldFollow by remember {
    derivedStateOf {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull()
      last == null || last.index >= info.totalItemsCount - 1
    }
  }
  var lastCount by remember { mutableStateOf(-1) }
  LaunchedEffect(state.messages.size) {
    val count = state.messages.size
    if (lastCount in 0 until count && shouldFollow) {
      listState.animateScrollToItem(count - 1)
    }
    lastCount = count
  }
  LaunchedEffect(imeVisible) {
    if (imeVisible && state.messages.isNotEmpty() && shouldFollow) {
      listState.animateScrollToItem(state.messages.lastIndex)
    }
  }

  Column(
    modifier
      .fillMaxSize()
      .background(MorrowTheme.bg),
  ) {
    ThreadHeader(state, onBack, swarmMembers, onOpenSwarm, onOpenSessions)

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
                expandedMessages = expandedMessages,
                onToggleMessage = { id ->
                  expandedMessages = if (id in expandedMessages) expandedMessages - id else expandedMessages + id
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
      onMentionMove = onMentionMove,
      onMentionConfirm = onMentionConfirm,
      onSend = onSend,
      onStop = onStop,
    )
  }
}

/** 顶栏：返回 + 标题 + 副标题（模型 · 权限 · 上下文占用）。 */
@Composable
private fun ThreadHeader(
  state: ThreadState,
  onBack: () -> Unit,
  swarmMembers: Int,
  onOpenSwarm: () -> Unit,
  onOpenSessions: (() -> Unit)? = null,
) {
  val palette = MorrowTheme.palette
  Column(Modifier.fillMaxWidth().background(palette.bgElev)) {
    Row(
      Modifier
        .fillMaxWidth()
        .height(56.dp)
        .padding(horizontal = MorrowSpacing.s2),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 读屏软件只会念出一个标点符号。
      BackButton(onClick = onBack, tag = ThreadTags.BACK)
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
      if (onOpenSessions != null) {
        Row(
          Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(MorrowRadius.control))
            .border(0.5.dp, palette.lineStrong, RoundedCornerShape(MorrowRadius.control))
            .clickable(onClick = onOpenSessions)
            .testTag(ThreadTags.SESSIONS_ENTRY)
            .padding(horizontal = MorrowSpacing.s3),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "会话",
            color = MorrowTheme.text2,
            fontSize = MorrowType.xs,
          )
          Spacer(Modifier.width(2.dp))
          Text("›", color = MorrowTheme.text2, fontSize = MorrowType.sm)
        }
        Spacer(Modifier.width(MorrowSpacing.s1))
      }
      //
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

@Composable
private fun EmptyThread(mentionOpen: Boolean, modifier: Modifier = Modifier) {
  val palette = MorrowTheme.palette
  Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = if (mentionOpen) "选一个文件加进上下文" else "这个会话还没有消息",
        color = MorrowTheme.text2,
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

object ThreadTags {
  /** 返回按钮（D-07 统一后新增的标识）。 */
  const val BACK = "thread-back"
  const val TRANSCRIPT = "thread-transcript"
  const val COMPOSER = "thread-composer"
  const val MENTION_PANEL = "thread-mention"
  const val STOP_BUTTON = "thread-stop"
  const val SEND_BUTTON = "thread-send"
  const val SESSIONS_ENTRY = "thread-sessions-entry"
  fun group(id: String) = "thread-group-$id"
  fun groupDetail(id: String) = "thread-group-detail-$id"
  fun message(id: String) = "thread-message-$id"
}

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
    else -> MorrowTheme.text2
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

/** 助手消息最多直接铺开几块，其余折叠。4 块 ≈ 标题 + 两段 + 一个列表。 */
private const val MAX_VISIBLE_BLOCKS = 4

/** 让用户气泡最宽占 82%，长文本自动换行不顶满屏。 */
@Composable
private fun Modifier.widthIn(): Modifier {
  val max = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.82f
  return this.width(max.dp.coerceAtMost(420.dp))
}

@Composable
internal fun AssistantMessage(
  message: ThreadMessage,
  expandedGroups: Set<String>,
  onToggleGroup: (String) -> Unit,
  expandedMessages: Set<String> = emptySet(),
  onToggleMessage: (String) -> Unit = {},
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
      MessageBody(
        message = message,
        expanded = message.id in expandedMessages,
        onToggle = { onToggleMessage(message.id) },
      )
    }

    message.blockErrors.forEach { error ->
      Spacer(Modifier.height(MorrowSpacing.s1))
      Row(verticalAlignment = Alignment.CenterVertically) {
        StateDot(MorrowTheme.palette.rust)
        Spacer(Modifier.width(MorrowSpacing.s1))
        Meta(error, MorrowTheme.palette.rust)
      }
    }

    // 默认铺开会让每条回复都变长一倍。
    message.thinking?.takeIf { it.isNotBlank() }?.let { thinking ->
      Spacer(Modifier.height(MorrowSpacing.s2))
      val key = ThreadReducer.thinkingKey(message.id)
      ThinkingView(thinking, key in expandedMessages) {
        onToggleMessage(key)
      }
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

@Composable
private fun MessageBody(
  message: ThreadMessage,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  val blocks = remember(message.text) { MarkdownParser.parse(message.text) }
  // ── P1-17：流式期间也要折叠 ──
  //
  //
  val collapsible = blocks.size > MAX_VISIBLE_BLOCKS
  if (!collapsible || expanded) {
    MarkdownView(source = message.text, blocks = blocks)
    if (collapsible) {
      Spacer(Modifier.height(MorrowSpacing.s2))
      CollapseToggle(label = "收起", onClick = onToggle)
    }
    return
  }
  MarkdownView(source = message.text, blocks = blocks, collapseAfterBlocks = MAX_VISIBLE_BLOCKS)
  Spacer(Modifier.height(MorrowSpacing.s2))
  CollapseToggle(
    label = "展开全文 · 还有 ${blocks.size - MAX_VISIBLE_BLOCKS} 段",
    onClick = onToggle,
  )
}

@Composable
private fun ThinkingView(text: String, expanded: Boolean, onToggle: () -> Unit) {
  val palette = MorrowTheme.palette
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(MorrowRadius.control))
      .background(palette.surfaceInset)
      .border(0.5.dp, palette.line, RoundedCornerShape(MorrowRadius.control))
      .clickable(onClick = onToggle)
      .padding(horizontal = MorrowSpacing.s3, vertical = MorrowSpacing.s2),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      StateDot(palette.mauve)
      Spacer(Modifier.width(MorrowSpacing.s2))
      Text(
        text = if (expanded) "思考过程" else "思考过程 · ${text.length} 字",
        color = palette.mauve,
        fontSize = MorrowType.xs,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.weight(1f))
      Text(if (expanded) "收起" else "展开", color = palette.hint, fontSize = MorrowType.xs)
    }
    if (expanded) {
      Spacer(Modifier.height(MorrowSpacing.s2))
      SelectionContainer {
        Text(
          text = text,
          color = MorrowTheme.text2,
          fontSize = MorrowType.base,
          lineHeight = MorrowType.base * MorrowType.lhCopy,
        )
      }
    }
  }
}

/** 「展开全文 / 收起」：一条细分隔线加一行等宽小字，不抢正文注意力。 */@Composable
private fun CollapseToggle(label: String, onClick: () -> Unit) {
  val palette = MorrowTheme.palette
  Row(
    Modifier
      .clip(RoundedCornerShape(MorrowRadius.control))
      .clickable(onClick = onClick)
      .testTag(RichTags.COLLAPSE)
      .padding(vertical = MorrowSpacing.s1, horizontal = MorrowSpacing.s2),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = MorrowTheme.text2, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
    Spacer(Modifier.width(MorrowSpacing.s1))
    Text("›", color = palette.gold, fontSize = MorrowType.xs)
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
          if (group.elapsedMillis > 0) {
            append(" · ")
            append(formatDurationSpoken(group.elapsedMillis))
          }
        },
        color = if (group.hasFailure) palette.rust else MorrowTheme.text2,
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
internal fun StepState.mark(): String = when (this) {
  StepState.Ok -> "✓"
  StepState.Failed -> "✕"
  StepState.Running -> "·"
}

/** 步骤状态的颜色。与 [mark] 同一批语义，所以放在一起。 */
internal fun StepState.color(palette: MorrowPalette): Color = when (this) {
  StepState.Ok -> palette.patina
  StepState.Failed -> palette.rust
  StepState.Running -> palette.gold
}

@Composable
private fun StepRow(step: ToolStep) {
  val palette = MorrowTheme.palette
  val color = step.state.color(palette)
  val mark = step.state.mark()
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(mark, color = color, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
      Spacer(Modifier.width(MorrowSpacing.s2))
      Text(
        text = step.label,
        color = if (step.state == StepState.Running) MorrowTheme.text2 else palette.text3,
        fontSize = MorrowType.base,
        lineHeight = MorrowType.base * MorrowType.lhUi,
      )
      step.detail?.let {
        Spacer(Modifier.width(MorrowSpacing.s2))
        Text(
          text = it,
          color = palette.hint,
          fontSize = MorrowType.xs,
          fontFamily = FontFamily.Monospace,
          lineHeight = MorrowType.xs * MorrowType.lhUi,
        )
      }
    }
    step.diff?.takeIf { it.isNotBlank() }?.let { patch ->
      Spacer(Modifier.height(MorrowSpacing.s1))
      StepDiff(patch)
    }
  }
}

@Composable
private fun StepDiff(patch: String) {
  val palette = MorrowTheme.palette
  var open by remember(patch) { mutableStateOf(false) }
  val lines = remember(patch) { MarkdownParser.parseDiffLines(patch) }
  val added = lines.count { it.kind == MdDiffKind.Added }
  val removed = lines.count { it.kind == MdDiffKind.Removed }
  Column(Modifier.fillMaxWidth().padding(start = 16.dp)) {
    Row(
      Modifier
        .clickable { open = !open }
        .padding(vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = if (open) "收起 diff" else "查看 diff",
        color = MorrowTheme.text2,
        fontSize = MorrowType.xs,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.width(MorrowSpacing.s1))
      Text("+$added", color = palette.patina, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
      Spacer(Modifier.width(2.dp))
      Text("−$removed", color = palette.rust, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
    }
    if (open) DiffView(lines)
  }
}

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
        color = MorrowTheme.text2,
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
    plan.summary?.takeIf { it.isNotBlank() }?.let {
      Spacer(Modifier.height(MorrowSpacing.s1))
      Text(
        text = it,
        color = MorrowTheme.text2,
        fontSize = MorrowType.base,
        lineHeight = MorrowType.base * MorrowType.lhCopy,
      )
    }
    if (plan.steps.isNotEmpty()) Spacer(Modifier.height(MorrowSpacing.s2))
    plan.steps.forEach { step ->
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
          text = step.state.mark(),
          color = step.state.color(palette),
          fontSize = MorrowType.xs,
          fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(MorrowSpacing.s2))
        Text(
          text = step.text,
          color = MorrowTheme.text2,
          fontSize = MorrowType.base,
          lineHeight = MorrowType.base * MorrowType.lhUi,
        )
      }
    }
    plan.risks?.takeIf { it.isNotBlank() }?.let {
      Spacer(Modifier.height(MorrowSpacing.s2))
      Text(
        text = "风险：$it",
        color = palette.amber,
        fontSize = MorrowType.base,
        lineHeight = MorrowType.base * MorrowType.lhUi,
      )
    }
  }
}

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

fun formatDurationSpoken(millis: Long): String = Formatters.durationSpoken(millis)

fun formatCompactTokens(tokens: Long): String = Formatters.tokens(tokens)

fun formatSize(bytes: Long): String = Formatters.bytes(bytes)

private const val MAX_LINES = 5
private const val MIN_LINES = 1

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
  onMentionMove: (Int) -> Unit = {},
  onMentionConfirm: () -> Unit = {},
  onModeChange: (ComposerMode) -> Unit,
  onMentionSelect: (WorkspaceFile) -> Unit,
  onMentionDismiss: () -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val palette = MorrowTheme.palette
  var fieldValue by remember { mutableStateOf(TextFieldValue(state.draft)) }
  if (fieldValue.text != state.draft) {
    fieldValue = TextFieldValue(
      text = state.draft,
      selection = TextRange(state.draft.length),
    )
  }

  Column(
    modifier
      .fillMaxWidth()
      .background(palette.bgElev)
      .testTag(ThreadTags.COMPOSER),
  ) {
    Hairline(palette.lineStrong)

    // @ 文件面板：在输入框之上。
    //
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
            .heightIn(min = 40.dp)
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
            value = fieldValue,
            onValueChange = { next ->
              fieldValue = next
              if (next.text != state.draft) onTextChange(next.text)
            },
            textStyle = TextStyle(
              color = palette.text,
              fontSize = MorrowType.body,
              lineHeight = MorrowType.body * MorrowType.lhCopy,
            ),
            cursorBrush = SolidColor(palette.gold),
            maxLines = MAX_LINES,
            // 误发半句话，而多行提示词在这里是常态。
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            // ── M-20：@ 面板的键盘导航 ──
            //
            //
            // 拦下来，否则方向键会先被光标移动消费掉。
            modifier = Modifier
              .fillMaxWidth()
              .testTag(ComposerTags.FIELD)
              .then(
                if (state.mentionQuery != null) {
                  Modifier.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                      Key.DirectionUp -> { onMentionMove(-1); true }
                      Key.DirectionDown -> { onMentionMove(1); true }
                      // 只在面板开着时生效，面板关着时照常换行。
                      Key.Enter -> { onMentionConfirm(); true }
                      Key.Escape -> { onMentionDismiss(); true }
                      else -> false
                    }
                  }
                } else {
                  Modifier
                },
              ),
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
        //    用户会一直改查询，而问题不在查询上。
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
              color = if (isSelected) palette.text else MorrowTheme.text2,
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
