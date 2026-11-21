package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.morrow.android.MorrowUiState
import studio.morrow.android.SessionUi
import studio.morrow.android.UiApproval

/**
 * 桌面审批 → 审批页展示模型的投影。
 *
 * 这些断言守的是「字段真的接上了」，而不是「页面能渲染」：
 * 审批卡四段信息（工具、目标、影响、参数）里任何一段没接，
 * 用户就是在盲签，而渲染测试照样会绿。
 */
class ApprovalProjectionTest {

  private fun stateWithSession(): MorrowUiState = MorrowUiState(
    sessions = mapOf("s1" to SessionUi(id = "s1", title = "重构登录页")),
  )

  private fun approval(
    id: String = "req-1",
    tool: String = "write_file",
    title: String = "写入文件",
    summary: String = "修改 src/login.tsx\n影响 1 个文件",
    category: String = "write",
    risk: String = "medium",
    at: Long = 1_000L,
    allowAlways: Boolean? = false,
  ) = UiApproval(
    requestId = id,
    toolName = tool,
    sessionId = "s1",
    requestedAtMillis = at,
    title = title,
    summary = summary,
    category = category,
    risk = risk,
    allowAlways = allowAlways,
  )

  @Test
  fun 完整字段映射到审批卡() {
    val item = ApprovalProjection.fromUi(approval(), stateWithSession(), nowMillis = 5_000L)

    assertEquals("req-1", item.id)
    assertEquals("write_file", item.toolName)
    assertEquals("写入文件", item.title)
    // 四段信息里的「目标」取 summary 第一行
    assertEquals("修改 src/login.tsx", item.target)
    assertTrue("参数摘要含完整影响", item.paramSummary.contains("影响 1 个文件"))
    assertEquals(ToolCategory.Write, item.category)
    assertEquals(ToolRisk.Medium, item.risk)
    assertEquals("重构登录页", item.sessionTitle)
    assertEquals(1_000L, item.createdAtMillis)
    assertFalse("普通写文件不是危险动作", item.danger)
    assertTrue("未过期且非危险可以允许", item.canAllow(5_000L))
  }

  @Test
  fun 未知类别与风险按最危险处理() {
    val item = ApprovalProjection.fromUi(
      approval(category = "future-category", risk = "unknown"),
      stateWithSession(),
      nowMillis = 5_000L,
    )
    // fail-closed：宁可多拦一次，不能把未知类别当只读放行
    assertEquals(ToolCategory.Exec, item.category)
    assertEquals(ToolRisk.High, item.risk)
  }

  @Test
  fun 危险动作的允许按钮禁用() {
    val item = ApprovalProjection.fromUi(
      approval(tool = "browser_click", category = "exec", risk = "high"),
      stateWithSession(),
      nowMillis = 5_000L,
    )
    assertTrue("浏览器自动点击属于危险动作", item.danger)
    assertFalse("危险动作不能允许", item.canAllow(5_000L))
  }

  @Test
  fun 缺时间戳按现在算而不是1970() {
    val item = ApprovalProjection.fromUi(approval(at = 0L), stateWithSession(), nowMillis = 5_000L)
    assertEquals("0 会被误判成早已过期，必须用当前时间兜底", 5_000L, item.createdAtMillis)
    assertTrue(item.canAllow(5_000L))
  }

  @Test
  fun 会话不存在时标题退化为当前会话() {
    val item = ApprovalProjection.fromUi(approval(), MorrowUiState(), nowMillis = 5_000L)
    assertEquals("当前会话", item.sessionTitle)
  }

  @Test
  fun 列表按最早等待排序() {
    val state = stateWithSession().copy(
      pendingApprovals = mapOf(
        "late" to approval(id = "late", at = 3_000L),
        "early" to approval(id = "early", at = 1_000L),
      ),
    )
    val list = ApprovalProjection.fromState(state, nowMillis = 5_000L)
    assertEquals(listOf("early", "late"), list.map { it.id })
  }

  @Test
  fun 改系统时间不能让审批无限延期() {
    // 回归：TTL 曾用墙钟（System.currentTimeMillis）比较，用户把时间往后调
    // 就能让 120 秒窗口任意延长。现在有效期走单调时钟 elapsedMillis，
    // 墙钟只用于显示「多久之前」。
    val item = ApprovalItem(
      id = "a",
      toolName = "write_file",
      title = "写入",
      target = "x",
      paramSummary = "y",
      category = ToolCategory.Write,
      risk = ToolRisk.Medium,
      danger = false,
      memberLabel = null,
      sessionTitle = "会话",
      // 墙钟看起来「刚刚产生」——如果按墙钟算，它还能再活 120 秒
      createdAtMillis = 1_000_000L,
      allowAlwaysOnDesktop = false,
      // 但单调时钟显示它在手机上已经待了 130 秒
      elapsedMillis = 130_000L,
    )

    assertTrue("单调口径已过期", item.expiredMonotonic())
    assertFalse(
      "墙钟看着还新，但单调口径已过期 → 不允许",
      item.canAllow(nowMillis = 1_000_001L, capability = BiometricGate.Capability.Ready),
    )
    assertEquals("剩余秒数按单调口径归零", 0, item.remainingSecondsMonotonic())
  }

  @Test
  fun 设备无法验身时允许按钮禁用() {
    // 回归：canAllow 曾只看危险与过期，不看生物识别能力。
    // 设备没录入指纹时按钮仍可点，点下去只会收到一次「已拒绝」。
    val base = ApprovalItem(
      id = "a",
      toolName = "write_file",
      title = "写入",
      target = "x",
      paramSummary = "y",
      category = ToolCategory.Write,
      risk = ToolRisk.Medium,
      danger = false,
      memberLabel = null,
      sessionTitle = "会话",
      createdAtMillis = 5_000L,
      allowAlwaysOnDesktop = false,
      elapsedMillis = 0L,
    )

    assertTrue("能力就绪时允许可点", base.canAllow(5_000L, BiometricGate.Capability.Ready))
    listOf(
      BiometricGate.Capability.NoHardware,
      BiometricGate.Capability.NotEnrolled,
      BiometricGate.Capability.Unavailable,
      BiometricGate.Capability.PolicyBlocked,
    ).forEach { cap ->
      assertFalse(
        "$cap 下必须禁用允许（否则等于让用户点一个必然失败的按钮）",
        base.canAllow(5_000L, cap),
      )
    }
  }
}