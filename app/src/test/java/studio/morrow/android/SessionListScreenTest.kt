package studio.morrow.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话列表页的渲染测试。
 *
 * 解析与排序在 [SessionListTests] 里已经逐条守住；这里只验「那一屏真的画出来了、
 * 点得动」—— 前端的「测试全绿但页面进不去」是这台工程反复踩过的坑
 * （C3 的成员协作页曾经就是这样）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h731dp-420dpi")
class SessionListScreenTest {

  @get:Rule
  val compose = createComposeRule()

  private val now = 1_700_000_000_000L

  private fun row(
    id: String,
    title: String,
    badge: SessionBadge = SessionBadge.Idle,
    preview: String = "",
    pending: Int = 0,
    active: Boolean = false,
    messages: Int = 1,
    at: Long = now - 5 * 60_000L,
  ) = SessionRow(
    id = id,
    title = title,
    badge = badge,
    preview = preview,
    lastActiveMillis = at,
    pendingApprovals = pending,
    isActive = active,
    messageCount = messages,
  )

  private var opened: String? = null
  private var backPressed = false

  private fun render(sections: List<SessionSection>, online: Boolean = true) {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        SessionListScreen(
          sections = sections,
          online = online,
          nowMillis = now,
          onBack = { backPressed = true },
          onSelect = { opened = it },
        )
      }
    }
  }

  @Test
  fun 三个分组标题与计数都画出来() {
    render(
      listOf(
        SessionSection(SessionGroup.NeedYou, listOf(row("a", "需要批准的那个", SessionBadge.Awaiting, pending = 2))),
        SessionSection(SessionGroup.Running, listOf(row("b", "正在跑的那个", SessionBadge.Running))),
        SessionSection(SessionGroup.Others, listOf(row("c", "闲着的那个"))),
      )
    )
    compose.waitForIdle()

    // 注意：「待我决定 / 运行中」也会出现在顶栏读数里（"N 个待你决定"），
    // 所以这里断言「至少出现一次」，不假定唯一。
    assertTrue(
      "应出现「待我决定」分组",
      compose.onAllNodesWithText("待我决定").fetchSemanticsNodes().isNotEmpty(),
    )
    assertTrue(
      "应出现「运行中」分组",
      compose.onAllNodesWithText("运行中").fetchSemanticsNodes().isNotEmpty(),
    )
    compose.onNodeWithText("其他会话").assertIsDisplayed()
    compose.onNodeWithText("需要批准的那个").assertIsDisplayed()
    compose.onNodeWithText("正在跑的那个").assertIsDisplayed()
    compose.onNodeWithText("闲着的那个").assertIsDisplayed()
  }

  @Test
  fun 待审的卡片写出具体审批数() {
    render(
      listOf(SessionSection(SessionGroup.NeedYou, listOf(row("a", "这个要批准", SessionBadge.Awaiting, pending = 3))))
    )
    compose.waitForIdle()
    // 「3 个待批准」比笼统的「待你决定」有用得多
    compose.onNodeWithText("3 个待批准").assertIsDisplayed()
  }

  @Test
  fun 当前会话带当前标记() {
    render(listOf(SessionSection(SessionGroup.Others, listOf(row("a", "选中的那个", active = true)))))
    compose.waitForIdle()
    compose.onNodeWithText("当前").assertIsDisplayed()
  }

  @Test
  fun 点一张卡会把它的id交出去() {
    render(
      listOf(
        SessionSection(SessionGroup.Others, listOf(
          row("ses-1", "第一张"),
          row("ses-2", "第二张"),
        ))
      )
    )
    compose.waitForIdle()
    compose.onNodeWithTag(SessionListTags.row("ses-2")).performClick()
    assertEquals("ses-2", opened)
  }

  @Test
  fun 返回键会回调() {
    render(listOf(SessionSection(SessionGroup.Others, listOf(row("a", "随便一个")))))
    compose.waitForIdle()
    compose.onNodeWithTag(SessionListTags.BACK).performClick()
    assertTrue(backPressed)
  }

  @Test
  fun 空列表分两种说法() {
    // 未连接：读不到；已连接：确实没有。两者不能共用一句
    render(emptyList(), online = false)
    compose.waitForIdle()
    compose.onNodeWithText("读不到会话").assertIsDisplayed()

    // 用第二个测试类实例验证已连接那一支（setContent 每个测试只能调一次）
    compose.onAllNodesWithTag(SessionListTags.EMPTY).fetchSemanticsNodes().let {
      assertEquals(1, it.size)
    }
  }

  @Test
  fun 已连接但没会话时说的是没有而不是读不到() {
    render(emptyList(), online = true)
    compose.waitForIdle()
    compose.onNodeWithText("桌面上还没有会话").assertIsDisplayed()
  }

  @Test
  fun 预览文字上屏() {
    render(
      listOf(SessionSection(SessionGroup.Others, listOf(row("a", "有预览的", preview = "已经生成 report.html"))))
    )
    compose.waitForIdle()
    compose.onNodeWithText("已经生成 report.html").assertIsDisplayed()
  }

  @Test
  fun 时间未知时不显示相对时间() {
    render(
      listOf(SessionSection(SessionGroup.Others, listOf(row("a", "没有时间的", at = 0L))))
    )
    compose.waitForIdle()
    // 没有「分钟前 / 小时前 / 刚刚」任何一种说法
    val nodes = compose.onAllNodesWithText("刚刚").fetchSemanticsNodes()
    assertEquals("时间未知时不能编一个「刚刚」", 0, nodes.size)
  }
}


