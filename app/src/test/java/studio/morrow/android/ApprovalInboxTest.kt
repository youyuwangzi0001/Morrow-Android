package studio.morrow.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.morrow.android.ApprovalActionReceiver
import studio.morrow.android.ApprovalDecisionInbox

/**
 * 通知「拒绝」的收件箱。
 *
 * 关键场景不是「有连接时能发」——那是显然的；而是**通知点的拒绝发生在
 * 没有 Activity、甚至进程刚被拉起的时候**。只存内存会随进程回收丢掉，
 * 用户以为拒绝生效了，桌面却还在等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApprovalInboxTest {

  private val app: Context = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    ApprovalDecisionInbox.clearForTest(app)
  }

  @Test
  fun 无连接时决策落盘并能恢复() {
    ApprovalDecisionInbox.registerSink(null)
    assertFalse(ApprovalDecisionInbox.offer("ap-1", allow = false, nowMillis = 1_000L, context = app))

    // 模拟进程被回收：内存清空，磁盘还在
    ApprovalDecisionInbox.clearForTest(app, clearDisk = false)
    assertTrue(ApprovalDecisionInbox.pending().isEmpty())

    ApprovalDecisionInbox.restore(app)
    val pending = ApprovalDecisionInbox.pending().single()
    assertEquals("ap-1", pending.approvalId)
    assertFalse(pending.allow)
    assertEquals(1_000L, pending.atMillis)
  }

  @Test
  fun 连上后_flush_发出并清空磁盘() {
    ApprovalDecisionInbox.registerSink(null)
    ApprovalDecisionInbox.offer("ap-2", allow = false, nowMillis = 2_000L, context = app)

    val sent = mutableListOf<String>()
    ApprovalDecisionInbox.registerSink { id, allow -> sent += "$id:${allow}"; true }
    ApprovalDecisionInbox.restore(app)
    assertEquals(1, ApprovalDecisionInbox.flush(app))
    assertEquals(listOf("ap-2:false"), sent)
    assertTrue(ApprovalDecisionInbox.pending().isEmpty())

    // 冷启动不该把已经发过的决策再发一遍
    ApprovalDecisionInbox.restore(app)
    assertTrue(ApprovalDecisionInbox.pending().isEmpty())
  }

  @Test
  fun 通知拒绝广播进入收件箱() {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("morrow://approvals?focus=ap-9&decision=deny"))
    ApprovalActionReceiver().onReceive(app, intent)

    val pending = ApprovalDecisionInbox.pending().single()
    assertEquals("ap-9", pending.approvalId)
    assertFalse("广播只能拒绝", pending.allow)
  }

  @Test
  fun 通知里的允许不走广播() {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("morrow://approvals?focus=ap-9&decision=allow"))
    ApprovalActionReceiver().onReceive(app, intent)
    assertTrue("允许必须推开应用过生物识别", ApprovalDecisionInbox.pending().isEmpty())
  }
}