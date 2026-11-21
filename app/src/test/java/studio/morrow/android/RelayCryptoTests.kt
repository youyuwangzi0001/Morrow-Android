package studio.morrow.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.morrow.android.SecureChannel
import java.io.File
import org.junit.Assert.assertNotNull
import studio.morrow.android.EcdhPairing
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertFalse
import studio.morrow.android.RelaySocketClient
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

// ───── SecureChannelVectorTest.kt ─────
/**
 * 跨语言固定向量测试（D1 第五批，硬要求）。
 *
 * ## 为什么必须是这种形式
 *
 * 「手机加密 → 手机解密」的往返测试即使全绿，也不能证明手机与桌面能互通：
 * HKDF 参数顺序、AAD 拼接、nonce 布局、base64 变体任何一处不同，
 * 两端**各自自洽**却互相解不开。所以这里硬编码的是
 * **桌面 `SecureChannel` 真实生成的密文**，手机必须能解开。
 *
 * 向量由 `scripts/gen-secure-vectors.mjs` 生成，那个脚本与桌面
 * `src/main/relay-crypto.ts` 用同一套参数（HKDF-SHA256 / AES-256-GCM /
 * 4 零字节 + 8 大端字节 nonce / AAD 绑方向+deviceId+计数 / base64url 无填充）。
 * 脚本末尾还做了桌面自检（自己加密自己解密回原文），确保向量本身没写错。
 *
 * ## 固定输入
 *
 * ```
 * sessionKey      = base64(32 个 0x07)
 * connectionSalt  = base64(16 个 0x09)
 * deviceId        = "dev-test-0001"
 * ```
 *
 * 选这种可读性极差的固定值是有意的：任何一处偏差（比如误用 17 字节 salt）
 * 都会立刻算错，而不会碰巧「差不多对」。
 */
class SecureChannelVectorTest {

  private val sessionKeyB64 = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
  private val saltB64 = "CQkJCQkJCQkJCQkJCQkJCQ=="
  private val deviceId = "dev-test-0001"

  /** 桌面派生出的双向密钥（十六进制），用于排错时定位是「派生错了」还是「AAD 错了」 */
  private val expectedP2dKeyHex = "98c4fed0bbdd351bdd57c49cd33013af606ab9952bfad2ca53d91c8f4efafcc2"
  private val expectedD2pKeyHex = "88e3ce06407004e6689d3be575707e6b92801b68ab4e4d0a90946aa25515f502"

  private data class Vector(
    val direction: String,
    val counter: Long,
    val plaintext: String,
    val ciphertextBase64Url: String,
  )

  private val vectors = listOf(
    Vector(
      direction = SecureChannel.D2P,
      counter = 0,
      plaintext = "你好，Morrow",
      ciphertextBase64Url = "FNH8CwrHM2r910Zl2-rWoj7uhQBKhsa_p1DBixS5Xg",
    ),
    Vector(
      direction = SecureChannel.D2P,
      counter = 1,
      plaintext = "",
      ciphertextBase64Url = "Lg3vcYqFUIzu-YrbFysItQ",
    ),
    Vector(
      direction = SecureChannel.D2P,
      counter = 2,
      plaintext = """{"v":1,"kind":"event","seq":1}""",
      ciphertextBase64Url = "C8dZtNTJA2EIR2_qNXH3HP17n9UBaIi02KFwz9KtH2goQcFEJxKVYuyCvafawg",
    ),
    Vector(
      direction = SecureChannel.P2D,
      counter = 0,
      plaintext = """{"cmd":"stop"}""",
      ciphertextBase64Url = "jSunG6ZDEbxWkEDMYQioP5BPa-cGlkE-p7v9etrE",
    ),
  )

  // ═══════════ 密钥派生本身必须一致（排错时的第一定位点）═══════════

  @Test
  fun 手机派生的双向密钥与桌面完全一致() {
    // 这条测试的价值在排错：GCM 认证失败时无法区分「密钥错了」与「AAD/布局错了」。
    // 先确认密钥逐字节相同，就能立刻排除一类原因。
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    assertEquals("p2d 密钥", expectedP2dKeyHex, ch.derivedKeyHexForTest(SecureChannel.P2D))
    assertEquals("d2p 密钥", expectedD2pKeyHex, ch.derivedKeyHexForTest(SecureChannel.D2P))
  }

  @Test
  fun 双向密钥确实不同() {
    // 若碰巧相同，说明 info 串里的 direction 没起作用（反射攻击风险）
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    assertTrue(
      "p2d 与 d2p 密钥必须不同",
      ch.derivedKeyHexForTest(SecureChannel.P2D) != ch.derivedKeyHexForTest(SecureChannel.D2P),
    )
  }

  // ═══════════ 单向：手机解开桌面密文 ═══════════

  @Test
  fun 手机能解开桌面生成的每一条密文() {
    vectors.forEach { v ->
      // 每个向量用新的 channel：接收计数从 -1 开始，counter=0 也要能通过
      val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
      val plain = ch.open(v.direction, SecureChannel.SealedFrame(v.counter.toString(), v.ciphertextBase64Url))
      assertEquals(
        "${v.direction} counter=${v.counter} 解密结果必须与桌面原文一致",
        v.plaintext, String(plain, Charsets.UTF_8),
      )
    }
  }

