package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.morrow.android.ForegroundSyncState
import studio.morrow.android.SyncState
import studio.morrow.android.TAG_HEADER
import studio.morrow.android.TAG_SCREEN
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import org.robolectric.RuntimeEnvironment
import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import studio.morrow.android.ForegroundSyncService
import studio.morrow.android.NotifyChannels

// ───── WorkbenchModelTest.kt ─────
/**
 * C1 工位页的纯数据层（不依赖 Android，纯 JVM 可跑）。
 *
 * 这组测试盯的是「数字看起来对但其实不对」那一类错误 ——
 * 工位页的每个数字都是「要不要现在起身去开电脑」的判断依据，
 * 算错不会崩，只会误导。
 */
class WorkbenchModelTest {

  // ───────────────── 进度 ─────────────────

  @Test
  fun 总量未知时进度为_null_而不是零() {
    // 0 会被读成「卡在第一步」。这是蓝图明确要求区分的两种状态：
    // 「不知道还有几步」与「刚起步」。
    assertNull(WorkbenchFormat.percentOf(step = 3, totalSteps = 0))
    assertNull(WorkbenchFormat.percentOf(step = 3, totalSteps = -1))
  }

  @Test
  fun step_超过总量时夹在一百() {
    // 桌面并发任务的计数抖动会送来 step > total 的瞬间值
    assertEquals(100, WorkbenchFormat.percentOf(step = 12, totalSteps = 10))
  }

  @Test
  fun 极大_step_不会因_Int_溢出变成负数() {
    // (Int.MAX_VALUE * 100) 在 Int 里会溢出成负数 → 旧写法会显示「-100%」
    val p = WorkbenchFormat.percentOf(step = Int.MAX_VALUE, totalSteps = 1)
    assertEquals(100, p)
  }

  @Test
  fun 步数文案_总量已知与未知两种写法() {
    assertEquals("第 3 步 / 5", WorkbenchFormat.stepLabel(3, 5))
    assertEquals("第 3 步", WorkbenchFormat.stepLabel(3, 0))
    // 负数步数（不该出现，但防御）：至少不能显示「第 -1 步」
    assertEquals("第 0 步 / 5", WorkbenchFormat.stepLabel(-1, 5))
  }

  // ───────────────── 时长 ─────────────────

  @Test
  fun 在线时长按量级切换单位() {
    assertEquals("4s", WorkbenchFormat.onlineDuration(4_000))
    assertEquals("12m 4s", WorkbenchFormat.onlineDuration(12 * 60_000 + 4_000))
    assertEquals("4h 12m", WorkbenchFormat.onlineDuration((4 * 3600 + 12 * 60) * 1000L))
  }

  @Test
  fun 等待时长用中文全称() {
    assertEquals("40 秒", WorkbenchFormat.waitDuration(40_000))
    assertEquals("3 分 12 秒", WorkbenchFormat.waitDuration(3 * 60_000 + 12_000))
    assertEquals("2 小时 5 分", WorkbenchFormat.waitDuration((2 * 3600 + 5 * 60) * 1000L))
  }

  @Test
  fun 时钟回拨时时长不为负() {
    // 手机改时间 / 时区跳变都会出现 now < startedAt
    assertEquals("0s", WorkbenchFormat.onlineDuration(-5_000))
    assertEquals("0 秒", WorkbenchFormat.waitDuration(-5_000))
  }

  @Test
  fun 运行中任务的已用时在时钟回拨时为0() {
    val task = RunningTask(taskId = "t1", step = 1, totalSteps = 10, startedAtMillis = 10_000)
    assertEquals(0L, task.elapsedSeconds(nowMillis = 5_000))
    assertEquals(45L, task.elapsedSeconds(nowMillis = 55_000))
  }

  // ───────────────── token ─────────────────

  @Test
  fun token_按千分位切档且不补尾零() {
    assertEquals("860", WorkbenchFormat.tokens(860))
    assertEquals("412K", WorkbenchFormat.tokens(412_000))
    assertEquals("18.4K", WorkbenchFormat.tokens(18_400))
    assertEquals("1.2M", WorkbenchFormat.tokens(1_200_000))
    // 恰好 1000：切成 1K（不是 0.9K）
    assertEquals("1K", WorkbenchFormat.tokens(1_000))
    // 999_999 不写成 1000K
    assertEquals("1M", WorkbenchFormat.tokens(999_999))
  }

  @Test
  fun 轮次摘要一行读得懂() {
    assertEquals("18 轮 · 412K token", WorkbenchFormat.roundsSummary(18, 412_000))
    // 负数（数据源异常）不该显示成「-3 轮」
    assertEquals("0 轮 · 0 token", WorkbenchFormat.roundsSummary(-3, -1))
  }

  // ───────────────── 体积 ─────────────────

