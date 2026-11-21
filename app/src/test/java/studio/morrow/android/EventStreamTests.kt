package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.morrow.android.DeltaKind
import studio.morrow.android.EventAction
import studio.morrow.android.EventParser
import studio.morrow.android.EventReducer
import studio.morrow.android.MessageRole
import studio.morrow.android.MorrowUiState
import studio.morrow.android.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import studio.morrow.android.EventStreamViewModel
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import studio.morrow.android.ForegroundSyncState
import studio.morrow.android.SyncState
import studio.morrow.android.WorkView
import studio.morrow.android.MorrowDestination
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.morrow.android.IgnoreBridges

// ───── EventReducerTest.kt ─────
/**
 * D1 事件归并测试 —— 逐条覆盖蓝图 D1 的三条验收。
 *
 * 1. 用录制的事件回放包重放，最终状态与桌面同源状态逐字段一致
 * 2. 切出会话再切回，流式消息不重复不丢失
 * 3. A 会话运行时切到 B，停止按钮只作用于 B
 */
class EventReducerTest {

  // ═══════════ 事件类型覆盖 ═══════════

  @Test
  fun 桌面二十八种事件类型与快照一个不漏() {
    // 蓝图说 20 种，实测 28 种（src/shared/types.ts:1676）；另加 1 种
    // 只存在于 relay 的 `snapshot`（不是 RuntimeEvent，是首连对齐控制事件）。
    // 这个断言守的是「新增了 type 却忘了在 EventParser 里处理」。
    assertEquals(29, EventParser.knownTypes.size)
    // 逐个都能解析出动作（不是 null、不是 Unparsed）
    EventParser.knownTypes.forEach { type ->
      val raw = mutableMapOf<String, Any?>(
        "type" to type, "seq" to 1L, "sessionId" to "s1", "runId" to "r1", "at" to 1000L,
      )
      // 给需要字段的类型补最小合法载荷
      when (type) {
        "run_started" -> raw["assistantMessageId"] = "m1"
        "message_started", "message_delta", "message_completed" -> {
          raw["messageId"] = "m1"
          if (type == "message_delta") raw["textDelta"] = "x"
          if (type == "message_completed") raw["message"] = mapOf("text" to "x")
        }
        "permission_requested" -> raw["request"] = mapOf("id" to "p1", "toolName" to "run_command")
        "permission_resolved" -> raw["requestId"] = "p1"
        // 工具事件已接线：给最小合法载荷，否则会因缺 messageId/toolUseId 变成 Unparsed
        "tool_started" -> {
          raw["messageId"] = "m1"
          raw["toolUseId"] = "t1"
          raw["tool"] = mapOf("name" to "run_command", "title" to "运行命令：npm test", "category" to "exec")
        }
        "tool_finished" -> {
          raw["messageId"] = "m1"
          raw["toolUseId"] = "t1"
        }
        "messages_replaced" -> raw["messages"] = listOf(mapOf("id" to "m1", "text" to "x", "createdAt" to 1L))
        "snapshot" -> raw["snapshot"] = mapOf("sessions" to emptyList<Any>(), "runs" to emptyList<Any>())
      }
      val parsed = EventParser.parse(raw)
      assertNotNull("$type 应能解析", parsed)
      assertFalse(
        "$type 不该是 Unparsed",
        parsed!!.action is EventAction.Unparsed
      )
    }
  }

  @Test
  fun 未知type计入Unparsed而不是静默忽略() {
    // 静默忽略会让「桌面新增了一种事件、手机不认识」永远不被发现
    val p = EventParser.parse(mapOf("type" to "future_event", "seq" to 1L, "sessionId" to "s1"))
    assertTrue(p!!.action is EventAction.Unparsed)
    assertEquals("future_event", (p.action as EventAction.Unparsed).type)
  }

  // ═══════════ 全量快照（首连对齐） ═══════════

  private fun snapshotRaw(): Map<String, Any?> = mapOf(
    "type" to "snapshot", "seq" to 50L, "sessionId" to "", "runId" to "snapshot", "at" to 1_000L,
    "snapshot" to mapOf(
      "activeSessionId" to "s2",
      "sessions" to listOf(
        mapOf(
          "id" to "s1", "title" to "旧会话",
          "messages" to listOf(mapOf("id" to "m1", "role" to "user", "text" to "hi", "createdAt" to 1L)),
        ),
        mapOf("id" to "s2", "title" to "新会话", "liveMessageId" to "m2", "messages" to emptyList<Any>()),
      ),
      "runs" to listOf(
        mapOf("sessionId" to "s2", "runId" to "r2", "startedAtMillis" to 2_000L, "stepCount" to 3),
      ),
      "approvals" to listOf(
        mapOf(
          "requestId" to "p1", "toolName" to "write_file", "sessionId" to "s2", "at" to 3_000L,
          "title" to "写文件", "summary" to "修改报告", "category" to "write", "risk" to "medium",
        ),
      ),
      "artifacts" to listOf(mapOf("title" to "报告.html", "kind" to "html", "updatedAt" to 4_000L)),
      "artifactsSessionId" to "s2",
      "members" to mapOf(
        "goal" to "把报告改完",
        "tasks" to listOf(mapOf("index" to 0, "title" to "成员 A", "status" to "running")),
      ),
    ),
  )

  @Test
  fun 协作事件的目标与成员进入状态() {
    val parsed = EventParser.parse(
      mapOf(
        "type" to "super_updated", "seq" to 3L, "sessionId" to "s1", "runId" to "r1", "at" to 1L,
        "state" to mapOf(
          "status" to "running", "goal" to "把 Q3 复盘做完",
          "tasks" to listOf(mapOf("index" to 0, "title" to "前端", "status" to "running")),
        ),
      )
    )!!
    val state = EventReducer.apply(MorrowUiState(), parsed.action, parsed.seq)
    assertEquals("把 Q3 复盘做完", state.superGoal)
    assertEquals("前端", state.members!!.single().title)
    assertEquals("running", state.members!!.single().state)
  }

  @Test
  fun 快照一次对齐会话运行审批产物与成员() {
    val parsed = EventParser.parse(snapshotRaw())!!
    assertTrue(parsed.action is EventAction.Snapshot)
    val state = EventReducer.apply(MorrowUiState(), parsed.action, parsed.seq)

    assertEquals(2, state.sessions.size)
    assertEquals("新会话", state.sessions["s2"]!!.title)
    assertEquals("m2", state.sessions["s2"]!!.liveMessageId)
    assertEquals("hi", state.sessions["s1"]!!.messages.single().text)

    assertEquals("s2", state.activeSessionId)
    assertEquals("r2", state.runs["s2"]!!.runId)
    assertEquals(3, state.runs["s2"]!!.stepCount)

    assertEquals("写文件", state.pendingApprovals["p1"]!!.title)
    assertEquals("medium", state.pendingApprovals["p1"]!!.risk)

    assertEquals("报告.html", state.artifacts!!.single().name)
    assertEquals("s2", state.artifactsSessionId)
    assertEquals("成员 A", state.members!!.single().title)
    assertEquals("快照也要带出协作目标", "把报告改完", state.superGoal)

    assertTrue(state.snapshotLoaded)
    assertEquals(50L, state.lastSeq)
  }

