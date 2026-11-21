package studio.morrow.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

enum class ConnectionPhase {
  /** 还没配对：手机不知道要连哪台电脑 */
  Unpaired,

  /** 正在连（扫码后 / 重连中） */
  Connecting,

  /** 已连上，能收到事件 */
  Online,

  /** 此前配过但对不上（电脑关机 / 不在同一网段 / 中继也断了） */
  Offline
}

data class Connection(
  val phase: ConnectionPhase,
  /** 电脑名（配对时记下的） */
  val deviceName: String? = null,
  /** 在线起点：用来算「在线 4h 12m」 */
  val sinceMillis: Long? = null,
  /** 最后一次收到事件的时刻：离线时用来算「上次同步 12 分钟前」 */
  val lastEventMillis: Long? = null,
  val tierLabel: String? = null
) {
  val isOnline: Boolean get() = phase == ConnectionPhase.Online

  companion object {
    val unpaired = Connection(phase = ConnectionPhase.Unpaired)
  }
}

data class RunningTask(
  val taskId: String,
  val title: String? = null,
  val stage: String? = null,
  val step: Int,
  val totalSteps: Int,
  val startedAtMillis: Long
) {
  /** 进度百分比；总量未知（<=0）时返回 null —— 界面据此显示「不确定」而不是 0% */
  val percent: Int? get() = WorkbenchFormat.percentOf(step, totalSteps)

  fun elapsedSeconds(nowMillis: Long): Long =
    ((nowMillis - startedAtMillis) / 1000).coerceAtLeast(0)

  /** 主行文案：有标题用标题，没有就用任务标识 */
  val primaryLine: String get() = title ?: taskId

  /** 是否是从任务标识退化显示的（界面据此换等宽字并降低字重） */
  val isTitleMissing: Boolean get() = title == null
}

data class PendingApprovals(
  val count: Int,
  /** 最早那条审批的进入时刻 */
  val oldestAtMillis: Long
) {
  fun waitedMillis(nowMillis: Long): Long = (nowMillis - oldestAtMillis).coerceAtLeast(0)
}

/** 今日统计。蓝图屏 2：「18 轮 · 412K token」+ 完成/运行中/失败/成品 四个计数 */
data class TodayStats(
  val rounds: Int,
  val tokens: Long,
  val completed: Int,
  val running: Int,
  val failed: Int,
  val artifacts: Int
)

/** 成品条目。蓝图屏 2：「HTML / Q3-sales-review.html / 24.8K · 22:41」 */
data class Artifact(
  val name: String,
  /** 格式标签：HTML / DOCX / ZIP / PDF …（显示在左侧小格里） */
  val format: String,
  val bytes: Long,
  val atMillis: Long
)

data class WorkbenchData(
  val connection: Connection,
  val running: RunningTask? = null,
  val approvals: PendingApprovals? = null,
  val syncNotificationHidden: Boolean = false,
  val syncingFirstSnapshot: Boolean = false,
  /** null 表示拿不到今日统计（未连接）：界面显示空态而不是四个 0 —— 0 会被读成「今天什么都没干」 */
  val today: TodayStats? = null,
  val artifacts: List<Artifact>? = null,
  /** 上一次成功同步的时刻，用于底部状态条 */
  val syncedAtMillis: Long? = null,
  /** 有待补拉（来自 B4 的 PendingPull）：界面在状态条上提示「打开时补拉中」 */
  val pendingPull: Boolean = false,
  val todos: List<UiTodo>? = null,
  /** 桌面 `goal_updated` 的目标态。null = 没开目标模式 */
  val goal: UiGoal? = null,
) {
  companion object {
    /** 冷启动的第一帧：还没连上电脑，什么都不知道 —— 全部走空态 */
    val initial = WorkbenchData(connection = Connection.unpaired)
  }
}

object WorkbenchFormat {

