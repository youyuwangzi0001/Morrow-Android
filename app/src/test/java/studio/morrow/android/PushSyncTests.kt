package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.morrow.android.FcmDiagnostics
import studio.morrow.android.FcmPayload
import studio.morrow.android.MorrowDestination
import studio.morrow.android.MorrowDeepLink
import studio.morrow.android.NotifyKind
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Robolectric
import studio.morrow.android.FcmReceiver
import studio.morrow.android.ForegroundSyncService
import studio.morrow.android.ForegroundSyncState
import studio.morrow.android.NotifyChannels
import studio.morrow.android.PendingPull
import android.app.Notification
import android.app.Service
import org.robolectric.Shadows.shadowOf
import studio.morrow.android.MorrowNotifier
import studio.morrow.android.NotifyContent
import studio.morrow.android.NotifyDecision
import studio.morrow.android.NotifyPolicy
import studio.morrow.android.SyncState
import studio.morrow.android.SyncStopReason
import studio.morrow.android.progressNotificationId

// ───── FcmPayloadTest.kt ─────
/**
 * B4 第四张卡：FCM 推送解析（纯逻辑，不依赖 Firebase SDK）。
 *
 * 这组测试按「攻击者 / 配错服务器」的角度写 —— 因为 Tier 3 是兜底通道，
 * 它出问题时用户只看到「什么都没发生」，没有任何崩溃可循。
 *
 * ## 为什么需要 Robolectric 而不是纯 JVM
 *
 * 解析本身是纯逻辑，但 [FcmPayload.toContent] 会拼 `morrow://` 深链，
 * 而深链解析走 `android.net.Uri`。在纯 JVM 里 `android.jar` 的方法体是
 * `throw new RuntimeException("Stub!")`，被 `runCatching` 吃掉后
 * **静默返回 null**，于是所有深链都变成「落回首页」——
 * 测试会以「落点不对」的形式失败，而真机上其实是好的。
 *
 * 这类「测试环境的假故障」比真故障更浪费时间：先怀疑了半天生产代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FcmPayloadTest {

  @Before
  fun setUp() {
    FcmDiagnostics.resetForTest()
  }

  private fun parse(vararg pairs: Pair<String, String>) =
    FcmPayload.parse(pairs.toMap(), hasNotificationBlock = false)

  // ───────────────── 正常路径 ─────────────────

  @Test
  fun data_only_审批推送被正确解析() {
    val result = parse(
      "kind" to "approval",
      "focus" to "ap-1",
      "detailCode" to "tool_run"
    )
    assertTrue(result is FcmPayload.Result.Accepted)
    val parsed = (result as FcmPayload.Result.Accepted).parsed
    assertEquals(NotifyKind.Approval, parsed.kind)
    assertEquals("ap-1", parsed.focus)
    assertEquals("tool_run", parsed.detailCode)
    assertEquals("需要你允许执行工具", parsed.detailText)
    assertFalse("普通推送不要求全量", parsed.snapshotRequired)
    assertEquals(1, FcmDiagnostics.acceptedTotal)
  }

  @Test
  fun 四类_kind_都能识别() {
    for ((wire, kind) in listOf(
      "approval" to NotifyKind.Approval,
      "completed" to NotifyKind.Completed,
      "stuck" to NotifyKind.Stuck,
      "progress" to NotifyKind.Progress
    )) {
      val r = parse("kind" to wire) as FcmPayload.Result.Accepted
      assertEquals("$wire 应映射到 $kind", kind, r.parsed.kind)
    }
  }

  @Test
  fun snapshotRequired_只认字面_true() {
    for (v in listOf("true", "TRUE", "1", "yes", "True")) {
      val r = parse("kind" to "stuck", "snapshotRequired" to v) as FcmPayload.Result.Accepted
      // 只有 "true" 精确匹配才算；其余一律不算
      assertEquals("'$v' 只有精确 true 才算", v == "true", r.parsed.snapshotRequired)
    }
  }

  // ───────────────── 关键防线：notification 段 ─────────────────

  /**
   * 这是本卡最重要的一条。
   *
   * FCM 带 `notification` 段时，应用被杀的情况下**系统自己弹通知**，
   * 用 payload 里的原文 —— 绕过我们的渠道、内容清洗、免打扰策略。
   * 也就是说「通知正文不含文件名以外的内容」这条验收会在真机上静默失效。
   *
   * 所以这类推送必须被拒，而且必须**留下记录**（否则服务器配错了
   * 永远发现不了，因为我们的代码根本不执行）。
   */
  @Test
  fun 带_notification_段的消息一律拒绝并记录() {
    val result = FcmPayload.parse(
      data = mapOf("kind" to "approval", "body" to "这里有任意文本"),
      hasNotificationBlock = true
    )
    assertTrue("必须拒绝", result is FcmPayload.Result.Rejected)
    assertEquals(
      FcmPayload.Reason.HAS_NOTIFICATION_BLOCK,
      (result as FcmPayload.Result.Rejected).reason
    )
    assertEquals("必须留痕", 1L, FcmDiagnostics.rejectedTotal)
    assertEquals(FcmPayload.Reason.HAS_NOTIFICATION_BLOCK, FcmDiagnostics.lastReason())
  }

  // ───────────────── 未知与缺失 ─────────────────

  @Test
  fun 未知_kind_丢弃但记录() {
    val result = parse("kind" to "brand_new_thing")
    assertTrue(result is FcmPayload.Result.Rejected)
    assertEquals(FcmPayload.Reason.UNKNOWN_KIND, (result as FcmPayload.Result.Rejected).reason)
    // 记录是必须的：静默丢弃会让「推送来了但没反应」无从排查
    assertEquals(FcmPayload.Reason.UNKNOWN_KIND, FcmDiagnostics.lastReason())
  }

  @Test
  fun 缺_kind_丢弃并记录() {
    val result = parse("focus" to "ap-1")
    assertEquals(
      FcmPayload.Reason.MISSING_KIND,
      (result as FcmPayload.Result.Rejected).reason
    )
  }

  @Test
  fun data_为空或过大被拒() {
    assertEquals(
      FcmPayload.Reason.EMPTY_OR_OVERSIZED,
      (FcmPayload.parse(emptyMap(), false) as FcmPayload.Result.Rejected).reason
    )
    assertEquals(
      FcmPayload.Reason.EMPTY_OR_OVERSIZED,
      (FcmPayload.parse(null, false) as FcmPayload.Result.Rejected).reason
    )
    val huge = (1..FcmPayload.MAX_DATA_KEYS + 1).associate { "k$it" to "v" }
    assertEquals(
      FcmPayload.Reason.EMPTY_OR_OVERSIZED,
      (FcmPayload.parse(huge, false) as FcmPayload.Result.Rejected).reason
    )
  }

  // ───────────────── 值形态：控制字符与长度 ─────────────────

  @Test
  fun 值含控制字符时整条拒绝() {
    // 换行注入：服务端想把通知做成两行，第二行冒充系统提示
    val result = parse("kind" to "approval", "focus" to "ap-1\n已批准，无需操作")
    assertEquals(
      FcmPayload.Reason.UNSAFE_DATA,
      (result as FcmPayload.Result.Rejected).reason
    )
  }

  @Test
  fun 值超长时拒绝() {
    val result = parse("kind" to "approval", "focus" to "a".repeat(FcmPayload.MAX_VALUE_LENGTH + 1))
    assertEquals(
      FcmPayload.Reason.UNSAFE_DATA,
      (result as FcmPayload.Result.Rejected).reason
    )
  }

  @Test
  fun 未知的_detailCode_不拒绝整条_只是不带细节() {
    // 通知本身（哪一类）是确定的；降级成「没有副标题」比丢掉一条审批好
    val r = parse("kind" to "approval", "detailCode" to "some_future_code")
      as FcmPayload.Result.Accepted
    assertNull("未知码不应被采纳", r.parsed.detailCode)
    assertNull(r.parsed.detailText)
    assertEquals(NotifyKind.Approval, r.parsed.kind)
  }

  // ───────────────── 白名单：表外的键根本不读 ─────────────────

  @Test
  fun 表外的键不会被读取_任何位置都进不了通知() {
    val result = parse(
      "kind" to "approval",
      "taskId" to "task-1",
      // 下面这些都是「服务端想塞进通知的任意文字」
      "title" to "老板发来消息",
      "body" to "C:\\机密\\合同.pdf 已就绪",
      "click_action" to "https://evil.com"
    )
    val parsed = (result as FcmPayload.Result.Accepted).parsed
    val content = FcmPayload.toContent(parsed)

    assertFalse("标题不得来自 payload", content.body.contains("老板"))
    assertFalse("正文不得来自 payload", content.body.contains("机密"))
    assertFalse("正文不得含路径", content.body.contains("C:"))
    assertFalse(
      "落点不得来自 payload",
      content.deepLink.orEmpty().contains("evil.com")
    )
  }

  @Test
  fun 正文只由本地文案与白名单字段组成() {
    val parsed = (parse(
      "kind" to "approval",
      "taskId" to "task-9",
      "focus" to "ap-9",
      "detailCode" to "permission_change"
    ) as FcmPayload.Result.Accepted).parsed
    val content = FcmPayload.toContent(parsed)

    assertEquals("等待你批准", content.title)
    assertEquals("需要你确认权限变更", content.body)
    assertEquals("morrow://approvals?focus=ap-9", content.deepLink)
  }

  // ───────────────── toContent：深链由我们拼 ─────────────────

  @Test
  fun 审批类落点是审批页_其余类是进展页() {
    val approval = FcmPayload.toContent(
      (parse("kind" to "approval", "focus" to "ap-1") as FcmPayload.Result.Accepted).parsed
    )
    assertEquals(
      MorrowDestination.Approvals("ap-1"),
      MorrowDeepLink.parse(approval.deepLink)
    )

    val completed = FcmPayload.toContent(
      (parse("kind" to "completed", "focus" to "task-1") as FcmPayload.Result.Accepted).parsed
    )
    assertEquals(
      MorrowDestination.Activity("task-1"),
      MorrowDeepLink.parse(completed.deepLink)
    )
  }

  @Test
  fun 非法_focus_不会传播进落点_只是不聚焦() {
    // focus 里带斜杠：拼进 URL 后深链层的白名单会拒掉这个 id。
    // 期望行为不是「落回首页」而是「仍进审批页，但不聚焦」——
    // 因为页面本身（approvals）是我们自己拼的常量，危险的是那个 id。
    val parsed = (parse("kind" to "approval", "focus" to "ap/1")
      as FcmPayload.Result.Accepted).parsed
    val content = FcmPayload.toContent(parsed)
    val dest = MorrowDeepLink.parse(content.deepLink)
    assertEquals(
      "应仍落在审批页",
      MorrowDestination.Approvals(focusId = null),
      dest
    )
    assertNull("非法 id 不得进入落点", (dest as MorrowDestination.Approvals).focusId)
    assertFalse("非法 id 不得出现在链接里", content.deepLink.orEmpty().contains("ap/1"))
  }

  @Test
  fun 路径穿越形态的_focus_同样被拦下() {
    for (bad in listOf("../admin", "..%2F..%2Fetc", "ap-1?x=1", "ap-1#frag", "ap 1")) {
      val parsed = (parse("kind" to "approval", "focus" to bad)
        as FcmPayload.Result.Accepted).parsed
      val content = FcmPayload.toContent(parsed)
      val dest = MorrowDeepLink.parse(content.deepLink)
      assertTrue("$bad 应仍落在审批页", dest is MorrowDestination.Approvals)
      assertNull("$bad 不得成为聚焦目标", (dest as MorrowDestination.Approvals).focusId)
    }
  }

  @Test
  fun 没有_focus_时落点不带参数() {
    val parsed = (parse("kind" to "stuck", "taskId" to "task-1")
      as FcmPayload.Result.Accepted).parsed
    val content = FcmPayload.toContent(parsed)
    assertEquals(
      MorrowDestination.Activity(null),
      MorrowDeepLink.parse(content.deepLink)
    )
    // 有任务时分组键取任务 id，让同一任务的多条通知合并
    assertEquals("task-1", content.groupKey)
  }

  @Test
  fun 无任务时分组键退回类别() {
    val parsed = (parse("kind" to "completed") as FcmPayload.Result.Accepted).parsed
    val content = FcmPayload.toContent(parsed)
    assertEquals(NotifyKind.Completed.channelId, content.groupKey)
  }

  // ───────────────── 诊断：有界缓冲 ─────────────────

  @Test
  fun 诊断记录有上限_不会无限增长() {
    repeat(FcmDiagnostics.MAX_ENTRIES + 30) {
      FcmPayload.parse(mapOf("kind" to "nope"), false, nowMillis = it.toLong())
    }
    assertEquals(
      "环形缓冲应只保留上限条",
      FcmDiagnostics.MAX_ENTRIES,
      FcmDiagnostics.recent().size
    )
    // 但累计计数不能因溢出而归零 —— 否则「一共坏了多少次」就查不到了
    assertEquals(
      (FcmDiagnostics.MAX_ENTRIES + 30).toLong(),
      FcmDiagnostics.rejectedTotal
    )
  }

  @Test
  fun 诊断能区分不同拒绝原因() {
    FcmPayload.parse(mapOf("kind" to "nope"), false)
    FcmPayload.parse(null, false)
    FcmPayload.parse(mapOf("kind" to "approval"), true)

    val reasons = FcmDiagnostics.recent().map { it.reason }
    assertEquals(
      listOf(
        FcmPayload.Reason.UNKNOWN_KIND,
        FcmPayload.Reason.EMPTY_OR_OVERSIZED,
        FcmPayload.Reason.HAS_NOTIFICATION_BLOCK
      ),
      reasons
    )
    assertNotNull(FcmDiagnostics.lastReason())
  }

  // ───────────────── 已知明文码表 ─────────────────

  @Test
  fun 所有细节码的文案都在长度上限内且不含路径分隔符() {
    for (code in FcmPayload.knownDetailCodes()) {
      val parsed = (parse("kind" to "stuck", "detailCode" to code)
        as FcmPayload.Result.Accepted).parsed
      val text = parsed.detailText!!
      assertTrue("$code 的文案超长：$text", text.length <= studio.morrow.android.NotifyContent.MAX_DETAIL)
      assertFalse("$code 的文案含路径分隔符", text.contains("/") || text.contains("\\"))
      assertFalse("$code 的文案含换行", text.contains("\n"))
    }
  }
}