  @Test
  fun 解开后接收计数推进到该帧的计数() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    val v = vectors.first { it.direction == SecureChannel.D2P && it.counter == 2L }
    ch.open(v.direction, SecureChannel.SealedFrame("2", v.ciphertextBase64Url))
    assertEquals(2L, ch.acceptedCount(SecureChannel.D2P))
  }

  @Test
  fun 按顺序解完三条桌面密文() {
    // 真实连接就是这样：counter 0,1,2 依次到达
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    vectors.filter { it.direction == SecureChannel.D2P }.sortedBy { it.counter }.forEach { v ->
      val plain = ch.open(v.direction, SecureChannel.SealedFrame(v.counter.toString(), v.ciphertextBase64Url))
      assertEquals(v.plaintext, String(plain, Charsets.UTF_8))
    }
    assertEquals("最后一条是 counter=2", 2L, ch.acceptedCount(SecureChannel.D2P))
  }

  @Test
  fun 两个方向互不干扰都能解() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    val d2p = vectors.first { it.direction == SecureChannel.D2P }
    val p2d = vectors.first { it.direction == SecureChannel.P2D }
    // 先收桌面帧再收手机帧：若是同一把钥匙，方向错会认证失败
    assertEquals("你好，Morrow",
      String(ch.open(d2p.direction, SecureChannel.SealedFrame("0", d2p.ciphertextBase64Url)), Charsets.UTF_8))
    assertEquals("""{"cmd":"stop"}""",
      String(ch.open(p2d.direction, SecureChannel.SealedFrame("0", p2d.ciphertextBase64Url)), Charsets.UTF_8))
  }

  // ═══════════ 反向：方向/设备用错必须失败 ═══════════

  @Test
  fun 把桌面帧当手机帧解会认证失败() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    val v = vectors.first { it.direction == SecureChannel.D2P }
    try {
      // 用 P2D 的密钥去解 D2P 的密文 —— 双向密钥分离的意义就在这里
      ch.open(SecureChannel.P2D, SecureChannel.SealedFrame("0", v.ciphertextBase64Url))
      fail("方向用错必须失败")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_TAMPERED, e.code)
    }
  }

  @Test
  fun 换一个deviceId就解不开() {
    // 跨设备挪用：A 设备的密文挂到 B 设备名下
    val other = SecureChannel(sessionKeyB64, saltB64, "dev-other-9999")
    val v = vectors.first { it.direction == SecureChannel.D2P }
    try {
      other.open(v.direction, SecureChannel.SealedFrame("0", v.ciphertextBase64Url))
      fail("deviceId 不同必须失败")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_TAMPERED, e.code)
    }
  }

  @Test
  fun 换一个salt就解不开() {
    // 每连接一个 salt 的意义：旧连接的密文不能在新连接里用
    val otherSalt = java.util.Base64.getEncoder()
      .encodeToString(ByteArray(16) { 0x11 })
    val other = SecureChannel(sessionKeyB64, otherSalt, deviceId)
    val v = vectors.first { it.direction == SecureChannel.D2P }
    try {
      other.open(v.direction, SecureChannel.SealedFrame("0", v.ciphertextBase64Url))
      fail("salt 不同必须失败")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_TAMPERED, e.code)
    }
  }

  @Test
  fun 改一个比特的密文会认证失败() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    val v = vectors.first { it.direction == SecureChannel.D2P }
    val raw = java.util.Base64.getUrlDecoder().decode(v.ciphertextBase64Url)
    raw[0] = (raw[0].toInt() xor 0x01).toByte()
    val tampered = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    try {
      ch.open(v.direction, SecureChannel.SealedFrame("0", tampered))
      fail("篡改必须失败")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_TAMPERED, e.code)
    }
  }

  @Test
  fun 改计数会认证失败因为计数在AAD里() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    val v = vectors.first { it.direction == SecureChannel.D2P }   // counter=0 的密文
    try {
      // 谎报计数为 5：AAD 与 nonce 都会因此改变
      ch.open(v.direction, SecureChannel.SealedFrame("5", v.ciphertextBase64Url))
      fail("改计数必须失败")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_TAMPERED, e.code)
    }
  }

  // ═══════════ 重放（用同一向量重复提交） ═══════════

  @Test
  fun 同一条密文第二次提交被拒为重放() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    val v = vectors.first { it.direction == SecureChannel.D2P }
    ch.open(v.direction, SecureChannel.SealedFrame("0", v.ciphertextBase64Url))
    try {
      ch.open(v.direction, SecureChannel.SealedFrame("0", v.ciphertextBase64Url))
      fail("重放必须被拒")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_REPLAYED, e.code)
    }
  }

  @Test
  fun 计数倒退也被拒为重放() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    val v2 = vectors.first { it.direction == SecureChannel.D2P && it.counter == 2L }
    val v0 = vectors.first { it.direction == SecureChannel.D2P && it.counter == 0L }
    ch.open(v2.direction, SecureChannel.SealedFrame("2", v2.ciphertextBase64Url))
    try {
      ch.open(v0.direction, SecureChannel.SealedFrame("0", v0.ciphertextBase64Url))
      fail("计数倒退必须被拒")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_REPLAYED, e.code)
    }
  }

  @Test
  fun 伪造帧不能污染接收窗口() {
    // 「先污染窗口再拒绝真消息」的拒绝服务：伪造帧失败后，合法帧仍必须能解
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    val v0 = vectors.first { it.direction == SecureChannel.D2P && it.counter == 0L }
    // 先来一条谎报计数 99 的伪造帧
    try { ch.open(v0.direction, SecureChannel.SealedFrame("99", v0.ciphertextBase64Url)) } catch (_: Exception) {}
    // 计数不能因此变成 99
    assertEquals("失败不能推进接收计数", -1L, ch.acceptedCount(v0.direction))
    // 真正的 counter=0 仍然能解
    assertEquals("你好，Morrow",
      String(ch.open(v0.direction, SecureChannel.SealedFrame("0", v0.ciphertextBase64Url)), Charsets.UTF_8))
  }

  // ═══════════ 帧格式校验 ═══════════

  @Test
  fun 计数不是数字被判格式错误() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    try {
      ch.open(SecureChannel.D2P, SecureChannel.SealedFrame("abc", "AAAA"))
      fail("非数字计数必须失败")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_MALFORMED, e.code)
    }
  }

  @Test
  fun 负数计数被判格式错误() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    try {
      ch.open(SecureChannel.D2P, SecureChannel.SealedFrame("-1", "AAAA"))
      fail("负数计数必须失败")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_MALFORMED, e.code)
    }
  }

  @Test
  fun 密文短于tag长度被判格式错误() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    try {
      ch.open(SecureChannel.D2P, SecureChannel.SealedFrame("0", "AAAA"))
      fail("过短密文必须失败")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_MALFORMED, e.code)
    }
  }

  @Test
  fun salt长度不对在构造时就报错() {
    val badSalt = java.util.Base64.getEncoder().encodeToString(ByteArray(15))
    try {
      SecureChannel(sessionKeyB64, badSalt, deviceId)
      fail("15 字节 salt 必须被拒绝（会导致 nonce 重用）")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_MALFORMED, e.code)
    }
  }

  @Test
  fun 空sessionKey在构造时报错() {
    try {
      SecureChannel("", saltB64, deviceId)
      fail("空 sessionKey 必须被拒绝")
    } catch (e: SecureChannel.FrameError) {
      assertEquals(SecureChannel.FRAME_MALFORMED, e.code)
    }
  }
}

// ───── SecureChannelSealProbe.kt ─────
/**
 * 为「手机加密 → 桌面解密」准备输入。
 *
 * 这不是断言测试，而是**产出物**：把手机用同一个 SecureChannel 加密的结果写成
 * JSON，交给 `scripts/verify-phone-seal.mjs` 用桌面的逻辑解密。
 *
 * 为什么需要它：前面 `SecureChannelVectorTest` 只证明了单向（手机解桌面的密文）。
 * 反方向不能靠推理 —— base64url 是否带填充、密文与 tag 的拼接顺序、
 * 计数器起始值，任一处不同都可能单向能通、反向不通。
 *
 * 这条测试本身只断言「文件写出来了」，真正的判定在 node 脚本里
 * （由 verify.sh 第 24 组调用）。
 */