  fun onlineDuration(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
      h > 0 -> "${h}h ${m}m"
      m > 0 -> "${m}m ${sec}s"
      else -> "${sec}s"
    }
  }

  fun waitDuration(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
      h > 0 -> "${h} 小时 ${m} 分"
      m > 0 -> "${m} 分 ${sec} 秒"
      else -> "${sec} 秒"
    }
  }

  /** 进度百分比；总量未知或非正时返回 null（界面显示「不确定」，不算成 0） */
  fun percentOf(step: Int, totalSteps: Int): Int? {
    if (totalSteps <= 0) return null
    val pct = (step.toLong().coerceAtLeast(0L) * 100) / totalSteps
    return pct.coerceIn(0L, 100L).toInt()
  }

  /** 步数：「第 3 步 / 5」；总量未知时只给「第 3 步」 */
  fun stepLabel(step: Int, totalSteps: Int): String =
    if (totalSteps > 0) "第 ${step.coerceAtLeast(0)} 步 / $totalSteps"
    else "第 ${step.coerceAtLeast(0)} 步"

  fun tokens(count: Long): String {
    var n = count.coerceAtLeast(0)
    if (n < 1_000) return "$n"
    var unit = 0
    val units = listOf("K", "M", "G")
    var value = n.toDouble() / 1_000.0
    while (value >= 1_000.0 && unit < units.lastIndex) {
      value /= 1_000.0
      unit++
    }
    var rounded = roundTo(value, 1)
    if (rounded >= 1_000.0 && unit < units.lastIndex) {
      rounded /= 1_000.0
      unit++
    }
    return trimZero(rounded) + units[unit]
  }

  fun artifactSize(count: Long): String = if (count < 0) "—" else bytes(count)

  fun bytes(count: Long): String = Formatters.bytes(count)

  /** 时刻：`22:41`（24 小时制，两位补零） */
  fun clock(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone))

  fun relative(millis: Long, nowMillis: Long): String =
    Formatters.relative(millis, nowMillis) ?: "刚刚"

  /** 轮次 + token 的一行摘要：「18 轮 · 412K token」 */
  fun roundsSummary(rounds: Int, tokens: Long): String =
    "${rounds.coerceAtLeast(0)} 轮 · ${tokens(tokens)} token"

  /** 一位小数，**去掉**多余的 `.0`（token 用：412K 不写成 412.0K） */
  private fun trimZero(v: Double): String {
    val rounded = roundTo(v, 1)
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
  }

  private fun roundTo(v: Double, decimals: Int): Double {
    if (decimals <= 0) return v.roundToInt().toDouble()
    val factor = 10.0
    return (v * factor).roundToInt() / factor
  }

  private fun formatFixed(v: Double, decimals: Int): String =
    if (decimals <= 0) v.roundToInt().toString()
    else {
      val scaled = (v * 10).roundToInt()
      val whole = scaled / 10
      val frac = kotlin.math.abs(scaled % 10)
      "$whole.$frac"
    }

  private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}

object WorkbenchProjection {

  /** 未连接时字节数显示用的占位。`bytes` 不可空，用 -1 表示「拿不到」 */
  const val BYTES_UNKNOWN = -1L

  fun project(
    state: MorrowUiState,
    connection: Connection,
    /** 已连接后过了多久（用于「在线 4h 12m」） */
    onlineMillis: Long = 0L,
    /** 上一次成功同步时刻；null = 还没同步过 */
    syncedAtMillis: Long? = null,
    pendingPull: Boolean = false,
    /** F-12：跟盯中但通知不可见。由调用方查真实系统状态后传入。 */
    syncNotificationHidden: Boolean = false,
    /** 审批从什么时候开始等 */
    oldestApprovalAtMillis: Long? = null,
  ): WorkbenchData {
    val online = connection.phase == ConnectionPhase.Online

    // ── 今日统计 ──
    // 只有「连上」且「收到过活动事件」才给数字。
    // 现在看到的还是最新的。
    val today = if (online) {
      val acc = state.today
      if (acc != null && acc.sawAnything) {
        TodayStats(
          rounds = acc.rounds,
          tokens = acc.tokens,
          completed = acc.completed,
          running = acc.running,
          failed = acc.failed,
          artifacts = acc.artifacts,
        )
      } else null
    } else null

    // ── 最近成品 ──
    val artifacts: List<Artifact>? = if (online) {
      state.artifacts?.map { a ->
        Artifact(
          name = a.name,
          format = a.format,
          // 0 会被读成「这个文件是空的」
          bytes = a.bytes ?: BYTES_UNKNOWN,
          atMillis = a.atMillis,
        )
      }
    } else null

    val run = state.activeRun
    val running = if (online && run != null) {
      RunningTask(
        taskId = run.runId,
        title = state.active?.title?.takeIf { it.isNotBlank() },
        stage = run.currentTool,
        step = run.stepCount,
        totalSteps = 0,
        startedAtMillis = run.startedAtMillis,
      )
    } else null

    // ── 待审批 ──
    val approvals = if (online && state.pendingApprovals.isNotEmpty()) {
      PendingApprovals(
        count = state.pendingApprovals.size,
        oldestAtMillis = oldestApprovalAtMillis
          ?: state.pendingApprovals.values
            .map { it.requestedAtMillis }
            .filter { it > 0L }
            .minOrNull()
          ?: 0L,
      )
    } else null

    return WorkbenchData(
      connection = connection,
      running = running,
      approvals = approvals,
      today = today,
      artifacts = artifacts,
      syncedAtMillis = syncedAtMillis,
      pendingPull = pendingPull,
      syncNotificationHidden = syncNotificationHidden,
      // 现在说「正在做什么」已经不成立。
      todos = if (online) state.activeTodos else null,
      goal = if (online) state.activeGoal else null,
    )
  }
}

