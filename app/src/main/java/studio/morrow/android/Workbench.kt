package studio.morrow.android

/**
 * 工位域：工位模型与事件投影，以及工位页与四区块。
 *
 * 工位是手机的主入口：连接状态、当前运行、待审批与今日统计都从这里出，
 * 模型与页面同文件便于核对「每个格子到底读的是哪一份状态」。
 */
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

// ── 原 workbench/WorkbenchModel.kt ──

// ───── WorkbenchModel.kt ─────
/**
 * 工位页（C1）的数据与格式层。**纯 Kotlin，不碰 Android API** —— 这样
 */
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

/**
 * 连接状态。蓝图屏 2 的头部行是「STUDIO-PC · 在线 4h 12m」——
 * 设备名、状态、在线时长三样一起才是「连着吗」的完整回答：
 * 只说「在线」而不说多久，用户无法判断这条连接是不是刚断过刚恢复。
 */
data class Connection(
  val phase: ConnectionPhase,
  /** 电脑名（配对时记下的） */
  val deviceName: String? = null,
  /** 在线起点：用来算「在线 4h 12m」 */
  val sinceMillis: Long? = null,
  /** 最后一次收到事件的时刻：离线时用来算「上次同步 12 分钟前」 */
  val lastEventMillis: Long? = null,
  /**
   * 当前通道档位文案（「直连」/「中继」/「推送」），来自 B3 的 tier。
   *
   * 为什么显示它：三级回退是产品承诺的一部分，用户看到「中继」就知道
   * 现在不是直连、速度会慢一些；不显示的话「偶尔变慢」永远查不出原因。
   */
  val tierLabel: String? = null
) {
  val isOnline: Boolean get() = phase == ConnectionPhase.Online

  companion object {
    val unpaired = Connection(phase = ConnectionPhase.Unpaired)
  }
}

/**
 * 运行中任务的**可显示**形态。
 *
 * 注意 [title] 与 [stage] 可以为 null：它们来自桌面事件（D 轨），
 * 手机端本地只知道 `ForegroundSyncState` 里的 taskId / step / total / startedAt。
 * 拿不到标题时显示任务标识（等宽字），**不编一个假标题**。
 */
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

/**
 * 等着我决定的事（审批）。
 *
 * 蓝图屏 2 原文：「2 个动作等你确认 / 最早等待 3 分 12 秒 / 去处理 ›」。
 * 「最早等待」比「还有 2 个」重要得多：两个审批里有一个已经等了 12 分钟，
 * 而另一个刚来 —— 只显示数量会让人以为都不急。
 */
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

/**
 * 工位页的完整快照。
 *
 * 四区块与蓝图「第一屏回答四个问题」一一对应，**顺序不可调换** ——
 * 前两个是状态、后两个是待办与结果，视线从上到下就是「确认 → 行动」。
 */
data class WorkbenchData(
  val connection: Connection,
  val running: RunningTask? = null,
  val approvals: PendingApprovals? = null,
  /** null 表示拿不到今日统计（未连接）：界面显示空态而不是四个 0 —— 0 会被读成「今天什么都没干」 */
  val today: TodayStats? = null,
  val artifacts: List<Artifact> = emptyList(),
  /** 上一次成功同步的时刻，用于底部状态条 */
  val syncedAtMillis: Long? = null,
  /** 有待补拉（来自 B4 的 PendingPull）：界面在状态条上提示「打开时补拉中」 */
  val pendingPull: Boolean = false,
  /**
   * 桌面 `todos_updated` 的当前待办（只保留未完成与进行中）。
   *
   * null = 还没收到过（界面不显示这一块）；空列表 = 桌面明确说没有待办。
   */
  val todos: List<UiTodo>? = null,
  /** 桌面 `goal_updated` 的目标态。null = 没开目标模式 */
  val goal: UiGoal? = null,
) {
  companion object {
    /** 冷启动的第一帧：还没连上电脑，什么都不知道 —— 全部走空态 */
    val initial = WorkbenchData(connection = Connection.unpaired)
  }
}

/**
 * 所有数字的显示规则。集中在一处的原因：
 * 同一个「已用时」在运行中区块和审批区块必须用同一种写法，
 * 两处各写一个函数迟早会一个写「3 分 12 秒」一个写「3m12s」。
 */
object WorkbenchFormat {

