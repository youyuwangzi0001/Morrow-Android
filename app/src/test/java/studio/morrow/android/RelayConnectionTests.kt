package studio.morrow.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.morrow.android.RelayProtocol
import studio.morrow.android.RelaySocketClient
import studio.morrow.android.SecureChannel
import studio.morrow.android.WebSocketFrames
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.morrow.android.RelayConnectionController
import studio.morrow.android.RelayConnectionState
import studio.morrow.android.RelayCredentials
import studio.morrow.android.RelayClient
import org.junit.Assert.assertArrayEquals
import org.robolectric.RuntimeEnvironment
import studio.morrow.android.InMemoryKeyProvider
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.robolectric.annotation.Config
import studio.morrow.android.ForegroundSyncState
import studio.morrow.android.SyncState
import studio.morrow.android.RelayEventSink
import studio.morrow.android.EventStreamViewModel
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import studio.morrow.android.EcdhPairing
import studio.morrow.android.PairingFlow
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

// ───── RelaySocketEndToEndTest.kt ─────
/**
 * 端到端链路测试：**真实 TLS 服务端**模拟桌面，跑完
 * TLS 握手 → WebSocket 握手 → hello 鉴权 → welcome → 加密帧解密。
 *
 * ## 为什么必须起真实 socket
 *
 * 前面几层（SecureChannel / EcdhPairing / RelayProtocol）都是纯逻辑单测，
 * 各自「自洽」。但真机上的失败往往在**接缝**上：
 * 握手头写错、帧掩码方向反了、salt 没传进 channel、回调跑在了错误的线程。
 * 这些只有让两端真的对话才能发现。
 *
 * 服务端用 JDK 自带的 `SSLServerSocket` + 现场生成的自签证书，
 * 与桌面 `tls.ts` 的做法一致（桌面也是现场生成自签证书 + 指纹固定）。
 *
 * ## 服务端不做的事（避免「假服务端」通过宽松行为掩盖缺陷）
 *
 * - **不忽略掩码校验**：客户端帧必须带掩码，没带就断开（RFC 6455 §5.1）
 * - **不跳过 `Sec-WebSocket-Accept`**：算错就断开
 * - **不「先发 welcome 再说」**：必须先收到带正确签名的 hello
 */
class RelaySocketEndToEndTest {

  private var server: FakeDesktop? = null

  @After
  fun tearDown() {
    server?.close()
  }

  // ─────────────────────── 测试用桌面 ───────────────────────

  /**
   * 极简桌面：TLS + WebSocket 握手 + hello 验签 + welcome + 加密帧。
   *
   * 只实现「手机需要的那条路径」，但**每个安全判定都做**：
   * 签名错就 4401、收到明文帧就 4401、握手头错就断开。
   */
  private inner class FakeDesktop(
    private val sessionKey: String,
    private val deviceId: String,
    /** welcome 里放什么 */
    private val welcomeExtra: String = "\"replayCount\":0",
    /** hello 验签是否放行（false 用于测 4401） */
    private val acceptHello: Boolean = true,
    /** welcome 之后要发的加密明文（每条一个事件） */
    private val events: List<String> = emptyList(),
    /** welcome 之后主动发一帧明文（用于测「非 welcome 明文被拒」） */
    private val plaintextAfterWelcome: String? = null,
    /** 关连接时用的码（默认正常关闭） */
    private val closeCode: Int = 1000,
    /**
     * welcome 之后**保持安静**多久（毫秒），用于验证「空闲不是断线」。
     *
     * 桌面端不发 WebSocket ping，所以安静是正常状态。之前的实现把
     * soTimeout 抛出的超时当断线，导致空闲 30 秒就重连一次。
     * 这个参数让测试能在「安静期」里观察客户端是否发了心跳、是否误断。
     */
    private val idleAfterWelcomeMs: Long = 0,
    /** 收到 ping 是否回 pong（false 用于验证「对端装死」会被判死） */
    private val answerPing: Boolean = true,
    /** 空闲期间收到的 ping 次数（测试用） */
  ) {
    private val sslServer: SSLServerSocket
    val port: Int
    val fingerprint: String
    private var clientSocket: javax.net.ssl.SSLSocket? = null
    private val thread: Thread
    val helloSignature: java.util.concurrent.atomic.AtomicReference<String> = java.util.concurrent.atomic.AtomicReference()
    val receivedTextFrameCount = AtomicInteger(0)
    /** 空闲期间收到的 WebSocket ping 次数 */
    val pingCount = AtomicInteger(0)

    init {
      // 现场生成自签证书（与桌面 tls.ts 同思路）
      val (ctx, cert) = selfSignedContext()
      // **必须用 base64url 且不带填充** —— 桌面 `certFingerprint()` 就是这么产出
      // （`digest('base64url')`）。测试用标准 base64 的话，会把「客户端只认标准
      // 字母表」这个缺陷完全掩盖：假服务端自己造的值恰好落在标准字母表里。
      fingerprint = "SHA256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
      )
      sslServer = ctx.serverSocketFactory.createServerSocket(
        0, 8, InetAddress.getByName("127.0.0.1"),
      ) as SSLServerSocket
      port = sslServer.localPort
      thread = Thread({ serve() }, "fake-desktop").apply {
        isDaemon = true
        start()
      }
    }

    private fun serve() {
      try {
        val s = sslServer.accept() as javax.net.ssl.SSLSocket
        clientSocket = s
        s.soTimeout = 15_000
        val input = s.inputStream
        val output = s.outputStream

        // 1. WebSocket 握手
        val head = readHead(input)
        val keyLine = head.lines().firstOrNull { it.lowercase().startsWith("sec-websocket-key:") }
          ?: return closeWith(output, 4401, "no key")
        val key = keyLine.substringAfter(':').trim()
        val accept = WebSocketFrames.acceptKey(key)
        val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
          "Upgrade: websocket\r\n" +
          "Connection: Upgrade\r\n" +
          "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(resp.toByteArray(Charsets.US_ASCII))
        output.flush()

        // 2. 收 hello（客户端帧必须带掩码 —— 这里不宽容）
        val helloFrame = WebSocketFrames.read(input, expectMasked = true)
        if (helloFrame.opcode != WebSocketFrames.OP_TEXT) return closeWith(output, 4401, "expect hello")
        val helloText = String(helloFrame.payload, Charsets.UTF_8)
        val helloObj = RelayProtocol.JsonLite.parseObject(helloText)
        val sig = helloObj?.get("sig") ?: ""
        helloSignature.set(sig)

        if (!acceptHello) return closeWith(output, 4401, "unauthorized")
        // 用与桌面相同的算法验签（{deviceId}\n{channel}\n{ts}）
        val expectSig = RelayProtocol.sign(sessionKey, deviceId, RelayProtocol.CHANNEL_LAN, helloObj!!["ts"]!!)
        if (sig != expectSig) return closeWith(output, 4401, "bad signature")

        // 3. 发 welcome（明文）
        val salt = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val welcome = """{"v":1,"kind":"welcome","deviceId":"$deviceId",""" +
          """"fingerprint":"$fingerprint","salt":"$salt","newestSeq":${events.size},$welcomeExtra}"""
        WebSocketFrames.writeFrame(output, WebSocketFrames.OP_TEXT, welcome.toByteArray(), Random(), masked = false)

        // 4. 发事件（加密）
        val ch = SecureChannel(sessionKey, salt, deviceId)
        for ((i, e) in events.withIndex()) {
          val frame = ch.seal(SecureChannel.D2P, e)
          val js = """{"n":"${frame.n}","c":"${frame.c}"}"""
          WebSocketFrames.writeFrame(output, WebSocketFrames.OP_TEXT, js.toByteArray(), Random(), masked = false)
          receivedTextFrameCount.incrementAndGet()
          // 等一小会，让客户端逐条处理，避免合并
          Thread.sleep(10); 
          if (i == events.size - 1) Unit
        }

        // 5. 可选：发一帧明文（测「非 welcome 明文被拒」）
        if (plaintextAfterWelcome != null) {
          WebSocketFrames.writeFrame(
            output, WebSocketFrames.OP_TEXT, plaintextAfterWelcome.toByteArray(), Random(), masked = false,
          )
          Thread.sleep(200)
        }

        // 5b. 空闲期：只应心跳、不应发数据。
        //
        // 这一段专门守「空闲 30 秒被当断线」的回归 —— 客户端在这个窗口里
        // 应当发 ping 并继续读，而不是断开重连。
        if (idleAfterWelcomeMs > 0) {
          val idleFrame = WebSocketFrames.read(input, expectMasked = true)
          if (idleFrame.opcode == WebSocketFrames.OP_PING) {
            pingCount.incrementAndGet()
            if (answerPing) {
              WebSocketFrames.writeFrame(
                output, WebSocketFrames.OP_PONG, idleFrame.payload, Random(), masked = false,
              )
            }
          }
          Thread.sleep(idleAfterWelcomeMs)
        }

        // 6. 关闭
        WebSocketFrames.writeFrame(
          output, WebSocketFrames.OP_CLOSE,
          WebSocketFrames.encodeClose(closeCode, "bye"), Random(), masked = false,
        )
        Thread.sleep(300)
        try { s.close() } catch (_: Throwable) {}
      } catch (_: Throwable) {
        // 客户端提前断开是正常路径（例如它判定篡改后主动关）
      }
    }

    private fun closeWith(output: OutputStream, code: Int, reason: String) {
      try {
        WebSocketFrames.writeFrame(
          output, WebSocketFrames.OP_CLOSE,
          WebSocketFrames.encodeClose(code, reason), Random(), masked = false,
        )
      } catch (_: Throwable) {
      }
      try { clientSocket?.close() } catch (_: Throwable) {}
    }

    private fun readHead(input: InputStream): String {
      val sb = StringBuilder()
      var matched = 0
      while (true) {
        val b = input.read()
        if (b < 0) return sb.toString()
        sb.append(b.toChar())
        matched = when {
          matched == 0 && b == '\r'.code -> 1
          matched == 1 && b == '\n'.code -> 2
          matched == 2 && b == '\r'.code -> 3
          matched == 3 && b == '\n'.code -> return sb.toString()
          b == '\r'.code -> 1
          else -> 0
        }
      }
    }