  @Test
  fun 快照把lastSeq重置为快照序号_桌面重启后不会漏事件() {
    // 桌面进程重启 → seq 从 1 重新开始。手机若保留上次的 lastSeq（如 3496），
    // 重连时会告诉桌面「已收到 3496」，桌面于是跳过新进程的 1–N。
    val before = MorrowUiState(lastSeq = 3496L)
    val parsed = EventParser.parse(snapshotRaw())!!
    val after = EventReducer.apply(before, parsed.action, parsed.seq)
    assertEquals(50L, after.lastSeq)
  }

  @Test
  fun 快照是替换而不是合并_桌面删掉的会话不会留在手机上() {
    val before = MorrowUiState(
      sessions = mapOf("gone" to studio.morrow.android.SessionUi("gone", "已删除")),
      pendingApprovals = mapOf("old" to studio.morrow.android.UiApproval("old", "x", "gone")),
    )
    val parsed = EventParser.parse(snapshotRaw())!!
    val after = EventReducer.apply(before, parsed.action, parsed.seq)

    assertNull(after.sessions["gone"])
    assertNull(after.pendingApprovals["old"])
    assertEquals(2, after.sessions.size)
  }

  @Test
  fun 审批请求带入页面展示字段() {
    val raw = mapOf(
      "type" to "permission_requested", "seq" to 7L, "sessionId" to "s1", "runId" to "r1", "at" to 9_000L,
      "request" to mapOf(
        "id" to "p9", "toolName" to "run_command", "title" to "执行命令",
        "summary" to "运行构建", "category" to "command", "risk" to "high", "allowAlways" to false,
      ),
    )
    val parsed = EventParser.parse(raw)!!
    val state = EventReducer.apply(MorrowUiState(), parsed.action, parsed.seq)
    val approval = state.pendingApprovals["p9"]!!
    assertEquals("执行命令", approval.title)
    assertEquals("运行构建", approval.summary)
    assertEquals("command", approval.category)
    assertEquals("high", approval.risk)
    assertEquals(false, approval.allowAlways)
    assertEquals(9_000L, approval.requestedAtMillis)
  }

  @Test
  fun 缺关键字段计入Unparsed() {
    // run_started 没有 assistantMessageId 就无法建容器
    val p = EventParser.parse(
      mapOf("type" to "run_started", "seq" to 1L, "sessionId" to "s1", "runId" to "r1")
    )
    assertTrue(p!!.action is EventAction.Unparsed)
  }

  @Test
  fun 不改变状态的事件明确记录原因() {
    // 不是「忘了处理」，是「知道它是什么、但手机不需要它」。
    // 本轮把工具/待办/计划/目标/提示/错误接成真实状态（用户要能从手机看到
    // 电脑执行了什么命令、改了哪些文件），因此 Ignore 只剩这些。
    val ignored = listOf(
      "question_asked", "question_answered",
      "research_updated", "collaboration_updated",
      "queue_updated",
      "compaction", "episodes_captured",
      "demo_step",
      // tool_progress 不带 memberTask 时归 Ignore（用最小载荷触发该分支）
    )
    assertEquals("Ignore 类共 8 种 + tool_progress 无 memberTask 共 1 种", 8, ignored.size)
    ignored.forEach { type ->
      val p = EventParser.parse(
        mapOf("type" to type, "seq" to 1L, "sessionId" to "s1", "runId" to "r1", "at" to 1L)
      )
      assertTrue("$type 应为 Ignore", p!!.action is EventAction.Ignore)
      assertTrue(
        "$type 必须写明为什么忽略",
        (p.action as EventAction.Ignore).why.isNotBlank()
      )
    }
    // tool_progress 无 memberTask → Ignore
    val tp = EventParser.parse(mapOf("type" to "tool_progress", "seq" to 1L, "sessionId" to "s1", "percent" to 50))
    assertTrue("tool_progress 无 memberTask 归 Ignore", tp!!.action is EventAction.Ignore)
    assertTrue("并写明原因", (tp.action as EventAction.Ignore).why.isNotBlank())

    // 双向核对：改状态 20 种 + Ignore 8 种 + tool_progress 1 种 = 29 全集
    val stateChanging = listOf(
      "run_started", "run_finished", "message_started", "message_delta",
      "message_completed", "messages_replaced", "session_updated",
      // relay 首连对齐用的控制事件（不是桌面 RuntimeEvent）
      "snapshot",
      "permission_requested", "permission_resolved",
      // D1 第二批接出去的三家 + tool_progress（带 memberTask 时）
      "artifacts_updated", "usage_updated", "super_updated",
      // 本轮接出去：手机要看得见「命令执行 / 文件增删改 / 待办 / 计划 / 目标 / 提示」
      "tool_started", "tool_finished",
      "todos_updated", "goal_updated", "plan_updated",
      "notice", "error",
    )
    assertEquals("改状态 20 种", 20, stateChanging.size)
    assertEquals(
      "20 + 8 + 1 必须覆盖全部 29 种，不多不少",
      EventParser.knownTypes,
      (stateChanging + ignored + "tool_progress").toSet()
    )
  }

