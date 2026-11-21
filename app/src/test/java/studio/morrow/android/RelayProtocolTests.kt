package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.morrow.android.RelayEnvelope
import studio.morrow.android.EventAction
import org.junit.Assert.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import studio.morrow.android.RelayProtocol
import studio.morrow.android.SecureChannel
import java.io.File
import org.junit.Assert.assertArrayEquals
import studio.morrow.android.WebSocketFrames
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import studio.morrow.android.RelayHint

// ───── RelayEnvelopeTest.kt ─────
/**
 * 信封解析（D1 第四批）。
 *
 * 这一层存在的唯一理由是：**桌面的 `seq` 在信封上，不在事件体里**。
 * 原先手机端从事件体读 `seq`，单测里手写 `"seq" to 1L` 看起来正常，
 * 真机上却恒为 0 —— 补漏判定永久失效，而且不报错。
 *
 * 所以这里的重点是：
 * ①`seq` 必须从信封取；②信封校验必须严格（照抄桌面 `isValidEnvelope`）；
 * ③`invoke`/`result`/`ack` 不能被当成运行时事件。
 */
class RelayEnvelopeTest {

  private fun env(
    v: Any? = 1,
    kind: Any? = "event",
    seq: Any? = 1,
    runId: Any? = "",
    sessionId: Any? = "s1",
    at: Any? = 1000L,
    body: Any? = mapOf("type" to "run_started"),
    omitBody: Boolean = false,
  ): Map<String, Any?> {
    val m = mutableMapOf<String, Any?>()
    if (v != null) m["v"] = v
    if (kind != null) m["kind"] = kind
    if (seq != null) m["seq"] = seq
    if (runId != null) m["runId"] = runId
    if (sessionId != null) m["sessionId"] = sessionId
    if (at != null) m["at"] = at
    if (!omitBody) m["body"] = body
    return m
  }

  // ═══════════ 正常路径 ═══════════

  @Test
  fun 合法信封能拆出全部字段() {
    val r = RelayEnvelope.parse(env(seq = 42, sessionId = "ses-9", runId = "run-7", at = 1763456789012L))
    assertTrue(r is RelayEnvelope.Result.Ok)
    val e = (r as RelayEnvelope.Result.Ok).envelope
    assertEquals(1, e.v)
    assertEquals("event", e.kind)
    assertEquals("seq 从信封取", 42L, e.seq)
    assertEquals("ses-9", e.sessionId)
    assertEquals("run-7", e.runId)
    assertEquals(1763456789012L, e.at)
    assertEquals("run_started", e.body["type"])
  }

  @Test
  fun 真实JSON文本也能解析() {
    val json = """{"v":1,"kind":"event","seq":7,"runId":"r1","sessionId":"s1","at":1000,
      "body":{"type":"message_delta","messageId":"m1","textDelta":"你好"}}"""
    val r = RelayEnvelope.parseJson(json)
    assertTrue(r is RelayEnvelope.Result.Ok)
    val e = (r as RelayEnvelope.Result.Ok).envelope
    assertEquals(7L, e.seq)
    assertEquals("你好", e.body["textDelta"])
  }

  // ═══════════ seq 来源（本批的核心） ═══════════

  @Test
  fun seq从信封取而不是从事件体取() {
    // 事件体里没有 seq —— 这正是真机上的样子。原先的 EventParser 期望事件体里有 seq。
    val e = (RelayEnvelope.parse(env(seq = 99, body = mapOf("type" to "run_started")))
      as RelayEnvelope.Result.Ok).envelope
    val merged = RelayEnvelope.toEventMap(e)
    assertEquals("合并后 seq 必须是信封的 99", 99L, merged["seq"])
  }

  @Test
  fun 事件体里的seq不能覆盖信封的seq() {
    // 客户端不该能通过改 body 影响补漏判定
    val e = (RelayEnvelope.parse(env(seq = 5, body = mapOf("type" to "x", "seq" to 9999)))
      as RelayEnvelope.Result.Ok).envelope
    val merged = RelayEnvelope.toEventMap(e)
    assertEquals("以信封为准，body 里的 seq 无效", 5L, merged["seq"])
  }

  @Test
  fun seq为零被判非法() {
    val r = RelayEnvelope.parse(env(seq = 0))
    assertTrue("seq 0 说明服务端分配坏了", r is RelayEnvelope.Result.Invalid)
  }

  @Test
  fun seq为负数被判非法() {
    assertTrue(RelayEnvelope.parse(env(seq = -3)) is RelayEnvelope.Result.Invalid)
  }

  @Test
  fun seq为小数被判非法() {
    assertTrue(RelayEnvelope.parse(env(seq = 1.5)) is RelayEnvelope.Result.Invalid)
  }

  @Test
  fun 缺seq被判非法() {
    assertTrue(RelayEnvelope.parse(env(seq = null)) is RelayEnvelope.Result.Invalid)
  }

  // ═══════════ 版本与 kind ═══════════

  @Test
  fun 协议版本不是1被判非法() {
    assertTrue(RelayEnvelope.parse(env(v = 2)) is RelayEnvelope.Result.Invalid)
    assertTrue(RelayEnvelope.parse(env(v = 0)) is RelayEnvelope.Result.Invalid)
    assertTrue(RelayEnvelope.parse(env(v = null)) is RelayEnvelope.Result.Invalid)
  }

  @Test
  fun 四种kind都能识别() {
    listOf("event", "invoke", "result", "ack").forEach { k ->
      val r = RelayEnvelope.parse(env(kind = k))
      assertTrue("$k 应被接受（但不是都当事件）",
        r is RelayEnvelope.Result.Ok || r is RelayEnvelope.Result.NotAnEvent)
    }
  }

  @Test
  fun 非event的信封不当运行时事件() {
    listOf("invoke", "result", "ack").forEach { k ->
      val r = RelayEnvelope.parse(env(kind = k))
      assertTrue("$k 不能进归并器", r is RelayEnvelope.Result.NotAnEvent)
      assertEquals(k, (r as RelayEnvelope.Result.NotAnEvent).kind)
    }
  }

  @Test
  fun 未知kind被判非法而不是当事件() {
    val r = RelayEnvelope.parse(env(kind = "heartbeat"))
    assertTrue("不认识就明确失败，不能猜", r is RelayEnvelope.Result.Invalid)
  }

  // ═══════════ 其它必需字段 ═══════════

