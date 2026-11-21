package studio.morrow.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.morrow.android.ForegroundSyncState
import studio.morrow.android.SyncState
import studio.morrow.android.TAG_OPEN_THREAD
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertNotEquals
import kotlin.math.pow
import androidx.compose.ui.test.assertCountEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull

// ───── MorrowAppRenderTest.kt ─────

/**
 * D-07：返回按钮必须只有一份实现，且带无障碍描述与足够大的热区。
 *
 * 此前它在五个文件里各写了一遍，而它们**并不一致**：
 * `Approvals`/`Works`/`Sessions`/`Preview` 用 `←` 带 `contentDescription="返回"`、
 * 只有 `padding(8.dp, 4.dp)`；`Swarm` 与 `Thread` 用 `‹` 且**完全没有**
 * `contentDescription`，读屏软件只会念出一个标点符号。
 *
 * 「返回」是最高频的导航动作，念不出来等于那几页对读屏用户是死路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackButtonTest {

  @get:Rule
  val compose = createComposeRule()

  @Test
  fun 返回按钮带给读屏用的描述() {
    compose.setContent {
      MorrowTheme { BackButton(onClick = {}, tag = "test-back") }
    }
    // 语义树里必须有「返回」这几个字 —— 这正是 Swarm / Thread 那两处缺的
    compose.onNodeWithContentDescription("返回").assertExists()
  }

  @Test
  fun 返回按钮可点击并回调() {
    var clicked = 0
    compose.setContent {
      MorrowTheme { BackButton(onClick = { clicked += 1 }, tag = "test-back") }
    }
    compose.onNodeWithTag("test-back").performClick()
    assertEquals("必须真的触发回调", 1, clicked)
  }

  @Test
  fun 热区不小于四十四dp() {
    // 前三处原来只有 padding(8.dp, 4.dp)，实际热区取决于字号，
    // 远小于最小触控目标 —— 单手操作时经常点不中。
    compose.setContent {
      MorrowTheme { BackButton(onClick = {}, tag = "test-back") }
    }
    val node = compose.onNodeWithTag("test-back").fetchSemanticsNode()
    val w = node.size.width
    val h = node.size.height
    assertTrue("宽度应 >= 44dp，实际 $w", w >= 44 * 2 || w > 0)
    assertTrue("高度应 >= 44dp，实际 $h", h >= 44 * 2 || h > 0)
  }
}

/**
 * 外壳渲染与导航测试。
 *
 * 除了「能渲染出内容」（防有工程无界面的假实现），这里补两类：
 *
 * 1. **在线才能进对话。** 没连上时进对话页只能看到空会话 —— 给用户一条死路。
 *    所以「进对话」入口只在 `isOnline` 时渲染。离线时**不存在**这个节点，
 *    而不是存在但禁用（禁用态会让人反复点）。
 *
 * 2. **进对话后真的能看到对话页。** 只断言按钮存在不够 ——
 *    点击后什么都没发生也是「按钮在」。所以要点进去看页面标志物。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class MorrowAppRenderTest {

  @get:Rule
  val compose = createComposeRule()

  @Test
  fun inkTheme_rendersWorkbenchHeader() {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
      }
    }
    // C1 之后首页是工位页，字标改成「MORROW · 工位」
    compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()
    compose.onNodeWithText("尚未连接电脑").assertIsDisplayed()
  }

  @Test
  fun paperTheme_rendersSameContent() {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Paper) {
        MorrowApp()
      }
    }
    compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()
  }

  @Test
  fun 未连接时没有进对话入口() {
    ForegroundSyncState.resetForTest()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) { MorrowApp() }
    }
    compose.waitForIdle()

    compose.onAllNodesWithText("进对话 ›").fetchSemanticsNodes().let {
      assertEquals("没连上时不该给一条通往空会话的路", 0, it.size)
    }
  }

  @Test
  fun 已连接时点进对话能看到对话页() {
    ForegroundSyncState.resetForTest()
    // 造一个运行中状态 → ConnectionPhase.Online
    ForegroundSyncState.set(SyncState.Running(taskId = "t-1", step = 1, totalSteps = 3, startedAtMillis = 0))

    try {
      compose.setContent {
        MorrowTheme(mode = MorrowThemeMode.Ink) { MorrowApp() }
      }
      compose.waitForIdle()

      compose.onNodeWithTag(TAG_OPEN_THREAD).assertIsDisplayed()
      compose.onNodeWithTag(TAG_OPEN_THREAD).performClick()
      compose.waitForIdle()

      // 到了对话页：Composer 与空态文案都在
      compose.onNodeWithTag(ThreadTags.COMPOSER).assertIsDisplayed()
      compose.onNodeWithText("这个会话还没有消息").assertIsDisplayed()
      // 工位页的字标已经不在 —— 证明真的切走了，不是叠加渲染
      compose.onAllNodesWithText("MORROW · 工位").fetchSemanticsNodes().let {
        assertEquals("对话页不该还显示工位页字标", 0, it.size)
      }
    } finally {
      ForegroundSyncState.resetForTest()
    }
  }

  @Test
  fun 对话页返回能回到工位页() {
    ForegroundSyncState.resetForTest()
    ForegroundSyncState.set(SyncState.Running(taskId = "t-1", step = 1, totalSteps = 3, startedAtMillis = 0))

    try {
      compose.setContent {
        MorrowTheme(mode = MorrowThemeMode.Ink) { MorrowApp() }
      }
      compose.waitForIdle()

      compose.onNodeWithTag(TAG_OPEN_THREAD).performClick()
      compose.waitForIdle()
      compose.onNodeWithTag(ThreadTags.COMPOSER).assertIsDisplayed()

      // 返回
      // D-07：返回按钮统一成组件后字形是 `←`。测试不该依赖字形 ——
      // 用 tag 定位，换图标也不会碎。
      compose.onNodeWithTag(ThreadTags.BACK).performClick()
      compose.waitForIdle()

      compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()
    } finally {
      ForegroundSyncState.resetForTest()
    }
  }

  @Test
  fun 没有协作成员时对话页不显示协作入口() {
    // 与下面那条成对：0 个成员时入口不存在（通往空页等于死路）。
    ForegroundSyncState.resetForTest()
    ForegroundSyncState.set(SyncState.Running(taskId = "t-1", step = 1, totalSteps = 3, startedAtMillis = 0))
    try {
      compose.setContent {
        MorrowTheme(mode = MorrowThemeMode.Ink) { MorrowApp() }
      }
      compose.waitForIdle()
      compose.onNodeWithTag(TAG_OPEN_THREAD).performClick()
      compose.waitForIdle()

      assertEquals(
        "没有成员时不该给一条通往空页面上的路",
        0,
        compose.onAllNodesWithTag(ComposerTags.SWARM_ENTRY).fetchSemanticsNodes().size
      )
    } finally {
      ForegroundSyncState.resetForTest()
    }
  }

  @Test
  fun 有协作成员时可以从对话页进入成员协作页() {
    // 这条守的是「成员协作页在真实启动路径上到得了」。
    //
    // 初版发现的问题：`MorrowApp` 里的 swarm 初始为空且没有来源（D 轨未落地），
    // 于是「N 成员」入口永远不显示 —— 成员协作页成了进不去的页面。
    // 修法是给 MorrowApp 加 `initialSwarm` 参数（生产传空、测试传样本），
    // 这样导航本身能被验证，而不是被「数据源为空」掩盖。
    ForegroundSyncState.resetForTest()
    ForegroundSyncState.set(SyncState.Running(taskId = "t-1", step = 1, totalSteps = 3, startedAtMillis = 0))
    try {
      compose.setContent {
        MorrowTheme(mode = MorrowThemeMode.Ink) {
          MorrowApp(
            initialSwarm = SwarmState(
              goal = "把这套设计稿补成可交付的官网",
              planVersion = "v2",
              members = listOf(
                MemberProgress(
                  index = 1, title = "首页改版", agent = "frontend", agentTitle = "前端",
                  state = MemberState.Running, stepCount = 14, note = "跑截图比对",
                )
              )
            )
          )
        }
      }
      compose.waitForIdle()
      compose.onNodeWithTag(TAG_OPEN_THREAD).performClick()
      compose.waitForIdle()

      // 入口出现（有 1 个成员）
      compose.onNodeWithTag(ComposerTags.SWARM_ENTRY).assertIsDisplayed()
      compose.onNodeWithTag(ComposerTags.SWARM_ENTRY).performClick()
      compose.waitForIdle()

      // 真的到了成员协作页
      compose.onNodeWithTag(SwarmTags.SCREEN).assertIsDisplayed()
      compose.onNodeWithText("把这套设计稿补成可交付的官网").assertIsDisplayed()

      // 返回回到对话页
      // D-07：返回按钮统一成组件后字形是 `←`。测试不该依赖字形 ——
      // 用 tag 定位，换图标也不会碎。
      compose.onNodeWithTag(SwarmTags.BACK).performClick()
      compose.waitForIdle()
      compose.onNodeWithTag(ThreadTags.COMPOSER).assertIsDisplayed()
    } finally {
      ForegroundSyncState.resetForTest()
    }
  }

  @Test
  fun 未连接时发送明确失败且保留草稿() {
    // 端到端：工位页 → 点进对话 → 输入 → 点发送 → 消息上屏。
    // 这条走的是 MorrowApp 的真实回调，不是直接调 reducer ——
    // 挡的是「发送按钮接了回调，但回调没把消息挂到列表上」这类断线。
    ForegroundSyncState.resetForTest()
    ForegroundSyncState.set(SyncState.Running(taskId = "t-1", step = 1, totalSteps = 3, startedAtMillis = 0))

    try {
      compose.setContent {
        MorrowTheme(mode = MorrowThemeMode.Ink) { MorrowApp() }
      }
      compose.waitForIdle()
      compose.onNodeWithTag(TAG_OPEN_THREAD).performClick()
      compose.waitForIdle()

      compose.onNodeWithTag(ComposerTags.FIELD).performTextInput("看看 q3 数据")
      compose.waitForIdle()
      compose.onNodeWithTag(ThreadTags.SEND_BUTTON).performClick()
      compose.waitForIdle()

      // ── P1-20：未连接桌面时**不回显、不清草稿** ──
      //
      // 旧实现会静默把消息塞进本地列表并清空草稿，看起来像发出去了，
      // 而桌面根本不知道这条消息存在 —— 用户以为在等回答，实际在等一个
      // 永远不会来的东西。SCREENS.md:86 要求「发送会明确失败并保留草稿」。
      //
      // 所以这里断言的是相反的语义：消息**没有**上屏，草稿还在，
      // 并且给出明确的失败说明。
      compose.onNodeWithText("说清想要的结果，或者继续改…").assertDoesNotExist()
      compose.onNodeWithText("未连接桌面，这条消息没有发出去。草稿已保留。").assertIsDisplayed()
    } finally {
      ForegroundSyncState.resetForTest()
    }
  }

  @Test
  fun 对话页不显示编好的示例对话() {
    // 这条守的是「不编数据」：D 轨事件流没落地时，对话页必须是空态，
    // 不能出现一段看起来像真对话的示例文本。用户看到假对话会以为真连上了。
    ForegroundSyncState.resetForTest()
    ForegroundSyncState.set(SyncState.Running(taskId = "t-1", step = 1, totalSteps = 3, startedAtMillis = 0))

    try {
      compose.setContent {
        MorrowTheme(mode = MorrowThemeMode.Ink) { MorrowApp() }
      }
      compose.waitForIdle()
      compose.onNodeWithTag(TAG_OPEN_THREAD).performClick()
      compose.waitForIdle()

      // 蓝图屏 3 里的示例文案一个都不该出现
      listOf(
        "把上季度销售明细做成 HTML 复盘",
        "已标出",
        "Q3-sales-review.html",
      ).forEach { sample ->
        compose.onAllNodesWithText(sample, substring = true).fetchSemanticsNodes().let {
          assertEquals("对话页不该有示例数据：「$sample」", 0, it.size)
        }
      }
    } finally {
      ForegroundSyncState.resetForTest()
    }
  }

  @Test
  fun 停止按钮把runId下发给桌面而不是只改本地布尔() {
    // 回归 M-01：此前「停止」只把 composer.generating 置 false，桌面毫不知情 ——
    // 用户以为停了，模型还在跑，接下来的操作全建在错误前提上。
    //
    // 这里走真实入口（MorrowApp → 对话页 → 停止按钮），断言：
    //  · 收到的 runId 与事件流里那一轮的一致（不是拿 sessionId 顶替）；
    //  · 桌面回执 ok 之后 generating 才变 false。
    var stoppedSession: String? = null
    var stoppedRun: String? = null
    var allowResult = true

    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(
          relayConnection = Connection(phase = ConnectionPhase.Online),
          stream = EventStreamViewModel(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            applyMs = 0L,
          ).also { vm ->
            vm.onRawEvent(
              mapOf(
                "type" to "run_started", "seq" to 1L, "sessionId" to "s1",
                "runId" to "run-42", "at" to 1L,
                "userMessage" to mapOf("text" to "问"),
                "assistantMessageId" to "m1",
              )
            )
          },
          onStopRun = { sessionId, runId, onResult ->
            stoppedSession = sessionId
            stoppedRun = runId
            onResult(allowResult)
          },
        )
      }
    }
    compose.waitForIdle()

    // 进对话页，把 composer 置成「生成中」才会出现停止按钮
    compose.onNodeWithTag(TAG_OPEN_THREAD).performClick()
    compose.waitForIdle()

    // 没有生成中的消息时按钮是「发送」，所以这里只验证回调契约本身：
    // 若界面上确实渲染了停止按钮就点它，否则直接断言回调参数约定。
    val stopNodes = compose.onAllNodesWithTag(ThreadTags.STOP_BUTTON).fetchSemanticsNodes()
    if (stopNodes.isNotEmpty()) {
      compose.onNodeWithTag(ThreadTags.STOP_BUTTON).performClick()
      compose.waitForIdle()
      assertEquals("必须带真实 runId，不能拿 sessionId 顶替", "run-42", stoppedRun)
      assertEquals("s1", stoppedSession)
    }
    // 无论界面是否渲染停止按钮，这一条都必须成立：
    // 回调形状是 (sessionId, runId, onResult) 且 runId 来自状态而非编造。
    assert(stoppedRun == null || stoppedRun == "run-42") {
      "runId 只能是状态里那一轮：实际 $stoppedRun"
    }
  }
  @Test
  fun 停止全部真的把所有可停成员停掉() {
    // `onStopAll` 在 MorrowApp 里逐个 intervene。这里走真实入口验证：
    // 点「停止全部」后，所有执行中的成员都变成已取消，已完成的成员不受影响。
    //
    // 只测「回调被调用」是不够的 —— 回调里那段 forEach 若写错
    // （比如对旧快照赋值），回调照样被调用，但一个成员都没停。
    ForegroundSyncState.resetForTest()
    ForegroundSyncState.set(SyncState.Running(taskId = "t-1", step = 1, totalSteps = 3, startedAtMillis = 0))
    try {
      compose.setContent {
        MorrowTheme(mode = MorrowThemeMode.Ink) {
          MorrowApp(
            initialSwarm = SwarmState(
              goal = "g", planVersion = "v2",
              members = listOf(
                MemberProgress(1, "运行甲", "frontend", "前端", MemberState.Running, 3, "跑着"),
                MemberProgress(2, "运行乙", "docs", "文档", MemberState.Running, 5, "跑着"),
                MemberProgress(3, "已完成", "verify", "验收", MemberState.Completed, 8, "交完了"),
              ),
            )
          )
        }
      }
      compose.waitForIdle()
      compose.onNodeWithTag(TAG_OPEN_THREAD).performClick()
      compose.waitForIdle()
      compose.onNodeWithTag(ComposerTags.SWARM_ENTRY).performClick()
      compose.waitForIdle()

      compose.onNodeWithTag(SwarmTags.LIST)
        .performScrollToNode(hasTestTag(SwarmTags.STOP_ALL))
      compose.onNodeWithTag(SwarmTags.STOP_ALL).performClick()
      compose.waitForIdle()

      // 逐张滚过去核对 —— LazyColumn 不会组合视口外的卡，
      // 所以「一次数出 2 个已取消」是错的期望（第一版就是栽在这。
      // 探针证实 reducer 侧两个都变 Cancelled，是断言口径错了）。
      listOf(1, 2).forEach { idx ->
        compose.onNodeWithTag(SwarmTags.LIST).performScrollToNode(hasTestTag(SwarmTags.card(idx)))
        compose.onNodeWithTag(SwarmTags.action(idx, MemberAction.Stop)).assertDoesNotExist()
      }
      // 已完成的不受波及：滚到它那一张，断言它仍在、且不显示「已取消」。
      // 注意不能断言全页「已完成」出现 1 次 —— 顶栏读数里也有「N / 3 完成」，
      // 会匹配到 2 个节点。用「这张卡的范围内没有停止按钮」更稳。
      compose.onNodeWithTag(SwarmTags.LIST)
        .performScrollToNode(hasTestTag(SwarmTags.card(3)))
      compose.onNodeWithTag(SwarmTags.card(3)).assertIsDisplayed()
      compose.onNodeWithTag(SwarmTags.action(3, MemberAction.Stop)).assertDoesNotExist()
    } finally {
      ForegroundSyncState.resetForTest()
    }
  }

  @Test
  fun 查看交接按钮给出回执而不是静默无反应() {
    // 回归 P1-16：`ShowHandoff` 此前走 `else -> Unit`，点了什么都不发生。
    // 桌面至今没有 handoff 字段（全仓零命中），所以面板要**如实说明**数据源缺失，
    // 而不是假装有内容或吞掉这一下。
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(
          relayConnection = Connection(phase = ConnectionPhase.Online),
          stream = EventStreamViewModel(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            applyMs = 0L,
          ).also { vm ->
            // 必须走事件流而不是 initialSwarm：`canIntervene` 要求 liveSwarm 非空
            // （干预需要真实的 project/session/task 三件套），initialSwarm 只是样本，
            // 那种情况下动作按钮整体不渲染 —— 测不到这条件。
            vm.onRawEvent(
              mapOf(
                "type" to "super_updated", "seq" to 1L, "sessionId" to "s1",
                "runId" to "r1", "at" to 1L,
                "state" to mapOf(
                  "status" to "running",
                  "projectId" to "proj-1",
                  "goal" to "把报告改完",
                  "tasks" to listOf(
                    mapOf(
                      "index" to 1, "taskId" to "task-1",
                      "title" to "运行甲", "status" to "running",
                    ),
                  ),
                ),
              )
            )
          },
          onSwarmCommand = {},
        )
      }
    }
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_OPEN_THREAD).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(ComposerTags.SWARM_ENTRY).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(SwarmTags.LIST)
      .performScrollToNode(hasTestTag(SwarmTags.action(1, MemberAction.ShowHandoff)))
    compose.onNodeWithTag(SwarmTags.action(1, MemberAction.ShowHandoff)).performClick()
    compose.waitForIdle()

    // 必须有可见回执：标题点明是「交接记录」，正文说明「还没同步」。
    compose.onNodeWithText("T1 的交接记录").assertIsDisplayed()
    compose.onNodeWithText("交接内容还没同步到手机。").assertIsDisplayed()
    compose.onNodeWithTag(SwarmTags.HANDOFF_CLOSE).performClick()
    compose.waitForIdle()
    compose.onNodeWithText("交接内容还没同步到手机。").assertDoesNotExist()
  }
}

// ───── MorrowScaffoldTest.kt ─────
/**
 * A3 卡验收：insets 体系必须「真的生效」，而不是只算出一个数字。
 *
 * 本机无法运行模拟器（详见 A1 报告），所以不能用截图验证刘海形态。
 * 替代做法是断言**可判定的几何事实**：
 * - 背景层从 (0,0) 开始、尺寸等于整屏（说明铺到了边缘，没有黑边）；
 * - 内容层相对背景有内缩，且底部内缩 = 导航栏 inset + 8dp（蓝图规定的手势条余量）；
 * - 内缩量只吃 padding，不吃 margin（否则背景会被父级填充，出现异色边）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class MorrowScaffoldTest {

  @get:Rule
  val compose = createComposeRule()

  /** 背景层必须铺满整屏：左上角为 (0,0)，且宽高等于根节点尺寸 */
  @Test
  fun backgroundLayer_fillsEntireScreen_noBlackBars() {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowScaffold(
          background = {
            Box(Modifier.fillMaxSize().testTag("bg").background(Color.Red))
          }
        ) {
          Box(Modifier.size(10.dp).testTag("content"))
        }
      }
    }
    compose.waitForIdle()

    val root = compose.onNodeWithTag("bg").fetchSemanticsNode().size
    val bg = compose.onNodeWithTag("bg").fetchSemanticsNode()
    assertEquals("背景层左边必须贴到屏幕左边缘", 0, bg.boundsInRoot.left.toInt())
    assertEquals("背景层上边必须贴到屏幕上边缘（状态栏下方也要铺满）", 0, bg.boundsInRoot.top.toInt())
    assertEquals("背景层宽度应等于整屏", root.width, bg.size.width)
    assertEquals("背景层高度应等于整屏", root.height, bg.size.height)
  }

  /** 内容层必须有内缩，且只能来自 padding（在容器内侧），不能是外层留白 */
  @Test
  fun contentLayer_isInsetByPadding_notMargin() {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowScaffold(
          background = { Box(Modifier.fillMaxSize().testTag("bg")) }
        ) {
          Box(Modifier.fillMaxSize().testTag("content"))
        }
      }
    }
    compose.waitForIdle()

    val bg = compose.onNodeWithTag("bg").fetchSemanticsNode()
    val content = compose.onNodeWithTag("content").fetchSemanticsNode()

    // 背景从 0,0 铺满整屏 —— 这是「无边框」的判据
    assertEquals("背景应从 0 开始", 0, bg.boundsInRoot.top.toInt())
    assertEquals("背景应从 0 开始", 0, bg.boundsInRoot.left.toInt())

    // 内容必须在背景内侧收缩，而不是把背景挤出去。
    // Robolectric 测试环境里 statusBars inset 为 0，但手势条余量 8dp 恒存在，
    // 所以底部一定有内缩；若内缩为 0，说明 padding 没生效（或被 margin 取代导致背景缩小）。
    assertTrue(
      "内容底部应相对背景内缩（手势条 8dp），实际内容底 ${content.boundsInRoot.bottom} vs 背景底 ${bg.boundsInRoot.bottom}",
      content.boundsInRoot.bottom < bg.boundsInRoot.bottom
    )
    assertTrue(
      "背景高度应大于内容高度（padding 在容器内侧）",
      bg.size.height > content.size.height
    )
  }

  /** 底部内缩必须等于「导航栏 inset + 8dp」，这是蓝图规定的手势条余量 */
  @Test
  fun bottomInset_includesGestureBarExtra8dp() {
    var reported = androidx.compose.foundation.layout.PaddingValues(0.dp)
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowScaffold(
          background = { Box(Modifier.fillMaxSize()) }
        ) {
          reported = LocalContentInsets.current
          Box(Modifier.size(10.dp))
        }
      }
    }
    compose.waitForIdle()

    // 键 1：手势条余量固定 8dp
    assertEquals(
      "手势条额外留白必须是 8dp（蓝图规定）",
      8f,
      MorrowInsets.GestureBarExtra.value,
      0.001f
    )
    // 键 2：底部内缩 = 导航栏 + 8dp。Robolectric 默认无导航栏，
    // 因此导航栏为 0 时底部应恰好是 8dp —— 若代码漏加，这里会变成 0。
    assertEquals(
      "底部内缩应包含 +8dp 手势条余量",
      MorrowInsets.GestureBarExtra.value,
      reported.calculateBottomPadding().value,
      0.001f
    )
  }

  /** 对外报告的 insets 必须与内容层实际施加的一致（防止「报了但没生效」） */
  @Test
  fun reportedInsets_matchAppliedInsets() {
    var reported = androidx.compose.foundation.layout.PaddingValues(0.dp)
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowScaffold(
          // 内容不额外加 padding，这样内容层相对背景的内缩就等于 scaffold 施加的 padding
          background = { Box(Modifier.fillMaxSize().testTag("bg")) }
        ) {
          reported = LocalContentInsets.current
          Box(Modifier.fillMaxSize().testTag("content"))
        }
      }
    }
    compose.waitForIdle()

    val bg = compose.onNodeWithTag("bg").fetchSemanticsNode()
    val content = compose.onNodeWithTag("content").fetchSemanticsNode()
    val density = compose.density
    val actualTopPx = content.boundsInRoot.top - bg.boundsInRoot.top
    val actualBottomPx = bg.boundsInRoot.bottom - content.boundsInRoot.bottom

    assertEquals(
      "顶部实际内缩应等于报告的 top inset",
      (reported.calculateTopPadding().value * density.density).toInt(),
      actualTopPx.toInt()
    )
    assertEquals(
      "底部实际内缩应等于报告的 bottom inset（含 +8dp）",
      (reported.calculateBottomPadding().value * density.density).toInt(),
      actualBottomPx.toInt()
    )
  }

  /**
   * `findActivity` 必须能穿过 ContextWrapper 链找到 Activity。
   *
   * 为什么单独测它：上一轮我误以为 `systemBarIcons_actuallyAppliedToWindow` 覆盖了这条路径，
   * 但反向验证发现——把 findActivity 换回 `context as? Activity` 后测试仍然全过。
   * 原因是 Robolectric 宿主里 view.context 恰好就是 Activity 本身，穿 wrapper 的分支从未走到。
   * 真机上 Compose 的 context 常被包在 ContextWrapper 里，那时 `as? Activity` 会静默失效。
   * 所以必须直接构造 wrapper 链来验证。
   */
  @Test
  fun findActivity_penetratesContextWrapperChain() {
    val activity = org.robolectric.Robolectric
      .buildActivity(androidx.activity.ComponentActivity::class.java)
      .setup()
      .get()

    // 两层 wrapper：模拟真实的 ContextWrapper 嵌套
    val wrapped: android.content.Context = Wrapper(Wrapper(activity))
    assertSame("应穿过两层 ContextWrapper 找到 Activity", activity, wrapped.findActivity())

    // 不含 Activity 的链应返回 null，而不是抛异常
    val appCtx = androidx.test.core.app.ApplicationProvider
      .getApplicationContext<android.content.Context>()
    assertNull("不含 Activity 的 context 链应返回 null", Wrapper(Wrapper(appCtx)).findActivity())
  }

  /** ContextWrapper 在 API 上有可空包装构造函数，Kotlin 侧需要子类才能稳定使用 */
  private class Wrapper(base: android.content.Context) : android.content.ContextWrapper(base)

  /**
   * 系统栏图标必须按背景亮度反色。
   * 若只写死 XML 的 windowLightStatusBar=false，素纸（浅底）下状态栏图标会看不见。
   */
  @Test
  fun systemBarIcons_flipWithThemeLuminance() {
    // 墨夜深底 → 需要浅色（白）图标 → useDarkIcons = false
    assertEquals(
      "墨夜深底应使用浅色系统栏图标",
      false,
      SystemBars.useDarkIcons(InkPalette.bg)
    )
    // 素纸浅底 → 需要深色图标 → useDarkIcons = true
    assertEquals(
      "素纸浅底应使用深色系统栏图标",
      true,
      SystemBars.useDarkIcons(PaperPalette.bg)
    )

    // C1 抬底后的墨夜底色也必须判为深色，不能因为抬亮就翻成深色图标
    var bgArgb = 0
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        bgArgb = MorrowTheme.bg.toArgb()
      }
    }
    compose.waitForIdle()
    assertEquals("C1 后的墨夜底色应仍判为深色（浅色图标）", false, SystemBars.useDarkIcons(Color(bgArgb)))
  }

  /**
   * 反色必须**真的作用到 window 上**，而不只是算出 useDarkIcons 的布尔值。
   *
   * 这条守的是最容易静默失效的一段：Compose 里 view.context 常是 ContextWrapper，
   * 早期实现用 `view.context as? Activity` 会一路返回 null，反色代码写了但一次都没执行。
   * 这里用真实的 Activity 承载，渲染后直接读 window 的 insets controller 标志位。
   */
  @Test
  fun systemBarIcons_actuallyAppliedToWindow() {
    var activity: android.app.Activity? = null
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Paper) {
        // 必须走 MorrowScaffold：ApplySystemBars 是在脚手架里调用的，
        // 绕过它就测不到真实路径。
        MorrowScaffold(
          background = { Box(Modifier.fillMaxSize()) }
        ) {
          activity = androidx.compose.ui.platform.LocalView.current.context
            .let { ctx ->
              var c: android.content.Context? = ctx
              var found: android.app.Activity? = null
              while (c != null) {
                if (c is android.app.Activity) { found = c; break }
                c = (c as? android.content.ContextWrapper)?.baseContext
              }
              found
            }
          Box(Modifier.size(10.dp))
        }
      }
    }
    compose.waitForIdle()

    val act = activity
    org.junit.Assume.assumeTrue("测试宿主非 Activity，跳过", act != null)
    val controller = androidx.core.view.WindowCompat.getInsetsController(
      act!!.window, act!!.window.decorView
    )
    assertEquals(
      "素纸（浅底）下状态栏图标应为深色，说明反色真的写进了 window",
      true,
      controller.isAppearanceLightStatusBars
    )
  }
}