  @Test
  fun 切出会话再切回流式分片不丢不重() {
    // 这是蓝图第 2 条验收，也是桌面真实修过的缺陷：
    // 「否则切回运行中的会话会从下一个分片重新开始」
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.RunStarted("s1", "r1", "你好", "m1", 0L))
    s = EventReducer.apply(s, EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "我"))
    s = EventReducer.apply(s, EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "在"))

    // 切到另一个会话（不存在也要能切），再切回来
    s = EventReducer.selectSession(s, "s2")
    s = EventReducer.apply(s, EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "写"))
    s = EventReducer.selectSession(s, "s1")

    val msg = s.sessions["s1"]!!.messages.first { it.id == "m1" }
    assertEquals("切出期间到达的分片不能丢", "我在写", msg.text)
    assertEquals("不能重复插入", 2, s.sessions["s1"]!!.messages.size)
  }

  @Test
  fun 切回时会话仍然接在流上() {
    // 桌面靠 liveMessageId 判断「切回时还在生成」
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.RunStarted("s1", "r1", "hi", "m1", 0L))
    s = EventReducer.apply(s, EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "半句"))
    s = EventReducer.selectSession(s, "s2")
    s = EventReducer.selectSession(s, "s1")

    assertEquals("m1", s.sessions["s1"]!!.liveMessageId)
    assertTrue("那条仍在流式", s.sessions["s1"]!!.liveMessage!!.streaming)
  }

  @Test
  fun 切会话不清空别的会话的运行状态() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.RunStarted("s1", "r1", "a", "m1", 0L))
    s = EventReducer.apply(s, EventAction.RunStarted("s2", "r2", "b", "m2", 10L))
    s = EventReducer.selectSession(s, "s1")

    assertNotNull("s2 的 run 仍在", s.runs["s2"])
    assertEquals(2, s.runs.size)
  }

  // ═══════════ 验收 3：停止按钮只作用于当前会话 ═══════════

  @Test
  fun A会话运行时切到B停止只作用B() {
    // 蓝图原文：「A 会话运行时切到 B，停止按钮只作用于 B」
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.RunStarted("A", "runA", "a", "mA", 0L))
    s = EventReducer.apply(s, EventAction.RunStarted("B", "runB", "b", "mB", 10L))
    s = EventReducer.selectSession(s, "B")

    // 前台是 B —— 停止按钮拿到的应该是 B 的 run
    assertEquals("runB", s.activeRun?.runId)

    // 停止 B 之后，A 必须还在跑
    s = EventReducer.apply(s, EventAction.RunFinished("B", "runB"))
    assertNull("B 已停", s.runs["B"])
    assertNotNull("A 不能受牵连", s.runs["A"])
    assertEquals("runA", s.runs["A"]!!.runId)
  }

  @Test
  fun runId相同但会话不同时判定要区分() {
    // 不同会话的 runId 可能撞名；isRunOf 必须 sessionId + runId 一起看
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.RunStarted("A", "same", "a", "mA", 0L))
    s = EventReducer.apply(s, EventAction.RunStarted("B", "same", "b", "mB", 10L))

    assertTrue(s.isRunOf("A", "same"))
    assertTrue(s.isRunOf("B", "same"))
    assertFalse("C 没有这个 run", s.isRunOf("C", "same"))
  }

  @Test
  fun 结束事件只清对应会话的live消息() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.RunStarted("A", "rA", "a", "mA", 0L))
    s = EventReducer.apply(s, EventAction.RunStarted("B", "rB", "b", "mB", 10L))
    s = EventReducer.apply(s, EventAction.RunFinished("A", "rA"))

    assertNull("A 的 live 已清", s.sessions["A"]!!.liveMessageId)
    assertEquals("B 的 live 不受影响", "mB", s.sessions["B"]!!.liveMessageId)
    assertTrue("B 那条仍在流式", s.sessions["B"]!!.liveMessage!!.streaming)
  }

  // ═══════════ 验收 1：回放后逐字段一致 ═══════════

  @Test
  fun 回放一段完整对话后逐字段一致() {
    val actions = listOf(
      EventAction.RunStarted("s1", "r1", "把销售明细做成复盘", "m1", 1000L),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Thinking, "先看数据"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "好的，"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "我先读文件。"),
      EventAction.MessageCompleted("s1", "m1", "好的，我先读文件。"),
      EventAction.PermissionRequested("s1", "p1", "run_command"),
      EventAction.SessionUpdated("s1", "Q3 复盘"),
      EventAction.RunFinished("s1", "r1"),
    )
    var s = EventReducer.replay(MorrowUiState(), actions)
    s = EventReducer.selectSession(s, "s1")

    val sess = s.sessions["s1"]!!
    assertEquals("Q3 复盘", sess.title)
    assertEquals(2, sess.messages.size)
    assertEquals(MessageRole.User, sess.messages[0].role)
    assertEquals("把销售明细做成复盘", sess.messages[0].text)
    assertEquals(MessageRole.Assistant, sess.messages[1].role)
    assertEquals("好的，我先读文件。", sess.messages[1].text)
    assertFalse("完成后不再是流式", sess.messages[1].streaming)
    assertNull("完成后没有 live", sess.liveMessageId)
    assertEquals("先看数据", sess.messages[1].thinking)
    // 审批在队列里
    assertEquals(1, s.approvalsOf("s1").size)
    assertEquals("run_command", s.approvalsOf("s1")[0].toolName)
    // 轮次已收
    assertTrue(s.runs.isEmpty())
  }

  @Test
  fun 审批解决后从队列消失() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.PermissionRequested("s1", "p1", "mcp_call"))
    assertEquals(1, s.pendingApprovals.size)
    s = EventReducer.apply(s, EventAction.PermissionResolved("s1", "p1"))
    assertEquals(0, s.pendingApprovals.size)
  }

  @Test
  fun 审批按会话分桶不串台() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.PermissionRequested("A", "p1", "run_command"))
    s = EventReducer.apply(s, EventAction.PermissionRequested("B", "p2", "write_file"))

    assertEquals(1, s.approvalsOf("A").size)
    assertEquals("p1", s.approvalsOf("A")[0].requestId)
    assertEquals(1, s.approvalsOf("B").size)
  }

  // ═══════════ 24ms 合并（照抄桌面 store.ts:492） ═══════════

  @Test
  fun 相邻同key的delta会被合并() {
    val actions = listOf(
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "你"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "好"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "吗"),
    )
    val merged = EventReducer.coalesce(actions)
    assertEquals("三段合成一段", 1, merged.size)
    assertEquals("你好吗", (merged[0] as EventAction.MessageDelta).text)
  }

  @Test
  fun 中间夹thinking时不跨越合并() {
    // 若合并成 text("你好吗") 就把 thinking 的位置弄丢了
    val actions = listOf(
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "你"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "好"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Thinking, "想"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "吗"),
    )
    val merged = EventReducer.coalesce(actions)
    assertEquals("必须是 3 段", 3, merged.size)
    assertEquals("你好", (merged[0] as EventAction.MessageDelta).text)
    assertEquals(DeltaKind.Thinking, (merged[1] as EventAction.MessageDelta).kind)
    assertEquals("吗", (merged[2] as EventAction.MessageDelta).text)
  }

  @Test
  fun 不同消息的分片不合并() {
    val actions = listOf(
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "a"),
      EventAction.MessageDelta("s1", "m2", DeltaKind.Text, "b"),
    )
    assertEquals(2, EventReducer.coalesce(actions).size)
  }

  @Test
  fun 不同会话的分片不合并() {
    val actions = listOf(
      EventAction.MessageDelta("A", "m1", DeltaKind.Text, "a"),
      EventAction.MessageDelta("B", "m1", DeltaKind.Text, "b"),
    )
    assertEquals(2, EventReducer.coalesce(actions).size)
  }

  @Test
  fun 合并后结果与逐条应用完全等价() {
    // 这是合并正确性的判据：合并只是减少次数，不能改变最终状态
    val actions = listOf(
      EventAction.RunStarted("s1", "r1", "hi", "m1", 0L),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "一"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "二"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Thinking, "思"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "三"),
      EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "四"),
    )
    val direct = EventReducer.replay(MorrowUiState(), actions)
    val coalesced = EventReducer.replay(MorrowUiState(), EventReducer.coalesce(actions))

    assertEquals(
      "合并前后最终状态必须一模一样",
      direct.sessions["s1"]!!.messages.find { it.id == "m1" }!!.text,
      coalesced.sessions["s1"]!!.messages.find { it.id == "m1" }!!.text
    )
    // 逐条应用的结果是「一」「二」+「三」「四」拼起来 = 一二三四
    assertEquals("一二三四", coalesced.sessions["s1"]!!.messages.find { it.id == "m1" }!!.text)
    assertEquals("思", coalesced.sessions["s1"]!!.messages.find { it.id == "m1" }!!.thinking)
    // 合并只减少次数，不改变内容：动作数从 6 段降下来但要保住 thinking 的边界
    assertEquals(
      "应是 run_started + text(一二) + thinking(思) + text(三四) = 4 段",
      4,
      EventReducer.coalesce(actions).size
    )
  }

  // ═══════════ 边界与幂等 ═══════════

  @Test
  fun 重复的message_started不插两条() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.MessageStarted("s1", "m1", MessageRole.Assistant))
    s = EventReducer.apply(s, EventAction.MessageStarted("s1", "m1", MessageRole.Assistant))
    assertEquals(1, s.sessions["s1"]!!.messages.size)
  }

  @Test
  fun delta先于message_started到达也不丢字() {
    // 网络乱序：delta 先到。丢弃会让这段文字永久消失（违反「不丢失」）
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "早到的字"))
    val msg = s.sessions["s1"]!!.messages.find { it.id == "m1" }
    assertNotNull("要补一条容器", msg)
    assertEquals("早到的字", msg!!.text)
  }

  @Test
  fun message_completed用最终全文覆盖累积分片() {
    // 分片累加可能因丢包不完整，最终全文是权威
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.RunStarted("s1", "r1", "q", "m1", 0L))
    s = EventReducer.apply(s, EventAction.MessageDelta("s1", "m1", DeltaKind.Text, "残缺"))
    s = EventReducer.apply(s, EventAction.MessageCompleted("s1", "m1", "完整的一句话"))
    assertEquals("完整的一句话", s.sessions["s1"]!!.messages.first { it.id == "m1" }.text)
  }

  @Test
  fun messages_replaced整表替换并重算live() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.RunStarted("s1", "r1", "q", "m1", 0L))
    s = EventReducer.apply(s, EventAction.MessagesReplaced("s1", listOf(
      UiMessage("x1", MessageRole.User, "旧一", createdAtMillis = 1L),
      UiMessage("x2", MessageRole.Assistant, "旧二", streaming = true, createdAtMillis = 2L),
    )))
    assertEquals(2, s.sessions["s1"]!!.messages.size)
    assertEquals("替换后 live 指向仍在流的那条", "x2", s.sessions["s1"]!!.liveMessageId)
  }

  @Test
  fun lastSeq只增不减且与墙上时间无关() {
    // seq 是事件序号，at 是墙上时间。初版把 at 当 seq 写进 state，
    // 回放测试立刻抓到（lastSeq 报 4226 而真实最大 seq 是 3496）。
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.RunStarted("s1", "r1", "q", "m1", 500L), seq = 7L)
    assertEquals("lastSeq 是 seq 7，不是 at 500", 7L, s.lastSeq)

    // 后来的旧 seq 不能把 lastSeq 拉回去
    s = EventReducer.apply(s, EventAction.SessionUpdated("s1", "t"), seq = 3L)
    assertEquals("旧 seq 不回退", 7L, s.lastSeq)

    // 更大的 seq 推进
    s = EventReducer.apply(s, EventAction.SessionUpdated("s1", "t2"), seq = 99L)
    assertEquals(99L, s.lastSeq)
  }

  @Test
  fun 不传seq时不更新lastSeq() {
    // 默认 0 = 调用方不关心 seq（例如单测只想验证状态变化）
    var s = MorrowUiState()
    s = EventReducer.apply(s, EventAction.RunStarted("s1", "r1", "q", "m1", 500L))
    assertEquals(0L, s.lastSeq)
  }
}