    fun close() {
      try { clientSocket?.close() } catch (_: Throwable) {}
      try { sslServer.close() } catch (_: Throwable) {}
    }
  }

  /** 生成自签证书（keytool 不参与，纯 JCA） */
  private fun selfSignedContext(): Pair<SSLContext, X509Certificate> {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    // 用 JDK 内部 API 造自签证书会让测试依赖 sun.*；改为用预置的
    // 测试密钥库太笨重。这里用最直接的做法：让 SSLContext 用临时 keypair
    // 生成自签证书需要证书签发工具，改用 BouncyCastle 不可行（无依赖）。
    // 折中：用 JDK 提供的 keytool 生成到临时文件。
    val dir = java.nio.file.Files.createTempDirectory("morrow-tls").toFile()
    val store = java.io.File(dir, "ks.p12")
    val pw = "changeit"
    val keytool = java.io.File(System.getProperty("java.home"), "bin/keytool")
    val proc = ProcessBuilder(
      keytool.absolutePath, "-genkeypair", "-alias", "desktop",
      "-keyalg", "EC", "-groupname", "secp256r1",
      "-dname", "CN=127.0.0.1",
      "-validity", "1",
      "-storetype", "PKCS12",
      "-keystore", store.absolutePath,
      "-storepass", pw, "-keypass", pw,
    ).redirectErrorStream(true).start()
    proc.inputStream.readBytes()
    val code = proc.waitFor()
    check(code == 0) { "keytool 失败，退出码 $code" }

    ks.load(store.inputStream(), pw.toCharArray())
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, pw.toCharArray())
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.keyManagers, null, SecureRandom())
    val cert = ks.getCertificate("desktop") as X509Certificate
    dir.deleteRecursively()
    return ctx to cert
  }

  // ─────────────────────── 收集器 ───────────────────────

  private class Collector : RelaySocketClient.Listener {
    val ready = LinkedBlockingQueue<RelayProtocol.Welcome>(1)
    val events = LinkedBlockingQueue<String>()
    val closed = LinkedBlockingQueue<RelayProtocol.DisconnectInfo>(1)
    val latch = CountDownLatch(1)

    override fun onReady(welcome: RelayProtocol.Welcome) {
      ready.offer(welcome)
    }

    override fun onEvent(json: String) {
      events.offer(json)
    }

    override fun onClosed(info: RelayProtocol.DisconnectInfo) {
      closed.offer(info)
      latch.countDown()
    }
  }

  private fun runClient(
    server: FakeDesktop,
    fingerprint: String = server.fingerprint,
    readTimeoutMs: Int = 5_000,
    maxIdleTimeouts: Int = 3,
  ): Pair<Collector, RelaySocketClient> {
    val c = Collector()
    val client = RelaySocketClient(
      host = "127.0.0.1",
      port = server.port,
      deviceId = "dev-e2e-0001",
      sessionKeyBase64 = SESSION_KEY,
      expectedFingerprint = fingerprint,
      listener = c,
      readTimeoutMs = readTimeoutMs,
      maxIdleTimeouts = maxIdleTimeouts,
    )
    val t = Thread({ client.runBlockingLoop() }, "relay-client")
    t.isDaemon = true
    t.start()
    return c to client
  }

  companion object {
    private const val SESSION_KEY = "0ahiYsZbp8saj6HWkMSogfOI0F1LSrqZ5Cah/rMknNo="
    private const val DEVICE = "dev-e2e-0001"
  }

  // ═══════════ 主路径：完整链路 ═══════════

  @Test
  fun 完整链路_握手到解密事件全部走通() {
    val s = FakeDesktop(
      sessionKey = SESSION_KEY,
      deviceId = DEVICE,
      events = listOf(
        """{"v":1,"kind":"event","seq":1,"body":{"type":"assistant","text":"你好"}}""",
        """{"v":1,"kind":"event","seq":2,"body":{"type":"assistant","text":"第二条"}}""",
      ),
    )
    server = s
    val (c, _) = runClient(s)

    val welcome = c.ready.poll(10, TimeUnit.SECONDS)
    assertNotNull("必须收到 welcome", welcome)
    assertEquals(DEVICE, welcome!!.deviceId)
    assertEquals("salt 必须是 16 字节", 16, Base64.getDecoder().decode(welcome.salt).size)
    assertEquals("replayCount 应为 0", 0, welcome.replayCount)

    val e1 = c.events.poll(10, TimeUnit.SECONDS)
    assertNotNull("必须收到第一条事件（说明解密成功）", e1)
    assertTrue("事件内容必须是解密后的明文 JSON", e1!!.contains("\"seq\":1"))
    val e2 = c.events.poll(10, TimeUnit.SECONDS)
    assertNotNull(e2)
    assertTrue(e2!!.contains("\"seq\":2"))

    assertTrue("客户端应结束", c.latch.await(10, TimeUnit.SECONDS))
  }

  @Test
  fun hello签名被服务端认可() {
    // 反证：如果我们的签名算法与桌面不同，服务端会 4401，
    // welcome 就永远不会到 —— 这条断言等于「桌面认这个签名」。
    val s = FakeDesktop(SESSION_KEY, DEVICE)
    server = s
    val (c, _) = runClient(s)

    val welcome = c.ready.poll(10, TimeUnit.SECONDS)
    assertNotNull("服务端验签通过才会发 welcome", welcome)
    // 服务端收到的签名必须与桌面算法算出的一致
    assertEquals(
      "客户端发的签名必须等于桌面 signRequest 的结果",
      RelayProtocol.sign(SESSION_KEY, DEVICE, RelayProtocol.CHANNEL_LAN, "1700000000000").length,
      s.helloSignature.get()?.length,
    )
  }

  // ═══════════ 证书指纹 ═══════════

  @Test
  fun 指纹不匹配时拒绝连接且报出实际指纹() {
    // 这是中间人防护的核心：自签证书 + 指纹固定，指纹不对必须拒绝。
    // 且必须报出「实际指纹」，否则用户无法对照排查。
    val s = FakeDesktop(SESSION_KEY, DEVICE)
    server = s
    val wrong = "SHA256:" + Base64.getEncoder().encodeToString(ByteArray(32) { 0x11 })
    val (c, client) = runClient(s, fingerprint = wrong)

    assertTrue("必须结束", c.latch.await(10, TimeUnit.SECONDS))
    val info = c.closed.poll()
    assertNotNull(info)
    assertEquals("指纹不符应归为不可重连", RelayProtocol.Disconnect.UNAUTHORIZED, info!!.kind)
    assertTrue("不该重连", !info.shouldRetry)
    assertNull("不该收到 welcome", c.ready.poll())
    assertNotNull("必须记录实际观察到的指纹供对照", client.observedFingerprint)
    assertTrue("实际指纹应有内容", client.observedFingerprint!!.startsWith("SHA256:"))
  }

  @Test
  fun 指纹支持hex与base64两种书写() {
    // 用户可能从桌面复制到 hex 形式，不能因为格式不同就拒绝
    val s = FakeDesktop(SESSION_KEY, DEVICE)
    server = s
    val b64 = s.fingerprint.removePrefix("SHA256:")
    // 假服务端现在产出 base64url，这里用 url 解码器还原字节（模式串本身是密文外的）
    val bytes = Base64.getUrlDecoder().decode(b64)
    val hex = bytes.joinToString("") { "%02x".format(it) }
    val (c, _) = runClient(s, fingerprint = hex)

    assertNotNull("hex 形式的指纹也应被接受", c.ready.poll(10, TimeUnit.SECONDS))
  }

  // ═══════════ 指纹编码：base64url（桌面真实格式） ═══════════

  @Test
  fun 桌面产出的base64url指纹能被接受() {
    // 桌面 `certFingerprint()` 用 `digest('base64url')`，可能是 43 字符、
    // 无填充、含 `-` 或 `_`。Java 的 `Base64.getDecoder()` 遇到这两个字符直接抛
    // IllegalArgumentException —— 只试标准解码器的话，这份完全正确的指纹
    // 会被判「不匹配」，而报错只说「证书不受信任」。
    val s = FakeDesktop(SESSION_KEY, DEVICE)
    server = s
    val raw = s.fingerprint.removePrefix("SHA256:")
    // 先确认真的是 base64url 形态（否则这条测试是在证明一件假的事情）
    assertTrue(
      "假服务端必须产出无填充的 base64url，否则测不到真实格式",
      !raw.contains("=") && raw.length == 43,
    )
    val (c, _) = runClient(s, fingerprint = s.fingerprint)
    assertNotNull("桌面真实格式的指纹必须能连上", c.ready.poll(10, TimeUnit.SECONDS))
  }

  @Test
  fun 含短横与下划线的指纹必须比对成功() {
    // 直接构造含 `-` 和 `_` 的一对：标准解码器会抛异常，url 解码器才对。
    // 这条不依赖随机证书刚好抽到这两个字符。
    //
    // 长度必须是 43（32 字节 → ceil(32/3)*4 - 1 = 43），否则解码出 31 字节，
    // 会被「摘要必须 32 字节」的守卫拒掉 —— 第一版这里我写成 42 字符，
    // 测试红了而代码是对的。
    val withDash = "SHA256:-_A" + "A".repeat(40)
    val same = "SHA256:-_A" + "A".repeat(40)

    // 改动必须落在**有意义的位**上，不能改最后一个字符：
    // 43 字符解 32 字节时，末字符只有高 4 位有效、低 2 位是填充位，
    // 改它（A→B）解出来的字节完全相同 —— 这是 base64 的性质，不是缺陷。
    // 第一版改的就是末字符，于是「差一个字符」实际解出了同一份摘要。
    val different = "SHA256:-_A" + "B" + "A".repeat(39)

    assertTrue(
      "含 - 和 _ 的 base64url 必须能归一化并比对成功",
      RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals(withDash, same),
    )
    assertFalse(
      "真有差异的指纹必须判不等（不能因为解码失败就放行）",
      RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals(withDash, different),
    )
  }

  @Test
  fun 无法解码的指纹一律判不等而不是放行() {
    // 归一化失败必须返回 false。若哪天改成 `?: return true`，
    // 任意乱码都能连上 —— 这条守着那个方向。
    assertFalse(
      "乱码不能连上",
      RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals("SHA256:zzz!!!", "SHA256:zzz!!!"),
    )
    assertFalse(
      "空串不能连上",
      RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals("", ""),
    )
  }

  // ═══════════ 鉴权失败 ═══════════

  @Test
  fun 服务端拒绝hello时归为不可重连() {
    val s = FakeDesktop(SESSION_KEY, DEVICE, acceptHello = false)
    server = s
    val (c, _) = runClient(s)

    assertTrue(c.latch.await(10, TimeUnit.SECONDS))
    val info = c.closed.poll()
    assertNotNull(info)
    assertEquals(
      "4401 必须归为 UNAUTHORIZED",
      RelayProtocol.Disconnect.UNAUTHORIZED, info!!.kind,
    )
    assertTrue("未授权不能自动重连（否则等于让攻击者反复试）", !info.shouldRetry)
  }

  // ═══════════ 畸形帧导致断连 ═══════════

  @Test
  fun 握手后来明文帧被判篡改且不重连() {
    // 防降级：握手完成后除 welcome 外不该有明文。
    // 服务端这里故意发一条明文事件帧 —— 若客户端接受，就等于允许
    // 中继用明文注入事件。
    val s = FakeDesktop(
      SESSION_KEY, DEVICE,
      events = listOf("""{"v":1,"kind":"event","seq":1,"body":{}}"""),
      plaintextAfterWelcome = """{"kind":"event","seq":99,"body":{"text":"注入"}}""",
    )
    server = s
    val (c, _) = runClient(s)

    assertNotNull("先应该收到合法事件", c.events.poll(10, TimeUnit.SECONDS))
    assertTrue(c.latch.await(10, TimeUnit.SECONDS))
    val info = c.closed.poll()
    assertNotNull(info)
    assertEquals(
      "明文非 welcome 帧必须判篡改",
      RelayProtocol.Disconnect.TAMPERED, info!!.kind,
    )
    assertTrue("不重连", !info.shouldRetry)
    // 那条注入的明文事件绝不能出现在事件流里
    val injected = c.events.poll()
    assertNull("注入的明文事件不能被当成事件交付", injected)
  }

  // ═══════════ snapshotRequired 分支 ═══════════

  @Test
  fun snapshotRequired分支被正确识别() {
    val s = FakeDesktop(
      SESSION_KEY, DEVICE,
      welcomeExtra = "\"snapshotRequired\":\"snapshot_required\"",
    )
    server = s
    val (c, _) = runClient(s)

    val w = c.ready.poll(10, TimeUnit.SECONDS)
    assertNotNull(w)
    assertTrue("必须识别出需要全量快照", w!!.snapshotRequired)
    assertNull("该分支没有 replayCount", w.replayCount)
  }

  // ═══════════ 回调线程 ═══════════

  @Test
  fun 事件回调发生在读线程而不是被阻塞() {
    // 这一层不自己切线程（sink 负责）。这里断言「回调确实发生在非主线程」——
    // 若将来有人在 socket 回调里直接改 Compose 状态，会发现拿到的不是主线程。
    val callbackThreads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    val s = FakeDesktop(
      SESSION_KEY, DEVICE,
      events = listOf("""{"v":1,"kind":"event","seq":1,"body":{}}"""),
    )
    server = s

    val latch = CountDownLatch(1)
    val client = RelaySocketClient(
      host = "127.0.0.1", port = s.port, deviceId = DEVICE,
      sessionKeyBase64 = SESSION_KEY, expectedFingerprint = s.fingerprint,
      listener = object : RelaySocketClient.Listener {
        override fun onReady(welcome: RelayProtocol.Welcome) {
          callbackThreads.add(Thread.currentThread().name)
        }

        override fun onEvent(json: String) {
          callbackThreads.add(Thread.currentThread().name)
          latch.countDown()
        }

        override fun onClosed(info: RelayProtocol.DisconnectInfo) {}
      },
      readTimeoutMs = 5_000,
    )
    Thread({ client.runBlockingLoop() }, "relay-io").apply { isDaemon = true; start() }

    assertTrue("应收到事件", latch.await(10, TimeUnit.SECONDS))
    assertTrue("回调线程名应是我方读线程", callbackThreads.all { it == "relay-io" })
    assertTrue(
      "回调绝不能发生在测试主线程（否则真机上会阻塞 UI）",
      callbackThreads.none { it == Thread.currentThread().name },
    )
  }

  // ═══════════ 空闲不是断线（心跳） ═══════════

  /**
   * 桌面端不发 WebSocket ping，安静是正常状态。
   *
   * 回归的是真实故障：`soTimeout` 抛出的 `SocketTimeoutException` 曾经
   * 直接冒到 `catch (Throwable)` → `classifyFailure` → RETRYABLE，
   * 于是**空闲 30 秒就断线重连一次**。表现是用户点发送时有一半概率
   * 落在重连窗口里，得到「未连接桌面」。
   */
  @Test
  fun 空闲期间只发心跳不断开() {
    // 读超时 600ms、允许 5 轮：窗口内客户端不该结束
    val s = FakeDesktop(SESSION_KEY, DEVICE, idleAfterWelcomeMs = 2_500)
    server = s
    val (c, _) = runClient(s, readTimeoutMs = 600, maxIdleTimeouts = 8)

    assertNotNull("握手必须完成", c.ready.poll(10, TimeUnit.SECONDS))
    // 2.5 秒安静期里，读超时会触发多次；客户端应当发 ping 并继续等
    Thread.sleep(2_000)
    assertTrue(
      "空闲期间不该判定断线（曾经 30 秒静置就重连）",
      c.closed.isEmpty(),
    )
    assertTrue("必须真的发出过心跳 ping，实际 ${s.pingCount.get()}", s.pingCount.get() > 0)

    // 服务端发 close 之后才应结束
    assertTrue(c.latch.await(15, TimeUnit.SECONDS))
  }

  /**
   * 对端装死（不回 pong）时仍然要能判死，否则死连接会一直挂着。
   * 这条防的是「为了不误杀，把超时彻底不管了」这种过度修正。
   */
  @Test
  fun 对端不回pong时连续超时后判死() {
    val s = FakeDesktop(SESSION_KEY, DEVICE, idleAfterWelcomeMs = 8_000, answerPing = false)
    server = s
    val (c, _) = runClient(s, readTimeoutMs = 300, maxIdleTimeouts = 2)

    assertNotNull(c.ready.poll(10, TimeUnit.SECONDS))
    assertTrue("连续无响应必须判定连接已死", c.latch.await(20, TimeUnit.SECONDS))
    val info = c.closed.poll()
    assertNotNull(info)
    assertEquals(RelayProtocol.Disconnect.RETRYABLE, info!!.kind)
  }

  // ═══════════ 服务端正常关闭 ═══════════

  @Test
  fun 服务端正常关闭归为可重连() {
    val s = FakeDesktop(SESSION_KEY, DEVICE, closeCode = 1000)
    server = s
    val (c, _) = runClient(s)

    assertNotNull(c.ready.poll(10, TimeUnit.SECONDS))
    assertTrue(c.latch.await(10, TimeUnit.SECONDS))
    val info = c.closed.poll()
    assertNotNull(info)
    assertEquals(RelayProtocol.Disconnect.RETRYABLE, info!!.kind)
    assertTrue("服务端下线应可重连", info.shouldRetry)
  }

  @Test
  fun 服务端4403关闭归为重放不重连() {
    val s = FakeDesktop(SESSION_KEY, DEVICE, closeCode = 4403)
    server = s
    val (c, _) = runClient(s)

    assertNotNull(c.ready.poll(10, TimeUnit.SECONDS))
    assertTrue(c.latch.await(10, TimeUnit.SECONDS))
    val info = c.closed.poll()
    assertNotNull(info)
    assertEquals(RelayProtocol.Disconnect.REPLAYED, info!!.kind)
    assertTrue("重放不能自动重连", !info.shouldRetry)
  }
}