@Composable
fun WorkbenchScreen(
  data: WorkbenchData,
  onStopTask: (String) -> Unit = {},
  onOpenThread: () -> Unit = {},
  onOpenPairing: () -> Unit = {},
  /** 进入审批页。C4 接上「待我决定」那一块的点击 */
  onOpenApprovals: () -> Unit = {},
  /** 进入作品页。C5 接上「最近成品」那一块的点击 */
  onOpenWorks: () -> Unit = {},
  hasWorks: Boolean = false,
  /** 进入设置页（右上角入口）。 */
  onOpenSettings: () -> Unit = {},
  /** 测试注入：默认每秒自增，驱动「已用时 / 在线时长」这类读数 */
  ticker: Boolean = true
) {
  val adaptive = LocalAdaptive.current
  val p = MorrowTheme.palette

  var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
  if (ticker) {
    LaunchedEffect(Unit) {
      while (true) {
        nowMillis = System.currentTimeMillis()
        delay(1_000)
      }
    }
  }

  Box(Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(
          horizontal = if (adaptive.isShortScreen) MorrowSpacing.s4 else MorrowSpacing.s5,
          vertical = MorrowSpacing.s4
        )
        .testTag(TAG_SCREEN)
    ) {
      ConnectionHeader(
        data = data,
        nowMillis = nowMillis,
        onOpenThread = onOpenThread,
        onOpenPairing = onOpenPairing,
        onOpenSettings = onOpenSettings,
      )
      Spacer(Modifier.height(if (adaptive.isShortScreen) 12.dp else 16.dp))

      val blocks: List<@Composable () -> Unit> = buildList {
        add {
          data.running?.let { task ->
            RunningBlock(task = task, nowMillis = nowMillis, onStop = onStopTask)
          } ?: IdleBlock(connection = data.connection)
        }
        add {
          if (data.approvals != null && data.approvals.count > 0) {
            ApprovalsBlock(
              approvals = data.approvals,
              nowMillis = nowMillis,
              onOpen = onOpenApprovals,
            )
          } else {
            NoApprovalsBlock(online = data.connection.phase == ConnectionPhase.Online)
          }
        }
        add {
          if (data.today != null) {
            TodayBlock(today = data.today)
          } else {
            UnknownTodayBlock(connection = data.connection, onOpenPairing = onOpenPairing)
          }
        }
        add { ArtifactsBlock(artifacts = data.artifacts, nowMillis = nowMillis, onOpenWorks = onOpenWorks, worksAvailable = hasWorks) }
        add {
          data.todos?.takeIf { it.isNotEmpty() }?.let { TodosBlock(it) }
        }
      }

      if (adaptive.columns <= 1) {
        Column(verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2 + 2.dp)) {
          blocks.forEach { it() }
        }
      } else {
        //
        Row(horizontalArrangement = Arrangement.spacedBy(MorrowSpacing.s2 + 2.dp)) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2 + 2.dp)) {
            blocks.forEachIndexed { index, block -> if (index % 2 == 0) block() }
          }
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2 + 2.dp)) {
            blocks.forEachIndexed { index, block -> if (index % 2 != 0) block() }
          }
        }
      }

      if (data.syncingFirstSnapshot) {
        Text(
          text = "正在同步电脑上的会话…",
          color = p.text2,
          fontSize = MorrowType.sm,
          modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_SYNCING)
            .padding(vertical = MorrowSpacing.s2),
        )
      }

      Spacer(Modifier.height(MorrowSpacing.s4))
      //
      if (data.syncNotificationHidden) {
        Text(
          text = "正在跟盯任务，但系统通知被关掉了 —— 你看不到进展，"
            + "也可能被系统随时冻结。点上面「设置」可以打开通知。",
          color = p.rust,
          fontSize = MorrowType.xs,
          modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_SYNC_HIDDEN),
        )
        Spacer(Modifier.height(MorrowSpacing.s2))
      }
      StatusFooter(data = data, nowMillis = nowMillis, isShortScreen = adaptive.isShortScreen)
    }
  }
}

internal const val TAG_SCREEN = "workbench"
internal const val TAG_HEADER = "workbench.header"
internal const val TAG_FOOTER = "workbench.footer"

internal const val TAG_SYNC_HIDDEN = "workbench.sync-hidden"

/** 「正在同步电脑上的会话」（F-20）。首连未对齐时短暂出现。 */
internal const val TAG_SYNCING = "workbench.syncing"
internal const val TAG_OPEN_THREAD = "workbench.openThread"

internal const val TAG_GO_PAIRING = "workbench.goPairing"