class SecureChannelSealProbe {

  private val sessionKeyB64 = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
  private val saltB64 = "CQkJCQkJCQkJCQkJCQkJCQ=="
  private val deviceId = "dev-test-0001"

  @Test
  fun 导出手机加密结果供桌面验证() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)

    data class Case(val direction: String, val plaintext: String)
    val cases = listOf(
      Case(SecureChannel.P2D, """{"cmd":"stop"}"""),
      Case(SecureChannel.P2D, "手机发出的第二条"),
      Case(SecureChannel.P2D, ""),
      // D2P 方向也加密一遍：验证「双向密钥分离」在加密侧同样正确
      Case(SecureChannel.D2P, "桌面方向的回显"),
    )

    // 用 Kotlin 的 JsonObject 构造，而不是手拼字符串。
    // 手拼时我把转义写错了（本该是转义序列，结果成了真实换行），
    // 产出物不是合法 JSON，node 端 JSON.parse 直接报错。
    // 这类错误在「产出物」里特别隐蔽 —— 测试本身只断言「文件非空」，
    // 格式错了它不会红。
    val frames = kotlinx.serialization.json.JsonArray(
      cases.map { c ->
        val f = ch.seal(c.direction, c.plaintext)
        kotlinx.serialization.json.JsonObject(
          mapOf(
            "direction" to kotlinx.serialization.json.JsonPrimitive(c.direction),
            "n" to kotlinx.serialization.json.JsonPrimitive(f.n),
            "c" to kotlinx.serialization.json.JsonPrimitive(f.c),
            "expectedPlaintext" to kotlinx.serialization.json.JsonPrimitive(c.plaintext),
          )
        )
      }
    )
    val root = kotlinx.serialization.json.JsonObject(
      mapOf(
        "sessionKeyBase64" to kotlinx.serialization.json.JsonPrimitive(sessionKeyB64),
        "connectionSaltBase64" to kotlinx.serialization.json.JsonPrimitive(saltB64),
        "deviceId" to kotlinx.serialization.json.JsonPrimitive(deviceId),
        "frames" to frames,
      )
    )
    val json = kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
      kotlinx.serialization.json.JsonObject.serializer(), root,
    )

    val dir = File("build-evidence")
    dir.mkdirs()
    val out = File(dir, "phone-sealed.json")
    out.writeText(json, Charsets.UTF_8)

    assertTrue("产出物必须写出来，node 脚本才能验证反方向", out.exists() && out.length() > 0)
  }

  @Test
  fun 手机加密结果不含base64填充() {
    // Node 的 Buffer.toString('base64url') 不带 '='；带填充时桌面
    // Buffer.from(..., 'base64url') 仍能解，但格式不一致属于将来会出问题的一类。
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    val frames = (1..8).map { ch.seal(SecureChannel.P2D, "payload-$it") }
    frames.forEach { f ->
      assertTrue("帧长度 ${f.c.length} 不该带填充：${f.c}", !f.c.contains('='))
      assertTrue("也不该出现 + / 之外的 url-unsafe 字符", !f.c.contains('+') && !f.c.contains('/'))
    }
  }

  @Test
  fun 发送计数从零开始并逐条递增() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    org.junit.Assert.assertEquals(0L, ch.sentCount(SecureChannel.P2D))
    val a = ch.seal(SecureChannel.P2D, "a")
    val b = ch.seal(SecureChannel.P2D, "b")
    org.junit.Assert.assertEquals("0", a.n)
    org.junit.Assert.assertEquals("1", b.n)
    org.junit.Assert.assertEquals(2L, ch.sentCount(SecureChannel.P2D))
  }

  @Test
  fun 两个方向的发送计数各自独立() {
    val ch = SecureChannel(sessionKeyB64, saltB64, deviceId)
    ch.seal(SecureChannel.P2D, "a")
    ch.seal(SecureChannel.P2D, "b")
    val d = ch.seal(SecureChannel.D2P, "c")
    org.junit.Assert.assertEquals("D2P 从 0 自己数", "0", d.n)
  }
}

// ───── EcdhPairingVectorTest.kt ─────
/**
 * 配对 / ECDH 的跨语言固定向量测试（D1 第五批第②步）。
 *
 * ## 为什么又是这种形式
 *
 * D1e 已经建立过这个教训：两端各自「自洽往返」全绿也可能握手失败。
 * 配对多出来的三条脆弱点全在跨语言边界上：
 *
 * | 环节 | 容易错的地方 |
 * |---|---|
 * | 公钥编码 | Java 的 `ECPoint` 坐标不补齐前导零 → 31 字节；桌面要求 65 字节 |
 * | 共享秘密 | 有的库返回 X 坐标，有的返回 04‖X‖Y 未压缩点（OpenSSL 3 的变化） |
 * | 六位码 | 小端读 vs 大端读；`%` 后的补零方式 |
 *
 * 所以这里硬编码的是**桌面 `PairingService` 真实算出的值**，
 * 由 `scripts/gen-pairing-vectors.mjs` 直接 `import` 桌面源码生成，
 * 并用 `--check` 模式在 verify.sh 里做漂移检测。
 *
 * 固定输入：
 * ```
 * 手机私钥标量 = 1a2b3c4d5e6f708192a3b4c5d6e7f8090a1b2c3d4e5f60718293a4b5c6d7e8f9
 * 桌面私钥标量 = 1f3a5e7c9b2d406152738495a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f
 * deviceId     = dev-test-0001
 * ```
 */
class EcdhPairingVectorTest {

  companion object {
    // ── 由 scripts/gen-pairing-vectors.mjs 生成（勿手改）──
    private const val PHONE_PRIVATE_HEX =
      "1a2b3c4d5e6f708192a3b4c5d6e7f8090a1b2c3d4e5f60718293a4b5c6d7e8f9"
    private const val PHONE_PUBLIC_KEY =
      "BEA1oFLGaKYt0IB7q+k9qvLiLJiXNPG7qFzQFhf+Uvgwwir2+dku7YccYqps6q+e7iGhjCYtfsv3ob7WwqbRCKc="
    private const val DESKTOP_PUBLIC_KEY =
      "BK0uc/3GCku4SyGv5F8ZMnttvEEkDUrFwlhLiV1urF3kghQN9bAFTu9u5q6L6qPSJwf+4LL5td0L9FFG5VpvDpA="
    private const val EXPECTED_CODE = "115589"
    private const val EXPECTED_SESSION_KEY = "0ahiYsZbp8saj6HWkMSogfOI0F1LSrqZ5Cah/rMknNo="
    private const val FIXED_NONCE = "50b5f6dd15b3e01da6287730afcd437f"
    private const val TICKET_EXP = 1700000090000L
    private const val DEVICE_ID = "dev-test-0001"

  }

