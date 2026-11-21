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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class SessionBadge { Running, Awaiting, Idle }

/** 列表里的一行。字段全部来自 [MorrowUiState]，没有任何本地猜测。 */
data class SessionRow(
  val id: String,
  val title: String,
  val badge: SessionBadge,
  /** 最后一条消息的正文摘要（已折行、已截断）。空 = 这个会话还没有消息 */
  val preview: String,
  /** 最后活动时刻：最后一条消息的时间，没有消息时为 0（未知，不能当 1970 显示） */
  val lastActiveMillis: Long,
  /** 这个会话里待审的请求数（0 = 没有） */
  val pendingApprovals: Int,
  /** 是否就是当前正在看的那个会话 */
  val isActive: Boolean,
  /** 消息条数。0 表示桌面只给了标题、还没下发正文 */
  val messageCount: Int,
)

enum class SessionGroup(val label: String) {
  /** 有审批在等我 —— 用户此刻唯一能推进的事 */
  NeedYou("待我决定"),
  /** 桌面正在跑 */
  Running("运行中"),
  /** 其余会话，按最后活动时间倒序 */
  Others("其他会话"),
}

data class SessionSection(val group: SessionGroup, val rows: List<SessionRow>)

object SessionListProjection {

  /** 摘要最长多少字。两行能显示的量级；再长会被 Composer 挤掉。 */
  const val PREVIEW_MAX = 90

  fun badgeOf(hasPendingApproval: Boolean, isRunning: Boolean): SessionBadge = when {
    hasPendingApproval -> SessionBadge.Awaiting
    isRunning -> SessionBadge.Running
    else -> SessionBadge.Idle
  }

  fun previewOf(messages: List<UiMessage>): String {
    val text = messages.asReversed()
      .firstOrNull { it.text.isNotBlank() }
      ?.text
      ?: return ""
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    // 表格行整行丢掉，不是只丢分隔行。
    val prose = lines.filter { line ->
      !line.startsWith("```") &&
        !line.startsWith("~~~") &&
        !line.startsWith("|") &&
        !line.startsWith("@@")
    }
    val source = if (prose.isNotEmpty()) prose else lines.filter { it.startsWith("|") }
    val oneLine = source
      .joinToString(" ")
      .replace("|", " ")
      .replace(Regex("\\s+"), " ")
      .trim()
    if (oneLine.isEmpty()) return ""
    return if (oneLine.length <= PREVIEW_MAX) oneLine else oneLine.take(PREVIEW_MAX).trimEnd() + "…"
  }

  fun lastActiveOf(messages: List<UiMessage>): Long =
    messages.maxOfOrNull { it.createdAtMillis } ?: 0L

  fun rowOf(
    session: SessionUi,
    pendingApprovals: Int,
    isRunning: Boolean,
    isActive: Boolean,
  ): SessionRow = SessionRow(
    id = session.id,
    // 用 id 尾巴兜底，用户至少能区分是哪一个。
    title = session.title.ifBlank { "未命名会话 · ${session.id.takeLast(4)}" },
    badge = badgeOf(pendingApprovals > 0, isRunning),
    preview = previewOf(session.messages),
    lastActiveMillis = lastActiveOf(session.messages),
    pendingApprovals = pendingApprovals,
    isActive = isActive,
    messageCount = session.messages.size,
  )

  fun sectionsOf(state: MorrowUiState): List<SessionSection> {
    if (state.sessions.isEmpty()) return emptyList()
    val rows = state.sessions.values.map { session ->
      rowOf(
        session = session,
        pendingApprovals = state.pendingApprovals.values.count { it.sessionId == session.id },
        isRunning = state.runs.containsKey(session.id),
        isActive = session.id == state.activeSessionId,
      )
    }
    val grouped = rows.groupBy {
      when (it.badge) {
        SessionBadge.Awaiting -> SessionGroup.NeedYou
        SessionBadge.Running -> SessionGroup.Running
        SessionBadge.Idle -> SessionGroup.Others
      }
    }
    val byRecency = compareByDescending<SessionRow> { it.lastActiveMillis > 0L }
      .thenByDescending { it.lastActiveMillis }
      .thenBy { it.title }
    return SessionGroup.entries.mapNotNull { group ->
      val list = grouped[group]?.sortedWith(byRecency) ?: return@mapNotNull null
      if (list.isEmpty()) null else SessionSection(group, list)
    }
  }

  fun readout(online: Boolean, sections: List<SessionSection>): String {
    if (!online) return "未连接 · 读不到会话"
    val all = sections.sumOf { it.rows.size }
    if (all == 0) return "还没有会话"
    val need = sections.firstOrNull { it.group == SessionGroup.NeedYou }?.rows?.size ?: 0
    val running = sections.firstOrNull { it.group == SessionGroup.Running }?.rows?.size ?: 0
    val parts = mutableListOf("$all 个")
    if (need > 0) parts += "$need 个待你决定"
    if (running > 0) parts += "$running 个在跑"
    return parts.joinToString(" · ")
  }

  fun relativeTime(lastActiveMillis: Long, nowMillis: Long): String? =
    Formatters.relative(lastActiveMillis, nowMillis)
}

object SessionListTags {
  const val SCREEN = "session-list"
  const val LIST = "session-list-items"
  const val BACK = "session-list-back"
  const val EMPTY = "session-list-empty"
  fun row(id: String) = "session-row-$id"
}

