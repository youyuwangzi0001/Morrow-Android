package studio.morrow.android

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import studio.morrow.android.MorrowDeepLink
import studio.morrow.android.MorrowDestination
import studio.morrow.android.MorrowNotifier
import studio.morrow.android.NotifyChannels
import studio.morrow.android.NotifyContent
import studio.morrow.android.NotifyDecision
import studio.morrow.android.NotifyImportance
import studio.morrow.android.NotifyKind
import studio.morrow.android.NotifyPolicy
import studio.morrow.android.notificationId
import studio.morrow.android.NotifyActionId
import studio.morrow.android.NotifyPhrase
import android.content.Intent
import android.net.Uri
import studio.morrow.android.ApprovalActionReceiver
import studio.morrow.android.ApprovalActions
import studio.morrow.android.ApprovalDecisionInbox

// ───── NotifyChannelsTest.kt ─────
/**
 * B4 第二张卡：渠道注册与真实投递（Robolectric）。
 *
 * ## 这组测试能证明什么、不能证明什么
 *
 * 能证明：**渠道真的被创建了、参数与枚举一致、投递真的调到了系统的
 * NotificationManager、汇总反复更新后通知栏只有一条、动作的 PendingIntent
 * 真的挂上了**。这些在 Robolectric 里都是真实对象（真的 NotificationManager
 * 在真的 shadow 里记录），不是 mock。
 *
 * **不能**证明：通知在 Android 8 / 12 / 14 / 15 的真机上一定到达。
 * 那需要真机矩阵（E2）。本机没有可用模拟器，所以本轮把能自动化的
 * 「注册与投递正确」做到尽，剩下的写进报告的限制章节。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifyChannelsTest {

  private lateinit var app: Application
  private lateinit var manager: NotificationManager

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    manager = app.getSystemService(NotificationManager::class.java)
    // D-16 把免打扰计数下沉成进程级单例（两条推送路径必须共用一份），
    // 代价是它跨测试存活 —— 不重置会把上一条用例的计数带进来。
    DigestCounter.resetForTest()
  }

  // ───────────────── 生产入口接线 ─────────────────

  /**
   * 这一项防的是「代码写好了但没人调」——B 轨已经踩过两次同样的坑：
   * B3 第一批 `index.ts` 忘了把 pairing 传给 startRelay，
   * B3 第五批 `index.ts` 忘了调 startLanStack。
   * 两次都是「逻辑正确 + 无人调用 = 功能不存在」，而且单测全绿。
   *
   * 所以这里**不调用** `NotifyChannels.register`，直接问系统要渠道 ——
   * 渠道存在就证明 `MorrowApplication.onCreate` 真的调了。
   */
  @Test
  fun 应用启动时自动注册渠道_生产入口真的接线了() {
    // Robolectric 已经按 Manifest 实例化了 MorrowApplication 并调用 onCreate
    assertTrue(
      "Application 应是 MorrowApplication",
      app is MorrowApplication
    )
    for (kind in NotifyKind.all()) {
      assertNotNull(
        "应用启动后 ${kind.channelId} 必须已存在（说明 onCreate 真的注册了）",
        manager.getNotificationChannel(kind.channelId)
      )
    }
  }


  /**
   * 探针：两条不同的审批必须同时存在。
   *
   * 这里有个真实缺陷要钉住 —— `NotifyContent.groupKey` 的默认值是 `kind.channelId`，
   * 而通知 id 是由 groupKey 的哈希算出来的。于是**所有审批通知共用同一个 id**：
   * 桌面同时等两条批准时，后到的那条会覆盖先到的，用户永远只看到一条。
   * 而审批正是「不处理就卡住别人」的类别 —— 漏掉一条等于让一个 agent 永远停着。
   */
  @Test
  fun 两条不同审批必须同时可见_不能互相覆盖() {
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)

    for (id in listOf("ap-1", "ap-2")) {
      val content = NotifyContent.of(
        NotifyKind.Approval,
        fileName = "deploy.sh",
        deepLink = "morrow://approvals?focus=$id"
      )
      notifier.post(content, NotifyDecision.Deliver(content))
    }

    assertEquals(
      "两条不同的审批必须都在，不能互相覆盖",
      2,
      shadowOf(manager).allNotifications.size
    )
  }

  // ───────────────── 渠道注册 ─────────────────

  @Test
  fun 四类通知各自创建独立渠道() {
    val reg = NotifyChannels.register(app)

    assertEquals("应有 4 个渠道", 4, reg.channelIds.size)
    for (kind in NotifyKind.all()) {
      val channel = manager.getNotificationChannel(kind.channelId)
      assertNotNull("渠道 ${kind.channelId} 必须真的存在", channel)
      assertEquals(kind.channelId, channel!!.id)
      assertEquals("渠道名必须取自枚举", kind.channelName, channel.name.toString())
    }
  }

  @Test
  fun 渠道重要性逐个与枚举一致() {
    NotifyChannels.register(app)
    for (kind in NotifyKind.all()) {
      val channel = manager.getNotificationChannel(kind.channelId)!!
      assertEquals("${kind.channelId} 的重要性", kind.importance, channel.importance)
    }
    // 单独钉住两条最关键的值：它们错了不会报错，只会让用户被骚扰或漏掉审批
    assertEquals(
      NotifyImportance.HIGH,
      manager.getNotificationChannel(NotifyKind.Approval.channelId)!!.importance
    )
    assertEquals(
      NotifyImportance.LOW,
      manager.getNotificationChannel(NotifyKind.Progress.channelId)!!.importance
    )
  }

  @Test
  fun 只有审批渠道尝试突破免打扰() {
    NotifyChannels.register(app)

    val bypassing = NotifyKind.all().filter {
      manager.getNotificationChannel(it.channelId)!!.canBypassDnd()
    }
    assertEquals("只有审批能尝试突破", listOf(NotifyKind.Approval), bypassing)
  }

  @Test
  fun 渠道描述非空_用户能看懂关掉它会怎样() {
    NotifyChannels.register(app)
    for (kind in NotifyKind.all()) {
      val desc = manager.getNotificationChannel(kind.channelId)!!.description
      assertNotNull("${kind.channelId} 应有描述", desc)
      assertTrue("${kind.channelId} 描述不该为空", desc.isNotBlank())
      // 描述是给用户看的，不该出现开发术语
      assertFalse("描述不该含下划线标识符", desc.contains("morrow_"))
    }
  }

  @Test
  fun 进度渠道不挂角标() {
    NotifyChannels.register(app)
    val progress = manager.getNotificationChannel(NotifyKind.Progress.channelId)!!
    assertFalse("每秒更新的进度挂角标等于骚扰", progress.canShowBadge())
    // 其余三类挂角标
    for (kind in NotifyKind.all() - NotifyKind.Progress) {
      assertTrue("${kind.channelId} 应挂角标", manager.getNotificationChannel(kind.channelId)!!.canShowBadge())
    }
  }

  @Test
  fun 重复注册不报错且结果一致() {
    val first = NotifyChannels.register(app)
    val second = NotifyChannels.register(app)
    assertEquals(first.channelIds, second.channelIds)
    assertTrue("重复注册后仍应是干净状态", second.isClean)
    assertEquals("不该多出渠道", 4, manager.notificationChannels.size)
  }

  /**
   * 这一项是本卡最容易被忽略的坑：渠道建立后重要性**改不动**。
   *
   * 模拟一个「上一个版本用 DEFAULT 建过 morrow_approval」的升级场景，
   * 断言 register() 能识别出不一致，而不是静默地以为成功了。
   */
  @Test
  fun 已存在渠道重要性不符时报出来而不是静默成功() {
    // 先按错误的重要性建一个（模拟上个版本留下的渠道）
    manager.createNotificationChannel(
      android.app.NotificationChannel(
        NotifyKind.Approval.channelId,
        "旧名字",
        NotifyImportance.DEFAULT
      )
    )
    // 再走正常注册（代码里期望的是 HIGH）
    val reg = NotifyChannels.register(app)

    // 系统不让重要性升级：这里必须被报出来，而不是静默成功
    assertEquals(
      "重要性不符必须被记录",
      NotifyImportance.DEFAULT,
      reg.importanceMismatches[NotifyKind.Approval.channelId]
    )
    assertFalse(reg.isClean)
    // 且渠道没有被删掉重建（那会抹掉用户在这个渠道上做过的所有设置）
    assertNotNull("不得删除用户已有渠道", manager.getNotificationChannel(NotifyKind.Approval.channelId))
  }

  // ───────────────── 通知 id 稳定性 ─────────────────

  @Test
  fun 同一任务的通知复用同一个_id() {
    val a1 = NotifyContent.of(NotifyKind.Progress, fileName = "报告.docx", groupKey = "task-1")
    val a2 = NotifyContent.of(NotifyKind.Progress, fileName = "别的.docx", groupKey = "task-1")
    val b = NotifyContent.of(NotifyKind.Progress, fileName = "报告.docx", groupKey = "task-2")

    assertEquals("同一任务必须覆盖而不是堆积", a1.notificationId(), a2.notificationId())
    assertTrue("不同任务应有不同 id", a1.notificationId() != b.notificationId())
  }

  @Test
  fun 通知_id_永远为正() {
    // hashCode 可能为负，abs(Int.MIN_VALUE) 仍是负 —— 必须被处理
    val ids = listOf("", "a", "task-1", "\u0000", "很长的任务标识".repeat(20))
      .map { NotifyContent.of(NotifyKind.Completed, groupKey = it).notificationId() }
    for (id in ids) assertTrue("id 必须为正：$id", id > 0)
  }

  // ───────────────── 真实投递 ─────────────────

  @Test
  fun 投递真的到达系统的_NotificationManager() {
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)
    val content = NotifyContent.of(NotifyKind.Completed, fileName = "报告.xlsx")

    val result = notifier.post(content, NotifyDecision.Deliver(content))
    assertTrue("应投递成功", result is MorrowNotifier.PostResult.Posted)

    val posted = shadowOf(manager).allNotifications
    assertEquals("系统里应有一条通知", 1, posted.size)
    val n = posted.first()
    assertEquals(NotifyKind.Completed.channelId, n.channelId)
    assertEquals(NotifyContent.of(NotifyKind.Completed, fileName = "报告.xlsx").title, n.extras.getString(Notification.EXTRA_TITLE))
  }

  @Test
  fun 投递的通知正文不含路径() {
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)
    val content = NotifyContent.of(
      NotifyKind.Completed,
      fileName = "C:\\交付成品\\客户合同-2026.pdf"
    )
    notifier.post(content, NotifyDecision.Deliver(content))

    val text = shadowOf(manager).allNotifications.first()
      .extras.getString(Notification.EXTRA_TEXT).orEmpty()
    assertFalse("正文不得含目录", text.contains("交付成品"))
    assertTrue("正文应含文件名", text.contains("客户合同-2026.pdf"))
  }

  @Test
  fun 汇总反复更新后通知栏只有一条() {
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)
    val policy = NotifyPolicy()

    // 免打扰下连来 5 条
    repeat(5) {
      val content = NotifyContent.of(NotifyKind.Progress, fileName = "第 $it 步.log")
      val decision = policy.decide(content, doNotDisturb = true)
      assertTrue(decision is NotifyDecision.SuppressIntoDigest)
      val result = notifier.postDigest(decision as NotifyDecision.SuppressIntoDigest)
      assertEquals(
        "汇总必须用固定 id",
        NotifyPolicy.DIGEST_ID,
        (result as MorrowNotifier.PostResult.Posted).notificationId
      )
    }

    val posted = shadowOf(manager).allNotifications
    assertEquals("汇总只能有一条，不能堆积", 1, posted.size)
    // 用固定 id 反查：能查到就证明这条汇总确实挂在 DIGEST_ID 上，
    // 而不是散落在若干随机 id 里（随机 id 会让通知栏堆出多条）
    assertNotNull(
      "汇总必须挂在固定 id 上",
      shadowOf(manager).getNotification(NotifyPolicy.DIGEST_ID)
    )
    // 正文反映最新计数
    assertTrue(
      "汇总正文应含最新条数",
      posted.first().extras.getString(Notification.EXTRA_TEXT)!!.contains("5")
    )
  }

  // ───────────────── M-10：汇总要有「全部清除」入口 ─────────────────

  @Test
  fun 汇总通知带全部清除动作() {
    // 回归 M-10：`cancelDigest()` 一直存在，但**没有任何 UI 入口调用它**。
    // 汇总通知会一直挂在通知栏里，用户没有「我全看过了」的手段。
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)
    val policy = NotifyPolicy()

    val content = NotifyContent.of(NotifyKind.Progress, fileName = "第 1 步.log")
    val decision = policy.decide(content, doNotDisturb = true)
    notifier.postDigest(decision as NotifyDecision.SuppressIntoDigest)

    val posted = shadowOf(manager).getNotification(NotifyPolicy.DIGEST_ID)
    assertNotNull("汇总必须已投递", posted)
    val actions = posted!!.actions ?: emptyArray()
    assertEquals("汇总只有一个动作：全部清除", 1, actions.size)
    assertEquals(
      "动作文案必须说清它会做什么",
      NotifyPhrase.DIGEST_ACTION_CLEAR,
      actions[0].title.toString(),
    )
  }

  @Test
  fun 全部清除真的撤掉汇总() {
    // 只断言「有按钮」不够 —— 按钮接了个空实现也会通过。
    // 这里直接驱动接收器，看通知栏里那条是否真的消失。
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)
    val policy = NotifyPolicy()
    val content = NotifyContent.of(NotifyKind.Progress, fileName = "x.log")
    notifier.postDigest(policy.decide(content, doNotDisturb = true) as NotifyDecision.SuppressIntoDigest)
    assertNotNull(
      "前提：汇总已经在通知栏里",
      shadowOf(manager).getNotification(NotifyPolicy.DIGEST_ID),
    )

    DigestActionReceiver().onReceive(
      app,
      android.content.Intent(DigestActionReceiver.ACTION_CLEAR_DIGEST),
    )

    assertNull(
      "点了「全部清除」后汇总必须消失",
      shadowOf(manager).getNotification(NotifyPolicy.DIGEST_ID),
    )
  }

  @Test
  fun 清除接收器不响应其它action() {
    // 防止「任何广播都清通知」这类误伤：action 不匹配就什么都不做。
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)
    val policy = NotifyPolicy()
    val content = NotifyContent.of(NotifyKind.Progress, fileName = "y.log")
    notifier.postDigest(policy.decide(content, doNotDisturb = true) as NotifyDecision.SuppressIntoDigest)

    DigestActionReceiver().onReceive(
      app,
      android.content.Intent("studio.morrow.android.SOMETHING_ELSE"),
    )

    assertNotNull(
      "无关广播不能撤掉汇总",
      shadowOf(manager).getNotification(NotifyPolicy.DIGEST_ID),
    )
  }

  @Test
  fun 免打扰下审批仍然真的发出去_且其余不进单条() {
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)
    val policy = NotifyPolicy()

    // 审批：应真的到系统
    val approval = NotifyContent.of(
      NotifyKind.Approval,
      fileName = "deploy.sh",
      deepLink = "morrow://approvals?focus=ap-1"
    )
    val d1 = policy.decide(approval, doNotDisturb = true)
    assertTrue(d1 is NotifyDecision.Deliver)
    notifier.post(approval, d1 as NotifyDecision.Deliver)

    // 完成：不该有单条
    val done = NotifyContent.of(NotifyKind.Completed, fileName = "a.txt")
    val d2 = policy.decide(done, doNotDisturb = true)
    assertTrue(d2 is NotifyDecision.SuppressIntoDigest)

    val posted = shadowOf(manager).allNotifications
    assertEquals("只有审批这一条", 1, posted.size)
    assertEquals(NotifyKind.Approval.channelId, posted.first().channelId)
  }

  // ───────────────── 动作按钮 ─────────────────

  @Test
  fun 审批通知挂两个动作_且两个_PendingIntent_不相等() {
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)
    val content = NotifyContent.of(
      NotifyKind.Approval,
      fileName = "deploy.sh",
      deepLink = "morrow://approvals?focus=ap-1"
    )
    notifier.post(content, NotifyDecision.Deliver(content))

    val n = shadowOf(manager).allNotifications.first()
    assertEquals("审批应有允许/拒绝两个按钮", 2, n.actions.size)
    assertEquals("允许", n.actions[0].title.toString())
    assertEquals("拒绝", n.actions[1].title.toString())

    // 这一项防的是 Android 上最危险的静默错误：
    // 两个 PendingIntent 若只在 extra 上不同，会被系统判成同一个，
    // 于是点「拒绝」执行「允许」。所以必须断言它们的 Intent data 不同。
    val allowIntent = shadowOf(n.actions[0].actionIntent).savedIntent
    val denyIntent = shadowOf(n.actions[1].actionIntent).savedIntent
    assertTrue("两个动作的 data 必须不同", allowIntent.data.toString() != denyIntent.data.toString())
    assertTrue("允许的 data 带 decision=allow", allowIntent.data.toString().contains("decision=allow"))
    assertTrue("拒绝的 data 带 decision=deny", denyIntent.data.toString().contains("decision=deny"))
    // 允许必须走 Activity（要过生物识别），拒绝走广播
    assertFalse("允许必须走 Activity（生物识别门）", allowIntent.component?.className!!.contains("Receiver"))
    assertTrue("拒绝应走 Receiver（安全方向立即生效）", denyIntent.component?.className!!.contains("ApprovalActionReceiver"))
  }

  @Test
  fun 非审批通知没有动作按钮() {
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)
    for (kind in listOf(NotifyKind.Completed, NotifyKind.Stuck, NotifyKind.Progress)) {
      val content = NotifyContent.of(kind, fileName = "a.txt")
      notifier.post(content, NotifyDecision.Deliver(content))
    }
    for (n in shadowOf(manager).allNotifications) {
      assertEquals("$n 不该有动作", 0, n.actions?.size ?: 0)
    }
  }

  // ───────────────── 渠道被关掉时的行为 ─────────────────

  @Test
  fun 渠道被关掉时返回明确失败_而不是静默丢弃() {
    NotifyChannels.register(app)
    // 模拟用户在本应用设置里关掉「任务完成」渠道
    manager.deleteNotificationChannel(NotifyKind.Completed.channelId)

    val notifier = MorrowNotifier(app)
    val content = NotifyContent.of(NotifyKind.Completed, fileName = "a.txt")
    val result = notifier.post(content, NotifyDecision.Deliver(content))

    assertTrue("渠道不存在时必须明确报错", result is MorrowNotifier.PostResult.ChannelBlocked)
    assertEquals(NotifyKind.Completed.channelId, (result as MorrowNotifier.PostResult.ChannelBlocked).channelId)
    assertEquals("不该有通知被投递", 0, shadowOf(manager).allNotifications.size)
  }

  // ───────────────── 点击落点 ─────────────────

  @Test
  fun 点击通知的落点只认_morrow_深链() {
    NotifyChannels.register(app)
    val notifier = MorrowNotifier(app)

    // 正常深链
    val ok = NotifyContent.of(
      NotifyKind.Approval,
      fileName = "a.sh",
      deepLink = "morrow://approvals?focus=ap-9"
    )
    notifier.post(ok, NotifyDecision.Deliver(ok))
    val okIntent = shadowOf(shadowOf(manager).allNotifications.first().contentIntent).savedIntent
    assertTrue("应落到审批页", okIntent.data.toString().contains("approvals"))
    assertTrue("应带 focus", okIntent.data.toString().contains("ap-9"))

    // 外部 scheme 的深链：必须落回首页，不能把外部字符串当路由
    val bad = NotifyContent.of(NotifyKind.Completed, fileName = "b.txt", deepLink = "https://evil.com/x")
    assertNull("外部链接应被 NotifyContent 直接拒掉", bad.deepLink)
    notifier.post(bad, NotifyDecision.Deliver(bad))
    val badIntent = shadowOf(shadowOf(manager).allNotifications.last().contentIntent).savedIntent
    assertTrue("应回到首页", badIntent.data.toString().contains("workbench"))
  }
}