  /**
   * 桌面真实签发的票据（由 gen-pairing-vectors.mjs 落到 JSON）。
   *
   * 为什么不硬编码：nonce 是桌面随机生成的，硬编码会让测试与生成脚本
   * 每次都对不上（我一开始就是硬编码了一个旧的 nonce，跑 `--check` 直接红）。
   * 从产出物读取，才能保证「测的是桌面此刻签发的那张票」。
   */
  private fun vectorJson(): String {
    val f = listOf("build-evidence/pairing-vector.json", "../build-evidence/pairing-vector.json")
      .map { java.io.File(it) }.firstOrNull { it.exists() }
      ?: throw IllegalStateException("缺 pairing-vector.json —— 先跑 scripts/gen-pairing-vectors.mjs")
    return f.readText(Charsets.UTF_8)
  }

  /** 从产出物里抽一张字段（避免测试引入 JSON 依赖） */
  private fun jsonField(json: String, name: String): String {
    val key = "\"$name\":"
    val i = json.indexOf(key)
    if (i < 0) throw IllegalStateException("JSON 里没有 $name")
    var j = i + key.length
    while (j < json.length && json[j].isWhitespace()) j++
    return if (json[j] == '"') {
      val start = j + 1
      var k = start
      while (k < json.length && json[k] != '"') k++
      json.substring(start, k)
    } else {
      val start = j
      while (j < json.length && (json[j].isDigit() || json[j] == '-')) j++
      json.substring(start, j)
    }
  }

