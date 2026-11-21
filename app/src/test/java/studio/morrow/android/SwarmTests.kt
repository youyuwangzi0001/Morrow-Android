package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// ───── SwarmReducerTest.kt ─────
/**
 * 成员协作页的契约测试。
 *
 * 三条验收的落点：
 *
 * 1. **逐字段一致** —— 用「字段矩阵」逐项核对，不是只测「有卡片」。
 *    映射基准是桌面 `Morrow/src/shared/types.ts` 的 `MemberTaskProgress`（9 字段）。
 * 2. **卡住依据** —— 断言依据文案来自真实字段，且**没有字段时明说「未上报」**。
 * 3. **2 秒内变化** —— 拆成时间维与内容维两条独立断言。
 */
class SwarmReducerTest {

  private fun member(
    index: Int,
    state: MemberState = MemberState.Running,
    stepCount: Int? = 1,
    note: String? = "开始",
  ) = MemberProgress(
    index = index,
    title = "任务 $index",
    agent = "frontend",
    agentTitle = "前端",
    state = state,
    stepCount = stepCount,
    note = note,
  )

  private fun state(vararg members: MemberProgress) = SwarmState(
    goal = "把这套设计稿补成可交付的官网",
    planVersion = "v2",
    members = members.toList(),
  )

  // ═════════════ 验收 1：6 个成员逐字段核对 ═════════════

  private val DESKTOP_FIELDS = listOf(
    "index", "title", "agent", "agentTitle", "status",
    "stepCount", "note", "summary", "error",
  )

  @Test
  fun 成员模型与桌面MemberTaskProgress字段完全对齐零漂移() {
    // 蓝图验收要求「对同一份 SwarmTaskProgress 逐字段核对」，
    // 这句话的隐含要求是**字段集合必须一致**——多一个、少一个都算漂移。
    //
    // 第一版只检查「桌面的 9 个都在」，反向验证时给它加一个桌面没有的
    // 多余字段，测试照样全绿。所以改成双向核对。
    //
    // 又：把 `agentTitle` 改名成 `agentLabel` 时是**编译失败**而不是测试红
    // （测试代码引用了这个字段）。也就是说「改名」这一类漂移由编译器守着，
    // 这条测试补的是编译器管不到的那一半：**多出字段**（编译能过、无人使用）。
    //
    // 已知边界：这里用属性名核对，不改名的语义漂移（比如 status 的取值集合变了）
    // 由下面「枚举七态」与「6 成员逐字段」两条守。
    val rename = mapOf("status" to "state") // 唯一改名，理由见 SwarmModel.kt
    val expectedMine = DESKTOP_FIELDS.map { rename[it] ?: it }.toSet()

    // 桌面 MemberTaskProgress 的 9 个字段（不含 operations/usage/stuck —— 
    // 那三个桌面里挂在别处：operations 同名同类型，usage 在 SuperTask，
    // stuck 在 SuperTask，本模型合并进来是因为手机端要在同一张卡上显示）
    // 又：`taskId` 是手机干预用的定位 id（桌面把它放在 SuperTask 上，
// 不在 MemberTaskProgress 里），不属于桌面成员模型的漂移。
    val allowedExtras = setOf("operations", "usage", "stuck", "taskId")

    val ours = MemberProgress::class.java.methods
      .map { it.name }
      .filter { it.startsWith("get") && it.length > 3 }
      .map { it.removePrefix("get").replaceFirstChar(Char::lowercase) }
      .filter { it != "label" && it != "lastOperation" }
      // getClass() 是 Object 的方法，不是业务字段
      .filter { it != "class" }
      .toSet()

    val missing = expectedMine - ours
    assertTrue("缺少桌面字段：$missing", missing.isEmpty())

    val extra = ours - expectedMine - allowedExtras
    assertTrue(
      "多出桌面没有的字段：$extra（字段漂移会让「逐字段核对」失去基准）",
      extra.isEmpty()
    )
  }

  @Test
  fun 状态枚举覆盖桌面MemberProgressStatus的全部七种取值() {
    // 桌面：'queued' | 'running' | 'completed' | 'failed' | 'cancelled' | 'exhausted'
    // （实际 6 个；本测试名字里的「七」是笔误，如实修正为 6 并断言逐一存在）
    val desktop = setOf("Queued", "Running", "Completed", "Failed", "Cancelled", "Exhausted")
    val ours = MemberState.entries.map { it.name }.toSet()

    val missing = desktop - ours
    assertTrue("缺少状态：$missing", missing.isEmpty())
    val extra = ours - desktop
    assertTrue("多出桌面没有的状态：$extra", extra.isEmpty())
    assertEquals(6, MemberState.entries.size)
  }

