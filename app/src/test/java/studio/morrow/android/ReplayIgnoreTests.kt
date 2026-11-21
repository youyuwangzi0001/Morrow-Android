package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.morrow.android.EventAction
import studio.morrow.android.EventParser
import studio.morrow.android.EventReducer
import studio.morrow.android.MessageRole
import studio.morrow.android.MorrowUiState
import studio.morrow.android.ReplayPack
import studio.morrow.android.IgnoreBridges
import studio.morrow.android.IgnoreBridges.TodayAccumulator

// ───── ReplayPackTest.kt ─────
/**
 * 回放测试 —— 蓝图 D1 第一条验收：
 *
 * > 用录制的事件回放包（≥5000 条）重放，最终状态与桌面同源状态逐字段一致
 *
 * ## 必须说清的前提
 *
 * 这个回放包是**合成**的，不是从桌面录制的（见 `ReplayPack` 的类注释）。
 * 合成包能证明归并逻辑在长序列上正确，不能证明「真实模型输出的分片节奏
 * 一定被正确处理」。报告里如实标注了这一点。
 *
 * 「逐字段一致」的比对方式是：合成器在**构造事件的同时**记录它期望
 * 最终状态是什么（每条消息的 id / role / text / thinking 全部由构造过程
 * 直接算出），回放后逐条对比。这样期望值不是「照抄回放结果」，
 * 而是独立算出来的。
 */
class ReplayPackTest {

  private fun run(pack: ReplayPack.Pack): MorrowUiState {
    var state = MorrowUiState()
    pack.events.forEach { raw ->
      val parsed = EventParser.parse(raw)
      if (parsed != null) state = EventReducer.apply(state, parsed.action, parsed.seq)
    }
    return state
  }

  // ═══════════ 规模 ═══════════

  @Test
  fun 回放包超过五千条() {
    val pack = ReplayPack.build()
    assertTrue(
      "蓝图要求 ≥5000 条，实际 ${pack.events.size} 条",
      pack.events.size >= 5000
    )
  }

  @Test
  fun 回放包覆盖三种以上事件类型() {
    val pack = ReplayPack.build()
    val types = pack.events.mapNotNull { it["type"] as String }.toSet()
    // 至少覆盖：5 种消息类 + 2 种 run 类 + 2 种审批 + 3 种工具 + 2 种其它
    assertTrue("类型多样（实际 ${types.size} 种）", types.size >= 12)
    listOf(
      "run_started", "run_finished", "message_started", "message_delta",
      "message_completed", "permission_requested", "permission_resolved",
      "tool_started", "todos_updated", "usage_updated", "session_updated",
    ).forEach { assertTrue("$it 应在包里", it in types) }
  }

  // ═══════════ 逐字段一致 ═══════════

  @Test
  fun 回放后每个会话逐字段与期望一致() {
    val pack = ReplayPack.build()
    val state = run(pack)

    pack.expected.forEach { exp ->
      val sess = state.sessions[exp.sessionId]
      assertNotNull("会话 ${exp.sessionId} 应存在", sess)
      assertEquals("${exp.sessionId} 标题", exp.title, sess!!.title)
      assertEquals("${exp.sessionId} 消息条数", exp.messageCount, sess.messages.size)

      exp.messages.forEachIndexed { i, em ->
        val actual = sess.messages[i]
        assertEquals("${exp.sessionId} 第 $i 条 id", em.id, actual.id)
        assertEquals("${exp.sessionId} 第 $i 条 role", em.role, actual.role)
        assertEquals("${exp.sessionId} 第 $i 条 text", em.text, actual.text)
        assertEquals("${exp.sessionId} 第 $i 条 thinking", em.thinking, actual.thinking)
        assertFalse(
          "${exp.sessionId} 第 $i 条不该仍在流式（轮次已结束）",
          actual.streaming
        )
      }

      // 结束时没有 live 消息（所有轮次都收了）
      assertNull("${exp.sessionId} 结束时无 live", sess.liveMessageId)
    }
  }

  @Test
  fun 回放后所有轮次都已收尾() {
    val pack = ReplayPack.build()
    val state = run(pack)
    assertTrue("结束时不该有在跑的轮次（实际 ${state.runs.keys}）", state.runs.isEmpty())
  }