@Composable
internal fun ConnectionHeader(
  data: WorkbenchData,
  nowMillis: Long,
  isShortScreen: Boolean = false,
  onOpenThread: () -> Unit = {},
  onOpenPairing: () -> Unit = {},
  onOpenSettings: () -> Unit = {},
) {
  val p = MorrowTheme.palette
  val c = data.connection
  val shortScreen = isShortScreen
  val (label, tint) = when (c.phase) {
    ConnectionPhase.Online -> "在线" to p.patina
    ConnectionPhase.Connecting -> "正在连接" to p.amber
    ConnectionPhase.Offline -> "已断开" to p.rust
    ConnectionPhase.Unpaired -> "未配对" to p.mauve
  }

  Column(Modifier.fillMaxWidth().testTag(TAG_HEADER)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "MORROW · 工位",
        color = MorrowTheme.hint,
        fontSize = MorrowType.xs,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.4.sp
      )
      Spacer(Modifier.weight(1f))
      // 从读数上拉走。
      if (c.isOnline) {
        Text(
          text = "进对话 ›",
          color = p.gold,
          fontSize = MorrowType.xs,
          modifier = Modifier
            .testTag(TAG_OPEN_THREAD)
            .clickable(onClick = onOpenThread)
            .padding(vertical = 4.dp, horizontal = 2.dp)
        )
      }
    }
    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      StatusDot(color = tint, diameter = 7.dp, glow = c.isOnline)
      Spacer(Modifier.width(2.dp))
      Text(
        text = c.deviceName ?: "尚未连接电脑",
        color = if (c.deviceName == null) MorrowTheme.hint else p.text,
        fontSize = MorrowType.title,
        fontWeight = FontWeight.Medium,
        maxLines = 1
      )
      Spacer(Modifier.weight(1f))
      Text(
        text = "设置",
        color = p.hint,
        fontSize = MorrowType.xs,
        modifier = Modifier
          .testTag("workbench-settings")
          .clickable(onClick = onOpenSettings)
          .padding(horizontal = 4.dp)
      )
    }

    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = label,
        color = tint,
        fontSize = MorrowType.sm,
        fontFamily = FontFamily.Monospace
      )
      if (c.isOnline && c.sinceMillis != null) {
        Text(
          text = " ${WorkbenchFormat.onlineDuration(nowMillis - c.sinceMillis)}",
          color = MorrowTheme.text2,
          fontSize = MorrowType.sm,
          fontFamily = FontFamily.Monospace
        )
      }
      c.tierLabel?.let {
        Text(
          text = " · $it",
          color = MorrowTheme.hint,
          fontSize = MorrowType.sm,
          fontFamily = FontFamily.Monospace
        )
      }
      if (!c.isOnline && c.lastEventMillis != null) {
        Text(
          text = " · 上次同步 ${WorkbenchFormat.relative(c.lastEventMillis, nowMillis)}",
          color = MorrowTheme.hint,
          fontSize = MorrowType.sm,
          fontFamily = FontFamily.Monospace
        )
      }
    }

    // 未配对时的唯一出口。
    //
    //
    if (c.phase == ConnectionPhase.Unpaired) {
      //
      // 用户视线本来也会落到那块大空态上。
    }

    Spacer(Modifier.height(12.dp))
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
      drawLine(
        brush = Brush.horizontalGradient(
          0f to p.gold.copy(alpha = 0.55f),
          0.55f to p.gold.copy(alpha = 0.12f),
          1f to Color.Transparent
        ),
        start = Offset(0f, 0f),
        end = Offset(size.width, 0f),
        strokeWidth = 1.dp.toPx()
      )
    }
  }
}

@Composable
internal fun IdleBlock(connection: Connection) {
  val p = MorrowTheme.palette
  SectionCard {
    SectionHeader(label = "运行中", accent = p.gold)
    Spacer(Modifier.height(MorrowSpacing.s3))
    if (connection.phase == ConnectionPhase.Unpaired) {
      Text(
        text = "还没和电脑配对",
        color = p.text,
        fontSize = MorrowType.bodyLg
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = "配对后，这台手机就能跟盯电脑上正在跑的任务，并在锁屏时收到进度。",
        color = MorrowTheme.text2,
        fontSize = MorrowType.base,
        lineHeight = (MorrowType.base.value * MorrowType.lhCopy).sp
      )
    } else if (connection.isOnline) {
      Text(
        text = "电脑上没有任务在跑",
        color = p.text,
        fontSize = MorrowType.bodyLg
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = "从对话页发一条指令，任务开始后这里会显示进度、步数与已用时。",
        color = MorrowTheme.text2,
        fontSize = MorrowType.base,
        lineHeight = (MorrowType.base.value * MorrowType.lhCopy).sp
      )
    } else {
      Text(
        text = "现在看不到电脑上的任务",
        color = p.text,
        fontSize = MorrowType.bodyLg
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = "连接断开时手机不知道对面在跑什么。重连后会自动补上这段时间的事件。",
        color = MorrowTheme.text2,
        fontSize = MorrowType.base,
        lineHeight = (MorrowType.base.value * MorrowType.lhCopy).sp
      )
    }
  }
}