  /**
   * 连接时长：`4h 12m` / `12m 4s` / `4s`。
   *
   * 用英文单位是因为蓝图屏 2 的头部行就是 `在线 4h 12m` ——
   * 这一行是等宽小字，拉丁字符宽度稳定，不会随中文数字换行。
   */
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

  /**
   * 等待时长：`3 分 12 秒` / `2 小时 5 分` / `40 秒`。
   *
   * 与 [onlineDuration] 分开，是因为这两处的读者情绪不同：
   * 在线时长是「稳定读数」，用等宽拉丁；等待时长是「有人被卡住了」，
   * 用中文全称更接近日常说法，也更适合大声念出来。
   */
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
    // 必须在 Long 域里夹紧再转 Int：先 toInt() 会在 step 很大时溢出成负数，
    // 于是 `coerceIn(0,100)` 把一个溢出值夹成 0 或 100 —— 数字看着体面，但是错的。
    val pct = (step.toLong().coerceAtLeast(0L) * 100) / totalSteps
    return pct.coerceIn(0L, 100L).toInt()
  }

  /** 步数：「第 3 步 / 5」；总量未知时只给「第 3 步」 */
  fun stepLabel(step: Int, totalSteps: Int): String =
    if (totalSteps > 0) "第 ${step.coerceAtLeast(0)} 步 / $totalSteps"
    else "第 ${step.coerceAtLeast(0)} 步"

  /**
   * token 数：`412K` / `18.4K` / `1.2M` / `860`。
   * 否则同一个量级出现「1000K」和「1M」两种写法。
   */
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

  /**
   * 体积：`24.8K` / `182K` / `6.4M` / `1.2M` / `820B`。
   * 规则：小于 100 时保留一位小数，100 及以上取整 ——
   * 否则列表里会出现「1024K」和「1.0M」两种写法表示同一量级。
   */
  /**
   * 产物列表里的体积文案。
   * 桌面**不带大小** —— 用负数哨兵表示「拿不到」。这个哨兵只在产物
   * 必须显示 `—` 而不是 `0B`：0B 会被读成「这个文件是空的」，
   */
  fun artifactSize(count: Long): String = if (count < 0) "—" else bytes(count)

  fun bytes(count: Long): String {
    val n = count.coerceAtLeast(0)
    if (n < 1024) return "${n}B"

    val units = listOf("K", "M", "G", "T")
    var value = n.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
      value /= 1024.0
      unit++
    }

    // 一位小数（<100 时）；先按小数位算出来，再看是否溢出到下一个量级
    var decimals = if (value < 100.0) 1 else 0
    var rounded = roundTo(value, decimals)
    if (rounded >= 1024.0 && unit < units.lastIndex) {
      rounded /= 1024.0
      unit++
      decimals = 1
    }
    return formatFixed(rounded, decimals) + units[unit]
  }

  /** 时刻：`22:41`（24 小时制，两位补零） */
  fun clock(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone))

  /** 相对时刻：`刚刚` / `12 分钟前` / `3 小时前` / `昨天` */
  fun relative(millis: Long, nowMillis: Long): String {
    val diff = nowMillis - millis
    if (diff < 0) return "刚刚" // 时钟回拨：不显示「-3 分钟前」这种荒谬文案
    val min = diff / 60_000
    return when {
      min < 1 -> "刚刚"
      min < 60 -> "$min 分钟前"
      min < 60 * 24 -> "${min / 60} 小时前"
      min < 60 * 24 * 2 -> "昨天"
      else -> "${min / (60 * 24)} 天前"
    }
  }

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

  /**
   * 按小数位格式化，**补齐尾零**。
   *
   * 为什么 `1.0M` 不写成 `1M`：`.` 是「这里有精度」的信号。
   * 体积列表左对齐时，`1.0M` / `6.4M` / `182K` 三者的量级一眼可分；
   * 写成 `1M` 就与整数混淆，读的人要多想一步。
   */
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