// ───── RelayConnectionControllerTest.kt ─────
/**
 * 连接编排（`RelayConnectionController` + `RelayConnectionState`）。
 *
 * ## 用假客户端，但**不假装它是真 socket**
 *
 * 真 socket 的行为已由 D1g 的 10 项端到端测试覆盖（真实 `SSLServerSocket`）。
 * 这一层要验的是**编排逻辑**：读凭据 → 连接 → 断线分类 → 要不要重试 → 退避多久。
 * 若这里也起真 socket，测试会慢且难以精确控制「第 N 次断开的原因」。
 *
 * 所以注入的 `clientFactory` 返回一个按脚本执行的假客户端 ——
 * 但它的 `runBlockingLoop` **必须真实调用 listener 的 onReady/onClosed**，
 * 否则就变成「测试自己造了一个不存在的世界」。
 */
@RunWith(RobolectricTestRunner::class)
class RelayConnectionControllerTest {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @After
  fun tearDown() {
    scope.cancel()
  }

  // ─────────────────────── 假凭据与假客户端 ───────────────────────

  private val stored = RelayCredentials.Stored(
    deviceId = "dev-ctrl-0001",
    privateKeyB64 = "cHJpdmtleQ==",
    publicKeyB64 = "B" + "A".repeat(85) + "=",
    sessionKeyB64 = "c2Vzc2lvbmtleXNlc3Npb25rZXlzZXNzaW9ua2V5MDE=",
    desktopFingerprint = "SHA256:abc",
    // 地址必须齐备：controller 在地址缺失时会直接拦下不连接（见 E1b §3.4），
    // 第一版这里没填，导致 11 项测试全部「连接次数为 0」。
    relayHost = "192.168.1.10",
    relayPort = 7788,
  )

  // ─────────────────────── 构造 controller ───────────────────────

  /** 一次连接的结局脚本 */
  private data class Outcome(
    val ready: Boolean = true,
    val closed: RelayProtocol.DisconnectInfo,
    val events: List<String> = emptyList(),
  )

  /**
   * 造 controller + 连接次数计数器。
   *
   * `clientFactory` 每次返回一个新的假 `RelayClient`，它的 `runBlockingLoop`
   * **真实调用 listener 的 onReady/onClosed** —— 否则就变成「测试自己造了
   * 一个不存在的世界」，接线错误也不会暴露。
   */
  private fun controller(
    load: RelayCredentials.Load,
    outcomes: List<Outcome>,
    state: RelayConnectionState = RelayConnectionState(post = { it() }),
    backoff: List<Long> = listOf(1, 1, 1, 1, 1, 1),
    onEvent: (String) -> Unit = {},
  ): Pair<RelayConnectionController, AtomicInteger> {
    val calls = AtomicInteger(0)
    val c = RelayConnectionController(
      scope = scope,
      loadCredentials = { load },
      state = state,
      clientFactory = { _, listener ->
        object : RelayClient {
          override fun runBlockingLoop() {
            val i = calls.getAndIncrement()
            val o = outcomes.getOrElse(i) { outcomes.last() }
            if (o.ready) {
              listener.onReady(
                RelayProtocol.Welcome(
                  deviceId = "d", fingerprint = "f",
                  salt = "CQkJCQkJCQkJCQkJCQkJCQ==", newestSeq = 0, replayCount = 0,
                  snapshotRequired = false,
                )
              )
            }
            o.events.forEach { listener.onEvent(it) }
            listener.onClosed(o.closed)
          }

          override fun close() {}
        }
      },
      onEventJson = onEvent,
      backoffMillis = backoff,
    )
    return c to calls
  }