  @Test
  fun 体积按_1024_进制切档() {
    assertEquals("820B", WorkbenchFormat.bytes(820))
    assertEquals("24.8K", WorkbenchFormat.bytes(25_395))       // 24.8K
    assertEquals("182K", WorkbenchFormat.bytes(186_368))        // 182K
    assertEquals("6.4M", WorkbenchFormat.bytes(6_710_886))      // 6.4M
    assertEquals("1.2M", WorkbenchFormat.bytes(1_258_291))      // 1.2M
  }

  /**
   * 这一条是本组最有价值的一条。
   *
   * `1023.6K` 四舍五入成 `1024K` 时必须进位成 `1.0M`。
   * 如果不判进位，列表里会同时出现「1024K」和「1.0M」表示同一量级 ——
   * 读的人会以为一个是 KB 一个是 MB。
   */
  @Test
  fun 进位到下一个量级时不出现一千零二十四K() {
    // 1023.6K = 1048371 B → 应显示 1.0M
    val v = WorkbenchFormat.bytes(1_048_371)
    assertEquals("1.0M", v)
    assertFalse("不应出现 1024K 这种写法", v.contains("1024"))
  }

  @Test
  fun 体积小于_1024_显示字节() {
    assertEquals("0B", WorkbenchFormat.bytes(0))
    assertEquals("1023B", WorkbenchFormat.bytes(1023))
    assertEquals("1.0K", WorkbenchFormat.bytes(1024))
  }

  @Test
  fun 体积负数按零处理() {
    assertEquals("0B", WorkbenchFormat.bytes(-100))
  }

  // ───────────────── 相对时刻 ─────────────────

  @Test
  fun 相对时刻按分钟小时天切档() {
    val now = 1_700_000_000_000L
    assertEquals("刚刚", WorkbenchFormat.relative(now - 10_000, now))
    assertEquals("12 分钟前", WorkbenchFormat.relative(now - 12 * 60_000, now))
    assertEquals("3 小时前", WorkbenchFormat.relative(now - 3 * 3600_000, now))
    assertEquals("昨天", WorkbenchFormat.relative(now - 30 * 3600_000, now))
    assertEquals("2 天前", WorkbenchFormat.relative(now - 50 * 3600_000, now))
  }

  @Test
  fun 时钟回拨时相对时刻显示刚刚() {
    val now = 1_700_000_000_000L
    // 未来时刻不该显示「-5 分钟前」
    assertEquals("刚刚", WorkbenchFormat.relative(now + 5 * 60_000, now))
  }

  // ───────────────── 数据模型 ─────────────────

  @Test
  fun 运行中任务缺标题时退化为任务标识() {
    val noTitle = RunningTask(taskId = "task-42", step = 1, totalSteps = 3, startedAtMillis = 0)
    assertEquals("task-42", noTitle.primaryLine)
    assertTrue("界面要据此换等宽字", noTitle.isTitleMissing)

    val withTitle = noTitle.copy(title = "把销售明细做成 HTML 复盘")
    assertEquals("把销售明细做成 HTML 复盘", withTitle.primaryLine)
    assertFalse(withTitle.isTitleMissing)
  }

  @Test
  fun 审批等待时长从最早一条算起() {
    val p = PendingApprovals(count = 2, oldestAtMillis = 10_000)
    assertEquals(3 * 60_000L + 12_000, p.waitedMillis(nowMillis = 10_000 + 3 * 60_000 + 12_000))
    assertEquals(0L, p.waitedMillis(nowMillis = 5_000)) // 时钟回拨
  }

  @Test
  fun 冷启动快照是未配对且没有任何数字() {
    val init = WorkbenchData.initial
    assertEquals(ConnectionPhase.Unpaired, init.connection.phase)
    assertNull("未连接时不能有运行中任务", init.running)
    assertNull("未连接时不能有审批数", init.approvals)
    // 关键：today 为 null 而不是四个 0 —— 0 会被读成「今天什么都没干」
    assertNull("未连接时今日统计必须是 null，不能是零值", init.today)
    assertTrue("未连接时不能有成品列表", init.artifacts.isEmpty())
    assertFalse(init.pendingPull)
  }

  @Test
  fun 连接状态有设备名与在线起点才算在线读数() {
    val online = Connection(
      phase = ConnectionPhase.Online,
      deviceName = "STUDIO-PC",
      sinceMillis = 1_000,
      tierLabel = "直连"
    )
    assertTrue(online.isOnline)
    assertEquals("STUDIO-PC", online.deviceName)
    assertEquals("直连", online.tierLabel)
  }