// ───── FcmReceiverTest.kt ─────
/**
 * B4 第四张卡的**接线**测试：把「推送 → 标记 → 通知 → 打开时补拉」
 * 这条链路真实跑一遍，而不是分别测三段。
 *
 * 为什么需要这一组：B 轨已经出现过两次同样的失败模式 ——
 * 逻辑写完了、测试全绿、但**生产代码里没有任何调用方**（B3 第一批的
 * `pairing` 没传、B3 第五批的 `startLanStack` 没调）。分段测试无法发现
 * 「没人把它们串起来」。
 *
 * 所以这里走 `FcmReceiver`（本卡新增的接线点，等价于
 * `FirebaseMessagingService.onMessageReceived` 里要做的事）跑完整链路：
 *
 * ```
 * data map ──parse──▶ Parsed ──▶ PendingPull.mark ──▶ NotifyPolicy.decide ──▶ Notifier.post
 *                                      │
 *                       onResume ──consume──▶ 拉取（测试里断言取到了请求）
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FcmReceiverTest {

  private lateinit var app: Application
  private lateinit var nm: NotificationManager

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    PendingPull.clearForTest(app)
    FcmDiagnostics.resetForTest()
    ForegroundSyncState.resetForTest()
    FcmReceiver.clearDigest()
    NotifyChannels.register(app)
    nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.cancelAll()
  }

  // ───────────────── 主链路 ─────────────────

  @Test
  fun 审批推送_投出一条审批通知并留下补拉标记() {
    val result = FcmReceiver.onMessage(
      context = app,
      data = mapOf("kind" to "approval", "focus" to "ap-7", "detailCode" to "tool_run"),
      hasNotificationBlock = false
    )

    assertTrue("应被接受", result is FcmPayload.Result.Accepted)

    // ① 通知真的投出去了（查真实 NotificationManager，不信 PostResult）
    val posted = nm.activeNotifications
    assertEquals("应投出 1 条通知", 1, posted.size)
    assertEquals(
      "审批通知必须挂在审批渠道（用户能单独控制它）",
      NotifyKind.Approval.channelId,
      posted[0].notification.channelId
    )
    assertEquals(
      "等待你批准",
      posted[0].notification.extras.getCharSequence("android.title").toString()
    )
    assertEquals(
      "正文来自本地码表，不含 payload 文本",
      "需要你允许执行工具",
      posted[0].notification.extras.getCharSequence("android.text").toString()
    )

    // ② 补拉标记已留下（推送本身不带正文，正文要靠打开后拉）
    assertTrue("必须留下补拉标记", PendingPull.isPending(app))
  }

  @Test
  fun 带_notification_段的推送_既不投通知也不留标记() {
    val result = FcmReceiver.onMessage(
      context = app,
      data = mapOf("kind" to "approval", "body" to "任意文字"),
      hasNotificationBlock = true
    )

    assertTrue(result is FcmPayload.Result.Rejected)
    assertEquals("不能投我们自己生成的通知", 0, nm.activeNotifications.size)
    assertFalse(
      "也不能留补拉标记 —— 我们什么都没做，不该假装要同步",
      PendingPull.isPending(app)
    )
    assertEquals(1L, FcmDiagnostics.rejectedTotal)
    assertEquals(
      FcmPayload.Reason.HAS_NOTIFICATION_BLOCK,
      FcmDiagnostics.lastReason()
    )
  }

  @Test
  fun 未知_kind_不投通知也不留标记但记录诊断() {
    FcmReceiver.onMessage(app, mapOf("kind" to "from_the_future"), false)
    assertEquals(0, nm.activeNotifications.size)
    assertFalse(PendingPull.isPending(app))
    assertEquals(FcmPayload.Reason.UNKNOWN_KIND, FcmDiagnostics.lastReason())
  }

  @Test
  fun 快照要求会被带进补拉标记() {
    FcmReceiver.onMessage(
      app,
      mapOf("kind" to "completed", "snapshotRequired" to "true"),
      false
    )
    val req = PendingPull.consume(app)
    assertNotNull(req)
    assertTrue("推送要求全量 → 补拉请求也应变全量", req!!.snapshotRequired)
  }

  @Test
  fun 多条推送只留一个补拉请求() {
    repeat(5) {
      FcmReceiver.onMessage(app, mapOf("kind" to "progress", "taskId" to "t-$it"), false)
    }
    val req = PendingPull.consume(app)!!
    assertEquals("5 条推送合并成 1 次补拉", 5, req.markCount)
    assertNull("只该有一个请求", PendingPull.consume(app))
  }

  // ───────────────── 与前台服务的去重 ─────────────────

  /**
   * 第三张卡做的双向去重必须在这条链路上仍然生效：
   * 服务正在跟盯某个任务时，推送不能为同一任务再投一条单条通知
   * （否则通知栏有两条同一任务的进度，数字还不一致）。
   */
  @Test
  fun 服务跟盯中的任务_推送不再投单条() {
    Robolectric.buildService(
      ForegroundSyncService::class.java,
      android.content.Intent(app, ForegroundSyncService::class.java).apply {
        action = ForegroundSyncService.ACTION_START
        putExtra(ForegroundSyncService.EXTRA_TASK_ID, "t-1")
        putExtra(ForegroundSyncService.EXTRA_STEP, 10)
        putExtra(ForegroundSyncService.EXTRA_TOTAL, 100)
      }
    ).create().startCommand(0, 0)

    val before = nm.activeNotifications.size
    FcmReceiver.onMessage(app, mapOf("kind" to "progress", "taskId" to "t-1"), false)

    assertEquals("同任务的进度单条不该再投", before, nm.activeNotifications.size)
    // 但补拉标记还是要留（这条推送本身的信息不能丢）
    assertTrue(PendingPull.isPending(app))
  }

  @Test
  fun 服务跟盯别的任务_推送照常投() {
    Robolectric.buildService(
      ForegroundSyncService::class.java,
      android.content.Intent(app, ForegroundSyncService::class.java).apply {
        action = ForegroundSyncService.ACTION_START
        putExtra(ForegroundSyncService.EXTRA_TASK_ID, "t-1")
        putExtra(ForegroundSyncService.EXTRA_STEP, 10)
        putExtra(ForegroundSyncService.EXTRA_TOTAL, 100)
      }
    ).create().startCommand(0, 0)

    val before = nm.activeNotifications.size
    FcmReceiver.onMessage(app, mapOf("kind" to "progress", "taskId" to "t-2"), false)
    assertEquals("别的任务的推送不受影响", before + 1, nm.activeNotifications.size)
  }

  // ───────────────── 免打扰 ─────────────────

  /**
   * 免打扰下「完成」类不进单条，进汇总；且汇总必须**累加** ——
   * 这正是「每次都 new 一个 NotifyPolicy」会静默搞坏的语义
   * （永远显示「另有 1 条」）。
   */
  @Test
  fun 免打扰下完成类进汇总且数量累加() {
    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
    try {
      repeat(3) { i ->
        FcmReceiver.onMessage(app, mapOf("kind" to "completed", "taskId" to "t-$i"), false)
      }
      val posted = nm.activeNotifications
      assertEquals("三条都进同一个汇总，不该有单条", 1, posted.size)
      val text = posted[0].notification.extras.getCharSequence("android.text").toString()
      assertTrue("汇总要显示累积到 3 条，实际是：$text", text.contains("3"))
      assertEquals(3, FcmReceiver.digestState().suppressedCount)
    } finally {
      nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
      FcmReceiver.clearDigest()
    }
  }

  @Test
  fun 免打扰下审批仍然单独发() {
    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
    try {
      FcmReceiver.onMessage(app, mapOf("kind" to "approval", "focus" to "ap-1"), false)
      assertEquals("审批是唯一豁免，必须单独发", 1, nm.activeNotifications.size)
      assertEquals(
        NotifyKind.Approval.channelId,
        nm.activeNotifications[0].notification.channelId
      )
      assertEquals("审批不参与汇总", 0, FcmReceiver.digestState().suppressedCount)
    } finally {
      nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
      FcmReceiver.clearDigest()
    }
  }

  /**
   * `INTERRUPTION_FILTER_ALARMS`（只允许闹钟）必须算作免打扰。
   *
   * 这是本卡最容易被写错的一处：直觉上「ALARMS 比 NONE 宽松」，
   * 于是很容易写成「只有 NONE 才算打扰」。那样会有一个真实后果 ——
   * 用户开了「仅闹钟」模式，我们的完成/卡住通知照常逐条响，
   * 而用户明确的意图是「除了闹钟什么都别吵」。
   *
   * 所以判定用 `!= ALL` 而不是列举「算打扰」的几个状态。
   */
  @Test
  fun 仅闹钟模式也算免打扰() {
    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
    try {
      FcmReceiver.onMessage(app, mapOf("kind" to "completed", "taskId" to "t-1"), false)
      val posted = nm.activeNotifications
      assertEquals("应进汇总而不是单条", 1, posted.size)
      assertEquals(1, FcmReceiver.digestState().suppressedCount)
    } finally {
      nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
      FcmReceiver.clearDigest()
    }
  }

  /** `PRIORITY` 同样算打扰（只允许优先联系人时，我们的通知也不该响） */
  @Test
  fun 优先模式也算免打扰() {
    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    try {
      FcmReceiver.onMessage(app, mapOf("kind" to "completed", "taskId" to "t-1"), false)
      assertEquals(1, FcmReceiver.digestState().suppressedCount)
    } finally {
      nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
      FcmReceiver.clearDigest()
    }
  }

  // ───────────────── 打开时补拉 ─────────────────

  @Test
  fun 打开应用时才取走补拉请求_之前一直在等() {
    FcmReceiver.onMessage(app, mapOf("kind" to "stuck", "taskId" to "t-9"), false)
    assertTrue("推送到达时标记在等（Tier 3 不实时）", PendingPull.isPending(app))

    val atResume = FcmReceiver.onResume(app)
    assertNotNull("onResume 时应取到请求", atResume)
    assertEquals(1, atResume!!.markCount)
    assertFalse("取走后不再重复", PendingPull.isPending(app))
    assertNull("第二次 onResume 无事可做", FcmReceiver.onResume(app))
  }
}