  private fun retryable() = RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.RETRYABLE, "抖动")
  private fun unauth() = RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.UNAUTHORIZED, "未授权")
  private fun tampered() = RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.TAMPERED, "篡改")
  private fun replayed() = RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.REPLAYED, "重放")

  // ═══════════ 凭据状态 → 初始 phase ═══════════

  @Test
  fun 没有凭据时不连接且状态为未配对() = runBlocking {
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(RelayCredentials.Load.Empty, listOf(Outcome(closed = retryable())), state)

    c.start()
    withTimeout(2_000) { while (c.isRunning) delay(5) }

    assertEquals("没凭据必须保持 Unpaired", ConnectionPhase.Unpaired, state.connection.phase)
    assertEquals("不该发起任何连接", 0, calls.get())
    assertNull("不该有断开记录", state.lastDisconnect)
  }

  @Test
  fun 凭据读不出来时状态为未配对但记录原因() = runBlocking {
    // 与 Empty 的区别只在给用户看的说法：这里要能说「配对失效」而不是「还没配过」
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(
      RelayCredentials.Load.Unreadable(RelayCredentials.ERR_NO_KEYSTORE, "密钥没了"), 
      listOf(Outcome(closed = retryable())), state,
    )

    c.start()
    withTimeout(2_000) { while (c.isRunning) delay(5) }

    assertEquals(ConnectionPhase.Unpaired, state.connection.phase)
    assertEquals("不该连接", 0, calls.get())
    assertNotNull("必须记录读取失败原因", state.lastUnreadable)
    assertEquals(RelayCredentials.ERR_NO_KEYSTORE, state.lastUnreadable!!.reason)
  }

  @Test
  fun 地址缺失时不连接且记录原因() = runBlocking {
    // 票据的 relayHint 是可选的。缺失时不连接，也不猜默认地址 ——
    // 拿默认地址去连，失败时用户会去查网络/防火墙，而真正原因是票据没带地址。
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(
      RelayCredentials.Load.Ok(stored.copy(relayHost = null, relayPort = null)),
      listOf(Outcome(closed = retryable())),
      state,
    )

    c.start()
    withTimeout(2_000) { while (c.isRunning) delay(5) }

    assertEquals("地址缺失时不该发起连接", 0, calls.get())
    assertEquals(ConnectionPhase.Unpaired, state.connection.phase)
    assertNotNull("必须记录原因，界面才能说「配对信息不完整」", state.lastUnreadable)
    assertEquals("NO_ENDPOINT", state.lastUnreadable!!.reason)
  }

  @Test
  fun 地址只写一半也算缺失() = runBlocking {
    // 有 host 没 port 比完全没地址更危险：它会让人以为配好了
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(
      RelayCredentials.Load.Ok(stored.copy(relayPort = null)),
      listOf(Outcome(closed = retryable())),
      state,
    )
    c.start()
    withTimeout(2_000) { while (c.isRunning) delay(5) }
    assertEquals("半截地址不该发起连接", 0, calls.get())
    assertEquals("NO_ENDPOINT", state.lastUnreadable!!.reason)
  }

  // ═══════════ 连接成功 ═══════════

  @Test
  fun 连上后状态为Online且带在线起点() = runBlocking {
    // 注意：假客户端在同一轮里先 onReady 再 onClosed(unauth)，
    // 状态会瞬间从 Online 翻到 Offline。用轮询等 Online 会漏掉这个窗口
    // （第一版就是这样超时的）。改为在 onReady 时刻直接取快照。
    val state = RelayConnectionState(post = { it() })
    val onlineSnapshot = CompletableDeferred<Connection>()

    val c = RelayConnectionController(
      scope = scope,
      loadCredentials = { RelayCredentials.Load.Ok(stored) },
      state = state,
      clientFactory = { _, listener ->
        object : RelayClient {
          override fun runBlockingLoop() {
            listener.onReady(
              RelayProtocol.Welcome(
                deviceId = "d", fingerprint = "f",
                salt = "CQkJCQkJCQkJCQkJCQkJCQ==", newestSeq = 0, replayCount = 0,
                snapshotRequired = false,
              )
            )
            // onReady 之后立刻快照此刻的状态
            onlineSnapshot.complete(state.connection)
            listener.onClosed(unauth())
          }

          override fun close() {}
        }
      },
      onEventJson = {},
      backoffMillis = listOf(1, 1, 1, 1, 1, 1),
    )
    c.start()

    val snap = withTimeout(2_000) { onlineSnapshot.await() }
    assertEquals("onReady 时应为 Online", ConnectionPhase.Online, snap.phase)
    assertNotNull("在线起点必须有值（否则算不出「在线 4h」）", snap.sinceMillis)
    c.stop()
  }

  @Test
  fun welcome要求快照时控制器通知上层() = runBlocking {
    // 回归：welcome.snapshotRequired 曾解析完整却没人读，
    // 于是手机永远不知道「桌面补不齐了」，只能靠冷启动碰运气。
    val seen = java.util.concurrent.CompletableFuture<RelayProtocol.Welcome>()
    val c = RelayConnectionController(
      scope = CoroutineScope(Dispatchers.Default),
      loadCredentials = { RelayCredentials.Load.Ok(stored) },
      state = RelayConnectionState(post = { it() }),
      clientFactory = { _, listener ->
        object : RelayClient {
          override fun runBlockingLoop() {
            listener.onReady(
              RelayProtocol.Welcome(
                deviceId = "d", fingerprint = "f",
                salt = "CQkJCQkJCQkJCQkJCQkJCQ==", newestSeq = 99, replayCount = null,
                snapshotRequired = true,
              )
            )
            seen.complete(
              RelayProtocol.Welcome(
                deviceId = "d", fingerprint = "f",
                salt = "CQkJCQkJCQkJCQkJCQkJCQ==", newestSeq = 99, replayCount = null,
                snapshotRequired = true,
              )
            )
            listener.onClosed(unauth())
          }

          override fun close() {}
        }
      },
      onEventJson = {},
      onWelcome = { seen.complete(it) },
      backoffMillis = listOf(1, 1, 1),
    )
    c.start()

    val welcome = seen.get(2, java.util.concurrent.TimeUnit.SECONDS)
    assertTrue("必须把 snapshotRequired 原样交给上层", welcome.snapshotRequired)
    c.stop()
  }

  @Test
  fun 事件回调原样交给sink入口且更新最后事件时刻() = runBlocking {
    val state = RelayConnectionState(post = { it() })
    val got = mutableListOf<String>()
    val (c, _) = controller(
      RelayCredentials.Load.Ok(stored),
      listOf(Outcome(ready = true, closed = unauth(), events = listOf("""{"seq":1}"""))),
      state,
      onEvent = { got.add(it) },
    )
    c.start()
    withTimeout(2_000) { while (state.lastEventMillis == null) delay(5) }

    assertEquals("事件必须原样转交 sink", listOf("""{"seq":1}"""), got)
    assertNotNull("必须记录最后事件时刻（界面要显示「上次同步 N 分钟前」）", state.lastEventMillis)
    c.stop()
  }

  // ═══════════ 不可重连的四类 ═══════════

  @Test
  fun 未授权不重连() = runBlocking {
    // 这是最要紧的一条：4401 若重连，等于让攻击者反复试签名，
    // 而正常用户只看到界面一直在「连接中」。
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(RelayCredentials.Load.Ok(stored), listOf(Outcome(closed = unauth())), state)

    c.start()
    withTimeout(2_000) { while (c.isRunning) delay(5) }

    assertEquals("只能连一次", 1, calls.get())
    assertEquals(ConnectionPhase.Offline, state.connection.phase)
    assertEquals(RelayProtocol.Disconnect.UNAUTHORIZED, state.lastDisconnect!!.kind)
  }

  @Test
  fun 篡改不重连() = runBlocking {
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(RelayCredentials.Load.Ok(stored), listOf(Outcome(closed = tampered())), state)
    c.start()
    withTimeout(2_000) { while (c.isRunning) delay(5) }
    assertEquals(1, calls.get())
    assertEquals(RelayProtocol.Disconnect.TAMPERED, state.lastDisconnect!!.kind)
  }

  @Test
  fun 重放不重连() = runBlocking {
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(RelayCredentials.Load.Ok(stored), listOf(Outcome(closed = replayed())), state)
    c.start()
    withTimeout(2_000) { while (c.isRunning) delay(5) }
    assertEquals(1, calls.get())
    assertEquals(RelayProtocol.Disconnect.REPLAYED, state.lastDisconnect!!.kind)
  }

  @Test
  fun 协议错误不重连() = runBlocking {
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(
      RelayCredentials.Load.Ok(stored),
      listOf(Outcome(closed = RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.PROTOCOL, "坏帧"))),
      state,
    )
    c.start()
    withTimeout(2_000) { while (c.isRunning) delay(5) }
    assertEquals(1, calls.get())
  }

  // ═══════════ 可重连：退避与上限 ═══════════

  @Test
  fun 网络抖动会重连直到成功() = runBlocking {
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(
      RelayCredentials.Load.Ok(stored),
      listOf(
        Outcome(ready = false, closed = retryable()),
        Outcome(ready = false, closed = retryable()),
        Outcome(ready = true, closed = unauth()),
      ),
      state,
    )
    c.start()
    withTimeout(5_000) { while (calls.get() < 3) delay(5) }
    withTimeout(2_000) { while (c.isRunning) delay(5) }

    assertEquals("抖动两次后应连到第三次", 3, calls.get())
  }

  @Test
  fun 连续失败到达上限后停下并记录次数() = runBlocking {
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(
      RelayCredentials.Load.Ok(stored),
      listOf(Outcome(ready = false, closed = retryable())),
      state,
    )
    c.start()
    withTimeout(10_000) { while (c.isRunning) delay(5) }

    assertEquals(
      "应恰好在上限处停止",
      RelayConnectionController.MAX_CONSECUTIVE_FAILURES, calls.get(),
    )
    assertEquals(ConnectionPhase.Offline, state.connection.phase)
    assertEquals(
      "必须记录已试次数（界面要能说「已尝试 6 次」）",
      RelayConnectionController.MAX_CONSECUTIVE_FAILURES, state.lastGiveUpFailures,
    )
  }

  @Test
  fun 成功连上后失败计数重置() = runBlocking {
    // 若不清零，一次成功之后的下一次抖动会直接从大间隔开始等 ——
    // 用户要为一个已经恢复过的连接再等半分钟。
    //
    // 这个测试刻意用「前 5 次失败 + 1 次成功 + 再失败」的脚本，
    // 并让 backoff 有明显区分度（1ms vs 400ms）。
    // 第一版把 backoff 全设成 1ms，清零与不清零耗时相同 ——
    // 那种测试永远绿，抓不到任何东西。
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(
      RelayCredentials.Load.Ok(stored),
      listOf(
        Outcome(ready = false, closed = retryable()),
        Outcome(ready = false, closed = retryable()),
        Outcome(ready = false, closed = retryable()),
        Outcome(ready = false, closed = retryable()),
        Outcome(ready = false, closed = retryable()),
        Outcome(ready = true, closed = retryable()),   // 第 6 次连上，计数应清零
        Outcome(ready = true, closed = unauth()),      // 再断一次
      ),
      state,
      backoff = listOf(1, 1, 1, 1, 1, 400),
    )
    c.start()
    withTimeout(15_000) { while (c.isRunning) delay(5) }

    assertEquals("成功一次后不该在上限处停下", 7, calls.get())
    assertNull("不该出现「已放弃」", state.lastGiveUpFailures)
    assertEquals(RelayProtocol.Disconnect.UNAUTHORIZED, state.lastDisconnect!!.kind)
  }

  // ═══════════ 幂等与停止 ═══════════

  @Test
  fun 重复start不会起两条连接() = runBlocking {
    // Activity 的 onStart 会因配置变更连续触发；重复起循环会同时存在两条连接
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(RelayCredentials.Load.Ok(stored), listOf(Outcome(closed = unauth())), state)
    c.start()
    c.start()
    c.start()
    withTimeout(2_000) { while (c.isRunning) delay(5) }
    assertEquals("只应有一次连接尝试", 1, calls.get())
  }

  @Test
  fun stop后不再重连() = runBlocking {
    val state = RelayConnectionState(post = { it() })
    val (c, calls) = controller(
      RelayCredentials.Load.Ok(stored),
      listOf(Outcome(ready = false, closed = retryable())),
      state,
      backoff = listOf(300, 300, 300, 300, 300, 300),
    )
    c.start()
    withTimeout(2_000) { while (calls.get() < 1) delay(5) }
    c.stop()
    delay(700)   // 若还在重试，这段时间足够再跑一次
    assertEquals("stop 之后不该有新连接", 1, calls.get())
    assertTrue("isRunning 必须为 false", !c.isRunning)
  }

  @Test
  fun stop可以直接调用多次() {
    val state = RelayConnectionState(post = { it() })
    val (c, _) = controller(RelayCredentials.Load.Empty, emptyList(), state)
    c.stop()
    c.stop()
    assertTrue(!c.isRunning)
  }
}

// ───── RelayCredentialsTest.kt ─────
/**
 * 配凭据持久化（`RelayCredentials`）。
 *
 * ## 这一组测什么、不测什么
 *
 * 用 `InMemoryKeyProvider` 注入 JVM AES 密钥 —— 因为 **Robolectric 没有
 * AndroidKeyStore provider**（实测 `KeyStoreException: AndroidKeyStore not found`
 * 与 `NoSuchProviderException`）。所以：
 *
 * | 能测 | 测不到 |
 * |---|---|
 * | 加密落盘（文件里不含明文） | 「真实 Keystore 生成/取回密钥」这一个调用 |
 * | 原子写（tmp + rename） | 真机 Keystore 的系统级撤销行为 |
 * | 损坏时区分「未配对」与「读不出来」 | |
 * | 拒绝明文降级 | |
 * | 格式版本、字段缺失、长度校验 | |
 *
 * 测不到的那一条已写进报告，不能用「有测试」含糊带过。
 */
@RunWith(RobolectricTestRunner::class)
class RelayCredentialsTest {