  @Test
  fun 回放后审批队列已清空() {
    // 包里每条审批都有对应的 resolved
    val pack = ReplayPack.build()
    val state = run(pack)
    assertEquals("审批都解决了", 0, state.pendingApprovals.size)
  }

  @Test
  fun lastSeq推进到最后一条事件的seq() {
    val pack = ReplayPack.build()
    val state = run(pack)
    val maxSeq = pack.events.mapNotNull { (it["seq"] as? Number)?.toLong() }.max()
    assertEquals("lastSeq 应是最大 seq", maxSeq, state.lastSeq)
  }

  // ═══════════ 三条验收在长序列上仍成立 ═══════════

  @Test
  fun 长序列下切会话不丢分片() {
    val pack = ReplayPack.build()
    var state = MorrowUiState()

    // 边回放边来回切前台（每 50 条切一次）
    pack.events.forEachIndexed { i, raw ->
      val parsed = EventParser.parse(raw)
      if (parsed != null) {
        state = EventReducer.apply(state, parsed.action, parsed.seq)
        if (i % 50 == 0) {
          state = EventReducer.selectSession(state, pack.sessionIds[i % pack.sessionIds.size])
        }
      }
    }

    // 最终状态必须与「不切」时完全相同
    val baseline = run(pack)
    pack.expected.forEach { exp ->
      assertEquals(
        "${exp.sessionId} 频繁切会话后消息数不变",
        baseline.sessions[exp.sessionId]!!.messages.size,
        state.sessions[exp.sessionId]!!.messages.size
      )
      exp.messages.forEachIndexed { i, em ->
        assertEquals(
          "${exp.sessionId} 第 $i 条文本不变",
          em.text,
          state.sessions[exp.sessionId]!!.messages[i].text
        )
      }
    }
  }

  @Test
  fun 停止B不影响正在跑的A() {
    // 从包里挑出 A 的 run_started，手工构造 B 在跑的场面
    var state = MorrowUiState()
    state = EventReducer.apply(state, EventAction.RunStarted("A", "runA", "a", "mA", 100L))
    state = EventReducer.apply(state, EventAction.RunStarted("B", "runB", "b", "mB", 200L))
    state = EventReducer.selectSession(state, "B")

    assertEquals("前台是 B", "runB", state.activeRun?.runId)
    state = EventReducer.apply(state, EventAction.RunFinished("B", "runB"))

    assertNull("B 停了", state.runs["B"])
    assertNotNull("A 还在跑", state.runs["A"])
    assertEquals("runA", state.runs["A"]!!.runId)
  }

  // ═══════════ 合并的正确性（等价性） ═══════════

  @Test
  fun 合并后回放结果与逐条回放完全等价() {
    val pack = ReplayPack.build()
    val direct = run(pack)

    // 先解析成动作，再合并，再回放
    val actions = pack.events.mapNotNull { EventParser.parse(it)?.action }
    val coalesced = EventReducer.replay(MorrowUiState(), EventReducer.coalesce(actions))

    assertEquals(
      "合并必须只是减少次数、不改变结果",
      direct.sessions.keys, coalesced.sessions.keys
    )
    pack.expected.forEach { exp ->
      val a = direct.sessions[exp.sessionId]!!
      val b = coalesced.sessions[exp.sessionId]!!
      assertEquals("${exp.sessionId} 消息数", a.messages.size, b.messages.size)
      a.messages.zip(b.messages).forEachIndexed { i, (x, y) ->
        assertEquals("${exp.sessionId} 第 $i 条 text", x.text, y.text)
        assertEquals("${exp.sessionId} 第 $i 条 thinking", x.thinking, y.thinking)
      }
      assertEquals("${exp.sessionId} 标题", a.title, b.title)
    }
  }

  @Test
  fun 合并确实减少了动作数() {
    val pack = ReplayPack.build()
    val actions = pack.events.mapNotNull { EventParser.parse(it)?.action }
    val merged = EventReducer.coalesce(actions)
    // 3000+ 片 delta 应被压成「每轮 2 段」（thinking 一段 + 正文一段）
    assertTrue(
      "动作数应明显减少（${actions.size} → ${merged.size}）",
      merged.size < actions.size * 0.4
    )
  }

  // ═══════════ 不认识的事件不破坏状态 ═══════════

