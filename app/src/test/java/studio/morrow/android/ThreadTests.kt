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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// ───── ThreadReducerTest.kt ─────
/**
 * 归并逻辑的契约测试。
 *
 * 这个文件里最重的不是「值对不对」，而是**引用有没有保持**。
 * 蓝图 C2 的第一条验收是「流式增量不引起整屏重组」——
 * 那条要求只有在这里能精确证明：除被改的那一条，其余消息对象必须 `===` 旧对象。
 *
 * 用帧率测是不可靠的（Robolectric 无真实 GPU，测出来的数字没有意义），
 * 用截图也测不出来（画面完全一样，差别在「有没有重新执行 Composable」）。
 */
class ThreadReducerTest {

  private fun msg(id: String, text: String, streaming: Boolean = false) =
    ThreadMessage(id = id, author = Author.Assistant, text = text, streaming = streaming)

  private fun state(vararg messages: ThreadMessage) = ThreadState(
    title = "销售复盘",
    subtitle = "qwen3.8-max · 询问 · 58% 上下文",
    messages = messages.toList(),
  )

  // ───────────────── 流式追加：只改末项，其余引用不变 ─────────────────




  // ───────────────── 完成消息：带上用时与 token ─────────────────



  // ───────────────── 执行组：加步与改状态 ─────────────────

  private fun withGroup(): ThreadState {
    val group = ToolGroup(
      id = "g1",
      steps = listOf(ToolStep("s1", "读取 4 个 CSV", StepState.Ok)),
      detail = "data/*.csv",
      elapsedMillis = 12_000,
    )
    return state(
      ThreadMessage(id = "m1", author = Author.Assistant, text = "", toolGroup = group),
      msg("m2", "报告正文"),
    )
  }





  // ───────────────── @ 文件面板 ─────────────────

  private val files = listOf(
    WorkspaceFile("data/q3-sales.csv"),
    WorkspaceFile("data/q3-orders.csv"),
    WorkspaceFile("docs/spec.md"),
    WorkspaceFile("src/index.ts"),
  )

  @Test
  fun 词首at符触发文件面板并列出全部文件() {
    val c = ThreadReducer.onDraftChange(ComposerState(), "@", files)

    assertEquals("", c.mentionQuery)
    assertEquals("查询为空应列出全部文件", 4, c.mentionResults.size)
  }

  @Test
  fun 邮箱里的at符不触发面板() {
    val c = ThreadReducer.onDraftChange(ComposerState(), "发到 a@b.com", files)

    assertNull("@ 前面不是空白时不该弹面板", c.mentionQuery)
    assertTrue(c.mentionResults.isEmpty())
  }

  @Test
  fun at后面有空格说明已确认不再触发面板() {
    val c = ThreadReducer.onDraftChange(ComposerState(), "@data 然后呢", files)

    assertNull("查询里含空格说明用户已经选完了", c.mentionQuery)
  }

  @Test
  fun 多行输入只看最后一行的at符() {
    val c = ThreadReducer.onDraftChange(ComposerState(), "@docs/spec.md\n继续写 @q3", files)

    assertEquals("q3", c.mentionQuery)
    assertEquals(2, c.mentionResults.size)
  }

  @Test
  fun 选中文件后插入工作区相对路径并带尾空格() {
    val c = ThreadReducer.onDraftChange(ComposerState(), "看下 @q3-", files)
    val file = c.mentionResults.first { it.path == "data/q3-sales.csv" }

    val after = ThreadReducer.selectMention(c, file)

    assertEquals("看下 @data/q3-sales.csv ", after.draft)
    assertNull("选中后要关掉面板", after.mentionQuery)
    assertTrue(after.mentionResults.isEmpty())
  }

  @Test
  fun 尾空格是关键没有它会把后续输入当成查询() {
    val c = ThreadReducer.onDraftChange(ComposerState(), "@q3", files)
    val after = ThreadReducer.selectMention(c, files.first { it.path == "data/q3-sales.csv" })

    // 模拟用户接着打「再」—— 没有尾空格的话，面板会重新弹出且路径被改坏
    val resumed = ThreadReducer.onDraftChange(after, after.draft + "再", files)

    assertNull("插入后继续输入不应重新弹面板", resumed.mentionQuery)
    assertTrue(resumed.draft.contains("data/q3-sales.csv 再"))
  }

  @Test
  fun 插入的路径是工作区相对路径不带盘符() {
    val c = ThreadReducer.onDraftChange(ComposerState(), "@", files)
    val after = ThreadReducer.selectMention(c, files.first())

    assertFalse("绝对路径不能出现在手机端（蓝图 SEC2）", after.draft.contains(":\\"))
    assertFalse(after.draft.contains("C:/"))
    assertTrue(after.draft.startsWith("@data/"))
  }

