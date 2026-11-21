package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.morrow.android.RelaySyncTracking
import studio.morrow.android.MorrowUiState
import studio.morrow.android.RunState

/**
 * 前台服务随运行启停。
 *
 * 序列错了会留下一条永远不消失的「正在执行」通知 —— 用户以为任务还在跑，
 * 而实际上桌面早就结束了。
 */
class RelaySyncTrackingTest {

  private val events = mutableListOf<String>()
  private val tracking = RelaySyncTracking(
    onStart = { id, step -> events += "start:$id:$step" },
    onUpdate = { id, step, started -> events += "update:$id:$step:$started" },
    onStop = { events += "stop" },
  )

  private fun running(runId: String = "r1", step: Int = 2) = MorrowUiState(
    runs = mapOf("s1" to RunState(runId, startedAtMillis = 100L, stepCount = step)),
  )

  @Test
  fun 开始一轮时启动服务并随步数更新() {
    tracking.onBatch(running(step = 0))
    assertEquals(listOf("start:r1:0"), events)

    tracking.onBatch(running(step = 3))
    assertEquals("update:r1:3:100", events.last())
  }

  @Test
  fun 一轮结束后停止服务() {
    tracking.onBatch(running())
    tracking.onBatch(MorrowUiState())
    assertEquals("stop", events.last())
  }

  @Test
  fun 没有运行时不重复发停止() {
    tracking.onBatch(MorrowUiState())
    tracking.onBatch(MorrowUiState())
    assertTrue("空状态不应产生任何服务调用", events.isEmpty())
  }
}