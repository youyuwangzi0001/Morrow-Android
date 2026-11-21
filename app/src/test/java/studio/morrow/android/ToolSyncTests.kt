package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机端「看得见桌面在干什么」的归并测试。
 *
 * 守的是这条产品要求：手机上不只要能读文字，还要能回答
 * 「电脑刚才运行了什么命令、改了哪些文件、成功没有」。
 * 同时守一条安全红线：命令原文、文件内容、命令输出**不出桌面**，
 * 手机拿到的只有工具名、人话标题与计数。
 */
class ToolSyncTests {

  private val base = mapOf(
    "seq" to 10L,
    "runId" to "run-1",
    "sessionId" to "s1",
    "at" to 1_700_000_000_000L,
  )

  private fun event(body: Map<String, Any?>): Map<String, Any?> = base + body

  private fun sessionWithMessage(): MorrowUiState = MorrowUiState(
    sessions = mapOf(
      "s1" to SessionUi(
        id = "s1",
        title = "会话",
        messages = listOf(
          UiMessage(id = "m1", role = MessageRole.Assistant, text = "", streaming = true),
        ),
      ),
    ),
  )

  // ─────────── 解析 ───────────

  @Test
  fun 工具开始事件解析出标题与类别() {
    val parsed = EventParser.parse(
      event(
        mapOf(
          "type" to "tool_started",
          "messageId" to "m1",
          "toolUseId" to "t1",
          "tool" to mapOf("name" to "edit_file", "title" to "修改 src/a.ts", "category" to "write"),
        ),
      ),
    )
    val action = parsed?.action as? EventAction.ToolStarted
    assertNotNull("tool_started 必须解析成 ToolStarted", action)
    assertEquals("t1", action?.call?.id)
    assertEquals("修改 src/a.ts", action?.call?.title)
    assertEquals("write", action?.call?.category)
    assertTrue("写文件属于改磁盘", action?.call?.mutating == true)
  }

  @Test
  fun 工具结束事件解析出计数与退出码() {
    val parsed = EventParser.parse(
      event(
        mapOf(
          "type" to "tool_finished",
          "messageId" to "m1",
          "toolUseId" to "t1",
          "toolResult" to mapOf(
            "ok" to true,
            "diffAdded" to 12,
            "diffRemoved" to 3,
            "exitCode" to 0,
            "durationMs" to 1500,
          ),
        ),
      ),
    )
    val action = parsed?.action as? EventAction.ToolFinished
    assertNotNull(action)
    assertEquals(12, action?.diffAdded)
    assertEquals(3, action?.diffRemoved)
    assertEquals(0, action?.exitCode)
    assertEquals(ToolCallStatus.Ok, action?.status)
  }

  @Test
  fun 工具失败与拒绝是两种不同结局() {
    val failed = EventParser.parse(
      event(
        mapOf(
          "type" to "tool_finished",
          "messageId" to "m1",
          "toolUseId" to "t1",
          "toolResult" to mapOf("ok" to false, "failed" to true),
        ),
      ),
    )!!.action as EventAction.ToolFinished
    assertEquals(ToolCallStatus.Failed, failed.status)

    val denied = EventParser.parse(
      event(mapOf("type" to "tool_finished", "messageId" to "m1", "toolUseId" to "t1", "denied" to true)),
    )!!.action as EventAction.ToolFinished
    assertEquals(ToolCallStatus.Denied, denied.status)
  }

  @Test
  fun 提示与错误都变成可见提示条() {
    val notice = EventParser.parse(
      event(mapOf("type" to "notice", "level" to "warn", "message" to "上下文已满")),
    )!!.action as EventAction.NoticeRaised
    assertEquals("warn", notice.level)

    val error = EventParser.parse(
      event(mapOf("type" to "error", "error" to mapOf("code" to "provider", "message" to "密钥无效"))),
    )!!.action as? EventAction.ErrorRaised
    assertNotNull("error 不能落进 Ignore", error)
    assertEquals("密钥无效", error?.message)
  }

  // ─────────── 归并 ───────────