@Composable
internal fun NoApprovalsBlock(online: Boolean = true) {
  val p = MorrowTheme.palette
  SectionCard {
    SectionHeader(label = "待我决定", accent = p.amber)
    Spacer(Modifier.height(MorrowSpacing.s3))
    Text(
      text = if (online) "没有等你决定的事" else "还不知道有没有事等你决定",
      color = p.text,
      fontSize = MorrowType.bodyLg
    )
    Spacer(Modifier.height(6.dp))
    Text(
      text = if (online) {
        "需要你批准的动作会同时出现在通知栏与这一格，并且是唯一会绕过免打扰的通知。"
      } else {
        "未连接桌面时这一格读不到队列 —— 它不代表「没有事」，只代表「还没连上」。"
      },
      color = MorrowTheme.text2,
      fontSize = MorrowType.base,
      lineHeight = (MorrowType.base.value * MorrowType.lhCopy).sp
    )
  }
}

@Composable
internal fun UnknownTodayBlock(connection: Connection, onOpenPairing: () -> Unit = {}, isShortScreen: Boolean = false) {
  val p = MorrowTheme.palette
  val shortScreen = isShortScreen
  SectionCard {
    val unpaired = connection.phase == ConnectionPhase.Unpaired
    //
    SectionHeader(
      label = "今日",
      accent = p.patina,
      onTrailingClick = if (unpaired) onOpenPairing else null,
      trailingTag = if (unpaired) TAG_GO_PAIRING else null,
    )
    Spacer(Modifier.height(MorrowSpacing.s3))
    Text(
      text = "今日统计要等连上电脑才有",
      color = p.text,
      fontSize = MorrowType.bodyLg
    )

    if (!(unpaired && shortScreen)) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = if (unpaired) {
          "统计由电脑记录；配对并连上后，这里会显示今天跑了几轮、用了多少 token。"
        } else {
          "暂时取不到电脑上的统计。这一格不会用 0 占位，因为 0 会被误读成「今天什么都没干」。"
        },
        color = MorrowTheme.text2,
        fontSize = MorrowType.base,
        lineHeight = (MorrowType.base.value * MorrowType.lhCopy).sp
      )
    }

    //
    //
    //
    // 反而会让用户以为要重新配一遍。
  }
}

@Composable
internal fun StatusFooter(data: WorkbenchData, nowMillis: Long, isShortScreen: Boolean) {
  val p = MorrowTheme.palette
  Column(Modifier.fillMaxWidth().testTag(TAG_FOOTER)) {
    HairLine(color = p.line)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = when {
          data.pendingPull -> "补拉中"
          data.syncedAtMillis != null -> "上次同步 ${WorkbenchFormat.relative(data.syncedAtMillis, nowMillis)}"
          else -> "尚未同步"
        },
        color = if (data.pendingPull) p.gold else MorrowTheme.hint,
        fontSize = MorrowType.sm,
        fontFamily = FontFamily.Monospace
      )
      Spacer(Modifier.weight(1f))
      // 在 A4 卡就定下了，这里保持一致。
      if (!isShortScreen) {
        Text(
          text = "1080×1920 基准 · edge-to-edge",
          color = MorrowTheme.hint,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace
        )
      }
    }
    // 这里补的是「视觉呼吸」，不是安全区。
    Spacer(Modifier.height(MorrowSpacing.s2))
  }
}

@Composable
internal fun RunningBlock(
  task: RunningTask,
  nowMillis: Long,
  onStop: (String) -> Unit
) {
  val p = MorrowTheme.palette
  val m = MorrowTheme.mobile
  SectionCard {
    SectionHeader(label = "运行中", accent = p.gold)

    Spacer(Modifier.height(MorrowSpacing.s3))

    // 不是全文 —— 全文在对话页。
    Text(
      text = task.primaryLine,
      color = if (task.isTitleMissing) MorrowTheme.hint else p.text,
      fontSize = if (task.isTitleMissing) MorrowType.base else MorrowType.bodyLg,
      lineHeight = (MorrowType.bodyLg.value * MorrowType.lhUi).sp,
      fontFamily = if (task.isTitleMissing) FontFamily.Monospace else FontFamily.Default,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis
    )

    task.stage?.let { stage ->
      Spacer(Modifier.height(6.dp))
      Text(
        text = stage,
        color = MorrowTheme.text2,
        fontSize = MorrowType.base,
        fontFamily = FontFamily.Monospace
      )
    }

    Spacer(Modifier.height(MorrowSpacing.s3))
    MiniProgress(percent = task.percent, color = p.gold)

    Spacer(Modifier.height(MorrowSpacing.s2))
    Row(verticalAlignment = Alignment.CenterVertically) {
      // 三个读数：步数 / 百分比 / 已用时
      Reading(WorkbenchFormat.stepLabel(task.step, task.totalSteps))
      ReadingDivider()
      Reading(
        task.percent?.let { "$it%" } ?: "不确定",
        dim = task.percent == null
      )
      ReadingDivider()
      Reading("已用 ${WorkbenchFormat.onlineDuration(task.elapsedSeconds(nowMillis) * 1000)}")
      Spacer(Modifier.weight(1f))
      SmallAction(label = "停止跟盯", onClick = { onStop(task.taskId) }, danger = true)
    }

    if (task.percent == null) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = "这一步不知道还有多远，桌面没有报总量",
        color = MorrowTheme.hint,
        fontSize = MorrowType.sm,
        lineHeight = (MorrowType.sm.value * MorrowType.lhUi).sp
      )
    }
  }
}