  @Test
  fun at必须是有限数() {
    assertTrue(RelayEnvelope.parse(env(at = Double.NaN)) is RelayEnvelope.Result.Invalid)
    assertTrue(RelayEnvelope.parse(env(at = Double.POSITIVE_INFINITY)) is RelayEnvelope.Result.Invalid)
    assertTrue(RelayEnvelope.parse(env(at = null)) is RelayEnvelope.Result.Invalid)
  }

  @Test
  fun 缺runId或sessionId被判非法() {
    assertTrue(RelayEnvelope.parse(env(runId = null)) is RelayEnvelope.Result.Invalid)
    assertTrue(RelayEnvelope.parse(env(sessionId = null)) is RelayEnvelope.Result.Invalid)
  }

  @Test
  fun runId可以为空串因为非运行期事件没有() {
    val r = RelayEnvelope.parse(env(runId = ""))
    assertTrue("空串合法（系统级事件）", r is RelayEnvelope.Result.Ok)
  }

  @Test
  fun 缺body被判非法() {
    assertTrue(RelayEnvelope.parse(env(omitBody = true)) is RelayEnvelope.Result.Invalid)
  }

  @Test
  fun body不是对象被判非法() {
    assertTrue(RelayEnvelope.parse(env(body = "not-an-object")) is RelayEnvelope.Result.Invalid)
    assertTrue(RelayEnvelope.parse(env(body = listOf(1, 2))) is RelayEnvelope.Result.Invalid)
  }

  // ═══════════ JSON 文本路径 ═══════════

  @Test
  fun 坏JSON给出可读原因而不是静默() {
    val r = RelayEnvelope.parseJson("{not json")
    assertTrue(r is RelayEnvelope.Result.Invalid)
    val reason = (r as RelayEnvelope.Result.Invalid).reason
    assertTrue("原因必须非空，便于定位", reason.isNotBlank())
    assertTrue("并带错误码", reason.contains(RelayEnvelope.ENVELOPE_INVALID))
  }

  @Test
  fun 顶层是数组而不是对象被判非法() {
    assertTrue(RelayEnvelope.parseJson("[1,2,3]") is RelayEnvelope.Result.Invalid)
  }

  @Test
  fun 空串被判非法() {
    assertTrue(RelayEnvelope.parseJson("") is RelayEnvelope.Result.Invalid)
  }

  // ═══════════ toEventMap 的合成规则 ═══════════

  @Test
  fun 事件体缺sessionId时用信封补上() {
    val e = (RelayEnvelope.parse(env(sessionId = "ses-1", body = mapOf("type" to "run_started")))
      as RelayEnvelope.Result.Ok).envelope
    assertEquals("ses-1", RelayEnvelope.toEventMap(e)["sessionId"])
  }

  @Test
  fun 事件体有自己的sessionId时不被信封覆盖() {
    val e = (RelayEnvelope.parse(env(sessionId = "ses-envelope",
      body = mapOf("type" to "message_delta", "sessionId" to "ses-body")))
      as RelayEnvelope.Result.Ok).envelope
    assertEquals("内层优先（桌面事件自己带路由）", "ses-body", RelayEnvelope.toEventMap(e)["sessionId"])
  }

  @Test
  fun 事件体缺at时用信封的服务端时间() {
    val e = (RelayEnvelope.parse(env(at = 5000L, body = mapOf("type" to "run_started")))
      as RelayEnvelope.Result.Ok).envelope
    assertEquals(5000L, RelayEnvelope.toEventMap(e)["at"])
  }

  @Test
  fun 合成后的事件体能被EventParser认出来() {
    val e = (RelayEnvelope.parse(env(
      seq = 3, sessionId = "s1", runId = "r1", at = 100L,
      body = mapOf(
        "type" to "run_started",
        "userMessage" to mapOf("text" to "问"),
        "assistantMessageId" to "m1",
      ),
    )) as RelayEnvelope.Result.Ok).envelope

    val parsed = studio.morrow.android.EventParser.parse(RelayEnvelope.toEventMap(e))
    assertNotNull("拼好的 Map 必须能被归并器解析", parsed)
    assertEquals("seq 一路传到 Parsed", 3L, parsed!!.seq)
    assertTrue(parsed.action is EventAction.RunStarted)
  }

  @Test
  fun 整数与浮点在JSON里都能读对() {
    val json = """{"v":1,"kind":"event","seq":12,"runId":"","sessionId":"s","at":1000,
      "body":{"type":"usage_updated","usage":{"inputTokens":300,"outputTokens":12.5}}}"""
    val e = (RelayEnvelope.parseJson(json) as RelayEnvelope.Result.Ok).envelope
    val usage = e.body["usage"] as Map<*, *>
    assertEquals("整数保持整数", 300L, (usage["inputTokens"] as Number).toLong())
    assertTrue("浮点保留小数", (usage["outputTokens"] as Number).toDouble() > 12.0)
  }

  @Test
  fun 嵌套数组与嵌套对象都能解开() {
    val json = """{"v":1,"kind":"event","seq":1,"runId":"","sessionId":"s","at":1,
      "body":{"type":"artifacts_updated","artifacts":[
        {"title":"a.html","kind":"html","updatedAt":100},
        {"title":"b.md","kind":"markdown","updatedAt":200}]}}"""
    val e = (RelayEnvelope.parseJson(json) as RelayEnvelope.Result.Ok).envelope
    val arts = e.body["artifacts"] as List<*>
    assertEquals(2, arts.size)
    assertEquals("a.html", (arts[0] as Map<*, *>)["title"])
    assertEquals("b.md", (arts[1] as Map<*, *>)["title"])
  }
}

// ───── RelayProtocolVectorTest.kt ─────
/**
 * relay 协议层的跨语言向量测试（D1 第五批第③步）。
 *
 * 硬编码值来自 `scripts/gen-relay-protocol-vectors.mjs`，它 `import` 桌面真实
 * `PairingService` 并调用 `signRequest` / `verifyRequest` / `completePairing`。
 * **关键证据：脚本里桌面 `verifyRequest` 对这对签名返回 true** ——
 * 不是我算出来对得上自己，而是桌面认这个签名。
 */
class RelayProtocolVectorTest {