// ───── NotifyContentTest.kt ─────
/**
 * B4 第一张卡：通知内容最小化。
 *
 * 蓝图验收原文：「通知正文不含文件名以外的内容」。
 *
 * 这条要求最容易被写成「看起来做了」：加个正则脱敏、把路径截短，
 * 然后测试用一个自己造的样本跑一遍就绿了。真正的问题在于
 * **漏掉的那种形态**——所以这里的用例全部是「真实会出现在通知里的敏感物」：
 * 完整路径、命令、API key、对话片段、多行注入。
 */
class NotifyContentTest {

  // ───────────────── 只留基名 ─────────────────

  // ───────────────── M-12：结束原因要能读出来 ─────────────────

  @Test
  fun 三种结束原因给出三种标题() {
    // 回归 M-12：此前 complete / cancelled / max-steps 共用一句「任务完成」。
    // 三者的含义完全不同 —— 取消是你自己按的，跑满上限是**没做完**，
    // 把后者显示成「任务完成」会让用户以为拿到了成品。
    assertEquals("任务完成", NotifyContent.of(NotifyKind.Completed, reason = "complete").title)
    assertEquals("已取消", NotifyContent.of(NotifyKind.Completed, reason = "cancelled").title)
    assertEquals("跑满步数上限", NotifyContent.of(NotifyKind.Completed, reason = "max-steps").title)

    // 未知/缺失原因回退到「任务完成」，不能显示空白标题
    assertEquals("任务完成", NotifyContent.of(NotifyKind.Completed, reason = null).title)
    assertEquals("任务完成", NotifyContent.of(NotifyKind.Completed, reason = "某个没见过的值").title)
  }