@Composable
internal fun TodosBlock(todos: List<UiTodo>) {
  val p = MorrowTheme.palette
  val open = todos.filter { it.status != "completed" }
  val done = todos.size - open.size
  if (open.isEmpty() && done == 0) return
  SectionCard {
    SectionHeader(label = "桌面待办", accent = p.patina)
    Spacer(Modifier.height(MorrowSpacing.s2))
    if (open.isEmpty()) {
      Text(
        text = "清单已全部完成",
        color = MorrowTheme.text2,
        fontSize = MorrowType.base,
      )
    } else {
      open.take(4).forEach { todo ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
          StateDot(
            color = if (todo.status == "in_progress") p.gold else p.lineStrong,
            glow = todo.status == "in_progress",
          )
          Spacer(Modifier.width(MorrowSpacing.s2))
          Text(
            text = todo.text,
            color = if (todo.status == "in_progress") p.text else MorrowTheme.text2,
            fontSize = MorrowType.base,
            lineHeight = (MorrowType.base.value * MorrowType.lhUi).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (open.size > 4) {
        Spacer(Modifier.height(4.dp))
        Reading("另有 ${open.size - 4} 项未展开")
      }
    }
    if (done > 0) {
      Spacer(Modifier.height(6.dp))
      Reading("+$done 已完成")
    }
  }
}

@Composable
internal fun ApprovalsBlock(
  approvals: PendingApprovals,
  nowMillis: Long,
  onOpen: () -> Unit = {},
) {
  val p = MorrowTheme.palette
  val waited = WorkbenchFormat.waitDuration(approvals.waitedMillis(nowMillis))
  SectionCard(accent = p.amber) {
    SectionHeader(label = "待我决定", accent = p.amber)
    Spacer(Modifier.height(MorrowSpacing.s3))
    Row(verticalAlignment = Alignment.Bottom) {
      Text(
        text = "${approvals.count}",
        color = p.amber,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium
      )
      Spacer(Modifier.width(8.dp))
      Text(
        text = "个动作等你确认",
        color = p.text,
        fontSize = MorrowType.body,
        modifier = Modifier.padding(bottom = 4.dp)
      )
    }
    Spacer(Modifier.height(6.dp))
    // 只说数量会让人以为都不急。
    Text(
      text = "最早一条已等 $waited",
      color = MorrowTheme.text2,
      fontSize = MorrowType.base,
      fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(10.dp))
    HairLine(color = p.line)
    Spacer(Modifier.height(8.dp))
    Text(
      text = "手机只能「允许一次」或「拒绝」，不提供始终允许",
      color = MorrowTheme.hint,
      fontSize = MorrowType.sm,
      lineHeight = (MorrowType.sm.value * MorrowType.lhUi).sp
    )
    Spacer(Modifier.height(MorrowSpacing.s2))
    //
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .testTag("workbench-approvals")
        .clip(RoundedCornerShape(MorrowRadius.control))
        .border(0.5.dp, p.amber.copy(alpha = 0.45f), RoundedCornerShape(MorrowRadius.control))
        .then(Modifier.androidClickablePublic(onOpen))
        .padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "去审批",
        color = p.amber,
        fontSize = MorrowType.base,
      )
      Spacer(Modifier.weight(1f))
      Text(
        text = "→",
        color = p.amber,
        fontSize = MorrowType.base,
        fontFamily = FontFamily.Monospace,
      )
    }
  }
}