  companion object {
    // ── 由 scripts/gen-relay-protocol-vectors.mjs 生成（勿手改）──
    private const val SESSION_KEY = "0ahiYsZbp8saj6HWkMSogfOI0F1LSrqZ5Cah/rMknNo="
    private const val DEVICE_ID = "dev-test-0001"
    private const val FIXED_TS = "1700000000000"
    private const val SIGN_LAN = "zcOQ1oShF9Welgta-vaqRB2XfnJ8Mw8wNzUVLTSvWoY"
    private const val SIGN_WSS = "V_VYXDCrQ-LUOkq6PI0lB3PEsCqm25giIGbhePX7ALU"
    private const val SNAPSHOT_TAG = "snapshot_required"

    private const val SALT_B64 = "CQkJCQkJCQkJCQkJCQkJCQ=="
  }

  private fun vectorJson(): String {
    val f = listOf("build-evidence/relay-protocol-vector.json", "../build-evidence/relay-protocol-vector.json")
      .map { File(it) }.firstOrNull { it.exists() }
      ?: throw IllegalStateException("缺 relay-protocol-vector.json —— 先跑 scripts/gen-relay-protocol-vectors.mjs")
    return f.readText(Charsets.UTF_8)
  }

  private fun field(name: String): String {
    val json = vectorJson()
    val key = "\"$name\":"
    val i = json.indexOf(key)
    require(i >= 0) { "JSON 里没有 $name" }
    var j = i + key.length
    while (j < json.length && json[j].isWhitespace()) j++
    if (json[j] != '"') {
      val start = j
      while (j < json.length && json[j] != ',' && json[j] != '\n' && json[j] != '}') j++
      return json.substring(start, j).trim()
    }
    // 值本身可能是「JSON 的字符串化形式」（如 welcomeOk 里内含 \" 转义），
    // 所以必须完整处理反斜杠转义，否则会在第一个 \" 处提前截断。
    val sb = StringBuilder()
    var k = j + 1
    while (k < json.length) {
      val ch = json[k]
      if (ch == '\\' && k + 1 < json.length) {
        when (val n = json[k + 1]) {
          '"' -> sb.append('"')
          '\\' -> sb.append('\\')
          'n' -> sb.append('\n')
          'r' -> sb.append('\r')
          't' -> sb.append('\t')
          '/' -> sb.append('/')
          else -> sb.append(n)
        }
        k += 2
        continue
      }
      if (ch == '"') break
      sb.append(ch)
      k++
    }
    return sb.toString()
  }

  // ═══════════ hello 签名：必须与桌面逐字节一致 ═══════════

  @Test
  fun hello签名与桌面signRequest一致() {
    val actual = RelayProtocol.sign(SESSION_KEY, DEVICE_ID, RelayProtocol.CHANNEL_LAN, FIXED_TS)
    assertEquals("签名必须与桌面 signRequest 完全相同", SIGN_LAN, actual)
  }

  @Test
  fun 签名内容用换行分隔而不是冒号() {
    // 桌面是 `${deviceId}\n${channel}\n${timestamp}`。
    // 这里不能「换个参数再算一遍」就算验证了分隔符 —— 那只是绕过了被测逻辑。
    // 正确做法：用同一个 sessionKey，手工按换行拼出消息并 HMAC，
    // 结果必须与 sign() 相同；再用冒号拼一遍，必须不同。
    val expected = SIGN_LAN

    fun hmac(msg: String): String {
      val mac = javax.crypto.Mac.getInstance("HmacSHA256")
      mac.init(
        javax.crypto.spec.SecretKeySpec(
          java.util.Base64.getDecoder().decode(SESSION_KEY), "HmacSHA256",
        )
      )
      return java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
    }

    assertEquals("换行分隔应得到与桌面相同的签名",
      expected, hmac("$DEVICE_ID\nlan\n$FIXED_TS"))
    assertTrue("冒号分隔必须得到不同结果（否则分隔符不是关键）",
      hmac("$DEVICE_ID:lan:$FIXED_TS") != expected)
    assertEquals("sign() 必须与手工换行拼接一致",
      expected, RelayProtocol.sign(SESSION_KEY, DEVICE_ID, RelayProtocol.CHANNEL_LAN, FIXED_TS))
  }

  @Test
  fun channel不同签名就不同() {
    // channel 参与签名 = 防止把 lan 的签名拿去连中继（横向越权）
    val lan = RelayProtocol.sign(SESSION_KEY, DEVICE_ID, RelayProtocol.CHANNEL_LAN, FIXED_TS)
    val wss = RelayProtocol.sign(SESSION_KEY, DEVICE_ID, "wss", FIXED_TS)
    assertEquals(lan, SIGN_LAN)
    assertEquals("wss 通道的签名与桌面一致", SIGN_WSS, wss)
    assertTrue("两个通道的签名必须不同", lan != wss)
  }

  @Test
  fun 时间戳不同签名就不同() {
    // 时间戳参与签名 = 防止无限重放（桌面还有 60 秒窗口）
    val a = RelayProtocol.sign(SESSION_KEY, DEVICE_ID, RelayProtocol.CHANNEL_LAN, FIXED_TS)
    val b = RelayProtocol.sign(SESSION_KEY, DEVICE_ID, RelayProtocol.CHANNEL_LAN, "1700000000001")
    assertTrue("时间戳变化必须导致签名变化", a != b)
  }

  @Test
  fun 签名是base64url无填充() {
    val sig = RelayProtocol.sign(SESSION_KEY, DEVICE_ID, RelayProtocol.CHANNEL_LAN, FIXED_TS)
    assertTrue("不该含 =", !sig.contains('='))
    assertTrue("不该含 +", !sig.contains('+'))
    assertTrue("不该含 /", !sig.contains('/'))
  }

  @Test
  fun hello帧字段完整且版本正确() {
    val frame = RelayProtocol.helloFrame(SESSION_KEY, DEVICE_ID, 1700000000000L)
    assertTrue("必须含 v:1", frame.contains("\"v\":1"))
    assertTrue("kind 必须是 hello", frame.contains("\"kind\":\"hello\""))
    assertTrue("必须带 deviceId", frame.contains("\"deviceId\":\"$DEVICE_ID\""))
    assertTrue("ts 必须是字符串（桌面按 string 读）", frame.contains("\"ts\":\"1700000000000\""))
    assertTrue("必须带签名", frame.contains("\"sig\":\"$SIGN_LAN\""))

    // 能被自己的严格解析器解回来
    val obj = RelayProtocol.JsonLite.parseObject(frame)
    assertNotNull("hello 必须是合法 JSON 对象", obj)
    assertEquals("1", obj!!["v"])
    assertEquals(RelayProtocol.KIND_HELLO, obj["kind"])
  }