// ───── WorkbenchProjection.kt ─────
/**
 * 把归并层的 [MorrowUiState] 投影成工位页要的 [WorkbenchData]。
 * ## 空态的两条铁律（不能破）
 */
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
    /** 审批从什么时候开始等 */
    oldestApprovalAtMillis: Long? = null,
  ): WorkbenchData {
    val online = connection.phase == ConnectionPhase.Online

    // ── 今日统计 ──
    // 只有「连上」且「收到过活动事件」才给数字。
    // 未连接时即便内存里有上次的累计，也不能显示 —— 那会让用户以为
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
    // state.artifacts 的 null / emptyList 语义直接透传，不在这里抹平
    val artifacts = if (online) {
      state.artifacts?.map { a ->
        Artifact(
          name = a.name,
          format = a.format,
          // 拿不到体积用 -1（渲染层显示 —），不能写 0 ——
          // 0 会被读成「这个文件是空的」
          bytes = a.bytes ?: BYTES_UNKNOWN,
          atMillis = a.atMillis,
        )
      }.orEmpty()
    } else emptyList()

    // ── 运行中任务（只取前台会话的那一个） ──
    val run = state.activeRun
    val running = if (online && run != null) {
      RunningTask(
        // 桌面 runId 就是手机能拿到的最稳定的任务标识
        taskId = run.runId,
        // 会话标题只在非空时当任务名 —— 空标题用 null，渲染层回退到 taskId，
        // 不能编一个「未命名任务」让用户以为桌面真有个叫这名字的任务
        title = state.active?.title?.takeIf { it.isNotBlank() },
        stage = run.currentTool,
        step = run.stepCount,
        // 桌面事件里没有「总步数」，给 0 表示未知 →
        // RunningTask.percent 会返回 null，界面显示「不确定」而不是 0%
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
      // 未连接时不展示桌面的待办/目标：那是上一段连接留下的快照，
      // 现在说「正在做什么」已经不成立。
      todos = if (online) state.todos else null,
      goal = if (online) state.goal else null,
    )
  }
}
// ── 原 workbench/WorkbenchScreen.kt ──

// ───── WorkbenchScreen.kt ─────
/**
 * 工位页（C1）。蓝图屏 2 原文：
 * 用户在地铁上单手握手机、只有二十秒。四个区块都必须在**不点任何东西**
 * 不必等真实时间流逝 —— 否则只能测「有没有这个字」。
 */
@Composable
fun WorkbenchScreen(
  data: WorkbenchData,
  onStopTask: (String) -> Unit = {},
  /**
   * 进入对话页。默认空实现只为测试方便 —— 生产里由 MorrowApp 传真实回调。
   */
  onOpenThread: () -> Unit = {},
  /**
   * 进入配对页。只在未配对时显示。
   *
   * 默认空实现沿用既有约定（测试方便），但生产必须传真实回调 ——
   * E1b 学到的教训：组件有测试、生产没接线时测试全绿。所以
   * `MainActivityRelayChipTest` 里有一条专门断言这个入口真的能点进去。
   */
  onOpenPairing: () -> Unit = {},
  /** 进入审批页。C4 接上「待我决定」那一块的点击 */
  onOpenApprovals: () -> Unit = {},
  /** 进入作品页。C5 接上「最近成品」那一块的点击 */
  onOpenWorks: () -> Unit = {},
  /**
   * 作品库是否已有作品。
   *
   * 「去作品页」入口原先只在有成品时出现，导致空成品 + 有作品的账号
   * 根本进不去作品页。这里让入口独立于成品列表存在。
   */
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

      // 四区块：单列时自上而下；折叠屏/平板按 AdaptiveColumns 分列，
      // 但**顺序不变** —— 分列只是排版，信息层级不随屏宽改变。
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
        // 待办放在最后：它是「桌面正在推进什么」的补充信息，
        // 没收到过（null）就整块不出现，不留一个空框。
        add {
          data.todos?.takeIf { it.isNotEmpty() }?.let { TodosBlock(it) }
        }
      }

      if (adaptive.columns <= 1) {
        Column(verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2 + 2.dp)) {
          blocks.forEach { it() }
        }
      } else {
        // 多列：第一列放「状态」（运行中），第二列放「待办与结果」，
        // 而不是按 items 顺序轮转 —— 否则平板上一列是「运行中 + 今日」，
        // 另一列是「待我决定 + 成品」，视线要来回横跳。
        //
        // 按索引取而不是 `blocks[i]` 直取：第 4 块（待办）是可选的，
        // 直取会在它缺席时越界。列分配保持「偶数列左、奇数列右」。
        Row(horizontalArrangement = Arrangement.spacedBy(MorrowSpacing.s2 + 2.dp)) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2 + 2.dp)) {
            blocks.forEachIndexed { index, block -> if (index % 2 == 0) block() }
          }
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2 + 2.dp)) {
            blocks.forEachIndexed { index, block -> if (index % 2 != 0) block() }
          }
        }
      }

      Spacer(Modifier.height(MorrowSpacing.s4))
      StatusFooter(data = data, nowMillis = nowMillis, isShortScreen = adaptive.isShortScreen)
    }
  }
}