// ───── EventStreamViewModelTest.kt ─────
/**
 * ViewModel 层：24ms 节流 + 顺序保证。
 *
 * 测试重点不是「事件能进去」，而是四件真机上会出错的事：
 * ①每条 delta 都重组（卡死）；②非 delta 事件插到已缓冲的正文之前（顺序错乱）；
 * ③切会话/页面销毁时缓冲被丢弃（最后半句永远消失）；④跨 kind 合并（thinking 位置丢）。
 */
class EventStreamViewModelTest {

  private var scope: CoroutineScope? = null
  private lateinit var vm: EventStreamViewModel

  @After
  fun tearDown() { scope?.cancel() }

  /**
   * 必须在 runTest 内部调用：用 testScheduler 建 scope，
   * `advanceTimeBy` 才能推进这个 scope 里的 delay。
   *
   * 我第一版用 `CoroutineScope(StandardTestDispatcher())` 在字段上建，
   * 那是**另一个**调度器，runTest 的虚拟时间推不动它 ——
   * 于是 8 项「应该 flush 了」的测试全部失败，看起来像节流实现坏了。
   * 这类错误只有真跑测试才会暴露，读代码看不出来。
   */
  private fun TestScope.newVm(): EventStreamViewModel {
    val s = CoroutineScope(StandardTestDispatcher(testScheduler))
    scope = s
    vm = EventStreamViewModel(s, applyMs = 24L) { 1000L }
    return vm
  }

  private fun start(sid: String = "s1", runId: String = "r1", at: Long = 1L) = mapOf(
    "type" to "run_started", "seq" to 1L, "sessionId" to sid, "runId" to runId,
    "at" to at, "userMessage" to mapOf("text" to "问"), "assistantMessageId" to "m1",
  )

  private fun delta(text: String, sid: String = "s1", mid: String = "m1") = mapOf(
    "type" to "message_delta", "seq" to 2L, "sessionId" to sid, "runId" to "r1",
    "at" to 2L, "messageId" to mid, "textDelta" to text,
  )

  private fun think(t: String, sid: String = "s1", mid: String = "m1") = mapOf(
    "type" to "message_delta", "seq" to 3L, "sessionId" to sid, "runId" to "r1",
    "at" to 3L, "messageId" to mid, "thinkingDelta" to t,
  )

  private fun text(vm: EventStreamViewModel, sid: String = "s1", mid: String = "m1"): String =
    vm.state.sessions[sid]!!.messages.first { it.id == mid }.text

  private fun thinking(vm: EventStreamViewModel, sid: String = "s1", mid: String = "m1"): String? =
    vm.state.sessions[sid]!!.messages.first { it.id == mid }.thinking

  // ═══════════════ 节流 ═══════════════

  @Test
  fun 流式分片不立即上屏而是进缓冲() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(delta("你"))
    vm.onRawEvent(delta("好"))