  @Test
  fun 结束原因不影响其它三类通知的标题() {
    // reason 只对 Completed 有意义。审批/卡住/进度即使被误传了 reason，
    // 标题也不能被改坏 —— 否则「等待你批准」变成「已取消」，
    // 用户会以为审批已经处理完了。
    assertEquals("等待你批准", NotifyContent.of(NotifyKind.Approval, reason = "cancelled").title)
    assertEquals("任务卡住", NotifyContent.of(NotifyKind.Stuck, reason = "cancelled").title)
    assertEquals("正在执行", NotifyContent.of(NotifyKind.Progress, reason = "cancelled").title)
  }

  @Test
  fun fileName_只保留基名_去掉目录层级() {
    // Windows 路径（桌面是 Windows，这是最常见的形态）
    assertEquals("报告.xlsx", NotifyContent.fileNameOf("C:\\交付成品\\周报\\报告.xlsx"))
    // POSIX 路径
    assertEquals("main.kt", NotifyContent.fileNameOf("/home/me/proj/src/main.kt"))
    // 相对路径
    assertEquals("a.txt", NotifyContent.fileNameOf("../../etc/a.txt"))
    // 只有文件名
    assertEquals("README.md", NotifyContent.fileNameOf("README.md"))
  }

  @Test
  fun fileName_异常形态返回_null_而不是展示半个路径() {
    assertNull(NotifyContent.fileNameOf(""))
    assertNull(NotifyContent.fileNameOf("   "))
    assertNull(NotifyContent.fileNameOf("C:\\"))          // 只有分隔符
    assertNull(NotifyContent.fileNameOf("/"))
    assertNull(NotifyContent.fileNameOf("."))
    assertNull(NotifyContent.fileNameOf(".."))
    // 超长（多半是内容而不是文件名）
    assertNull(NotifyContent.fileNameOf("a".repeat(100)))
  }