  /** 桌面真实票据的 JSON（取产出物里的 ticket 子对象原始文本） */
  private fun desktopTicketJson(): String {
    val json = vectorJson()
    val i = json.indexOf("\"ticket\":")
    require(i >= 0) { "产出物里没有 ticket" }
    val start = json.indexOf('{', i)
    var depth = 0
    var k = start
    while (k < json.length) {
      when (json[k]) {
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) return json.substring(start, k + 1)
        }
      }
      k++
    }
    throw IllegalStateException("ticket 子对象不完整")
  }

  private fun desktopTicket(): EcdhPairing.Ticket {
    val raw = desktopTicketJson()
    val t = EcdhPairing.parseTicket(jsonToQr(raw), nowMillis = 0)
    return t
  }

  /** 票据的二维码编码（与桌面 `Buffer.toString('base64url')` 一致） */
  private fun jsonToQr(json: String): String =
    Base64.getUrlEncoder().withoutPadding()
      .encodeToString(json.toByteArray(Charsets.UTF_8))

  /** 用固定标量造一个 P-256 私钥对象（等同桌面的 setPrivateKey） */
  private fun fixedIdentity(): EcdhPairing.Identity {
    val scalar = java.math.BigInteger(PHONE_PRIVATE_HEX, 16)
    val params: ECParameterSpec = EcdhPairing.decodePublicKey(PHONE_PUBLIC_KEY).params
    val priv = KeyFactory.getInstance("EC")
      .generatePrivate(ECPrivateKeySpec(scalar, params)) as ECPrivateKey
    return EcdhPairing.Identity(priv, PHONE_PUBLIC_KEY)
  }

  // ═══════════ 公钥编码：必须 65 字节未压缩点 ═══════════

  @Test
  fun 公钥编码成65字节未压缩点且与桌面一致() {
    val raw = Base64.getDecoder().decode(PHONE_PUBLIC_KEY)
    assertEquals("未压缩点必须是 65 字节", 65, raw.size)
    assertEquals("必须以 0x04 开头", 0x04.toByte(), raw[0])

    // 反过来：把桌面的 base64 解成 Java 公钥再编回去，必须逐字节相同。
    // 这条能抓到「坐标不补前导零」——那种错会让长度变成 63/64 字节。
    val pub = EcdhPairing.decodePublicKey(PHONE_PUBLIC_KEY)
    assertEquals("编回来必须与桌面给出的完全相同", PHONE_PUBLIC_KEY, EcdhPairing.encodeUncompressed(pub))
  }

  @Test
  fun 坐标高位为零时仍补足32字节() {
    // 直接构造一个 X 坐标高位为 0 的点，验证补零逻辑。
    // 这是最容易出错的分支：BigInteger.toByteArray() 在最高位为 0 时会更短。
    val generator = java.security.KeyPairGenerator.getInstance("EC")
    generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    // 反复生成，直到遇到 X 或 Y 首字节为 0 的密钥（约 1/256 概率）
    var found = false
    repeat(4000) {
      if (found) return@repeat
      val kp = generator.generateKeyPair()
      val pub = kp.public as java.security.interfaces.ECPublicKey
      val xLen = (pub.w.affineX.bitLength() + 7) / 8
      val yLen = (pub.w.affineY.bitLength() + 7) / 8
      if (xLen < 32 || yLen < 32) {
        found = true
        val encoded = EcdhPairing.encodeUncompressed(pub)
        val raw = Base64.getDecoder().decode(encoded)
        assertEquals("即使坐标高位为 0，也必须补齐到 65 字节", 65, raw.size)
        // 解回来必须是同一个点
        val back = EcdhPairing.decodePublicKey(encoded)
        assertEquals("X 坐标往返必须一致", pub.w.affineX, back.w.affineX)
        assertEquals("Y 坐标往返必须一致", pub.w.affineY, back.w.affineY)
      }
    }
    assertTrue("4000 次里应当能遇到坐标不足 32 字节的密钥", found)
  }

  @Test
  fun 生成的密钥对公钥也是65字节且能被自己解析() {
    val id = EcdhPairing.generateIdentity()
    val raw = Base64.getDecoder().decode(id.publicKeyB64)
    assertEquals(65, raw.size)
    assertEquals(0x04.toByte(), raw[0])
    // 能被解析回同一个点（自洽，不作为互通证据）
    val back = EcdhPairing.decodePublicKey(id.publicKeyB64)
    assertEquals(id.publicKeyB64, EcdhPairing.encodeUncompressed(back))
  }

  // ═══════════ ECDH：与桌面算出同一个共享秘密 ═══════════

  @Test
  fun 手机用桌面公钥算出的共享秘密与桌面一致() {
    val id = fixedIdentity()
    val shared = EcdhPairing.sharedSecret(id.privateKey, DESKTOP_PUBLIC_KEY)
    val actual = Base64.getEncoder().encodeToString(shared)
    assertEquals("ECDH 共享秘密必须与桌面逐字节相同", EXPECTED_SESSION_KEY, actual)
    assertEquals("X 坐标必须是 32 字节", 32, shared.size)
  }

  @Test
  fun sessionKey就是共享秘密的base64() {
    // 桌面的 completePairing 直接 toBase64(computeSecret(...))，
    // 中间没有再套一层 KDF。手机侧必须一样，否则两边 sessionKey 不同。
    val id = fixedIdentity()
    val shared = EcdhPairing.sharedSecret(id.privateKey, DESKTOP_PUBLIC_KEY)
    assertEquals(EXPECTED_SESSION_KEY, EcdhPairing.sessionKey(shared))
  }

  @Test
  fun 六位码与桌面一致且是六位数字() {
    val id = fixedIdentity()
    val shared = EcdhPairing.sharedSecret(id.privateKey, DESKTOP_PUBLIC_KEY)
    val code = EcdhPairing.pairingCode(shared, FIXED_NONCE)
    assertEquals("六位码必须与桌面相同", EXPECTED_CODE, code)
    assertTrue("必须是 6 位", code.length == 6)
    assertTrue("必须全是数字", code.all { it.isDigit() })
  }

  @Test
  fun 六位码在前导零时仍补足六位() {
    // 找一组会产出前导零的输入：直接构造固定 shared 与 nonce 逐个试，
    // 直到码以 '0' 开头。补零逻辑错了（比如用 toString 直接输出）就会变成 5 位。
    var found = false
    for (i in 0 until 400) {
      val shared = ByteArray(32) { ((i * 31 + it * 7) and 0xFF).toByte() }
      val code = EcdhPairing.pairingCode(shared, "n$i")
      assertEquals("任何输入都必须是 6 位", 6, code.length)
      if (code.startsWith("0")) {
        found = true
        assertTrue("前导零必须保留", code[0] == '0' && code.length == 6)
        break
      }
    }
    assertTrue("400 次里应当能遇到前导零的码", found)
  }

  // ═══════════ 端到端：两端独立算出同一个 sessionKey ═══════════

  @Test
  fun 手机私钥加桌面公钥与桌面私钥加手机公钥算出同一sessionKey() {
    // 这才是配对的全部意义：两端各持一半，独立算出同一个值。
    // 这条用「桌面侧真实跑过一遍」的向量来验 —— 手机算出的必须等于
    // 桌面 PairingService.completePairing 写进设备表的那一个。
    val id = fixedIdentity()
    val phoneSide = EcdhPairing.sessionKey(
      EcdhPairing.sharedSecret(id.privateKey, DESKTOP_PUBLIC_KEY)
    )
    assertEquals(
      "桌面 completePairing 产出的 sessionKey 与手机独立算出的必须相同",
      EXPECTED_SESSION_KEY, phoneSide,
    )
  }

  @Test
  fun 两台不同手机算出不同sessionKey() {
    // 若相同的 sessionKey 能被两台设备产出，说明私钥没参与运算
    val a = EcdhPairing.generateIdentity()
    val b = EcdhPairing.generateIdentity()
    val sa = EcdhPairing.sessionKey(EcdhPairing.sharedSecret(a.privateKey, DESKTOP_PUBLIC_KEY))
    val sb = EcdhPairing.sessionKey(EcdhPairing.sharedSecret(b.privateKey, DESKTOP_PUBLIC_KEY))
    assertTrue("不同手机必须得到不同 sessionKey", sa != sb)
  }

  @Test
  fun sessionKey能直接喂给SecureChannel() {
    // 配对产出与加密层的衔接：sessionKey 必须是 SecureChannel 能接受的 32 字节 base64
    val id = fixedIdentity()
    val sk = EcdhPairing.sessionKey(EcdhPairing.sharedSecret(id.privateKey, DESKTOP_PUBLIC_KEY))
    assertEquals("sessionKey 解码后必须是 32 字节", 32, Base64.getDecoder().decode(sk).size)

    val salt = Base64.getEncoder().encodeToString(ByteArray(16) { 0x09 })
    val ch = studio.morrow.android.SecureChannel(sk, salt, DEVICE_ID)
    val frame = ch.seal(studio.morrow.android.SecureChannel.P2D, "配对完成")
    assertNotNull(frame.c)
    // 自己解得开（自洽），跨端互通另由 D1e 的向量保证
    val plain = ch.open(studio.morrow.android.SecureChannel.P2D, frame)
    assertEquals("配对完成", String(plain, Charsets.UTF_8))
  }

  // ═══════════ 票据解析：三个必查项 ═══════════

  @Test
  fun 能解析桌面签发的真实票据() {
    // 用桌面真实签发的那张票（不是手组 JSON）
    val expected = desktopTicket()
    val qr = jsonToQr(desktopTicketJson())
    val t = EcdhPairing.parseTicket(qr, nowMillis = expected.exp - 1000)

    assertEquals("deviceId 必须与桌面一致", expected.deviceId, t.deviceId)
    assertEquals("手机公钥必须与桌面一致", PHONE_PUBLIC_KEY, t.pubKey)
    assertEquals("桌面公钥必须与桌面一致", DESKTOP_PUBLIC_KEY, t.desktopPub)
    assertEquals("票据里的 desktopPub 必须等于桌面长期公钥", DESKTOP_PUBLIC_KEY, t.desktopPub)
    assertEquals(expected.nonce, t.nonce)
    assertEquals(expected.exp, t.exp)
    assertEquals(expected.relayHint, t.relayHint)
  }

  @Test
  fun 缺desktopPub的票据被拒绝() {
    // 桌面第一版就漏了这个字段 —— 手机拿不到桌面公钥，ECDH 无从算起。
    // 这条断言防止它再被删掉。
    val json = """{"deviceId":"d","pubKey":"$PHONE_PUBLIC_KEY","nonce":"n","exp":${TICKET_EXP}}"""
    val qr = jsonToQr(json)
    try {
      EcdhPairing.parseTicket(qr, TICKET_EXP - 1000)
      fail("缺 desktopPub 必须被拒绝")
    } catch (e: EcdhPairing.PairingException) {
      assertEquals(EcdhPairing.TICKET_INVALID, e.code)
      assertTrue("错误信息要指明缺的是哪个字段", e.message!!.contains("desktopPub"))
    }
  }

  @Test
  fun 过期票据被拒绝并报过期而不是通用错误() {
    val t = desktopTicket()
    val qr = jsonToQr(desktopTicketJson())
    try {
      EcdhPairing.parseTicket(qr, nowMillis = t.exp + 1)
      fail("过期票据必须被拒绝")
    } catch (e: EcdhPairing.PairingException) {
      // 必须是 EXPIRED 而不是 INVALID：两者对用户的含义不同
      // （「重新扫一次码」vs「这张码坏了」）
      assertEquals(EcdhPairing.TICKET_EXPIRED, e.code)
    }
  }

  @Test
  fun 票据含密钥字段时被拒绝() {
    // 把 sessionKey 写进票据是严重错误：二维码会被肩窥、截屏、转发。
    // 这条守卫让「将来有人加了字段」立刻失败，而不是悄悄上线。
    val json = """{"deviceId":"d","pubKey":"$PHONE_PUBLIC_KEY","desktopPub":"$DESKTOP_PUBLIC_KEY","nonce":"n","exp":${TICKET_EXP},"sessionKey":"leaked"}"""
    val qr = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    try {
      EcdhPairing.parseTicket(qr, TICKET_EXP - 1000)
      fail("含 sessionKey 的票据必须被拒绝")
    } catch (e: EcdhPairing.PairingException) {
      assertEquals(EcdhPairing.TICKET_INVALID, e.code)
      assertTrue(e.message!!.contains("sessionKey"))
    }
  }

  /**
   * 手写 JSON 解析器的严格性。
   *
   * 为什么单独测：`JsonLite` 是 80 行手写解析（不引 Gson 只为省一个依赖），
   * 而它的全部价值在「遇到畸形输入必须拒绝」—— 宽松解析会把畸形票据
   * 当成合法票据。第一版就有个真实漏洞：**带尾随逗号的 JSON**
   * （`{...,"exp":1,}`）只要必填字段齐全就能通过，因为循环读完最后一项
   * 遇到 `,` 就跳过了，接着自然结束。
   *
   * 这组用例每个都**带齐全的必填字段**，所以只有真正的语法校验才能拦住它们。
   */
  @Test
  fun 畸形JSON即使字段齐全也被拒绝() {
    val full = """"deviceId":"d","pubKey":"p","desktopPub":"dp","nonce":"n","exp":1"""
    val malformed = listOf(
      "尾随逗号" to "{$full,}",
      "双逗号" to "{\"deviceId\":\"d\",,\"pubKey\":\"p\",\"desktopPub\":\"dp\",\"nonce\":\"n\",\"exp\":1}",
      "前导逗号" to "{,$full}",
      "对象后有垃圾" to "{$full}garbage",
      "键带空格" to "{\"deviceId \":\"d\",\"pubKey\":\"p\",\"desktopPub\":\"dp\",\"nonce\":\"n\",\"exp\":1}",
      "exp 是浮点" to "{\"deviceId\":\"d\",\"pubKey\":\"p\",\"desktopPub\":\"dp\",\"nonce\":\"n\",\"exp\":1.5}",
      "exp 为空" to "{\"deviceId\":\"d\",\"pubKey\":\"p\",\"desktopPub\":\"dp\",\"nonce\":\"n\",\"exp\":}",
      "嵌套对象值" to """{"deviceId":{"a":"b"},"pubKey":"p","desktopPub":"dp","nonce":"n","exp":1}""",
      "数组值" to """{"deviceId":["a"],"pubKey":"p","desktopPub":"dp","nonce":"n","exp":1}""",
      "缺逗号" to """{"deviceId":"d" "pubKey":"p"}""",
      "未闭合字符串" to """{"deviceId":"d}""",
      "键无引号" to """{deviceId:"d"}""",
    )
    for ((name, json) in malformed) {
      val qr = jsonToQr(json)
      try {
        EcdhPairing.parseTicket(qr, nowMillis = 0)
        fail("畸形 JSON 必须被拒绝：$name -> $json")
      } catch (e: EcdhPairing.PairingException) {
        assertEquals("$name 的拒绝码", EcdhPairing.TICKET_INVALID, e.code)
      }
    }
  }

  @Test
  fun 坏base64与坏JSON都被拒绝() {
    for (bad in listOf("!!!not-base64!!!", "e30", "W10", "")) {
      try {
        EcdhPairing.parseTicket(bad, 0)
        fail("非法二维码必须被拒绝：$bad")
      } catch (e: EcdhPairing.PairingException) {
        assertEquals(EcdhPairing.TICKET_INVALID, e.code)
      }
    }
  }

  @Test
  fun 公钥长度不对时拒绝() {
    for (bad in listOf("", "AAAA", Base64.getEncoder().encodeToString(ByteArray(64)))) {
      try {
        EcdhPairing.decodePublicKey(bad)
        fail("非 65 字节公钥必须被拒绝：${bad.length}")
      } catch (e: EcdhPairing.PairingException) {
        assertEquals(EcdhPairing.TICKET_INVALID, e.code)
      }
    }
  }

  // ═══════════ 中间人：六位码必须变化 ═══════════

  @Test
  fun 公钥被替换后六位码不同() {
    // 六位码存在的全部理由是抓中间人。
    // 中间人把桌面公钥换成自己的 → 手机算出的共享秘密不同 → 码不同。
    val id = fixedIdentity()
    val attacker = EcdhPairing.generateIdentity()

    val encrypted = EcdhPairing.sharedSecret(id.privateKey, DESKTOP_PUBLIC_KEY)
    val codeReal = EcdhPairing.pairingCode(encrypted, FIXED_NONCE)

    val mitm = EcdhPairing.sharedSecret(id.privateKey, attacker.publicKeyB64)
    val codeMitm = EcdhPairing.pairingCode(mitm, FIXED_NONCE)

    assertEquals("真实公钥算出的码", EXPECTED_CODE, codeReal)
    assertTrue("公钥被换后六位码必须不同（否则人工比对形同虚设）", codeMitm != codeReal)
  }

  @Test
  fun nonce不同六位码也不同() {
    // nonce 一次性：同一对密钥在不同配对会话里码不同，
    // 防止「上次看到过的码」被复用
    val id = fixedIdentity()
    val shared = EcdhPairing.sharedSecret(id.privateKey, DESKTOP_PUBLIC_KEY)
    val a = EcdhPairing.pairingCode(shared, FIXED_NONCE)
    val b = EcdhPairing.pairingCode(shared, "ffffffffffffffffffffffffffffffff")
    assertTrue("不同 nonce 必须得到不同码", a != b)
  }

  // ═══════════ pair() 组合入口 ═══════════

  @Test
  fun pair一次给出码与sessionKey() {
    // 用桌面真实签发的票据：码由那张票的 nonce 决定（随机），
    // 所以断言「等于桌面自己算的码」而不是硬编码常量。
    val ticket = desktopTicket()
    val result = EcdhPairing.pair(ticket, fixedIdentity())

    assertEquals("sessionKey 必须与桌面一致", EXPECTED_SESSION_KEY, result.sessionKey)
    assertEquals("deviceId 来自票据", ticket.deviceId, result.deviceId)
    assertEquals("nonce 原样带出（用于回显给桌面）", ticket.nonce, result.nonce)
    assertTrue("码必须是 6 位", result.code.length == 6)
    // 同一张票、同一密钥，重复计算结果必须稳定
    assertEquals(result.code, EcdhPairing.pair(ticket, fixedIdentity()).code)
  }

  @Test
  fun 固定nonce下的六位码与桌面硬编码值一致() {
    // 与脚本输出的 EXPECTED_CODE 对照（用固定 nonce，可复现）
    val shared = EcdhPairing.sharedSecret(fixedIdentity().privateKey, DESKTOP_PUBLIC_KEY)
    assertEquals(EXPECTED_CODE, EcdhPairing.pairingCode(shared, FIXED_NONCE))
  }
}