    assertEquals("两条 delta 应在缓冲里", 2, vm.pendingCount)
    assertEquals("还没 flush", 0, vm.flushCount)
    assertEquals("字还没上屏（这是节流的意义）", "", text(vm))
  }

  @Test
  fun 二十四毫秒后一次性上屏() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(delta("你"))
    vm.onRawEvent(delta("好"))

    advanceTimeBy(25)
    assertEquals("合并成一次 flush", 1, vm.flushCount)
    assertEquals("你好", text(vm))
    assertEquals("缓冲已清", 0, vm.pendingCount)
  }

  @Test
  fun 不到二十四毫秒不flush() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(delta("你"))

    advanceTimeBy(20)
    assertEquals("20ms 时还没到窗口", 0, vm.flushCount)
    assertEquals("", text(vm))
  }

  @Test
  fun 一百条分片只flush一次而不是一百次() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    repeat(100) { vm.onRawEvent(delta("字")) }

    advanceTimeBy(25)
    assertEquals("关键：重组次数与分片数无关", 1, vm.flushCount)
    assertEquals("100 个字全部落地", "字".repeat(100), text(vm))
  }

  @Test
  fun 持续流式时每二十四毫秒一批() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())

    // 模拟真实节奏：每 10ms 来一个字，共 6 个字（60ms）
    repeat(6) {
      vm.onRawEvent(delta("字"))
      advanceTimeBy(10)
    }
    advanceTimeBy(30)

    // 60ms / 24ms ≈ 2–3 次（不是 6 次）
    assertTrue("flush 次数应远少于事件数（实际 ${vm.flushCount}）", vm.flushCount in 2..4)
    assertEquals("字".repeat(6), text(vm))
  }

  @Test
  fun 已有排程时不重复排程() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(delta("a"))
    vm.onRawEvent(delta("b"))
    vm.onRawEvent(delta("c"))
    advanceTimeBy(25)
    assertEquals("三条分片只产生一次 flush", 1, vm.flushCount)
  }

  // ═══════════════ 顺序保证（真机上最容易错的一条）═══════════════

  @Test
  fun 非delta事件必须先落地已缓冲的正文() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(delta("正文"))
    assertEquals("正文还在缓冲", 0, vm.flushCount)

    // 工具开始事件到达 —— 不能插到「正文」之前
    vm.onRawEvent(mapOf(
      "type" to "tool_started", "seq" to 4L, "sessionId" to "s1", "runId" to "r1",
      "at" to 4L, "messageId" to "m1", "block" to mapOf("name" to "read_file"),
    ))

    assertEquals("非 delta 触发了一次 flush", 1, vm.flushCount)
    assertEquals("正文必须先上屏", "正文", text(vm))
    assertEquals("缓冲已清", 0, vm.pendingCount)
  }

  @Test
  fun 消息完成事件到达时正文不能丢最后一个字() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(delta("最"))
    vm.onRawEvent(delta("后"))
    // message_completed 立即到达（不等 24ms）
    vm.onRawEvent(mapOf(
      "type" to "message_completed", "seq" to 5L, "sessionId" to "s1", "runId" to "r1",
      "at" to 5L, "messageId" to "m1", "message" to mapOf("text" to "最后"),
    ))
    assertEquals("最后", text(vm))
  }

  @Test
  fun 批量回放后缓冲必须清空() = runTest {
    val vm = newVm()
    val events = listOf(start(), delta("a"), delta("b"))
    vm.onRawEvents(events)
    assertEquals("批量结束时已落地", "ab", text(vm))
    assertEquals(0, vm.pendingCount)
  }

  // ═══════════════ 合并规则 ═══════════════

  @Test
  fun 相邻正文分片合并为一段() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(delta("你"))
    vm.onRawEvent(delta("好"))
    vm.onRawEvent(delta("吗"))
    advanceTimeBy(25)
    assertEquals("你好吗", text(vm))
  }

  @Test
  fun thinking夹在正文中间时不跨越合并() = runTest {
    // 桌面 flushPendingDeltas 的 kind 参与判定。若只按 messageId 合并，
    // 这个序列会被压成 text("你好号")，thinking 位置丢失
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(delta("你"))
    vm.onRawEvent(delta("好"))
    vm.onRawEvent(think("想"))
    vm.onRawEvent(delta("吗"))
    advanceTimeBy(25)

    assertEquals("正文：你好吗（thinking 不混进来）", "你好吗", text(vm))
    assertEquals("thinking 单独成段", "想", thinking(vm))
  }

  @Test
  fun 不同消息的分片不互相合并() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(mapOf(
      "type" to "message_started", "seq" to 9L, "sessionId" to "s1", "runId" to "r1",
      "at" to 9L, "messageId" to "m2", "role" to "assistant",
    ))
    vm.onRawEvent(delta("A", mid = "m1"))
    vm.onRawEvent(delta("B", mid = "m2"))
    advanceTimeBy(25)

    assertEquals("A", text(vm, mid = "m1"))
    assertEquals("B", text(vm, mid = "m2"))
  }

  // ═══════════════ 切会话 / 销毁 ═══════════════

  @Test
  fun 切会话前必须落地缓冲否则最后几个字丢失() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(mapOf(
      "type" to "run_started", "seq" to 20L, "sessionId" to "s2", "runId" to "r2",
      "at" to 20L, "userMessage" to mapOf("text" to "问2"), "assistantMessageId" to "m9",
    ))
    vm.selectSession("s1")
    vm.onRawEvent(delta("半句"))

    // 用户此时切到别的会话
    vm.selectSession("s2")

    assertEquals("切走前必须把「半句」落地", "半句", text(vm))
    assertEquals("确实切过去了", "s2", vm.state.activeSessionId)
  }

  @Test
  fun onCleared落地缓冲() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(delta("最后一句"))
    vm.onCleared()
    assertEquals("销毁前必须落地", "最后一句", text(vm))
    assertEquals(0, vm.pendingCount)
  }

  @Test
  fun 切会话不影响别的会话的流() = runTest {
    val vm = newVm()
    vm.onRawEvent(mapOf(
      "type" to "run_started", "seq" to 1L, "sessionId" to "A", "runId" to "rA",
      "at" to 1L, "userMessage" to mapOf("text" to "q"), "assistantMessageId" to "mA",
    ))
    vm.onRawEvent(mapOf(
      "type" to "run_started", "seq" to 2L, "sessionId" to "B", "runId" to "rB",
      "at" to 2L, "userMessage" to mapOf("text" to "q"), "assistantMessageId" to "mB",
    ))
    vm.selectSession("B")
    vm.onRawEvent(delta("A还在跑", sid = "A", mid = "mA"))
    advanceTimeBy(25)

    assertEquals("A 的分片照常落地", "A还在跑", text(vm, sid = "A", mid = "mA"))
    assertEquals("前台仍是 B", "B", vm.state.activeSessionId)
  }

  // ═══════════════ 状态语义 ═══════════════

  @Test
  fun 初始状态没有会话也没有前台() = runTest {
    val vm = newVm()
    assertNull(vm.state.activeSessionId)
    assertTrue(vm.state.sessions.isEmpty())
    assertEquals(0, vm.flushCount)
  }

  @Test
  fun startRun后消息立刻可见不必等flush() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    val sess = vm.state.sessions["s1"]
    assertNotNull("会话立刻建立", sess)
    assertEquals("用户消息 + 助手占位", 2, sess!!.messages.size)
    assertEquals(MessageRole.User, sess.messages[0].role)
    assertTrue("助手消息在流式中", sess.messages[1].streaming)
  }

  @Test
  fun 轮次结束后助手消息不再标流式() = runTest {
    val vm = newVm()
    vm.onRawEvent(start())
    vm.onRawEvent(delta("完成"))
    vm.onRawEvent(mapOf(
      "type" to "run_finished", "seq" to 30L, "sessionId" to "s1",
      "runId" to "r1", "at" to 30L, "reason" to "complete",
    ))
    val m = vm.state.sessions["s1"]!!.messages.first { it.id == "m1" }
    assertEquals("正文已落地", "完成", m.text)
    assertTrue("不再流式", !m.streaming)
  }
}