  @Test
  fun fileName_含换行时拒绝_防注入伪造行() {
    // 这种输入会让通知显示成两行，第二行可以伪装成系统提示
    assertNull(NotifyContent.fileNameOf("report.txt\n漏过审批"))
    assertNull(NotifyContent.fileNameOf("report.txt\r\nX"))
    assertNull(NotifyContent.fileNameOf("a\u0000b.txt"))
  }

  // ───────────────── body 只能由白名单拼出 ─────────────────

  @Test
  fun body_只含文件名_不含目录() {
    val c = NotifyContent.of(
      kind = NotifyKind.Completed,
      fileName = "C:\\交付成品\\Morrow\\docs\\cards\\B4-推送.md"
    )
    assertEquals("B4-推送.md", c.body)
    assertFalse("body 不得含盘符", c.body.contains("C:"))
    assertFalse("body 不得含目录", c.body.contains("cards"))
  }

  @Test
  fun body_路径出现在其他参数里也不会漏进去() {
    // 攻击者（或粗心的调用方）可能把完整路径塞进 fileName，
    // 文件名削完只剩基名 —— 但同一串路径若出现在 tool/detail 里必须被拒
    val c = NotifyContent.of(
      kind = NotifyKind.Stuck,
      fileName = "C:\\secret\\proj\\a.ts",
      detail = "C:\\secret\\proj\\a.ts",
      tool = "read_file"
    )
    assertFalse(c.body.contains("secret"))
    assertEquals("a.ts · read_file", c.body)
  }

  @Test
  fun body_命令与_api_key_形态的内容进不来() {
    val dangerous = listOf(
      "curl -H 'Authorization: Bearer sk-live-abc123' https://api.example.com",
      "apiKey=sk-live-should-not-leak",
      "git push --force origin main"
    )
    for (raw in dangerous) {
      // ① 当文件名传：削完只剩基名（或为 null），不会带出整条命令
      val asName = NotifyContent.of(NotifyKind.Completed, fileName = raw)
      assertFalse("命令不得进入 body：$raw", asName.body.contains("sk-live"))
      assertFalse("命令不得进入 body：$raw", asName.body.contains("curl"))

      // ② 当 detail 传：含 `/` 或超长 → 被拒
      val asDetail = NotifyContent.of(NotifyKind.Completed, detail = raw)
      assertFalse("detail 不得带出命令：$raw", asDetail.body.contains("sk-live"))
    }
  }

  @Test
  fun detail_长度与分隔符受限() {
    // 正常短句放行
    assertEquals("等待确认", NotifyContent.of(NotifyKind.Approval, detail = "等待确认").body)
    // 含路径分隔符 → 拒（多半是路径不是短句）
    assertEquals("", NotifyContent.of(NotifyKind.Approval, detail = "C:\\a\\b").body)
    assertEquals("", NotifyContent.of(NotifyKind.Approval, detail = "/etc/passwd").body)
    // 超长 → 拒
    assertEquals("", NotifyContent.of(NotifyKind.Approval, detail = "字".repeat(40)).body)
    // 换行 → 拒
    assertEquals("", NotifyContent.of(NotifyKind.Approval, detail = "a\nb").body)
  }

  @Test
  fun tool_只接受小写下划线标识() {
    assertEquals("read_file", NotifyContent.of(NotifyKind.Progress, tool = "read_file").body)
    // 带参数/大写/路径形态 → 拒
    assertEquals("", NotifyContent.of(NotifyKind.Progress, tool = "read_file(C:\\a.txt)").body)
    assertEquals("", NotifyContent.of(NotifyKind.Progress, tool = "ReadFile").body)
    assertEquals("", NotifyContent.of(NotifyKind.Progress, tool = "a/b").body)
    assertEquals("", NotifyContent.of(NotifyKind.Progress, tool = "x".repeat(40)).body)
  }

  @Test
  fun body_为空是合法状态_不用兜底文案() {
    // 没有文件名、没有 detail —— 宁可空着，也不塞「任务已完成」这类兜底
    val c = NotifyContent.of(NotifyKind.Completed)
    assertEquals("", c.body)
    // 兜底文案最容易带上敏感信息，所以这里刻意允许空 body
    assertTrue(c.body.isEmpty())
  }

  @Test
  fun body_组合顺序稳定_便于测试与人工核对() {
    val c = NotifyContent.of(
      kind = NotifyKind.Progress,
      fileName = "/a/b/report.pdf",
      tool = "write_file",
      detail = "第 2 步",
      seconds = 130,
      extraCount = 3
    )
    assertEquals("report.pdf · write_file · 第 2 步 · 用时 2 分钟 · 另有 3 条", c.body)
  }

  // ───────────────── 标题固定 ─────────────────

  @Test
  fun 四类通知的标题是固定文案_不受调用方影响() {
    val expect = mapOf(
      NotifyKind.Approval to "等待你批准",
      NotifyKind.Completed to "任务完成",
      NotifyKind.Stuck to "任务卡住",
      NotifyKind.Progress to "正在执行"
    )
    for ((kind, title) in expect) {
      assertEquals(title, NotifyContent.of(kind, fileName = "a.txt").title)
    }
  }