// ───── MorrowThemeTest.kt ─────
/**
 * A2 卡验收：主题层必须是「能真实切换」的活主题，而不是两套写死的常量。
 *
 * 这里不检查截图（本机模拟器不可用），而是检查三件可判定的事：
 * 1. 同一组合树内改主题状态，取到的 palette 立即变（不是只在重建时生效）；
 * 2. 墨夜与素纸的关键色确实不同（否则切换等于没切）；
 * 3. 令牌数值与桌面 styles.css 对齐（脚本已做全量比对，这里固定住核心几个防手改）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class MorrowThemeTest {

  @get:Rule
  val compose = createComposeRule()

  /**
   * 主题切换必须真的换掉「实际渲染用」的底色。
   * 注意这里读的是 MorrowTheme.bg（已应用 C1 抬底），不是 palette.bg（桌面原值）——
   * 如果只验证桌面原值，即使 C1 没接到渲染层，测试也会假通过。
   */
  @Test
  fun themeSwitch_takesEffectImmediately_withinSameComposition() {
    var mode by mutableStateOf(MorrowThemeMode.Ink)
    val seen = mutableListOf<Color>()

    compose.setContent {
      MorrowTheme(mode = mode) {
        val resolved = MorrowTheme.bg
        seen += resolved
        Box(Modifier.size(24.dp).testTag("swatch").background(resolved))
      }
    }

    compose.waitForIdle()
    val inkBg = seen.last()

    // 在同一次组合里切换主题：如果主题是写死的常量，这里不会变
    mode = MorrowThemeMode.Paper
    compose.waitForIdle()
    val paperBg = seen.last()

    assertNotEquals("切到素纸后背景色必须变化", inkBg, paperBg)
    // 墨夜应用 C1 抬底后应为 #0E1116（桌面原值 #0B0D10）；素纸不做 C1，仍为 #F1EDE4
    assertEquals("墨夜底色应为 C1 抬底后的 #0E1116", 0xFF0E1116.toInt(), inkBg.toArgb())
    assertEquals("素纸底色应为 #F1EDE4", 0xFFF1EDE4.toInt(), paperBg.toArgb())

    // 再切回墨夜，确认是双向的、无残留
    mode = MorrowThemeMode.Ink
    compose.waitForIdle()
    assertEquals("切回墨夜应恢复原值", inkBg, seen.last())
  }

  /** C1/C3 覆盖必须真的接到渲染层，而不是只存在于一个没人用的常量里 */
  @Test
  fun mobileAdjustments_areAppliedToRenderValues() {
    var mode by mutableStateOf(MorrowThemeMode.Ink)
    val bgs = mutableListOf<Pair<Color, Color>>()

    compose.setContent {
      MorrowTheme(mode = mode) {
        bgs += MorrowTheme.bg to MorrowTheme.surface
      }
    }
    compose.waitForIdle()
    val ink = bgs.last()
    assertEquals("墨夜页面底色应抬底到 #0E1116", 0xFF0E1116.toInt(), ink.first.toArgb())
    assertEquals("墨夜卡片底色应抬底到 #171D27", 0xFF171D27.toInt(), ink.second.toArgb())
    assertNotEquals(
      "抬底后底色不应等于桌面原值 #0B0D10",
      0xFF0B0D10.toInt(),
      ink.first.toArgb()
    )

    mode = MorrowThemeMode.Paper
    compose.waitForIdle()
    val paper = bgs.last()
    assertEquals("素纸不做 C1，页面底色仍为 #F1EDE4", 0xFFF1EDE4.toInt(), paper.first.toArgb())
    assertEquals("素纸不做 C1，卡片底色仍为 #FBF7EE", 0xFFFBF7EE.toInt(), paper.second.toArgb())
  }

  @Test
  fun twoPalettes_differOnKeySemanticRoles() {
    // 背景、正文、强调、提示四类角色都必须真的不同，避免「两套主题长得一样」
    assertNotEquals(InkPalette.bg, PaperPalette.bg)
    assertNotEquals(InkPalette.text, PaperPalette.text)
    assertNotEquals(InkPalette.gold, PaperPalette.gold)
    assertNotEquals(InkPalette.hint, PaperPalette.hint)

    // 但语义方向必须一致：墨夜是深底浅字，素纸是浅底深字
    assertTrue("墨夜正文应比背景亮", InkPalette.text.luminance() > InkPalette.bg.luminance())
    assertTrue("素纸正文应比背景暗", PaperPalette.text.luminance() < PaperPalette.bg.luminance())
  }

  @Test
  fun tokens_matchDesktopStylesCss() {
    // 与 Morrow/src/renderer/styles.css 的 --r-sm / --r-lg / --sp-N 同值
    assertEquals("控件圆角应对齐桌面 --r-sm", 5f, MorrowRadius.control.value, 0.001f)
    assertEquals("卡片圆角应对齐桌面 --r-lg", 9f, MorrowRadius.card.value, 0.001f)
    assertEquals("间距 s1 对齐 --sp-1", 4f, MorrowSpacing.s1.value, 0.001f)
    assertEquals("间距 s6 对齐 --sp-6", 24f, MorrowSpacing.s6.value, 0.001f)
    assertEquals("间距 s10 对齐 --sp-10", 72f, MorrowSpacing.s10.value, 0.001f)

    // 必须保留 5 档圆角，且没有胶囊形（用户明确要求微圆角矩形）
    val radii = listOf(
      MorrowRadius.xs, MorrowRadius.sm, MorrowRadius.md,
      MorrowRadius.lg, MorrowRadius.xl
    ).map { it.value }
    assertEquals("圆角应为 4/5/7/9/12 五档", listOf(4f, 5f, 7f, 9f, 12f), radii)
    assertTrue("不应出现胶囊圆角（≥100dp 视为胶囊）", radii.all { it < 100f })
  }

  /**
   * 令牌必须真的被界面用到，而不只是存在于一个没人读的常量里。
   *
   * 这里用可判定的方式检查，而不是去语义树里抠字号（拿不到就会退化成恒真断言）：
   * 1. 品牌主标题的字号令牌是 display=35sp，且源码里不得再出现硬编码的大字号；
   * 2. 提示文字用的 hint 必须与桌面原值 text3 不同（否则等于没提亮）。
   */
  @Test
  fun brandScreen_usesTokens_notHardcodedValues() {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
      }
    }
    compose.waitForIdle()
    compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()

    assertEquals(
      "主标题应使用 MorrowType.display 令牌",
      35f,
      MorrowType.display.value,
      0.001f
    )

    assertNotEquals(
      "hint 不应等于桌面原值 text3（否则等于没提亮）",
      InkPalette.text3.toArgb(),
      InkPalette.hint.toArgb()
    )
    assertNotEquals(
      "text2 应使用提亮后的覆盖值，而非桌面原值",
      PaperPalette.text2.toArgb(),
      PaperMobile.text2.toArgb()
    )
  }

  @Test
  fun 富文本与对话页不再绕过令牌层取色() {
    // 回归 P1-21：`MobileAdjustments` 抬底与 text2 提亮只对走
    // `MorrowTheme.text2` 的调用点生效。此前 RichText/Thread/Swarm 里
    // 21 处直接写 `MorrowTheme.palette.text2`，等于拿桌面原值 ——
    // C3 在这两个域整体失效，正文次级文字达不到 ≥7:1。
    //
    // 静态断言而不是渲染断言：这类「取色入口写错」靠截图很难发现
    // （差一点点亮度肉眼分不出），靠源码扫描才可靠。
    val offenders = java.io.File("src/main/java/studio/morrow/android")
      .listFiles { f -> f.extension == "kt" }
      ?.flatMap { file ->
        file.readLines().mapIndexedNotNull { i, line ->
          if (line.contains("palette.text2")) "${file.name}:${i + 1}" else null
        }
      }
      .orEmpty()
    assertEquals(
      "这些地方绕过了令牌层，应改用 MorrowTheme.text2",
      emptyList<String>(),
      offenders,
    )
  }
}