// ───── ForegroundSyncServiceTest.kt ─────
/**
 * B4 第三张卡：前台服务常驻进度（Robolectric）。
 *
 * ## 这组测试能证明什么、不能证明什么
 *
 * 能证明：`startForeground` 真的被调用、通知挂在 Progress 渠道、进度只更新不重建、
 * 重复启动不产生第二条、停止后通知被撤、`onTimeout` 真的走停止路径、
 * 去重逻辑双向都成立、Manifest 声明齐全。
 *
 * **不能**证明：服务在真机上能活 12 小时不被系统杀。那取决于 OEM 的后台策略、
 * 电池优化白名单、内存压力 —— 本机无模拟器（A1 报告第 5 节），属 E2。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundSyncServiceTest {

  private lateinit var app: Application
  private lateinit var manager: NotificationManager

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    manager = app.getSystemService(NotificationManager::class.java)
    NotifyChannels.register(app)
    ForegroundSyncState.resetForTest()
  }

  private fun startIntent(taskId: String, step: Int = 0, total: Int = 100) =
    android.content.Intent(app, ForegroundSyncService::class.java).apply {
      action = ForegroundSyncService.ACTION_START
      putExtra(ForegroundSyncService.EXTRA_TASK_ID, taskId)
      putExtra(ForegroundSyncService.EXTRA_STEP, step)
      putExtra(ForegroundSyncService.EXTRA_TOTAL, total)
    }

  // ───────────────── startForeground 真的被调用 ─────────────────

  @Test
  fun 启动后真的调用了_startForeground_且挂在进度渠道() {
    val controller = Robolectric.buildService(ForegroundSyncService::class.java, startIntent("task-1", 30, 100))
      .create()
    controller.startCommand(0, 0)

    val shadow = shadowOf(controller.get())
    assertNotNull("startForeground 必须真的被调用", shadow.lastForegroundNotification)
    assertEquals(
      "前台通知必须用固定 id",
      ForegroundSyncState.SYNC_NOTIFICATION_ID,
      shadow.lastForegroundNotificationId
    )

    val notification = shadow.lastForegroundNotification
    assertEquals(
      "常驻通知必须挂在进度渠道（用户能单独关掉它）",
      NotifyKind.Progress.channelId,
      notification.channelId
    )
    // 蓝图：「用户可随时关闭」—— 不可划掉的通知必须有一个停止入口
    assertTrue("常驻通知必须有「停止跟盯」动作", notification.actions.isNotEmpty())
    assertEquals("停止跟盯", notification.actions[0].title.toString())
  }

  @Test
  fun 状态机从_Idle_进入_Running() {
    assertEquals(SyncState.Idle, ForegroundSyncState.current())

    Robolectric.buildService(ForegroundSyncService::class.java, startIntent("task-1", 30, 100))
      .create().startCommand(0, 0)

    val running = ForegroundSyncState.current()
    assertTrue("应进入 Running", running is SyncState.Running)
    assertEquals("task-1", (running as SyncState.Running).taskId)
    assertEquals(30, running.step)
    assertEquals(100, running.totalSteps)
    assertEquals("进度百分比", 30, running.percent)
    assertEquals("task-1", ForegroundSyncState.runningTaskId())
  }

  // ───────────────── 进度只更新不重建 ─────────────────

  @Test
  fun 进度更新只替换同一条通知_不产生第二条() {
    val controller = Robolectric.buildService(
      ForegroundSyncService::class.java,
      startIntent("task-1", 10, 100)
    ).create()
    controller.startCommand(0, 0)
    val serviceShadow = shadowOf(controller.get())

    // 连续三次更新
    for (step in listOf(40, 70, 95)) {
      val update = android.content.Intent(app, ForegroundSyncService::class.java).apply {
        action = ForegroundSyncService.ACTION_UPDATE
        putExtra(ForegroundSyncService.EXTRA_TASK_ID, "task-1")
        putExtra(ForegroundSyncService.EXTRA_STEP, step)
        putExtra(ForegroundSyncService.EXTRA_TOTAL, 100)
        putExtra(ForegroundSyncService.EXTRA_STARTED_AT, 1000L)
      }
      controller.get().onStartCommand(update, 0, 0)
    }

    // 「只更新不重建」的三条可观测证据：
    //
    // ① 通知栏里始终只有一条 —— 更新走的是同一 id 的 notify() 替换。
    // ② 没有被 stopForeground（重建会先撤再起）—— isForegroundStopped 保持 false。
    // ③ 内容真的是最后一次的值 —— 说明更新确实落到了系统里，不是被丢弃。
    //
    // 注意读的是 NotificationManager 里的那条（服务侧 ShadowService
    // 只保留 startForeground 传进去的那一份，不含后续 notify() 的更新）。
    assertEquals("进度更新不该堆出多条", 1, shadowOf(manager).allNotifications.size)
    assertFalse("不该中途退出前台", serviceShadow.isForegroundStopped)

    val latest = shadowOf(manager).getNotification(ForegroundSyncState.SYNC_NOTIFICATION_ID)
    assertNotNull("常驻通知必须还在", latest)
    assertEquals(
      "内容应是最后一次更新（95）",
      95,
      latest!!.extras.getInt(Notification.EXTRA_PROGRESS)
    )
    assertEquals("进度总量", 100, latest.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    assertEquals(
      "状态机也应是最后一次的值",
      95,
      (ForegroundSyncState.current() as SyncState.Running).step
    )
    // startForeground 只被调用过一次：id 始终是固定那个
    assertEquals(
      ForegroundSyncState.SYNC_NOTIFICATION_ID,
      serviceShadow.lastForegroundNotificationId
    )
  }

  @Test
  fun 总量未知时进度条是不确定的_而不是_0_百分比() {
    val controller = Robolectric.buildService(
      ForegroundSyncService::class.java,
      startIntent("task-unknown", step = 7, total = 0)
    ).create()
    controller.startCommand(0, 0)

    val running = ForegroundSyncState.current() as SyncState.Running
    assertNull("总量未知时 percent 必须是 null（不是 0）", running.percent)

    val n = shadowOf(controller.get()).lastForegroundNotification
    // indeterminate 的进度条：max=0 且 progress=0
    assertEquals("应是 indeterminate 进度", 0, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    assertTrue(
      "文案应说步数而不是百分比",
      n.extras.getString(Notification.EXTRA_TEXT)!!.contains("7 步")
    )
  }

  @Test
  fun 步数超过总量时百分比被夹在_100() {
    // 并发任务的计数抖动会送来 step > total 的瞬间值
    val state = SyncState.Running("t", step = 103, totalSteps = 100, startedAtMillis = 0)
    assertEquals(100, state.percent)
  }

  @Test
  fun 已用时不会被时钟回拨算成负数() {
    val state = SyncState.Running("t", 1, 10, startedAtMillis = 5_000)
    // 设备时钟回拨：now < startedAt
    assertEquals(0, state.elapsedSeconds(nowMillis = 1_000))
    assertEquals(3, state.elapsedSeconds(nowMillis = 8_000))
  }

  @Test
  fun 常驻通知文案含已用时_让用户知道跑了多久() {
    val controller = Robolectric.buildService(
      ForegroundSyncService::class.java,
      startIntent("task-1", 30, 100)
    ).create()
    controller.startCommand(0, 0)

    val text = shadowOf(controller.get()).lastForegroundNotification
      .extras.getString(Notification.EXTRA_TEXT).orEmpty()
    assertTrue("应含百分比", text.contains("30%"))
    assertTrue("应含步数", text.contains("30/100 步"))
    // startedAtMillis 若只写不读，用户就看不到「跑了多久」——
    // 而「回来时它还在跑」正是前台服务的核心价值
    assertTrue("应含已用时：$text", text.contains("已用时"))
  }

  // ───────────────── 用户看得见吗 ─────────────────

  @Test
  fun 通知权限被关时_visibility_报告看不见() {
    org.robolectric.Shadows.shadowOf(manager).setNotificationsEnabled(false)
    assertEquals(
      ForegroundSyncService.Visibility.NotificationSuppressed,
      ForegroundSyncState.visibility(app)
    )
  }

  @Test
  fun 进度渠道被关掉时_visibility_报告渠道被关() {
    NotifyChannels.register(app)
    // 渠道 importance 变 NONE 等价于用户关掉了这类通知
    manager.deleteNotificationChannel(NotifyKind.Progress.channelId)
    manager.createNotificationChannel(
      android.app.NotificationChannel(
        NotifyKind.Progress.channelId,
        NotifyKind.Progress.channelName,
        android.app.NotificationManager.IMPORTANCE_NONE
      )
    )
    assertEquals(
      ForegroundSyncService.Visibility.ChannelBlocked,
      ForegroundSyncState.visibility(app)
    )
  }

  @Test
  fun 一切正常时_visibility_报告可见() {
    NotifyChannels.register(app)
    assertEquals(
      ForegroundSyncService.Visibility.Visible,
      ForegroundSyncState.visibility(app)
    )
  }

  // ───────────────── 停止 ─────────────────

  @Test
  fun 停止后通知被撤掉_状态回到_Idle() {
    val controller = Robolectric.buildService(
      ForegroundSyncService::class.java,
      startIntent("task-1", 50, 100)
    ).create()
    val service = controller.get()
    controller.startCommand(0, 0)

    val shadow = shadowOf(service)
    assertNotNull(shadow.lastForegroundNotification)

    // 点「停止跟盯」
    val stop = android.content.Intent(app, ForegroundSyncService::class.java).apply {
      action = ForegroundSyncService.ACTION_STOP
    }
    service.onStartCommand(stop, 0, 0)

    assertTrue("必须退到前台之外", shadow.isForegroundStopped)
    assertTrue("通知必须被撤掉", shadow.notificationShouldRemoved)
    assertEquals(SyncState.Idle, ForegroundSyncState.current())
    assertEquals(SyncStopReason.USER_CANCELLED, ForegroundSyncState.lastStopReason())
    assertNull("不该再有跟盯任务", ForegroundSyncState.runningTaskId())
  }

  @Test
  fun 没有任务标识时不停在空转前台状态() {
    val intent = android.content.Intent(app, ForegroundSyncService::class.java).apply {
      action = ForegroundSyncService.ACTION_START
      // 不传 EXTRA_TASK_ID
    }
    val controller = Robolectric.buildService(ForegroundSyncService::class.java, intent).create()
    controller.get().onStartCommand(intent, 0, 0)

    assertNull("不该 startForeground", shadowOf(controller.get()).lastForegroundNotification)
    assertEquals(SyncState.Idle, ForegroundSyncState.current())
  }

  // ───────────────── Android 15 的时限回调 ─────────────────

  /**
   * 这是本卡最容易漏、后果最重的一条：
   * 不实现 onTimeout，Android 15 到点直接抛
   * ForegroundServiceDidNotStopInTimeException —— 应用崩溃。
   */
  @Test
  fun onTimeout_必须主动停止_并记录系统超时() {
    val controller = Robolectric.buildService(
      ForegroundSyncService::class.java,
      startIntent("task-long", 50, 100)
    ).create()
    val service = controller.get()
    controller.startCommand(0, 0)
    assertTrue(ForegroundSyncState.isRunning())

    service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    assertTrue("必须主动退出前台", shadowOf(service).isForegroundStopped)
    assertEquals(
      "停止原因必须能区分「系统不让跟了」和「任务完成了」",
      SyncStopReason.SYSTEM_TIMEOUT,
      ForegroundSyncState.lastStopReason()
    )
    assertEquals(SyncState.Idle, ForegroundSyncState.current())
  }

  @Test
  fun 系统回收时记录_SYSTEM_DESTROYED_而不是当成任务完成() {
    val controller = Robolectric.buildService(
      ForegroundSyncService::class.java,
      startIntent("task-1", 20, 100)
    ).create()
    controller.startCommand(0, 0)

    controller.destroy()

    assertEquals(
      "系统杀掉 ≠ 任务完成，原因必须分开记",
      SyncStopReason.SYSTEM_DESTROYED,
      ForegroundSyncState.lastStopReason()
    )
  }

  /**
   * `startForeground` 必须带 `foregroundServiceType`。
   *
   * 这一条专门补一个 Robolectric 的盲区：把实现里的
   * `startForeground(id, n, DATA_SYNC)` 改成两参数版（不带 type），
   * **其余 16 项全绿** —— 因为 shadow 不模拟 Android 14 的
   * `MissingForegroundServiceTypeException`。
   *
   * 也就是说「Manifest 里声明了 type」有测试守着，但「运行时真的把它传给
   * startForeground」没有 —— 而 14 上抛异常的正是后者。
   * 这里直接读 `Service.getForegroundServiceType()`：
   * 它返回的是运行时生效的 type，不带 type 的调用会拿不到 DATA_SYNC。
   */
  @Test
  fun 运行时的前台服务类型必须真的传到_startForeground() {
    val controller = Robolectric.buildService(
      ForegroundSyncService::class.java,
      startIntent("task-1", 10, 100)
    ).create()
    controller.startCommand(0, 0)

    // ShadowService 的 getForegroundServiceType() 是 protected，改用反射读。
    // 反射在这里不是权宜之计 —— 这个值没有公开 API 能读到，
    // 而「运行时 type 是不是真的传了」恰恰是 14 上唯一会炸的地方。
    val shadowClass = shadowOf(controller.get()).javaClass
    val method = generateSequence(shadowClass as Class<*>) { it.superclass }
      .mapNotNull { cls -> cls.declaredMethods.firstOrNull { it.name == "getForegroundServiceType" } }
      .firstOrNull()
    assertNotNull("ShadowService 应提供 getForegroundServiceType", method)
    method!!.isAccessible = true
    val type = method.invoke(shadowOf(controller.get())) as Int

    assertEquals(
      "运行时 type 必须是 dataSync（14 上不带 type 会抛异常）",
      android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      type
    )
  }

  // ───────────────── 与普通通知的互斥 ─────────────────

  @Test
  fun 前台服务跟盯期间_同一任务的进度单条不再投递() {
    Robolectric.buildService(ForegroundSyncService::class.java, startIntent("task-1", 30, 100))
      .create().startCommand(0, 0)
    assertTrue(ForegroundSyncState.isRunning())

    val notifier = MorrowNotifier(app)
    val content = NotifyContent.of(
      NotifyKind.Progress,
      fileName = "渲染.mov",
      groupKey = "task-1"
    )
    val result = notifier.post(content, NotifyDecision.Deliver(content))

    assertTrue(
      "同一任务不该在通知栏出现两条进度",
      result is MorrowNotifier.PostResult.TakenOverByForegroundService
    )
    assertEquals("task-1", (result as MorrowNotifier.PostResult.TakenOverByForegroundService).taskId)
    // 系统里只有前台服务那一条
    assertEquals(1, shadowOf(manager).allNotifications.size)
  }

  @Test
  fun 别的任务的进度单条仍然正常投递() {
    Robolectric.buildService(ForegroundSyncService::class.java, startIntent("task-1", 30, 100))
      .create().startCommand(0, 0)

    val notifier = MorrowNotifier(app)
    val other = NotifyContent.of(
      NotifyKind.Progress,
      fileName = "另一个任务.log",
      groupKey = "task-2"
    )
    val result = notifier.post(other, NotifyDecision.Deliver(other))

    assertTrue("别的任务不受影响", result is MorrowNotifier.PostResult.Posted)
    assertEquals("两条：常驻 + 另一个任务", 2, shadowOf(manager).allNotifications.size)
  }

  @Test
  fun 前台服务启动时撤掉同一任务的遗留单条() {
    val notifier = MorrowNotifier(app)
    // 先有一个普通进度单条（任务还没升级成前台服务跟盯）
    NotifyChannels.register(app)
    val content = NotifyContent.of(NotifyKind.Progress, fileName = "a.log", groupKey = "task-1")
    notifier.post(content, NotifyDecision.Deliver(content))
    assertNotNull(
      "前置条件：单条应存在",
      shadowOf(manager).getNotification(progressNotificationId("task-1"))
    )

    // 升级成前台服务跟盯
    Robolectric.buildService(ForegroundSyncService::class.java, startIntent("task-1", 10, 100))
      .create().startCommand(0, 0)

    assertNull(
      "旧的单条必须被撤掉，否则通知栏有两条矛盾的数字",
      shadowOf(manager).getNotification(progressNotificationId("task-1"))
    )
  }

  // ───────────────── id 空间不重叠 ─────────────────

  @Test
  fun 三个固定_id_互不重叠且与普通_id_区间不重叠() {
    val digest = NotifyPolicy.DIGEST_ID
    val sync = ForegroundSyncState.SYNC_NOTIFICATION_ID
    val normalMax = 100_000

    assertTrue("汇总 id 与普通 id 不重叠", digest > normalMax)
    assertTrue("前台服务 id 与普通 id 不重叠", sync > normalMax)
    assertTrue("两个固定 id 不能相同", digest != sync)

    // 抽一批普通 id 确认都落在 1..100_000
    for (task in listOf("", "a", "task-1", "很长的任务标识".repeat(20))) {
      val id = progressNotificationId(task)
      assertTrue("普通 id 必须在 1..100000：$id", id in 1..normalMax)
    }
  }

  // ───────────────── Manifest 声明 ─────────────────

  @Test
  fun Manifest_声明了前台服务类型与权限() {
    val info = app.packageManager.getServiceInfo(
      android.content.ComponentName(app, ForegroundSyncService::class.java),
      0
    )
    assertNotNull("服务必须登记在 Manifest", info)
    assertFalse("服务不该导出", info.exported)
    assertTrue(
      "Android 14 起必须声明 dataSync 类型",
      info.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0
    )

    // 权限：缺任一条都会在真机上炸（FOREGROUND_SERVICE_DATA_SYNC 在 14 上抛 SecurityException）
    val declared = app.packageManager
      .getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
      .requestedPermissions?.toSet() ?: emptySet()
    for (perm in listOf(
      android.Manifest.permission.FOREGROUND_SERVICE,
      "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
      "android.permission.POST_NOTIFICATIONS"
    )) {
      assertTrue("必须申请 $perm", perm in declared)
    }
  }

  // ───────────────── 生产入口接线 ─────────────────

  /**
   * 记录「调用方到底用了哪个 API」的 Context。
   *
   * ## 为什么不能只看 `shadowOf(app).nextStartedService`
   *
   * Robolectric 的 shadow 把 `startService` 与 `startForegroundService`
   * **放进同一个队列**，两者的可观测结果完全一样。于是
   * 「用了 startService 而不是 startForegroundService」这种**真实且致命**
   * 的错误（Android 8 起对前台服务用 startService 会 ANR / 抛
   * `IllegalStateException`）在测试里完全看不出来 —— 实测把实现改成
   * startService，原有断言照样全绿。
   *
   * 所以这里换一种做法：传一个自己重写这两个方法的 Context，
   * 直接记录「哪个方法被调用了」。这是唯一能区分它们的方式。
   */
  private class RecordingContext(base: android.content.Context) : android.content.ContextWrapper(base) {
    var foregroundCalls = 0
    var plainServiceCalls = 0
    var lastIntent: android.content.Intent? = null

    override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? {
      foregroundCalls++
      lastIntent = service
      return android.content.ComponentName(baseContext, ForegroundSyncService::class.java)
    }

    override fun startService(service: android.content.Intent): android.content.ComponentName? {
      plainServiceCalls++
      lastIntent = service
      return android.content.ComponentName(baseContext, ForegroundSyncService::class.java)
    }
  }

  /**
   * 防「代码写好了但没人调」：B 轨已踩过两次（B3 第一批忘传 pairing、
   * 第五批忘调 startLanStack，两次单测全绿）。
   *
   * 同时钉住「用哪个 API」：Android 8 起前台服务必须用
   * `startForegroundService`，用 `startService` 会在 5 秒内 ANR。
   */
  @Test
  fun 启动方法必须用_startForegroundService_而不是_startService() {
    val recorder = RecordingContext(app)
    ForegroundSyncService.start(recorder, "task-x", 5, 50)

    assertEquals("必须真的调用 startForegroundService", 1, recorder.foregroundCalls)
    assertEquals(
      "不能退回普通的 startService（26+ 上会 ANR）",
      0,
      recorder.plainServiceCalls
    )
    val intent = recorder.lastIntent!!
    assertEquals(ForegroundSyncService::class.java.name, intent.component?.className)
    assertEquals("task-x", intent.getStringExtra(ForegroundSyncService.EXTRA_TASK_ID))
    assertEquals(5, intent.getIntExtra(ForegroundSyncService.EXTRA_STEP, -1))
    assertEquals(50, intent.getIntExtra(ForegroundSyncService.EXTRA_TOTAL, -1))
  }

  @Test
  fun 进度更新也必须用_startForegroundService() {
    val recorder = RecordingContext(app)
    ForegroundSyncService.update(recorder, "task-y", 7, 10, 1234L)

    assertEquals("更新不前进前台也会被系统限制", 1, recorder.foregroundCalls)
    assertEquals(0, recorder.plainServiceCalls)
    val intent = recorder.lastIntent!!
    assertEquals("task-y", intent.getStringExtra(ForegroundSyncService.EXTRA_TASK_ID))
    assertEquals(ForegroundSyncService.ACTION_UPDATE, intent.action)
  }

  @Test
  fun 停止方法真的发出停止指令() {
    val recorder = RecordingContext(app)
    ForegroundSyncService.stop(recorder)

    // 停止走普通 startService 是对的：服务已在运行，不需要再走前台语义
    assertEquals(1, recorder.plainServiceCalls)
    assertEquals(ForegroundSyncService.ACTION_STOP, recorder.lastIntent!!.action)
  }
}

