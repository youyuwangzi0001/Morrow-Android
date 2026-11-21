package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

// ───── ApprovalQueueTest.kt ─────
/**
 * C4 审批队列测试。
 *
 * 分组对应蓝图 C4 的四条验收加两类边界：
 * 1. 只能 once / deny（SEC3）
 * 2. 危险动作允许被禁用
 * 3. 拒绝真的下发 deny
 * 4. 超时失效且队列同步
 * 5. 生物识别不可跳过、取消即拒绝
 * 6. 去重与重试
 */
class ApprovalQueueTest {

  // ─────────── 工具 ───────────

  private fun item(
    id: String = "ap-1",
    tool: String = "run_command",
    danger: Boolean = DangerTools.isDanger(tool),
    risk: ToolRisk = ToolRisk.Medium,
    created: Long = 0L,
    member: String? = null,
  ) = ApprovalItem(
    id = id,
    toolName = tool,
    title = "运行命令",
    target = "npm test",
    paramSummary = "npm test -- --run",
    category = ToolCategory.Exec,
    risk = risk,
    danger = danger,
    memberLabel = member,
    sessionTitle = "重构登录页",
    createdAtMillis = created,
    allowAlwaysOnDesktop = true,
  )

  /** 记录下发内容的假 sink */
  private class Sink {
    val calls = mutableListOf<Triple<String, Boolean, DenyReason?>>()
    var result = true
    fun asFn(): (String, Boolean, DenyReason?) -> Boolean = { id, allow, reason ->
      calls += Triple(id, allow, reason)
      result
    }
  }

  // ═══════════ 1. 只能 once / deny ═══════════

  @Test
  fun 决策枚举里没有always() {
    // 蓝图 §05 SEC3：手机端只能回 once 或 deny，不能回 always。
    // 这条断言守的是「类型上写不出第三个值」—— 比注释可靠。
    val names = ApprovalQueue.Decision.entries.map { it.name }
    assertEquals("手机端只能有两种决策", listOf("AllowOnce", "Deny"), names)
    assertFalse(
      "不能出现 Always 之类的第三个决策",
      names.any { it.contains("Always", ignoreCase = true) }
    )
  }

  @Test
  fun 即使桌面允许始终允许手机也只下发一次性批准() {
    // 桌面的 PermissionRequest.allowAlways 可能是 true，
    // 但手机主动放弃这个能力：下发的永远是 allow=true 的一次性批准，
    // 不能带任何「记住」语义。
    val s = Sink()
    val q = ApprovalQueue(s.asFn(), clock = { 0L })
    q.upsert(item())
    q.decide("ap-1", ApprovalQueue.Decision.AllowOnce)

    assertEquals(1, s.calls.size)
    val (id, allow, reason) = s.calls[0]
    assertEquals("ap-1", id)
    assertTrue("应下发允许", allow)
    assertNull("允许不带任何 reason/作用域标记", reason)
  }

  // ═══════════ 2. 危险动作 ═══════════

  @Test
  fun 危险动作的允许按钮是禁用的() {
    val q = ApprovalQueue({ _, _, _ -> true }, clock = { 0L })
    q.upsert(item(id = "ap-danger", tool = "mcp_call"))

    assertTrue("mcp_call 在桌面的 ALWAYS_APPROVAL_TOOLS 里", DangerTools.isDanger("mcp_call"))
    assertFalse("危险动作不能允许", q.get("ap-danger")!!.canAllow(0L))
    assertTrue(
      "危险动作必须有说明文案，不能只禁用一个按钮不解释",
      !DangerTools.reasonOf("mcp_call").isNullOrBlank()
    )
  }

  @Test
  fun 危险动作即使绕过界面点允许也不下发() {
    // UI 的禁用态只是第一道。无障碍服务、脚本、将来某个新入口都可能绕过去。
    // 逻辑层必须再挡一次，而且不能把这条审批悄悄丢掉。
    val s = Sink()
    val q = ApprovalQueue(s.asFn(), clock = { 0L })
    q.upsert(item(id = "ap-danger", tool = "computer_click"))

    val outcome = q.decide("ap-danger", ApprovalQueue.Decision.AllowOnce)
    assertTrue("必须是失败结果", outcome is ApprovalOutcome.Failed)
    assertTrue(s.calls.isEmpty())
    assertNotNull("审批要留在队列里（用户仍可拒绝），不能消失", q.get("ap-danger"))
  }

