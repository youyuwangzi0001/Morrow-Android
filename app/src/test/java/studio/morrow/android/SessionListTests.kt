package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话列表的投影测试。
 *
 * 这一组守的是「手机能不能找到要控制的那个任务」——远程控制的前提。
 * 全部是纯函数断言，不起 Robolectric：排序、分组、摘要、相对时间
 * 各自独立，出错时能直接指到是哪条规则错。
 */
class SessionListTests {

  private fun session(
    id: String,
    title: String = "会话 $id",
    messages: List<UiMessage> = emptyList(),
    liveMessageId: String? = null,
  ) = SessionUi(id = id, title = title, messages = messages, liveMessageId = liveMessageId)

  private fun msg(id: String, text: String, at: Long) =
    UiMessage(id = id, role = MessageRole.Assistant, text = text, createdAtMillis = at)

  // ─────────────── 状态判定 ───────────────

  @Test
  fun 有审批时状态优先显示待你决定() {
    // 同一轮既在跑又有审批：用户该看到「有事等你」，而不是「它在跑」——
    // 后者会让人以为不用管
    assertEquals(SessionBadge.Awaiting, SessionListProjection.badgeOf(hasPendingApproval = true, isRunning = true))
    assertEquals(SessionBadge.Awaiting, SessionListProjection.badgeOf(hasPendingApproval = true, isRunning = false))
  }

  @Test
  fun 无审批时才看是否在跑() {
    assertEquals(SessionBadge.Running, SessionListProjection.badgeOf(hasPendingApproval = false, isRunning = true))
    assertEquals(SessionBadge.Idle, SessionListProjection.badgeOf(hasPendingApproval = false, isRunning = false))
  }

  // ─────────────── 摘要 ───────────────

  @Test
  fun 摘要取最后一条非空消息() {
    val preview = SessionListProjection.previewOf(
      listOf(msg("m1", "第一条", 1), msg("m2", "最后一条", 2), msg("m3", "   ", 3))
    )
    assertEquals("最后一条", preview)
  }

  @Test
  fun 摘要压掉代码围栏与整张表格() {
    val text = """
      ```diff
      --- a/x
      +++ b/x
      ```
      | 月份 | 金额 |
      | --- | ---: |
      | 7 月 | 128.4 |
      结论是 9 月下滑
    """.trimIndent()
    val preview = SessionListProjection.previewOf(listOf(msg("m1", text, 1)))
    assertTrue("围栏不该出现在摘要里：$preview", !preview.contains("```"))
    // 整张表都丢掉，不是只丢分隔行：留下「| 7 月 | 128.4 |」在列表里毫无意义
    assertFalse("表格不该出现在摘要里：$preview", preview.contains("128.4"))
    assertTrue("正文必须留下：$preview", preview.contains("结论是 9 月下滑"))
  }

  @Test
  fun 纯表格消息退回用表格内容做摘要而不是空白() {
    val text = "| 月份 | 金额 |\n| --- | ---: |\n| 7 月 | 128.4 |"
    val preview = SessionListProjection.previewOf(listOf(msg("m1", text, 1)))
    assertTrue("不能是空白行：[$preview]", preview.isNotEmpty())
    assertTrue("应保留可读内容：$preview", preview.contains("128.4"))
    assertFalse("竖线要清掉：$preview", preview.contains("|"))
  }

  @Test
  fun 摘要超长时截断并加省略号() {
    val preview = SessionListProjection.previewOf(listOf(msg("m1", "一".repeat(300), 1)))
    assertEquals(SessionListProjection.PREVIEW_MAX + 1, preview.length)
    assertTrue(preview.endsWith("…"))
  }

  @Test
  fun 没有消息时摘要为空而不是造一句() {
    assertEquals("", SessionListProjection.previewOf(emptyList()))
    assertEquals("", SessionListProjection.previewOf(listOf(msg("m1", "  ", 1))))
  }

  // ─────────────── 行投影 ───────────────

  @Test
  fun 空标题用id尾巴兜底() {
    val row = SessionListProjection.rowOf(
      session = session("ses-abcd1234", title = ""),
      pendingApprovals = 0, isRunning = false, isActive = false,
    )
    assertTrue("空标题不能显示成空白行", row.title.contains("1234"))
    assertTrue(row.title.contains("未命名"))
  }

  @Test
  fun 行里带上审批数与消息条数() {
    val row = SessionListProjection.rowOf(
      session = session("s1", messages = listOf(msg("m1", "a", 1), msg("m2", "b", 2))),
      pendingApprovals = 2, isRunning = false, isActive = true,
    )
    assertEquals(2, row.pendingApprovals)
    assertEquals(2, row.messageCount)
    assertTrue(row.isActive)
    assertEquals(2L, row.lastActiveMillis)
    assertEquals(SessionBadge.Awaiting, row.badge)
  }

  @Test
  fun 没有消息的时间是零而不是1970() {
    // 0 表示「不知道」；渲染层据此显示「—」，不按 1970 算
    val row = SessionListProjection.rowOf(session("s1"), 0, false, false)
    assertEquals(0L, row.lastActiveMillis)
    assertNull(SessionListProjection.relativeTime(row.lastActiveMillis, 1_700_000_000_000L))
  }

  // ─────────────── 分组与排序 ───────────────

