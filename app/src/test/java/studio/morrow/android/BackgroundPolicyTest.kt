package studio.morrow.android

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.morrow.android.ForegroundSyncState
import studio.morrow.android.SyncState

/**
 * 后台策略：跟盯中保持连接，非跟盯切后台断开。
 *
 * 之前的实现是「一进 onStop 就断」，于是切后台后这一轮的事件全部收不到；
 * 前台服务虽然把进程保住了，连接却是断的 —— 常驻通知成了唯一的
 * 进度来源，审批/完成事件要等用户切回前台才补。
 *
 * 断言用 `stopCallCount` 而不是 `isRunning`：Robolectric 没有凭据，
 * 连接循环一进 runLoop 自己就会把 `isRunning` 置 false，用后者测不出
 * `onStop` 里到底有没有调用 stop（见 RelayConnectionController 注释）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundPolicyTest {

  @After
  fun tearDown() {
    ForegroundSyncState.set(SyncState.Idle)
  }

  @Test
  fun 跟盯中切后台不主动断开() {
    ForegroundSyncState.set(
      SyncState.Running(taskId = "task-1", step = 1, totalSteps = 3, startedAtMillis = 1_000L),
    )
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      var before = 0
      scenario.onActivity { before = it.relayControllerForTest().stopCallCount }
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.onActivity { activity ->
        assertEquals(
          "跟盯中应由前台服务保活，onStop 不应断开连接",
          before,
          activity.relayControllerForTest().stopCallCount,
        )
      }
    }
  }

  @Test
  fun 非跟盯切后台断开连接() {
    ForegroundSyncState.set(SyncState.Idle)
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      var before = 0
      scenario.onActivity { before = it.relayControllerForTest().stopCallCount }
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.onActivity { activity ->
        assertEquals(
          "没有跟盯任务时后台连接只会耗电，必须断开",
          before + 1,
          activity.relayControllerForTest().stopCallCount,
        )
      }
    }
  }
}