  @Test
  fun 今日统计与成品列表可以独立缺省() {
    // 场景：连上了但今天还没跑过任务 —— today 有值但全是 0，成品为空
    val data = WorkbenchData(
      connection = Connection(ConnectionPhase.Online, "STUDIO-PC", 1_000),
      today = TodayStats(0, 0, 0, 0, 0, 0),
      artifacts = emptyList()
    )
    assertEquals(0, data.today!!.rounds)
    assertTrue(data.artifacts.isEmpty())

    val withArtifact = data.copy(
      artifacts = listOf(Artifact("Q3-sales-review.html", "HTML", 25_395, 1_700_000_000_000))
    )
    assertEquals("Q3-sales-review.html", withArtifact.artifacts[0].name)
  }

  // ───────────────── 待审批「最早等待」 ─────────────────

  @Test
  fun 待审批最早等待取真实到达时间而不是_1970() {
    // 旧实现 `minOfOrNull { 0L }` 的选择器忽略元素，永远返回 0 →
    // 界面把「刚刚到达」显示成「已等 56 年」。这条用例盯的就是它。
    val state = studio.morrow.android.MorrowUiState(
      pendingApprovals = mapOf(
        "req-a" to studio.morrow.android.UiApproval(
          requestId = "req-a",
          toolName = "write_file",
          sessionId = "s1",
          requestedAtMillis = 9_000L,
        ),
        "req-b" to studio.morrow.android.UiApproval(
          requestId = "req-b",
          toolName = "run_command",
          sessionId = "s1",
          requestedAtMillis = 5_000L,
        ),
      ),
    )
    val data = WorkbenchProjection.project(
      state = state,
      connection = Connection(ConnectionPhase.Online, "STUDIO-PC", 1_000),
    )
    assertEquals(2, data.approvals!!.count)
    assertEquals(5_000L, data.approvals!!.oldestAtMillis)
  }

  @Test
  fun 待审批缺时间时保持未知而不是编一个时间() {
    val state = studio.morrow.android.MorrowUiState(
      pendingApprovals = mapOf(
        "req-a" to studio.morrow.android.UiApproval(
          requestId = "req-a",
          toolName = "write_file",
          sessionId = "s1",
        ),
      ),
    )
    val data = WorkbenchProjection.project(
      state = state,
      connection = Connection(ConnectionPhase.Online, "STUDIO-PC", 1_000),
    )
    assertEquals(0L, data.approvals!!.oldestAtMillis)
  }
}