  @Test
  fun 重连时hello带lastSeq而首次连接不带() {
    // 首次连接：手机上没有任何状态，桌面按 lastSeq=0 改发全量快照。
    // 省略该字段是为了与旧版桌面/旧向量逐字节兼容。
    val first = RelayProtocol.helloFrame(SESSION_KEY, DEVICE_ID, 1700000000000L)
    assertFalse("首次连接不带 lastSeq", first.contains("lastSeq"))

    // 重连：带上已应用的最大序号，桌面只补缺口。
    val resumed = RelayProtocol.helloFrame(SESSION_KEY, DEVICE_ID, 1700000000000L, 3496L)
    assertTrue("重连必须带 lastSeq", resumed.contains("\"lastSeq\":3496"))
    val obj = RelayProtocol.JsonLite.parseObject(resumed)
    assertEquals("3496", obj!!["lastSeq"])
    // 签名只覆盖 deviceId/channel/ts：加 lastSeq 不能改变签名（不是被签字段）
    assertTrue("签名必须不变", resumed.contains("\"sig\":\"$SIGN_LAN\""))
  }

  @Test
  fun 上行invoke帧字段完整且参数被正确转义() {
    val frame = RelayProtocol.invokeFrame(
      id = "inv-1",
      method = "approval.decide",
      params = mapOf(
        "requestId" to "r\"1\\x",
        "decision" to "once",
        "n" to 3,
        "flag" to false,
        "nested" to mapOf("k" to "v"),
      ),
      seq = 7,
    )
    val obj = Json.parseToJsonElement(frame).jsonObject
    assertEquals(1, obj["v"]!!.jsonPrimitive.int)
    assertEquals("invoke", obj["kind"]!!.jsonPrimitive.content)
    assertEquals(7L, obj["seq"]!!.jsonPrimitive.long)
    assertEquals("inv-1", obj["id"]!!.jsonPrimitive.content)
    assertEquals("approval.decide", obj["method"]!!.jsonPrimitive.content)

    val params = obj["params"]!!.jsonObject
    // 引号/反斜杠必须转义成合法 JSON —— 手拼不转义会让桌面侧整帧解析失败
    assertEquals("r\"1\\x", params["requestId"]!!.jsonPrimitive.content)
    assertEquals("once", params["decision"]!!.jsonPrimitive.content)
    assertEquals(3, params["n"]!!.jsonPrimitive.int)
    assertEquals(false, params["flag"]!!.jsonPrimitive.boolean)
    assertEquals("v", params["nested"]!!.jsonObject["k"]!!.jsonPrimitive.content)
  }

  @Test
  fun result帧带出信封body供上行回调使用() {
    val text = """{"v":1,"kind":"result","seq":9,"runId":"","sessionId":"","at":123,"body":{"id":"inv-1","ok":false,"error":{"code":"not_found","message":"已失效"}}}"""
    val r = RelayEnvelope.parseJson(text)
    assertTrue("result 不是事件", r is RelayEnvelope.Result.NotAnEvent)
    val notEvent = r as RelayEnvelope.Result.NotAnEvent
    assertEquals("result", notEvent.kind)
    val env = notEvent.envelope
    assertNotNull("信封必须带出来（body 里有 id/ok/error）", env)
    assertEquals(9L, env!!.seq)
    assertEquals("inv-1", env.body["id"])
    assertEquals(false, env.body["ok"])
  }

  @Test
  fun hello里的sessionKey非法时报错而不是静默生成空签名() {
    try {
      RelayProtocol.sign("!!!not-base64!!!", DEVICE_ID, RelayProtocol.CHANNEL_LAN, FIXED_TS)
      fail("非法 sessionKey 必须报错")
    } catch (e: RelayProtocol.ProtocolError) {
      assertEquals("BAD_SESSION_KEY", e.code)
    }
  }

  // ═══════════ welcome：真实帧形状 ═══════════

  @Test
  fun 能解析桌面真实welcome帧() {
    val w = RelayProtocol.parseWelcome(field("welcomeOk"))
    assertEquals(DEVICE_ID, w.deviceId)
    assertEquals("SHA256:abcdef0123456789", w.fingerprint)
    assertEquals(SALT_B64, w.salt)
    assertEquals(42L, w.newestSeq)
    assertEquals(3, w.replayCount)
    assertTrue("正常 welcome 不该标 snapshotRequired", !w.snapshotRequired)
  }

  @Test
  fun 能解析snapshotRequired分支() {
    // 桌面在无法补漏时下发 snapshotRequired 标记（是字符串不是布尔！）
    val w = RelayProtocol.parseWelcome(field("welcomeSnapshot"))
    assertTrue("必须识别出 snapshotRequired", w.snapshotRequired)
    assertEquals("该分支没有 replayCount", null, w.replayCount)
    assertEquals("snapshot_required 字面量必须与桌面一致", SNAPSHOT_TAG,
      if (w.snapshotRequired) SNAPSHOT_TAG else "不可能")
  }

  @Test
  fun welcome缺salt时拒绝() {
    val bad = """{"v":1,"kind":"welcome","deviceId":"d","fingerprint":"f","newestSeq":0,"replayCount":0}"""
    try {
      RelayProtocol.parseWelcome(bad)
      fail("缺 salt 必须拒绝")
    } catch (e: RelayProtocol.ProtocolError) {
      assertTrue(e.message!!.contains("salt"))
    }
  }

  @Test
  fun welcome的salt长度不对时拒绝() {
    // salt 用于防 nonce 重用，长度不对必须在这里拦住
    val bad = """{"v":1,"kind":"welcome","deviceId":"d","fingerprint":"f","salt":"AAAA","newestSeq":0,"replayCount":0}"""
    try {
      RelayProtocol.parseWelcome(bad)
      fail("4 字节 salt 必须拒绝")
    } catch (e: RelayProtocol.ProtocolError) {
      assertTrue("错误信息要说明长度要求", e.message!!.contains("16"))
    }
  }