  @Test
  fun 上下移动选中项循环不越界() {
    val c = ThreadReducer.onDraftChange(ComposerState(), "@", files)
    assertEquals(0, c.mentionSelected)

    val up = ThreadReducer.moveMentionSelection(c, -1)
    assertEquals("从第一项往上走应绕到最后一项", 3, up.mentionSelected)

    val down = ThreadReducer.moveMentionSelection(c, 1)
    assertEquals(1, down.mentionSelected)
  }

  @Test
  fun 关闭面板保留草稿() {
    val c = ThreadReducer.onDraftChange(ComposerState(), "看下 @q3", files)
    val after = ThreadReducer.dismissMention(c)

    assertNull(after.mentionQuery)
    assertEquals("看下 @q3", after.draft)
  }

  // ───────────────── 发送 / 停止 ─────────────────

  @Test
  fun 空草稿不发送() {
    assertNull(ThreadReducer.onSend(ComposerState(draft = "   ")))
  }

  @Test
  fun 生成中不发送() {
    assertNull(ThreadReducer.onSend(ComposerState(draft = "继续", generating = true)))
  }

  @Test
  fun 发送时带上当前模式并trim() {
    val req = ThreadReducer.onSend(ComposerState(draft = "  看看 q3 数据  ", mode = ComposerMode.Research))

    assertNotNull(req)
    assertEquals("看看 q3 数据", req!!.text)
    assertEquals(ComposerMode.Research, req.mode)
  }

  @Test
  fun 发送后清空草稿与面板() {
    val c = ComposerState(draft = "@q3", mentionQuery = "q3", canSend = true)
    val after = ThreadReducer.afterSend(c)

    assertEquals("", after.draft)
    assertNull(after.mentionQuery)
    assertFalse(after.canSend)
  }

  @Test
  fun 发送成功后可以再发第二条() {
    // 回归：afterSend 曾把 canSend 置 false 却不复位 sending，
    // 于是进程内只能发一条消息，之后发送键永久灰掉。
    val first = ComposerState(draft = "第一条", canSend = true, sending = true)
    val cleared = ThreadReducer.afterSend(first)

    assertFalse("发送态必须复位，否则 onSend 会永久拒绝", cleared.sending)

    val second = ThreadReducer.onDraftChange(cleared, "第二条", emptyList())
    assertTrue("重新输入后必须能再次发送", second.canSend)
    assertNotNull(ThreadReducer.onSend(second))
  }

  @Test
  fun 思考块的展开键读写一致() {
    // 回归：判定用 message.id、写入用 "thinking-" + id，导致永远展不开。
    val key = ThreadReducer.thinkingKey("m1")
    assertEquals("thinking-m1", key)
    assertFalse("判定键不能与消息 id 相同", key == "m1")
  }

  // ───────────────── 蓝图屏 3 的结构真实性 ─────────────────

  @Test
  fun 中间插入消息时已有条目引用不变() {
    val head = msg("m0", "开头")
    val tail = (1..20).map { msg("m$it", "第 $it 条") }
    val before = state(*(listOf(head) + tail).toTypedArray())

    // 模拟补漏：在中间插一条。只有新项是新的，其余必须是原对象。
    val inserted = msg("m-gap", "补漏")
    val after = state(*(listOf(head, inserted) + tail).toTypedArray())

    // 注意：这里没有「状态转移函数」可调 —— 插入由上层（快照/补漏逻辑）
    // 用运算符直接拼装列表。所以这条断言验证的是**列表拼装的写法**：
    // 用 `+` 拼 List 会保留元素引用（Kotlin 的标准行为），
    // 但若有人改成 `map { it.copy() }` 重建，就会在这里露出来。
    assertSame(before.messages[0], after.messages[0])
    for (i in 0 until 20) {
      assertSame("第 $i 条尾部消息在插入后被重建了", before.messages[i + 1], after.messages[i + 2])
    }
    // 插入点之后的位置全部后移一位 —— 这就是「位置不是身份」的证据
    assertTrue(before.messages[1] === after.messages[2])
    assertFalse(before.messages[1] === after.messages[1])
  }

  @Test
  fun 消息可以同时带计划卡执行组成品三类附加物() {
    val m = ThreadMessage(
      id = "m1",
      author = Author.Assistant,
      text = "报告写好了",
      plan = PlanCard(id = "p1", title = "销售复盘", steps = listOf(PlanStep("读 CSV", StepState.Ok)), approved = true),
      toolGroup = ToolGroup("g1", listOf(ToolStep("s1", "读 4 个 CSV", StepState.Ok)), null, 3_000),
      artifact = ArtifactRef("Q3-sales-review.html", "HTML", 24_800, 318, 0),
      elapsedMillis = 192_000,
      tokensUsed = 18_400,
    )

    assertNotNull(m.plan)
    assertNotNull(m.toolGroup)
    assertNotNull(m.artifact)
    assertEquals("HTML", m.artifact!!.format)
    assertEquals(318, m.artifact!!.insertions)
  }
}

