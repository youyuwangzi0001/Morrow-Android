package studio.morrow.android

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import studio.morrow.android.NotifyChannels
import studio.morrow.android.NotifyPhrase
import studio.morrow.android.RelayNotifications
import studio.morrow.android.MorrowUiState
import studio.morrow.android.UiApproval

/**
 * 通知桥的 Android 侧：一条新审批真的落进通知栏。
 *
 * 触发条件由 NotificationSignalsTest 覆盖；这里只证明「条件满足后
 * 确实调用了系统通知」，防止桥接层被静默跳过（此前的缺陷正是如此）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayNotificationsTest {

  private val app: Context = ApplicationProvider.getApplicationContext()
  private val manager = app.getSystemService(NotificationManager::class.java)

  private fun approval(id: String) = UiApproval(
    requestId = id, toolName = "write_file", sessionId = "s1", requestedAtMillis = 1_000L,
  )

  @Test
  fun 新审批投递到通知栏且重连不重复() {
    NotifyChannels.register(app)
    val relay = RelayNotifications(app) { 5_000L }
    val after = MorrowUiState(pendingApprovals = mapOf("ap-1" to approval("ap-1")))

    relay.onBatch(MorrowUiState(), after, emptyList())
    val posted = shadowOf(manager).allNotifications
    assertEquals(1, posted.size)
    assertEquals(NotifyPhrase.APPROVAL_TITLE, posted.single().extras.getString("android.title"))

    // 重连重放同一批审批：不能第二次响
    relay.onBatch(MorrowUiState(), after, emptyList())
    assertEquals(1, shadowOf(manager).allNotifications.size)
  }

  @Test
  fun 一轮结束发完成通知() {
    NotifyChannels.register(app)
    val relay = RelayNotifications(app) { 121_000L }
    val before = MorrowUiState(
      runs = mapOf("s1" to studio.morrow.android.RunState("r1", startedAtMillis = 1_000L)),
    )
    relay.onBatch(
      before,
      before.copy(runs = emptyMap()),
      listOf(mapOf("type" to "run_finished", "sessionId" to "s1", "reason" to "completed")),
    )
    val posted = shadowOf(manager).allNotifications
    assertEquals(1, posted.size)
    assertEquals(NotifyPhrase.COMPLETED_TITLE, posted.single().extras.getString("android.title"))
    assertTrue(
      "正文应带耗时",
      posted.single().extras.getString("android.text")?.contains("2 分") == true,
    )
  }
}