// ───── WorkbenchScreenTest.kt ─────
/**
 * C1 工位页渲染测试（Robolectric + Compose）。
 *
 * ## 这组测试盯的是什么
 *
 * 蓝图的验收是「首屏必须在 1.2 秒内可读（骨架屏不算可读）」，还有
 * 「无任务时显示空态文案而非空白卡片」「进度、步数、耗时三项都来自真实事件」。
 *
 * 帧率与 1.2 秒属 E2 真机矩阵（本机无模拟器），这里能证的是**内容层**：
 * 四个区块在首帧就有真实可读文字、读数来自传入的数据而不是占位符、
 * 空态是文案而不是空白。这是「骨架屏不算可读」的可测部分。
 *
 * ## 为什么断言具体文案而不是「有节点」
 *
 * 只断言「非空」会放过 `Text("—")` 这种假读数。所以每条断言都写死
 * 期望的字面值（`第 3 步 / 5`、`已用 5m 0s`、`24.8K · 22:41`），
 * 数据一变就必须同步改测试 —— 这是刻意的摩擦。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h731dp-420dpi")
class WorkbenchScreenTest {

  @get:Rule
  val compose = createComposeRule()

  private val onlinePc = Connection(
    phase = ConnectionPhase.Online,
    deviceName = "STUDIO-PC",
    sinceMillis = 1_000L,
    tierLabel = "直连"
  )

  /**
   * 满数据 fixture：四个区块都有内容。多列与等宽两条测试共用，
   * 避免各自造一份、将来改字段时只改一处。
   */
  private val fullData = WorkbenchData(
    connection = onlinePc,
    running = RunningTask(
      taskId = "t-1", title = "重构同步层", stage = "扫描依赖",
      step = 3, totalSteps = 5, startedAtMillis = 0
    ),
    approvals = PendingApprovals(count = 2, oldestAtMillis = 192_000L),
    today = TodayStats(
      rounds = 12, tokens = 412_000L,
      completed = 9, running = 0, failed = 1, artifacts = 4
    ),
    artifacts = listOf(Artifact(name = "重构方案.md", format = "MD", bytes = 24_800L, atMillis = 0L))
  )

  private fun setScreen(data: WorkbenchData) {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        // ticker = false：时间读数由传入的 startedAt 与「现在」算出，
        // 测试里不启动每秒 tick，避免时间抖动让断言不稳定
        WorkbenchScreen(data = data, ticker = false)
      }
    }
  }

  // ───────────────── 四区块都在 ─────────────────

  @Test
  fun 四个区块标签全部渲染() {
    setScreen(
      WorkbenchData(
        connection = onlinePc,
        running = RunningTask("t1", "把销售明细做成 HTML 复盘", "分析数据", 3, 5, 0),
        approvals = PendingApprovals(2, 0),
        today = TodayStats(18, 412_000, 6, 1, 0, 4),
        artifacts = listOf(Artifact("Q3-sales-review.html", "HTML", 25_395, 0))
      )
    )
    compose.onNodeWithTag(TAG_SCREEN).assertIsDisplayed()
    // 蓝图规定四区块顺序不可调换，四个区块头必须都在。
    // 用 tag 而不是文字定位：「运行中」既是区块名，也是今日统计里的一格标签，
    // 按文字找会命中两个节点。
    compose.onNodeWithTag(sectionTagOf("运行中")).assertIsDisplayed()
    compose.onNodeWithTag(sectionTagOf("待我决定")).assertIsDisplayed()
    compose.onNodeWithTag(sectionTagOf("今日")).assertIsDisplayed()
    compose.onNodeWithTag(sectionTagOf("最近成品")).assertIsDisplayed()
  }

  @Test
  fun 头部行给出设备名_在线状态与通道档位() {
    setScreen(WorkbenchData(connection = onlinePc, today = TodayStats(0, 0, 0, 0, 0, 0)))
    compose.onNodeWithTag(TAG_HEADER).assertIsDisplayed()
    compose.onNodeWithText("STUDIO-PC").assertIsDisplayed()
    // 三样一起才回答「连着吗」：状态、时长、通道
    compose.onNodeWithText("在线", substring = true).assertIsDisplayed()
    compose.onNodeWithText("直连", substring = true).assertIsDisplayed()
  }

  // ───────────────── 读数来自真实数据 ─────────────────

  @Test
  fun 运行中区块显示真实步数与百分比() {
    setScreen(
      WorkbenchData(
        connection = onlinePc,
        running = RunningTask("t1", "分析数据", null, step = 3, totalSteps = 5, startedAtMillis = 0),
        today = TodayStats(0, 0, 0, 0, 0, 0)
      )
    )
    compose.onNodeWithText("第 3 步 / 5").assertIsDisplayed()
    compose.onNodeWithText("60%").assertIsDisplayed()
    compose.onNodeWithText("分析数据").assertIsDisplayed()
  }

  @Test
  fun 总量未知时显示不确定而不是零() {
    setScreen(
      WorkbenchData(
        connection = onlinePc,
        running = RunningTask("task-42", null, null, step = 2, totalSteps = 0, startedAtMillis = 0),
        today = TodayStats(0, 0, 0, 0, 0, 0)
      )
    )
    // 「不确定」是蓝图要求区分出来的状态；0% 会被读成「卡住」
    compose.onNodeWithText("不确定").assertIsDisplayed()
    compose.onNodeWithText("这一步不知道还有多远，桌面没有报总量").assertIsDisplayed()
    // 没有标题时退化成等宽任务标识，而不是编一个假标题
    compose.onNodeWithText("task-42").assertIsDisplayed()
  }

  @Test
  fun 今日统计显示轮次与四个计数() {
    setScreen(
      WorkbenchData(
        connection = onlinePc,
        today = TodayStats(18, 412_000, 6, 1, 0, 4)
      )
    )
    compose.onNodeWithText("18 轮 · 412K token").assertIsDisplayed()
    // 四个格子的读数与标签：读数用数字（6/1/0/4），标签各自独立
    compose.onNodeWithText("完成").assertIsDisplayed()
    compose.onAllNodesWithText("运行中").assertCountEquals(2) // 区块头 + 格子标签
    compose.onNodeWithText("失败").assertIsDisplayed()
    compose.onNodeWithText("成品").assertIsDisplayed()
  }

  @Test
  fun 最近成品显示格式标签_文件名_体积与时刻() {
    setScreen(
      WorkbenchData(
        connection = onlinePc,
        today = TodayStats(0, 0, 0, 0, 0, 1),
        artifacts = listOf(
          Artifact("Q3-sales-review.html", "HTML", 25_395, 1_700_000_000_000)
        )
      )
    )
    compose.onNodeWithText("Q3-sales-review.html").assertIsDisplayed()
    compose.onNodeWithText("HTML").assertIsDisplayed()
    // 体积格式：24.8K（一位小数），不是 25.4K / 25395B
    compose.onNodeWithText("24.8K", substring = true).assertIsDisplayed()
  }

  @Test
  fun 审批区块给出数量与最早等待() {
    // oldestAt 设成「现在」，waited 会是秒级小数字；断言结构而不卡死具体秒数
    setScreen(
      WorkbenchData(
        connection = onlinePc,
        approvals = PendingApprovals(count = 2, oldestAtMillis = System.currentTimeMillis()),
        today = TodayStats(0, 0, 0, 0, 0, 0)
      )
    )
    compose.onNodeWithText("2").assertIsDisplayed()
    compose.onNodeWithText("个动作等你确认").assertIsDisplayed()
    compose.onNodeWithText("最早一条已等", substring = true).assertIsDisplayed()
  }

  @Test
  fun 满数据时四个区块头全部在首屏内可见() {
    // 蓝图验收：「首屏必须在 1.2 秒内可读（骨架屏不算可读）」。
    // 1.2 秒测不了（本机无模拟器），但「可读」的前置条件是**内容在屏幕上**：
    // 区块头如果落在折线以下，用户看到的就是半截卡片。
    // 所以这条断言四个区块头都 assertIsDisplayed（Compose 的语义是「在窗口内且非零尺寸」）。
    setScreen(
      WorkbenchData(
        connection = onlinePc,
        running = RunningTask(
          "t1", "把销售明细做成 HTML 复盘，标出异常订单", "分析数据", 3, 5, 0
        ),
        approvals = PendingApprovals(2, 0),
        today = TodayStats(18, 412_000, 6, 1, 0, 4),
        artifacts = listOf(
          Artifact("Q3-sales-review.html", "HTML", 25_395, 0),
          Artifact("渠道复盘-终稿.docx", "DOCX", 186_368, 0),
          Artifact("工程包-含素材.zip", "ZIP", 6_710_886, 0),
          Artifact("八月周报-汇总.pdf", "PDF", 1_258_291, 0)
        )
      )
    )
    listOf("运行中", "待我决定", "今日", "最近成品").forEach { label ->
      compose.onNodeWithTag(sectionTagOf(label))
        .assertIsDisplayed()
    }
  }

  // ───────────────── 状态变化必须触发重组 ─────────────────

  /**
   * 这一条盯的是**「什么时候刷新」**，不是「值对不对」。
   *
   * 初版 `ForegroundSyncState.state` 是 `@Volatile var`，所有测试都直接读
   * `current()` 断言值 —— 全绿。但工位页是在 Composition 里读它的：
   * 普通字段写入不会让 Compose 知道该重组，于是服务启动后界面还停在
   * 「电脑上没有任务在跑」，直到用户离开这页再回来。
   *
   * 这类缺陷任何「读值断言」都抓不到。所以这里真起一个 Composable 读它，
   * 改状态后断言界面跟着变 —— 只有 `mutableStateOf` 能过。
   */
  @Test
  fun 前台同步状态变化会驱动界面重组() {
    ForegroundSyncState.resetForTest()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        val running = (ForegroundSyncState.current() as? SyncState.Running)?.let {
          RunningTask(it.taskId, null, null, it.step, it.totalSteps, it.startedAtMillis)
        }
        WorkbenchScreen(
          data = WorkbenchData(connection = onlinePc, running = running, today = TodayStats(0, 0, 0, 0, 0, 0)),
          ticker = false
        )
      }
    }
    // 初始：没有任务
    compose.onNodeWithText("电脑上没有任务在跑").assertIsDisplayed()

    // 服务启动（真实写入路径：ForegroundSyncState.set 由服务调用）
    compose.runOnUiThread {
      ForegroundSyncState.set(
        SyncState.Running(taskId = "t-7", step = 2, totalSteps = 8, startedAtMillis = 0)
      )
    }
    compose.waitForIdle()

    compose.onNodeWithText("第 2 步 / 8").assertIsDisplayed()
    compose.onAllNodesWithText("电脑上没有任务在跑").assertCountEquals(0)

    ForegroundSyncState.resetForTest()
  }

  // ───────────────── 艺术方向的硬规矩要被测试守着 ─────────────────

  /**
   * 报告 §1 把「所有读数用等宽字」列为四条硬规矩之一，但**没有任何断言守着它**。
   *
   * 这条规矩不是审美偏好：百分比、步数、时长每秒都在变，比例字体让数字
   * 左右轻微跳动，读的人会不由自主去盯它。所以它必须是一条能被验证的约束，
   * 否则改一次字体就悄悄失效，而报告还在说「所有读数用等宽字」。
   *
   * 做法：从 `TextLayoutResult` 读**真实排版的字体族**，不是 grep 源码。
   * grep 只能证明「代码里写了 Monospace」，证明不了「这段文字真的用了它」——
   * 比如被一个外层 style 覆盖掉，grep 一样是绿的。
   */
  @Test
  fun 读数与统计数字必须用等宽字体() {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        WorkbenchScreen(
          data = WorkbenchData(
            connection = onlinePc,
            running = RunningTask(
              taskId = "t-1", title = "重构同步层", stage = "扫描依赖",
              step = 3, totalSteps = 5, startedAtMillis = 0
            ),
            today = TodayStats(
              rounds = 12, tokens = 412_000L,
              completed = 9, running = 0, failed = 1, artifacts = 4
            ),
            artifacts = listOf(Artifact(name = "重构方案.md", format = "MD", bytes = 24_800L, atMillis = 0L))
          ),
          ticker = false
        )
      }
    }

    // 抽样覆盖三类读数，全部用页面上**真实独立存在**的节点：
    // 进度百分比（RunningBlock）、步数标签（Reading）、统计格的单值（StatCell）。
    //
    // 两个坑都在这里踩过：
    // ① 不能拿 "12" 抽 —— 它被 roundsSummary 合成了「12 轮 · 412K token」，
    //    页面上没有独立节点，断言会以「没找到」失败，而不是以「字体不对」失败；
    // ② 不能拿 "完成" 抽 —— 那是 StatCell 的标签，只有数值那行是等宽，
    //    标签是正文体。规矩说的是「读数」用等宽，不是「读数旁边每个字」都用。
    val readings = listOf("60%", "第 3 步 / 5", "9")
    readings.forEach { label ->
      val families = compose.onAllNodesWithText(label)
        .fetchSemanticsNodes()
        .mapNotNull { node ->
          val handler = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?: return@mapNotNull null
          val results = mutableListOf<TextLayoutResult>()
          handler.action?.invoke(results)
          results.firstOrNull()?.layoutInput?.style?.fontFamily
        }
      assertTrue("「$label」在页面上没找到，断言无效", families.isNotEmpty())
      families.forEach { family ->
        assertTrue(
          "读数「$label」的字体族是 $family，不是等宽 —— 数字会左右跳动",
          family == FontFamily.Monospace
        )
      }
    }
  }

  // ───────────────── 多列必须按语义分列，不是按顺序轮转 ─────────────────

  /**
   * 报告 §2 写了一条明确的设计决定：
   *
   * > 多列时**不是按顺序轮转**，而是第一列「状态」（运行中 + 今日），
   * > 第二列「待办与结果」（待我决定 + 成品）。按顺序轮转会得到
   * > 「运行中 + 今日」对「待我决定 + 成品」，视线要来回横跳。
   *
   * 但这条决定**没有任何断言守着** —— 之前 19 项全部跑在 `w411dp`
   * （Compact → 单列），`columns > 1` 的分支一次都没执行过。
   * 也就是说：把 `blocks[2]` 改成 `blocks[1]`（退化成顺序轮转），
   * 测试照样全绿，而平板上会变成用户要横跳的布局。
   *
   * 断言方式：在平板视口下按**几何位置**验证分组 ——
   * 同一列的两个区块 x 坐标相同，不同列的 x 坐标必须不同。
   * 只断言「四个区块都在」抓不到这个错误，因为顺序轮转时它们也都在。
   */
  @Test
  fun 多列时按语义分列而不是按顺序轮转() {
    // w900dp 落在 Expanded（>840），columns = 3，走多列分支
    RuntimeEnvironment.setQualifiers("w900dp-h1200dp-420dpi")
    try {
      compose.setContent {
        MorrowTheme(mode = MorrowThemeMode.Ink) {
          // 必须包 AdaptiveScaffold：LocalAdaptive 只由它提供，
          // 直接调 WorkbenchScreen 会拿到 CompositionLocal 的默认值（Compact/1 列），
          // 多列分支永远不会执行 —— 这正是这条测试一直缺失的原因。
          AdaptiveScaffold {
            WorkbenchScreen(data = fullData, ticker = false)
          }
        }
      }
      compose.waitForIdle()

      fun leftOf(label: String): Float {
        val node = compose.onNodeWithTag(sectionTagOf(label)).fetchSemanticsNode()
        return node.boundsInRoot.left
      }

      val running = leftOf("运行中")
      val today = leftOf("今日")
      val approvals = leftOf("待我决定")
      val artifacts = leftOf("最近成品")

      // 第一列：运行中 + 今日；第二列：待我决定 + 成品
      assertEquals("「运行中」与「今日」应在同一列", running, today, 1f)
      assertEquals("「待我决定」与「最近成品」应在同一列", approvals, artifacts, 1f)
      assertTrue(
        "两列必须左右分开；若相同说明退化成了单列，这条断言失效",
        kotlin.math.abs(approvals - running) > 100f
      )
      // 关键：按语义分列时「今日」在左、「待我决定」在右。
      // 若误改成顺序轮转，第二列会变成「今日 + 成品」—— 即 today 与 approvals 同列。
      assertTrue(
        "「今日」不该和「待我决定」同列 —— 那是顺序轮转的错误布局",
        kotlin.math.abs(today - approvals) > 100f
      )
    } finally {
      RuntimeEnvironment.setQualifiers("w411dp-h731dp-420dpi")
    }
  }

  // ───────────────── 空态是文案，不是空白 ─────────────────

  @Test
  fun 未配对时运行中区块给空态文案而非空白() {
    setScreen(WorkbenchData(connection = Connection.unpaired))
    compose.onNodeWithText("还没和电脑配对").assertIsDisplayed()
    compose.onNodeWithText("配对后，这台手机就能跟盯电脑上正在跑的任务", substring = true)
      .assertIsDisplayed()
  }

  @Test
  fun 已连接但空闲时给出另一种空态说法() {
    setScreen(
      WorkbenchData(connection = onlinePc, today = TodayStats(0, 0, 0, 0, 0, 0))
    )
    // 与「未配对」不是同一句话 —— 空态要回答「为什么空」
    compose.onNodeWithText("电脑上没有任务在跑").assertIsDisplayed()
  }

  @Test
  fun 没有审批时是文案而不是隐藏区块() {
    setScreen(
      WorkbenchData(connection = onlinePc, today = TodayStats(0, 0, 0, 0, 0, 0))
    )
    // 区块仍然在（顺序不可调换），只是内容变成「没有」
    compose.onNodeWithTag(sectionTagOf("待我决定")).assertIsDisplayed()
    compose.onNodeWithText("没有等你决定的事").assertIsDisplayed()
  }

  @Test
  fun 未连接时审批空态不说没有而是说不知道() {
    // 「没有等你决定的事」是一句肯定句。未连接时它会被读成
    // 「桌面那边确实没有事等我」，而真实情况是手机读不到队列。
    // 与「今日统计不能显示四个 0」同一条原则：0 和「不知道」含义不同。
    setScreen(
      WorkbenchData(
        connection = Connection(phase = ConnectionPhase.Unpaired),
        today = null,
      )
    )
    compose.onNodeWithText("还不知道有没有事等你决定").assertIsDisplayed()
    // 反向：不能同时出现肯定句
    compose.onAllNodesWithText("没有等你决定的事").assertCountEquals(0)
  }

  @Test
  fun 拿不到今日统计时不用四个零占位() {
    setScreen(WorkbenchData(connection = onlinePc, today = null))
    compose.onNodeWithText("今日统计要等连上电脑才有").assertIsDisplayed()
    // 关键：0 会被读成「今天什么都没干」，所以不能出现单独的 0 读数
    compose.onAllNodesWithText("0").assertCountEquals(0)
  }

  @Test
  fun 今日为空时说明怎么开始() {
    setScreen(
      WorkbenchData(connection = onlinePc, today = TodayStats(0, 0, 0, 0, 0, 0))
    )
    compose.onNodeWithText("今天还没有跑过任务。从对话页发一条指令就会出现在这里。")
      .assertIsDisplayed()
  }

  @Test
  fun 没有成品时说明成品从哪来() {
    setScreen(
      WorkbenchData(connection = onlinePc, today = TodayStats(0, 0, 0, 0, 0, 0))
    )
    compose.onNodeWithText("还没有成品。任务跑完、文件落盘后，这里会按时间列出最近 4 件。")
      .assertIsDisplayed()
  }

  // ───────────────── 底部状态条 ─────────────────

  @Test
  fun 有待补拉时状态条明说补拉中() {
    setScreen(
      WorkbenchData(
        connection = onlinePc,
        today = TodayStats(0, 0, 0, 0, 0, 0),
        pendingPull = true
      )
    )
    compose.onNodeWithText("补拉中").assertIsDisplayed()
  }

  @Test
  fun 没有同步过时说尚未同步() {
    setScreen(
      WorkbenchData(connection = onlinePc, today = TodayStats(0, 0, 0, 0, 0, 0))
    )
    compose.onNodeWithText("尚未同步").assertIsDisplayed()
  }

  // ───────────────── 反例：证明断言真的在盯内容 ─────────────────

  /**
   * 这一条不做「通过」断言，而是证明上面对文案的断言是有鉴别力的：
   * 把 running 从数据里去掉后，`第 3 步 / 5` 必须消失。
   *
   * 它挡的是「页面上有一堆字，但读数来自硬编码」这类假实现 ——
   * 如果进度是写死的，去掉数据它还在。
   *
   * 注意用 `mutableStateOf` 改数据而不是第二次 `setContent`：
   * ComposeTestRule 只允许设置一次 content，第二次会直接抛
   * 「has already set content」。
   */
  @Test
  fun 去掉任务数据后步数读数必须消失() {
    val data = androidx.compose.runtime.mutableStateOf(
      WorkbenchData(
        connection = onlinePc,
        running = RunningTask("t1", "分析数据", null, 3, 5, 0),
        today = TodayStats(0, 0, 0, 0, 0, 0)
      )
    )
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        WorkbenchScreen(data = data.value, ticker = false)
      }
    }
    compose.onNodeWithText("第 3 步 / 5").assertIsDisplayed()

    // 数据里去掉任务
    compose.runOnUiThread {
      data.value = data.value.copy(running = null)
    }
    compose.waitForIdle()

    compose.onAllNodesWithText("第 3 步 / 5").assertCountEquals(0)
    compose.onNodeWithText("电脑上没有任务在跑").assertIsDisplayed()
  }
}