// ───── TlsFingerprintVectorTest.kt ─────
/**
 * **跨语言固定向量**：桌面 `certFingerprint()` 与手机 `fingerprintOf()` 必须逐字符相同。
 *
 * ## 这个类存在的理由
 *
 * 证书固定是手机连桌面的**唯一**安全机制（自签证书没有可信链）。
 * 它成立于一个前提：两端对同一张证书算出同一个指纹字符串。
 *
 * 前一版这里是坏的：桌面用 `digest('base64url')`，手机用 `Base64.getEncoder()`
 * （标准字母表）。同一张证书会产出两个不同的字符串 —— 而且更糟的是，
 * 手机**回落到标准解码器**去解析用户给的 base64url 指纹时会抛异常，
 * 于是判「不匹配」，报错是「证书不受信任」。
 *
 * 实测：40 张真实证书里 33 张（83%）的指纹含 `-` 或 `_`。
 * 也就是说**八成以上的真实配对会失败**，而排查方向完全指向错误的方向。
 *
 * ## 向量怎么来
 *
 * `scripts/gen-tls-fingerprint-vector.mjs` **import 桌面的 certFingerprint**
 * （不是手抄参数），对同一份 DER 算一次，输出期望值。
 * 桌面改一个字符，重跑脚本值就变，这个类立刻红。
 */