internal const val TAG_SCREEN = "workbench"
internal const val TAG_HEADER = "workbench.header"
internal const val TAG_FOOTER = "workbench.footer"
internal const val TAG_OPEN_THREAD = "workbench.openThread"

/**
 * 「去配对」入口。
 *
 * 单独给 testTag 而不是靠文案匹配：文案将来改一个字，
 * 「未配对时有出口」的测试就会假失败，而它守的其实是「有没有出口」。
 */
internal const val TAG_GO_PAIRING = "workbench.goPairing"

/**
 * 头部行：设备名 · 状态 · 在线时长 · 通道档位。
 */
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
  // 短屏判断：640dp 高的设备上加一行说明会把底部状态条挤出可视区
  val shortScreen = isShortScreen
  val (label, tint) = when (c.phase) {
    ConnectionPhase.Online -> "在线" to p.patina
    ConnectionPhase.Connecting -> "正在连接" to p.amber
    ConnectionPhase.Offline -> "已断开" to p.rust
    ConnectionPhase.Unpaired -> "未配对" to p.mauve
  }

  Column(Modifier.fillMaxWidth().testTag(TAG_HEADER)) {
    // 字标：这一屏在任何状态下都要认得出是「明日工坊」
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "MORROW · 工位",
        color = MorrowTheme.hint,
        fontSize = MorrowType.xs,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.4.sp
      )
      Spacer(Modifier.weight(1f))
      // 进对话的入口。只在已连上时出现 —— 没连上时进对话页只能看到
      // 一个空会话，等于给用户一条死路。放在字标行右侧而不是做成
      // 悬浮主按钮：工位页是「看一眼」的页面，常驻主按钮会把视线
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
      // 状态灯 + 文字：颜色是第二编码，永不用颜色单独传达状态
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
      // 设置入口放在状态行末尾：不新增一行高度。短屏（360×640）上
      // 多一行会把底部「尚未同步」挤出可视区（AdaptiveScaffoldTest 抓到）。
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
    // 此前这里只显示「未配对」四个字 —— 用户看得出没连上，却没有任何地方
    // 能去解决它，等于一条死路（E1b 报告 §7.1 记的就是这条）。
    //
    // 只在 Unpaired 时出现：Offline（连过又断）不该引导去重新配对，
    // 那种情况会自动重连，给配对入口反而会让用户以为要重新配一遍。
    if (c.phase == ConnectionPhase.Unpaired) {
      // 这里**不**放入口。原计划放在 chip 旁，但 A4 的 640dp 视口测试
      // 证明：哪怕只是同行加 6dp 间距 + 11sp 文字，也会触发换行撑高，
      // 把底部状态条挤出可视区（实测：删掉这行 → 14 项全过，加回 → 1 项红）。
      //
      // 入口改放 `UnknownTodayBlock` —— 那块在未配对时本来就显示
      // 「今日统计要等连上电脑才有」，有整块空白可用，加一个按钮不影响任何布局。
      // 用户视线本来也会落到那块大空态上。
    }

    // 头部下方一条金线：这是整页唯一一条「亮线」，把标题区与读数区分开。
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

/**
 * 「没在跑」的空态。
 */
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

/**
 * 「没有待我决定」的空态。它是个好消息，所以用中性色，不用绿色庆祝。
 * 但**只在真的连上桌面时才是好消息**。没连上时写「没有等你决定的事」
 * 而真实情况是「手机不知道」。这与「今日统计不能显示四个 0」是同一条原则：
 */
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

/**
 * 拿不到今日统计（未连接）时的空态。
 *
 * 这里**不能显示四个 0**：0 会被读成「今天什么都没干」，
 * 而真实情况是「手机不知道」。两者对用户的含义完全不同。
 */