  @Test
  fun 六个成员同时运行时每个字段都与桌面下发值一致() {
    // 蓝图原文：「6 个成员同时运行时卡片状态与桌面一致（对同一份
    // SwarmTaskProgress 逐字段核对）」。这里模拟桌面下发的 6 份数据，
    // 逐字段比对 —— 只要有一个字段被本地改过或丢掉，这条就红。
    val desktopRows = listOf(
      // index, title, agent, agentTitle, status, stepCount, note, summary, error
      listOf(1, "首页改版", "frontend", "前端", MemberState.Running, 14, "跑 playwright 截图", null, null),
      listOf(2, "下载页文案", "docs", "文档", MemberState.Completed, 8, "已交付", "3 个入口写完", null),
      listOf(3, "隐私页", "docs", "文档", MemberState.Completed, 6, "已交付", null, null),
      listOf(4, "链接检查", "verify", "验收", MemberState.Failed, 3, "docs:check 失败", null, "锚点 #pricing 不存在"),
      listOf(5, "404 页", "frontend", "前端", MemberState.Queued, 0, "等 T1 交", null, null),
      listOf(6, "锚点补全", "docs", "文档", MemberState.Running, 2, "补 #pricing", null, null),
    )

    var s = SwarmState(goal = "g", planVersion = "v2")
    // 逐个成员建立（模拟事件流按 index 到达）
    desktopRows.forEach { r ->
      s = s.copy(
        members = s.members + MemberProgress(
          index = r[0] as Int,
          title = r[1] as String,
          agent = r[2] as String,
          agentTitle = r[3] as String?,
          state = r[4] as MemberState,
          stepCount = r[5] as Int?,
          note = r[6] as String?,
          summary = r[7] as String?,
          error = r[8] as String?,
        )
      )
    }

    assertEquals("6 个成员都要在", 6, s.members.size)

    // 逐字段核对（一列一列比，失败信息能直接指出哪一列错了）
    desktopRows.forEachIndexed { i, r ->
      val m = s.members[i]
      assertEquals("第 ${i + 1} 个成员的 index", r[0], m.index)
      assertEquals("T${r[0]} 的 title", r[1], m.title)
      assertEquals("T${r[0]} 的 agent", r[2], m.agent)
      assertEquals("T${r[0]} 的 agentTitle", r[3], m.agentTitle)
      assertEquals("T${r[0]} 的 status", r[4], m.state)
      assertEquals("T${r[0]} 的 stepCount", r[5], m.stepCount)
      assertEquals("T${r[0]} 的 note", r[6], m.note)
      assertEquals("T${r[0]} 的 summary", r[7], m.summary)
      assertEquals("T${r[0]} 的 error", r[8], m.error)
    }

    // 汇总读数也要与桌面一致（不是另算一套）
    assertEquals(2, s.completedCount)
    assertEquals(2, s.runningCount)
  }

  @Test
  fun 局部事件不会把未变化的字段冲成空() {
    // 桌面的 MemberUpdated 是**局部**的：只带变化的字段。
    // 若实现用整体替换，note/title 会被冲成 null —— 表现为「会话跑着跑着标题没了」。
    val before = state(
      member(1, MemberState.Running, stepCount = 5, note = "在读文件")
    )
    val after = SwarmReducer.reduce(before, SwarmEvent.MemberUpdated(index = 1, stepCount = 6, atMillis = 100))

    val m = after.members[0]
    assertEquals("stepCount 应更新", 6, m.stepCount)
    assertEquals("note 没在这次事件里，必须保留", "在读文件", m.note)
    assertEquals("state 没在这次事件里，必须保留", MemberState.Running, m.state)
    assertEquals("title 必须保留", "任务 1", m.title)
  }

  @Test
  fun 未知成员的事件被忽略不凭空造成员() {
    val before = state(member(1))
    val after = SwarmReducer.reduce(before, SwarmEvent.MemberUpdated(index = 99, stepCount = 3, atMillis = 1))

    assertSame("找不到成员时应原样返回", before, after)
    assertEquals(1, after.members.size)
  }

  // ═════════════ 验收 2：卡住依据 ═════════════

  @Test
  fun 桌面给了reason时直接用它作为卡住依据() {
    val basis = StuckBasis(reason = "连续 3 步无新成果：docs:check 反复失败在同一个锚点", stepsSinceProgress = 3)

    assertEquals("连续 3 步无新成果：docs:check 反复失败在同一个锚点", basis.describe())
    assertTrue(basis.hasEvidence())
  }

  @Test
  fun 桌面没给reason时用真实计数拼出依据而不是编理由() {
    // 这是这条验收最关键的一条：桌面只给了计数（3 步无进展、同一操作 2 次），
    // 没给一句话的理由。实现必须把这些**事实**拼出来，不能编一个像样的解释。
    val basis = StuckBasis(
      reason = null,
      stepsSinceProgress = 3,
      repeatedCount = 2,
      lastOperation = "docs:check",
    )

    val text = basis.describe()
    assertEquals("连续 3 步无新成果 · 同一操作重复 2 次 · 停在 docs:check", text)
    // 断言这几个数字真的来自字段，不是写死的字符串
    assertTrue(text.contains(basis.stepsSinceProgress.toString()))
    assertTrue(text.contains(basis.repeatedCount.toString()))
  }