  @Test
  fun 危险工具集与桌面十八个逐字一致() {
    // 桌面 src/main/features.ts:3184 ALWAYS_APPROVAL_TOOLS
    val desktop = setOf(
      "http_request", "mcp_call", "mcp_list", "mcp_resources", "mcp_prompt",
      "browser_click", "browser_type", "browser_press", "browser_tab",
      "computer_click", "computer_type", "computer_key", "computer_launch_app",
      "computer_install_package", "computer_download_file", "computer_run_installer",
      "computer_invoke", "computer_set_value", "computer_drag",
    )
    // 桌面 19 个（5 个 mcp + 4 个 browser + 10 个 computer）
    assertEquals(19, desktop.size)
    assertEquals("与桌面集合必须完全相同", desktop, DangerTools.all)
    // 双向：多一个少一个都算漂移
    assertTrue("不能多", DangerTools.all.all { it in desktop })
    assertTrue("不能少", desktop.all { it in DangerTools.all })
  }

  @Test
  fun 普通写操作不按危险处理() {
    // 反向确认：把危险集写得过宽会让所有审批都不可用
    listOf("write_file", "edit_file", "run_command", "terminal", "swarm").forEach {
      assertFalse("$it 不在桌面 ALWAYS_APPROVAL_TOOLS 里", DangerTools.isDanger(it))
    }
  }

  // ═══════════ 3. 拒绝真的下发 ═══════════

  @Test
  fun 拒绝会真的下发deny并带原因() {
    val s = Sink()
    val q = ApprovalQueue(s.asFn(), clock = { 0L })
    q.upsert(item())
    q.decide("ap-1", ApprovalQueue.Decision.Deny)

    assertEquals(1, s.calls.size)
    val (id, allow, reason) = s.calls[0]
    assertEquals("ap-1", id)
    assertFalse("必须下发 allow=false", allow)
    assertEquals("拒绝要带原因，便于桌面回灌给模型", DenyReason.User, reason)
  }

  @Test
  fun 拒绝后从队列移除不会重复下发() {
    val s = Sink()
    val q = ApprovalQueue(s.asFn(), clock = { 0L })
    q.upsert(item())
    q.decide("ap-1", ApprovalQueue.Decision.Deny)
    val second = q.decide("ap-1", ApprovalQueue.Decision.Deny)

    assertTrue(second is ApprovalOutcome.Missing)
    assertEquals("只能下发一次", 1, s.calls.size)
  }

  @Test
  fun 下发失败可以重试而不是永久卡住() {
    val s = Sink()
    s.result = false
    val q = ApprovalQueue(s.asFn(), clock = { 0L })
    q.upsert(item())

    val first = q.decide("ap-1", ApprovalQueue.Decision.Deny)
    assertTrue(first is ApprovalOutcome.Failed)
    assertNotNull("失败后审批必须还在", q.get("ap-1"))

    s.result = true
    val second = q.decide("ap-1", ApprovalQueue.Decision.AllowOnce)
    assertTrue("重试应成功", second is ApprovalOutcome.Sent)
    assertEquals(2, s.calls.size)
  }

  @Test
  fun 全部拒绝逐条下发一条失败不影响其余() {
    val s = Sink()
    val q = ApprovalQueue(s.asFn(), clock = { 0L })
    q.upsert(item(id = "a"))
    q.upsert(item(id = "b"))
    q.upsert(item(id = "c"))

    val results = q.denyAll()
    assertEquals(3, results.size)
    assertEquals("三条都要真的下发", 3, s.calls.size)
    assertTrue("全部是拒绝", s.calls.all { !it.second })
    assertEquals(0, q.size)
  }

  // ═══════════ 4. 超时失效 ═══════════

  @Test
  fun 超过两分钟自动失效且从队列消失() {
    var now = 0L
    val s = Sink()
    val q = ApprovalQueue(s.asFn(), clock = { now })
    q.upsert(item(created = 0L))

    now = ApprovalItem.TTL_MILLIS - 1
    assertEquals("差 1 毫秒仍有效", 1, q.size)
    assertTrue(q.get("ap-1")!!.canAllow(now))

    now = ApprovalItem.TTL_MILLIS
    assertEquals("到点即失效", 0, q.size)
    assertNull("失效后取不到", q.get("ap-1"))
  }

  @Test
  fun 过期后点允许不会下发任何决策() {
    // 这条最关键：过期的审批如果还下发「允许」，
    // 桌面可能已经把这条废弃、甚至复用 id 给新请求，
    // 那条允许会批准另一件用户没看过的事。
    var now = 0L
    val s = Sink()
    val q = ApprovalQueue(s.asFn(), clock = { now })
    q.upsert(item())
    now = ApprovalItem.TTL_MILLIS + 1

    val outcome = q.decide("ap-1", ApprovalQueue.Decision.AllowOnce)
    assertTrue("必须是失败", outcome is ApprovalOutcome.Failed)
    assertTrue("一条都不能下发", s.calls.isEmpty())
  }