  // ───────────────── 动作按钮 ─────────────────

  @Test
  fun 只有审批通知带动作按钮_且为上拒绝下允许() {
    val approval = NotifyContent.of(NotifyKind.Approval, fileName = "a.txt")
    assertEquals(2, approval.actions.size)
    // 纵向顺序由 UI 决定，但列表顺序必须稳定：先允许后拒绝时，
    // UI 可以按 index 反转成「上拒绝下允许」；若顺序不定就无法保证
    assertEquals(NotifyActionId.ALLOW, approval.actions[0].id)
    assertEquals(NotifyActionId.DENY, approval.actions[1].id)
    assertEquals("允许", approval.actions[0].label)
    assertEquals("拒绝", approval.actions[1].label)

    // 其余三类没有动作按钮
    for (kind in listOf(NotifyKind.Completed, NotifyKind.Stuck, NotifyKind.Progress)) {
      assertTrue("$kind 不应有动作按钮", NotifyContent.of(kind, fileName = "a.txt").actions.isEmpty())
    }
  }

  @Test
  fun 动作按钮的_label_不含变量() {
    val approval = NotifyContent.of(NotifyKind.Approval, fileName = "C:\\x\\secret.txt")
    for (a in approval.actions) {
      assertFalse("按钮上看不到文件名以外的内容", a.label.contains("secret"))
      assertTrue(a.label.length <= 4)
    }
  }

  // ───────────────── 落点 ─────────────────

  @Test
  fun deepLink_只接受_morrow_协议() {
    assertEquals("morrow://approvals/42", NotifyContent.of(NotifyKind.Approval, deepLink = "morrow://approvals/42").deepLink)
    // 外链一律拒绝（防调用方把用户导向网页）
    assertNull(NotifyContent.of(NotifyKind.Approval, deepLink = "https://evil.com").deepLink)
    assertNull(NotifyContent.of(NotifyKind.Approval, deepLink = "javascript:alert(1)").deepLink)
    assertNull(NotifyContent.of(NotifyKind.Approval, deepLink = "morrow://a\nb").deepLink)
    assertNull(NotifyContent.of(NotifyKind.Approval, deepLink = "morrow://" + "a".repeat(300)).deepLink)
  }

  // ───────────────── 时长格式 ─────────────────

  @Test
  fun 时长只到分钟_不展示秒级抖动() {
    assertEquals("用时 5 秒", NotifyContent.formatDuration(5))
    assertEquals("用时 1 分钟", NotifyContent.formatDuration(60))
    assertEquals("用时 2 分钟", NotifyContent.formatDuration(130))
    assertEquals("用时 1 小时 0 分钟", NotifyContent.formatDuration(3600))
    assertEquals("用时 2 小时 5 分钟", NotifyContent.formatDuration(7500))
    // 负数不该产生奇怪文案
    assertEquals("用时 0 秒", NotifyContent.formatDuration(-10))
  }

  // ───────────────── 渠道配置 ─────────────────

  @Test
  fun 四类通知各有独立渠道_且只有审批豁免免打扰() {
    val all = NotifyKind.all()
    assertEquals(4, all.size)
    // 渠道 id 唯一 —— 重复会让两类通知共用一个用户开关，等于少一类控制
    assertEquals(4, all.map { it.channelId }.toSet().size)

    assertEquals(listOf(true, false, false, false), all.map { it.survivesDoNotDisturb })
    // 只有审批不可汇总（它必须单条到达）
    assertEquals(listOf(false, true, true, true), all.map { it.summarizable })
  }

  @Test
  fun 进度类必须是最低重要性_否则会变成骚扰() {
    // 进度更新可能每秒一次，DEFAULT/HIGH 会让通知栏不停跳
    assertEquals(NotifyImportance.LOW, NotifyKind.Progress.importance)
    // 审批必须能横幅弹出，否则用户可能几小时没看见
    assertEquals(NotifyImportance.HIGH, NotifyKind.Approval.importance)
  }

  @Test
  fun 渠道_id_可以被反查_未知_id_返回_null() {
    for (kind in NotifyKind.all()) {
      assertEquals(kind, NotifyKind.fromChannelId(kind.channelId))
    }
    assertNull(NotifyKind.fromChannelId("morrow_unknown"))
    assertNull(NotifyKind.fromChannelId(""))
  }
}

// ───── NotifyPolicyTest.kt ─────
/**
 * B4 第一张卡：免打扰联动与汇总。
 *
 * 蓝图验收原文：「免打扰开启时只保留汇总通知」。
 *
 * 这条如果照字面实现（免打扰下**什么都不发单条**）会产生一个严重后果：
 * 审批通知也被压掉，而审批是唯一「不处理就卡住桌面上那个 agent」的类别。
 * 用户开了专注模式，桌面上的 agent 停在那里等他批准，而他完全不知道。
 *
 * 所以真正的规则是：**审批豁免，其余三类进汇总。**
 * 下面第一组用例就是为这条豁免写的 —— 它是本卡最容易被实现错的地方。
 */
class NotifyPolicyTest {

  /**
   * 每个用例都从「计数为 0」开始。
   *
   * D-16 把免打扰累积计数下沉成进程级单例（两条推送路径必须共用一份），
   * 代价是它**跨测试存活** —— 不重置的话，前一条用例记的 10 条会带到
   * 后一条，断言 `expected:<3> but was:<13>`。这不是产品缺陷，
   * 是进程级状态的测试卫生问题。
   */
  private fun policy(): NotifyPolicy {
    DigestCounter.resetForTest()
    return NotifyPolicy()
  }

  private fun content(kind: NotifyKind, fileName: String? = "a.txt"): NotifyContent =
    NotifyContent.of(kind, fileName = fileName)

  // ───────────────── D-16：两条推送路径共用同一份计数 ─────────────────

  @Test
  fun 两个policy实例共用同一份免打扰计数() {
    // 回归 D-16：`NotifyPolicy` 有**两个**持有者（FCM 推送路径的 FcmReceiver
    // 与 relay 路径的 RelayNotifications），各自 new 一个。计数放在实例字段时
    // 两条路径各记各的 —— 免打扰期间来 3 条推送 + 4 条 relay，
    // 「共 N 条进展」永远只报其中一条路径的数。
    //
    // 这里用两个独立实例模拟那两条路径：它们必须看到同一个累计值。
    DigestCounter.resetForTest()
    val pushPath = NotifyPolicy()
    val relayPath = NotifyPolicy()
    val content = content(NotifyKind.Progress)

    repeat(3) { pushPath.decide(content, doNotDisturb = true) }
    repeat(4) { relayPath.decide(content, doNotDisturb = true) }

    assertEquals(
      "两条路径的计数必须累加到同一个值",
      7,
      pushPath.digestState().suppressedCount,
    )
    assertEquals("另一个实例读到的也必须是 7", 7, relayPath.digestState().suppressedCount)
  }

  @Test
  fun 一个实例清理后另一个也归零() {
    // 用户点开汇总 = 「这些都看过了」。若清的是各自实例，
    // 另一条路径的计数还留着，下一条通知就会显示「共 8 条」而实际只有 1 条。
    DigestCounter.resetForTest()
    val a = NotifyPolicy()
    val b = NotifyPolicy()
    repeat(5) { a.decide(content(NotifyKind.Progress), doNotDisturb = true) }
    assertEquals(5, b.digestState().suppressedCount)

    a.clearDigest()
    assertEquals("清理必须跨实例生效", 0, b.digestState().suppressedCount)
  }