  @Test
  fun 分组顺序是待审运行其他() {
    val state = MorrowUiState(
      sessions = mapOf(
        "s-idle" to session("s-idle", messages = listOf(msg("m", "x", 1))),
        "s-run" to session("s-run", messages = listOf(msg("m", "y", 2))),
        "s-need" to session("s-need", messages = listOf(msg("m", "z", 3))),
      ),
      runs = mapOf("s-run" to RunState(runId = "r1", startedAtMillis = 1)),
      pendingApprovals = mapOf(
        "req1" to UiApproval(requestId = "req1", toolName = "edit_file", sessionId = "s-need"),
      ),
      activeSessionId = "s-idle",
    )
    val sections = SessionListProjection.sectionsOf(state)
    assertEquals(
      listOf(SessionGroup.NeedYou, SessionGroup.Running, SessionGroup.Others),
      sections.map { it.group },
    )
    assertEquals("s-need", sections[0].rows.single().id)
    assertEquals("s-run", sections[1].rows.single().id)
    assertEquals("s-idle", sections[2].rows.single().id)
  }

  @Test
  fun 空分组不产出空标题() {
    val state = MorrowUiState(
      sessions = mapOf("s1" to session("s1", messages = listOf(msg("m", "x", 5)))),
    )
    val sections = SessionListProjection.sectionsOf(state)
    assertEquals(1, sections.size)
    assertEquals(SessionGroup.Others, sections.single().group)
  }

  @Test
  fun 同一组内按最后活动倒序且时间未知的排最后() {
    val state = MorrowUiState(
      sessions = mapOf(
        "old" to session("old", messages = listOf(msg("m1", "x", 100))),
        "new" to session("new", messages = listOf(msg("m2", "y", 900))),
        "unknown" to session("unknown"), // 没有任何消息 → 时间未知
      ),
    )
    val rows = SessionListProjection.sectionsOf(state).single().rows
    assertEquals(listOf("new", "old", "unknown"), rows.map { it.id })
  }

  @Test
  fun 没有会话时返回空列表() {
    assertTrue(SessionListProjection.sectionsOf(MorrowUiState()).isEmpty())
  }

  // ─────────────── 顶栏读数 ───────────────

  @Test
  fun 未连接时不说零个会话() {
    // 与工位页同一条原则：未连接时读不到 ≠ 没有
    val text = SessionListProjection.readout(online = false, sections = emptyList())
    assertTrue(text.contains("读不到"))
    assertFalse(text.contains("0"))
  }

  @Test
  fun 已连接但没有会话时如实说没有() {
    val text = SessionListProjection.readout(online = true, sections = emptyList())
    assertEquals("还没有会话", text)
  }

  @Test
  fun 读数里分别报待你决定与在跑的数量() {
    val sections = listOf(
      SessionSection(SessionGroup.NeedYou, listOf(
        SessionListProjection.rowOf(session("a"), 1, false, false),
        SessionListProjection.rowOf(session("b"), 1, false, false),
      )),
      SessionSection(SessionGroup.Running, listOf(
        SessionListProjection.rowOf(session("c"), 0, true, false),
      )),
      SessionSection(SessionGroup.Others, listOf(
        SessionListProjection.rowOf(session("d"), 0, false, false),
      )),
    )
    val text = SessionListProjection.readout(online = true, sections = sections)
    assertEquals("4 个 · 2 个待你决定 · 1 个在跑", text)
  }

  // ─────────────── 相对时间 ───────────────

  @Test
  fun 相对时间各档位() {
    val now = 1_700_000_000_000L
    assertEquals("刚刚", SessionListProjection.relativeTime(now - 10_000, now))
    assertEquals("刚刚", SessionListProjection.relativeTime(now, now))
    assertEquals("5 分钟前", SessionListProjection.relativeTime(now - 5 * 60_000, now))
    assertEquals("3 小时前", SessionListProjection.relativeTime(now - 3 * 3_600_000, now))
    assertEquals("2 天前", SessionListProjection.relativeTime(now - 2 * 86_400_000L, now))
    assertEquals("2 个月前", SessionListProjection.relativeTime(now - 61L * 86_400_000L, now))
  }

  @Test
  fun 时间在未来时不显示负数() {
    val now = 1_700_000_000_000L
    assertEquals("刚刚", SessionListProjection.relativeTime(now + 60_000, now))
  }

  // ─────────────── 与真实状态串联 ───────────────

  @Test
  fun 从事件流状态一路投影到列表行() {
    // 用真实归并器造状态，而不是手工拼 MorrowUiState ——
    // 这样「快照 → 会话列表」这条链的字段名也对得上
    var s = MorrowUiState()
    s = EventReducer.apply(
      s,
      EventAction.Snapshot(
        SnapshotData(
          sessions = listOf(
            SnapshotSession(
              id = "ses-1",
              title = "把销售明细做成复盘",
              liveMessageId = null,
              messages = listOf(
                UiMessage(id = "m1", role = MessageRole.User, text = "做个复盘", createdAtMillis = 100),
                UiMessage(
                  id = "m2", role = MessageRole.Assistant,
                  text = "已经生成 report.html，包含趋势与异常订单",
                  createdAtMillis = 200,
                ),
              ),
            ),
            SnapshotSession("ses-2", "整理会议纪要", null, emptyList()),
          ),
          activeSessionId = "ses-1",
          runs = emptyList(),
          approvals = emptyList(),
          artifacts = null,
          artifactsSessionId = null,
          members = null,
          superGoal = null,
        )
      ),
    )
    val sections = SessionListProjection.sectionsOf(s)
    val rows = sections.flatMap { it.rows }
    assertEquals(2, rows.size)
    val active = rows.single { it.isActive }
    assertEquals("ses-1", active.id)
    assertEquals("把销售明细做成复盘", active.title)
    assertEquals("已经生成 report.html，包含趋势与异常订单", active.preview)
    assertEquals(SessionBadge.Idle, active.badge)
    assertNotNull(
      "有消息的会话必须能算出相对时间",
      SessionListProjection.relativeTime(active.lastActiveMillis, 200L + 60_000),
    )
  }
}