// ───── PendingPullTest.kt ─────
/**
 * B4 第四张卡：打开时补拉（Tier 3 的后半截）。
 *
 * 蓝图原文：「被系统杀掉后靠 FCM 唤醒；唤醒后先 snapshot_required
 * 全量对齐，再进增量。」以及 Tier 3 的「打开时补拉事件，不实时」。
 *
 * 这三句合起来意味着补拉标记必须满足三件事，下面每组用例对应一件：
 *
 * 1. **跨进程存活** —— 应用被杀正是要解决的场景，标记放内存等于没做；
 * 2. **多次推送合并** —— 离线三小时来 20 条，打开时拉一次，不是拉 20 次；
 * 3. **`snapshotRequired` 粘性** —— 先要全量又被后来的增量推送覆盖，
 *    会按增量补漏，中间超出 ring buffer 的事件永久丢失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingPullTest {

  private lateinit var app: Application

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    PendingPull.clearForTest(app)
  }

  // ───────────────── 基本写入与消费 ─────────────────

  @Test
  fun 初始状态没有待补拉() {
    assertFalse(PendingPull.isPending(app))
    assertNull(PendingPull.consume(app))
  }

  @Test
  fun 标记后_待补拉为真() {
    PendingPull.mark(app, needsSnapshot = false)
    assertTrue(PendingPull.isPending(app))
  }

  @Test
  fun consume_取出后清空_不会补拉两次() {
    PendingPull.mark(app, needsSnapshot = false, nowMillis = 1000)

    val first = PendingPull.consume(app, nowMillis = 2000)
    assertNotNull("第一次应拿到请求", first)
    assertNull("第二次必须为空，否则会补拉两遍", PendingPull.consume(app))
    assertFalse(PendingPull.isPending(app))
  }

  @Test
  fun consume_带回首次标记到现在的时间() {
    PendingPull.mark(app, needsSnapshot = false, nowMillis = 10_000)
    val req = PendingPull.consume(app, nowMillis = 70_000)!!
    assertEquals("隔了 60 秒", 60_000L, req.sinceFirstMarkMillis)
  }

  @Test
  fun 时钟回拨时_间隔不会为负() {
    PendingPull.mark(app, needsSnapshot = false, nowMillis = 70_000)
    val req = PendingPull.consume(app, nowMillis = 10_000)!!
    assertEquals(0L, req.sinceFirstMarkMillis)
  }

  // ───────────────── 合并：多次推送只产生一个请求 ─────────────────

  @Test
  fun 多次推送合并成一个补拉请求() {
    repeat(20) { PendingPull.mark(app, needsSnapshot = false, nowMillis = 1000L + it) }

    val req = PendingPull.consume(app, nowMillis = 2000)!!
    assertEquals("20 条推送只该产生 1 次补拉", 20, req.markCount)
    // 消费一次就全清掉
    assertNull(PendingPull.consume(app))
  }

  @Test
  fun markCount_在消费后归零() {
    repeat(3) { PendingPull.mark(app, needsSnapshot = false) }
    assertEquals(3, PendingPull.consume(app)!!.markCount)
    PendingPull.mark(app, needsSnapshot = false)
    assertEquals("新的一轮从 1 开始", 1, PendingPull.consume(app)!!.markCount)
  }

  // ───────────────── 关键：snapshotRequired 粘性 ─────────────────

  /**
   * 这是本卡最重要的一条。
   *
   * 桌面端的 ring buffer 只有 2000 条。手机离线太久时会返回
   * `snapshot_required`，要求下次全量对齐。如果实现是「最后写入者获胜」，
   * 那么：
   *
   *   1. 16:00 收到「超出 buffer，需要全量」（snapshot=true）
   *   2. 16:05 收到一条普通增量推送（snapshot=false）
   *   3. 用户 17:00 打开应用 → 按增量补拉 → 中间 500 条事件永久丢失
   *
   * 而且丢得**毫无痕迹**：用户只看到「有些事没发生」，没有任何报错。
   * 所以 snapshot 一旦置上就必须粘住，直到真正消费。
   */
  @Test
  fun 全量要求不会被后来的增量推送降级() {
    PendingPull.mark(app, needsSnapshot = true, nowMillis = 1000)
    // 后续来了 5 条普通推送
    repeat(5) { PendingPull.mark(app, needsSnapshot = false, nowMillis = 2000L + it) }

    val req = PendingPull.consume(app)!!
    assertTrue("全量要求必须粘住", req.snapshotRequired)
    assertEquals("推送仍然被合并计数", 6, req.markCount)
  }

  @Test
  fun 消费之后_全量要求被清掉_不会影响下一轮() {
    PendingPull.mark(app, needsSnapshot = true)
    PendingPull.consume(app)

    PendingPull.mark(app, needsSnapshot = false)
    assertFalse("新的一轮不该继承上一轮的全量要求", PendingPull.consume(app)!!.snapshotRequired)
  }

  @Test
  fun 先增量后全量_最终为全量() {
    PendingPull.mark(app, needsSnapshot = false)
    PendingPull.mark(app, needsSnapshot = true)
    assertTrue(PendingPull.consume(app)!!.snapshotRequired)
  }

  // ───────────────── 失败重放：不能降级 ─────────────────

  @Test
  fun 补拉失败后放回去_仍保留全量要求() {
    PendingPull.mark(app, needsSnapshot = true, nowMillis = 1000)
    val req = PendingPull.consume(app, nowMillis = 5000)!!
    assertTrue(req.snapshotRequired)

    // 假设补拉过程中网络断了，调用方把请求放回去
    PendingPull.restore(app, req)

    val again = PendingPull.consume(app, nowMillis = 7000)!!
    assertTrue("重放时全量要求不能被降级 —— 否则那批事件永久丢失", again.snapshotRequired)
    assertEquals("计数也要保留（诊断用）", req.markCount, again.markCount)
    // 首次标记时刻必须原样保留：否则「离线多久」会把 consume→restore
    // 之间的间隔算进去，判断凭空变短
    assertEquals("首次标记时刻应原样保留", 1000L, again.firstMarkedAtMillis)
    assertEquals("离线时长从首次标记算起", 6000L, again.sinceFirstMarkMillis)
  }

  @Test
  fun restore_后可以再次取走() {
    PendingPull.mark(app, needsSnapshot = false)
    val req = PendingPull.consume(app)!!
    PendingPull.restore(app, req)
    assertTrue(PendingPull.isPending(app))
    assertNotNull(PendingPull.consume(app))
  }

  // ───────────────── 跨实例（模拟进程重启） ─────────────────

  @Test
  fun 标记落在持久存储_换一个_context_也能读到() {
    PendingPull.mark(app, needsSnapshot = true)

    // 用一个新的 ContextWrapper 读 —— 它共享同一份 SharedPreferences
    val other = android.content.ContextWrapper(app)
    assertTrue("标记必须跨进程存活（应用被杀正是要解决的场景）", PendingPull.isPending(other))
    assertTrue(PendingPull.consume(other)!!.snapshotRequired)
    assertFalse("清也要清到同一份存储", PendingPull.isPending(app))
  }
}