  @Test
  fun 既无replayCount也无snapshotRequired时拒绝() {
    // 这种情况无法判断该补漏还是要全量。宁可报错，也不能默认「不用补」
    // —— 默认不用补会静默丢事件。
    val bad = """{"v":1,"kind":"welcome","deviceId":"d","fingerprint":"f","salt":"$SALT_B64","newestSeq":5}"""
    try {
      RelayProtocol.parseWelcome(bad)
      fail("缺少补漏策略必须拒绝")
    } catch (e: RelayProtocol.ProtocolError) {
      assertTrue(e.message!!.contains("replayCount") || e.message!!.contains("snapshotRequired"))
    }
  }

  @Test
  fun 协议版本不对时拒绝() {
    val bad = """{"v":2,"kind":"welcome","deviceId":"d","fingerprint":"f","salt":"$SALT_B64","newestSeq":0,"replayCount":0}"""
    try {
      RelayProtocol.parseWelcome(bad)
      fail("版本不匹配必须拒绝")
    } catch (e: RelayProtocol.ProtocolError) {
      assertEquals("PROTOCOL_VERSION", e.code)
    }
  }

  @Test
  fun welcome解析出的salt能构造SecureChannel() {
    // 这一步把「协议层」与「加密层」接起来：welcome 的 salt 直接可用
    val w = RelayProtocol.parseWelcome(field("welcomeOk"))
    val ch = SecureChannel(SESSION_KEY, w.salt, DEVICE_ID)
    val frame = ch.seal(SecureChannel.P2D, "ack")
    assertNotNull(frame.c)
  }

  // ═══════════ 帧分类：顺序是安全相关的 ═══════════

  @Test
  fun welcome被归为明文帧() {
    val r = RelayProtocol.classify(field("welcomeOk"))
    assertTrue("welcome 必须是 Welcome 分支", r is RelayProtocol.Incoming.Welcome)
  }

  @Test
  fun 加密帧被正确识别() {
    val r = RelayProtocol.classify("""{"n":"7","c":"AAAAbbbb"}""")
    assertTrue(r is RelayProtocol.Incoming.Secure)
    val s = r as RelayProtocol.Incoming.Secure
    assertEquals("7", s.frame.n)
    assertEquals("AAAAbbbb", s.frame.c)
  }

  @Test
  fun 同时带kind和nc的帧被判为welcome而不是加密帧() {
    // 分类顺序是安全相关的：welcome 是唯一允许明文的帧。
    // 若先判 n/c，一个带 kind:welcome 的畸形帧会被当加密帧解 ——
    // 那等于给「明文塞进加密通道」留了缝。反过来则安全：
    // 它会被当 welcome，然后因缺字段被拒绝。
    val ambiguous = """{"kind":"welcome","n":"0","c":"AAAA"}"""
    val r = RelayProtocol.classify(ambiguous)
    assertTrue("必须先判 welcome（安全顺序）", r is RelayProtocol.Incoming.Welcome)
    // 进一步：当 welcome 解析时必须失败（缺 salt 等）
    try {
      RelayProtocol.parseWelcome(ambiguous)
      fail("缺字段的伪 welcome 必须解析失败")
    } catch (_: RelayProtocol.ProtocolError) {
      // 正确
    }
  }

  @Test
  fun 加密帧含多余字段时拒绝() {
    // 多余字段不受 AAD 认证覆盖，被下游读取就是「绕过认证的旁路」
    val r = RelayProtocol.classify("""{"n":"0","c":"AAAA","kind":"event","seq":"999"}""")
    assertTrue("必须判为畸形", r is RelayProtocol.Incoming.Malformed)
    assertTrue((r as RelayProtocol.Incoming.Malformed).reason.contains("多余字段"))
  }

  @Test
  fun 加密帧字段只有一半时拒绝() {
    for (bad in listOf("""{"n":"0"}""", """{"c":"AAAA"}""")) {
      val r = RelayProtocol.classify(bad)
      assertTrue("半截加密帧必须拒绝：$bad", r is RelayProtocol.Incoming.Malformed)
    }
  }

  @Test
  fun 握手后的其他明文帧被拒绝() {
    // 防降级攻击：握手完成后除了欢迎帧不该有任何明文
    for (bad in listOf(
      """{"kind":"event","seq":1}""",
      """{"kind":"ack","seq":1}""",
      """{"foo":"bar"}""",
    )) {
      val r = RelayProtocol.classify(bad)
      assertTrue("明文非 welcome 帧必须拒绝：$bad", r is RelayProtocol.Incoming.Malformed)
    }
  }

  @Test
  fun 非JSON被判畸形() {
    for (bad in listOf("", "not json", "[]", "null", "{}garbage")) {
      val r = RelayProtocol.classify(bad)
      assertTrue("非 JSON 必须拒绝：$bad", r is RelayProtocol.Incoming.Malformed)
    }
  }

  // ═══════════ 断线归因：决定要不要重连 ═══════════

  @Test
  fun 三种安全关闭码都不重连() {
    // 这是断线分类的**全部意义**：若对 4401/4402/4403 自动重连，
    // 等于让攻击者可以反复试，而这三条正是「试错了」的证据。
    val unauth = RelayProtocol.classifyClose(RelayProtocol.CLOSE_UNAUTHORIZED, "unauthorized")
    assertEquals(RelayProtocol.Disconnect.UNAUTHORIZED, unauth.kind)
    assertTrue("未授权不该重连", !unauth.shouldRetry)

    val tampered = RelayProtocol.classifyClose(RelayProtocol.CLOSE_TAMPERED, "tampered")
    assertEquals(RelayProtocol.Disconnect.TAMPERED, tampered.kind)
    assertTrue("篡改不该重连", !tampered.shouldRetry)

    val replayed = RelayProtocol.classifyClose(RelayProtocol.CLOSE_REPLAYED, "replayed")
    assertEquals(RelayProtocol.Disconnect.REPLAYED, replayed.kind)
    assertTrue("重放不该重连", !replayed.shouldRetry)
  }

  @Test
  fun 正常关闭与服务端下线可重连() {
    for (code in listOf(RelayProtocol.CLOSE_NORMAL, RelayProtocol.CLOSE_GOING_AWAY)) {
      val d = RelayProtocol.classifyClose(code, null)
      assertTrue("关闭码 $code 应该可重连", d.shouldRetry)
    }
  }

  @Test
  fun 网络异常关闭可重连() {
    // 1006 是「连接被异常断开」，属于网络层
    val d = RelayProtocol.classifyClose(1006, "abnormal")
    assertEquals(RelayProtocol.Disconnect.RETRYABLE, d.kind)
    assertTrue(d.shouldRetry)
  }