  @Test
  fun 包里那条未知类型被计入Unparsed而不影响状态() {
    val pack = ReplayPack.build()
    val unknown = pack.events.filter { it["type"] == "totally_unknown_event" }
    assertEquals("合成器插了 1 条未知事件", 1, unknown.size)

    val parsed = EventParser.parse(unknown[0])!!
    assertTrue("应判为 Unparsed", parsed.action is EventAction.Unparsed)

    // 状态不受影响：与跑一遍完整包的结果一样
    val state = run(pack)
    assertEquals(3, state.sessions.size)
  }

  @Test
  fun 十七种Ignore事件在长序列里不改变任何状态() {
    val pack = ReplayPack.build()
    // 只跑 Ignore 类事件，状态应保持空白
    var state = MorrowUiState()
    var ignored = 0
    pack.events.forEach { raw ->
      val p = EventParser.parse(raw) ?: return@forEach
      if (p.action is EventAction.Ignore) {
        ignored++
        state = EventReducer.apply(state, p.action)
      }
    }
    assertTrue("包里应有 Ignore 类事件（实际 $ignored 条）", ignored > 0)
    assertEquals("Ignore 事件不能建出会话", 0, state.sessions.size)
    assertEquals("Ignore 事件不能建出 run", 0, state.runs.size)
  }

  // ═══════════ 乱序容错 ═══════════

  @Test
  fun 包里刻意的乱序delta被正确处理() {
    // 合成器让每轮第一片 delta 早于 message_started
    val pack = ReplayPack.build()
    val firstRunStart = pack.events.indexOfFirst { it["type"] == "run_started" }
    val firstDelta = pack.events.indexOfFirst { it["type"] == "message_delta" }
    val firstStarted = pack.events.indexOfFirst { it["type"] == "message_started" }

    assertTrue("确认包里确实是 delta 先到", firstDelta < firstStarted)
    assertTrue(firstRunStart < firstDelta)

    // 那一片「先」必须出现在最终文本开头
    val state = run(pack)
    val firstAssistant = state.sessions["sess-A"]!!.messages.first { it.role == MessageRole.Assistant }
    assertTrue(
      "乱序到达的首片不能丢（实际开头：${firstAssistant.text.take(4)}）",
      firstAssistant.text.startsWith("先")
    )
  }
}

// ───── IgnoreBridgesTest.kt ─────
/**
 * 三个展开器（`usage_updated` / `artifacts_updated` / `super_updated`+`memberTask`）。
 *
 * 映射与降级规则见 `docs/cards/D1b-事件到模型对照表.md`。
 *
 * 测试重点不是「正常路径能跑」，而是三件极易写错的事：
 * ①整表替换被写成 append（重复累积）；②单条合并被写成整表替换（其他成员消失）；
 * ③「字段缺失」被当成「字段是 null」（已有文字凭空消失）。
 */
class IgnoreBridgesTest {

  // ═══════════════ 1. usage → 今日累计 ═══════════════

  @Test
  fun usage累加而不是覆盖() {
    var acc = TodayAccumulator()
    acc = IgnoreBridges.accumulateUsage(acc, mapOf("usage" to mapOf("inputTokens" to 100, "outputTokens" to 20)))
    acc = IgnoreBridges.accumulateUsage(acc, mapOf("usage" to mapOf("inputTokens" to 300, "outputTokens" to 40)))
    // 累加：100+20 + 300+40 = 460。若写成覆盖会是 340
    assertEquals(460L, acc.tokens)
  }

  @Test
  fun usage缺一个token字段时另一个照常加() {
    var acc = TodayAccumulator()
    acc = IgnoreBridges.accumulateUsage(acc, mapOf("usage" to mapOf("inputTokens" to 100)))
    assertEquals("只有 input 也算 100", 100L, acc.tokens)
    acc = IgnoreBridges.accumulateUsage(acc, mapOf("usage" to mapOf("outputTokens" to 7)))
    assertEquals(107L, acc.tokens)
  }

  @Test
  fun usage整个缺失时不动累计() {
    val acc = TodayAccumulator(tokens = 500L)
    val after = IgnoreBridges.accumulateUsage(acc, mapOf("type" to "usage_updated"))
    assertEquals("无 usage 载荷：保持原值，不能清 0", 500L, after.tokens)
    assertEquals(false, after.sawAnything)
  }

