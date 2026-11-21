package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息编解码测试。
 *
 * 守的是「结构化内容真的到得了手机」这条链路的入口：同一份 JSON 会从
 * 三条路径到达（快照 / `messages_replaced` / `message_completed`），
 * 这里逐条验，避免出现「首连能看到 diff、重连就没了」。
 */
class MessageCodecTest {

  private fun wire(vararg pairs: Pair<String, Any?>): Map<*, *> =
    mapOf<Any?, Any?>("id" to "m1", "role" to "assistant", "createdAt" to 1_700_000_000_000L, *pairs)

  // ─────────────── 基本字段 ───────────────

  @Test
  fun 缺id的消息被丢弃() {
    // 放进去会造出重复 key，LazyColumn 整列重建且内容错乱
    assertNull(UiMessageCodec.fromWire(mapOf("role" to "assistant", "text" to "x")))
  }

  @Test
  fun 角色映射三种并默认助手() {
    assertEquals(MessageRole.User, UiMessageCodec.fromWire(wire("role" to "user"))?.role)
    assertEquals(MessageRole.System, UiMessageCodec.fromWire(wire("role" to "system"))?.role)
    assertEquals(MessageRole.Assistant, UiMessageCodec.fromWire(wire("role" to "assistant"))?.role)
    assertEquals("未知角色按助手处理", MessageRole.Assistant, UiMessageCodec.fromWire(wire("role" to "weird"))?.role)
  }

  @Test
  fun 没有blocks时返回空列表而不是崩溃() {
    // 旧版桌面完全不带 blocks
    val msg = UiMessageCodec.fromWire(wire("text" to "纯文本"))
    assertNotNull(msg)
    assertTrue(msg!!.blocks.isEmpty())
    assertEquals("纯文本", msg.text)
  }

  // ─────────────── 块解析 ───────────────

  @Test
  fun 文字与思考块各自成块() {
    val msg = UiMessageCodec.fromWire(
      wire(
        "blocks" to listOf(
          mapOf("type" to "text", "text" to "正文"),
          mapOf("type" to "thinking", "text" to "想法"),
        )
      )
    )!!
    assertEquals(2, msg.blocks.size)
    assertEquals(UiBlock.Text("正文"), msg.blocks[0])
    assertEquals(UiBlock.Thinking("想法"), msg.blocks[1])
  }

  @Test
  fun 空文字块被跳过不占位置() {
    val msg = UiMessageCodec.fromWire(
      wire("blocks" to listOf(mapOf("type" to "text", "text" to "")))
    )!!
    assertTrue(msg.blocks.isEmpty())
  }

  @Test
  fun 工具块带行级diff与结果计数() {
    val msg = UiMessageCodec.fromWire(
      wire(
        "blocks" to listOf(
          mapOf(
            "type" to "tool",
            "id" to "t1",
            "name" to "edit_file",
            "title" to "修改 src/a.ts",
            "category" to "write",
            "status" to "ok",
            "result" to mapOf("ok" to true, "diffAdded" to 3, "diffRemoved" to 1),
            "diff" to "--- a/src/a.ts\n+++ b/src/a.ts\n@@ -1 +1 @@\n-x\n+y\n",
          )
        )
      )
    )!!
    val tool = msg.blocks.single() as UiBlock.Tool
    assertEquals("t1", tool.call.id)
    assertEquals("修改 src/a.ts", tool.call.title)
    assertEquals(ToolCallStatus.Ok, tool.call.status)
    assertEquals(3, tool.call.diffAdded)
    assertEquals(1, tool.call.diffRemoved)
    assertTrue("diff 原文必须带下来", tool.diff!!.contains("@@ -1 +1 @@"))
  }

  @Test
  fun 工具状态四种映射() {
    fun statusOf(value: String): ToolCallStatus? {
      val msg = UiMessageCodec.fromWire(
        wire("blocks" to listOf(mapOf("type" to "tool", "id" to "t1", "status" to value)))
      )!!
      return (msg.blocks.single() as UiBlock.Tool).call.status
    }
    assertEquals(ToolCallStatus.Running, statusOf("running"))
    assertEquals(ToolCallStatus.Failed, statusOf("failed"))
    assertEquals(ToolCallStatus.Denied, statusOf("denied"))
    assertEquals(ToolCallStatus.Interrupted, statusOf("interrupted"))
    assertEquals("cancelled 归到中断", ToolCallStatus.Interrupted, statusOf("cancelled"))
    assertEquals("未知状态按成功处理", ToolCallStatus.Ok, statusOf("whatever"))
  }

  @Test
  fun 缺id的工具块被丢弃() {
    val msg = UiMessageCodec.fromWire(
      wire("blocks" to listOf(mapOf("type" to "tool", "title" to "无 id")))
    )!!
    assertTrue("没有 id 的工具块没有稳定 key，必须丢弃", msg.blocks.isEmpty())
  }