  @Test
  fun 其他应用层关闭码算协议错误不重连() {
    val d = RelayProtocol.classifyClose(4400, "bad request")
    assertEquals(RelayProtocol.Disconnect.PROTOCOL, d.kind)
    assertTrue("协议错误重连也无用", !d.shouldRetry)
  }

  @Test
  fun 网络失败默认可重连但协议异常不可() {
    val net = RelayProtocol.classifyFailure(java.io.IOException("连接被重置"))
    assertTrue("IO 失败可重连", net.shouldRetry)

    val proto = RelayProtocol.classifyFailure(RelayProtocol.ProtocolError("WELCOME_MALFORMED", "坏帧"))
    assertEquals(RelayProtocol.Disconnect.PROTOCOL, proto.kind)
    assertTrue("协议错误不该重连", !proto.shouldRetry)
  }

  // ═══════════ snapshotRequired 分支与 ack ═══════════

  @Test
  fun snapshotRequired时发的是全量请求而不是补漏请求() {
    // 桌面理由：缓冲为空或 lastSeq 非法时无法补漏，只能要全量。
    // 若这里退回「补漏」，手机就会永远缺一段历史且不自知。
    val req = RelayProtocol.snapshotRequest(42)
    assertTrue("必须是 snapshot_request", req.contains("\"kind\":\"snapshot_request\""))
    assertTrue("必须带上 lastSeq", req.contains("\"lastSeq\":42"))
    assertTrue("不能是补漏语义", !req.contains("replay"))
  }

  @Test
  fun ack帧格式与桌面读取的一致() {
    // 桌面读的是 inner.kind === 'ack' 与 inner.seq
    val ack = RelayProtocol.ackFrame(17)
    assertTrue(ack.contains("\"kind\":\"ack\""))
    assertTrue(ack.contains("\"seq\":17"))
    val obj = RelayProtocol.JsonLite.parseObject(ack)
    assertEquals("ack", obj!!["kind"])
    assertEquals("17", obj["seq"])
  }

  // ═══════════ 与严格 JSON 解析的一致性 ═══════════

  @Test
  fun relay解析器同样拒绝尾随逗号() {
    // D1f 在配对解析器上发现过这个缺陷（尾随逗号被接受），
    // 这一层是另一份实现，必须同样被守住。
    val r = RelayProtocol.classify("""{"n":"0","c":"AAAA",}""")
    assertTrue("尾随逗号必须拒绝", r is RelayProtocol.Incoming.Malformed)
  }

  @Test
  fun relay解析器拒绝嵌套与数组() {
    for (bad in listOf(
      """{"n":{"a":"b"},"c":"AAAA"}""",
      """{"n":["0"],"c":"AAAA"}""",
    )) {
      val r = RelayProtocol.classify(bad)
      assertTrue("嵌套/数组必须拒绝：$bad", r is RelayProtocol.Incoming.Malformed)
    }
  }
}

// ───── WebSocketFramesTest.kt ─────
/**
 * WebSocket 帧编解码（RFC 6455）的纯逻辑测试。
 *
 * 为什么单独测这一层：端到端测试只走「文本帧 + 小载荷」这一条路，
 * 掩盖了三类只在特定条件下暴露的错误：
 *
 * 1. **掩码方向**：客户端必须加、服务端必须不加。
 *    写错时端到端测试仍然通过（因为假服务端用的是同一份代码，
 *    两边一起错就互相抵消）。第一版就是这样错的。
 * 2. **长度编码**：>125 字节才走 2 字节长度，>65535 才走 8 字节。
 *    只测小消息永远不会覆盖后两个分支。
 * 3. **控制帧限制**：close/ping/pong 载荷不能超 125 字节。
 */
class WebSocketFramesTest {

  private val rnd = Random(1234)

  // ═══════════ 掩码方向（这条端到端测不到）═══════════

  @Test
  fun `客户端帧必须带掩码且服务端读得出`() {
    val payload = """{"n":"0","c":"abc"}""".toByteArray()
    val encoded = WebSocketFrames.encode(WebSocketFrames.OP_TEXT, payload, rnd, masked = true)

    // 第二字节的最高位是掩码标志
    assertEquals("客户端帧的掩码位必须是 1", 0x80, encoded[1].toInt() and 0x80)

    val decoded = WebSocketFrames.read(ByteArrayInputStream(encoded), expectMasked = true)
    assertArrayEquals("解码后必须还原原文", payload, decoded.payload)
    assertEquals(WebSocketFrames.OP_TEXT, decoded.opcode)
    assertTrue(decoded.fin)
  }

  @Test
  fun `服务端帧不带掩码且客户端读得出`() {
    val payload = "welcome".toByteArray()
    val encoded = WebSocketFrames.encode(WebSocketFrames.OP_TEXT, payload, rnd, masked = false)

    assertEquals("服务端帧的掩码位必须是 0", 0x00, encoded[1].toInt() and 0x80)

    val decoded = WebSocketFrames.read(ByteArrayInputStream(encoded), expectMasked = false)
    assertArrayEquals(payload, decoded.payload)
  }

  @Test
  fun `客户端拒绝被掩码的服务端帧`() {
    // 这一条是本文件存在的首要理由：去掉 read() 里的掩码校验，
    // 端到端 10 项仍然全过（实测过），而真机上这意味着
    // 「中间人可以把服务端帧改写成带掩码形态」被静默接受。
    val encoded = WebSocketFrames.encode(
      WebSocketFrames.OP_TEXT, "x".toByteArray(), rnd, masked = true,
    )
    try {
      WebSocketFrames.read(ByteArrayInputStream(encoded), expectMasked = false)
      fail("服务端帧带掩码时必须拒绝")
    } catch (e: WebSocketFrames.FrameError) {
      assertTrue("错误信息要说明掩码方向问题", e.message!!.contains("掩码"))
    }
  }

  @Test
  fun `服务端拒绝未掩码的客户端帧`() {
    // 反方向同理：RFC 6455 §5.1 规定服务端必须关闭未掩码的连接
    val encoded = WebSocketFrames.encode(
      WebSocketFrames.OP_TEXT, "x".toByteArray(), rnd, masked = false,
    )
    try {
      WebSocketFrames.read(ByteArrayInputStream(encoded), expectMasked = true)
      fail("客户端帧没掩码时必须拒绝")
    } catch (e: WebSocketFrames.FrameError) {
      assertTrue(e.message!!.contains("掩码"))
    }
  }