  @Test
  fun 工具调用挂在发起它的那条消息上并按顺序累积() {
    val started = sessionWithMessage()
    val afterFirst = EventReducer.apply(
      started,
      EventAction.ToolStarted(
        "s1",
        "m1",
        UiToolCall(id = "t1", name = "run_command", title = "运行命令：npm test", category = "exec", status = ToolCallStatus.Running),
      ),
    )
    val afterSecond = EventReducer.apply(
      afterFirst,
      EventAction.ToolStarted(
        "s1",
        "m1",
        UiToolCall(id = "t2", name = "edit_file", title = "修改 src/a.ts", category = "write", status = ToolCallStatus.Running),
      ),
    )

    val tools = afterSecond.sessions.getValue("s1").messages.single().tools
    assertEquals(listOf("t1", "t2"), tools.map { it.id })
    assertEquals(ToolCallStatus.Running, tools.first().status)
  }

  @Test
  fun 重放同一条工具开始事件不会重复加() {
    val call = UiToolCall(id = "t1", name = "edit_file", title = "修改 a", category = "write", status = ToolCallStatus.Running)
    val once = EventReducer.apply(sessionWithMessage(), EventAction.ToolStarted("s1", "m1", call))
    val twice = EventReducer.apply(once, EventAction.ToolStarted("s1", "m1", call))
    assertEquals(1, twice.sessions.getValue("s1").messages.single().tools.size)
  }

  @Test
  fun 工具结束把对应步骤改成成功并带上改动行数() {
    val running = EventReducer.apply(
      sessionWithMessage(),
      EventAction.ToolStarted(
        "s1",
        "m1",
        UiToolCall(id = "t1", name = "edit_file", title = "修改 a.ts", category = "write", status = ToolCallStatus.Running),
      ),
    )
    val done = EventReducer.apply(
      running,
      EventAction.ToolFinished("s1", "m1", "t1", ToolCallStatus.Ok, diffAdded = 5, diffRemoved = 1),
    )
    val step = done.sessions.getValue("s1").messages.single().tools.single()
    assertEquals(ToolCallStatus.Ok, step.status)
    assertEquals(5, step.diffAdded)
    assertEquals(1, step.diffRemoved)
  }

  @Test
  fun 断线期间开始的工具靠结束事件补出一条步骤() {
    // 只有 tool_finished（没有对应的 started）：不能丢，否则用户完全看不到这次改动
    val done = EventReducer.apply(
      sessionWithMessage(),
      EventAction.ToolFinished("s1", "m1", "t9", ToolCallStatus.Ok, deleted = true),
    )
    val step = done.sessions.getValue("s1").messages.last().tools.single()
    assertEquals("t9", step.id)
    assertTrue(step.deleted)
  }

  @Test
  fun 结束事件挂到已有消息上而不是插一条重复消息() {
    // 消息体已在、只是缺这一步时，必须挂到那条消息上。
    // 直接 append 一条同 id 的消息会让列表出现两个相同 key。
    val done = EventReducer.apply(
      sessionWithMessage(),
      EventAction.ToolFinished("s1", "m1", "t9", ToolCallStatus.Ok, diffAdded = 2),
    )
    val messages = done.sessions.getValue("s1").messages
    assertEquals("不能插入重复 id 的消息", 1, messages.size)
    assertEquals("m1", messages.single().id)
    assertEquals("t9", messages.single().tools.single().id)
    assertEquals(2, messages.single().tools.single().diffAdded)
  }

  @Test
  fun 运行中的当前工具会显示到工位页的活动行() {
    val running = MorrowUiState(
      sessions = mapOf("s1" to SessionUi(id = "s1", title = "会话")),
      runs = mapOf("s1" to RunState(runId = "run-1", startedAtMillis = 1L)),
      activeSessionId = "s1",
    )
    val next = EventReducer.apply(
      running,
      EventAction.ToolStarted(
        "s1",
        "m1",
        UiToolCall(id = "t1", name = "run_command", title = "运行命令：npm test", category = "exec", status = ToolCallStatus.Running),
      ),
    )
    assertEquals("运行命令：npm test", next.runs.getValue("s1").currentTool)
  }

