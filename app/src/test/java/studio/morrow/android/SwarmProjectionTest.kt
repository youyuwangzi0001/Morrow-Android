package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.morrow.android.IgnoreBridges
import studio.morrow.android.MorrowUiState

/**
 * `super_updated` → 协作页投影。
 *
 * 重点是「桌面说排队就显示排队」：未知/缺字段不能猜成已完成，
 * 否则用户会以为成员已经跑完而不再等待。
 */
class SwarmProjectionTest {

  private fun view(
    index: Int = 0,
    state: String = "running",
    stepCount: Int? = 3,
  ) = IgnoreBridges.MemberView(
    index = index,
    title = "前端重构",
    agent = "frontend",
    agentTitle = "前端",
    state = state,
    stepCount = stepCount,
    note = "正在改登录页",
    summary = null,
    error = null,
  )

  @Test
  fun 字段完整映射到成员卡() {
    val member = SwarmProjection.memberOf(view())
    assertEquals(0, member.index)
    assertEquals("前端重构", member.title)
    assertEquals("frontend", member.agent)
    assertEquals("前端", member.agentTitle)
    assertEquals(MemberState.Running, member.state)
    assertEquals(3, member.stepCount)
    assertEquals("正在改登录页", member.note)
    // 桌面没给 usage/operations，保持空而不是编造数字
    assertEquals(0L, member.usage.tokens)
    assertTrue(member.operations.isEmpty())
  }

  @Test
  fun 桌面状态字面量逐一映射() {
    val expected = mapOf(
      "queued" to MemberState.Queued,
      "pending" to MemberState.Queued,
      "running" to MemberState.Running,
      "done" to MemberState.Completed,
      "completed" to MemberState.Completed,
      "failed" to MemberState.Failed,
      "cancelled" to MemberState.Cancelled,
      "exhausted" to MemberState.Exhausted,
    )
    expected.forEach { (raw, state) ->
      assertEquals("状态 $raw", state, SwarmProjection.stateOf(raw))
    }
  }

  @Test
  fun 未知状态按排队处理而不是已完成() {
    assertEquals(MemberState.Queued, SwarmProjection.stateOf("future-status"))
    assertEquals(MemberState.Queued, SwarmProjection.stateOf(""))
  }

  @Test
  fun 目标与成员来自事件流_客户端设置保留() {
    val state = MorrowUiState(
      members = listOf(view(index = 0), view(index = 1, state = "done")),
      superGoal = "把 Q3 复盘做完",
    )
    val base = SwarmState(goal = "", planVersion = "v1", concurrencyLimit = 7, stuckAlertEnabled = false)
    val projected = SwarmProjection.fromState(state, base)

    assertEquals("把 Q3 复盘做完", projected.goal)
    assertEquals(2, projected.members.size)
    assertEquals(MemberState.Completed, projected.members[1].state)
    // 纯客户端设置不从事件流覆盖
    assertEquals(7, projected.concurrencyLimit)
    assertEquals(false, projected.stuckAlertEnabled)
  }

  @Test
  fun 没有协作时目标为空字符串而不是编造文案() {
    val projected = SwarmProjection.fromState(MorrowUiState(), SwarmState(goal = "", planVersion = "v1"))
    assertTrue(projected.members.isEmpty())
    assertEquals("", projected.goal)
    assertNull(projected.members.firstOrNull() as MemberProgress?)
  }

  @Test
  fun 桌面下发的卡住告警映射到成员卡() {
    // 回归：桌面 StuckAlert 有 12 个字段，投影时一个都不发；
    // Android 侧的卡住状态机整块空转，卡住卡永远是空的。
    val member = SwarmProjection.memberOf(
      IgnoreBridges.MemberView(
        index = 0,
        title = "链接检查",
        agent = "verify",
        agentTitle = "校验",
        state = "running",
        stepCount = 14,
        note = null,
        summary = null,
        error = null,
        stuck = IgnoreBridges.MemberStuckView(
          reason = "连续 3 步无新成果",
          stepsSinceProgress = 3,
          lastOperation = "docs:check",
        ),
      )
    )

    val stuck = member.stuck
    assertTrue("必须认出来卡住了", stuck != null)
    assertEquals("连续 3 步无新成果", stuck!!.reason)
    assertEquals(3, stuck.stepsSinceProgress)
    assertEquals("docs:check", stuck.lastOperation)
    assertTrue("describe 要能说清依据", stuck.describe().contains("连续 3 步无新成果"))
  }

  @Test
  fun 没有告警时成员不是卡住态() {
    assertNull(SwarmProjection.memberOf(view()).stuck)
  }
}