  @Test
  fun 六个字段由不同事件分别累加() {
    // C1 的 TodayStats 不是从一个事件映射出来的，而是多事件聚合
    var acc = TodayAccumulator()
    acc = IgnoreBridges.onRunStarted(acc)                                   // rounds 1, running 1
    acc = IgnoreBridges.accumulateUsage(acc, mapOf("usage" to mapOf("inputTokens" to 10, "outputTokens" to 5)))
    acc = IgnoreBridges.onArtifactsCount(acc, 2)                            // artifacts 2
    acc = IgnoreBridges.onRunFinished(acc, "done")                          // running 0, completed 1

    assertEquals(1, acc.rounds)
    assertEquals(15L, acc.tokens)
    assertEquals(2, acc.artifacts)
    assertEquals(0, acc.running)
    assertEquals(1, acc.completed)
    assertEquals(0, acc.failed)
  }

  @Test
  fun 失败的轮次计入failed而不是completed() {
    var acc = TodayAccumulator()
    acc = IgnoreBridges.onRunStarted(acc)
    acc = IgnoreBridges.onRunFinished(acc, "error")
    assertEquals("completed 不加", 0, acc.completed)
    assertEquals("failed 加一次", 1, acc.failed)
  }

  @Test
  fun 四种结束原因里只有complete算成功() {
    // 桌面 RunFinishReason: complete | cancelled | error | max-steps
    // 「撞步数上限」和「用户取消」都不能算完成，否则今日统计把两件事混成一类
    fun end(reason: String?): TodayAccumulator {
      var a = TodayAccumulator()
      a = IgnoreBridges.onRunStarted(a)
      return IgnoreBridges.onRunFinished(a, reason)
    }
    assertEquals("complete → 完成", 1, end("complete").completed)
    assertEquals("complete → 不算失败", 0, end("complete").failed)
    assertEquals("cancelled → 不算完成", 0, end("cancelled").completed)
    assertEquals("cancelled → 计入失败", 1, end("cancelled").failed)
    assertEquals("error → 不算完成", 0, end("error").completed)
    assertEquals("error → 计入失败", 1, end("error").failed)
    assertEquals("max-steps → 不算完成（任务没做完）", 0, end("max-steps").completed)
    assertEquals("max-steps → 计入失败", 1, end("max-steps").failed)
    assertEquals("reason 缺失（老版本）→ 按成功", 1, end(null).completed)
  }

  @Test
  fun running不会减成负数() {
    // 补漏重放会重复投递 run_finished
    var acc = TodayAccumulator()
    acc = IgnoreBridges.onRunFinished(acc, "done")
    acc = IgnoreBridges.onRunFinished(acc, "done")
    assertEquals("重复的 run_finished 不能把 running 打穿", 0, acc.running)
  }

  @Test
  fun 零事件时sawAnything为false() {
    val acc = TodayAccumulator()
    assertEquals("没收到任何活动事件 → 界面该说不知道", false, acc.sawAnything)
  }

  @Test
  fun 有一条活动后sawAnything为true() {
    val acc = IgnoreBridges.onRunStarted(TodayAccumulator())
    assertTrue("今天确实跑过 → 可以显示真实数字", acc.sawAnything)
  }

  // ═══════════════ 2. artifacts → 产物列表 ═══════════════

  private fun art(title: String?, kind: String, content: String? = null, path: String? = null,
                  at: Long? = 1000L) = buildMap<String, Any?> {
    if (title != null) put("title", title)
    put("kind", kind)
    if (content != null) put("content", content)
    if (path != null) put("path", path)
    if (at != null) put("updatedAt", at)
  }

  @Test
  fun 产物整表替换而不是累加() {
    val first = IgnoreBridges.artifactsOf(mapOf("artifacts" to listOf(art("a.html", "html"), art("b.md", "markdown"))))!!
    assertEquals(2, first.size)
    // 第二次只发 1 条：结果应是 1 条，不是 3 条
    val second = IgnoreBridges.artifactsOf(mapOf("artifacts" to listOf(art("c.pdf", "file"))))!!
    assertEquals("整表替换：桌面说现在只有 1 个产物", 1, second.size)
    assertEquals("c.pdf", second[0].name)
  }

  @Test
  fun 产物载荷缺失时返回null表示不知道() {
    assertNull("没有 artifacts 数组 → null（不能当成「删光了」）", IgnoreBridges.artifactsOf(mapOf()))
    assertNull(IgnoreBridges.artifactsOf(mapOf("artifacts" to "not-an-array")))
  }