  @Test
  fun sweepExpired把过期的清出去并返回id() {
    // 只是「不显示」不够：size 仍是旧值的话，顶栏会写「3 条待审批」
    // 而列表里一条都看不到，用户会以为界面坏了。
    var now = 0L
    val q = ApprovalQueue({ _, _, _ -> true }, clock = { now })
    q.upsert(item(id = "old", created = 0L))
    now = 150_000L
    q.upsert(item(id = "fresh", created = 150_000L))

    assertEquals(1, q.size)
    val gone = q.sweepExpired()
    assertEquals(listOf("old"), gone)
    assertEquals(1, q.size)
  }

  @Test
  fun 重复推送不刷新时效() {
    // 若用新时间覆盖，用户每收到一次重复推送就多 2 分钟，
    // 等于把「时效」这个约束悄悄取消了。
    var now = 0L
    val q = ApprovalQueue({ _, _, _ -> true }, clock = { now })
    q.upsert(item(created = 0L))
    now = 100_000L
    q.upsert(item(created = 100_000L))

    assertEquals("产生时间必须还是最初那次", 0L, q.get("ap-1")!!.createdAtMillis)
    now = ApprovalItem.TTL_MILLIS
    assertEquals("按最初时间算，此刻已失效", 0, q.size)
  }

  // ═══════════ 5. 生物识别 ═══════════

  @Test
  fun 没有生物识别硬件时只能拒绝不能允许() {
    // 最容易写错的实现是 `if (available) prompt() else proceed()` ——
    // 在「设备没录指纹」时直接放行，恰好把最需要保护的用户放过去了。
    val verdict = BiometricGate.gate(BiometricGate.Capability.NoHardware)
    assertTrue(verdict is BiometricGate.Verdict.DenyOnly)
  }

  @Test
  fun 四种不可用情形都只能拒绝() {
    listOf(
      BiometricGate.Capability.NoHardware,
      BiometricGate.Capability.NotEnrolled,
      BiometricGate.Capability.Unavailable,
      BiometricGate.Capability.PolicyBlocked,
    ).forEach { cap ->
      val v = BiometricGate.gate(cap)
      assertTrue("$cap 必须只能拒绝", v is BiometricGate.Verdict.DenyOnly)
      assertTrue(
        "每种情形都要有可读文案",
        (v as BiometricGate.Verdict.DenyOnly).why.message.isNotBlank()
      )
    }
  }

  @Test
  fun 取消与出错都视为拒绝() {
    // 蓝图：取消即视为拒绝。
    // 「保持待批」会让用户误以为自己还没做决定，
    // 而审批其实已经从他眼前消失过一次。
    assertEquals(
      BiometricGate.Decision.Deny,
      BiometricGate.resolve(BiometricGate.AuthResult.Cancelled)
    )
    assertEquals(
      BiometricGate.Decision.Deny,
      BiometricGate.resolve(BiometricGate.AuthResult.Error(7, "用户取消"))
    )
    assertEquals(
      BiometricGate.Decision.Deny,
      BiometricGate.resolve(BiometricGate.AuthResult.Lockout)
    )
  }

  @Test
  fun 只有认证成功才能允许() {
    assertEquals(
      BiometricGate.Decision.Allow,
      BiometricGate.resolve(BiometricGate.AuthResult.Succeeded)
    )
  }

  @Test
  fun 设备就绪时闸门放行到认证() {
    val v = BiometricGate.gate(BiometricGate.Capability.Ready)
    assertTrue(v is BiometricGate.Verdict.Proceed)
    assertTrue((v as BiometricGate.Verdict.Proceed).allow)
  }

  // ═══════════ 6. 队列行为 ═══════════

  @Test
  fun 危险动作排在最前() {
    val q = ApprovalQueue({ _, _, _ -> true }, clock = { 0L })
    q.upsert(item(id = "safe", tool = "write_file", created = 0L))
    q.upsert(item(id = "danger", tool = "mcp_call", risk = ToolRisk.High, created = 10L))

    assertEquals("危险的要在最前，即使它更晚产生", "danger", q.pending.first().id)
  }

  @Test
  fun 同类按产生时间先来先处理() {
    val q = ApprovalQueue({ _, _, _ -> true }, clock = { 0L })
    q.upsert(item(id = "later", created = 5000L))
    q.upsert(item(id = "earlier", created = 1000L))

    assertEquals(listOf("earlier", "later"), q.pending.map { it.id })
  }