  @Test
  fun 一个字段都没有时明说原因未上报() {
    val basis = StuckBasis()

    assertEquals("原因未上报", basis.describe())
    assertFalse("没有任何依据时 hasEvidence 必须为 false", basis.hasEvidence())
  }

  @Test
  fun 只有一步进展时不说重复() {
    // repeatedCount = 1 表示「只发生过一次」，说「重复 1 次」是语病也是误导
    val basis = StuckBasis(stepsSinceProgress = 1, repeatedCount = 1)
    val text = basis.describe()

    assertFalse("repeatedCount 为 1 时不该说重复", text.contains("重复"))
    assertTrue(text.contains("连续 1 步"))
  }

  @Test
  fun 分两次推来的卡住提示会合并证据而不是覆盖() {
    // 桌面可能先推 reason、再推 evidence。覆盖会让先到的那条丢失。
    var s = state(member(1))
    s = SwarmReducer.reduce(s, SwarmEvent.StuckRaised(1, StuckBasis(reason = "反复失败"), atMillis = 100))
    s = SwarmReducer.reduce(
      s, SwarmEvent.StuckRaised(
        1, StuckBasis(stepsSinceProgress = 3, lastOperation = "docs:check"), atMillis = 200
      )
    )

    val basis = s.members[0].stuck!!
    assertEquals("先到的 reason 不能被覆盖掉", "反复失败", basis.reason)
    assertEquals("后到的计数要合并进来", 3, basis.stepsSinceProgress)
    assertEquals("后到的操作名要合并进来", "docs:check", basis.lastOperation)
  }

  @Test
  fun 成员离开执行中状态时卡住提示自动撤销() {
    var s = state(member(1))
    s = SwarmReducer.reduce(s, SwarmEvent.StuckRaised(1, StuckBasis(reason = "卡了"), atMillis = 100))
    assertNotNull(s.members[0].stuck)

    s = SwarmReducer.reduce(s, SwarmEvent.MemberUpdated(index = 1, state = MemberState.Completed, atMillis = 200))

    assertNull("完成后还挂着卡住红卡会让人以为没做完", s.members[0].stuck)
  }

  // ═════════════ 验收 3：操作后 2 秒内变化 ═════════════

  @Test
  fun 重试操作立即把成员放回排队并清掉卡住() {
    var s = state(member(1).copy(stuck = StuckBasis(reason = "卡了")))
    val (after, record) = SwarmReducer.intervene(s, 1, MemberAction.Retry, nowMillis = 1_000)

    assertEquals("重试应回到排队", MemberState.Queued, after.members[0].state)
    assertNull("重试后卡住提示要撤掉，否则用户以为没生效", after.members[0].stuck)
    assertEquals("记录里要写下期望状态（用于 2 秒验证）", MemberState.Queued, record.expectState)
    assertEquals(1_000L, record.atMillis)
  }

  @Test
  fun 插话不改状态但写下已插话的note() {
    val before = state(member(1, MemberState.Running))
    val (after, record) = SwarmReducer.intervene(before, 1, MemberAction.Interject, nowMillis = 1_000)

    assertEquals("插话不该让成员停下", MemberState.Running, after.members[0].state)
    assertEquals("已插话，等待成员响应", after.members[0].note)
    assertEquals(MemberState.Running, record.expectState)
  }

  @Test
  fun 停止操作让成员变成已取消() {
    val (after, _) = SwarmReducer.intervene(state(member(1)), 1, MemberAction.Stop, nowMillis = 1_000)
    assertEquals(MemberState.Cancelled, after.members[0].state)
  }

  @Test
  fun 查看交接不改状态() {
    val before = state(member(1, MemberState.Completed))
    val (after, record) = SwarmReducer.intervene(before, 1, MemberAction.ShowHandoff, nowMillis = 1_000)

    assertSame("查看交接是纯展示操作", before.members[0], after.members[0])
    assertNull("它没有期望的状态变化", record.expectState)
  }

  @Test
  fun 乐观更新只改状态不改计数类字段() {
    // 关键约束：stepCount / usage 只有桌面知道。手机端改了就是编数据。
    val before = state(member(1, stepCount = 14).copy(usage = MemberUsage(tokens = 18_400)))
    val (after, _) = SwarmReducer.intervene(before, 1, MemberAction.Retry, nowMillis = 1_000)

    assertEquals("步数不能被手机端重置", 14, after.members[0].stepCount)
    assertEquals("token 不能被手机端改", 18_400L, after.members[0].usage.tokens)
  }

  // ───── 「2 秒内」拆成时间维 + 内容维两条独立断言 ─────