  // ───────────────── 免打扰：审批豁免 ─────────────────

  @Test
  fun 免打扰下审批仍然单条送达() {
    val p = policy()
    val d = p.decide(content(NotifyKind.Approval), doNotDisturb = true)
    assertTrue("审批必须突破免打扰", d is NotifyDecision.Deliver)
    assertEquals(NotifyKind.Approval, (d as NotifyDecision.Deliver).content.kind)
    // 且没有被计入汇总
    assertEquals(0, p.digestState().suppressedCount)
  }

  @Test
  fun 免打扰下完成卡住进度三类进汇总_不发单条() {
    val p = policy()
    for (kind in listOf(NotifyKind.Completed, NotifyKind.Stuck, NotifyKind.Progress)) {
      val d = p.decide(content(kind), doNotDisturb = true)
      assertTrue("$kind 应进汇总", d is NotifyDecision.SuppressIntoDigest)
      val digest = (d as NotifyDecision.SuppressIntoDigest).digest
      // 汇总不是原通知的复制：标题换成汇总标题
      assertEquals(NotifyPhrase.DIGEST_TITLE, digest.title)
      assertTrue("汇总更新必须静音（免打扰期间别吵）", d.silent)
    }
    assertEquals(3, p.digestState().suppressedCount)
  }

  @Test
  fun 免打扰关闭时四类都单条送达() {
    val p = policy()
    for (kind in NotifyKind.all()) {
      val d = p.decide(content(kind), doNotDisturb = false)
      assertTrue("$kind 应单条送达", d is NotifyDecision.Deliver)
      assertEquals(kind, (d as NotifyDecision.Deliver).content.kind)
    }
    assertEquals(0, p.digestState().suppressedCount)
  }

  // ───────────────── 汇总文案：不含任务细节 ─────────────────

  @Test
  fun 汇总正文只有数量与粗分类_不含文件名() {
    val p = policy()
    p.decide(content(NotifyKind.Completed, "C:\\交付成品\\secret-plan.xlsx"), doNotDisturb = true)
    val d = p.decide(content(NotifyKind.Stuck, "/home/me/proj/机密.ts"), doNotDisturb = true)
      as NotifyDecision.SuppressIntoDigest

    assertFalse("汇总不得含文件名", d.digest.body.contains("secret-plan"))
    assertFalse("汇总不得含文件名", d.digest.body.contains("机密"))
    assertFalse("汇总不得含路径", d.digest.body.contains("交付成品"))
    // 只允许数量与粗分类
    assertTrue(d.digest.body.contains("2"))
    assertTrue(d.digest.body.contains("卡住"))
  }

  @Test
  fun 汇总数量随压制条数递增() {
    val p = policy()
    val bodies = mutableListOf<String>()
    repeat(3) {
      val d = p.decide(content(NotifyKind.Progress), doNotDisturb = true)
        as NotifyDecision.SuppressIntoDigest
      bodies.add(d.digest.body)
    }
    assertEquals(3, p.digestState().suppressedCount)
    assertTrue(bodies[0].contains("1"))
    assertTrue(bodies[1].contains("2"))
    assertTrue(bodies[2].contains("3"))
  }

  // ───────────────── 汇总 id 与清空 ─────────────────

  @Test
  fun 汇总通知使用固定_id_不会堆积() {
    val p = policy()
    val id1 = p.digestNotificationId()
    p.decide(content(NotifyKind.Completed), doNotDisturb = true)
    p.decide(content(NotifyKind.Stuck), doNotDisturb = true)
    // 无论压制多少条，id 不变 —— 系统里始终只有一条汇总
    assertEquals(id1, p.digestNotificationId())
  }

  @Test
  fun clearDigest_清空累积() {
    val p = policy()
    p.decide(content(NotifyKind.Completed), doNotDisturb = true)
    p.decide(content(NotifyKind.Stuck), doNotDisturb = true)
    assertEquals(2, p.digestState().suppressedCount)

    p.clearDigest()
    assertTrue(p.digestState().isEmpty)
    assertEquals(0, p.digestState().suppressedCount)
  }

  @Test
  fun 没有累积时_汇总状态为空() {
    val p = policy()
    assertTrue(p.digestState().isEmpty)
    // 没东西可汇总就不该发汇总通知 —— 这个判断由 isEmpty 提供
    assertEquals(0, p.digestState().suppressedCount)
  }

  // ───────────────── 混合场景 ─────────────────

  @Test
  fun 免打扰期间混着来_审批立刻到达_其余进汇总() {
    val p = policy()
    val events = listOf(
      NotifyKind.Progress,
      NotifyKind.Approval, // 必须立刻到
      NotifyKind.Completed,
      NotifyKind.Stuck
    )

    var delivered = 0
    var suppressed = 0
    for (kind in events) {
      when (p.decide(content(kind), doNotDisturb = true)) {
        is NotifyDecision.Deliver -> delivered++
        is NotifyDecision.SuppressIntoDigest -> suppressed++
        NotifyDecision.Drop -> Unit
      }
    }

    assertEquals("只有审批立刻到达", 1, delivered)
    assertEquals("其余三条进汇总", 3, suppressed)
    assertEquals(3, p.digestState().suppressedCount)
  }

  @Test
  fun 免打扰反复开关_累积不被误清() {
    val p = policy()
    p.decide(content(NotifyKind.Completed), doNotDisturb = true)
    assertEquals(1, p.digestState().suppressedCount)

    // 关掉免打扰：单条送达，但不该清掉已有累积（用户还没看到那一条）
    p.decide(content(NotifyKind.Stuck), doNotDisturb = false)
    assertEquals(1, p.digestState().suppressedCount)

    // 再开：继续累加
    p.decide(content(NotifyKind.Progress), doNotDisturb = true)
    assertEquals(2, p.digestState().suppressedCount)
  }

  @Test
  fun 审批不会因为免打扰关闭而进汇总() {
    // 双保险：无论免打扰怎么设，审批都走单条
    for (dnd in listOf(true, false)) {
      val p = policy()
      val d = p.decide(content(NotifyKind.Approval), doNotDisturb = dnd)
      assertTrue("dnd=$dnd 时审批也应单条", d is NotifyDecision.Deliver)
      assertEquals(0, p.digestState().suppressedCount)
    }
  }
}