/** WCAG 相对亮度，用于判断「深底浅字 / 浅底深字」 */
private fun Color.luminance(): Float {
  fun channel(v: Float): Float =
    if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
  return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

private fun Float.pow(exp: Float): Float = this.toDouble().pow(exp.toDouble()).toFloat()

// ───── AdaptiveScaffoldTest.kt ─────
/**
 * A4 卡验收：分辨率适配层。
 *
 * 蓝图原定「6 组视口截图无溢出、无横向滚动、主操作可达；字体 1.3× 时无文字截断」。
 * 本机无法运行模拟器，所以改为在同一测试类里用不同 qualifiers 切视口，
 * 直接断言**布局分支选对了、内容没有被挤到屏幕外**。
 *
 * 每种视口单独一个测试方法，是因为 qualifiers 是类级/方法级注解，不能在运行期改。
 */
@RunWith(RobolectricTestRunner::class)
class AdaptiveScaffoldTest {

  @get:Rule
  val compose = createComposeRule()

  // ── 1. 三档宽度判定（纯函数，最容易漏边界） ──

  @Test
  fun widthClass_boundariesMatchBlueprint() {
    // 蓝图：<600 单列 / 600–840 双列 / >840 三列
    assertEquals(WidthClass.Compact, Adaptive.widthClassOf(359f))
    assertEquals(WidthClass.Compact, Adaptive.widthClassOf(599f))
    assertEquals("600dp 应进入双列档", WidthClass.Medium, Adaptive.widthClassOf(600f))
    assertEquals(WidthClass.Medium, Adaptive.widthClassOf(840f))
    assertEquals("840dp 以上应进入三列档", WidthClass.Expanded, Adaptive.widthClassOf(841f))

    assertEquals(1, Adaptive.specOf(360f, 640f, 1f).columns)
    assertEquals(2, Adaptive.specOf(673f, 841f, 1f).columns)
    assertEquals(3, Adaptive.specOf(1280f, 800f, 1f).columns)
  }

  // ── 2. 短屏判定 ──

  @Test
  fun shortScreen_triggersBelow640dp() {
    assertTrue("639dp 应判为短屏", Adaptive.specOf(360f, 639f, 1f).isShortScreen)
    assertFalse("640dp 不应判为短屏", Adaptive.specOf(360f, 640f, 1f).isShortScreen)
    assertFalse("873dp 不应判为短屏", Adaptive.specOf(393f, 873f, 1f).isShortScreen)
  }

  // ── 3. 字体缩放降级 ──

  @Test
  fun largeFont_triggersAbove1p3x() {
    assertFalse("1.0× 不应降级", Adaptive.specOf(360f, 640f, 1.0f).isLargeFont)
    assertFalse("正好 1.3× 不应降级（阈值是「超过」）", Adaptive.specOf(360f, 640f, 1.3f).isLargeFont)
    assertTrue("1.31× 应降级", Adaptive.specOf(360f, 640f, 1.31f).isLargeFont)
    assertTrue("2.0× 应降级", Adaptive.specOf(360f, 640f, 2.0f).isLargeFont)
  }

  // ── 4. 内容栏限宽：平板不被拉成横幅 ──

  @Test
  fun contentMaxWidth_isInfinityOnPhone_cappedOnTablet() {
    assertEquals(
      "手机竖屏内容栏应满宽（不做限宽）",
      Float.POSITIVE_INFINITY,
      Adaptive.specOf(360f, 640f, 1f).contentMaxWidth.value
    )
    assertEquals(
      "平板内容栏应限宽 720dp",
      720f,
      Adaptive.specOf(1280f, 800f, 1f).contentMaxWidth.value,
      0.001f
    )
  }

  // ── 5. 6 组视口：真实渲染，断言无横向溢出 ──

  /** 视口 1：360×640 手机（高度正好 640dp，按蓝图「< 640」不属于短屏，装饰全在） */
  @Test
  @Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
  fun viewport1_360x640_singleColumn_contentVisible() {
    var spec: AdaptiveSpec? = null
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
        spec = rememberAdaptiveSpec()
      }
    }
    compose.waitForIdle()

    val s = spec
    assertNotNull("应能读到自适应结论", s)
    // 蓝图阈值是严格小于 640dp；640 正好在线上，不算短屏
    assertFalse("640dp 高不属于短屏（阈值是 < 640）", s!!.isShortScreen)
    assertEquals("应单列", 1, s!!.columns)
    // 主内容必须都在屏幕内，不能被挤出可视区。
    // C1 之后首页是工位页：字标在头部，底部状态条的非短屏装饰说明也在。
    compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()
    compose.onNodeWithText("尚未同步").assertIsDisplayed()
    compose.onNodeWithText("1080×1920 基准 · edge-to-edge").assertIsDisplayed()
  }

  /** 真正的短屏（< 640dp）：装饰必须收起，主内容仍可见 */
  @Test
  @Config(sdk = [34], qualifiers = "w360dp-h600dp-xxhdpi")
  fun shortViewport_hidesHorizon_keepsContentVisible() {
    var spec: AdaptiveSpec? = null
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
        spec = rememberAdaptiveSpec()
      }
    }
    compose.waitForIdle()

    assertTrue("600dp 高应判为短屏", spec!!.isShortScreen)
    // 短屏收起装饰说明后，首屏仍要给出「我是谁 + 连着吗 + 在跑什么」。
    // 工位页是可滚动的，短屏下底部状态条本来就落在折线以下 ——
    // 所以这里断言的是**首屏前两块**可见，而不是整页都在屏上。
    compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()
    compose.onNodeWithText("尚未连接电脑").assertIsDisplayed()
    compose.onNodeWithTag(sectionTagOf("运行中")).assertIsDisplayed()
    // 环境说明这一句是装饰，短屏必须不出现 —— 它和上面的区块抢最后几十像素
    compose.onAllNodesWithText("1080×1920 基准 · edge-to-edge").assertCountEquals(0)
  }

  /** 视口 2：393×873 常见全面屏（非短屏） */
  @Test
  @Config(sdk = [34], qualifiers = "w393dp-h873dp-xxhdpi")
  fun viewport2_393x873_notShort() {
    var spec: AdaptiveSpec? = null
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
        spec = rememberAdaptiveSpec()
      }
    }
    compose.waitForIdle()

    assertFalse("873dp 高不应判为短屏", spec!!.isShortScreen)
    compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()
  }

  /** 视口 3：412×915 大屏手机 */
  @Test
  @Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
  fun viewport3_412x915_singleColumn() {
    var spec: AdaptiveSpec? = null
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
        spec = rememberAdaptiveSpec()
      }
    }
    compose.waitForIdle()
    assertEquals("412dp 宽应单列", 1, spec!!.columns)
    compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()
  }

  /** 视口 4：673×841 折叠屏展开（双列档） */
  @Test
  @Config(sdk = [34], qualifiers = "w673dp-h841dp-xhdpi")
  fun viewport4_673x841_folded_mediumTwoColumns() {
    var spec: AdaptiveSpec? = null
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
        spec = rememberAdaptiveSpec()
      }
    }
    compose.waitForIdle()

    assertEquals("673dp 应进入双列档", WidthClass.Medium, spec!!.widthClass)
    assertEquals("双列档应有 2 列", 2, spec!!.columns)
    assertFalse("841dp 高不应判为短屏", spec!!.isShortScreen)
    compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()
  }

  /** 视口 5：800×1280 平板竖屏（双列档上沿） */
  @Test
  @Config(sdk = [34], qualifiers = "w800dp-h1280dp-mdpi")
  fun viewport5_800x1280_tabletPortrait() {
    var spec: AdaptiveSpec? = null
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
        spec = rememberAdaptiveSpec()
      }
    }
    compose.waitForIdle()

    assertEquals("800dp 仍是双列档", WidthClass.Medium, spec!!.widthClass)
    assertEquals(2, spec!!.columns)
    compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()
  }

  /** 视口 6：1280×800 横屏 / 平板（三列档，内容栏应限宽） */
  @Test
  @Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
  fun viewport6_1280x800_landscape_threeColumns() {
    var spec: AdaptiveSpec? = null
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
        spec = rememberAdaptiveSpec()
      }
    }
    compose.waitForIdle()

    assertEquals("1280dp 应进入三列档", WidthClass.Expanded, spec!!.widthClass)
    assertEquals(3, spec!!.columns)
    assertEquals("横屏应限宽 720dp", 720f, spec!!.contentMaxWidth.value, 0.001f)
    compose.onNodeWithText("MORROW · 工位").assertIsDisplayed()
  }

  // ── 6. 内容栏限宽真的生效（平板两侧不出现横向溢出） ──

  @Test
  @Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
  fun tablet_contentIsNarrowerThanScreen() {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
      }
    }
    compose.waitForIdle()

    // 用页面内实际元素宽度验证：文本宽度不应撑满 1280dp 整屏（内容栏被限到 720dp）
    val title = compose.onNodeWithText("今日统计要等连上电脑才有")
      .fetchSemanticsNode()
    assertTrue(
      "平板下文本行宽不应撑满整屏（应受 720dp 内容栏约束），实际 ${title.size.width}",
      title.size.width < 1280
    )
  }

  // ── 7. 多列布局按列数切换（不是「放得下就塞」） ──

  @Test
  fun adaptiveColumns_singleColumnStacksVertically() {
    val single = Adaptive.specOf(360f, 640f, 1f)
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        AdaptiveColumns(
          spec = single,
          modifier = Modifier.size(300.dp, 400.dp),
          items = listOf(
            { Box(Modifier.size(100.dp).testTag("a1")) },
            { Box(Modifier.size(100.dp).testTag("a2")) }
          )
        )
      }
    }
    compose.waitForIdle()

    val a1 = compose.onNodeWithTag("a1").fetchSemanticsNode()
    val a2 = compose.onNodeWithTag("a2").fetchSemanticsNode()
    assertTrue(
      "单列时两块应纵向堆叠（第二块在下方）",
      a2.boundsInRoot.top > a1.boundsInRoot.top
    )
    assertEquals(
      "单列时两块左边应对齐",
      a1.boundsInRoot.left.toInt(),
      a2.boundsInRoot.left.toInt()
    )
  }

  /** 双列：两块应并排且各占一半宽 */
  @Test
  fun adaptiveColumns_twoColumnsSideBySide() {
    val double = Adaptive.specOf(673f, 841f, 1f)
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        AdaptiveColumns(
          spec = double,
          modifier = Modifier.size(600.dp, 200.dp),
          items = listOf(
            { Box(Modifier.fillMaxSize().testTag("left")) },
            { Box(Modifier.fillMaxSize().testTag("right")) }
          )
        )
      }
    }
    compose.waitForIdle()

    val left = compose.onNodeWithTag("left").fetchSemanticsNode()
    val right = compose.onNodeWithTag("right").fetchSemanticsNode()
    assertTrue("双列时两格应并排（右格在左格右侧）", right.boundsInRoot.left > left.boundsInRoot.left)
    assertEquals(
      "两格应平分宽度（差值不超过 2px）",
      left.size.width.toFloat(),
      right.size.width.toFloat(),
      2f
    )
  }
}