// ───── WorkbenchStopWiringTest.kt ─────
/**
 * 「停止跟盯」是工位页唯一能按下去的控件 —— 它必须真的有效。
 *
 * 这一组盯的是**按钮到服务的最后一跳**：页面上的 `onStopTask` 是否
 * 接到了 `ForegroundSyncService.stop()`。之前的实现里 `onStopTask` 默认是
 * `{}`（空实现），按钮点下去什么都不发生，而且没有任何报错 ——
 * 一个看起来能点、实际是装饰的控件，比没有这个控件更糟。
 *
 * 断言方式：点按钮后查 Robolectric 记录的已启动 Service Intent，
 * 断言它的 action 是 `ACTION_STOP` 且目标是对的服务。只断言「有 service 被启动」
 * 不够 —— 那会把「启动一个新的跟盯」也判为通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h731dp-420dpi")
class WorkbenchStopWiringTest {

  @get:Rule
  val compose = createComposeRule()

  private lateinit var app: Application

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    NotifyChannels.register(app)
    studio.morrow.android.ForegroundSyncState.resetForTest()
  }

  @Test
  fun 点停止跟盯_真的向服务发出_stop_命令() {
    // 先让服务进入 Running（真实路径：start 命令）
    Robolectric.buildService(
      ForegroundSyncService::class.java,
      Intent(app, ForegroundSyncService::class.java).apply {
        action = ForegroundSyncService.ACTION_START
        putExtra(ForegroundSyncService.EXTRA_TASK_ID, "t-1")
        putExtra(ForegroundSyncService.EXTRA_STEP, 3)
        putExtra(ForegroundSyncService.EXTRA_TOTAL, 10)
      }
    ).create().startCommand(0, 0)
    assertTrue("前置：服务应在跟盯", studio.morrow.android.ForegroundSyncState.isRunning())

    compose.setContent {
      MorrowTheme { MorrowApp() }
    }
    compose.waitForIdle()

    compose.onNodeWithText("停止跟盯").performClick()
    compose.waitForIdle()

    val started = shadowOf(app).nextStartedService
    // 只断言「有 service 被启动」不够：那会把「启动一次新的跟盯」也判为通过。
    // 必须断言 action 是停止，且目标类是对的。
    assertEquals(
      "必须向 ForegroundSyncService 发出停止命令",
      ForegroundSyncService.ACTION_STOP,
      started?.action
    )
    assertEquals(
      ForegroundSyncService::class.java.name,
      started?.component?.className
    )
  }

  @Test
  fun 点停止跟盯之后_服务状态回到_Idle() {
    val controller = Robolectric.buildService(
      ForegroundSyncService::class.java,
      Intent(app, ForegroundSyncService::class.java).apply {
        action = ForegroundSyncService.ACTION_START
        putExtra(ForegroundSyncService.EXTRA_TASK_ID, "t-1")
        putExtra(ForegroundSyncService.EXTRA_STEP, 3)
        putExtra(ForegroundSyncService.EXTRA_TOTAL, 10)
      }
    ).create()
    controller.startCommand(0, 0)
    assertTrue("前置：服务应在跟盯", studio.morrow.android.ForegroundSyncState.isRunning())

    compose.setContent { MorrowTheme { MorrowApp() } }
    compose.waitForIdle()
    compose.onNodeWithText("停止跟盯").performClick()
    compose.waitForIdle()

    // 把界面上真正发出去的那条 Intent 交给同一个服务实例处理 ——
    // 这一跳才是「按钮到底有没有用」的判据。只断言「发了 Intent」不够：
    // action 拼错时 Intent 已发出、服务却不认，界面会永远挂在「第 3 步 / 10」。
    val sent = shadowOf(app).nextStartedService
    controller.get().onStartCommand(sent, 0, 0)

    assertEquals(
      "停止命令必须让服务状态回落",
      "Idle",
      studio.morrow.android.ForegroundSyncState.current().toString()
    )
    assertEquals(
      "停止原因应记为 USER_CANCELLED（不是「完成」）",
      studio.morrow.android.SyncStopReason.USER_CANCELLED,
      studio.morrow.android.ForegroundSyncState.lastStopReason()
    )
  }
}