// ───── NotifyDeepLinkTest.kt ─────
/**
 * B4 第二张卡的「攻击面」测试。
 *
 * 深链解析与审批动作这两块，一旦写松了不会报错、也不会崩，
 * 只会让「点一下就把危险动作批了」变成可能 —— 所以这里按攻击者的角度写：
 * 每条用例都是一次「我能不能用这种形态绕过去」的尝试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifyDeepLinkTest {

  private lateinit var app: Application

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    ApprovalActions.resetForTest()
    ApprovalDecisionInbox.clearForTest()
  }

  // ───────────────── 深链白名单 ─────────────────

  @Test
  fun 三个已知_页面正常解析() {
    assertEquals(
      MorrowDestination.Workbench,
      MorrowDeepLink.parse("morrow://workbench")
    )
    assertEquals(
      MorrowDestination.Approvals("ap-1"),
      MorrowDeepLink.parse("morrow://approvals?focus=ap-1")
    )
    assertEquals(
      MorrowDestination.Activity(null),
      MorrowDeepLink.parse("morrow://activity")
    )
  }

  @Test
  fun 路径形式的_focus_也认() {
    assertEquals(
      MorrowDestination.Approvals("ap-2"),
      MorrowDeepLink.parse("morrow://approvals/ap-2")
    )
  }

  @Test
  fun 未知_scheme_和_host_一律落回首页() {
    val attacks = listOf(
      "https://evil.com/approvals",
      "javascript:alert(1)",
      "file:///etc/passwd",
      "morrow://unknown-page",
      "MORROW://approvals",
      "",
      "   ",
      "morrow://",
      "not a uri at all",
      // 下面三条防的是「用 contains/startsWith 拼 scheme」这种写法：
      // 这些串里出现了 morrow、host 也恰好是合法页面名，
      // 但 scheme 不是 morrow —— 只认精确 scheme 实现才会拒绝它们。
      "https://evil.com/?next=morrow://approvals",
      "https://approvals/?ref=morrow",
      "ftp://activity/?from=morrow"
    )
    for (raw in attacks) {
      assertEquals("攻击形态应落回首页：$raw", MorrowDestination.Workbench, MorrowDeepLink.parse(raw))
    }
    assertNull(MorrowDeepLink.parse(null) as? MorrowDestination.Approvals)
  }

  @Test
  fun focus_参数必须过白名单_非法形态退化为不聚焦() {
    val bad = listOf(
      "morrow://approvals?focus=../../etc/passwd",
      "morrow://approvals?focus=<script>",
      "morrow://approvals?focus=" + "a".repeat(65),
      "morrow://approvals?focus=has space",
      "morrow://approvals?focus=\${inject}"
    )
    for (raw in bad) {
      val dest = MorrowDeepLink.parse(raw)
      assertTrue("仍应识别为审批页：$raw", dest is MorrowDestination.Approvals)
      assertNull("非法 focus 必须退化为 null：$raw", (dest as MorrowDestination.Approvals).focusId)
    }
  }

  @Test
  fun toUri_与_parse_互为逆运算() {
    val dests = listOf(
      MorrowDestination.Workbench,
      MorrowDestination.Approvals("ap-3"),
      MorrowDestination.Approvals(null),
      MorrowDestination.Activity("task-9")
    )
    for (d in dests) {
      assertEquals("往返应一致：$d", d, MorrowDeepLink.parse(MorrowDeepLink.toUri(d)))
    }
  }

  // ───────────────── 动作参数 ─────────────────

  @Test
  fun decision_参数只认_allow_与_deny() {
    assertTrue(MorrowDeepLink.requestedDecision(Uri.parse("morrow://approvals?decision=allow"))!!)
    assertFalse(MorrowDeepLink.requestedDecision(Uri.parse("morrow://approvals?decision=deny"))!!)
    assertNull("其它值一律当普通点击", MorrowDeepLink.requestedDecision(Uri.parse("morrow://approvals?decision=maybe")))
    assertNull(MorrowDeepLink.requestedDecision(Uri.parse("morrow://approvals")))
  }

  // ───────────────── 审批执行闸门 ─────────────────

  @Test
  fun 未校验的请求_连类型都造不出来() {
    // 非法 id 直接返回 null —— 「已校验的请求」这个类型本身就是一道门
    assertNull(ApprovalActions.ApprovalRequest.verified("", true))
    assertNull(ApprovalActions.ApprovalRequest.verified("a/b", true))
    assertNull(ApprovalActions.ApprovalRequest.verified("a b", true))
    assertNull(ApprovalActions.ApprovalRequest.verified("x".repeat(65), true))
    assertNotNull(ApprovalActions.ApprovalRequest.verified("ap-1", true))
  }

  @Test
  fun 同一条审批不会被执行两次() {
    val req = ApprovalActions.ApprovalRequest.verified("ap-1", true)!!
    var sinkCalls = 0
    val sink: (String, Boolean) -> Boolean = { _, _ -> sinkCalls++; true }

    val first = ApprovalActions.perform(req, setOf("ap-1"), sink)
    val second = ApprovalActions.perform(req, setOf("ap-1"), sink)

    assertTrue(first is ApprovalActions.Result.Dispatched)
    assertTrue("第二次必须被拒", second is ApprovalActions.Result.AlreadyHandled)
    assertEquals("只该真的下发一次", 1, sinkCalls)
  }

  @Test
  fun 未知审批不下发() {
    val req = ApprovalActions.ApprovalRequest.verified("ap-404", true)!!
    var called = false
    val result = ApprovalActions.perform(req, setOf("ap-1")) { _, _ -> called = true; true }
    assertTrue(result is ApprovalActions.Result.Unknown)
    assertFalse("未知审批不该触达桌面", called)
  }

  @Test
  fun 下发失败必须可重试() {
    val req = ApprovalActions.ApprovalRequest.verified("ap-2", false)!!
    var attempt = 0
    val flaky: (String, Boolean) -> Boolean = { _, _ ->
      attempt++
      attempt > 1 // 第一次失败，第二次成功
    }

    val first = ApprovalActions.perform(req, setOf("ap-2"), flaky)
    assertTrue("第一次应报失败", first is ApprovalActions.Result.Unknown)

    val second = ApprovalActions.perform(req, setOf("ap-2"), flaky)
    assertTrue("失败后必须能重试，否则这条审批永远批不下去", second is ApprovalActions.Result.Dispatched)
    assertEquals(2, attempt)
  }

  // ───────────────── F-11：dispatchOnce 去重 ─────────────────

  @Test
  fun 同一审批第二次下发会被拦下() {
    // 回归 F-11。`MainActivity` 有两条并行路径都会下发审批：
    //  · 通知栏按钮 → ApprovalDecisionInbox.registerSink
    //  · 界面按钮 / 生物识别回调 → decideApproval
    //
    // 此前两条都**没有去重**。审批是有副作用的动作（写文件、跑命令），
    // 用户在两处各点一次，桌面会收到两条 approval.decide。
    ApprovalActions.resetForTest()
    var calls = 0
    val sink: (String, Boolean) -> Boolean = { _, _ -> calls++; true }

    assertTrue("第一次应真的下发", ApprovalActions.dispatchOnce("ap-dup", true, sink))
    assertFalse("同一 id 第二次必须被拦下", ApprovalActions.dispatchOnce("ap-dup", true, sink))
    assertEquals("下沉只应发生一次", 1, calls)

    ApprovalActions.resetForTest()
  }

  @Test
  fun 不同审批互不影响() {
    ApprovalActions.resetForTest()
    val seen = mutableListOf<String>()
    val sink: (String, Boolean) -> Boolean = { id, _ -> seen += id; true }

    ApprovalActions.dispatchOnce("ap-a", true, sink)
    ApprovalActions.dispatchOnce("ap-b", true, sink)
    assertEquals(listOf("ap-a", "ap-b"), seen)

    ApprovalActions.resetForTest()
  }

  @Test
  fun 下发失败后必须可重试() {
    // 失败后若仍占着 handled，用户再点会被当成「已处理」，
    // 这条审批就永远批不下去了。
    ApprovalActions.resetForTest()
    var attempt = 0
    val flaky: (String, Boolean) -> Boolean = { _, _ ->
      attempt++
      attempt > 1
    }

    assertFalse("第一次失败", ApprovalActions.dispatchOnce("ap-retry", false, flaky))
    assertTrue("失败后必须能重试", ApprovalActions.dispatchOnce("ap-retry", false, flaky))
    assertEquals(2, attempt)

    ApprovalActions.resetForTest()
  }

  @Test
  fun 下发抛异常时按失败处理且可重试() {
    // sink 会经 relay 下发，可能抛（连接已被关闭）。异常不能把 id 永久占住。
    ApprovalActions.resetForTest()
    var threw = false
    val boom: (String, Boolean) -> Boolean = { _, _ ->
      if (!threw) { threw = true; error("通道已关闭") }
      true
    }

    assertFalse("抛异常应视为失败", ApprovalActions.dispatchOnce("ap-boom", true, boom))
    assertTrue("异常后必须能重试", ApprovalActions.dispatchOnce("ap-boom", true, boom))

    ApprovalActions.resetForTest()
  }

  // ───────────────── 收件箱：发不出去不丢 ─────────────────

  @Test
  fun 通道未就绪时决策进队列而不是丢() {
    ApprovalDecisionInbox.registerSink(null)
    val sent = ApprovalDecisionInbox.offer("ap-1", false, 1000L)

    assertFalse("没通道时不算发出", sent)
    assertEquals(1, ApprovalDecisionInbox.pending().size)
    assertEquals(false, ApprovalDecisionInbox.pending().first().allow)
  }

  @Test
  fun 通道恢复后_flush_把队列发出去() {
    ApprovalDecisionInbox.registerSink(null)
    ApprovalDecisionInbox.offer("ap-1", false, 1000L)
    ApprovalDecisionInbox.offer("ap-2", false, 2000L)

    val received = mutableListOf<String>()
    ApprovalDecisionInbox.registerSink { id, _ -> received.add(id); true }

    val sent = ApprovalDecisionInbox.flush()
    assertEquals("两条都应发出", 2, sent)
    assertEquals(listOf("ap-1", "ap-2"), received)
    assertTrue("队列应清空", ApprovalDecisionInbox.pending().isEmpty())
  }

  @Test
  fun flush_中途断线时保持剩余顺序() {
    ApprovalDecisionInbox.registerSink(null)
    ApprovalDecisionInbox.offer("ap-1", false, 1000L)
    ApprovalDecisionInbox.offer("ap-2", false, 2000L)
    ApprovalDecisionInbox.offer("ap-3", false, 3000L)

    // 只允许第一条通过
    ApprovalDecisionInbox.registerSink { id, _ -> id == "ap-1" }
    val sent = ApprovalDecisionInbox.flush()

    assertEquals(1, sent)
    assertEquals("剩下的必须还在，且顺序不变", listOf("ap-2", "ap-3"), ApprovalDecisionInbox.pending().map { it.approvalId })
  }

  @Test
  fun 队列有上限_长时间离线不会无限涨() {
    ApprovalDecisionInbox.registerSink(null)
    repeat(ApprovalDecisionInbox.MAX_QUEUE + 10) {
      ApprovalDecisionInbox.offer("ap-$it", false, it.toLong())
    }
    assertEquals(ApprovalDecisionInbox.MAX_QUEUE, ApprovalDecisionInbox.pending().size)
    // 保留的应是最近的（丢掉的是最早那几条）
    assertEquals("ap-10", ApprovalDecisionInbox.pending().first().approvalId)
  }

  @Test
  fun 同一条审批的后续决策覆盖前面的() {
    ApprovalDecisionInbox.registerSink(null)
    ApprovalDecisionInbox.offer("ap-1", false, 1000L)
    ApprovalDecisionInbox.offer("ap-1", true, 2000L)

    assertEquals("同一审批只留一条", 1, ApprovalDecisionInbox.pending().size)
    assertEquals("后到的意图生效", true, ApprovalDecisionInbox.pending().first().allow)
  }

  // ───────────────── 广播接收器：只接「拒绝」 ─────────────────

  @Test
  fun 广播收下拒绝_进队列() {
    val receiver = ApprovalActionReceiver()
    receiver.onReceive(app, intentFor("morrow://approvals?focus=ap-7&decision=deny"))

    assertEquals(1, ApprovalDecisionInbox.pending().size)
    assertEquals("ap-7", ApprovalDecisionInbox.pending().first().approvalId)
    assertFalse(ApprovalDecisionInbox.pending().first().allow)
  }

  /**
   * 最关键的一条：从通知来的「允许」**绝不能**在后台直接生效。
   *
   * 通知栏上的按钮是一次点击就能按下的。如果广播接收器也处理 allow，
   * 等于任何人拿到这台手机都能在锁屏上把审批按掉 —— C4 的生物识别
   * 就被整个绕过去了。
   */
  @Test
  fun 广播收到允许时什么都不做() {
    val receiver = ApprovalActionReceiver()
    receiver.onReceive(app, intentFor("morrow://approvals?focus=ap-7&decision=allow"))
    assertTrue("允许必须走 Activity 过生物识别", ApprovalDecisionInbox.pending().isEmpty())
  }

  @Test
  fun 广播忽略非法形态() {
    val receiver = ApprovalActionReceiver()
    val bad = listOf(
      "https://evil.com/approvals?focus=ap-1&decision=deny",
      "morrow://approvals?focus=../x&decision=deny",
      "morrow://approvals?focus=ap-1&decision=maybe",
      "morrow://workbench?decision=deny",
      "morrow://approvals"
    )
    for (uri in bad) {
      receiver.onReceive(app, intentFor(uri))
    }
    assertTrue("这些都不该产生决策：$bad", ApprovalDecisionInbox.pending().isEmpty())
  }

  @Test
  fun 没有_data_的广播也不崩() {
    val receiver = ApprovalActionReceiver()
    receiver.onReceive(app, Intent())
    assertTrue(ApprovalDecisionInbox.pending().isEmpty())
  }

  private fun intentFor(uri: String): Intent =
    Intent().apply { data = Uri.parse(uri) }

  // ───────────────── 渠道与通知权限的组合 ─────────────────

  @Test
  fun 应用级通知被关时投递返回明确失败() {
    val manager = app.getSystemService(NotificationManager::class.java)
    org.robolectric.Shadows.shadowOf(manager).setNotificationsEnabled(false)

    val result = studio.morrow.android.MorrowNotifier(app).post(
      studio.morrow.android.NotifyContent.of(
        studio.morrow.android.NotifyKind.Completed,
        fileName = "a.txt"
      ),
      studio.morrow.android.NotifyDecision.Deliver(
        studio.morrow.android.NotifyContent.of(
          studio.morrow.android.NotifyKind.Completed,
          fileName = "a.txt"
        )
      )
    )
    assertTrue(
      "整个应用的通知被关时必须明确报错，而不是静默丢弃",
      result is studio.morrow.android.MorrowNotifier.PostResult.ChannelBlocked
    )
  }
}