/** 今日。读数与「最近成品」同屏但不合并：一个是量，一个是物。 */
@Composable
internal fun TodayBlock(today: TodayStats) {
  val p = MorrowTheme.palette
  SectionCard {
    SectionHeader(label = "今日", accent = p.patina)
    Spacer(Modifier.height(MorrowSpacing.s3))
    Text(
      text = WorkbenchFormat.roundsSummary(today.rounds, today.tokens),
      color = p.text,
      fontSize = MorrowType.h2,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Medium
    )
    Spacer(Modifier.height(MorrowSpacing.s3))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      StatCell(today.completed, "完成", p.patina)
      StatCell(today.running, "运行中", p.gold)
      StatCell(today.failed, "失败", p.rust)
      StatCell(today.artifacts, "成品", p.mauve)
    }
    if (today.rounds == 0 && today.artifacts == 0) {
      Spacer(Modifier.height(MorrowSpacing.s3))
      Text(
        text = "今天还没有跑过任务。从对话页发一条指令就会出现在这里。",
        color = MorrowTheme.hint,
        fontSize = MorrowType.sm,
        lineHeight = (MorrowType.sm.value * MorrowType.lhUi).sp
      )
    }
  }
}

/** 最近成品。C1 只列出与读数，预览与分享属 C5。 */
@Composable
internal fun ArtifactsBlock(
  /** null = 拿不到（未连接 / 桌面没上报）；emptyList = 确实一件都没有 */
  artifacts: List<Artifact>?,
  nowMillis: Long,
  onOpenWorks: () -> Unit = {},
  worksAvailable: Boolean = false,
) {
  val p = MorrowTheme.palette
  SectionCard {
    SectionHeader(
      label = "最近成品",
      accent = p.mauve,
      trailing = if (artifacts == null || artifacts.isEmpty()) null else "共 ${artifacts.size} 件",
    )
    Spacer(Modifier.height(MorrowSpacing.s2))

    // ── 三态，三句话 ──
    //
    val showWorksEntry = !artifacts.isNullOrEmpty() || worksAvailable
    when {
      artifacts == null -> Text(
        text = "还没连上电脑，看不到最近成品。",
        color = MorrowTheme.hint,
        fontSize = MorrowType.sm,
        lineHeight = (MorrowType.sm.value * MorrowType.lhUi).sp
      )

      artifacts.isEmpty() && !worksAvailable -> {
        Spacer(Modifier.height(MorrowSpacing.s2))
        Text(
          text = "还没有成品。任务跑完、文件落盘后，这里会按时间列出最近 4 件。",
          color = MorrowTheme.hint,
          fontSize = MorrowType.sm,
          lineHeight = (MorrowType.sm.value * MorrowType.lhUi).sp
        )
        return@SectionCard
      }

      artifacts.isEmpty() -> Text(
        text = "桌面的作品都在作品库里，点下面进入。",
        color = MorrowTheme.hint,
        fontSize = MorrowType.sm,
        lineHeight = (MorrowType.sm.value * MorrowType.lhUi).sp
      )

      else -> artifacts.take(MAX_ROWS).forEachIndexed { index, item ->
        if (index > 0) HairLine(color = p.line)
        ArtifactRow(item = item, nowMillis = nowMillis)
      }
    }
    if (!showWorksEntry) return@SectionCard
    Spacer(Modifier.height(MorrowSpacing.s2))
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .testTag("workbench-works")
        .clip(RoundedCornerShape(MorrowRadius.control))
        .border(0.5.dp, p.mauve.copy(alpha = 0.45f), RoundedCornerShape(MorrowRadius.control))
        .then(Modifier.androidClickablePublic(onOpenWorks))
        .padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("去作品页", color = p.mauve, fontSize = MorrowType.base)
      Spacer(Modifier.weight(1f))
      Text("→", color = p.mauve, fontSize = MorrowType.base, fontFamily = FontFamily.Monospace)
    }
  }
}

@Composable
private fun ArtifactRow(item: Artifact, nowMillis: Long) {
  val p = MorrowTheme.palette
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .width(46.dp)
        .height(22.dp)
        .clip(RoundedCornerShape(MorrowRadius.xs))
        .background(p.bgElev)
        .border(1.dp, p.line, RoundedCornerShape(MorrowRadius.xs)),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = item.format,
        color = MorrowTheme.text2,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.6.sp
      )
    }
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(
        text = item.name,
        color = p.text,
        fontSize = MorrowType.ui,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = "${WorkbenchFormat.artifactSize(item.bytes)} · ${WorkbenchFormat.clock(item.atMillis)}",
        color = MorrowTheme.hint,
        fontSize = MorrowType.sm,
        fontFamily = FontFamily.Monospace
      )
    }
  }
}

internal const val MAX_ROWS = 4

/** 区块头的 testTag：`workbench.section.运行中` 这样，测试按它定位区块 */
internal fun sectionTagOf(label: String): String = "workbench.section.$label"