  @Test
  fun 空数组表示确实没有产物() {
    val empty = IgnoreBridges.artifactsOf(mapOf("artifacts" to emptyList<Any?>()))
    assertEquals("空数组是「确实没有」，与 null「不知道」不同", 0, empty!!.size)
  }

  @Test
  fun 六种ArtifactKind各自映射到格式标签() {
    val kinds = mapOf(
      "html" to "HTML", "markdown" to "MD", "code" to "CODE",
      "svg" to "SVG", "text" to "TXT", "file" to "FILE",
    )
    val list = IgnoreBridges.artifactsOf(
      mapOf("artifacts" to kinds.keys.map { art("x", it) })
    )!!
    assertEquals(6, list.size)
    list.forEachIndexed { i, a ->
      assertEquals("${kinds.keys.elementAt(i)} 的格式标签", kinds.values.elementAt(i), a.format)
    }
  }

  @Test
  fun 未知kind不丢弃该条而是用大写原值() {
    val list = IgnoreBridges.artifactsOf(mapOf("artifacts" to listOf(art("x.weird", "weird"))))!!
    assertEquals("不认识的类型也保留", 1, list.size)
    assertEquals("WEIRD", list[0].format)
  }

  @Test
  fun 标题缺失时回退到文件名() {
    val list = IgnoreBridges.artifactsOf(
      mapOf("artifacts" to listOf(art(null, "html", path = "/w/out/report.html")))
    )!!
    assertEquals("report.html", list[0].name)
  }

  @Test
  fun 标题与路径都缺失时叫未命名产物() {
    val list = IgnoreBridges.artifactsOf(mapOf("artifacts" to listOf(art(null, "file"))))!!
    assertEquals("未命名产物", list[0].name)
  }

  @Test
  fun path型产物拿不到体积时bytes为null而不是0() {
    val list = IgnoreBridges.artifactsOf(
      mapOf("artifacts" to listOf(art("a.bin", "file", path = "/w/a.bin")))
    )!!
    assertNull("拿不到体积 → null（界面显示 —），不能写 0", list[0].bytes)
  }

  @Test
  fun inline型产物按UTF8字节计算体积() {
    // 中文一个字 3 字节：这里 "报告" 是 6 字节
    val list = IgnoreBridges.artifactsOf(
      mapOf("artifacts" to listOf(art("a.html", "html", content = "报告")))
    )!!
    assertEquals(6L, list[0].bytes)
  }

  @Test
  fun 时间优先updatedAt缺则createdAt() {
    val a = mapOf("artifacts" to listOf(
      mapOf("title" to "a", "kind" to "html", "updatedAt" to 2000L, "createdAt" to 1000L)
    ))
    assertEquals(2000L, IgnoreBridges.artifactsOf(a)!![0].atMillis)

    val b = mapOf("artifacts" to listOf(
      mapOf("title" to "b", "kind" to "html", "createdAt" to 1000L)
    ))
    assertEquals(1000L, IgnoreBridges.artifactsOf(b)!![0].atMillis)
  }

  @Test
  fun 时间都缺时给0不假装刚创建() {
    val list = IgnoreBridges.artifactsOf(
      mapOf("artifacts" to listOf(mapOf("title" to "c", "kind" to "html")))
    )!!
    assertEquals("0 → 排在列表底部", 0L, list[0].atMillis)
  }

  // ═══════════════ 3. super_updated / memberTask → 成员 ═══════════════

  private fun task(index: Int, status: String, title: String = "T$index",
                   note: String? = null, stepCount: Int? = null) =
    buildMap<String, Any?> {
      put("index", index)
      put("title", title)
      put("agent", "frontend")
      put("status", status)
      if (note != null) put("note", note)
      if (stepCount != null) put("stepCount", stepCount)
    }

  private fun superState(tasks: List<Map<String, Any?>>) = mapOf(
    "state" to mapOf("goal" to "g", "status" to "running", "tasks" to tasks)
  )

  @Test
  fun super_updated整表给出全部成员() {
    val m = IgnoreBridges.membersOf(superState(listOf(task(1, "running"), task(2, "queued"))))
    assertEquals(2, m.size)
    assertEquals("running", m[0].state)
    assertEquals("queued", m[1].state)
  }

  @Test
  fun state为null时清空成员表示协作结束() {
    assertEquals(0, IgnoreBridges.membersOf(mapOf("state" to null)).size)
  }