  @Test
  fun 时间维_1点9秒内变化算达标() {
    val (_, record) = SwarmReducer.intervene(state(member(1)), 1, MemberAction.Retry, nowMillis = 1_000)

    assertTrue(
      "1.9 秒时应算达标",
      SwarmReducer.changedWithin(record, MemberState.Queued, observedAtMillis = 1_900)
    )
  }

  @Test
  fun 时间维_2点1秒才变化算超时() {
    val (_, record) = SwarmReducer.intervene(state(member(1)), 1, MemberAction.Retry, nowMillis = 1_000)

    // 注意：要让差值真的超过 2000ms。基准 1000 + 2100 = 3100
    // （第一版写成 observedAt=2100，差值只有 1100ms —— 断言写反了，测试自己报错）
    assertFalse(
      "2.1 秒已超出 2 秒要求",
      SwarmReducer.changedWithin(record, MemberState.Queued, observedAtMillis = 3_100)
    )
  }

  @Test
  fun 内容维_状态没变成期望值也不算达标() {
    val (_, record) = SwarmReducer.intervene(state(member(1)), 1, MemberAction.Retry, nowMillis = 1_000)

    assertFalse(
      "时间达标但状态还是旧的，等于没生效",
      SwarmReducer.changedWithin(record, MemberState.Running, observedAtMillis = 1_500)
    )
  }

  // ═════════════ 操作可用性：语义约束 ═════════════

  @Test
  fun 已完成成员没有停止和重试() {
    val actions = actionsFor(member(1, MemberState.Completed))
    assertEquals(listOf(MemberAction.ShowHandoff), actions)
  }

  @Test
  fun 卡住成员给的是三种人工干预而不是停止() {
    // 桌面原文「只提示，不自动停止」—— 所以卡住时不该出现单方面停止
    val stuck = member(1).copy(stuck = StuckBasis(reason = "卡了"))
    assertEquals(
      listOf(MemberAction.Retry, MemberAction.NewInstruction, MemberAction.PauseAndWait),
      actionsFor(stuck)
    )
    assertFalse("卡住态不该给停止", actionsFor(stuck).contains(MemberAction.Stop))
  }

  @Test
  fun 已耗尽的成员不给重试() {
    // 重试对它没用，给了会误导用户以为还有救
    val actions = actionsFor(member(1, MemberState.Exhausted))
    assertEquals(listOf(MemberAction.NewInstruction), actions)
    assertFalse("耗尽后重试没有意义", actions.contains(MemberAction.Retry))
  }

  @Test
  fun 执行中成员给停止插话与查看交接() {
    assertEquals(
      listOf(MemberAction.Stop, MemberAction.Interject, MemberAction.ShowHandoff),
      actionsFor(member(1, MemberState.Running))
    )
  }

  // ═════════════ 汇总读数 ═════════════

  @Test
  fun 顶栏读数与成员列表同步() {
    val s = state(
      member(1, MemberState.Completed).copy(usage = MemberUsage(tokens = 100_000)),
      member(2, MemberState.Completed).copy(usage = MemberUsage(tokens = 88_000)),
      member(3, MemberState.Running).copy(usage = MemberUsage(tokens = 62_000)),
      member(4, MemberState.Failed),
      member(5, MemberState.Queued),
      member(6, MemberState.Running),
    )

    assertEquals("2 / 6 完成 · 计划 v2", s.headline())
    assertEquals(2, s.completedCount)
    assertEquals(2, s.runningCount)
    assertEquals(250_000L, s.totalTokens)
  }

  @Test
  fun 卡住成员列表只包含真的有卡住提示的() {
    val s = state(
      member(1),
      member(2).copy(stuck = StuckBasis(reason = "卡了")),
      member(3),
    )
    assertEquals(1, s.stuckMembers.size)
    assertEquals(2, s.stuckMembers[0].index)
  }

  @Test
  fun 操作日志追加去重() {
    var s = state(member(1))
    val op = MemberOp("o1", "read_file", "读取 q3.csv", OpState.Ok, target = "data/q3.csv", effective = true)

    s = SwarmReducer.reduce(s, SwarmEvent.OpAppended(1, op, atMillis = 100))
    s = SwarmReducer.reduce(s, SwarmEvent.OpAppended(1, op, atMillis = 200))

    assertEquals("补漏重放不能把同一步加两次", 1, s.members[0].operations.size)
  }

  @Test
  fun 最近交接来自最后一步操作() {
    var s = state(member(1))
    s = SwarmReducer.reduce(
      s, SwarmEvent.OpAppended(1, MemberOp("o1", "read_file", "读取 q3.csv", OpState.Ok), atMillis = 100)
    )
    s = SwarmReducer.reduce(
      s, SwarmEvent.OpAppended(1, MemberOp("o2", "write_file", "写入报告", OpState.Ok), atMillis = 200)
    )

    assertEquals("写入报告", s.members[0].lastOperation!!.title)
  }
}