// ───── MainActivityPendingPullTest.kt ─────
/**
 * C1 卡里兑现的**第二处接线**：`MainActivity.onResume` → `FcmReceiver.onResume`。
 *
 * 为什么单独一组测试：B 轨已经把补拉标记写好了（`FcmReceiver.onMessage` 里 `mark`），
 * 也有人能取（`FcmReceiver.onResume`），但**没有任何 Activity 调它** ——
 * 标记会一直躺在 SharedPreferences 里没人取。这正是 B 轨前几张卡反复出现的
 * 「逻辑在、没人调用」。所以这里直接驱动真实 Activity 的生命周期。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityPendingPullTest {

  private lateinit var app: Application

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    PendingPull.clearForTest(app)
    FcmDiagnostics.resetForTest()
    NotifyChannels.register(app)
    MainActivity.resetPendingPullForTest()
  }

  @Test
  fun 冷启动时没有待补拉_发布状态为空() {
    Robolectric.buildActivity(MainActivity::class.java).setup()
    assertNull("没有待补拉时不该发布任何请求", MainActivity.pendingPullRequest.value)
  }

  @Test
  fun 有补拉标记时_onResume_会取走并发布() {
    // 模拟：应用被杀期间来了推送（B4 的 FcmReceiver 会 mark）
    FcmReceiver.onMessage(app, mapOf("kind" to "stuck", "taskId" to "t-9"), false)
    assertTrue("标记已写入", PendingPull.isPending(app))

    Robolectric.buildActivity(MainActivity::class.java).setup()

    val published = MainActivity.pendingPullRequest.value
    assertNotNull("onResume 必须取走并发布", published)
    assertEquals(1, published!!.markCount)
    assertFalse("取走后标记应清空，不能补拉两次", PendingPull.isPending(app))
  }

  @Test
  fun 第二次_onResume_不会重复补拉() {
    FcmReceiver.onMessage(app, mapOf("kind" to "completed", "taskId" to "t-1"), false)

    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    assertNotNull(MainActivity.pendingPullRequest.value)

    // 模拟切后台再回来。上一轮发布的值还在（界面可能仍在展示），
    // 但 PendingPull 里已无标记 —— 关键是「不会又取到一次」。
    // 用一个新 Activity 验证更干净。
    controller.pause().resume()
    MainActivity.resetPendingPullForTest()
    Robolectric.buildActivity(MainActivity::class.java).setup()
    assertNull("已经没有标记了，第二次不该再取到", MainActivity.pendingPullRequest.value)
  }

  @Test
  fun 冷启动之后才来的推送_热回到前台也能取到() {
    // 这一条区分 onResume 与 onCreate —— 两者在 `buildActivity().setup()` 里
    // 都会跑，所以前面几条测试**分不出来**（把接线挪到 onCreate 它们照样全绿）。
    //
    // 真实场景：应用已经在后台，期间来了推送；用户切回来。
    // 此时 onCreate 不会再执行，只有 onResume 会。若接线放在 onCreate，
    // 这条补拉会一直等到下次冷启动 —— 也就是「点了通知进来却发现没同步」。
    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    assertNull("冷启动时不该有待补拉", MainActivity.pendingPullRequest.value)

    // 推送到（应用在后台 / 进程还在）
    controller.pause()
    FcmReceiver.onMessage(app, mapOf("kind" to "completed", "taskId" to "t-1"), false)
    assertTrue("标记已写入", PendingPull.isPending(app))

    // 用户切回前台
    controller.resume()

    assertNotNull(
      "热回到前台必须取到补拉 —— 接在 onCreate 会漏掉这一整类场景",
      MainActivity.pendingPullRequest.value
    )
    assertFalse("取走后标记清空", PendingPull.isPending(app))
  }

  @Test
  fun 快照要求会随请求一起发布() {
    FcmReceiver.onMessage(
      app,
      mapOf("kind" to "completed", "snapshotRequired" to "true"),
      false
    )
    Robolectric.buildActivity(MainActivity::class.java).setup()
    assertTrue(
      "全量要求必须一起传出去，否则补拉会按增量走、漏事件",
      MainActivity.pendingPullRequest.value!!.snapshotRequired
    )
  }

  @Test
  fun 界面清掉待补拉后状态回到空() {
    FcmReceiver.onMessage(app, mapOf("kind" to "stuck"), false)
    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    assertNotNull(MainActivity.pendingPullRequest.value)

    // 补拉完成后由上层清掉（工位页的「补拉中」随之消失）
    controller.get().clearPendingPull()
    assertNull(MainActivity.pendingPullRequest.value)
  }

  @Test
  fun 发布状态是可观察的_变更会触发重组() {
    // 用 mutableStateOf 而不是普通字段：普通字段改了不会触发重组，
    // 界面会一直显示旧值且没有任何报错。这一条盯的是「它确实是个状态」。
    val state = MainActivity.pendingPullRequest
    assertNull(state.value)
    FcmReceiver.onMessage(app, mapOf("kind" to "approval", "focus" to "ap-1"), false)
    Robolectric.buildActivity(MainActivity::class.java).setup()
    assertNotNull("值已更新", state.value)
    assertTrue("是 Compose 状态而不是普通字段", state is androidx.compose.runtime.MutableState<*>)
  }
}