@Composable
internal fun UnknownTodayBlock(connection: Connection, onOpenPairing: () -> Unit = {}, isShortScreen: Boolean = false) {
  val p = MorrowTheme.palette
  val shortScreen = isShortScreen
  SectionCard {
    val unpaired = connection.phase == ConnectionPhase.Unpaired
    // 未配对时把「去配对」放进标题行右侧 —— **不新增任何高度**。
    //
    // 为什么不放在卡片里：640dp 高的屏幕上这块卡片已经排满，
    // 加一个 34dp 的按钮会把底部状态条挤出可视区（A4 的 viewport1 实测抓到，
    // 删掉按钮 → 14 项全过，加回 → 1 项红）。
    // 标题行本来就有右侧空白（`trailing` 槽），把入口放这里零成本。
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

    // 「去配对」入口放在这里，而不是 chip 旁。
    //
    // 为什么不放 chip 旁：A4 的 640dp 视口测试证明，哪怕只是同一行加
    // 6dp 间距 + 11sp 文字，也会触发换行撑高，把底部状态条挤出可视区
    // （实测：删掉那处 → 14 项全过；加回 → 1 项红）。
    //
    // 为什么短屏连这里也不放：640dp 高时这块卡片本身已经占满剩余空间，
    // 再加 34dp 的按钮就会把底部状态条推出去（同样实测确认）。
    // 短屏的正确处置**不是**压缩按钮（那会让点击区小于 44dp 的可达性下限），
    // 而是在上面那句说明里直接告诉用户怎么做 —— 文案已经写明「配对并连上后」。
    //
    // 只在未配对时出现：Offline（连过又断）会自动重连，给配对入口
    // 反而会让用户以为要重新配一遍。
  }
}

/**
 * 底部状态条。
 */
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
      // 短屏（< 640dp 高）省掉这一句：它是环境说明而不是信息，
      // 在矮屏上会与上面的区块抢最后几十像素。同一套「短屏减装饰」规则
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
    // 底部留白让最后一行不贴手势条：手势条本身由 MorrowScaffold 的 inset 让开，
    // 这里补的是「视觉呼吸」，不是安全区。
    Spacer(Modifier.height(MorrowSpacing.s2))
  }
}