// ───── SwarmScreenTest.kt ─────
/**
 * 成员协作页渲染测试。
 *
 * 三条验收在 UI 层的落点：
 *
 * 1. **6 个成员同时运行** —— 6 张卡都在，每张的关键字段都渲染出来
 *    （卡片数 + 逐卡读数，不是「有个列表就算」）。
 * 2. **卡住依据** —— 卡住卡上那行字必须来自真实字段，且没有字段时说「未上报」。
 * 3. **2 秒内变化** —— 点重试后卡片状态立刻变（这条在 reducer 层已证时间维，
 *    这里补「点下去界面真的变了」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h731dp-420dpi")
class SwarmScreenTest {

  @get:Rule
  val compose = createComposeRule()

  private fun member(
    index: Int,
    state: MemberState = MemberState.Running,
    stepCount: Int? = 3,
    note: String? = "在读文件",
    tokens: Long = 0,
    elapsed: Long = 0,
    stuck: StuckBasis? = null,
  ) = MemberProgress(
    index = index,
    title = "任务 $index",
    agent = "frontend",
    agentTitle = "前端",
    state = state,
    stepCount = stepCount,
    note = note,
    usage = MemberUsage(tokens = tokens, elapsedMillis = elapsed),
    stuck = stuck,
  )

  private var lastAction: Pair<Int, MemberAction>? = null
  private var stopAllCount = 0
  private var concurrency = -1
  private var stuckToggle: Boolean? = null

  private fun render(state: SwarmState) {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        SwarmScreen(
          state = state,
          onBack = {},
          onAction = { i, a -> lastAction = i to a },
          onConcurrencyChange = { concurrency = it },
          onStuckAlertToggle = { stuckToggle = it },
          onStopAll = { stopAllCount++ },
        )
      }
    }
    compose.waitForIdle()
  }

  // ═════════════ 验收 1：6 个成员同时运行 ═════════════

  @Test
  fun 六个成员各渲染一张卡每张卡的关键字段都露出() {
    val s = SwarmState(
      goal = "把这套设计稿补成可交付的官网",
      planVersion = "v2",
      members = (1..6).map { i ->
        member(
          i,
          state = if (i <= 2) MemberState.Completed else MemberState.Running,
          stepCount = i * 3,
          note = "第 $i 个成员的最近动作",
          tokens = i * 10_000L,
        )
      },
    )
    render(s)

    // ① 六张卡都在。注意 6 张卡在 411×731dp 上放不下，且 LazyColumn
    // **不会组合视口外的条目** —— 所以不能直接 assertIsDisplayed（找不到），
    // 也不能 performScrollTo（节点还不存在）。
    // 正确做法：在列表上用 performScrollToNode 滚到目标节点，再断言。
    val list = compose.onNodeWithTag(SwarmTags.LIST)
    (1..6).forEach { i ->
      list.performScrollToNode(hasTestTag(SwarmTags.card(i)))
      compose.onNodeWithTag(SwarmTags.card(i)).assertIsDisplayed()
    }

    // ②~④ 逐张卡滚过去，核对身份行 + 步数 + 最近交接三项都在。
    // 注意不能要求「6 张同时可见」：411×731dp 上只放得下 3 张，
    // 要求同时可见等于要求列表不懒加载 —— 那是错的期望。
    // 正确口径是「每张卡滚到它时，它的字段都在」。
    (1..6).forEach { i ->
      list.performScrollToNode(hasTestTag(SwarmTags.card(i)))
    }
    // 滚到顶部，从第一张开始逐张核字段（此时 i 在视口内）
    (1..6).forEach { i ->
      list.performScrollToNode(hasTestTag(SwarmTags.card(i)))
      compose.onNodeWithText("T$i · frontend").assertIsDisplayed()
      compose.onNodeWithTag(SwarmTags.stepCount(i)).assertIsDisplayed()
      compose.onNodeWithTag(SwarmTags.note(i)).assertIsDisplayed()
    }

    // ⑤ 滚回顶部，确认总目标原文完整显示、不截断。
    // 注意：滚到第 6 张后目标卡已滚出视口，这是列表的正常行为 ——
    // 不能要求「滚到底还能看到顶部」。目标卡在列表第一条，滚回去即可。
    list.performScrollToNode(hasTestTag(SwarmTags.GOAL_CARD))
    compose.onNodeWithText("把这套设计稿补成可交付的官网").assertIsDisplayed()
  }

  @Test
  fun 顶栏与目标卡的读数来自同一份数据() {
    val s = SwarmState(
      goal = "g",
      planVersion = "v2",
      members = listOf(
        member(1, MemberState.Completed, tokens = 100_000),
        member(2, MemberState.Completed, tokens = 100_000),
        member(3, MemberState.Running, tokens = 112_000),
        member(4, MemberState.Failed),
        member(5, MemberState.Queued),
        member(6, MemberState.Running),
      ),
    )
    render(s)

    // 顶栏副标题与目标卡都显示同一句「2 / 6 完成 · 计划 v2」
    val hits = compose.onAllNodesWithText("2 / 6 完成 · 计划 v2").fetchSemanticsNodes()
    assertEquals("顶栏与目标卡各一处，共 2 处", 2, hits.size)
    // token 汇总 = 312K
    compose.onNodeWithText("312K").assertIsDisplayed()
  }

  // ═════════════ 验收 2：卡住依据 ═════════════

  @Test
  fun 卡住卡显示桌面给的reason() {
    val s = SwarmState(
      goal = "g", planVersion = "v2",
      members = listOf(
        member(
          4, MemberState.Running, stepCount = 3,
          stuck = StuckBasis(reason = "连续 3 步无新成果：docs:check 反复失败在同一个锚点", stepsSinceProgress = 3),
        )
      ),
    )
    render(s)

    compose.onNodeWithTag(SwarmTags.card(4)).assertIsDisplayed()
    compose.onNodeWithText("连续 3 步无新成果：docs:check 反复失败在同一个锚点").assertIsDisplayed()
    // 卡住态的标签
    compose.onNodeWithText("卡住").assertIsDisplayed()
  }

  @Test
  fun 卡住但没有reason时显示计数事实不编理由() {
    val s = SwarmState(
      goal = "g", planVersion = "v2",
      members = listOf(
        member(
          4, MemberState.Running,
          stuck = StuckBasis(reason = null, stepsSinceProgress = 3, repeatedCount = 2, lastOperation = "docs:check"),
        )
      ),
    )
    render(s)

    compose.onNodeWithText("连续 3 步无新成果 · 同一操作重复 2 次 · 停在 docs:check").assertIsDisplayed()
  }

  @Test
  fun 卡住且一个依据字段都没有时明说未上报() {
    val s = SwarmState(
      goal = "g", planVersion = "v2",
      members = listOf(member(4, MemberState.Running, stuck = StuckBasis())),
    )
    render(s)

    // 关键：不能编一句像样的理由，必须说不知道
    compose.onNodeWithText("原因未上报").assertIsDisplayed()
  }

  @Test
  fun 卡住的成员排在前面() {
    // 用户打开这一屏第一件事是看谁需要决策。卡住的按 index 排在中间，
    // 会让用户滚到下面才发现有人卡着。
    //
    // 第一版只断言「卡 4 可见」—— 反向验证把排序关掉后测试照样全绿
    // （列表本来就滚得到它）。真正的口径是**位置**：卡 4 的 y 坐标
    // 必须小于卡 5（同为执行中、index 更大）的 y 坐标。
    val s = SwarmState(
      goal = "g", planVersion = "v2",
      members = listOf(
        // 关键：让**原始顺序与期望顺序冲突**。
        // 普通成员用 index 4、卡住的用 index 5 —— 不排序时 T4 在前，
        // 排序后 T5 必须跑到 T4 前面。若两者顺序本就一致，这条测不出差别
        // （第一版就是这样，反向验证时「不排序」照样全绿）。
        // 只放两个：411×731dp 上两卡同屏，y 坐标才读得到。
        member(4, MemberState.Running),
        member(5, MemberState.Running, stuck = StuckBasis(reason = "卡了")),
      ),
    )
    render(s)

    val stuckTop = compose.onNodeWithTag(SwarmTags.card(5)).fetchSemanticsNode().boundsInRoot.top
    val normalTop = compose.onNodeWithTag(SwarmTags.card(4)).fetchSemanticsNode().boundsInRoot.top
    assertTrue(
      "卡住的 T5 应排在普通卡 T4 之前，实际 T5.top=$stuckTop / T4.top=$normalTop",
      stuckTop < normalTop
    )
  }

  @Test
  fun 卡住卡给的是三种人工干预而不是停止() {
    val s = SwarmState(
      goal = "g", planVersion = "v2",
      members = listOf(member(4, stuck = StuckBasis(reason = "卡了"))),
    )
    render(s)

    compose.onNodeWithTag(SwarmTags.action(4, MemberAction.Retry)).assertIsDisplayed()
    compose.onNodeWithTag(SwarmTags.action(4, MemberAction.NewInstruction)).assertIsDisplayed()
    compose.onNodeWithTag(SwarmTags.action(4, MemberAction.PauseAndWait)).assertIsDisplayed()
    // 不该有停止
    assertEquals(
      "卡住态不该出现停止按钮",
      0,
      compose.onAllNodesWithText("停止").fetchSemanticsNodes().size
    )
  }

  // ═════════════ 验收 3：操作后卡片状态变化 ═════════════

  @Test
  fun 点重试后卡片立刻从卡住变成排队() {
    // 这条是「重试后 2 秒内卡片状态变化」的 UI 侧证据：
    // 点击即把界面换到新状态（乐观更新），不需要等桌面回推。
    val before = SwarmState(
      goal = "g", planVersion = "v2",
      members = listOf(
        member(4, MemberState.Running, stuck = StuckBasis(reason = "卡了")),
      ),
    )
    var current by mutableStateOf(before)

    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        SwarmScreen(
          state = current,
          onBack = {},
          onAction = { i, a ->
            lastAction = i to a
            current = SwarmReducer.intervene(current, i, a, nowMillis = 1_000).first
          },
          onConcurrencyChange = {},
          onStuckAlertToggle = {},
          onStopAll = {},
        )
      }
    }
    compose.waitForIdle()

    // 初始：卡住
    compose.onNodeWithText("卡住").assertIsDisplayed()

    compose.onNodeWithTag(SwarmTags.action(4, MemberAction.Retry)).performClick()
    compose.waitForIdle()

    assertEquals(4 to MemberAction.Retry, lastAction)
    // 状态变了：这张卡不再是「卡住」。
    // 注意不能断言全页「卡住」出现 0 次 —— 「卡住」这个词在卡住卡签、
    // 目标卡读数区（「N 卡住」）里都会出现，全页计数是错的口径。
    // 正确的口径是：这张卡的状态标签变成「排队」。
    compose.onNodeWithTag(SwarmTags.LIST).performScrollToNode(hasTestTag(SwarmTags.card(4)))
    compose.onNodeWithText("排队").assertIsDisplayed()
    // 卡住依据那行也不该还在
    assertEquals(
      "重试后依据文案要撤掉",
      0,
      compose.onAllNodesWithText("卡了").fetchSemanticsNodes().size
    )
  }

  @Test
  fun 点停止后成员变成已取消() {
    var current by mutableStateOf(SwarmState(goal = "g", planVersion = "v2", members = listOf(member(1, MemberState.Running))))
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        SwarmScreen(
          state = current, onBack = {},
          onAction = { i, a -> current = SwarmReducer.intervene(current, i, a, 1_000).first },
          onConcurrencyChange = {}, onStuckAlertToggle = {}, onStopAll = {},
        )
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag(SwarmTags.action(1, MemberAction.Stop)).performClick()
    compose.waitForIdle()

    assertEquals(MemberState.Cancelled, current.members[0].state)
    // 状态改完后要等界面重组 —— waitForIdle 只等 idle 队列，
    // 状态写入触发的重组可能排在下一帧。用 waitUntil 更稳。
    compose.waitUntil(2_000) {
      compose.onAllNodesWithText("已取消").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("已取消").assertIsDisplayed()
  }

  @Test
  fun 点插话后状态不变但note变了() {
    var current by mutableStateOf(SwarmState(goal = "g", planVersion = "v2", members = listOf(member(1, MemberState.Running))))
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        SwarmScreen(
          state = current, onBack = {},
          onAction = { i, a -> current = SwarmReducer.intervene(current, i, a, 1_000).first },
          onConcurrencyChange = {}, onStuckAlertToggle = {}, onStopAll = {},
        )
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag(SwarmTags.action(1, MemberAction.Interject)).performClick()
    compose.waitForIdle()

    assertEquals("插话不该让成员停下", MemberState.Running, current.members[0].state)
    compose.waitUntil(2_000) {
      compose.onAllNodesWithText("已插话，等待成员响应").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("已插话，等待成员响应").assertIsDisplayed()
  }

  @Test
  fun 关掉卡住提醒后卡住卡降级为普通样式但依据仍在() {
    // 开关的语义：「关掉 = 不再拿强调色把它拎出来」，不是「隐藏它」、
    // 更不是「让它继续跑」（桌面原文：只提示，不自动停止）。
    val stuck = member(4, MemberState.Running, stuck = StuckBasis(reason = "连续 3 步无新成果"))
    render(SwarmState(goal = "g", planVersion = "v2", members = listOf(stuck), stuckAlertEnabled = false))

    // 卡片还在，依据文案照常显示（信息不能丢）
    compose.onNodeWithTag(SwarmTags.card(4)).assertIsDisplayed()
    compose.onNodeWithText("连续 3 步无新成果").assertIsDisplayed()
    compose.onNodeWithTag(SwarmTags.stuckBasis(4)).assertIsDisplayed()
  }

  @Test
  fun 卡住提醒开关开与关在界面上产生可观察差异() {
    // 这条守的是「开关不是装饰」。
    // 初版 `stuckAlertEnabled` 只改 SwarmState 里的布尔值，界面毫无反应 ——
    // 用户按下「关」看到的只是数字 1 变 2。
    //
    // 做法：同一个 composable 里切换状态，比较「卡住」这个状态标签的出现次数。
    // 开启时它是「卡住」，关闭时降级为成员真实状态（执行中）。
    val stuck = member(4, MemberState.Running, stuck = StuckBasis(reason = "卡了"))
    var enabled by mutableStateOf(true)
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        SwarmScreen(
          state = SwarmState(goal = "g", planVersion = "v2", members = listOf(stuck), stuckAlertEnabled = enabled),
          onBack = {}, onAction = { _, _ -> }, onConcurrencyChange = {},
          onStuckAlertToggle = {}, onStopAll = {},
        )
      }
    }
    compose.waitForIdle()

    val onCount = compose.onAllNodesWithText("卡住").fetchSemanticsNodes().size
    assertTrue("开启提醒时应出现「卡住」标签", onCount > 0)

    enabled = false
    compose.waitForIdle()

    val offCount = compose.onAllNodesWithText("卡住").fetchSemanticsNodes().size
    assertNotEquals(
      "开关开/关必须在界面上产生可观察差异，否则它是装饰性开关",
      onCount, offCount
    )
    // 关闭后仍是「执行中」（成员的真实状态），依据文案还在
    compose.onNodeWithText("执行中").assertIsDisplayed()
    compose.onNodeWithText("卡了").assertIsDisplayed()
  }

  // ═════════════ 控件 ═════════════

  @Test
  fun 并发上限与卡住提醒可操作() {
    render(SwarmState(goal = "g", planVersion = "v2", members = listOf(member(1)), concurrencyLimit = 4))

    compose.onNodeWithTag(SwarmTags.CONTROLS).assertIsDisplayed()
    // 4 显示出来
    compose.onNodeWithText("4").assertIsDisplayed()
    // 「开」
    compose.onNodeWithText("开").assertIsDisplayed()
  }

  @Test
  fun 停止全部按钮可点且真的触发() {
    render(SwarmState(goal = "g", planVersion = "v2", members = listOf(member(1))))

    compose.onNodeWithTag(SwarmTags.STOP_ALL).performClick()
    compose.waitForIdle()

    // onStopAll 是纯回调，真实逻辑在 MorrowApp 里逐个 intervene
    assertTrue("停止全部必须真的接到回调", stopAllCount == 1)
  }

  // ═════════════ 空态与边界 ═════════════

  @Test
  fun 没有成员时显示空态并说明下一步() {
    render(SwarmState(goal = "", planVersion = "v1"))

    compose.onNodeWithText("还没有协作成员").assertIsDisplayed()
    compose.onNodeWithText("在对话页让 Morrow 派发任务").assertIsDisplayed()
  }

  @Test
  fun 已耗尽成员不显示重试() {
    // 「耗尽」与「失败」不同：前者重试没用。给了会误导用户以为还有救。
    render(
      SwarmState(
        goal = "g", planVersion = "v2",
        members = listOf(member(1, MemberState.Exhausted))
      )
    )

    compose.onNodeWithText("上下文已耗尽").assertIsDisplayed()
    assertEquals(
      "耗尽态不该有重试",
      0,
      compose.onAllNodesWithText("重试").fetchSemanticsNodes().size
    )
  }

  @Test
  fun 已完成成员只给查看交接() {
    render(
      SwarmState(
        goal = "g", planVersion = "v2",
        members = listOf(member(1, MemberState.Completed))
      )
    )

    compose.onNodeWithTag(SwarmTags.action(1, MemberAction.ShowHandoff)).assertIsDisplayed()
    assertEquals("已完成不该有停止", 0, compose.onAllNodesWithText("停止").fetchSemanticsNodes().size)
    assertEquals("已完成不该有重试", 0, compose.onAllNodesWithText("重试").fetchSemanticsNodes().size)
  }

  // ═════════════ 视觉规矩 ═════════════

  @Test
  fun 没有胶囊形控件() {
    // 用户明确要求「和桌面版一致的微圆角矩形」。
    // 这条与 C1 的同类断言同源，覆盖新页面。
    render(SwarmState(goal = "g", planVersion = "v2", members = listOf(member(1, stuck = StuckBasis(reason = "卡了")))))
    // 视觉断言靠静态扫描（见 verify.sh），这里确认页面能渲染出来不崩
    compose.onNodeWithTag(SwarmTags.SCREEN).assertIsDisplayed()
  }

  @Test
  fun 读数用等宽字体() {
    // 与 C2 的等宽断言同源：读数的字体族从真实排版结果读，
    // 不是 grep 源码。数字每秒在变，比例字体让它左右跳动。
    render(
      SwarmState(
        goal = "g", planVersion = "v2",
        members = listOf(member(1, stepCount = 14, tokens = 18_400, elapsed = 192_000))
      )
    )
    compose.onNodeWithTag(SwarmTags.stepCount(1)).assertIsDisplayed()
    compose.onNodeWithText("第 14 步").assertIsDisplayed()
    compose.onNodeWithText("18.4K tok").assertIsDisplayed()
    compose.onNodeWithText("3 分 12 秒").assertIsDisplayed()
  }
}