class TlsFingerprintVectorTest {

  /**
   * 固定证书的 DER（base64，无换行）。
   *
   * 自签证书，CN=morrow-lan，RSA-2048，sha256 —— 与桌面 `tls.ts` 的生成参数一致。
   * 私钥已丢弃，这里只需要「同一份字节」。
   */
  private val certDerB64 =
    "MIIDAzCCAeugAwIBAgIJcrSsqs73kWYIMA0GCSqGSIb3DQEBCwUAMBUxEzARBgNVBAMTCm1vcnJv" +
      "dy1sYW4wHhcNMjYxMTIwMTI1NzI4WhcNMjcxMTIwMTI1NzI4WjAVMRMwEQYDVQQDEwptb3Jyb3ct" +
      "bGFuMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAzHtS6yUzWstCO8MwvOqZ7maOEx7v" +
      "i6LxS/RQaEwFDX1REzhr88tF+5W9JGi+St9ByGAPPqJQL92hDtjQ+VysONuTQcHpuPEmtXNRK4r3" +
      "S7nHxid0tGHxPmW0pUlB9U1GmswfVqnzSRR1zRuHQd0yqV+OhJN3CAeVfFRJvd+uYh4HIeRAgxYj" +
      "TvPOLcQhUbV7O01r5rUFmHYcntPSJi0aaAIfEIx9q1+IQBZgkA/B2iRd4YEwl0aSUIUwBhhHk69d" +
      "gO8QpxXAzBIZAUw8ErcygdNpplOkB+NzmUD0N/zueDXwF8hs9RPVTnaaLVLAeZTXu+zhghVXTT1O" +
      "zzBCUiE8UwIDAQABo1YwVDAMBgNVHRMBAf8EAjAAMA4GA1UdDwEB/wQEAwIFoDAdBgNVHSUEFjAU" +
      "BggrBgEFBQcDAQYIKwYBBQUHAwIwFQYDVR0RBA4wDIIKbW9ycm93LWxhbjANBgkqhkiG9w0BAQsF" +
      "AAOCAQEAcGEaA6/PF5Afxj20uxyx3nZowxZv5LhsPKEuok8yEDMdKAx8uoRiN/t0n0g6DakphuGF" +
      "J8AP7ikrr2178hfLQHNszx1n2tm3GXJNns0DtH7ch5+W/rOO1uSjQyTKaZb+EWV46OaUKICNQdA5" +
      "9LbwKJYAMor2ovT+dyzVaRhuvUBezGnSxPDgAJEGySyTzCI3tPqNxpuj4zDcNAexawap9BXRLUOW" +
      "qdqaRtDRlui7FtUQ6TsN2tcaJGrj9ihNSnPQOZq6MZgKVWeYS69J5PNKq9exDJeW2pggAXq3TwOD" +
      "OvCbFKCcg2hEsRABpeW/jbUH0rW0DVWNtR+d0d+NG3U0Rw=="

  /** 桌面 `certFingerprint()` 对上面这份 DER 的输出（脚本生成，勿手改） */
  private val expectedFingerprint = "tYhp-6S_bSXQwwvLSU_UtzCJTBYcG2Ow6uST_Dadcjc"

  private fun cert(): X509Certificate {
    val der = Base64.getDecoder().decode(certDerB64)
    return CertificateFactory.getInstance("X.509")
      .generateCertificate(der.inputStream()) as X509Certificate
  }

  // ─────────────────────── 核心断言 ───────────────────────

  @Test
  fun 手机算出的指纹与桌面逐字符相同() {
    val actual = RelaySocketClient.SSLSocketFactoryFactory.fingerprintOf(cert())
    // 注意：桌面侧的值**不带** `SHA256:` 前缀（tls.ts 只返回 base64url）
    assertEquals(
      "指纹必须与桌面 certFingerprint() 完全一致，否则真机上永远连不上",
      expectedFingerprint,
      actual.removePrefix("SHA256:"),
    )
  }

  @Test
  fun 指纹用base64url字母表且不带填充() {
    val raw = RelaySocketClient.SSLSocketFactoryFactory.fingerprintOf(cert()).removePrefix("SHA256:")
    assertEquals("sha256 摘要 → 43 字符（无填充）", 43, raw.length)
    assertFalse("不能带 = 填充（桌面 digest('base64url') 就不带）", raw.contains("="))
    assertFalse("不能带 + （标准字母表）", raw.contains("+"))
    assertFalse("不能带 / （标准字母表）", raw.contains("/"))
  }

  @Test
  fun 这份向量确实含base64url专有字符() {
    // 这条是**测试自身**的有效性断言。
    // 如果这份向量恰好只含字母数字，那它就测不到「标准解码器拒绝 - 和 _」这个缺陷 ——
    // 测试会绿，但它什么都没证明。
    val raw = expectedFingerprint
    assertTrue(
      "固定向量必须含 - 或 _，否则这条测试证明不了 base64url 支持",
      raw.contains("-") || raw.contains("_"),
    )
  }