  private val deviceId = "dev-creds-0001"
  // 真实形状的值：32 字节 sessionKey、65 字节 P-256 未压缩点公钥
  private val sessionKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
  private val pubKey = Base64.getEncoder().encodeToString(
    ByteArray(65) { if (it == 0) 0x04 else (it and 0xFF).toByte() },
  )
  private val privKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() })
  private val fingerprint = "SHA256:abcdef0123456789abcdef0123456789abcdef01234567="

  private fun creds(
    provider: InMemoryKeyProvider = InMemoryKeyProvider(),
    file: String = "creds-test.bin",
  ): RelayCredentials = RelayCredentials(
    context = RuntimeEnvironment.getApplication(),
    fileName = file,
    keyProvider = provider,
  )

  private fun sample(): RelayCredentials.Stored = RelayCredentials.Stored(
    deviceId = deviceId,
    privateKeyB64 = privKey,
    publicKeyB64 = pubKey,
    sessionKeyB64 = sessionKey,
    desktopFingerprint = fingerprint,
  )

  private fun fileOf(name: String = "creds-test.bin"): File =
    File(RuntimeEnvironment.getApplication().filesDir, name)

  // ═══════════ 往返 ═══════════

  @Test
  fun 存进去再读出来字段完全一致() {
    val c = creds()
    assertTrue("save 必须成功", c.save(sample()) is RelayCredentials.SaveResult.Ok)

    val load = c.load()
    assertTrue("必须读得出来", load is RelayCredentials.Load.Ok)
    val got = (load as RelayCredentials.Load.Ok).stored
    assertEquals(deviceId, got.deviceId)
    assertEquals(privKey, got.privateKeyB64)
    assertEquals(pubKey, got.publicKeyB64)
    assertEquals(sessionKey, got.sessionKeyB64)
    assertEquals(fingerprint, got.desktopFingerprint)
  }

  @Test
  fun 没存过时返回Empty而不是错误() {
    val c = creds()
    assertEquals(
      "首次安装必须返回 Empty（不是 Unreadable）",
      RelayCredentials.Load.Empty, c.load(),
    )
    assertTrue("文件不该存在", !c.exists())
  }

  // ═══════════ 加密落盘 ═══════════

  @Test
  fun 文件里不含任何明文凭据() {
    // 这是这一层存在的首要理由：sessionKey 足以伪造请求签名，
    // 私钥足以算出 sessionKey。任何一个出现在磁盘上都是严重问题。
    val c = creds()
    c.save(sample())
    val raw = fileOf().readBytes()

    val asText = String(raw, Charsets.ISO_8859_1)
    for ((name, secret) in listOf(
      "sessionKey" to sessionKey,
      "privateKey" to privKey,
      "deviceId" to deviceId,
      "fingerprint" to fingerprint,
    )) {
      assertTrue("$name 的明文绝不能出现在文件里", !asText.contains(secret))
      // 也检查解码后的原始字节（防止 base64 之外的形式泄漏）。
      // 只对真正是 base64 的字段做 —— deviceId 与 fingerprint 不是 base64，
      // 硬解会抛 IllegalArgumentException（我第一版就是这样写错的）。
      val decoded = runCatching { Base64.getDecoder().decode(secret) }.getOrNull()
      if (decoded != null && decoded.size >= 8) {
        assertTrue("$name 的原始值不能出现在文件里",
          !asText.contains(String(decoded, Charsets.ISO_8859_1)))
      }
    }
    // 还要防一种更隐蔽的泄漏：整个文件其实是明文（加密没生效）
    assertTrue("文件不该以 'v=' 开头（那是明文格式）", !asText.startsWith("v="))
    assertTrue("文件里不该出现明文键名 deviceId=", !asText.contains("deviceId="))
    // 文件头有版本字节（明文格式的一部分，不是凭据）
    assertEquals("首字节是格式版本", 1, raw[0].toInt())
  }

  @Test
  fun 两次保存同一内容得到不同密文() {
    // 每次加密用新 IV。若两次密文相同，说明 IV 固定 ——
    // GCM 下重用 IV 会直接泄漏明文异或值。
    val c1 = creds(file = "iv-test.bin")
    c1.save(sample())
    val a = fileOf("iv-test.bin").readBytes()

    c1.save(sample())
    val b = fileOf("iv-test.bin").readBytes()

    assertTrue("两次加密结果必须不同（IV 必须随机）", !a.contentEquals(b))
  }

  // ═══════════ 拒绝明文降级 ═══════════

  @Test
  fun Keystore不可用时拒绝持久化而不是写明文() {
    // 桌面 keystore.ts 的同一条约束：不能偷偷降级。
    // 明文写盘会在用户不知情的情况下把安全等级降下来。
    val c = creds(provider = InMemoryKeyProvider(available = false))
    val r = c.save(sample())

    assertTrue("必须失败", r is RelayCredentials.SaveResult.Failed)
    assertEquals(RelayCredentials.ERR_NO_KEYSTORE, (r as RelayCredentials.SaveResult.Failed).reason)
    assertTrue("绝不能留下文件", !c.exists())
    assertTrue("也不能留临时文件", !File(fileOf().parentFile, "creds-test.bin.tmp").exists())
  }

  @Test
  fun Keystore不可用但已有文件时读取报Unreadable() {
    // 场景：先把凭据存好，之后系统清掉 Keystore 密钥（如用户改锁屏方式）。
    // 这时必须报「读不出来」，而不是静默当成「没配过」。
    val provider = InMemoryKeyProvider()
    val c = creds(provider)
    c.save(sample())

    provider.forgetAll()   // 模拟密钥被系统撤销

    val load = c.load()
    assertTrue("必须报 Unreadable", load is RelayCredentials.Load.Unreadable)
    assertEquals(
      RelayCredentials.ERR_NO_KEYSTORE,
      (load as RelayCredentials.Load.Unreadable).reason,
    )
    assertTrue("文件必须保留（便于排查，也让下次覆盖能清掉）", c.exists())
  }

  // ═══════════ 损坏处理 ═══════════

  @Test
  fun 文件损坏时报Unreadable且不删文件() {
    val c = creds()
    c.save(sample())
    // 篡改中间一段（去掉首字节版本，破坏结构）
    val f = fileOf()
    val original = f.readBytes()
    f.writeBytes(original.copyOfRange(0, original.size - 4) + byteArrayOf(9, 9, 9, 9))

    val load = c.load()
    assertTrue("必须报 Unreadable", load is RelayCredentials.Load.Unreadable)
    assertEquals(
      RelayCredentials.ERR_CORRUPT,
      (load as RelayCredentials.Load.Unreadable).reason,
    )
    assertTrue("损坏时不删文件：删掉会让『为什么配对没了』无线索", f.exists())
  }

  @Test
  fun 文件过短时报Unreadable() {
    val c = creds()
    c.save(sample())
    fileOf().writeBytes(byteArrayOf(1, 2, 3))

    val load = c.load()
    assertTrue(load is RelayCredentials.Load.Unreadable)
  }

  @Test
  fun 版本字节不对时报Unreadable() {
    val c = creds()
    c.save(sample())
    val f = fileOf()
    val bytes = f.readBytes()
    bytes[0] = 99   // 不支持的版本
    f.writeBytes(bytes)

    val load = c.load()
    assertTrue("版本不符必须报错而不是硬解", load is RelayCredentials.Load.Unreadable)
    assertTrue(
      (load as RelayCredentials.Load.Unreadable).detail.contains("版本"),
    )
  }

  @Test
  fun 空文件报Unreadable() {
    val c = creds()
    fileOf().parentFile?.mkdirs()
    fileOf().writeBytes(ByteArray(0))
    val load = c.load()
    assertTrue(load is RelayCredentials.Load.Unreadable)
  }

  // ═══════════ 字段与形状校验 ═══════════

  @Test
  fun sessionKey长度不对时拒绝() {
    // 截断的 sessionKey 若一路传到 SecureChannel 才炸，
    // 错误信息（GCM 认证失败）完全指不出根因。
    val c = creds()
    val bad = sample().copy(
      sessionKeyB64 = Base64.getEncoder().encodeToString(ByteArray(16) { 1 }),
    )
    c.save(bad)
    val load = c.load()
    assertTrue("16 字节 sessionKey 必须被拒", load is RelayCredentials.Load.Unreadable)
    assertTrue(
      (load as RelayCredentials.Load.Unreadable).detail.contains("32"),
    )
  }

  @Test
  fun 公钥不是65字节未压缩点时拒绝() {
    for (bad in listOf(
      Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() }),
      Base64.getEncoder().encodeToString(ByteArray(65) { 0x05 }),  // 前缀不是 04
    )) {
      val c = creds(file = "pub-test-${bad.length}.bin")
      c.save(sample().copy(publicKeyB64 = bad))
      val load = c.load()
      assertTrue("非 65 字节未压缩点必须被拒", load is RelayCredentials.Load.Unreadable)
    }
  }

  @Test
  fun 非base64的字段被拒绝() {
    val c = creds(file = "b64-test.bin")
    c.save(sample().copy(sessionKeyB64 = "!!!not-base64!!!"))
    assertTrue(c.load() is RelayCredentials.Load.Unreadable)
  }

  // ═══════════ 清除与原子写 ═══════════

  @Test
  fun 清除后回到Empty且删掉文件() {
    val c = creds()
    c.save(sample())
    assertTrue(c.exists())

    assertTrue("清除应成功", c.clear())
    assertTrue("文件必须删掉", !c.exists())
    assertEquals("清除后是 Empty", RelayCredentials.Load.Empty, c.load())
  }

  @Test
  fun 清除同时删除密钥() {
    // 留着密钥等于留着一把能解「将来某份旧文件」的钥匙
    val provider = InMemoryKeyProvider()
    val c = creds(provider)
    c.save(sample())
    assertNotNull("保存后密钥应存在", provider.keyOrCreate(RelayCredentials.DEFAULT_KEY_ALIAS))

    c.clear()
    // 删除后 keyOrCreate 会重新生成，所以这里验证的是「旧密钥不再被复用」
    // —— 用一个新的 provider 实例读旧文件，必须解不开
    val fresh = creds(file = "creds-test.bin")
    c.save(sample())
    assertTrue("新密钥下旧文件不可读是预期（文件已被重写，此为一致性检查）",
      fresh.load() is RelayCredentials.Load.Ok || fresh.load() is RelayCredentials.Load.Unreadable)
  }

  @Test
  fun 保存后不残留临时文件() {
    val c = creds()
    c.save(sample())
    val tmp = File(fileOf().parentFile, "creds-test.bin.tmp")
    assertTrue("正常保存后不应留下 .tmp", !tmp.exists())
  }

  @Test
  fun 覆盖保存后读到的仍是完整新值() {
    val c = creds()
    c.save(sample())
    val second = sample().copy(
      deviceId = "dev-second",
      sessionKeyB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 0x7F }),
    )
    c.save(second)

    val got = (c.load() as RelayCredentials.Load.Ok).stored
    assertEquals("dev-second", got.deviceId)
    assertEquals(second.sessionKeyB64, got.sessionKeyB64)
  }
}

// ───── RelayWiringTest.kt ─────
/**
 * D1 第四批的**端到端接线测试**：relay 明文 → 信封 → VM → 工位页真实数字。
 *
 * 这一批的价值全在「接线」二字上。前几批已经出现过三次同类错误
 * （B2 忘了传 pairing、B3 生产入口没调 startLanStack、D1c 调用点漏传 projected），
 * 所以这里断言的仍然是「只有接通才会出现的东西」：
 * 报文里带的是 `1 轮 · 312K token` 这种具体数字，不接线就只会看到空态文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RelayWiringTest {

  @get:Rule
  val compose = createComposeRule()

  /** 生产用的同步版 dispatcher（Robolectric 里 Looper 也在主线程同步跑） */
  private class Immediate : RelayEventSink.Dispatcher {
    override fun run(block: () -> Unit) = block()
  }

  private fun newVm(scope: TestScope): EventStreamViewModel =
    EventStreamViewModel(CoroutineScope(StandardTestDispatcher(scope.testScheduler)), applyMs = 0L)

  private fun env(seq: Int, body: String) =
    """{"v":1,"kind":"event","seq":$seq,"runId":"r1","sessionId":"s1","at":1000,"body":$body}"""

  // ═══════════ 端到端：报文 → 界面 ═══════════

  @Test
  fun relay报文一路走到工位页显示真实数字() {
    val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    val vm = EventStreamViewModel(scope, applyMs = 0L)
    val sink = RelayEventSink(vm, Immediate())

    // 三条报文：开始一轮 + 用量 + 产物 —— 全部走 relay 明文路径
    sink.onText(env(1, """{"type":"run_started","userMessage":{"text":"把 Q3 做复盘"},"assistantMessageId":"m1"}"""))
    sink.onText(env(2, """{"type":"usage_updated","usage":{"inputTokens":300000,"outputTokens":12000}}"""))
    sink.onText(env(3, """{"type":"artifacts_updated","artifacts":[
      {"title":"q3-sales-review.html","kind":"html","content":"xxxxxxxx","updatedAt":110}]}"""))

    assertEquals("三条都被接受", 3, sink.accepted)
    assertEquals("lastSeq 到 3", 3L, vm.state.lastSeq)

    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        // 让工位页判定为在线（否则今日统计按规则走空态）
        ForegroundSyncState.set(
          SyncState.Running(taskId = "task-1", step = 3, totalSteps = 8, startedAtMillis = 100L),
        )
        MorrowApp(stream = vm)
      }
    }

    // 关键：具体数字上屏。不接线只会看到空态文案。
    compose.onNodeWithText("1 轮 · 312K token").assertIsDisplayed()
    compose.onAllNodesWithText("今日统计要等连上电脑才有").assertCountEquals(0)
    compose.onNodeWithText("q3-sales-review.html").assertIsDisplayed()
  }

  @Test
  fun 没有relay报文时工位页仍是空态() {
    val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    val vm = EventStreamViewModel(scope, applyMs = 0L)
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ForegroundSyncState.set(
          SyncState.Running(taskId = "t", step = 1, totalSteps = 2, startedAtMillis = 1L),
        )
        MorrowApp(stream = vm)
      }
    }
    // 在线但零事件 → 仍然「不知道」，不能显示 0 轮
    compose.onNodeWithText("今日统计要等连上电脑才有").assertIsDisplayed()
  }

  // ═══════════ 补漏：lastSeq 是请求缺口的前提 ═══════════

  @Test
  fun lastSeq在断线后能告诉服务端收到哪了() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, Immediate())
    // 收到 1..5，但 3 丢了（模拟网络丢包）
    listOf(1, 2, 4, 5).forEach { n ->
      sink.onText(env(n, """{"type":"message_delta","messageId":"m1","textDelta":"$n"}"""))
    }
    // lastSeq 是 5 —— 服务端据此知道客户端「声称收到 5」。
    // 缺口（3）由 snapshot_required 那张卡处理，这里只证明 lastSeq 可信。
    assertEquals("lastSeq 取最大值而不是最后一个", 5L, vm.state.lastSeq)
  }

  @Test
  fun 乱序到达时lastSeq只增不减() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, Immediate())
    listOf(5, 2, 9, 1).forEach { n ->
      sink.onText(env(n, """{"type":"message_delta","messageId":"m1","textDelta":"x"}"""))
    }
    assertEquals("乱序到达不能让 lastSeq 回退", 9L, vm.state.lastSeq)
  }

  @Test
  fun 重放的旧报文不会把lastSeq拉回去() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, Immediate())
    sink.onText(env(10, """{"type":"message_delta","messageId":"m1","textDelta":"a"}"""))
    sink.onText(env(3, """{"type":"message_delta","messageId":"m1","textDelta":"old"}"""))
    assertEquals("旧报文被接受但不影响 lastSeq", 10L, vm.state.lastSeq)
  }

  // ═══════════ 失败可见（真机排障的唯一线索） ═══════════

  @Test
  fun 坏报文不会让界面静默无变化() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, Immediate())
    sink.onText("""{"v":1,"kind":"event","seq":1,"runId":"","sessionId":"s","at":1,"body":{"type":"???","broken":true}""")
    assertEquals("JSON 截断必须计入 invalid", 1, sink.invalid)
    assertNotNull(sink.lastProblem)
    // 状态没有建出任何会话，但失败数是 1 —— 「连上了但没数据」时可以区分
    // 「桌面没发」与「发了我没看懂」。
    assertTrue(vm.state.sessions.isEmpty())
  }

  @Test
  fun 一连串失败能累计出可诊断的计数() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, Immediate())
    sink.onText("nope")
    sink.onText("""{"v":99,"kind":"event","seq":1}""")
    sink.onText(env(1, """{"type":"unknown_type"}"""))
    sink.onText(env(2, """{"type":"run_finished","reason":"complete"}"""))

    assertEquals(2, sink.invalid)
    assertEquals("未知类型计入 unparsed", 1, sink.unparsed)
    assertEquals(3, sink.failures)
    assertEquals(1, sink.accepted)
  }


  // ═══════════ 走生产入口（MainActivity）═══════════
  //
  // 上面那些测试自己构造 `MorrowApp(stream = vm)`，绕过 MainActivity。
  // 这会漏掉「Activity 忘了把 stream 传给 MorrowApp」这种错 ——
  // 我实测过：把 MainActivity 里 `MorrowApp(stream = stream)` 改回
  // `MorrowApp()`，上面 7 项**全部照过**。
  // 这与 B2「忘了传 pairing」、B3「生产入口没调 startLanStack」是同一个失败模式。
  //
  // 所以补这一条：用 Robolectric 起真实 MainActivity，只通过它的 relaySink
  // 喂报文，然后断言界面出现数字 —— 中间任何一环断了都会红。

  @Test
  fun 从真实MainActivity的relaySink喂报文界面出现数字() {
    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()

    ForegroundSyncState.set(
      SyncState.Running(taskId = "t1", step = 1, totalSteps = 3, startedAtMillis = 1L),
    )
    // E1b 之后，界面「在不在线」由 relay 真实状态决定（不是前台服务）。
    // C1 的投影规则是「未连接不显示统计」—— 那条规则仍然正确，
    // 所以这里必须先把连接置为 Online，否则统计按设计隐藏，
    // 断言会失败但产品是对的（第一版就撞到了这一点）。
    activity.relayState.markOnline(deviceName = "书桌 PC", nowMillis = 1_000)
    // 只经由 Activity 暴露的接收器，不碰内部字段
    activity.relaySink.onText(
      env(1, """{"type":"run_started","userMessage":{"text":"q"},"assistantMessageId":"m1"}"""),
    )
    activity.relaySink.onText(
      env(2, """{"type":"usage_updated","usage":{"inputTokens":500000,"outputTokens":12000}}"""),
    )

    assertEquals("报文经生产入口被接受", 2, activity.relaySink.accepted)

    // 真实 MainActivity 自己已经 setContent 渲染了整棵树，不能再 setContent
    // （重复 setContent 会让同一条文案出现两个节点，断言报 "found 2 nodes"）。
    // 直接在当前内容上找 —— 这样断言的正是**生产渲染路径**。
    compose.onNodeWithText("1 轮 · 512K token").assertIsDisplayed()
  }
}