  @Test
  fun 撤销的审批从队列消失且不能批准() {
    val s = Sink()
    val q = ApprovalQueue(s.asFn(), clock = { 0L })
    q.upsert(item())
    assertTrue(q.revoke("ap-1"))
    assertNull(q.get("ap-1"))
    assertTrue(q.decide("ap-1", ApprovalQueue.Decision.AllowOnce) is ApprovalOutcome.Missing)
    assertTrue("撤销后不能下发", s.calls.isEmpty())
  }

  @Test
  fun 通知栏与审批页共用同一套规则() {
    // B4 的 ApprovalActions 需要 knownApprovalIds，此前生产代码里没有来源。
    val s = Sink()
    val q = ApprovalQueue(s.asFn(), clock = { 0L })
    q.upsert(item(id = "ap-1"))

    assertTrue(q.dispatchFromNotification("ap-1", allow = false))
    assertEquals(1, s.calls.size)
    assertFalse(s.calls[0].second)
    assertFalse("同一入口不能重复下发", q.dispatchFromNotification("ap-1", allow = false))
  }

  @Test
  fun 成员来源会带上以便界面标注出处() {
    val q = ApprovalQueue({ _, _, _ -> true }, clock = { 0L })
    q.upsert(item(member = "T3 · backend"))
    assertEquals("T3 · backend", q.pending.first().memberLabel)
  }

  @Test
  fun 未知类别与风险按最严处理() {
    // fail-closed：不认识的 wire 值不能当成 read 放行
    assertEquals(ToolCategory.Exec, ToolCategory.fromWire("future_category"))
    assertEquals(ToolRisk.High, ToolRisk.fromWire("unknown"))
    assertEquals(ToolCategory.Read, ToolCategory.fromWire("read"))
    assertEquals(ToolRisk.Low, ToolRisk.fromWire("low"))
  }

  @Test
  fun 剩余秒数向上取整且不为负() {
    val it = item(created = 0L)
    assertEquals(120, it.remainingSeconds(0L))
    assertEquals(1, it.remainingSeconds(ApprovalItem.TTL_MILLIS - 1))
    assertEquals(0, it.remainingSeconds(ApprovalItem.TTL_MILLIS))
    assertEquals(0, it.remainingSeconds(ApprovalItem.TTL_MILLIS + 99_000L))
  }
}