@Composable
internal fun SectionCard(
  accent: Color? = null,
  content: ColumnScopeContent
) {
  val p = MorrowTheme.palette
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(MorrowRadius.card))
      .background(MorrowTheme.surface)
      .border(
        width = 1.dp,
        color = accent?.copy(alpha = 0.22f) ?: p.line,
        shape = RoundedCornerShape(MorrowRadius.card)
      )
      .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s3)
  ) {
    content()
  }
}

/** Column 内容的别名，避免在上面每处都写 `@Composable ColumnScope.() -> Unit` */
internal typealias ColumnScopeContent = @Composable () -> Unit

/** 区块头：等宽大写小字 + 一条从标签向右淡出的细线。 */
@Composable
internal fun SectionHeader(
  label: String,
  accent: Color,
  trailing: String? = null,
  onTrailingClick: (() -> Unit)? = null,
  trailingTag: String? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth().testTag(sectionTagOf(label)),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      color = accent,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace,
      letterSpacing = 1.6.sp
    )
    Spacer(Modifier.width(10.dp))
    Canvas(Modifier.weight(1f).height(1.dp)) {
      drawRect(
        brush = Brush.horizontalGradient(
          0f to accent.copy(alpha = 0.38f),
          1f to Color.Transparent
        )
      )
    }
    if (onTrailingClick != null) {
      Spacer(Modifier.width(10.dp))
      Text(
        text = trailing ?: "去配对 ›",
        color = accent,
        fontSize = MorrowType.sm,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
          .then(if (trailingTag != null) Modifier.testTag(trailingTag) else Modifier)
          .clickable(onClick = onTrailingClick)
          .padding(vertical = 2.dp, horizontal = 2.dp)
      )
    } else {
      trailing?.let {
        Spacer(Modifier.width(10.dp))
        Text(
          text = it,
          color = MorrowTheme.hint,
          fontSize = MorrowType.sm,
          fontFamily = FontFamily.Monospace
        )
      }
    }
  }
}

/** 一条极细的分隔线（区块内条目之间） */
@Composable
internal fun HairLine(color: Color) {
  Box(
    Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(color)
  )
}

@Composable
internal fun MiniProgress(percent: Int?, color: Color) {
  val p = MorrowTheme.palette
  Box(
    Modifier
      .fillMaxWidth()
      .height(2.dp)
      .clip(RoundedCornerShape(1.dp))
      .background(p.lineStrong)
  ) {
    if (percent != null && percent > 0) {
      Box(
        Modifier
          .fillMaxWidth(percent / 100f)
          .height(2.dp)
          .clip(RoundedCornerShape(1.dp))
          .background(
            Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color))
          )
      )
    }
  }
}

/** 一行等宽读数 */
@Composable
internal fun Reading(text: String, dim: Boolean = false) {
  Text(
    text = text,
    color = if (dim) MorrowTheme.hint else MorrowTheme.text2,
    fontSize = MorrowType.sm,
    fontFamily = FontFamily.Monospace
  )
}

/** 读数之间的分隔点 */
@Composable
internal fun ReadingDivider() {
  Text(
    text = "·",
    color = MorrowTheme.hint,
    fontSize = MorrowType.sm,
    modifier = Modifier.padding(horizontal = 6.dp)
  )
}

/** 数字 + 标签竖排（今日统计的四个格子） */
@Composable
internal fun StatCell(value: Int, label: String, color: Color) {
  Column(horizontalAlignment = Alignment.Start) {
    Text(
      text = "$value",
      color = if (value == 0) MorrowTheme.hint else color,
      fontSize = MorrowType.title,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Medium
    )
    Spacer(Modifier.height(2.dp))
    Text(
      text = label,
      color = MorrowTheme.hint,
      fontSize = MorrowType.sm
    )
  }
}

@Composable
internal fun SmallAction(
  label: String,
  onClick: () -> Unit,
  danger: Boolean = false,
  height: Dp = 30.dp
) {
  val p = MorrowTheme.palette
  val accent = if (danger) p.rust else p.gold
  Box(
    modifier = Modifier
      .height(height)
      .clip(RoundedCornerShape(MorrowRadius.control))
      .background(accent.copy(alpha = 0.10f))
      .border(1.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(MorrowRadius.control))
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = label,
      color = accent,
      fontSize = MorrowType.sm,
      fontWeight = FontWeight.Medium
    )
  }
}

/** 状态灯：一个实心点 + 可选外光。颜色永远配文字出现，不单独传达状态。 */
@Composable
internal fun StatusDot(color: Color, diameter: Dp = 7.dp, glow: Boolean = true) {
  Canvas(Modifier.size(diameter * 3)) {
    val r = diameter.toPx() / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    if (glow) {
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(color.copy(alpha = 0.42f), Color.Transparent),
          center = c,
          radius = r * 3f
        ),
        radius = r * 3f,
        center = c
      )
    }
    drawCircle(color = color, radius = r, center = c)
  }
}
