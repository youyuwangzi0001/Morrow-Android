package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.morrow.android.NotificationSignal
import studio.morrow.android.NotificationSignals
import studio.morrow.android.IgnoreBridges
import studio.morrow.android.MorrowUiState
import studio.morrow.android.RunState
import studio.morrow.android.UiApproval

/**
 * 通知触发条件。
 *
 * 这一层不碰 Android：先证明「什么时候该发」，再由 RelayNotifications
 * 负责「发成什么样」。通知漏发是用户无感知的缺陷，必须在这里钉住。
 */
class NotificationSignalsTest {

  private fun approval(id: String, at: Long = 1_000L, tool: String = "write_file") =
    UiApproval(requestId = id, toolName = tool, sessionId = "s1", requestedAtMillis = at)

  private fun member(index: Int, state: String) = IgnoreBridges.MemberView(
    index = index, title = "成员 $index", agent = "general", agentTitle = null,
    state = state, stepCount = null, note = null, summary = null, error = null,
  )

  @Test
  fun 只有新审批才产生通知() {
    val before = MorrowUiState(pendingApprovals = mapOf("ap-1" to approval("ap-1")))
    val after = before.copy(
      pendingApprovals = mapOf(
        "ap-1" to approval("ap-1"),
        "ap-2" to approval("ap-2", at = 2_000L, tool = "browser_click"),
      ),
    )
    val signals = NotificationSignals.of(before, after, emptyList(), nowMillis = 5_000L)
    assertEquals(1, signals.size)
    val approval = signals.single() as NotificationSignal.Approval
    assertEquals("ap-2", approval.requestId)
    assertEquals("browser_click", approval.toolName)
  }

  @Test
  fun 正常结束按完成通知并带耗时() {
    val before = MorrowUiState(runs = mapOf("s1" to RunState("r1", startedAtMillis = 1_000L, stepCount = 4)))
    val events = listOf(mapOf("type" to "run_finished", "sessionId" to "s1", "reason" to "completed"))
    val signal = NotificationSignals.of(before, before.copy(runs = emptyMap()), events, nowMillis = 121_000L)
      .single() as NotificationSignal.RunFinished
    assertEquals("r1", signal.runId)
    assertFalse(signal.failed)
    assertEquals(120L, signal.elapsedSeconds)
  }

  @Test
  fun 非正常结束按卡住通知() {
    val before = MorrowUiState(runs = mapOf("s1" to RunState("r1", startedAtMillis = 1_000L)))
    val events = listOf(mapOf("type" to "run_finished", "sessionId" to "s1", "reason" to "max-steps"))
    val signal = NotificationSignals.of(before, before.copy(runs = emptyMap()), events, nowMillis = 2_000L)
      .single() as NotificationSignal.RunFinished
    assertTrue("max-steps 不是正常完成", signal.failed)
  }

  @Test
  fun 成员失败才通知且首连快照不打扰() {
    val snapshot = MorrowUiState(members = listOf(member(0, "failed")))
    assertTrue(
      "首连快照里的历史失败不该再响一次",
      NotificationSignals.of(MorrowUiState(), snapshot, emptyList(), 1L).isEmpty(),
    )

    val before = MorrowUiState(members = listOf(member(0, "running")))
    val after = before.copy(members = listOf(member(0, "failed")))
    val signal = NotificationSignals.of(before, after, emptyList(), 1L).single() as NotificationSignal.MemberStuck
    assertEquals(0, signal.index)
  }

  @Test
  fun 同一轮步数前进才产生进度() {
    val before = MorrowUiState(runs = mapOf("s1" to RunState("r1", 1_000L, stepCount = 2)))
    val advanced = before.copy(runs = mapOf("s1" to RunState("r1", 1_000L, stepCount = 3)))
    assertTrue(
      NotificationSignals.of(before, advanced, emptyList(), 2_000L)
        .single() is NotificationSignal.Progress,
    )

    assertTrue(
      "步数没变不发",
      NotificationSignals.of(before, before.copy(runs = mapOf("s1" to RunState("r1", 1_000L, stepCount = 2))), emptyList(), 2_000L).isEmpty(),
    )
    assertTrue(
      "换了一轮不发（新 run 的第一步不是进度）",
      NotificationSignals.of(before, before.copy(runs = mapOf("s1" to RunState("r2", 1_000L, stepCount = 5))), emptyList(), 2_000L).isEmpty(),
    )
  }

  @Test
  fun 没有变化时不产生任何通知() {
    val state = MorrowUiState()
    assertTrue(NotificationSignals.of(state, state, emptyList(), 1L).isEmpty())
  }
}