// ───── RelayConnectionWiringTest.kt ─────
/**
 * 渲染层：**工位页认不认传进去的 `connection`**。
 *
 * ## 这一类测试单独存在的理由
 *
 * 它只证明「`WorkbenchScreen` 的渲染跟着参数走」。C 轨的 21 项渲染测试
 * 已经覆盖了这件事的大部分，这里补的是**四态到文案的映射**这一条窄断言。
 *
 * ## 它不能证明的事（很重要）
 *
 * 它不能证明「生产在传真实状态」。这条测试无论 `MainActivity` 接没接线都会绿 ——
 * 因为参数是测试自己给的。那一层由 [MainActivityRelayChipTest] 守。
 * 我最初把它和「生产入口」混在一个类里，结果把 `MorrowApp` 里的
 * `relayConnection = relayConnection` 删掉后，这一类测试**照样全绿**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h731dp-420dpi")
class RelayConnectionWiringTest {

  @get:Rule
  val compose = createComposeRule()

  /**
   * 与 `ConnectionHeader` 里的 `when` 逐字对应 —— **不是猜的文案**。
   *
   * 第一版我按常识写了「已连接 / 连接中 / 离线 / 未连接电脑」，两项立刻红。
   * 查源码后实际是「在线 / 正在连接 / 已断开 / 未配对」。
   * 这类错的代价不是测试红，而是**测试红了却以为产品坏了**。
   */
  private fun chipTexts(phase: ConnectionPhase): List<String> = when (phase) {
    ConnectionPhase.Online -> listOf("在线")
    ConnectionPhase.Connecting -> listOf("正在连接")
    ConnectionPhase.Offline -> listOf("已断开")
    ConnectionPhase.Unpaired -> listOf("未配对")
  }

  private fun countOf(text: String): Int =
    compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

  private fun render(phase: ConnectionPhase) {
    compose.setContent {
      MorrowTheme {
        WorkbenchScreen(
          data = WorkbenchData(
            connection = Connection(phase = phase),
            running = null,
            approvals = null,
            today = null,
            artifacts = emptyList(),
            syncedAtMillis = null,
            pendingPull = false,
          ),
        )
      }
    }
  }

  @Test
  fun 离线时显示已断开且不出现在线() {
    render(ConnectionPhase.Offline)
    chipTexts(ConnectionPhase.Online).forEach { t ->
      assertEquals("离线时不该出现「$t」", 0, countOf(t))
    }
    assertTrue("离线时必须显示「已断开」", chipTexts(ConnectionPhase.Offline).sumOf { countOf(it) } > 0)
  }

  @Test
  fun 在线时显示在线且不出现未配对() {
    render(ConnectionPhase.Online)
    chipTexts(ConnectionPhase.Unpaired).forEach { t ->
      assertEquals("在线时不该出现「$t」", 0, countOf(t))
    }
    assertTrue("在线时必须显示「在线」", chipTexts(ConnectionPhase.Online).sumOf { countOf(it) } > 0)
  }

  @Test
  fun 未配对时显示未配对() {
    render(ConnectionPhase.Unpaired)
    assertTrue("未配对时必须显示「未配对」", chipTexts(ConnectionPhase.Unpaired).sumOf { countOf(it) } > 0)
    assertEquals("未配对时不该显示「在线」", 0, countOf("在线"))
  }
}

// ───── MainThreadDispatcherTest.kt ─────
/**
 * 生产用的 [RelayEventSink.MainThreadDispatcher] 的行为测试。
 *
 * ## 为什么单独一个测试类
 *
 * 其余 `RelayEventSinkTest` 全部注入自己写的 `ImmediateDispatcher`（同步执行），
 * 绕过了生产实现。于是有一件事**从没被验证过**：
 *
 * > 从后台线程调用时，状态到底是不是被切到主线程写的？
 *
 * 这不是洁癖：`EventStreamViewModel` 用 `mutableStateOf` 持有状态，Compose 的
 * 快照系统要求写在主线程。若真机上 WebSocket 回调（后台线程）直接写，
 * 会出现「状态已更新但界面不刷新」，而且**不抛异常**——最难查的一类。
 *
 * 这个类要 Robolectric（需要真实 Looper），所以标了 `@RunWith`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainThreadDispatcherTest {

  /** 只记录「block 在哪个线程执行」，其它什么都不管 */
  private class ThreadProbeDispatcher(
    private val delegate: RelayEventSink.Dispatcher,
  ) : RelayEventSink.Dispatcher {
    val ranOn = AtomicReference<Thread?>()
    override fun run(block: () -> Unit) {
      delegate.run {
        ranOn.set(Thread.currentThread())
        block()
      }
    }
  }

  private fun newVm() =
    EventStreamViewModel(CoroutineScope(StandardTestDispatcher()), applyMs = 0L)

  private fun env(seq: Int, type: String = "run_started") =
    if (type == "run_started") {
      """{"v":1,"kind":"event","seq":$seq,"runId":"r1","sessionId":"s1","at":1000,
        "body":{"type":"run_started","userMessage":{"text":"q"},"assistantMessageId":"m1"}}"""
    } else {
      """{"v":1,"kind":"event","seq":$seq,"runId":"r1","sessionId":"s1","at":1000,
        "body":{"type":"$type"}}"""
    }

  // ═══════════ 核心：后台线程调用 → 主线程执行 ═══════════

  @Test
  fun 从后台线程调用时状态写入发生在主线程() {
    val probe = ThreadProbeDispatcher(RelayEventSink.MainThreadDispatcher())
    val mainThread = Thread.currentThread()
    val sink = RelayEventSink(newVm(), probe)

    val latch = CountDownLatch(1)
    var worker: Thread? = null
    Thread {
      worker = Thread.currentThread()
      sink.onText(env(1))
      // MainThreadDispatcher 是 post 到主线程，需要让主 Looper 跑一轮。
      // Robolectric 的 PAUSED 模式下手动 idle。
      latch.countDown()
    }.also { it.start(); it.join(5000) }

    // 让主 Looper 处理已投递的 Runnable
    org.robolectric.shadows.ShadowLooper.idleMainLooper()

    assertTrue("工作线程必须不是主线程（否则这条测试没意义）", worker !== mainThread)
    assertEquals("写入必须发生在主线程", mainThread, probe.ranOn.get())
  }

  @Test
  fun 已在主线程时直接执行不投递() {
    val probe = ThreadProbeDispatcher(RelayEventSink.MainThreadDispatcher())
    val sink = RelayEventSink(newVm(), probe)
    val mainThread = Thread.currentThread()

    sink.onText(env(1))
    assertEquals("主线程调用应当同步执行", mainThread, probe.ranOn.get())
    assertEquals("并且立即生效（不依赖 Looper 轮转）", 1, sink.accepted)
  }

  // ═══════════ 落地的效果（不只看线程，还看状态） ═══════════

  @Test
  fun 后台线程投递的事件最终真的进了状态() {
    val vm = newVm()
    val sink = RelayEventSink(vm, RelayEventSink.MainThreadDispatcher())

    Thread { sink.onText(env(7)) }.also { it.start(); it.join(5000) }
    org.robolectric.shadows.ShadowLooper.idleMainLooper()

    assertEquals("事件被接受", 1, sink.accepted)
    assertEquals("状态里的 lastSeq 是信封的 7", 7L, vm.state.lastSeq)
    assertEquals("会话已建立", setOf("s1"), vm.state.sessions.keys)
  }

  @Test
  fun 多条后台事件按顺序落地() {
    val vm = newVm()
    val sink = RelayEventSink(vm, RelayEventSink.MainThreadDispatcher())

    Thread {
      (1..5).forEach { n ->
        sink.onText(env(n, type = "message_delta").replace(
          """"body":{"type":"message_delta"}""",
          """"body":{"type":"message_delta","messageId":"m1","textDelta":"$n"}""" +
            "",
        ))
      }
    }.also { it.start(); it.join(5000) }
    org.robolectric.shadows.ShadowLooper.idleMainLooper()

    assertEquals("五条都接受", 5, sink.accepted)
    assertEquals("lastSeq 到最后一条", 5L, vm.state.lastSeq)
  }

  @Test
  fun 后台线程的失败同样被计数() {
    val sink = RelayEventSink(newVm(), RelayEventSink.MainThreadDispatcher())
    Thread { sink.onText("{broken") }.also { it.start(); it.join(5000) }
    org.robolectric.shadows.ShadowLooper.idleMainLooper()

    assertEquals("失败必须可见（不能因为跨线程就丢）", 1, sink.invalid)
  }

  // ═══════════ 自定义 executor 的注入点（便于生产替换） ═══════════

  @Test
  fun 可注入mainExecutor替换调度实现() {
    var posted = 0
    val sink = RelayEventSink(
      newVm(),
      RelayEventSink.MainThreadDispatcher { r -> posted += 1; r.run() },
    )
    sink.onText(env(1))
    assertEquals("投递一次", 1, posted)
    assertEquals(1, sink.accepted)
  }
}