// ───── EventStreamWiringTest.kt ─────
/**
 * D1 第三批的**接线测试**：事件流真的接到了工位页上。
 *
 * 为什么需要这条：`MorrowApp(stream = vm)` 这个参数存在、
 * `WorkbenchProjection` 逻辑也对，**不等于**接线成立。
 * B2/B3 已经各犯过一次同样的错 —— 逻辑写好了、测试全绿，
 * 但生产入口没调用它，真机上功能不存在。
 *
 * 这里走的就是生产路径：`MorrowApp(stream = …)` → `WorkbenchRoute`
 * → `WorkbenchProjection.project` → `WorkbenchScreen` 渲染。
 *
 * 一个必须处理的前提：工位页的「在线」判定来自 `ForegroundSyncState`，
 * 而事件流投影的今日统计需要 `online = true` 才显示。所以在 Robolectric 里
 * 要先让前台服务处于 Running 态，否则测的永远是空态分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EventStreamWiringTest {

  @get:Rule
  val compose = createComposeRule()

  /** 真实构造一个「今天跑过一轮」的事件流 */
  private fun streamWithOneRound(): EventStreamViewModel {
    val scope = CoroutineScope(StandardTestDispatcher())
    val vm = EventStreamViewModel(scope, applyMs = 0L) { 1L }
    vm.onRawEvents(
      listOf(
        mapOf(
          "type" to "run_started", "seq" to 1L, "sessionId" to "s1", "runId" to "r1",
          "at" to 100L, "userMessage" to mapOf("text" to "把 Q3 做复盘"),
          "assistantMessageId" to "m1",
        ),
        mapOf(
          "type" to "usage_updated", "seq" to 2L, "sessionId" to "s1", "runId" to "r1",
          "at" to 101L, "usage" to mapOf("inputTokens" to 300000, "outputTokens" to 12000),
        ),
        mapOf(
          "type" to "artifacts_updated", "seq" to 3L, "sessionId" to "s1", "runId" to "r1",
          "at" to 102L, "artifacts" to listOf(
            mapOf(
              "title" to "q3-sales-review.html", "kind" to "html",
              "content" to "x".repeat(1024), "updatedAt" to 110L,
            ),
          ),
        ),
      ),
    )
    return vm
  }

  @Test
  fun 有事件流时工位页显示真实今日统计() {
    val vm = streamWithOneRound()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        // 让工位页判定为在线（否则今日统计按规则走空态）
        studio.morrow.android.ForegroundSyncState.set(
          studio.morrow.android.SyncState.Running(
            taskId = "task-1", step = 3, totalSteps = 8, startedAtMillis = 100L,
          ),
        )
        MorrowApp(stream = vm)
      }
    }

    // 关键断言：真实数字上屏了。
    // 300000+12000 = 312000 token → 312K；1 轮。
    // 断言具体文案而不是「有数字」：空态与数字态是互斥的两个分支，
    // 只查「页面渲染出来了」的话，接不接线都会绿。
    compose.onNodeWithText("1 轮 · 312K token").assertIsDisplayed()

    // 同时确认没走空态分支
    compose.onAllNodesWithText("今日统计要等连上电脑才有").assertCountEquals(0)
  }

  @Test
  fun 有事件流时最近成品显示真实条目() {
    val vm = streamWithOneRound()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        studio.morrow.android.ForegroundSyncState.set(
          studio.morrow.android.SyncState.Running(
            taskId = "task-1", step = 3, totalSteps = 8, startedAtMillis = 100L,
          ),
        )
        MorrowApp(stream = vm)
      }
    }
    // 产物名来自事件的 title 字段
    compose.onNodeWithText("q3-sales-review.html").assertIsDisplayed()
  }

  @Test
  fun 协作事件带出目标并接到协作页() {
    val vm = EventStreamViewModel(CoroutineScope(StandardTestDispatcher()), applyMs = 0L) { 100L }
    vm.onRawEvents(
      listOf(
        mapOf(
          "type" to "super_updated", "seq" to 1L, "sessionId" to "s1", "runId" to "r1", "at" to 100L,
          "state" to mapOf(
            "status" to "running",
            "goal" to "把 Q3 复盘做完",
            "tasks" to listOf(
              mapOf(
                "index" to 0, "title" to "前端重构", "agent" to "frontend",
                "agentTitle" to "前端", "status" to "running",
                "stepCount" to 3, "note" to "正在改登录页",
              ),
            ),
          ),
        ),
      ),
    )
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ForegroundSyncState.set(
          SyncState.Running(taskId = "task-1", step = 3, totalSteps = 8, startedAtMillis = 100L),
        )
        MorrowApp(stream = vm)
      }
    }
    compose.waitForIdle()
    compose.onNodeWithTag("workbench.openThread").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(ComposerTags.SWARM_ENTRY).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(SwarmTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithText("把 Q3 复盘做完").assertIsDisplayed()
    compose.onNodeWithText("前端重构").assertIsDisplayed()
    // 手机还没有 swarm.* 上行：必须只读，不能摆一排点了没用的按钮
    compose.onNodeWithTag(SwarmTags.READONLY).assertIsDisplayed()
    compose.onAllNodesWithTag(SwarmTags.action(0, MemberAction.Stop)).assertCountEquals(0)
  }

  @Test
  fun 作品库摘要接到作品页并在进入时刷新() {
    val vm = EventStreamViewModel(CoroutineScope(StandardTestDispatcher()), applyMs = 0L) { 100L }
    vm.onWorksLoaded(
      listOf(
        WorkView(
          id = "w1", title = "末日咖啡馆", kind = "short",
          logline = "末日后唯一还开着的咖啡馆", updatedAtMillis = 1_000L,
          stage = "writing", progress = 40, chapters = 3, shots = 0, assets = 2, words = 1_200L,
        ),
      ),
    )
    var refreshed = 0
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ForegroundSyncState.set(
          SyncState.Running(taskId = "task-1", step = 3, totalSteps = 8, startedAtMillis = 100L),
        )
        MorrowApp(stream = vm, onWorksOpened = { refreshed++ })
      }
    }
    compose.waitForIdle()
    compose.onNodeWithTag("workbench-works").performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(WorksTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(WorksTags.card("w1")).assertIsDisplayed()
    compose.onNodeWithText("末日咖啡馆").assertIsDisplayed()
    // 进入页面触发一次 works.get；否则桌面刚建的作品永远看不到
    assertEquals(1, refreshed)
  }

  @Test
  fun 审批深链直接进审批页并只消费一次() {
    var handled = 0
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(
          deepLink = MorrowDestination.Approvals(focusId = null),
          onDeepLinkHandled = { handled += 1 },
        )
      }
    }
    compose.waitForIdle()
    compose.onNodeWithTag(ApprovalTags.SCREEN).assertIsDisplayed()
    assertEquals(1, handled)
  }

  @Test
  fun 进展深链落到对应会话() {
    val vm = streamWithThread()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(
          stream = vm,
          deepLink = MorrowDestination.Activity(focusId = "s1"),
          onDeepLinkHandled = {},
        )
      }
    }
    compose.waitForIdle()
    compose.onNodeWithTag(ThreadTags.TRANSCRIPT).assertIsDisplayed()
    compose.onNodeWithText("好的，我先看数据").assertIsDisplayed()
  }

  @Test
  fun 没有事件流时行为与C1完全一致不显示假零() {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp()
      }
    }
    // C1 原有断言：未连接时是「尚未连接电脑」+ 今日统计空态
    compose.onNodeWithText("尚未连接电脑").assertIsDisplayed()
  }

  /** 一条真实审批事件（时间取「现在」，避免投影时被判过期） */
  private fun streamWithApproval(now: Long): EventStreamViewModel {
    val vm = EventStreamViewModel(CoroutineScope(StandardTestDispatcher()), applyMs = 0L) { now }
    vm.onRawEvents(
      listOf(
        mapOf(
          "type" to "permission_requested", "seq" to 1L, "sessionId" to "s1", "runId" to "r1", "at" to now,
          "request" to mapOf(
            "id" to "req-1", "toolName" to "write_file", "title" to "写入文件",
            "summary" to "修改报告", "category" to "write", "risk" to "medium", "allowAlways" to false,
          ),
        ),
      ),
    )
    return vm
  }

  @Test
  fun 允许前先走生物识别而不是直接下发() {
    val now = System.currentTimeMillis()
    val vm = streamWithApproval(now)
    var requested: String? = null
    val decisions = mutableListOf<String>()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ForegroundSyncState.set(
          SyncState.Running(taskId = "task-1", step = 3, totalSteps = 8, startedAtMillis = 100L),
        )
        MorrowApp(
          stream = vm,
          onApprovalDecision = { id, decision -> decisions += "$id:$decision"; true },
          onApprovalAllowRequest = { id -> requested = id },
        )
      }
    }
    compose.waitForIdle()
    compose.onNodeWithTag("workbench-approvals").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(ApprovalTags.allow("req-1")).performClick()
    compose.waitForIdle()

    assertEquals("必须先弹生物识别", "req-1", requested)
    assertTrue("验证通过前不得下发 once", decisions.isEmpty())
  }

  @Test
  fun 审批事件接到审批页并成功下发once() {
    val now = System.currentTimeMillis()
    val vm = streamWithApproval(now)
    val calls = mutableListOf<Pair<String, String>>()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ForegroundSyncState.set(
          SyncState.Running(taskId = "task-1", step = 3, totalSteps = 8, startedAtMillis = 100L),
        )
        MorrowApp(
          stream = vm,
          onApprovalDecision = { id, decision -> calls += id to decision; true },
        )
      }
    }
    compose.waitForIdle()

    // 工位徽标来自同一份 pendingApprovals → 点进去能看到真实卡片
    compose.onNodeWithTag("workbench-approvals").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(ApprovalTags.card("req-1")).assertIsDisplayed()

    compose.onNodeWithTag(ApprovalTags.allow("req-1")).performClick()
    compose.waitForIdle()

    assertEquals("手机端只能回 once", listOf("req-1" to "once"), calls)
    // 已下发成功的卡片乐观移除（桌面随后还会回 permission_resolved）
    compose.onAllNodesWithTag(ApprovalTags.card("req-1")).assertCountEquals(0)
  }

  private fun streamWithThread(): EventStreamViewModel {
    val vm = EventStreamViewModel(CoroutineScope(StandardTestDispatcher()), applyMs = 0L) { 100L }
    vm.onRawEvents(
      listOf(
        mapOf(
          "type" to "run_started", "seq" to 1L, "sessionId" to "s1", "runId" to "r1", "at" to 100L,
          "userMessage" to mapOf("text" to "把 Q3 做复盘"),
          "assistantMessageId" to "m1",
        ),
        mapOf(
          "type" to "message_completed", "seq" to 2L, "sessionId" to "s1", "runId" to "r1", "at" to 101L,
          "messageId" to "m1",
          "message" to mapOf("id" to "m1", "role" to "assistant", "text" to "好的，我先看数据", "createdAt" to 101L),
        ),
      ),
    )
    return vm
  }

  @Test
  fun 事件流消息接到对话页() {
    val vm = streamWithThread()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ForegroundSyncState.set(
          SyncState.Running(taskId = "task-1", step = 3, totalSteps = 8, startedAtMillis = 100L),
        )
        MorrowApp(stream = vm)
      }
    }
    compose.waitForIdle()
    compose.onNodeWithTag("workbench.openThread").performClick()
    compose.waitForIdle()

    // 用户消息与助手消息都来自事件流，不是样例数据
    compose.onNodeWithText("把 Q3 做复盘").assertIsDisplayed()
    compose.onNodeWithText("好的，我先看数据").assertIsDisplayed()
  }

  @Test
  fun 对话页发送走messageSend而不是本地回显() {
    val vm = streamWithThread()
    var sent: Pair<String, String>? = null
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ForegroundSyncState.set(
          SyncState.Running(taskId = "task-1", step = 3, totalSteps = 8, startedAtMillis = 100L),
        )
        MorrowApp(
          stream = vm,
          // 生产里 ok 由桌面 result 帧决定；这里立即回执，验的是「下发的是当前会话 + 原文」。
          onSendMessage = { sessionId, text, onResult ->
            sent = sessionId to text
            onResult(true, null)
          },
        )
      }
    }
    compose.waitForIdle()
    compose.onNodeWithTag("workbench.openThread").performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(ComposerTags.FIELD).performTextInput("继续")
    compose.waitForIdle()
    compose.onNodeWithTag(ThreadTags.SEND_BUTTON).performClick()
    compose.waitForIdle()

    assertEquals("必须是当前会话", "s1", sent?.first)
    assertEquals("继续", sent?.second)
  }
}