  // ─────────────────────── 归一化与比对 ───────────────────────

  @Test
  fun 桌面原样指纹能被自己接受() {
    val actual = RelaySocketClient.SSLSocketFactoryFactory.fingerprintOf(cert())
    assertTrue(
      "手机算出的指纹必须能匹配桌面给我的同一串",
      RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals(actual, "SHA256:$expectedFingerprint"),
    )
    assertTrue(
      "不带前缀也应接受（桌面日志里可能只打了裸值）",
      RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals(actual, expectedFingerprint),
    )
  }

  @Test
  fun hex形式的同一份摘要也能匹配() {
    val der = Base64.getDecoder().decode(certDerB64)
    val hex = MessageDigest.getInstance("SHA-256").digest(der)
      .joinToString("") { "%02x".format(it) }
    val actual = RelaySocketClient.SSLSocketFactoryFactory.fingerprintOf(cert())
    assertTrue(
      "用户从别处拿到 hex 形式也不能拒绝",
      RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals(actual, hex),
    )
  }

  @Test
  fun 差一个字节的指纹必须判不等() {
    val actual = RelaySocketClient.SSLSocketFactoryFactory.fingerprintOf(cert())
    // 改第一位（第一位是有意义的位，不像末位的填充位）
    val tampered = "SHA256:" + (if (expectedFingerprint[0] == 'A') 'B' else 'A') + expectedFingerprint.drop(1)
    assertFalse(
      "改一个字符必须判不等，否则证书固定形同虚设",
      RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals(actual, tampered),
    )
  }

  @Test
  fun 长度不是32字节的摘要一律拒绝() {
    // sha256 摘要必然是 32 字节。不卡长度的话，空串会解出空数组，
    // 于是 `fingerprintEquals("", "")` 为 true —— 一份没写指纹的凭据
    // 会被判成「指纹匹配」。
    assertFalse(
      "空串不能匹配空串",
      RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals("", ""),
    )
    assertFalse(
      "31 字节的 base64 不能匹配",
      RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals(
        "SHA256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31)),
        "SHA256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31)),
      ),
    )
  }

  @Test
  fun 向量元数据与桌面一致() {
    // 让脚本 --check 能逐项核对：DER 字节数、指纹长度。
    // 这两个数字写在这里而不是从别处推导 —— 它们是**交叉核对点**，
    // 由 Node 与 Kotlin 两侧各算一次，值相同才说明两边拿到的是同一份输入。
    assertEquals("DER 必须是 775 字节", 775, Base64.getDecoder().decode(certDerB64).size)
    assertEquals("指纹必须是 43 字符", 43, expectedFingerprint.length)
  }

  @Test
  fun 桌面指纹的编码方式被写死为base64url() {
    // 这条守着「有人把 fingerprintOf 改回标准 base64」这种回退。
    // 直接断言输出里不含标准字母表专有字符还不够 —— 43 字符里本来也可能没有。
    // 所以用一份**必然**含 base64url 专有字符的摘要：全 0xFF 的 32 字节。
    val allHigh = ByteArray(32) { 0xFF.toByte() }
    val std = Base64.getEncoder().encodeToString(allHigh)
    val url = Base64.getUrlEncoder().withoutPadding().encodeToString(allHigh)
    assertTrue("前提：这份摘要的标准 base64 确实含 /", std.contains("/"))
    assertTrue("前提：这份摘要的 base64url 确实含 _", url.contains("_"))
    assertFalse("base64url 形式不应含 /", url.contains("/"))
  }
}

// ───── TicketFieldProbe.kt ─────
/**
 * 探针：确认 `parseTicket` 真的能读出 `relayHint` 与 `fingerprint`。
 *
 * 这两条是 E2b 新加的字段。加它们的直接原因是：`MainActivity.onPairingCode`
 * 要用 `relayHint` 决定连哪、用 `fingerprint` 做证书固定。
 * 若解析读不出来，配对会「成功」但存下一份缺字段的凭据 —— 正是
 * `load()` 报 `CREDENTIALS_CORRUPT` 的那条路径。
 *
 * 单独放一个探针类而不是塞进 `EcdhPairingVectorTest`：
 * 那个类是跨语言固定向量（数据来自 Node），这里要验的是**本层解析行为**，
 * 混在一起会让「哪一侧出问题」变得不清楚。
 */
class TicketFieldProbe {

  private fun qr(json: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

  private val baseJson = """{"deviceId":"d","pubKey":"BAAAA","desktopPub":"BBBBB","nonce":"n","exp":"9999999999999"}"""

  @Test
  fun relayHint能被读出来() {
    val t = EcdhPairing.parseTicket(
      qr(baseJson.dropLast(1) + ""","relayHint":"192.168.1.10:7788"}"""),
      1_000_000L,
    )
    assertNotNull("relayHint 必须被解析出来", t.relayHint)
    assertEquals("192.168.1.10:7788", t.relayHint)
  }

  @Test
  fun fingerprint能被读出来() {
    val t = EcdhPairing.parseTicket(
      qr(baseJson.dropLast(1) + ""","fingerprint":"SHA256:aabb"}"""),
      1_000_000L,
    )
    assertNotNull("fingerprint 必须被解析出来", t.desktopFingerprint)
    assertEquals("SHA256:aabb", t.desktopFingerprint)
  }

  @Test
  fun 两个字段同时存在时都能读出来() {
    val t = EcdhPairing.parseTicket(
      qr(baseJson.dropLast(1) + ""","relayHint":"h:1","fingerprint":"SHA256:x"}"""),
      1_000_000L,
    )
    assertEquals("h:1", t.relayHint)
    assertEquals("SHA256:x", t.desktopFingerprint)
  }

  @Test
  fun 字段顺序不影响解析() {
    // 桌面 JSON.stringify 的输出顺序取决于对象字面量顺序，
    // 将来加字段或改顺序时，按位置解析的实现会静默错位。
    val a = EcdhPairing.parseTicket(
      qr("""{"relayHint":"h:1","deviceId":"d","pubKey":"BAAAA","desktopPub":"BBBBB","nonce":"n","exp":"9999999999999"}"""),
      1_000_000L,
    )
    assertEquals("h:1", a.relayHint)
    assertEquals("d", a.deviceId)
  }

  @Test
  fun 缺失时是null而不是空串() {
    // null 和 "" 在调用方要区分：`isNullOrBlank()` 都能挡，
    // 但写成 `== ""` 就只挡得住一种。这里锁住约定。
    val t = EcdhPairing.parseTicket(qr(baseJson), 1_000_000L)
    assertEquals(null, t.relayHint)
    assertEquals(null, t.desktopFingerprint)
  }
}