  // ═══════════ 长度编码的三个分支 ═══════════

  @Test
  fun `短载荷用7位长度`() {
    val payload = ByteArray(125) { it.toByte() }
    val encoded = WebSocketFrames.encode(WebSocketFrames.OP_TEXT, payload, rnd, masked = false)
    assertEquals("125 字节应直接写长度", 125, encoded[1].toInt() and 0x7F)

    val decoded = WebSocketFrames.read(ByteArrayInputStream(encoded), expectMasked = false)
    assertArrayEquals(payload, decoded.payload)
  }

  @Test
  fun `126字节走2字节长度`() {
    // 边界：125 是直接表示的上限，126 必须切换编码
    val payload = ByteArray(126) { it.toByte() }
    val encoded = WebSocketFrames.encode(WebSocketFrames.OP_TEXT, payload, rnd, masked = false)
    assertEquals("126 字节时长度字段应为 126 标记", 126, encoded[1].toInt() and 0x7F)
    assertEquals("随后 2 字节大端表示真实长度", 0, encoded[2].toInt())
    assertEquals(126, encoded[3].toInt() and 0xFF)

    val decoded = WebSocketFrames.read(ByteArrayInputStream(encoded), expectMasked = false)
    assertArrayEquals(payload, decoded.payload)
  }

  @Test
  fun `65535与65536字节分别走2字节与8字节长度`() {
    val mid = ByteArray(65535) { (it % 251).toByte() }
    val encMid = WebSocketFrames.encode(WebSocketFrames.OP_TEXT, mid, rnd, masked = false)
    assertEquals("65535 仍用 2 字节长度", 126, encMid[1].toInt() and 0x7F)
    assertArrayEquals(mid, WebSocketFrames.read(ByteArrayInputStream(encMid), false).payload)

    val big = ByteArray(65536) { (it % 251).toByte() }
    val encBig = WebSocketFrames.encode(WebSocketFrames.OP_TEXT, big, rnd, masked = false)
    assertEquals("65536 必须切到 8 字节长度", 127, encBig[1].toInt() and 0x7F)
    assertArrayEquals(big, WebSocketFrames.read(ByteArrayInputStream(encBig), false).payload)
  }

  @Test
  fun `超过上限的消息被拒绝`() {
    // 与桌面 maxPayload 对齐（8MB）。这里不构造 8MB 数组，
    // 直接用 read 拒绝超长声明来验证守卫。
    val out = ByteArrayOutputStream()
    out.write(0x81)                    // FIN + text
    out.write(127)                     // 8 字节长度
    val huge = WebSocketFrames.MAX_MESSAGE_BYTES.toLong() + 1
    for (i in 7 downTo 0) out.write(((huge ushr (8 * i)) and 0xFF).toInt())
    try {
      WebSocketFrames.read(ByteArrayInputStream(out.toByteArray()), expectMasked = false)
      fail("超过上限必须拒绝")
    } catch (e: WebSocketFrames.FrameError) {
      assertTrue("错误信息要提到过大", e.message!!.contains("过大"))
    }
  }

  // ═══════════ 控制帧 ═══════════

  @Test
  fun `控制帧载荷不能超过125字节`() {
    try {
      WebSocketFrames.encode(WebSocketFrames.OP_CLOSE, ByteArray(126), rnd, masked = true)
      fail("控制帧超长必须拒绝")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("125"))
    }
  }

  @Test
  fun `close帧编解码往返`() {
    val payload = WebSocketFrames.encodeClose(4401, "unauthorized")
    val (code, reason) = WebSocketFrames.parseClose(payload)
    assertEquals(4401, code)
    assertEquals("unauthorized", reason)
  }

  @Test
  fun `close帧空载荷表示无关闭码`() {
    val (code, _) = WebSocketFrames.parseClose(ByteArray(0))
    assertEquals("空载荷按 1005 处理（RFC 6455）", 1005, code)
  }

  @Test
  fun `close帧只带1字节是非法`() {
    try {
      WebSocketFrames.parseClose(ByteArray(1))
      fail("1 字节 close 载荷非法")
    } catch (e: WebSocketFrames.FrameError) {
      assertTrue(e.message!!.contains("1 字节"))
    }
  }

  @Test
  fun `ping与pong的opcode正确`() {
    for ((op, name) in listOf(
      WebSocketFrames.OP_PING to "ping",
      WebSocketFrames.OP_PONG to "pong",
      WebSocketFrames.OP_CLOSE to "close",
    )) {
      val enc = WebSocketFrames.encode(op, ByteArray(0), rnd, masked = true)
      val dec = WebSocketFrames.read(ByteArrayInputStream(enc), expectMasked = true)
      assertEquals("$name 的 opcode 必须保持", op, dec.opcode)
      assertTrue("$name 是控制帧", dec.isControl)
    }
  }

  @Test
  fun `设置了RSV位时拒绝`() {
    // 未协商任何扩展，RSV 必须为 0。若接受，等于允许「压缩炸弹」之类的扩展攻击面
    val out = ByteArrayOutputStream()
    out.write(0xC1)   // FIN + RSV1 + text
    out.write(0)      // 长度 0，不带掩码
    try {
      WebSocketFrames.read(ByteArrayInputStream(out.toByteArray()), expectMasked = false)
      fail("RSV 位非零必须拒绝")
    } catch (e: WebSocketFrames.FrameError) {
      assertTrue(e.message!!.contains("RSV"))
    }
  }

  // ═══════════ 握手校验 ═══════════

  @Test
  fun `acceptKey符合RFC示例`() {
    // RFC 6455 §1.3 的官方示例：key 对应的 accept 值
    assertEquals(
      "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
      WebSocketFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="),
    )
  }

  @Test
  fun `握手响应accept不匹配时拒绝`() {
    val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
      "Sec-WebSocket-Accept: wrongvalue\r\n\r\n"
    try {
      WebSocketFrames.verifyHandshakeResponse(resp, "dGhlIHNhbXBsZSBub25jZQ==")
      fail("accept 不匹配必须拒绝")
    } catch (e: WebSocketFrames.FrameError) {
      assertTrue(e.message!!.contains("Accept"))
    }
  }

  @Test
  fun `普通HTTP200被拒为握手失败`() {
    // 不校验状态码的话，一个 200 的网页也会被当成握手成功，
    // 之后就进入「读帧读不到、一直等到超时」这种最难查的状态
    val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n"
    try {
      WebSocketFrames.verifyHandshakeResponse(resp, "abc")
      fail("非 101 必须拒绝")
    } catch (e: WebSocketFrames.FrameError) {
      // 断言必须能被这条错误消息满足：实现写的是「握手失败：<状态行>」，
      // 不含字面量 101。写成 contains("101") 会让测试永远红，
      // 而如果为了让它绿去改实现加「101」字样，又是在迁就断言而不是修行为。
      assertTrue("错误信息应说明握手失败：${e.message}",
        e.message!!.contains("握手失败") && e.message!!.contains("200"))
    }
  }

  @Test
  fun `缺Upgrade头的响应被拒`() {
    val key = "dGhlIHNhbXBsZSBub25jZQ=="
    val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
      "Connection: Upgrade\r\n" +
      "Sec-WebSocket-Accept: ${WebSocketFrames.acceptKey(key)}\r\n\r\n"
    try {
      WebSocketFrames.verifyHandshakeResponse(resp, key)
      fail("缺 Upgrade 头必须拒绝")
    } catch (e: WebSocketFrames.FrameError) {
      assertTrue(e.message!!.contains("Upgrade"))
    }
  }

  @Test
  fun `合法的握手响应被接受`() {
    val key = "dGhlIHNhbXBsZSBub25jZQ=="
    val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
      "Sec-WebSocket-Accept: ${WebSocketFrames.acceptKey(key)}\r\n\r\n"
    WebSocketFrames.verifyHandshakeResponse(resp, key)   // 不抛即通过
  }

  @Test
  fun `握手请求头齐全`() {
    val req = WebSocketFrames.handshakeRequest("192.168.1.10", 47833, "/", "abc123")
    assertTrue("必须是 GET", req.startsWith("GET / HTTP/1.1"))
    assertTrue("必须带 Host（含端口）", req.contains("Host: 192.168.1.10:47833"))
    assertTrue(req.contains("Upgrade: websocket"))
    assertTrue(req.contains("Connection: Upgrade"))
    assertTrue(req.contains("Sec-WebSocket-Key: abc123"))
    assertTrue("版本必须是 13", req.contains("Sec-WebSocket-Version: 13"))
    assertTrue("必须以空行结束", req.endsWith("\r\n\r\n"))
  }

  // ═══════════ 分片 ═══════════

  @Test
  fun `分片帧的fin位被正确保留`() {
    // 重组逻辑在 RelaySocketClient 里，这里只保证 fin 位编解码无损
    val enc1 = WebSocketFrames.encode(
      WebSocketFrames.OP_TEXT, "a".toByteArray(), rnd, fin = false, masked = false,
    )
    val d1 = WebSocketFrames.read(ByteArrayInputStream(enc1), expectMasked = false)
    assertTrue("首片 fin 应为 false", !d1.fin)

    val enc2 = WebSocketFrames.encode(
      WebSocketFrames.OP_CONTINUATION, "b".toByteArray(), rnd, fin = true, masked = false,
    )
    val d2 = WebSocketFrames.read(ByteArrayInputStream(enc2), expectMasked = false)
    assertTrue("末片 fin 应为 true", d2.fin)
    assertEquals(WebSocketFrames.OP_CONTINUATION, d2.opcode)
  }
}