// ───── WorkbenchProjectionTest.kt ─────
/**
 * 投影层：`MorrowUiState` → `WorkbenchData`。
 *
 * 重点是两条空态铁律（C1 已立、这层不能破）：
 * ①`today` 为 null = 「不知道」，不是「今天零活动」；
 * ②`artifacts` 的 null / emptyList 含义不同，不能抹平。
 *
 * 以及「接线之后 C1 原有测试是否失效」—— 这个必须实测，不能推断。
 */
class WorkbenchProjectionTest {

  private val online = Connection(phase = ConnectionPhase.Online, deviceName = "STUDIO-PC")
  private val unpaired = Connection(phase = ConnectionPhase.Unpaired)

  /** 造一个「今天跑过一轮」的 state */
  private fun stateWithOneRun(): MorrowUiState {
    var s = MorrowUiState()
    s = EventReducer.apply(s, studio.morrow.android.EventAction.RunStarted(
      "s1", "r1", "问", "m1", 100L,
    ), seq = 1L)
    s = EventReducer.apply(s, studio.morrow.android.EventAction.UsageReported(1200L, true), seq = 2L)
    s = EventReducer.apply(s, studio.morrow.android.EventAction.RunFinished("s1", "r1", "complete"), seq = 3L)
    return s
  }

  // ═══════════ 今日统计 ═══════════

  @Test
  fun 未连接时今日统计为null不显示假零() {
    val s = stateWithOneRun()
    val data = WorkbenchProjection.project(s, unpaired)
    assertNull("未连接 → 不知道，不能显示四个 0", data.today)
  }

  @Test
  fun 已连接但今天零事件时也为null() {
    val data = WorkbenchProjection.project(MorrowUiState(), online)
    assertNull("连上了但还没收到活动事件 → 仍然不知道", data.today)
  }

  @Test
  fun 已连接且有活动时给真实数字() {
    val data = WorkbenchProjection.project(stateWithOneRun(), online)
    val t = data.today
    assertNotNull(t)
    assertEquals("一轮", 1, t!!.rounds)
    assertEquals("1200 token", 1200L, t.tokens)
    assertEquals("完成一次", 1, t.completed)
    assertEquals("已结束不占运行中", 0, t.running)
    assertEquals(0, t.failed)
  }