  @Test
  fun tasks缺失时清空成员() {
    assertEquals(0, IgnoreBridges.membersOf(mapOf("state" to mapOf("goal" to "g"))).size)
  }

  @Test
  fun memberTask只改一个成员其他成员不能消失() {
    val all = IgnoreBridges.membersOf(superState(listOf(task(1, "running"), task(2, "queued"), task(3, "queued"))))
    assertEquals(3, all.size)

    val after = IgnoreBridges.mergeMember(all, mapOf(
      "memberTask" to mapOf("index" to 2, "status" to "running", "note" to "正在写组件")
    ))
    assertEquals("关键：其他成员不能消失", 3, after.size)
    assertEquals("T1 状态不变", "running", after.first { it.index == 1 }.state)
    assertEquals("T2 状态已更新", "running", after.first { it.index == 2 }.state)
    assertEquals("T2 的 note 写入", "正在写组件", after.first { it.index == 2 }.note)
    assertEquals("T3 不受影响", "queued", after.first { it.index == 3 }.state)
  }

  @Test
  fun memberTask没传的字段要保留而不是清空() {
    val all = IgnoreBridges.membersOf(superState(listOf(task(1, "running", note = "读文件", stepCount = 7))))

    // 只更新 status，不提 note / stepCount
    val after = IgnoreBridges.mergeMember(all, mapOf(
      "memberTask" to mapOf("index" to 1, "status" to "completed")
    ))
    val m = after[0]
    assertEquals("状态更新", "completed", m.state)
    assertEquals("note 必须保留（没传 ≠ 传了 null）", "读文件", m.note)
    assertEquals("stepCount 必须保留", 7, m.stepCount)
  }

  @Test
  fun memberTask传了空字符串时照收() {
    // 与「没传」不同：桌面明确想清空
    val all = IgnoreBridges.membersOf(superState(listOf(task(1, "running", note = "旧说明"))))
    val after = IgnoreBridges.mergeMember(all, mapOf(
      "memberTask" to mapOf("index" to 1, "note" to "")
    ))
    assertEquals("显式传空 = 清空", "", after[0].note)
  }

  @Test
  fun memberTask的index不存在时追加新成员() {
    val all = IgnoreBridges.membersOf(superState(listOf(task(1, "running"))))
    assertEquals(1, all.size)
    val after = IgnoreBridges.mergeMember(all, mapOf(
      "memberTask" to mapOf("index" to 5, "title" to "新来的", "status" to "queued")
    ))
    assertEquals("新成员出现要追加", 2, after.size)
    assertEquals("新来的", after.first { it.index == 5 }.title)
  }

  @Test
  fun 没有memberTask的tool_progress不动成员() {
    val all = IgnoreBridges.membersOf(superState(listOf(task(1, "running"))))
    val after = IgnoreBridges.mergeMember(all, mapOf("percent" to 50, "toolUseId" to "tu"))
    assertEquals("普通工具进度与成员无关", 1, after.size)
    assertEquals("running", after[0].state)
  }

  @Test
  fun 六种成员状态能逐个认出来() {
    val states = listOf("queued", "running", "completed", "failed", "cancelled", "exhausted")
    val m = IgnoreBridges.membersOf(superState(states.mapIndexed { i, s -> task(i + 1, s) }))
    assertEquals(6, m.size)
    states.forEachIndexed { i, s -> assertEquals(s, m[i].state) }
  }

  @Test
  fun 缺status时按queued处理不丢成员() {
    val raw = mapOf("state" to mapOf("tasks" to listOf(
      mapOf("index" to 1, "title" to "x", "agent" to "backend")
    )))
    val m = IgnoreBridges.membersOf(raw)
    assertEquals("没有 status 也保留成员", 1, m.size)
    assertEquals("queued", m[0].state)
  }

  @Test
  fun 缺index的任务被跳过因为它无法安全合并() {
    val raw = mapOf("state" to mapOf("tasks" to listOf(
      mapOf("title" to "无 index", "agent" to "x"),
      mapOf("index" to 2, "title" to "有 index", "agent" to "y"),
    )))
    val m = IgnoreBridges.membersOf(raw)
    assertEquals("无 index 无法参与合并，跳过", 1, m.size)
    assertEquals(2, m[0].index)
  }
}