  @Test
  fun 错误块带code() {
    val msg = UiMessageCodec.fromWire(
      wire("blocks" to listOf(mapOf("type" to "error", "message" to "失败了", "code" to "tool_error")))
    )!!
    assertEquals(UiBlock.Error("失败了", "tool_error"), msg.blocks.single())
  }

  @Test
  fun 未知块类型被跳过不整条丢弃() {
    val msg = UiMessageCodec.fromWire(
      wire(
        "blocks" to listOf(
          mapOf("type" to "video", "url" to "x"),
          mapOf("type" to "text", "text" to "后面的正文"),
        )
      )
    )!!
    assertEquals("未知块跳过，后面的照常保留", 1, msg.blocks.size)
    assertEquals(UiBlock.Text("后面的正文"), msg.blocks.single())
  }

  @Test
  fun 非列表的blocks值不崩溃() {
    assertTrue(UiMessageCodec.parseBlocks("不是列表").isEmpty())
    assertTrue(UiMessageCodec.parseBlocks(null).isEmpty())
  }

  // ─────────────── 工具合并 ───────────────

  @Test
  fun 合并时blocks提供diff增量提供实时状态() {
    val fromBlocks = listOf(
      UiToolCall(id = "t1", name = "edit_file", title = "修改 a", category = "write",
        status = ToolCallStatus.Running, diff = "patch-1"),
    )
    val fromEvents = listOf(
      UiToolCall(id = "t1", name = "edit_file", title = "修改 a", category = "write",
        status = ToolCallStatus.Ok, durationMillis = 900),
    )
    val merged = UiMessageCodec.mergeTools(fromBlocks, fromEvents).single()
    assertEquals("diff 只能来自 blocks", "patch-1", merged.diff)
    assertEquals("状态以增量为准", ToolCallStatus.Ok, merged.status)
    assertEquals(900L, merged.durationMillis)
  }

  @Test
  fun 增量里多出来的步骤追加在后面() {
    val merged = UiMessageCodec.mergeTools(
      fromBlocks = listOf(UiToolCall(id = "t1", name = "a", title = "一", category = "write", status = ToolCallStatus.Ok)),
      fromEvents = listOf(
        UiToolCall(id = "t1", name = "a", title = "一", category = "write", status = ToolCallStatus.Ok),
        UiToolCall(id = "t2", name = "b", title = "二", category = "write", status = ToolCallStatus.Ok),
      ),
    )
    assertEquals(listOf("t1", "t2"), merged.map { it.id })
  }

  @Test
  fun 一边为空时直接用另一边() {
    val onlyBlocks = listOf(UiToolCall(id = "t1", name = "", title = "", category = "", status = ToolCallStatus.Ok))
    assertEquals(onlyBlocks, UiMessageCodec.mergeTools(onlyBlocks, emptyList()))
    assertEquals(onlyBlocks, UiMessageCodec.mergeTools(emptyList(), onlyBlocks))
    assertTrue(UiMessageCodec.mergeTools(emptyList(), emptyList()).isEmpty())
  }

  @Test
  fun 增量的空标题不覆盖blocks里的标题() {
    val merged = UiMessageCodec.mergeTools(
      fromBlocks = listOf(UiToolCall(id = "t1", name = "edit_file", title = "修改 src/a.ts", category = "write", status = ToolCallStatus.Running)),
      fromEvents = listOf(UiToolCall(id = "t1", name = "", title = "", category = "", status = ToolCallStatus.Ok)),
    ).single()
    assertEquals("修改 src/a.ts", merged.title)
  }

  // ─────────────── 三条路径一致 ───────────────

  @Test
  fun 快照与messages_replaced与message_completed解析出同样的块() {
    val payload = wire(
      "blocks" to listOf(
        mapOf("type" to "text", "text" to "正文"),
        mapOf("type" to "tool", "id" to "t1", "status" to "ok", "diff" to "+a\n-b"),
      )
    )
    val base = mapOf(
      "seq" to 1L, "runId" to "r1", "sessionId" to "s1", "at" to 1L,
    )

    val completed = EventParser.parse(
      base + mapOf("type" to "message_completed", "messageId" to "m1", "message" to payload)
    )?.action as? EventAction.MessageCompleted
    assertNotNull(completed)
    assertEquals(2, completed!!.blocks.size)

    val replaced = EventParser.parse(
      base + mapOf("type" to "messages_replaced", "messages" to listOf(payload))
    )?.action as? EventAction.MessagesReplaced
    assertNotNull(replaced)
    assertEquals("同一个 codec，块必须一致", completed.blocks, replaced!!.messages.single().blocks)
  }
}