@Composable
fun SessionListScreen(
  sections: List<SessionSection>,
  onBack: () -> Unit,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
  /** 是否已连上桌面。未连接时不能说「没有会话」 */
  online: Boolean = true,
  /** 当前时刻，用于相对时间。传 System.currentTimeMillis() */
  nowMillis: Long = System.currentTimeMillis(),
) {
  val p = MorrowTheme.palette
  Column(modifier.fillMaxSize().testTag(SessionListTags.SCREEN)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = MorrowSpacing.s4)
        .padding(top = MorrowSpacing.s4, bottom = MorrowSpacing.s2),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "←",
        color = p.text2,
        fontSize = MorrowType.title,
        modifier = Modifier
          .clip(RoundedCornerShape(MorrowRadius.control))
          .testTag(SessionListTags.BACK)
          .clickable(onClick = onBack)
          .padding(horizontal = 8.dp, vertical = 4.dp),
      )
      Spacer(Modifier.width(MorrowSpacing.s2))
      Column(Modifier.weight(1f)) {
        Text("会话", color = p.text, fontSize = MorrowType.title)
        Text(
          text = SessionListProjection.readout(online, sections),
          color = p.text3,
          fontSize = MorrowType.xs,
        )
      }
    }

    if (sections.isEmpty()) {
      EmptySessions(online = online, modifier = Modifier.weight(1f))
    } else {
      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .testTag(SessionListTags.LIST),
        contentPadding = PaddingValues(
          start = MorrowSpacing.s4,
          end = MorrowSpacing.s4,
          top = MorrowSpacing.s1,
          bottom = MorrowSpacing.s6,
        ),
        verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2),
      ) {
        sections.forEach { section ->
          item(key = "h-${section.group.name}") {
            SectionHeading(section.group, section.rows.size)
          }
          items(section.rows, key = { it.id }) { row ->
            SessionCard(row = row, nowMillis = nowMillis, onClick = { onSelect(row.id) })
          }
        }
      }
    }
  }
}

/** 分组标题：一行细字 + 计数。不做吸顶 —— 列表短，吸顶反而抢空间。 */
@Composable
private fun SectionHeading(group: SessionGroup, count: Int) {
  val p = MorrowTheme.palette
  Row(
    Modifier
      .fillMaxWidth()
      .padding(top = MorrowSpacing.s3, bottom = MorrowSpacing.s1),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = group.label,
      color = when (group) {
        SessionGroup.NeedYou -> p.amber
        SessionGroup.Running -> p.gold
        SessionGroup.Others -> p.text3
      },
      fontSize = MorrowType.xs,
      fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.width(MorrowSpacing.s2))
    Text(
      text = "$count",
      color = p.hint,
      fontSize = MorrowType.xs,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.width(MorrowSpacing.s2))
    Box(Modifier.weight(1f).height(0.5.dp).background(p.line))
  }
}

@Composable
private fun SessionCard(row: SessionRow, nowMillis: Long, onClick: () -> Unit) {
  val p = MorrowTheme.palette
  val accent = when (row.badge) {
    SessionBadge.Running -> p.gold
    SessionBadge.Awaiting -> p.amber
    SessionBadge.Idle -> p.patina
  }
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(MorrowRadius.card))
      .background(if (row.isActive) p.surfaceHover else p.surface)
      .border(0.5.dp, if (row.isActive) p.lineGold else p.line, RoundedCornerShape(MorrowRadius.card))
      .clickable(onClick = onClick)
      .testTag(SessionListTags.row(row.id)),
  ) {
    Column(Modifier.weight(1f).padding(MorrowSpacing.s3)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        StateDot(accent, glow = row.badge == SessionBadge.Running)
        Spacer(Modifier.width(MorrowSpacing.s2))
        Text(
          text = row.title,
          color = p.text,
          fontSize = MorrowType.body,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.weight(1f),
        )
        SessionListProjection.relativeTime(row.lastActiveMillis, nowMillis)?.let { rel ->
          Spacer(Modifier.width(MorrowSpacing.s2))
          Text(rel, color = p.hint, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
        }
      }
      if (row.preview.isNotEmpty()) {
        Spacer(Modifier.height(MorrowSpacing.s1))
        Text(
          text = row.preview,
          color = p.text2,
          fontSize = MorrowType.sm,
          lineHeight = MorrowType.sm * MorrowType.lhUi,
          maxLines = 2,
        )
      }
      Spacer(Modifier.height(MorrowSpacing.s1))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = badgeLabel(row),
          color = accent,
          fontSize = MorrowType.xs,
          fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(MorrowSpacing.s2))
        Text(
          text = if (row.messageCount > 0) "${row.messageCount} 条" else "还没下发正文",
          color = p.hint,
          fontSize = MorrowType.xs,
          fontFamily = FontFamily.Monospace,
        )
        if (row.isActive) {
          Spacer(Modifier.width(MorrowSpacing.s2))
          Text("当前", color = p.gold, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
        }
      }
    }
  }
}

/** 状态标签。刻意把审批数写进去 —— 「2 个待批准」比「待你决定」有用得多。 */
private fun badgeLabel(row: SessionRow): String = when (row.badge) {
  SessionBadge.Running -> "运行中"
  SessionBadge.Awaiting -> "${row.pendingApprovals} 个待批准"
  SessionBadge.Idle -> "空闲"
}

@Composable
private fun EmptySessions(online: Boolean, modifier: Modifier = Modifier) {
  val p = MorrowTheme.palette
  Box(modifier.fillMaxWidth().testTag(SessionListTags.EMPTY), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = if (online) "桌面上还没有会话" else "读不到会话",
        color = p.text2,
        fontSize = MorrowType.body,
      )
      Spacer(Modifier.height(MorrowSpacing.s2))
      Text(
        text = if (online) "在电脑上新建一个对话，这里就会出现" else "连上电脑后，这里会列出全部会话",
        color = p.hint,
        fontSize = MorrowType.sm,
      )
    }
  }
}