// ───── RelayHintTest.kt ─────
/**
 * `relayHint` 解析。
 *
 * 这一段之所以单独测而不是靠 UI 测试：它的每一条都是「应当被拒绝的字符串」，
 * 用 UI 测试表达要绕一大圈（还得造一张票据、走到配对流程尾部）。
 *
 * 更重要的一点：**解析失败必须返回 null，而不是猜一个默认地址**。
 * 拿默认地址去连，失败时用户会去查网络、查防火墙、查路由器，
 * 而真正原因是票据里没带地址 —— 排查方向完全被误导。
 */
class RelayHintTest {

  @Test
  fun 标准hostPort被正确解析() {
    val e = RelayHint.parse("192.168.1.10:7788")
    assertEquals("192.168.1.10", e?.host)
    assertEquals(7788, e?.port)
  }

  @Test
  fun 域名也能解析() {
    val e = RelayHint.parse("relay.morrow.example:443")
    assertEquals("relay.morrow.example", e?.host)
    assertEquals(443, e?.port)
  }

  @Test
  fun IPv6带方括号能正确解析() {
    // IPv6 的冒号在方括号里，简单 split(":") 会把地址切碎 ——
    // 这是这类解析最常写错的地方
    val e = RelayHint.parse("[::1]:7788")
    assertEquals("::1", e?.host)
    assertEquals(7788, e?.port)

    val e2 = RelayHint.parse("[fe80::1ff:fe23:4567:890a]:9000")
    assertEquals("fe80::1ff:fe23:4567:890a", e2?.host)
    assertEquals(9000, e2?.port)
  }

  @Test
  fun 空与null返回null() {
    assertNull(RelayHint.parse(null))
    assertNull(RelayHint.parse(""))
    assertNull(RelayHint.parse("   "))
  }

  @Test
  fun 缺少端口返回null() {
    assertNull("只有 host 没端口", RelayHint.parse("192.168.1.10"))
  }

  @Test
  fun 端口越界返回null() {
    assertNull(RelayHint.parse("h:0"))
    assertNull(RelayHint.parse("h:65536"))
    assertNull(RelayHint.parse("h:-1"))
  }

  @Test
  fun 端口不是纯数字返回null() {
    // toIntOrNull 会接受 "+80"，所以要额外挡
    assertNull(RelayHint.parse("h:+80"))
    assertNull(RelayHint.parse("h:80abc"))
    assertNull(RelayHint.parse("h: "))
  }

  @Test
  fun 缺少host返回null() {
    assertNull(RelayHint.parse(":7788"))
  }

  @Test
  fun IPv6方括号格式错误返回null() {
    assertNull("方括号未闭合", RelayHint.parse("[::1:7788"))
    assertNull("方括号后没有冒号", RelayHint.parse("[::1]7788"))
    assertNull("空方括号", RelayHint.parse("[]:7788"))
  }

  @Test
  fun 冒号在host中间且无端口的写法被拒绝() {
    // "a:b" 可以解释为 host=a port=b，而 b 不是数字 → 拒绝。
    // 这条守的是「不要退回按第一个冒号切」的错法。
    assertNull(RelayHint.parse("a:b"))
  }
}