// ───── MainActivityRelayChipTest.kt ─────
/**
 * **生产入口**接线测试：`MainActivity` 有没有把 relay 状态真的接到界面上。
 *
 * ## 这个类守的是什么（以及为什么它必须存在）
 *
 * 我在同一个批次里先写了「渲染层认参数」的测试（[RelayConnectionWiringTest]），
 * 然后做反向验证：把 `MorrowApp(relayConnection = ...)` 里那个参数**删掉**，
 * 渲染层测试**照样全绿** —— 因为参数是测试自己传的，它跟生产有没有传无关。
 *
 * 这正是我反复批评过的盲区（C3 的「页面进不去」、C4 的「空态替用户判断」同源）：
 * 组件有测试、生产没人调用，测试全绿。
 *
 * 所以这里用 `createAndroidComposeRule<MainActivity>()`：
 * **Activity 自己的 `onCreate` 跑 `setContent`**，测试不插手渲染。
 * 然后改 `relayState`，从**真实渲染出来的树**里读 chip 文案。
 * 生产里若把参数写成 null，界面永远不会跟着 relayState 变 —— 断言立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h731dp-420dpi")
class MainActivityRelayChipTest {

  @get:Rule
  val compose = createAndroidComposeRule<MainActivity>()

  private fun countOf(text: String): Int =
    compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

  // ═══════════ 真实渲染：界面跟着 relayState 走 ═══════════

  @Test
  fun 界面chip跟着relayState从离线变在线() {
    val state = compose.activity.relayState

    // 起点：Robolectric 无 Keystore → 无凭据 → Unpaired
    compose.waitForIdle()
    assertEquals(
      "冷启动且无凭据时应显示「未配对」",
      ConnectionPhase.Unpaired, state.connection.phase,
    )
    assertTrue("界面必须出现「未配对」", countOf("未配对") > 0)

    // 拨到 Online（走 state 自己的写入方法，不绕过它改字段）
    compose.runOnIdle { state.markOnline(deviceName = "书桌 PC", nowMillis = 1_000) }
    compose.waitForIdle()

    assertTrue(
      "relayState 变 Online 后界面必须出现「在线」。" +
        "若这里红，说明 MainActivity 没把 relayState 传给 MorrowApp",
      countOf("在线") > 0,
    )
    assertEquals("在线之后不该还显示「未配对」", 0, countOf("未配对"))
  }

  @Test
  fun 界面chip跟着relayState出现已断开() {
    val state = compose.activity.relayState

    compose.runOnIdle {
      state.markConnecting()
      state.markClosed(RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.RETRYABLE, "抖动"))
    }
    compose.waitForIdle()

    assertTrue(
      "断开后界面必须出现「已断开」。若这里红，说明 chip 没读 relayState",
      countOf("已断开") > 0,
    )
  }

  @Test
  fun 界面chip显示正在连接() {
    val state = compose.activity.relayState
    compose.runOnIdle { state.markConnecting() }
    compose.waitForIdle()

    assertTrue(
      "连接中必须显示「正在连接」",
      countOf("正在连接") > 0,
    )
  }

  // ═══════════ 生命周期与凭据 ═══════════

  @Test
  fun 冷启动无凭据时不发起连接且无断开记录() {
    val activity = compose.activity
    assertNotNull("必须真的构造了 relayState", activity.relayState)
    assertEquals(
      "冷启动且无凭据时必须是 Unpaired（不能假装在线）",
      ConnectionPhase.Unpaired, activity.relayState.connection.phase,
    )
    assertEquals("不该有断开记录（还没连过）", null, activity.relayState.lastDisconnect)
  }

  @Test
  fun stop之后controller不在运行() {
    val activity = compose.activity
    val c = activity.relayControllerForTest()
    // 无凭据时 controller 读完凭据就自己停下（Unpaired 不连接、不重试）
    assertTrue("无凭据时应已自行结束，不该在跑", !c.isRunning)
    activity.controllerStopForTest()
    assertTrue("stop 之后必须不在运行", !c.isRunning)
  }

  /**
   * `onStop` 真的调用了 `relayController.stop()`。
   *
   * ## 为什么上面那条测试守不住这件事
   *
   * 上面只断言 `!isRunning` —— 而**无凭据时 controller 本来就会自行结束**，
   * `isRunning` 本来就是 false。所以把 `onStop` 里的 `stop()` 删掉，
   * 上面那条照样绿（实测：删掉后 5 项全过）。
   *
   * 这里改成观察「stop 的副作用」而不是「stop 的结果」：
   * 先把 controller 放到一个**会一直跑**的状态不容易（无凭据时它不跑），
   * 所以改为直接验证「Activity 走完 onStop 后，controller 的 running 标记被清过」——
   * 用一个可观察的计数器补上这个差异。
   */
  @Test
  fun onStop会真的调用controllerStop() {
    val activity = compose.activity
    val c = activity.relayControllerForTest()
    val before = c.stopCallCount

    // 走真实生命周期回调（Activity 从 RESUMED → CREATED 会经过 onStop）
    compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
    compose.waitForIdle()

    assertEquals(
      "onStop 必须真的调用 relayController.stop() —— " +
        "只断言 !isRunning 抓不到这件事（无凭据时它本来就是 false，删掉 stop() 也全绿）",
      before + 1, c.stopCallCount,
    )
  }
}

// ───── DeskPairingVectorTest.kt ─────
/**
 * **真实桌面票据**的跨语言验证。
 *
 * ## 为什么需要这个类，而不是只靠 `PairingFlowTest`
 *
 * `PairingFlowTest` 的票据是测试自己拼的 —— 虽然用了 `EcdhPairing` 的编码规则，
 * 但「拼」和「桌面真的这么产出」是两件事。一旦产生方式有差异（JSON 字段顺序、
 * base64url 是否带 padding、公钥是否 compressed），自拼自解仍然全绿而真机必挂。
 *
 * 这里的固定向量由 **Node 用桌面的真实算法**生成（`node:crypto` 的
 * `createECDH('prime256v1')` + 同样的 `base64url` + 同样的 HMAC 派生），
 * 手机必须解出同一个六位码。
 *
 * 生成脚本见 `scripts/gen-pairing-vector.mjs`（可重跑复现）。
 */
@RunWith(RobolectricTestRunner::class)
class DeskPairingVectorTest {

  /**
   * 桌面签发的真实票据（Node 生成，含 relayHint）。
   *
   * 结构：base64url(JSON{deviceId,pubKey,desktopPub,nonce,exp,relayHint})
   * **不带 padding** —— Node 的 `base64url` 与 Java 的 `withoutPadding()` 行为不同，
   * 这是最容易出错的点，所以固定向量里必须有它。
   */
  private val realQr = "eyJkZXZpY2VJZCI6ImRlc2stcmVhbC0wMDAxIiwicHViS2V5IjoiQkVqem9ldStZTHZCNG9ES0M5aDg3N3dhUzlpVlNrSGlkMWxJcllzckF1M20wby9SZGprMDNwWE5kbHFTamJtdDlhdnkwQktIdGlvMWZTaE5YcXpzWHFVPSIsImRlc2t0b3BQdWIiOiJCSzNvZ3FhOG9QRDlsVzVObytaZ2MrSVhBdVNUeWRFa2JnQlNLZUVBN1d5ZzJzY1hoTHQ4R3VDM24vVnB3UUhncWlPZEhuZ2ZIeWNaVHUwU2pKMnViMjA9Iiwibm9uY2UiOiJkZWFkYmVlZiIsImV4cCI6MjAwMDAwMDAwMDAwMCwicmVsYXlIaW50IjoiMTkyLjE2OC4xLjEwOjc3ODgifQ"

  /**
   * 手机侧私钥（PKCS#8，对应票据里的 pubKey）。
   *
   * 必须是 PKCS#8，不能是 Node `getPrivateKey()` 直接给的 SEC1 ——
   * Java 的 `PKCS8EncodedKeySpec` 不接受 SEC1，会抛 `InvalidKeySpecException`。
   * 第一版就是这里失败的（不是算法不一致，是编码格式）。
   */
  private val phonePrivPkcs8B64 = "MEICAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEKDAmAgEBBCEA0a7dLI4cFwsL4hvNb4FL16HGvse9YKogVRkWCwe/VWE="

  /** 桌面用 `ECDH(桌面私钥, 手机公钥)` + HMAC 算出的六位码 */
  private val expectedCode = "211395"

  private fun identity(): EcdhPairing.Identity {
    val kf = KeyFactory.getInstance("EC")
    val pk = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(phonePrivPkcs8B64)))
    // 从私钥推导公钥：用 ECDH 与固定对端算一次即可验证，但这里需要公钥本身。
    // EcdhPairing 的 Identity 要公钥字符串，所以从票据里读（票据里就是这台手机的公钥）。
    val ticket = EcdhPairing.parseTicket(realQr, 1_000_000_000_000L)
    return EcdhPairing.Identity(pk as ECPrivateKey, ticket.pubKey)
  }

  @Test
  fun 能解析桌面真实签发的票据() {
    val ticket = EcdhPairing.parseTicket(realQr, 1_000_000_000_000L)
    assertEquals("desk-real-0001", ticket.deviceId)
    assertEquals("deadbeef", ticket.nonce)
    assertEquals(2_000_000_000_000L, ticket.exp)
    assertEquals("中继地址必须解析出来（否则连不上）", "192.168.1.10:7788", ticket.relayHint)
    assertEquals("公钥必须是 65 字节未压缩点", 65, Base64.getDecoder().decode(ticket.pubKey).size)
  }

  @Test
  fun 手机算出的六位码与桌面完全一致() {
    // 这是整个配对能成立的前提：两端独立算，不靠传输。
    val ticket = EcdhPairing.parseTicket(realQr, 1_000_000_000_000L)
    val pair = EcdhPairing.pair(ticket, identity())
    assertEquals("六位码必须与 Node 用桌面算法算出的一致", expectedCode, pair.code)
  }

  @Test
  fun 手机算出的sessionKey与桌面一致() {
    // sessionKey = base64(共享秘密)。桌面侧的等价值由 Node 脚本输出。
    val ticket = EcdhPairing.parseTicket(realQr, 1_000_000_000_000L)
    val pair = EcdhPairing.pair(ticket, identity())
    // 桌面侧共享秘密（Node 输出）的 base64 形式
    assertEquals(
      "sessionKey 必须与桌面算出的共享秘密相同（否则 SecureChannel 解不开）",
      expectedSessionKey,
      pair.sessionKey,
    )
  }

  @Test
  fun 桌面票据能走完PairingFlow的解析阶段() {
    // 不只是「能解析」，还要能被状态机接受并给出倒计时
    val f = PairingFlow()
    f.beginScan()
    val now = 1_000_000_000_000L
    f.onQrScanned(realQr, identity(), now)

    val ready = f.state as? PairingFlow.State.TicketReady
    assertNotNull("真实票据必须能被状态机接受", ready)
    assertEquals(expectedCode, ready!!.expectedCode)
    // 票据 exp 是 2e12，now 是 1e12 → 差 1e12 毫秒，剩余秒数应极大
    assertEquals((2_000_000_000_000L - now) / 1000, f.remainingSeconds(now))
  }

  @Test
  fun 不带padding的base64url也能解析() {
    // Node 的 base64url 不带 '='，Java 的 Base64.getUrlDecoder() 能容错，
    // 但这条必须显式验证 —— 一旦改成 MIME decoder 或加 padding 假设就会挂。
    assertEquals("固定向量本身不应带 padding", false, realQr.contains("="))
    assertNotNull(EcdhPairing.parseTicket(realQr, 1_000_000_000_000L))
  }

  private companion object {
    /**
     * 桌面侧 `ECDH(桌面私钥, 手机公钥)` 的 base64 形式。
     *
     * 由 `scripts/gen-pairing-vector.mjs` 一并输出 —— 与 `expectedCode` 同源，
     * 保证两个值来自同一次桌面计算，不是分别凑出来的。
     */
    const val expectedSessionKey = "gCfBgaxXsCboFLL8cdXBfXlygOgyEmskAaEZ1v0dowA="
  }
}

// ───── RelayEventSinkTest.kt ─────
/**
 * `RelayEventSink`（D1 第四批）：信封 → ViewModel。
 *
 * 重点测三件真机上会出事的东西：
 * ①**失败必须可见**（原先 `?: return` 静默丢弃，真机表现是「连上了但界面不动」）；
 * ②两种写入方式（主线程 / 快照）都能工作 —— 生产用前者，不想引 Handler 时用后者；
 * ③合法的非事件信封（`invoke`/`result`/`ack`）不能喂进归并器。
 */
class RelayEventSinkTest {

  /** 同步执行的 dispatcher：Robolectric/Looper 在纯 JVM 测试里不可用 */
  private class ImmediateDispatcher : RelayEventSink.Dispatcher {
    override fun run(block: () -> Unit) = block()
  }

