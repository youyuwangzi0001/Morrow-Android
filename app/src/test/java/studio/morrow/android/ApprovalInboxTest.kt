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
 * D-10：审批决策必须是类型，不是散落的字面量。
 *
 * 上行协议里 `decision` 只有 `"once"` / `"deny"` 两个合法值，此前它们
 * 散落在 5 个文件的 8 处。拼错一个字母不会编译失败，桌面会当成未知决策——
 * 而审批是唯一「不处理就卡住别人」的类别。
 */
class ApprovalDecisionTest {

  @Test
  fun 线上值与桌面协议一字不差() {
    // 这两个字符串是协议契约。改了它等于改协议，必须显式确认。
    assertEquals("once", ApprovalDecision.Once.wire)
    assertEquals("deny", ApprovalDecision.Deny.wire)
  }

  @Test
  fun 从线上值还原认识的两个() {
    assertEquals(ApprovalDecision.Once, ApprovalDecision.fromWire("once"))
    assertEquals(ApprovalDecision.Deny, ApprovalDecision.fromWire("deny"))
  }

  @Test
  fun 未知值一律当作拒绝() {
    // 这是安全方向问题：把不认识的东西当成「允许」，
    // 等于让一个拼写错误变成一次未经审批的操作。
    assertEquals(ApprovalDecision.Deny, ApprovalDecision.fromWire("allow"))
    assertEquals(ApprovalDecision.Deny, ApprovalDecision.fromWire(""))
    assertEquals(ApprovalDecision.Deny, ApprovalDecision.fromWire(null))
    assertEquals(ApprovalDecision.Deny, ApprovalDecision.fromWire("ONCE"))
  }
}

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