  @Test
  fun 运行中的轮次计入running() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, studio.morrow.android.EventAction.RunStarted(
      "s1", "r1", "问", "m1", 100L,
    ), seq = 1L)
    val data = WorkbenchProjection.project(s, online)
    assertEquals("还在跑", 1, data.today!!.running)
    assertEquals("还没完成", 0, data.today!!.completed)
  }

  @Test
  fun 失败的轮次计入failed() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, studio.morrow.android.EventAction.RunStarted("s1", "r1", "q", "m1", 1L), seq = 1L)
    s = EventReducer.apply(s, studio.morrow.android.EventAction.RunFinished("s1", "r1", "error"), seq = 2L)
    val t = WorkbenchProjection.project(s, online).today!!
    assertEquals(0, t.completed)
    assertEquals(1, t.failed)
  }

  // ═══════════ 产物 ═══════════

  @Test
  fun 未收到产物事件时产物为空列表() {
    // 未连接：界面走空态，不应该拿到任何条目
    val data = WorkbenchProjection.project(MorrowUiState(), unpaired)
    assertTrue("未连接时不显示产物", data.artifacts.isEmpty())
  }

  @Test
  fun 产出事件后条目逐字段正确() {
    val s = MorrowUiState(
      artifacts = listOf(
        IgnoreBridges.ArtifactView("q3.html", "HTML", 2048L, 500L),
        IgnoreBridges.ArtifactView("a.zip", "FILE", null, 600L),
      ),
    )
    val data = WorkbenchProjection.project(s, online)
    assertEquals(2, data.artifacts.size)
    assertEquals("q3.html", data.artifacts[0].name)
    assertEquals("HTML", data.artifacts[0].format)
    assertEquals(2048L, data.artifacts[0].bytes)
    assertEquals(500L, data.artifacts[0].atMillis)
  }

  @Test
  fun 拿不到体积时渲染成破折号而不是0B() {
    // 投影层用 -1 表示「拿不到」只是约定的一半 —— 另一半在渲染层。
    // 这条测试守住两端的接合：若 bytes() 里 coerceAtLeast(0) 把 -1 变成 0，
    // 用户会看到「0B」，读成「这个文件是空的」，而那是个错误结论。
    val fmt = WorkbenchFormat
    assertEquals("—", fmt.artifactSize(-1L))
    assertEquals("—", fmt.artifactSize(WorkbenchProjection.BYTES_UNKNOWN))
    // 真实空文件仍然是 0B —— 不能把「确实为空」也变成「不知道」
    assertEquals("0B", fmt.artifactSize(0L))
    assertEquals("1.0K", fmt.artifactSize(1024L))
    // 通用 bytes() 不受影响（C1 立下的「负数按 0」行为保持不变）
    assertEquals("0B", fmt.bytes(-100L))
  }

  @Test
  fun 拿不到体积时用负一而不是零() {
    val s = MorrowUiState(
      artifacts = listOf(IgnoreBridges.ArtifactView("a.zip", "FILE", null, 1L)),
    )
    val data = WorkbenchProjection.project(s, online)
    assertEquals(
      "0 会被读成「空文件」；-1 让渲染层显示 —",
      WorkbenchProjection.BYTES_UNKNOWN, data.artifacts[0].bytes,
    )
  }

  // ═══════════ 运行中任务 ═══════════

  @Test
  fun 没有前台运行时running为null() {
    val data = WorkbenchProjection.project(stateWithOneRun(), online)
    assertNull("轮次已结束", data.running)
  }

  @Test
  fun 前台会话在跑时running有值() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, studio.morrow.android.EventAction.RunStarted("s1", "r1", "q", "m1", 100L), seq = 1L)
    s = EventReducer.selectSession(s, "s1")
    val data = WorkbenchProjection.project(s, online)
    val r = data.running
    assertNotNull(r)
    assertEquals("runId 当任务标识", "r1", r!!.taskId)
    assertEquals(100L, r.startedAtMillis)
  }

  @Test
  fun 未连接时即便有run也不显示running() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, studio.morrow.android.EventAction.RunStarted("s1", "r1", "q", "m1", 1L), seq = 1L)
    s = EventReducer.selectSession(s, "s1")
    assertNull("未连接时不能显示「正在跑」", WorkbenchProjection.project(s, unpaired).running)
  }

  @Test
  fun 总步数未知时进度不是零而是不确定() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, studio.morrow.android.EventAction.RunStarted("s1", "r1", "q", "m1", 1L), seq = 1L)
    s = EventReducer.selectSession(s, "s1")
    val r = WorkbenchProjection.project(s, online).running!!
    assertNull("总步数未知 → percent 为 null（界面显示不确定）", r.percent)
  }

  // ═══════════ 待审批 ═══════════

  @Test
  fun 没有待审批时为null() {
    assertNull(WorkbenchProjection.project(stateWithOneRun(), online).approvals)
  }

  @Test
  fun 有审批时计数正确() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, studio.morrow.android.EventAction.PermissionRequested("s1", "p1", "run_command"), seq = 1L)
    s = EventReducer.apply(s, studio.morrow.android.EventAction.PermissionRequested("s1", "p2", "write_file"), seq = 2L)
    val a = WorkbenchProjection.project(s, online).approvals
    assertNotNull(a)
    assertEquals(2, a!!.count)
  }

  @Test
  fun 未连接时不显示待审批() {
    var s = MorrowUiState()
    s = EventReducer.apply(s, studio.morrow.android.EventAction.PermissionRequested("s1", "p1", "run_command"), seq = 1L)
    assertNull(WorkbenchProjection.project(s, unpaired).approvals)
  }

  // ═══════════ 与 C1 原有数据源的兼容 ═══════════

  @Test
  fun 投影结果能直接喂给WorkbenchScreen的渲染逻辑() {
    // 这条测的是「接线不破 C1」：投影出来的 WorkbenchData 必须能被
    // C1 已有的格式化函数处理，不出现异常值
    val data = WorkbenchProjection.project(stateWithOneRun(), online)
    data.today!!.let { t ->
      assertTrue("轮次非负", t.rounds >= 0)
      assertTrue("token 非负", t.tokens >= 0)
    }
    data.artifacts.forEach { a ->
      assertTrue("体积要么是真实值要么是 -1", a.bytes >= 0 || a.bytes == WorkbenchProjection.BYTES_UNKNOWN)
    }
  }

  @Test
  fun 空state投影出全空态而不是异常() {
    val data = WorkbenchProjection.project(MorrowUiState(), unpaired)
    assertNull(data.today)
    assertNull(data.running)
    assertNull(data.approvals)
    assertTrue(data.artifacts.isEmpty())
    assertEquals(ConnectionPhase.Unpaired, data.connection.phase)
  }

  @Test
  fun 从原始事件流一路到投影端点正确() {
    // 端到端：wire 事件 → parse → reduce → project
    var s = MorrowUiState()
    listOf(
      mapOf("type" to "run_started", "seq" to 1L, "sessionId" to "s1", "runId" to "r1",
        "at" to 100L, "userMessage" to mapOf("text" to "q"), "assistantMessageId" to "m1"),
      mapOf("type" to "usage_updated", "seq" to 2L, "sessionId" to "s1", "runId" to "r1",
        "at" to 101L, "usage" to mapOf("inputTokens" to 500, "outputTokens" to 100)),
      mapOf("type" to "artifacts_updated", "seq" to 3L, "sessionId" to "s1", "runId" to "r1",
        "at" to 102L, "artifacts" to listOf(
          mapOf("title" to "报告.html", "kind" to "html", "content" to "abc", "updatedAt" to 110L),
        )),
    ).forEach { raw ->
      val p = EventParser.parse(raw)!!
      s = EventReducer.apply(s, p.action, p.seq)
    }

    val data = WorkbenchProjection.project(s, online)
    assertEquals("一轮", 1, data.today!!.rounds)
    assertEquals("600 token", 600L, data.today!!.tokens)
    assertEquals("一个产物", 1, data.artifacts.size)
    assertEquals("报告.html", data.artifacts[0].name)
    assertEquals("3 字节", 3L, data.artifacts[0].bytes)
  }
}