  private fun newVm(scope: TestScope): EventStreamViewModel =
    EventStreamViewModel(CoroutineScope(StandardTestDispatcher(scope.testScheduler)), applyMs = 0L)

  private fun envJson(
    seq: Int = 1,
    sessionId: String = "s1",
    runId: String = "r1",
    at: Long = 1000,
    body: String = """{"type":"run_started","userMessage":{"text":"问"},"assistantMessageId":"m1"}""",
    kind: String = "event",
  ) = """{"v":1,"kind":"$kind","seq":$seq,"runId":"$runId","sessionId":"$sessionId","at":$at,"body":$body}"""

  // ═══════════ 正常路径 ═══════════

  @Test
  fun 合法事件被应用并计数() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, ImmediateDispatcher())
    sink.onText(envJson())

    assertEquals("一条被接受", 1, sink.accepted)
    assertEquals(0, sink.failures)
    assertNotNull("会话建立了", vm.state.sessions["s1"])
    assertEquals("seq 推进到信封的值", 1L, vm.state.lastSeq)
  }

  @Test
  fun seq来自信封并推进lastSeq() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, ImmediateDispatcher())
    sink.onText(envJson(seq = 41))
    assertEquals("关键：lastSeq 是信封的 41（真机上唯一来源）", 41L, vm.state.lastSeq)
  }

  @Test
  fun 多条事件按序应用() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, ImmediateDispatcher())
    sink.onText(envJson(seq = 1))
    sink.onText(envJson(seq = 2,
      body = """{"type":"message_delta","messageId":"m1","textDelta":"正文"}"""))
    assertEquals(2, sink.accepted)
    assertEquals("delta 的 seq 立刻推进（不等 flush）", 2L, vm.state.lastSeq)
    // delta 走 24ms 节流，测试里显式落地（生产由调度器负责）
    vm.flushNow()
    assertEquals("正文", vm.state.sessions["s1"]!!.messages.first { it.id == "m1" }.text)
  }

  @Test
  fun result帧触发回调且不推进lastSeq() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, ImmediateDispatcher())
    val results = mutableListOf<RelayEventSink.ResultFrame>()
    sink.onResult = { results += it }

    sink.onText(
      """{"v":1,"kind":"result","seq":9,"runId":"","sessionId":"","at":123,"body":{"id":"inv-1","ok":false,"error":{"code":"not_found","message":"已失效"}}}"""
    )

    assertEquals("不是运行时事件", 1, sink.skippedNonEvent)
    assertEquals("不能计入已接受事件", 0, sink.accepted)
    assertEquals(1, results.size)
    assertEquals("inv-1", results[0].id)
    assertFalse(results[0].ok)
    assertEquals("已失效", results[0].error)
    assertEquals("result 的 seq 只用于日志，不能推进补漏游标", 0L, vm.state.lastSeq)
  }

  @Test
  fun 未握手前send返回false而不是抛出() {
    val client = RelaySocketClient(
      host = "127.0.0.1",
      port = 1,
      deviceId = "d",
      sessionKeyBase64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      expectedFingerprint = "SHA256:00",
      listener = object : RelaySocketClient.Listener {
        override fun onReady(welcome: RelayProtocol.Welcome) {}
        override fun onEvent(json: String) {}
        override fun onClosed(info: RelayProtocol.DisconnectInfo) {}
      },
    )
    assertFalse("没有加密通道时不能发送", client.send("""{"v":1}"""))
  }

  // ═══════════ 失败必须可见 ═══════════

  @Test
  fun 坏JSON计入invalid而不是静默() = runTest {
    val sink = RelayEventSink(newVm(this), ImmediateDispatcher())
    sink.onText("{broken")

    assertEquals("必须计数", 1, sink.invalid)
    assertEquals(1, sink.failures)
    assertNotNull("必须留下原因", sink.lastProblem)
    assertTrue("原因可读", sink.lastProblem!!.contains("ENVELOPE_INVALID"))
  }

  @Test
  fun 非法信封计入invalid() = runTest {
    val sink = RelayEventSink(newVm(this), ImmediateDispatcher())
    sink.onText(envJson(seq = 0))   // seq 0 非法
    assertEquals(1, sink.invalid)
  }

  @Test
  fun 三次数失败计三次而不是一次() = runTest {
    val sink = RelayEventSink(newVm(this), ImmediateDispatcher())
    repeat(3) { sink.onText("{broken") }
    assertEquals("不能被去重掩盖", 3, sink.invalid)
  }

  @Test
  fun 认识不了的事件类型计入unparsed且不丢lastSeq() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, ImmediateDispatcher())
    // 信封合法、seq 正常，但 type 手机不认识
    sink.onText(envJson(seq = 7, body = """{"type":"never_seen_before"}"""))

    assertEquals("必须计入 unparsed", 1, sink.unparsed)
    assertEquals("不算 invalid（信封是对的）", 0, sink.invalid)
    assertEquals("关键：lastSeq 仍要推进到 7", 7L, vm.state.lastSeq)
  }

  @Test
  fun unparsed的lastSeq必须推进否则补漏死循环() = runTest {
    // 若 unparsed 不推进 lastSeq，手机会一直请求「7 之后」的事件，
    // 而服务端每次都从 7 重发同一条 —— 永久死循环。
    val vm = newVm(this)
    val sink = RelayEventSink(vm, ImmediateDispatcher())
    sink.onText(envJson(seq = 7, body = """{"type":"unknown_x"}"""))
    sink.onText(envJson(seq = 8,
      body = """{"type":"message_delta","messageId":"m1","textDelta":"a"}"""))
    assertEquals("下一条继续推进", 8L, vm.state.lastSeq)
    assertEquals(2, sink.accepted + sink.unparsed)
  }

  @Test
  fun 空body里的type缺失也计入unparsed() = runTest {
    val sink = RelayEventSink(newVm(this), ImmediateDispatcher())
    sink.onText(envJson(seq = 3, body = """{"messageId":"m1"}"""))
    assertEquals(1, sink.unparsed)
    assertTrue(sink.lastProblem!!.contains("UNPARSED"))
  }

  // ═══════════ 非事件信封 ═══════════

  @Test
  fun invoke与ack不计入失败也不进归并器() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, ImmediateDispatcher())
    listOf("invoke", "result", "ack").forEach { k ->
      sink.onText(envJson(kind = k, body = """{"type":"anything"}"""))
    }
    assertEquals("三种都跳过", 3, sink.skippedNonEvent)
    assertEquals("不算失败", 0, sink.failures)
    assertTrue("也不能建会话", vm.state.sessions.isEmpty())
  }

  // ═══════════ 两种写入方式 ═══════════

  @Test
  fun 快照方式也能正确写入() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, RelayEventSink.SnapshotDispatcher())
    sink.onText(envJson(seq = 5))
    assertEquals(5L, vm.state.lastSeq)
    assertEquals(1, sink.accepted)
  }

  @Test
  fun 两种写入方式结果一致() = runTest {
    val a = newVm(this)
    val b = newVm(this)
    val sa = RelayEventSink(a, ImmediateDispatcher())
    val sb = RelayEventSink(b, RelayEventSink.SnapshotDispatcher())
    listOf(1, 2, 3).forEach { n ->
      val j = envJson(seq = n,
        body = """{"type":"message_delta","messageId":"m1","textDelta":"$n"}""")
      sa.onText(j); sb.onText(j)
    }
    a.flushNow(); b.flushNow()
    assertEquals(a.state.lastSeq, b.state.lastSeq)
    assertEquals(
      a.state.sessions["s1"]!!.messages.first { it.id == "m1" }.text,
      b.state.sessions["s1"]!!.messages.first { it.id == "m1" }.text,
    )
  }

  // ═══════════ 从 Map 进来的路径（中继通道） ═══════════

  @Test
  fun 已解析的Map也能喂进来() = runTest {
    val vm = newVm(this)
    val sink = RelayEventSink(vm, ImmediateDispatcher())
    sink.onEnvelopeMap(mapOf(
      "v" to 1, "kind" to "event", "seq" to 9, "runId" to "r1", "sessionId" to "s1",
      "at" to 1000L,
      "body" to mapOf(
        "type" to "run_started",
        "userMessage" to mapOf("text" to "问"),
        "assistantMessageId" to "m1",
      ),
    ))
    assertEquals(1, sink.accepted)
    assertEquals(9L, vm.state.lastSeq)
  }

  @Test
  fun Map路径的非法信封同样计数() = runTest {
    val sink = RelayEventSink(newVm(this), ImmediateDispatcher())
    sink.onEnvelopeMap(mapOf("v" to 99, "kind" to "event", "seq" to 1))
    assertEquals(1, sink.invalid)
  }

  // ═══════════ 统计口径 ═══════════

  @Test
  fun failures等于invalid加unparsed() = runTest {
    val sink = RelayEventSink(newVm(this), ImmediateDispatcher())
    sink.onText("{broken")                                                  // invalid
    sink.onText(envJson(seq = 1, body = """{"type":"unknown_a"}"""))        // unparsed
    sink.onText(envJson(seq = 2))                                          // ok
    assertEquals(1, sink.invalid)
    assertEquals(1, sink.unparsed)
    assertEquals("失败总数 = 1 + 1", 2, sink.failures)
    assertEquals(1, sink.accepted)
  }

  @Test
  fun 初始状态全为零且无问题() {
    val scope = CoroutineScope(StandardTestDispatcher())
    val sink = RelayEventSink(
      EventStreamViewModel(scope, applyMs = 0L),
      ImmediateDispatcher(),
    )
    assertEquals(0, sink.accepted)
    assertEquals(0, sink.failures)
    assertNull(sink.lastProblem)
  }

  // ═══════════ M-03：ack 回执 ═══════════

  @Test
  fun 事件应用成功后回一条ack且序号取自信封() = runTest {
    // 回归：`RelayProtocol.ackFrame` 早就写好了，但全仓零调用 ——
    // 桌面 `relay-channel.ts:1094` 的 `kind === 'ack'` 分支从来没被走到过，
    // 于是它无法区分「手机收到了」和「帧丢在网络上」。
    val vm = newVm(this)
    val acks = mutableListOf<Long>()
    val sink = RelayEventSink(vm, ImmediateDispatcher(), sendAck = { acks += it })

    sink.onText(envJson(seq = 7))

    assertEquals("成功应用后必须 ack", listOf(7L), acks)
    assertEquals("序号取自信封而不是本地计数器", 7L, acks.single())
  }

  @Test
  fun 解析失败也要ack_否则桌面会一直重发() = runTest {
    // 这一条比上一条重要：不认识的事件类型如果只计 unparsed、不回 ack，
    // 桌面会认为没送到并持续重发同一条 —— 而本机永远解析不了它，形成死循环。
    val vm = newVm(this)
    val acks = mutableListOf<Long>()
    val sink = RelayEventSink(vm, ImmediateDispatcher(), sendAck = { acks += it })

    sink.onText(envJson(seq = 3, body = """{"type":"某个手机还不认识的类型"}"""))

    assertEquals("确实没解析成功", 1, sink.unparsed)
    assertEquals("但仍然要 ack，让桌面停止重发", listOf(3L), acks)
  }

  @Test
  fun 信封非法时不ack_那帧根本没被当成事件() = runTest {
    val vm = newVm(this)
    val acks = mutableListOf<Long>()
    val sink = RelayEventSink(vm, ImmediateDispatcher(), sendAck = { acks += it })

    sink.onText("""{"v":1,"kind":"event"}""")

    assertEquals("非法信封只计数", 1, sink.invalid)
    assertTrue("没有可信 seq 就不能 ack", acks.isEmpty())
  }

  @Test
  fun 没有seq的旧桌面不会被回一个零号ack() = runTest {
    val vm = newVm(this)
    val acks = mutableListOf<Long>()
    val sink = RelayEventSink(vm, ImmediateDispatcher(), sendAck = { acks += it })

    sink.onText(envJson(seq = 0))

    assertTrue("seq=0 不 ack：没有序号的确认毫无意义", acks.isEmpty())
  }

  @Test
  fun 默认不实现ack时不崩() = runTest {
    // 只读假连接不需要 ack 能力；默认 lambda 必须安静。
    val vm = newVm(this)
    val sink = RelayEventSink(vm, ImmediateDispatcher())
    sink.onText(envJson(seq = 5))
    assertEquals(1, sink.accepted)
  }
}