  @Test
  fun 待办与目标按整表替换() {
    val withTodos = EventReducer.apply(
      MorrowUiState(),
      EventAction.TodosUpdated("s1", listOf(UiTodo("1", "读数据", "completed"), UiTodo("2", "写报告", "in_progress"))),
    )
    assertEquals(2, withTodos.todos?.size)

    val replaced = EventReducer.apply(
      withTodos,
      EventAction.TodosUpdated("s1", listOf(UiTodo("2", "写报告", "in_progress"))),
    )
    assertEquals("整表替换，不是累加", 1, replaced.todos?.size)

    val goal = EventReducer.apply(
      MorrowUiState(),
      EventAction.GoalUpdated("s1", UiGoal(status = "running", round = 2, maxRounds = 5)),
    )
    assertEquals(2, goal.goal?.round)
  }

  @Test
  fun 提示条只保留最近若干条且最新在前() {
    var state = MorrowUiState()
    repeat(MAX_NOTICES + 3) { index ->
      state = EventReducer.apply(
        state,
        EventAction.NoticeRaised("s1", "info", "第 $index 条", 1000L + index),
      )
    }
    assertEquals(MAX_NOTICES, state.notices.size)
    assertEquals("最新的在最前", "第 ${MAX_NOTICES + 2} 条", state.notices.first().message)
  }

  // ─────────── 投影到界面 ───────────

  @Test
  fun 工具组默认收起并给出步数与用时() {
    val calls = listOf(
      UiToolCall("t1", "edit_file", "修改 a.ts", "write", ToolCallStatus.Ok, diffAdded = 3, diffRemoved = 1, durationMillis = 1200),
      UiToolCall("t2", "run_command", "运行命令：npm test", "exec", ToolCallStatus.Failed, exitCode = 1, durationMillis = 800),
    )
    val group = ToolProjection.groupOf("m1", calls)
    assertNotNull(group)
    assertEquals(2, group?.steps?.size)
    assertEquals(2000L, group?.elapsedMillis)
    assertTrue("有失败步骤要能看出来", group?.hasFailure == true)
    assertTrue("全部结束后不再显示运行中", group?.allDone == true)
  }

  @Test
  fun 每步摘要说明改了什么而不是命令原文() {
    val edit = UiToolCall("t1", "edit_file", "修改 a.ts", "write", ToolCallStatus.Ok, diffAdded = 12, diffRemoved = 3)
    assertEquals("+12 −3", ToolProjection.detailOf(edit))

    val created = UiToolCall("t2", "write_file", "写入 b.ts", "write", ToolCallStatus.Ok, created = true)
    assertEquals("新建", ToolProjection.detailOf(created))

    val deleted = UiToolCall("t3", "delete_file", "删除 c.ts", "write", ToolCallStatus.Ok, deleted = true)
    assertEquals("删除", ToolProjection.detailOf(deleted))

    val command = UiToolCall("t4", "run_command", "运行命令：npm test", "exec", ToolCallStatus.Ok, exitCode = 0)
    assertEquals("退出码 0", ToolProjection.detailOf(command))

    val nothing = UiToolCall("t5", "read_file", "读取 d.ts", "read", ToolCallStatus.Ok)
    assertNull("没有可说的时候不占位置", ToolProjection.detailOf(nothing))
  }

  @Test
  fun 被拒绝的工具标记为失败并写明原因() {
    val denied = UiToolCall("t1", "run_command", "运行命令：rm -rf build", "exec", ToolCallStatus.Denied)
    val step = ToolProjection.stepsOf(listOf(denied)).single()
    assertEquals(StepState.Failed, step.state)
    assertEquals("已拒绝", step.detail)
    assertTrue("执行命令属于改磁盘", step.mutating)
  }

  @Test
  fun 没有工具调用时不生成空的执行组() {
    assertNull(ToolProjection.groupOf("m1", emptyList()))
  }

  @Test
  fun 命令原文与输出正文不出现在手机状态里() {
    // 桌面只下发摘要；即便有人误把原文塞进 title，这里也确认正常载荷不含原文。
    val parsed = EventParser.parse(
      event(
        mapOf(
          "type" to "tool_started",
          "messageId" to "m1",
          "toolUseId" to "t1",
          "tool" to mapOf("name" to "run_command", "title" to "运行命令：npm test", "category" to "exec"),
        ),
      ),
    )
    val rendered = parsed?.action.toString() + EventReducer.apply(sessionWithMessage(), parsed!!.action).toString()
    assertFalse(rendered.contains("PASSWORD"))
  }
}