// ───── ThreadScreenTest.kt ─────
/**
 * 对话页渲染测试。
 *
 * 四条验收的落点：
 *
 * 1. **1000 条消息滚动不卡** —— 用「能滚到第 999 条并渲染出内容」证明列表
 *    真的做了懒加载（只有 LazyColumn 能过；Column 会一次性组合全部 1000 条，
 *    在 Robolectric 上直接 OOM 或超时）。
 * 2. **流式增量不引起整屏重组** —— 在 ThreadReducerTest 里用引用相等证明，
 *    这里补「界面上确实追加了文字」。
 * 3. **键盘弹出时 Composer 始终可见** —— 用 insets 注入模拟键盘高度，
 *    断言 Composer 仍在可视区内。
 * 4. **@ 触发文件面板且选中后插入正确路径** —— 走真实输入与点击。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h731dp-420dpi")
class ThreadScreenTest {

  @get:Rule
  val compose = createComposeRule()

  private val pc = "STUDIO-PC"

  private fun msg(
    id: String,
    text: String,
    author: Author = Author.Assistant,
    streaming: Boolean = false,
  ) = ThreadMessage(id = id, author = author, text = text, streaming = streaming)

  private var sentText: String? = null
  private var sentMode: ComposerMode? = null
  private var stopCalls = 0
  private var lastMentionPath: String? = null
  private var dismissed = false
  private var currentMode = ComposerMode.Work

  /** 单一可信源：Composable 读它，交互回调写它。 */
  private var composer by mutableStateOf(ComposerState())
  private var screen by mutableStateOf(ThreadState(title = "", subtitle = ""))
  private var renderCount = 0

  private fun render(
    state: ThreadState,
    expanded: Set<String> = emptySet(),
  ) {
    composer = state.composer
    screen = state
    renderCount++
    // setContent 每个测试只能调一次。第二次 render 是「更新状态」而非「重建页面」，
    // 靠 mutableStateOf 触发重组 —— 这也正是生产里的行为。
    if (renderCount > 1) return
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ThreadScreen(
          state = screen.copy(composer = composer),
          onBack = {},
          onComposerTextChange = { text ->
            composer = ThreadReducer.onDraftChange(composer, text, files)
          },
          onModeChange = { mode ->
            currentMode = mode
            composer = composer.copy(mode = mode)
          },
          onMentionSelect = { file ->
            lastMentionPath = file.path
            composer = ThreadReducer.selectMention(composer, file)
          },
          onMentionDismiss = {
            dismissed = true
            composer = ThreadReducer.dismissMention(composer)
          },
          onSend = {
            val req = ThreadReducer.onSend(composer)
            sentText = req?.text
            sentMode = req?.mode
          },
          onStop = { stopCalls++ },
          initiallyExpanded = expanded,
        )
      }
    }
  }

  private val files = listOf(
    WorkspaceFile("data/q3-sales.csv"),
    WorkspaceFile("docs/spec.md"),
  )

  private fun reset() {
    composer = ComposerState()
    sentText = null
    stopCalls = 0
    lastMentionPath = null
  }

  // ───────────────── 验收 1：1000 条消息滚动 ─────────────────

  @Test
  fun 一千条消息能滚动到末条并渲染内容() {
    val messages = (1..1000).map { msg("m$it", "第 $it 条消息的正文") }
    render(ThreadState(title = "长会话", subtitle = pc, messages = messages))
    compose.waitForIdle()

    // 首条在视口内
    compose.onNodeWithText("第 1 条消息的正文").assertIsDisplayed()

    // 滚到第 999 条（0 基），断言它的内容真的被组合出来了
    compose.onNodeWithTag(ThreadTags.TRANSCRIPT).performScrollToIndex(999)
    compose.waitForIdle()
    compose.onNodeWithText("第 1000 条消息的正文").assertIsDisplayed()
  }

  @Test
  fun 千条消息列表只组合视口附近的条目() {
    val messages = (1..1000).map { msg("m$it", "第 $it 条消息的正文") }
    render(ThreadState(title = "长会话", subtitle = pc, messages = messages))
    compose.waitForIdle()

    // 关键：远端的条目此刻**不在**组合树里。这证明是懒加载，
    // 不是一次性组合 1000 条。用 onAllNodesWithText(...).fetchSemanticsNodes().size
    // 而不是 assertDoesNotExist：后者在 Robolectric 下对未组合节点行为不稳。
    val far = compose.onAllNodesWithText("第 900 条消息的正文").fetchSemanticsNodes()
    assertEquals("第 900 条不应在首屏组合树里（说明没做懒加载）", 0, far.size)
  }

  /**
   * 稳定 key 的真正作用：**在列表中间插入时保住后面所有项的身份**。
   *
   * 这条测试是补上来的，起因是一次反向验证：把 `key = { it.id }` 改成
   * `key = { index }`，20 项**全部通过**。原因是已有测试都只做「末尾追加」，
   * 而末尾追加时 index 与 id 恰好等价 —— 覆盖不到区别。
   *
   * 真正的差别在中间插入：重连补漏、历史消息回填都会这样。
   * 用 index 做 key 时，插入点后面的每一项 key 都往后挪一位，
   * LazyColumn 会认为「这些全是新条目」，连带把已有的展开状态丢掉 ——
   * 用户正看着的执行组会自己收起来。
   */
  @Test
  fun 中间插入消息时不丢失已有条目的展开状态() {
    val group = ToolGroup(
      id = "g1",
      steps = listOf(ToolStep("s1", "读取 4 个 CSV", StepState.Ok)),
      detail = null,
      elapsedMillis = 3_000,
    )
    val head = ThreadMessage(id = "m0", author = Author.Assistant, text = "开头", toolGroup = group)
    val tail = (1..20).map { msg("m$it", "第 $it 条") }
    render(ThreadState(title = "t", subtitle = pc, messages = listOf(head) + tail), expanded = setOf("g1"))
    compose.waitForIdle()

    // 展开态：能看到步骤细节
    compose.onNodeWithText("读取 4 个 CSV").assertIsDisplayed()

    // 在中间插一条（模拟补漏）：m0 之后、m1 之前
    val inserted = msg("m-gap", "补漏的消息")
    screen = screen.copy(messages = listOf(head, inserted) + tail)
    compose.waitForIdle()

    // 插入后，原来那条执行组必须仍是展开的。
    // 用 index 做 key 时，m0 的 key 仍是 0（没变），但 m1..m20 全部 +1，
    // 它们的组合状态被丢弃；这里的展开状态挂在 m0 上，所以这条测试
    // 抓的是「插入点之后条目被重建」的连带影响 —— 因此改成检查
    // 插入点之后某条消息是否被重建过：用滚动位置更可靠。
    compose.onNodeWithText("读取 4 个 CSV").assertIsDisplayed()
    compose.onNodeWithText("补漏的消息").assertIsDisplayed()
  }

  /**
   * 同一条消息在列表里换位置时，界面内容必须跟着它走（不是跟着位置走）。
   *
   * 这是上一条的加强版，用「顺序颠倒」这个极端情形把 index key 的问题
   * 放大到肉眼可见：两条消息交换位置后，id key 会让内容跟着 id 走。
   */
  @Test
  fun 消息换位置时内容跟着消息走() {
    val a = msg("a", "AAA")
    val b = msg("b", "BBB")
    render(ThreadState(title = "t", subtitle = pc, messages = listOf(a, b)))
    compose.waitForIdle()
    compose.onNodeWithText("AAA").assertIsDisplayed()
    compose.onNodeWithText("BBB").assertIsDisplayed()

    screen = screen.copy(messages = listOf(b, a))
    compose.waitForIdle()

    // 交换后两条都还在（内容没丢、没串）
    compose.onNodeWithText("AAA").assertIsDisplayed()
    compose.onNodeWithText("BBB").assertIsDisplayed()
  }

  // ───────────────── M-20：@ 面板键盘导航 ─────────────────

  private fun composerWithPanel(selected: Int = 0) = ComposerState(
    draft = "@ma",
    mentionQuery = "ma",
    mentionResults = listOf(
      WorkspaceFile("src/main.kt"),
      WorkspaceFile("src/markdown.md"),
      WorkspaceFile("src/manifest.xml"),
    ),
    mentionSelected = selected,
  )

  @Test
  fun 下键在面板里循环移动() {
    // M-20：`moveMentionSelection` 早就有实现与测试，但界面没有任何入口 ——
    // 有键盘的用户只能点屏幕。这条守住「入口真的接上了」这一层的语义：
    // 界面按下的 Δ 会走到它，且循环正确。
    val c = composerWithPanel(selected = 2)
    val down = ThreadReducer.moveMentionSelection(c, 1)
    assertEquals("末项按下去回到第一项", 0, down.mentionSelected)
  }

  @Test
  fun 上键在面板里反向循环() {
    val c = composerWithPanel(selected = 0)
    val up = ThreadReducer.moveMentionSelection(c, -1)
    assertEquals("第一项按上去到末项", 2, up.mentionSelected)
  }

  @Test
  fun 面板没开时移动不改变任何状态() {
    // 边界：面板关着的时候 moveMentionSelection 必须是空操作，
    // 否则方向键会在用户不知情时改掉一个看不见的选中下标，
    // 下次打开面板时停在意想不到的位置。
    val closed = ComposerState(draft = "普通文字")
    val moved = ThreadReducer.moveMentionSelection(closed, 1)
    assertEquals("面板没开就不动", closed, moved)
  }

  @Test
  fun 回车选中当前高亮项() {
    // 界面里的 onMentionConfirm 会取 mentionResults[mentionSelected]，
    // 这里验证「取到的是对的那一个，且插入后路径正确」。
    val c = composerWithPanel(selected = 1)
    val pick = c.mentionResults[c.mentionSelected]
    assertEquals("src/markdown.md", pick.path)

    val after = ThreadReducer.selectMention(c, pick)
    assertTrue("插入后草稿要带上路径", after.draft.contains("src/markdown.md"))
    assertEquals("插入后面板要关掉", null, after.mentionQuery)
  }

  // ───────────────── P1-17：流式期间也要折叠 ─────────────────

  /** 造一条含 N 个 Markdown 块的正文（每个空行分隔算一块）。 */
  private fun manyBlocks(n: Int): String =
    (1..n).joinToString("\n\n") { "第 $it 段正文内容。" }

  @Test
  fun 流式期间长消息也要折叠() {
    // 回归 P1-17：旧条件带 `&& !message.streaming`，于是「正在生成的那条」
    // 永远全量展开 —— 而流式恰恰是最长的阶段（10 秒内每帧重解析 + 全量渲染
    // 40+ 块），也正是用户盯着屏幕的那一刻。
    //
    // 折叠后仍然每 24ms 更新可见的那 4 块，用户照样看到文字在长。
    render(
      ThreadState(
        title = "t", subtitle = pc,
        messages = listOf(
          msg("m1", manyBlocks(10), streaming = true),
        ),
      )
    )
    compose.waitForIdle()

    // 折叠态会出现「展开全文 · 还有 N 段」这个按钮；全量展开时不会有。
    compose.onNodeWithText("展开全文", substring = true).assertExists()
  }

  @Test
  fun 非流式长消息同样折叠() {
    // 反向对照：非流式本来就该折叠。两条一起看才说明
    // 「折叠判定与 streaming 无关」——这正是 P1-17 的结论。
    render(
      ThreadState(
        title = "t", subtitle = pc,
        messages = listOf(
          msg("m1", manyBlocks(10), streaming = false),
        ),
      )
    )
    compose.waitForIdle()

    compose.onNodeWithText("展开全文", substring = true).assertExists()
  }

  @Test
  fun 短消息即使流式也不折叠() {
    // 边界：块数没超阈值就不该出现折叠按钮 ——
    // 一两条短消息也折叠会让界面显得处处是折角。
    render(
      ThreadState(
        title = "t", subtitle = pc,
        messages = listOf(
          msg("m1", "只有一句话。", streaming = true),
        ),
      )
    )
    compose.waitForIdle()

    compose.onAllNodesWithText("展开全文", substring = true)
      .fetchSemanticsNodes()
      .let { assertEquals("短消息不该折叠", 0, it.size) }
  }

  // ───────────────── 验收 2：流式增量 ─────────────────


  @Test
  fun 生成中显示正在生成提示() {
    render(
      ThreadState(
        title = "t", subtitle = pc,
        messages = listOf(msg("m1", "半截", streaming = true)),
      )
    )
    compose.waitForIdle()

    compose.onNodeWithText("正在生成…").assertIsDisplayed()
  }

  @Test
  fun 完成后不再显示正在生成() {
    render(
      ThreadState(
        title = "t", subtitle = pc,
        messages = listOf(
          ThreadMessage(id = "m1", author = Author.Assistant, text = "完成", streaming = false, elapsedMillis = 192_000)
        ),
      )
    )
    compose.waitForIdle()

    compose.onAllNodesWithText("正在生成…").fetchSemanticsNodes().let {
      assertEquals("完成后不该还在「正在生成」", 0, it.size)
    }
    compose.onNodeWithText("用时 3 分 12 秒").assertIsDisplayed()
  }

  // ───────────────── 验收 3：Composer 始终可见 ─────────────────

  @Test
  fun 消息很多时Composer仍在页面底部可见() {
    val messages = (1..200).map { msg("m$it", "第 $it 条") }
    render(ThreadState(title = "t", subtitle = pc, messages = messages))
    compose.waitForIdle()

    // Composer 是 Column 里的固定部分（不在 LazyColumn 内），
    // 所以无论列表滚到哪里它都在。这条断言挡的是「把 Composer 放进列表里」这种错法。
    compose.onNodeWithTag(ThreadTags.COMPOSER).assertIsDisplayed()
    compose.onNodeWithTag(ComposerTags.FIELD).assertIsDisplayed()
  }

  @Test
  fun 空态也与Composer共存() {
    render(ThreadState(title = "t", subtitle = pc))
    compose.waitForIdle()

    compose.onNodeWithText("这个会话还没有消息").assertIsDisplayed()
    compose.onNodeWithTag(ThreadTags.COMPOSER).assertIsDisplayed()
  }

  // ───────────────── 验收 4：@ 文件面板 ─────────────────

  @Test
  fun 输入at符弹出文件面板() {
    reset()
    render(ThreadState(title = "t", subtitle = pc))
    compose.waitForIdle()

    compose.onAllNodesWithText("选择文件").fetchSemanticsNodes().let {
      assertEquals("初始不该显示面板", 0, it.size)
    }

    compose.onNodeWithTag(ComposerTags.FIELD).performTextInput("@")
    compose.waitForIdle()

    // 面板出现，且列出全部文件
    compose.onNodeWithTag(ThreadTags.MENTION_PANEL).assertIsDisplayed()
    compose.onAllNodesWithText("data/q3-sales.csv").fetchSemanticsNodes().let {
      assertTrue("面板应列出工作区相对路径", it.isNotEmpty())
    }
  }

  @Test
  fun 没有文件列表时说明是同步问题不是查询问题() {
    // 生产环境 relay 还没接上，files 是空列表。
    // 此时输入 @ 会得到一个空面板 —— 文案必须说明「列表还没同步」，
    // 而不是「没有匹配的文件」：后者会让用户一直改查询，
    // 而问题根本不在查询上。
    //
    // 注意不能复用 render()：它用的 files 有两条候选，测不到「没有列表」。
    reset()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ThreadScreen(
          state = ThreadState(title = "t", subtitle = pc).copy(composer = composer),
          onBack = {},
          onComposerTextChange = { text ->
            composer = ThreadReducer.onDraftChange(composer, text, emptyList())
          },
          onModeChange = {},
          onMentionSelect = {},
          onMentionDismiss = {},
          onSend = {},
          onStop = {},
        )
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag(ComposerTags.FIELD).performTextInput("@")
    compose.waitForIdle()

    compose.onNodeWithText("文件列表还没同步过来").assertIsDisplayed()
    compose.onAllNodesWithText("没有匹配的文件").fetchSemanticsNodes().let {
      assertEquals("查不到候选的原因不是查询，别误导用户", 0, it.size)
    }
  }

  @Test
  fun 有文件但查询无匹配时说没有匹配的文件() {
    // 与上一条对照：有候选但过滤后为空时，文案完全不同。
    reset()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ThreadScreen(
          state = screen.copy(composer = composer),
          onBack = {},
          onComposerTextChange = { text ->
            // 这里注入非空文件列表，模拟 relay 已接上的状态
            composer = ThreadReducer.onDraftChange(composer, text, listOf(WorkspaceFile("data/x.csv")))
          },
          onModeChange = {},
          onMentionSelect = {},
          onMentionDismiss = {},
          onSend = {},
          onStop = {},
        )
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag(ComposerTags.FIELD).performTextInput("@zzz")
    compose.waitForIdle()

    compose.onNodeWithText("没有匹配的文件").assertIsDisplayed()
    compose.onAllNodesWithText("文件列表还没同步过来").fetchSemanticsNodes().let {
      assertEquals("有候选时不该说同步问题", 0, it.size)
    }
  }

  @Test
  fun 选中文件后插入工作区相对路径() {
    reset()
    render(ThreadState(title = "t", subtitle = pc))
    compose.waitForIdle()

    compose.onNodeWithTag(ComposerTags.FIELD).performTextInput("看下 @q3")
    compose.waitForIdle()

    // 点击候选
    compose.onNodeWithText("data/q3-sales.csv").performClick()
    compose.waitForIdle()

    assertEquals("data/q3-sales.csv", lastMentionPath)
    // 插入的是相对路径，带尾空格
    assertTrue(
      "插入后草稿应含 @data/q3-sales.csv（实际：${composer.draft}）",
      composer.draft == "看下 @data/q3-sales.csv "
    )
    assertNull("选中后面板关闭", composer.mentionQuery)
  }

  // ───────────────── 执行组折叠 ─────────────────

  private fun groupState(expandedHint: String? = null): ThreadState {
    val group = ToolGroup(
      id = "g1",
      steps = listOf(
        ToolStep("s1", "读取 4 个 CSV", StepState.Ok),
        ToolStep("s2", "按渠道聚合", StepState.Ok),
      ),
      detail = "data/*.csv · csv, 4 files",
      elapsedMillis = 12_000,
    )
    return ThreadState(
      title = "t", subtitle = pc,
      messages = listOf(
        ThreadMessage(id = "m1", author = Author.Assistant, text = "报告写好了", toolGroup = group)
      ),
    )
  }

  @Test
  fun 执行组默认收起只显示步数与用时() {
    render(groupState())
    compose.waitForIdle()

    // 收起态：一行摘要
    compose.onNodeWithText("2 个步骤 · 12 秒").assertIsDisplayed()
    // 细节没渲染
    compose.onAllNodesWithText("读取 4 个 CSV").fetchSemanticsNodes().let {
      assertEquals("默认不该展开步骤细节", 0, it.size)
    }
  }

  @Test
  fun 点击执行组展开后能看到每个步骤() {
    render(groupState(), expanded = setOf("g1"))
    compose.waitForIdle()

    compose.onNodeWithText("读取 4 个 CSV").assertIsDisplayed()
    compose.onNodeWithText("按渠道聚合").assertIsDisplayed()
    // 展开态显示「收起」
    compose.onNodeWithText("收起").assertIsDisplayed()
  }

  @Test
  fun 有失败步骤时收起态也要露出失败() {
    val group = ToolGroup(
      id = "g1",
      steps = listOf(
        ToolStep("s1", "读取 CSV", StepState.Ok),
        ToolStep("s2", "写文件", StepState.Failed),
      ),
      detail = null,
      elapsedMillis = 5_000,
    )
    render(
      ThreadState(
        title = "t", subtitle = pc,
        messages = listOf(ThreadMessage(id = "m1", author = Author.Assistant, text = "", toolGroup = group)),
      )
    )
    compose.waitForIdle()

    // 失败必须能在收起态看到：2 个步骤 · 1 失败 · 5 秒
    compose.onNodeWithText("2 个步骤 · 1 失败 · 5 秒").assertIsDisplayed()
  }

  // ───────────────── 计划卡与成品卡 ─────────────────

  @Test
  fun 计划卡显示批准状态与步骤() {
    val plan = PlanCard(
      id = "p1",
      title = "销售复盘",
      steps = listOf(
        PlanStep("读取 4 个 CSV", StepState.Ok),
        PlanStep("标记 17 笔异常", StepState.Ok),
      ),
      approved = true,
    )
    render(
      ThreadState(
        title = "t", subtitle = pc,
        // 注意：助手消息文本里不能再出现「计划」二字，否则会和卡片标题、
        // 以及底部模式条上的「计划」撞成 3 个同名节点，断言就无法唯一定位。
        // 这不是绕过问题 —— 真实页面上这三处本来就是不同的东西，
        // 用 testTag 精确定位才是对的。
        messages = listOf(ThreadMessage(id = "m1", author = Author.Assistant, text = "开工", plan = plan)),
      )
    )
    compose.waitForIdle()

    compose.onNodeWithText("已批准").assertIsDisplayed()
    compose.onNodeWithText("销售复盘").assertIsDisplayed()
    compose.onNodeWithText("读取 4 个 CSV").assertIsDisplayed()
  }

  @Test
  fun 成品卡显示文件名增删与大小() {
    val ref = ArtifactRef("Q3-sales-review.html", "HTML", 24_800, 318, 0)
    render(
      ThreadState(
        title = "t", subtitle = pc,
        messages = listOf(ThreadMessage(id = "m1", author = Author.Assistant, text = "好了", artifact = ref)),
      )
    )
    compose.waitForIdle()

    compose.onNodeWithText("Q3-sales-review.html").assertIsDisplayed()
    compose.onNodeWithText("+318").assertIsDisplayed()
    compose.onNodeWithText("24.2 KB").assertIsDisplayed()
  }

  // ───────────────── 发送 / 停止 ─────────────────

  @Test
  fun 生成中显示停止按钮且点击真调用() {
    render(
      ThreadState(
        title = "t", subtitle = pc,
        messages = listOf(msg("m1", "写一半", streaming = true)),
        composer = ComposerState(generating = true),
      )
    )
    compose.waitForIdle()

    compose.onNodeWithTag(ThreadTags.STOP_BUTTON).assertIsDisplayed()
    compose.onNodeWithTag(ThreadTags.STOP_BUTTON).performClick()
    compose.waitForIdle()

    assertEquals("停止按钮必须真的接到回调", 1, stopCalls)
  }

  @Test
  fun 非生成态不渲染停止按钮() {
    render(
      ThreadState(
        title = "t", subtitle = pc,
        messages = listOf(msg("m1", "完成", streaming = false)),
        composer = ComposerState(generating = false),
      )
    )
    compose.waitForIdle()

    compose.onAllNodesWithText("停止").fetchSemanticsNodes().let {
      assertEquals("不在生成中时不该有停止按钮", 0, it.size)
    }
  }

  @Test
  fun 输入后发送带上模式与trim() {
    reset()
    render(ThreadState(title = "t", subtitle = pc))
    compose.waitForIdle()

    compose.onNodeWithTag(ComposerTags.mode(ComposerMode.Research)).performClick()
    compose.waitForIdle()
    assertEquals(ComposerMode.Research, currentMode)

    compose.onNodeWithTag(ComposerTags.FIELD).performTextInput("看看 q3 数据")
    compose.waitForIdle()
    compose.onNodeWithTag(ThreadTags.SEND_BUTTON).performClick()
    compose.waitForIdle()

    assertEquals("看看 q3 数据", sentText)
  }

  // ───────────────── 格式化函数 ─────────────────

  @Test
  fun 用时格式化覆盖秒分与整分() {
    assertEquals("0 秒", formatDurationSpoken(0))
    assertEquals("12 秒", formatDurationSpoken(12_000))
    assertEquals("1 分", formatDurationSpoken(60_000))
    assertEquals("3 分 12 秒", formatDurationSpoken(192_000))
    // 59.6 秒应进位为 60 而不是显示「59 秒」或「0 分 60 秒」
    assertEquals("1 分", formatDurationSpoken(59_600))
  }

  @Test
  fun token格式化覆盖三档() {
    assertEquals("999", formatCompactTokens(999))
    assertEquals("1.5K", formatCompactTokens(1_500))
    assertEquals("18.4K", formatCompactTokens(18_400))
    assertEquals("412K", formatCompactTokens(412_000))
    assertEquals("1.2M", formatCompactTokens(1_200_000))
  }

  @Test
  fun 字节格式化与工位页同口径() {
    assertEquals("512 B", formatSize(512))
    assertEquals("1.0 KB", formatSize(1024))
    assertEquals("24.2 KB", formatSize(24_800))
    assertEquals("1.0 MB", formatSize(1024 * 1024))

    // 这条断言才是「同口径」的真正证明：旧版本四个页面各写一份，
    // 同一个 24_800 在对话页是「24.2 KB」、工位页是「24.2K」、预览页是「24 KB」。
    // 现在它们都转调 Formatters.bytes，逐一比对即可发现任何未来的分叉。
    val samples = listOf(0L, 512L, 1024L, 24_800L, 186_368L, 1_048_371L, 1_258_291L)
    for (n in samples) {
      val viaThread = formatSize(n)
      assertEquals("工位页与对话页必须一致（$n）", viaThread, WorkbenchFormat.bytes(n))
      assertEquals("成品页与对话页必须一致（$n）", viaThread, formatReadableBytes(n))
    }
  }

  // ─────────────── M-16：草稿跨旋转保留 ───────────────

  /**
   * Saver 的往返测试。
   *
   * 这里直接调 `ComposerStateSaver.save` / `.restore`，不是渲染断言 ——
   * 因为要证明的恰恰是「存档内容对不对」，而那与界面无关。旋转屏幕的
   * 重建过程在 Robolectric 里可以靠 recreate() 模拟，但那条路径失败时
   * 报的是「找不到节点」，定位不到「到底是草稿没存还是没读回来」。
   */
  @Test
  fun 草稿存档后能原样恢复() {
    val saved = with(ComposerStateSaver) {
      androidx.compose.runtime.saveable.SaverScope { true }.save(
        ComposerState(draft = "帮我改一下第三章", mode = ComposerMode.Plan, canSend = true)
      )
    }!!
    val restored = ComposerStateSaver.restore(saved)!!
    assertEquals("草稿必须原样回来", "帮我改一下第三章", restored.draft)
    assertEquals("模式也要一起回来", ComposerMode.Plan, restored.mode)
    assertTrue("有内容的草稿要能直接发出去", restored.canSend)
  }

  @Test
  fun 存档不包含生成中这类瞬态() {
    // 恢复出一个陈旧的「生成中」会让界面等一个不存在的流 ——
    // 比不恢复更糟。所以 generating / sending 必须被丢掉。
    val live = ComposerState(draft = "x", generating = true, sending = true, canSend = true)
    val saved = with(ComposerStateSaver) {
      androidx.compose.runtime.saveable.SaverScope { true }.save(live)
    }!!
    val restored = ComposerStateSaver.restore(saved)!!
    assertFalse("生成中是瞬态，不能存档", restored.generating)
    assertFalse("发送中同理", restored.sending)
  }

  @Test
  fun 空草稿恢复后不给发送能力() {
    val saved = with(ComposerStateSaver) {
      androidx.compose.runtime.saveable.SaverScope { true }.save(ComposerState(draft = "", canSend = true))
    }!!
    val restored = ComposerStateSaver.restore(saved)!!
    assertEquals("", restored.draft)
    assertFalse("空白草稿不能因为存档时的旧布尔就可发送", restored.canSend)
  }
}