// ───── WorkbenchBlocks.kt ─────
/**
 * 工位页的四个读数区块。
 * 色弱用户与强光下都读得出来。不允许「只有绿点没有字」。
 */
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

    // 标题两行截断：任务描述可能很长，工位页要的是「认得出是哪件事」，
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
      // 「停止」是真实动作（B4 的 ForegroundSyncService 已有 stop），不是装饰。
      // 工位页唯一一个能按下去的控件 —— 因为它是唯一在这一屏就能完成的事。
      SmallAction(label = "停止跟盯", onClick = { onStop(task.taskId) }, danger = true)
    }

    if (task.percent == null) {
      // 总量未知时不画进度条填充，改在下面说明 —— 空的进度条会被读成「0%」。
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

/**
 * 桌面的当前待办（`todos_updated`）。
 *
 * 手机上看待办的意义不是「再列一遍清单」，而是回答「它现在卡在哪一步」：
 * 所以只显示未完成与进行中，已完成的收进 `+N 已完成`。
 */
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

/**
 * 待我决定。
 *
 * C1 当时只做读数（「处理入口属 C4」），C4 把入口接上：整块可点，进审批页。
 * 为什么不做成「点数字进、点空白不进」：用户看到这块卡片时想的就是
 * 「去处理」，把可点区域做成整块比让他精确点中数字更容易按到。
 */
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
    // 「最早等待」比「共 2 个」重要：两个里有一个已经等了 12 分钟，
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
    // 入口做成一行明确的「去审批」，而不是让整块可点。
    //
    // 整块可点试过，有两个问题：①`clickable` 会 mergeDescendants，
    // 把区块标题的语义并进父节点，按 tag 定位标题就找不到了（C1 的
    // 「四区块标题全部可见」三条测试立刻红）②整块可点时用户不知道
    // 边界在哪，点数字、点空白、点说明的反应都不一样才叫意外。
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .testTag("workbench-approvals")
        .clip(RoundedCornerShape(MorrowRadius.control))
        .border(0.5.dp, p.amber.copy(alpha = 0.45f), RoundedCornerShape(MorrowRadius.control))
        .then(Modifier.androidClickableWorkbench(onOpen))
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

/** 工位页的 clickable 包装（与审批页同名函数不冲突：不同包） */
private fun Modifier.androidClickableWorkbench(onClick: () -> Unit): Modifier =
  this.then(Modifier.clickable(onClick = onClick))

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
  artifacts: List<Artifact>,
  nowMillis: Long,
  onOpenWorks: () -> Unit = {},
  worksAvailable: Boolean = false,
) {
  val p = MorrowTheme.palette
  SectionCard {
    SectionHeader(label = "最近成品", accent = p.mauve, trailing = if (artifacts.isEmpty()) null else "共 ${artifacts.size} 件")
    Spacer(Modifier.height(MorrowSpacing.s2))

    if (artifacts.isEmpty() && !worksAvailable) {
      Spacer(Modifier.height(MorrowSpacing.s2))
      Text(
        text = "还没有成品。任务跑完、文件落盘后，这里会按时间列出最近 4 件。",
        color = MorrowTheme.hint,
        fontSize = MorrowType.sm,
        lineHeight = (MorrowType.sm.value * MorrowType.lhUi).sp
      )
      return@SectionCard
    }

    if (artifacts.isEmpty()) {
      Text(
        text = "桌面的作品都在作品库里，点下面进入。",
        color = MorrowTheme.hint,
        fontSize = MorrowType.sm,
        lineHeight = (MorrowType.sm.value * MorrowType.lhUi).sp
      )
    } else {
      artifacts.take(MAX_ROWS).forEachIndexed { index, item ->
        if (index > 0) HairLine(color = p.line)
        ArtifactRow(item = item, nowMillis = nowMillis)
      }
    }
    Spacer(Modifier.height(MorrowSpacing.s2))
    // 与审批块同一个模式：底部一行明确的入口，不让整块可点
    // （整块可点会 mergeDescendants，把区块标题的语义吞掉 —— C4 踩过）
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .testTag("workbench-works")
        .clip(RoundedCornerShape(MorrowRadius.control))
        .border(0.5.dp, p.mauve.copy(alpha = 0.45f), RoundedCornerShape(MorrowRadius.control))
        .then(Modifier.androidClickableWorkbench(onOpenWorks))
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
    // 格式标签：固定宽度的微圆角小方块。固定宽度是为了让「HTML / DOCX / ZIP / PDF」
    // 四个不同字数的标签在列表里对齐 —— 否则文件名起始位置会参差。
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
        // 蓝图屏 2 的写法：「24.8K · 22:41」。相对时刻在悬停/详情才有，
        // 工位页给绝对时刻 —— 「22:41」比「12 分钟前」更容易和记忆对齐。
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

// ───────────────────────── 共用小件 ─────────────────────────

/**
 * 区块底板。
 *
 * 不用 Material3 `Card`：它自带 elevation 与 tonal surface 语义，
 * 深色底上会叠出一层比页面更亮的色块，与「墨 + 金线」的桌面质感冲突。
 * 这里只有「卡片底色 + 1dp 描边 + 9dp 圆角」三样。
 */
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
  /**
   * 标题行右侧的可点击动作（与 [trailing] 共用同一行，**不增加高度**）。
   *
   * 存在的理由：640dp 高的屏幕上，工位页每一块卡片都已经排满，
   * 任何新起一行都会把底部状态条挤出可视区（A4 的 6 组视口测试会抓到）。
   * 把入口塞进已有的标题行是唯一不改变布局高度的位置。
   */
  onTrailingClick: (() -> Unit)? = null,
  trailingTag: String? = null,
) {
  Row(
    // 每个区块头带稳定 tag：区块标题可能与读数文案同字（「运行中」既是
    // 区块名也是今日统计里的一格），测试按 tag 定位才不会歧义。
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
    // 细线向右淡出：它是分隔符而不是装饰框，长度随可用宽度自适应
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

/**
 * 细进度条。高度 2dp —— 工位页不需要粗条：
 * 它要的是「有没有在动」，精确值由旁边的步数与百分比给出。
 */
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
            // 从暗金到亮金：进度条右端更亮，视觉上「往右推进」
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

/**
 * 小动作按钮：微圆角矩形（控件 5dp），不是胶囊。
 *
 * 用户明确要求「与桌面版一致的微圆角矩形」，所以这里不用
 * `Button`（Material3 默认 20dp+ 圆角 = 胶囊），手写一个带按下反馈的 Box。
 */
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