// ───── ApprovalScreenTest.kt ─────
/**
 * 审批页渲染测试 —— 逐条覆盖蓝图 C4 的四条验收。
 *
 * 蓝图原文：
 * 1. 每次允许都触发 BiometricPrompt（不可跳过，取消即视为拒绝）
 * 2. 危险动作的允许按钮为禁用态并有说明
 * 3. 拒绝后桌面立即收到 deny（<500ms）
 * 4. 按钮纵向顺序固定为上拒绝下允许
 *
 * 第 1 条里的 BiometricPrompt 本身无法在 Robolectric 里真跑（没有指纹硬件），
 * 所以拆成两半：**闸门语义**由 [BiometricGate] 的纯逻辑测（见 ApprovalQueueTest），
 * **界面表达**在这里测 —— 设备不可用时不允许出现可点的「允许」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ApprovalScreenTest {

  @get:Rule val compose = createComposeRule()

  private fun item(
    id: String = "ap-1",
    tool: String = "run_command",
    danger: Boolean = tool in setOf("mcp_call", "computer_click", "http_request"),
    risk: ToolRisk = ToolRisk.Medium,
    created: Long = 0L,
    member: String? = null,
  ) = ApprovalItem(
    id = id,
    toolName = tool,
    title = "运行命令",
    target = "npm test",
    paramSummary = "npm test -- --run",
    category = ToolCategory.Exec,
    risk = risk,
    danger = danger,
    memberLabel = member,
    sessionTitle = "重构登录页",
    createdAtMillis = created,
    allowAlwaysOnDesktop = true,
  )

  private fun render(
    items: List<ApprovalItem>,
    now: Long = 0L,
    onAllow: (String) -> Unit = {},
    onDeny: (String) -> Unit = {},
    onDenyAll: () -> Unit = {},
    biometricUnavailable: BiometricGate.NoBiometricReason? = null,
  ) {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ApprovalScreen(
          items = items,
          nowMillis = now,
          onAllow = onAllow,
          onDeny = onDeny,
          onDenyAll = onDenyAll,
          onBack = {},
          biometricUnavailable = biometricUnavailable,
        )
      }
    }
    compose.waitForIdle()
  }

  // ═══════════ 验收 4：按钮纵向顺序 ═══════════

  @Test
  fun 按钮顺序固定为上拒绝下允许() {
    // 「上拒下允」不是审美偏好：拇指从下往上滑先碰到下面那个按钮，
    // 把「允许」放下面，快速操作时更容易误触。
    render(listOf(item()))

    val denyTop = compose.onNodeWithTag(ApprovalTags.deny("ap-1"))
      .fetchSemanticsNode().boundsInRoot.top
    val allowTop = compose.onNodeWithTag(ApprovalTags.allow("ap-1"))
      .fetchSemanticsNode().boundsInRoot.top

    assertTrue(
      "拒绝必须在允许上方（实际 deny=$denyTop allow=$allowTop）",
      denyTop < allowTop
    )
  }

  // ═══════════ 验收 2：危险动作 ═══════════

  @Test
  fun 危险动作的允许按钮不可点且有说明() {
    render(listOf(item(id = "ap-d", tool = "mcp_call", danger = true)))

    compose.onNodeWithTag(ApprovalTags.allow("ap-d")).assertIsNotEnabled()
    compose.onNodeWithTag(ApprovalTags.dangerNote("ap-d")).assertIsDisplayed()
    // 说明必须讲「为什么」，不是只写「不可用」
    compose.onNodeWithText("外部副作用动作：会在你的电脑之外产生效果，手机上无法核对结果")
      .assertIsDisplayed()
  }

  @Test
  fun 危险动作的拒绝按钮仍可点() {
    // 只禁用「允许」，不能连拒绝也禁掉 —— 那样用户只能干等它超时
    var denied: String? = null
    render(listOf(item(id = "ap-d", tool = "computer_click", danger = true)), onDeny = { denied = it })

    compose.onNodeWithTag(ApprovalTags.deny("ap-d")).performClick()
    compose.waitForIdle()
    assertEquals("ap-d", denied)
  }

  @Test
  fun 危险动作点允许不会触发回调() {
    // 禁用态在 Compose 里仍然会接 click（如果没有正确拦），
    // 所以这条要真的点一下，确认回调没被调用。
    var allowed: String? = null
    render(listOf(item(id = "ap-d", tool = "mcp_call", danger = true)), onAllow = { allowed = it })

    compose.onNodeWithTag(ApprovalTags.allow("ap-d")).performClick()
    compose.waitForIdle()
    assertEquals("危险动作的允许不该有任何反应", null, allowed)
  }

  // ═══════════ 验收 1：生物识别不可跳过 ═══════════

  @Test
  fun 生物识别不可用时显示横幅() {
    render(
      listOf(item()),
      biometricUnavailable = BiometricGate.NoBiometricReason.NotEnrolled,
    )
    compose.onNodeWithTag(ApprovalTags.CRYPTO_BANNER).assertIsDisplayed()
    compose.onNodeWithText("还没有录入指纹或面容，只能在桌面批准").assertIsDisplayed()
  }

  @Test
  fun 生物识别可用时不显示横幅() {
    // 反向确认：横幅不是「一直挂着」的装饰
    render(listOf(item()))
    assertEquals(
      0,
      compose.onAllNodesWithTag(ApprovalTags.CRYPTO_BANNER).fetchSemanticsNodes().size
    )
  }

  @Test
  fun 每次允许都提示需要指纹确认() {
    // 蓝图：每次允许都触发 BiometricPrompt。
    // 界面上的对应承诺是「允许」旁边必须写明要过指纹 ——
    // 否则用户不知道按下去还会有一道验证。
    render(listOf(item()))
    compose.onNodeWithText("允许需指纹确认 · 手机不提供「始终允许」").assertIsDisplayed()
  }

  @Test
  fun 界面上没有始终允许的入口() {
    // 蓝图 §05 SEC3：手机端只能回 once 或 deny，不能回 always。
    // 断言方式：页面上所有可点击的节点里，文本不能是「始终允许」。
    // 说明性文字「手机不提供「始终允许」」不算 —— 那是在告诉用户边界在哪。
    render(listOf(item()))

    val clickableTexts = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
      .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { t -> t.text } }

    assertFalse(
      "可点击节点里不能有「始终允许」（实际：$clickableTexts）",
      clickableTexts.any { it.trim() == "始终允许" }
    )
  }

  // ═══════════ 验收 3：拒绝真的下发 ═══════════

  @Test
  fun 点拒绝会带出这条的id() {
    var denied: String? = null
    render(listOf(item(id = "ap-7")), onDeny = { denied = it })

    compose.onNodeWithTag(ApprovalTags.deny("ap-7")).performClick()
    compose.waitForIdle()
    assertEquals("ap-7", denied)
  }

  @Test
  fun 点允许会带出这条的id() {
    var allowed: String? = null
    render(listOf(item(id = "ap-9")), onAllow = { allowed = it })

    compose.onNodeWithTag(ApprovalTags.allow("ap-9")).performClick()
    compose.waitForIdle()
    assertEquals("ap-9", allowed)
  }

  @Test
  fun 全部拒绝一次带走整页() {
    var deniedAll = 0
    render(
      listOf(item(id = "a"), item(id = "b"), item(id = "c")),
      onDenyAll = { deniedAll++ },
    )
    compose.onNodeWithTag(ApprovalTags.DENY_ALL).performClick()
    compose.waitForIdle()
    assertEquals(1, deniedAll)
  }

  @Test
  fun 队列为空时不显示全部拒绝() {
    // 空队列时按「全部拒绝」没有任何意义，显示出来只会让用户怀疑自己漏了什么
    render(emptyList())
    assertEquals(
      0,
      compose.onAllNodesWithTag(ApprovalTags.DENY_ALL).fetchSemanticsNodes().size
    )
  }

  // ═══════════ 四段信息 ═══════════

  @Test
  fun 四个信息段一个都不少() {
    // 蓝图：「必须展示工具名 + 目标 + 影响范围 + 参数摘要，四件缺一不可」。
    // 只写「某成员请求授权」等于让用户盲签。
    render(listOf(item(id = "ap-x", tool = "write_file")))

    compose.onNodeWithText("工具").assertIsDisplayed()
    compose.onNodeWithText("write_file").assertIsDisplayed()
    compose.onNodeWithText("目标").assertIsDisplayed()
    compose.onNodeWithText("npm test").assertIsDisplayed()
    compose.onNodeWithText("参数").assertIsDisplayed()
    compose.onNodeWithText("npm test -- --run").assertIsDisplayed()
    compose.onNodeWithText("类别").assertIsDisplayed()
  }

  // ─────────── M-21：两段同源时不要重复 ───────────

  @Test
  fun 目标与参数相同时不重复同一句话() {
    // 桌面只下发一段 `summary`，投影层把「第一行」当目标、整段当前 400 字当参数。
    // 当 summary 只有一行且不超过 160 字时，两段**一字不差** ——
    // 界面上就是同一句话连排两次。
    val duplicated = ApprovalItem(
      id = "ap-dup",
      toolName = "run_command",
      title = "运行命令",
      // 两段完全相同，正是单行 summary 的情形
      target = "npm test",
      paramSummary = "npm test",
      category = ToolCategory.Exec,
      risk = ToolRisk.Medium,
      danger = false,
      memberLabel = null,
      sessionTitle = "重构登录页",
      createdAtMillis = 0L,
      allowAlwaysOnDesktop = true,
    )
    render(listOf(duplicated))

    // 四段结构仍在（不能因为去重就少一段）
    compose.onNodeWithText("目标").assertIsDisplayed()
    compose.onNodeWithText("参数").assertIsDisplayed()
    // 但重复的那句只出现一次
    assertEquals(
      "同一句话不该显示两遍",
      1,
      compose.onAllNodesWithText("npm test").fetchSemanticsNodes().size,
    )
    // 取代它的是说明文字
    compose.onNodeWithText("与目标同一段", substring = true).assertExists()
  }

  @Test
  fun 两段不同时仍然分别显示() {
    // 反向断言：别把正常分段的审批也合并掉。
    // 多行 summary 时「目标」是第一行、「参数」是整段，两者本来就不同。
    render(listOf(item(id = "ap-x", tool = "write_file")))

    compose.onNodeWithText("npm test").assertIsDisplayed()
    compose.onNodeWithText("npm test -- --run").assertIsDisplayed()
    compose.onAllNodesWithText("与目标同一段", substring = true)
      .fetchSemanticsNodes()
      .let { assertEquals("两段不同时不该出现去重说明", 0, it.size) }
  }

  @Test
  fun 成员来源会显示在卡上() {
    render(listOf(item(member = "T3 · backend")))
    compose.onNodeWithText("T3 · backend").assertIsDisplayed()
  }

  // ═══════════ 超时与空态 ═══════════

  @Test
  fun 倒计时显示剩余秒数() {
    render(listOf(item(created = 0L)), now = 0L)
    compose.onNodeWithTag(ApprovalTags.countdown("ap-1"))
      .assertIsDisplayed()
    compose.onNodeWithText("120s").assertIsDisplayed()
  }

  @Test
  fun 过期后允许按钮不可点且倒计时写已失效() {
    render(listOf(item(created = 0L)), now = ApprovalItem.TTL_MILLIS + 1)
    // 到点后队列本该已经把它清掉；即便没清（时钟边界），按钮也不能可点
    compose.onNodeWithTag(ApprovalTags.allow("ap-1")).assertIsNotEnabled()
    compose.onNodeWithText("已失效").assertIsDisplayed()
  }

  @Test
  fun 空态回答还有没有事要做而不是暂无数据() {
    render(emptyList())
    compose.onNodeWithTag(ApprovalTags.EMPTY).assertIsDisplayed()
    compose.onNodeWithText("没有待你批准的请求").assertIsDisplayed()
    // 空态要说明会自己刷新，否则用户会反复手动刷新
    compose.onNodeWithText("超过 2 分钟未处理的请求会自动失效。", substring = true)
      .assertIsDisplayed()
  }

  @Test
  fun 顶栏显示待处理条数() {
    render(listOf(item(id = "a"), item(id = "b")))
    compose.onNodeWithText("2 条待处理").assertIsDisplayed()
  }

  // ═══════════ 页面与队列联动（走真实状态） ═══════════

  @Test
  fun 处理一条后列表真的少一条() {
    // 前面的测试都是「渲染给定列表」。这条走真实状态：
    // 队列 remove 后重组，界面必须跟着变 —— 否则用户点完拒绝会看到同一张卡还在。
    val sent = mutableListOf<Pair<String, Boolean>>()
    val q = ApprovalQueue({ id, allow, _ -> sent += id to allow; true }, clock = { 0L })
    q.upsert(item(id = "a"))
    q.upsert(item(id = "b"))

    var version by mutableStateOf(0)
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        // 读 version 触发重组
        @Suppress("UNUSED_EXPRESSION") version
        ApprovalScreen(
          items = q.pending,
          nowMillis = 0L,
          onAllow = { id -> q.decide(id, ApprovalQueue.Decision.AllowOnce); version++ },
          onDeny = { id -> q.decide(id, ApprovalQueue.Decision.Deny); version++ },
          onDenyAll = {},
          onBack = {},
        )
      }
    }
    compose.waitForIdle()
    compose.onNodeWithText("2 条待处理").assertIsDisplayed()

    compose.onNodeWithTag(ApprovalTags.deny("a")).performClick()
    compose.waitForIdle()

    assertNotNull("a 应已下发", sent.firstOrNull { it.first == "a" })
    assertEquals(1, q.size)
    compose.onNodeWithText("1 条待处理").assertIsDisplayed()
    // b 那张卡还在，a 那张不在了
    compose.onNodeWithTag(ApprovalTags.card("b")).assertIsDisplayed()
    assertEquals(
      "a 的卡应该消失",
      0,
      compose.onAllNodesWithTag(ApprovalTags.card("a")).fetchSemanticsNodes().size
    )
  }
}

// ───── ApprovalNavigationTest.kt ─────
/**
 * 审批页的**到达性**测试。
 *
 * 为什么单独一个文件：C3 踩过一次 —— `SwarmScreen` 的渲染测试全绿，
 * 但页面根本进不去（swarm 初始为空、入口永不显示）。
 * 渲染测试直接调 Screen，天然绕过了「怎么到达」这一步，
 * 所以「页面存在」和「页面到得了」必须分开证明。
 *
 * 这里走真实入口：MorrowApp → 工位页 → 点「待我决定」块 → 审批页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ApprovalNavigationTest {

  @get:Rule val compose = createComposeRule()

  private fun item(id: String, tool: String = "run_command") = ApprovalItem(
    id = id,
    toolName = tool,
    title = "运行命令",
    target = "npm test",
    paramSummary = "npm test -- --run",
    category = ToolCategory.Exec,
    risk = ToolRisk.Medium,
    danger = false,
    memberLabel = null,
    sessionTitle = "重构登录页",
    createdAtMillis = System.currentTimeMillis(),
    allowAlwaysOnDesktop = true,
  )

  @Test
  fun 从工位页点待我决定能进审批页() {
    val sent = mutableListOf<Pair<String, Boolean>>()
    val q = ApprovalQueue({ id, allow, _ -> sent += id to allow; true })

    q.upsert(item("ap-nav"))
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(approvals = q)
      }
    }
    compose.waitForIdle()

    // 工位页应显示「待我决定」块（因为队列非空）
    compose.onNodeWithTag("workbench-approvals").performClick()
    compose.waitForIdle()

    // 到了审批页
    compose.onNodeWithTag(ApprovalTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(ApprovalTags.card("ap-nav")).assertIsDisplayed()
  }

  @Test
  fun 队列为空时工位页不显示待我决定块() {
    // 反向：没有审批时不该出现「待我决定」——
    // 它出现但写着 0，会让人以为有东西没看到
    val q = ApprovalQueue({ _, _, _ -> true })
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(approvals = q)
      }
    }
    compose.waitForIdle()
    assertEquals(
      0,
      compose.onAllNodesWithTag("workbench-approvals").fetchSemanticsNodes().size
    )
  }

  @Test
  fun 审批页点拒绝真的走到下发() {
    // 走完整链路：工位页 → 审批页 → 点拒绝 → sink 收到 deny。
    // 只测「ApprovalScreen 的 onDeny 被调用」是不够的 ——
    // 那只证明组件，不证明接线。
    val sent = mutableListOf<Pair<String, Boolean>>()
    val q = ApprovalQueue({ id, allow, _ -> sent += id to allow; true })
    q.upsert(item("ap-1"))

    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(approvals = q)
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag("workbench-approvals").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(ApprovalTags.deny("ap-1")).performClick()
    compose.waitForIdle()

    assertEquals("应下发一条", 1, sent.size)
    assertEquals("ap-1", sent[0].first)
    assertEquals("必须是拒绝", false, sent[0].second)
  }

  @Test
  fun 审批页点允许下发一次性批准() {
    val sent = mutableListOf<Pair<String, Boolean>>()
    val q = ApprovalQueue({ id, allow, _ -> sent += id to allow; true })

    q.upsert(item("ap-2"))
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(approvals = q)
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag("workbench-approvals").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(ApprovalTags.allow("ap-2")).performClick()
    compose.waitForIdle()

    assertEquals(1, sent.size)
    assertEquals("必须是允许", true, sent[0].second)
  }

  @Test
  fun 处理完后界面同步减少() {
    val q = ApprovalQueue({ _, _, _ -> true })
    q.upsert(item("ap-a"))
    q.upsert(item("ap-b"))
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(approvals = q)
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag("workbench-approvals").performClick()
    compose.waitForIdle()
    compose.onNodeWithText("2 条待处理").assertIsDisplayed()

    compose.onNodeWithTag(ApprovalTags.deny("ap-a")).performClick()
    compose.waitForIdle()

    // 队列与界面都要变，不能只变一个
    assertEquals(1, q.size)
    compose.onNodeWithText("1 条待处理").assertIsDisplayed()
    assertEquals(
      0,
      compose.onAllNodesWithTag(ApprovalTags.card("ap-a")).fetchSemanticsNodes().size
    )
  }

  @Test
  fun 返回回到工位页() {
    val q = ApprovalQueue({ _, _, _ -> true })
    q.upsert(item("ap-1"))
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(approvals = q)
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag("workbench-approvals").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(ApprovalTags.SCREEN).assertIsDisplayed()

    compose.onNodeWithTag("approval-back").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(ApprovalTags.SCREEN).assertDoesNotExist()
  }

  @Test
  fun 生物识别不可用时审批页显示横幅() {
    val q = ApprovalQueue({ _, _, _ -> true })
    q.upsert(item("ap-1"))
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(
          approvals = q,
          biometricCapability = BiometricGate.Capability.NotEnrolled,
        )
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag("workbench-approvals").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(ApprovalTags.CRYPTO_BANNER).assertIsDisplayed()
  }

  // ───────────────── P1-01：没有闸门回调时不许直接 once ─────────────────

  @Test
  fun 设备无法验身时仍可以拒绝() {
    // 反向断言：拒绝是安全方向，必须永远可用。
    // 若把「无法验身」错误地做成整页禁用，用户就只剩「去桌面处理」一条路。
    val q = ApprovalQueue({ _, _, _ -> true })
    q.upsert(item("ap-1"))
    val decisions = mutableListOf<String>()

    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(
          approvals = q,
          biometricCapability = BiometricGate.Capability.NoHardware,
          onApprovalDecision = { id, decision -> decisions += "$id:$decision"; true },
        )
      }
    }
    compose.waitForIdle()
    compose.onNodeWithTag("workbench-approvals").performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(ApprovalTags.deny("ap-1")).performClick()
    compose.waitForIdle()

    // 没有事件流时走**本地队列**（`streamState == null` 分支），
    // 不会调 `onApprovalDecision` —— 这是设计，不是缺陷。
    // `decide()` 之后条目会从待审队列移除，用这个可观测结果断言。
    assertNull(
      "拒绝必须真的生效（条目从待审队列移除）；实际 decisions=$decisions",
      q.get("ap-1"),
    )
  }

  @Test
  fun 生物识别可用时没有横幅() {
    val q = ApprovalQueue({ _, _, _ -> true })
    q.upsert(item("ap-1"))
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(approvals = q, biometricCapability = BiometricGate.Capability.Ready)
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag("workbench-approvals").performClick()
    compose.waitForIdle()
    assertEquals(
      0,
      compose.onAllNodesWithTag(ApprovalTags.CRYPTO_BANNER).fetchSemanticsNodes().size
    )
  }
}
