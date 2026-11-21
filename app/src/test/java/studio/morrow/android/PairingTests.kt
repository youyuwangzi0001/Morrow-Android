package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.morrow.android.EcdhPairing
import studio.morrow.android.PairingFlow
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertNull
import org.junit.Rule
import org.robolectric.annotation.Config
import studio.morrow.android.InMemoryKeyProvider
import studio.morrow.android.RelayCredentials
import studio.morrow.android.RelayHint
import studio.morrow.android.SecretKeyProvider
import studio.morrow.android.TAG_PAIRING_PASTE_FIELD
import studio.morrow.android.TAG_PAIRING_SCREEN
import studio.morrow.android.TAG_PAIRING_SUBMIT_TICKET
import studio.morrow.android.TAG_GO_PAIRING
import org.junit.Assert.assertNotEquals
import studio.morrow.android.PairRequest
import studio.morrow.android.TAG_PAIRING_COPY_REQUEST
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import studio.morrow.android.RelayConnectionController
import studio.morrow.android.RelayConnectionState
import studio.morrow.android.RelayEventSink
import studio.morrow.android.RelayProtocol
import studio.morrow.android.RelaySocketClient
import studio.morrow.android.EventStreamViewModel
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ───── PairingFlowTest.kt ─────
/**
 * 配对流程状态机（`PairingFlow`）。
 *
 * ## 测试用的票据是真的
 *
 * 不手搓 JSON 字符串：先用 `EcdhPairing` 生成一对桌面密钥，再用桌面
 * **同一套编码规则**（65 字节未压缩点 → base64）拼出票据，然后 base64url 编码。
 * 这样 `parseTicket` 走的是真实解析路径 —— 手搓字符串会绕开编码规则，
 * 一旦 base64url 变体（`-_` vs `+/`）不一致，测试仍然绿而真机必挂。
 *
 * ## 六位码怎么来
 *
 * 桌面侧 = `ECDH(桌面私钥, 手机公钥)`，手机侧 = `ECDH(手机私钥, 桌面公钥)`，
 * 两边共享秘密相同 → 同一个 HMAC → 同一个六位码。
 * 测试里两边各算一次并断言相等，这本身就是「两端能对上」的证据。
 */
@RunWith(RobolectricTestRunner::class)
class PairingFlowTest {

  // ─────────────────────── 真实票据的构造 ───────────────────────

  private val desktopKp = KeyPairGenerator.getInstance("EC").apply {
    initialize(ECGenParameterSpec("secp256r1"))
  }.generateKeyPair()

  private val phoneIdentity = EcdhPairing.generateIdentity()

  /** 用桌面私钥 + 手机公钥算共享秘密（等价于桌面 `pairing.issueTicket` 那一步） */
  private fun desktopSharedSecret(phonePubB64: String): ByteArray {
    val kdf = javax.crypto.KeyAgreement.getInstance("ECDH")
    kdf.init(desktopKp.private)
    kdf.doPhase(EcdhPairing.decodePublicKey(phonePubB64), true)
    return kdf.generateSecret()
  }

  private fun desktopPubB64(): String =
    EcdhPairing.encodeUncompressed(desktopKp.public as ECPublicKey)

  private fun desktopCode(nonce: String): String =
    EcdhPairing.pairingCode(desktopSharedSecret(phoneIdentity.publicKeyB64), nonce)

  /** 拼一张真实票据的二维码内容（与桌面 issueTicket 的输出形式一致） */
  private fun qrOf(
    nonce: String,
    exp: Long,
    deviceId: String = "desk-0001",
    relayHint: String? = "192.168.1.10:7788",
    extra: Map<String, String> = emptyMap(),
  ): String {
    val fields = LinkedHashMap<String, String>()
    fields["deviceId"] = deviceId
    fields["pubKey"] = phoneIdentity.publicKeyB64
    fields["desktopPub"] = desktopPubB64()
    fields["nonce"] = nonce
    fields["exp"] = exp.toString()
    relayHint?.let { fields["relayHint"] = it }
    fields.putAll(extra)
    val json = fields.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
  }

  private fun flow() = PairingFlow()

  // ═══════════ 正常路径 ═══════════

  @Test
  fun 正常路径从扫码到配对完成() {
    val f = flow()
    val now = 1_000_000L
    val nonce = "aabbccdd"
    val sessionKey = "c2Vzc2lvbmtleXNlc3Npb25rZXlzZXNzaW9ua2V5MDE="

    assertEquals("初始应为 Idle", PairingFlow.State.Idle, f.state)
    f.beginScan()
    assertEquals(PairingFlow.State.Scanning, f.state)

    f.onQrScanned(qrOf(nonce, now + 90_000), phoneIdentity, now)
    val ready = f.state as? PairingFlow.State.TicketReady
    assertNotNull("应进入 TicketReady", ready)
    assertEquals("六位码必须与桌面算出的一致", desktopCode(nonce), ready!!.expectedCode)
    assertEquals("过期时刻必须来自票据", now + 90_000, ready.expiresAtMillis)

    f.beginCodeEntry()
    val entering = f.state as? PairingFlow.State.CodeEntering
    assertNotNull(entering)
    assertEquals("初始应有 3 次机会", 3, entering!!.attemptsLeft)

    assertTrue("码对时提交应返回 true", f.submitCode(ready.expectedCode, now + 1_000, sessionKey))
    assertTrue("应进入 Verifying", f.state is PairingFlow.State.Verifying)

    f.onVerified()
    assertTrue("应进入 Saving", f.state is PairingFlow.State.Saving)

    f.onSaved()
    val paired = f.state as? PairingFlow.State.Paired
    assertNotNull("应进入 Paired", paired)
    assertEquals("deviceId 必须来自票据", "desk-0001", paired!!.deviceId)
  }

  @Test
  fun 六位码在手机本地就能判断对错不需要先连桌面() {
    // 这是整个配对方案的关键：两端各自算，不靠传输。
    // 若这条不成立，就必须先连上中继才能配对 —— 而中继地址来自票据，
    // 变成「要配对得先配对」的死锁。
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("11223344", now + 90_000), phoneIdentity, now)
    f.beginCodeEntry()

    val wrong = (f.state as PairingFlow.State.CodeEntering)
    assertFalse(
      "错码必须在本地就被拒（不发起任何网络请求）",
      f.submitCode("000000", now + 1_000, "k"),
    )
    assertTrue("仍停在 CodeEntering", f.state is PairingFlow.State.CodeEntering)
    assertEquals("错误次数应减少", 2, (f.state as PairingFlow.State.CodeEntering).attemptsLeft)
    assertEquals("必须标记这次输错了（界面要抖一下）", true, (f.state as PairingFlow.State.CodeEntering).lastMismatch)
    // wrong 变量只是为了让编译器确认类型，实际断言在上面
    assertNotNull(wrong)
  }

  @Test
  fun 输错但还有机会时仍停在输码状态() {
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("5566", now + 90_000), phoneIdentity, now)
    f.beginCodeEntry()

    repeat(2) {
      f.submitCode("999999", now + 1_000, "k")
      assertTrue("前两次错仍应可继续", f.state is PairingFlow.State.CodeEntering)
    }
    assertEquals("剩 1 次", 1, (f.state as PairingFlow.State.CodeEntering).attemptsLeft)
  }

  @Test
  fun 连续输错三次后失败并给出码错误原因() {
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("7788", now + 90_000), phoneIdentity, now)
    f.beginCodeEntry()

    repeat(3) { f.submitCode("999999", now + 1_000, "k") }
    val failed = f.state as? PairingFlow.State.Failed
    assertNotNull("三次错后应失败", failed)
    assertEquals(PairingFlow.Failure.CODE_MISMATCH, failed!!.failure)
    assertTrue("码错误可以重扫", failed.failure.canRetryFromScan)
  }

  // ═══════════ 过期：本卡最重要的路径 ═══════════

  @Test
  fun 扫码时票据已过期直接报过期而不是通用无效() {
    // 报 TICKET_INVALID 会让用户以为「扫错码了」，而实际是「扫慢了」。
    // 两种情况该做的事完全不同：前者重新对准，后者回电脑重新生成。
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("aa", now - 1), phoneIdentity, now)

    val failed = f.state as? PairingFlow.State.Failed
    assertNotNull(failed)
    assertEquals(
      "必须报过期（TICKET_EXPIRED），不是 TICKET_INVALID",
      PairingFlow.Failure.TICKET_EXPIRED, failed!!.failure,
    )
  }

  @Test
  fun 输码期间票据过期时提交码被判过期() {
    // 用户扫完码、正在输六位数时接了个电话，回来时票据已过期。
    // 若先比码再判过期，用户输对了却被告知「码错了」—— 错的提示。
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("bb", now + 10_000), phoneIdentity, now)
    f.beginCodeEntry()
    val expected = (f.state as PairingFlow.State.CodeEntering).expectedCode

    // 过 11 秒再提交正确的码
    assertFalse("过期后即使码对也不能通过", f.submitCode(expected, now + 11_000, "k"))
    val failed = f.state as? PairingFlow.State.Failed
    assertEquals(
      "必须报过期，不能报码错误",
      PairingFlow.Failure.TICKET_EXPIRED, failed!!.failure,
    )
  }

  @Test
  fun 过期后可以重新扫码而不用先回到Idle之外的状态() {
    // 90 秒 TTL 意味着过期是常态，不是异常。失败态必须能直接接受新二维码。
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("cc", now - 5), phoneIdentity, now)
    assertTrue("先失败", f.state is PairingFlow.State.Failed)

    // 用户在电脑上重新生成后，直接再扫
    f.onQrScanned(qrOf("dd", now + 90_000), phoneIdentity, now)
    assertTrue(
      "失败态下必须能直接接受新票据（否则用户要退出再进来，多一步且没有提示）",
      f.state is PairingFlow.State.TicketReady,
    )
  }

  @Test
  fun 剩余秒数与过期判断一致() {
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("ee", now + 30_000), phoneIdentity, now)

    assertEquals("剩余 30 秒", 30L, f.remainingSeconds(now))
    assertFalse("未过期", f.isExpired(now))
    assertTrue("到点即过期（> 判定）", f.isExpired(now + 30_001))
    assertEquals("过期后剩余秒数为 0 而不是负数", 0L, f.remainingSeconds(now + 40_000))
  }

  // ═══════════ 各类失败原因必须可区分 ═══════════

  @Test
  fun 坏二维码报票据无效并可重扫() {
    val f = flow()
    f.beginScan()
    f.onQrScanned("这不是二维码", phoneIdentity, 1_000_000L)
    val failed = f.state as PairingFlow.State.Failed
    assertEquals(PairingFlow.Failure.TICKET_INVALID, failed!!.failure)
    assertTrue(failed.failure.canRetryFromScan)
  }

  @Test
  fun 桌面拒绝报桌面拒绝而不是码错误() {
    // 两者处置完全不同：码错误用户自己能修，桌面拒绝必须回电脑看。
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("ff", now + 90_000), phoneIdentity, now)
    f.beginCodeEntry()
    val code = (f.state as PairingFlow.State.CodeEntering).expectedCode
    f.submitCode(code, now + 1_000, "k")
    f.onVerified()
    f.onRejected("票据已使用，重放被拒绝", now + 2_000)

    val failed = f.state as? PairingFlow.State.Failed
    assertEquals(PairingFlow.Failure.DESKTOP_REJECTED, failed!!.failure)
    assertTrue("详情要保留桌面给的理由", failed.detail.contains("已使用"))
  }

  @Test
  fun 保存失败报保存失败而不是配对失败() {
    // 「配对成功但存不下来」与「配对没成功」必须分开：
    // 前者用户重试有用（磁盘/权限问题），后者要回电脑重新生成。
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("1234", now + 90_000), phoneIdentity, now)
    f.beginCodeEntry()
    f.submitCode((f.state as PairingFlow.State.CodeEntering).expectedCode, now + 1_000, "k")
    f.onVerified()
    f.onSaveFailed("Keystore 不可用", now + 2_000)

    assertEquals(PairingFlow.Failure.SAVE_FAILED, (f.state as PairingFlow.State.Failed).failure)
  }

  @Test
  fun 取消不算错误也不该给重试按钮() {
    val f = flow()
    f.beginScan()
    f.cancel(1_000_000L)
    val failed = f.state as? PairingFlow.State.Failed
    assertEquals(PairingFlow.Failure.CANCELLED, failed!!.failure)
    assertFalse(
      "取消不是错误，不该提示重试",
      failed.failure.canRetryFromScan,
    )
    assertFalse("取消后不应显示「去配对」入口（应回到未配对态）", f.canStartPairing)
  }

  @Test
  fun 六种失败原因都有各自的用户提示() {
    // 提示必须互不相同：全都说「配对失败」时，用户只能重来，
    // 而其中三种重来是没用的（要回电脑操作）。
    val hints = PairingFlow.Failure.entries.map { it.userHint }
    assertEquals("提示文案不能重复", hints.size, hints.toSet().size)
    assertTrue("每条提示都不该为空", hints.all { it.isNotBlank() })

    val codes = PairingFlow.Failure.entries.map { it.code }
    assertEquals("错误码不能重复", codes.size, codes.toSet().size)
  }

  // ═══════════ 非法转换必须被忽略而不是把状态搞乱 ═══════════

  @Test
  fun 输码阶段再扫一张票会被忽略() {
    // 用户手里有两张票时会连续扫到。换票会让已经输了一半的码失去意义。
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("aaaa", now + 90_000), phoneIdentity, now)
    f.beginCodeEntry()
    val before = f.state as PairingFlow.State.CodeEntering

    f.onQrScanned(qrOf("bbbb", now + 90_000), phoneIdentity, now)
    val after = f.state as? PairingFlow.State.CodeEntering
    assertNotNull("仍应在输码阶段", after)
    assertEquals("票据不能被换掉", before.ticket.nonce, after!!.ticket.nonce)
  }

  @Test
  fun 没在输码时提交码不会改变状态() {
    val f = flow()
    f.beginScan()
    assertFalse("Scanning 阶段提交码无效", f.submitCode("123456", 1_000_000L, "k"))
    assertEquals(PairingFlow.State.Scanning, f.state)
  }

  @Test
  fun 没在验证时收到通过消息不会推进() {
    val f = flow()
    f.beginScan()
    f.onVerified()
    assertEquals("仍在 Scanning", PairingFlow.State.Scanning, f.state)
  }

  @Test
  fun reset回到Idle并可重新开始() {
    val f = flow()
    f.beginScan()
    f.cancel(1_000_000L)
    f.reset()
    assertEquals(PairingFlow.State.Idle, f.state)
    assertTrue("Idle 应允许开始配对", f.canStartPairing)
  }

  @Test
  fun 完成之后可以开始下一次配对() {
    // 换一台电脑要能重新配对。若 Paired 之后不允许重开，用户只能重装 App。
    val f = flow()
    val now = 1_000_000L
    f.beginScan()
    f.onQrScanned(qrOf("cccc", now + 90_000), phoneIdentity, now)
    f.beginCodeEntry()
    f.submitCode((f.state as PairingFlow.State.CodeEntering).expectedCode, now + 1_000, "k")
    f.onVerified()
    f.onSaved()
    assertTrue(f.state is PairingFlow.State.Paired)

    f.reset()
    f.beginScan()
    assertEquals(PairingFlow.State.Scanning, f.state)
  }
}

// ───── PairingEntryTest.kt ─────
/**
 * **生产入口**：未配对时有没有「去配对」入口，配完之后凭据有没有真的落盘。
 *
 * ## 这条测试存在的理由
 *
 * E1b 学到过一次：组件有测试、生产没接线，测试全绿。
 * 本轮同样有风险 —— `PairingScreen` 可以单独渲染得很漂亮，
 * 但工位页上可能根本没有通往它的入口。所以这里：
 *
 * 1. 起**真实 `MainActivity`**（不自己 setContent），
 * 2. 断言界面上能**找到**「去配对」并**点得进去**，
 * 3. 走完配对流程后断言 `credentials.load()` 返回 `Ok` 且文件真的存在。
 *
 * ## 假 Keystore 是必须的
 *
 * Robolectric 没有 `AndroidKeyStore` provider（E1a 已记录），
 * 所以用 `InMemoryKeyProvider`（同一个库里的测试实现）。
 * 它替代的只有「真 Keystore 生成/取回密钥」这一个调用，
 * 加密、格式、原子写全部走真实代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h731dp-420dpi")
class PairingEntryTest {

  @get:Rule
  val compose = createAndroidComposeRule<MainActivity>()

  /** 注入假 Keystore：把 credentials 换成用内存 AES 密钥的实现 */
  private fun useInMemoryKeystore() {
    val activity = compose.activity
    // 反射不可取，改由 Activity 暴露的 open 属性实现 —— 见 MainActivity
    // 的 `secretKeyProvider` / `credentials`。测试侧通过子类化太重，
    // 这里直接用 Activity 已公开的测试入口。
    activity.installTestKeystore(InMemoryKeyProvider())
  }

  // ═══════════ ① 未配对时必须有出口 ═══════════

  @Test
  fun 未配对时工位页能找到去配对入口() {
    // 冷启动：无凭据 → Unpaired
    compose.waitForIdle()
    assertEquals(
      "前提：冷启动应为未配对",
      ConnectionPhase.Unpaired, compose.activity.relayState.connection.phase,
    )

    val hits = compose.onAllNodesWithTag(TAG_GO_PAIRING).fetchSemanticsNodes().size
    assertTrue(
      "未配对时界面上必须有「去配对」入口。" +
        "若这里为 0，说明工位页仍是死路（E1b 报告 §7.1 记的问题没修）",
      hits > 0,
    )
    // 文案也要在：光有 testTag 看不见，用户需要读得到
    assertTrue(
      "入口文案必须可读",
      compose.onAllNodesWithText("去配对", substring = true).fetchSemanticsNodes().isNotEmpty(),
    )
  }

  @Test
  fun 点去配对能进入配对页() {
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(TAG_PAIRING_SCREEN).assertIsDisplayed()
    // 配对页首屏要有可操作的东西，不能只是一句说明
    assertTrue(
      "配对页必须有粘贴入口",
      compose.onAllNodesWithTag(TAG_PAIRING_PASTE_FIELD).fetchSemanticsNodes().isNotEmpty(),
    )
  }

  // ═══════════ ② 配对走完后凭据真的落盘 ═══════════

  @Test
  fun 配对成功后凭据真的写盘且能读回() {
    useInMemoryKeystore()
    val activity = compose.activity

    // 造一张真实票据（用桌面同一套算法）
    val (qr, code) = makeRealTicket(activity)

    compose.waitForIdle()
    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()

    // 粘贴票据 → 下一步
    compose.onNodeWithTag(TAG_PAIRING_PASTE_FIELD).performTextInput(qr)
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_SUBMIT_TICKET).performClick()
    compose.waitForIdle()

    assertEquals(
      "粘贴票据后应停在「已读票据」态，实际 ${activity.pairingStateForTest()}",
      "TicketReady", activity.pairingStateForTest()::class.simpleName,
    )

    // 进入输码 → 提交正确码
    activity.pairingContinueForTest()
    compose.waitForIdle()
    assertEquals(
      "点「输入六位码」后应进入输码态，实际 ${activity.pairingStateForTest()}",
      "CodeEntering", activity.pairingStateForTest()::class.simpleName,
    )

    // 诊断：确认票据里真的读到了 relayHint（否则后面存下的地址会是 null）
    val tk = activity.lastTicketForTest()
    assertEquals(
      "票据必须解析出 relayHint，实际 ${tk?.relayHint}",
      "192.168.1.10:7788", tk?.relayHint,
    )
    assertEquals(
      "票据必须解析出 fingerprint，实际 ${tk?.desktopFingerprint}",
      "SHA256:" + "a1".repeat(32), tk?.desktopFingerprint,
    )

    // 票据是编造的，真去连 192.168.1.10 只会超时；这一步验证的是校验与写盘，
    // 所以把「桌面确认」换成直接成功。
    activity.setConfirmOutcomeForTest(PairConfirmClient.Outcome.Confirmed)
    activity.pairingSubmitCodeForTest(code)
    compose.waitForIdle()

    val st = activity.pairingStateForTest()
    assertEquals(
      "提交正确码后应已配对，实际 $st",
      "Paired", st::class.simpleName,
    )

    // 核心断言：文件存在且能读回
    val creds = activity.credentialsForTest()
    val file = java.io.File(activity.filesDir, MainActivity.CREDENTIALS_FILE)
    assertTrue(
      "配对成功后凭据文件必须存在（这是 E1a 的 save() 第一次有生产调用方）",
      file.exists(),
    )
    val loaded = creds.load()
    assertTrue(
      "load() 必须返回 Ok，实际 ${loaded::class.simpleName} " +
        (if (loaded is RelayCredentials.Load.Unreadable) "${loaded.reason} / ${loaded.detail}" else ""),
      loaded is RelayCredentials.Load.Ok,
    )
    val stored = (loaded as RelayCredentials.Load.Ok).stored
    assertEquals("deviceId 必须来自票据", "desk-e2b-0001", stored.deviceId)
    assertEquals("地址必须从 relayHint 解析出来", "192.168.1.10", stored.relayHost)
    assertEquals(7788, stored.relayPort)
    // **指纹必须原样存下来。** 这是证书固定的唯一来源：
    // 存丢了，每次连接都会拒绝桌面证书，用户看到「证书不受信任」。
    // 上一版这条断言不存在，而当时活跃的缺陷正好是「桌面票据里根本没有这个字段」。
    assertEquals(
      "桌面证书指纹必须原样保存（连接时用它做证书固定）",
      "SHA256:" + "a1".repeat(32), stored.desktopFingerprint,
    )

    // 文件里不能有明文私钥
    val bytes = file.readBytes()
    val asText = String(bytes, Charsets.ISO_8859_1)
    assertTrue(
      "凭据文件里不能出现明文私钥",
      !asText.contains(stored.privateKeyB64.take(20)),
    )
  }

  @Test
  fun 配对成功后状态从Unpaired离开() {
    // 配完对还显示「未配对」，用户会以为没成功
    useInMemoryKeystore()
    val activity = compose.activity
    val (qr, code) = makeRealTicket(activity)

    compose.waitForIdle()
    assertEquals(ConnectionPhase.Unpaired, activity.relayState.connection.phase)

    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_PASTE_FIELD).performTextInput(qr)
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_SUBMIT_TICKET).performClick()
    compose.waitForIdle()
    activity.pairingContinueForTest()
    compose.waitForIdle()
    // 同上：票据指向的地址连不上，确认这一步直接给成功。
    activity.setConfirmOutcomeForTest(PairConfirmClient.Outcome.Confirmed)
    activity.pairingSubmitCodeForTest(code)
    compose.waitForIdle()

    // controller 会被 retryNow 重新拉起 → 进入 Connecting 或已经因连不上而 Offline。
    // 两者都不是 Unpaired —— 这条断言要的是「不再显示未配对」。
    assertTrue(
      "配对后不该仍停在 Unpaired，实际 ${activity.relayState.connection.phase}",
      activity.relayState.connection.phase != ConnectionPhase.Unpaired,
    )
  }

  @Test
  fun 码错误时不写盘也不离开未配对() {
    // 反面：码错却把凭据存下来，等于「输错了也能配上」
    useInMemoryKeystore()
    val activity = compose.activity
    val (qr, _) = makeRealTicket(activity)

    compose.waitForIdle()
    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_PASTE_FIELD).performTextInput(qr)
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_SUBMIT_TICKET).performClick()
    compose.waitForIdle()
    activity.pairingContinueForTest()
    compose.waitForIdle()
    activity.pairingSubmitCodeForTest("000000")
    compose.waitForIdle()

    val file = java.io.File(activity.filesDir, MainActivity.CREDENTIALS_FILE)
    assertTrue("码错时不该生成凭据文件", !file.exists())
    assertEquals(ConnectionPhase.Unpaired, activity.relayState.connection.phase)
  }

  @Test
  fun 票据缺指纹时明确拒绝且不写盘() {
    // 桌面上一版票据里没有指纹（pairing.ts 只有六个字段）。手机侧若「照存不误」，
    // 存下的凭据 `load()` 会判 CORRUPT —— 用户看到「配对失效」而不知道原因。
    // 正确处置是当场失败并说清是电脑端的缺口。
    useInMemoryKeystore()
    val activity = compose.activity
    val (qr, code) = makeRealTicket(activity, includeFingerprint = false)

    compose.waitForIdle()
    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_PASTE_FIELD).performTextInput(qr)
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_SUBMIT_TICKET).performClick()
    compose.waitForIdle()

    assertEquals(
      "缺指纹时票据仍应解析成功（缺字段是保存阶段才拒绝）",
      "TicketReady", activity.pairingStateForTest()::class.simpleName,
    )
    assertNull("缺指纹的票据不该给出指纹", activity.lastTicketForTest()?.desktopFingerprint)

    activity.pairingContinueForTest()
    compose.waitForIdle()
    activity.pairingSubmitCodeForTest(code)
    compose.waitForIdle()

    val st = activity.pairingStateForTest()
    assertEquals("必须停在失败态而不是假装配对成功，实际 $st", "Failed", st::class.simpleName)
    val file = java.io.File(activity.filesDir, MainActivity.CREDENTIALS_FILE)
    assertTrue("缺指纹时绝不能写盘（写下的文件自己读不回来）", !file.exists())
    assertEquals(ConnectionPhase.Unpaired, activity.relayState.connection.phase)
  }

  @Test
  fun 指纹格式非法时明确拒绝且不写盘() {
    // 「字段存在」不等于「值可用」。存一个解析不出来的指纹，
    // `load()` 会成功，但每次连接都判「证书不受信任」——
    // 排查方向会完全指向网络/证书，而真实原因是配对时存了垃圾。
    useInMemoryKeystore()
    val activity = compose.activity
    val (qr, code) = makeRealTicket(activity, fingerprintOverride = "SHA256:not-a-real-digest")

    compose.waitForIdle()
    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_PASTE_FIELD).performTextInput(qr)
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_SUBMIT_TICKET).performClick()
    compose.waitForIdle()
    activity.pairingContinueForTest()
    compose.waitForIdle()
    activity.pairingSubmitCodeForTest(code)
    compose.waitForIdle()

    assertEquals(
      "格式非法的指纹必须拒绝保存，实际 ${activity.pairingStateForTest()}",
      "Failed", activity.pairingStateForTest()::class.simpleName,
    )
    val file = java.io.File(activity.filesDir, MainActivity.CREDENTIALS_FILE)
    assertTrue("不写盘", !file.exists())
  }

  @Test
  fun 桌面base64url格式的真指纹能被接受并落盘() {
    // 端到端把「桌面真实格式」走一遍：43 字符、无填充、含 _。
    // 这条与 TlsFingerprintVectorTest 的分工：那个测算法一致，
    // 这个测「这串值走到存储层不会被格式校验挡掉」。
    useInMemoryKeystore()
    val activity = compose.activity
    val realFormat = "SHA256:tYhp-6S_bSXQwwvLSU_UtzCJTBYcG2Ow6uST_Dadcjc"
    val (qr, code) = makeRealTicket(activity, fingerprintOverride = realFormat)

    compose.waitForIdle()
    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_PASTE_FIELD).performTextInput(qr)
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_PAIRING_SUBMIT_TICKET).performClick()
    compose.waitForIdle()
    activity.pairingContinueForTest()
    compose.waitForIdle()
    activity.setConfirmOutcomeForTest(PairConfirmClient.Outcome.Confirmed)
    activity.pairingSubmitCodeForTest(code)
    compose.waitForIdle()

    val st = activity.pairingStateForTest()
    assertEquals("桌面真实格式必须能完成配对，实际 $st", "Paired", st::class.simpleName)
    val loaded = activity.credentialsForTest().load()
    assertTrue("凭据必须能读回", loaded is RelayCredentials.Load.Ok)
    assertEquals(
      "指纹必须原样存下（含 base64url 专有字符）",
      realFormat, (loaded as RelayCredentials.Load.Ok).stored.desktopFingerprint,
    )
  }

  // ─────────────────────── 造真实票据 ───────────────────────

  /**
   * 用桌面同一套算法造票据，返回 (二维码文本, 六位码)。
   *
   * 注意：这里手上有手机私钥，所以直接算六位码 —— 真机上是用户从电脑屏幕上
   * 抄下来的。测试不可能「看屏幕」，只能自己算，这一点在报告里写清。
   *
   * @param includeFingerprint 是否带证书指纹。桌面 E2c 之前**不带**，
   *   所以「不带」这条路径必须能单独构造出来测。
   * @param fingerprintOverride 用指定的指纹值（默认 `SHA256:` + 64 个 a1）
   */
  private fun makeRealTicket(
    activity: MainActivity,
    includeFingerprint: Boolean = true,
    fingerprintOverride: String? = null,
  ): Pair<String, String> {
    // 关键：票据里的 pubKey 必须是**这台手机的**公钥。
    // 第一版测试自己生成了一对密钥，与 Activity 的 pendingIdentity 不同 →
    // 两端算出的共享秘密不同 → 六位码永远对不上，表现为「码不匹配」。
    // 这不是测试写错的细节，而是真实协议里的顺序要求：
    // 手机先有密钥对，桌面拿它的公钥去签票据。
    val phonePub = activity.pendingIdentityForTest().publicKeyB64

    val desktopKp = KeyPairGenerator.getInstance("EC").apply {
      initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    val desktopPub = EcdhPairing.encodeUncompressed(desktopKp.public as ECPublicKey)

    val nonce = "e2bcafe0"
    val exp = System.currentTimeMillis() + 90_000
    // 拼字段：指纹可选（桌面 E2c 之前不带），带的时候允许指定具体值。
    // 用列表拼接而不是字符串模板，是为了「不带」时**字段真的不出现**，
    // 而不是出现一个空串 —— 空串会被 parseTicket 当成合法字符串，
    // 手机侧拿到它做比对永远失败，报错却是「指纹不匹配」。
    val fields = mutableListOf(
      """"deviceId":"desk-e2b-0001"""",
      """"pubKey":"$phonePub"""",
      """"desktopPub":"$desktopPub"""",
      """"nonce":"$nonce"""",
      """"exp":"$exp"""",
      """"relayHint":"192.168.1.10:7788"""",
    )
    if (includeFingerprint) {
      val fp = fingerprintOverride ?: ("SHA256:" + "a1".repeat(32))
      fields += """"fingerprint":"$fp""""
    }
    val json = "{" + fields.joinToString(",") + "}"
    val qr = Base64.getUrlEncoder().withoutPadding()
      .encodeToString(json.toByteArray(Charsets.UTF_8))

    // 手机侧由 Activity 自己算（见 onPairingCode），这里给出「应该输的值」：
    // 用桌面私钥 + 手机公钥算共享秘密 → 同一个 HMAC → 同一个六位码
    val kdf = javax.crypto.KeyAgreement.getInstance("ECDH")
    kdf.init(desktopKp.private)
    kdf.doPhase(EcdhPairing.decodePublicKey(phonePub), true)
    val shared = kdf.generateSecret()
    val code = EcdhPairing.pairingCode(shared, nonce)

    return qr to code
  }
}

// ───── PairingRequestTest.kt ─────
/**
 * **配对请求**：手机能不能把公钥交出去，桌面能不能照着签出一张真正能配上的票。
 *
 * ## 这条测试存在的理由
 *
 * E2c 之后桌面票据里有指纹了，但桌面**签票需要手机公钥**：
 * - 票据里的 `pubKey` 被手机侧校验（不符就报「这张配对码是给另一台手机的」）；
 * - 六位码是 `HMAC(ECDH(手机私钥, 桌面公钥))`，桌面算的时候也要手机公钥。
 *
 * 如果没有「把公钥交给电脑」这一步，用户在电脑上根本点不出配对码 ——
 * 而这件事在纯逻辑测试里看不出来：`PairRequest.encode` 可以写得很正确，
 * 但页面上没有那一段、或者点了不复制，真机链路一样断在第一环。
 *
 * ## 为什么要断言「两次复制是同一串」
 *
 * 公钥变了，桌面算出的六位码就变了。用户通常会「复制 → 去电脑 → 发现填错了 →
 * 回来再复制一次」，如果第二次拿到的是新密钥，桌面上那张票立刻作废，
 * 而界面只会显得「六位码一直不对」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h731dp-420dpi")
class PairingRequestTest {

  @get:Rule
  val compose = createAndroidComposeRule<MainActivity>()

  // ═══════════ ① 页面上真的有「复制配对请求」 ═══════════

  @Test
  fun 配对页首屏给出可复制的配对请求() {
    compose.waitForIdle()
    assertTrue(
      "前提：冷启动应有「去配对」入口",
      compose.onAllNodesWithTag(TAG_GO_PAIRING).fetchSemanticsNodes().isNotEmpty(),
    )
    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(TAG_PAIRING_SCREEN).assertIsDisplayed()

    // 只有按钮不够 —— 用户需要看到自己能复制的到底是什么
    assertTrue(
      "页面上要显示配对请求原文（以 MORROWPAIR1. 开头）",
      compose.onAllNodesWithText(PairRequest.PREFIX, substring = true).fetchSemanticsNodes().isNotEmpty(),
    )
    // 同时粘贴框也要在（两步都在同一屏，不要求用户来回切页）
    assertTrue(
      "粘贴配对码的输入框必须同时存在",
      compose.onAllNodesWithTag(TAG_PAIRING_PASTE_FIELD).fetchSemanticsNodes().isNotEmpty(),
    )
  }

  @Test
  fun 点复制按钮真的写进系统剪贴板() {
    // 只断言「按钮存在」是不够的：onCopyPairRequest 传空实现也能通过。
    // 这里读真实剪贴板 —— 它是用户实际会去粘贴的那一份内容。
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()

    setClipboard(compose.activity, "")
    compose.onNodeWithTag(TAG_PAIRING_COPY_REQUEST).performClick()
    compose.waitForIdle()

    val copied = readClipboard(compose.activity)
    assertTrue(
      "复制后剪贴板里应是配对请求，实际：${copied.take(40)}",
      copied.startsWith(PairRequest.PREFIX),
    )
    // 内容必须是可解析的（而不只是前缀对）
    val obj = EcdhPairing.JsonLite.parseObject(decodePayload(copied)) ?: error("配对请求内容解不开：$copied")
    assertTrue("pubKey 不能为空", (obj["pubKey"] ?: "").isNotBlank())
  }

  // ═══════════ ② 交给桌面的公钥 == 票据校验用的公钥 ═══════════

  @Test
  fun 配对请求里的公钥与本机待配对身份一致() {
    // 这是整条链路的关键不变量：桌面照这个公钥签票，手机拿票据回来
    // 会校验 `ticket.pubKey == identity.publicKeyB64`。两者只要有一处
    // 走的是另一个身份，用户就会看到「这张配对码是给另一台手机的」，
    // 而他的操作完全正确。
    compose.waitForIdle()
    val activity = compose.activity
    val identity = activity.pendingIdentityForTest()

    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()
    setClipboard(activity, "")
    compose.onNodeWithTag(TAG_PAIRING_COPY_REQUEST).performClick()
    compose.waitForIdle()

    val payload = decodePayload(readClipboard(activity))
    val obj = EcdhPairing.JsonLite.parseObject(payload) ?: error("解不开：$payload")
    assertEquals(
      "配对请求里的公钥必须就是本机待配对身份的公钥",
      identity.publicKeyB64,
      obj["pubKey"],
    )
  }

  @Test
  fun 重复复制得到同一串字符串() {
    // 公钥变了桌面算出的六位码就变了。用户来回复制两次是常态，
    // 第二次拿到新密钥会让桌面上那张票静默作废。
    compose.waitForIdle()
    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()

    setClipboard(compose.activity, "")
    compose.onNodeWithTag(TAG_PAIRING_COPY_REQUEST).performClick()
    compose.waitForIdle()
    val first = readClipboard(compose.activity)

    setClipboard(compose.activity, "")
    compose.onNodeWithTag(TAG_PAIRING_COPY_REQUEST).performClick()
    compose.waitForIdle()
    val second = readClipboard(compose.activity)

    assertEquals("两次复制必须是同一串（公钥不能变）", first, second)
    assertTrue(first.isNotEmpty())
  }

  @Test
  fun 取消配对后再次进入复制到的是新身份而不是旧公钥() {
    // 取消配对会把 `pendingIdentity` 置 null（下次会生成新密钥对）。
    // 若页面上的请求文本还留着**上一把**钥匙，用户复制给电脑后，
    // 电脑照旧文本签票、手机用新身份校验 → 报「这张配对码是给另一台手机的」，
    // 而用户每一步都做对了。这是本轮实际修掉的一处缺陷：
    // `syncPairingState` 原来的条件是 `pairRequestState == null` 才刷新，
    // 而取消后它不是 null，于是永远不刷新。
    compose.waitForIdle()
    val activity = compose.activity

    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()
    setClipboard(activity, "")
    compose.onNodeWithTag(TAG_PAIRING_COPY_REQUEST).performClick()
    compose.waitForIdle()
    val before = readClipboard(activity)

    // 取消 → 身份被丢弃
    compose.onNodeWithTag(TAG_PAIRING_CANCEL).performClick()
    compose.waitForIdle()

    // 再进配对页，复制到的必须是**新的**那串
    compose.onNodeWithTag(TAG_GO_PAIRING).performClick()
    compose.waitForIdle()
    setClipboard(activity, "")
    compose.onNodeWithTag(TAG_PAIRING_COPY_REQUEST).performClick()
    compose.waitForIdle()
    val after = readClipboard(activity)

    assertTrue("两次都应有内容", before.isNotEmpty() && after.isNotEmpty())
    assertNotEquals("取消配对后请求文本必须换新（否则电脑签出的票永远对不上）", before, after)

    // 而且新文本里的公钥必须是当前 pendingIdentity 的
    val obj = EcdhPairing.JsonLite.parseObject(decodePayload(after)) ?: error("解不开：$after")
    assertEquals(
      "复制到的公钥必须与当前待配对身份一致",
      activity.pendingIdentityForTest().publicKeyB64,
      obj["pubKey"],
    )
  }

  // ═══════════ ③ 编码本身：与桌面解码器能对上 ═══════════

  @Test
  fun 编码结果符合桌面解析器要求的前缀与字段() {
    // 两端各写一份编码器最容易漂移，所以解码只在桌面写一份。
    // 这里断言手机侧产出的形状确实是桌面 `parsePairRequest` 认的那种。
    val identity = EcdhPairing.generateIdentity()
    val text = PairRequest.encode(deviceId = "phone-abc", publicKeyB64 = identity.publicKeyB64)

    assertTrue("必须以 MORROWPAIR1. 开头，实际：${text.take(20)}", text.startsWith(PairRequest.PREFIX))
    val payload = text.removePrefix(PairRequest.PREFIX)
    // base64url 不能有 + / 与填充（桌面的正则不接受填充）
    assertTrue("payload 必须是 base64url（不含 +/ 与 =）", !payload.any { it == '+' || it == '/' || it == '=' })

    val obj = EcdhPairing.JsonLite.parseObject(decodePayload(text)) ?: error("解不开：$text")
    assertEquals("phone-abc", obj["deviceId"])
    assertEquals(identity.publicKeyB64, obj["pubKey"])
  }

  @Test
  fun 未给deviceId时从公钥派生且稳定() {
    // 不用随机 UUID：随机值每次点「复制」都不同，桌面会看到「又一台新手机」，
    // 已配对的旧条目永远留在列表里。
    val identity = EcdhPairing.generateIdentity()
    val a = PairRequest.encode(deviceId = null, publicKeyB64 = identity.publicKeyB64)
    val b = PairRequest.encode(deviceId = null, publicKeyB64 = identity.publicKeyB64)
    assertEquals("同一公钥必须派生出同一个 deviceId", a, b)

    val idA = EcdhPairing.JsonLite.parseObject(decodePayload(a))?.get("deviceId")
    assertTrue("deviceId 不能为空", !idA.isNullOrBlank())

    // 不同公钥 → 不同 deviceId（否则桌面会把两台手机认成同一台）
    val other = EcdhPairing.generateIdentity()
    val c = PairRequest.encode(deviceId = null, publicKeyB64 = other.publicKeyB64)
    val idC = EcdhPairing.JsonLite.parseObject(decodePayload(c))?.get("deviceId")
    assertNotEquals(idA, idC)
  }

  @Test
  fun deviceId里的特殊字符不会破坏JSON() {
    // deviceId 来自调用方，不能假设它永远干净。引号或反斜杠会让整段
    // JSON 无效，而错误会表现为「复制不完整」—— 排查时指不到真正原因。
    val identity = EcdhPairing.generateIdentity()
    val nasty = "a\"b\\c\nd"
    val text = PairRequest.encode(deviceId = nasty, publicKeyB64 = identity.publicKeyB64)
    val obj = EcdhPairing.JsonLite.parseObject(decodePayload(text))
      ?: error("含特殊字符的 deviceId 让 JSON 失效了：$text")
    assertEquals(nasty, obj["deviceId"])
    assertEquals(identity.publicKeyB64, obj["pubKey"])
  }

  @Test
  fun 前缀与桌面必须逐字一致() {
    // 两端各有一份常量。写死字面量在这里断言，任一侧改动都会红。
    assertEquals("MORROWPAIR1.", PairRequest.PREFIX)
  }

  // ─────────── 辅助 ───────────

  private fun decodePayload(text: String): String {
    val b64 = text.removePrefix(PairRequest.PREFIX)
    val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
    return String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
  }

  private fun setClipboard(activity: MainActivity, text: String) {
    val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText("test", text))
  }

  private fun readClipboard(activity: MainActivity): String {
    val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(activity).toString()
  }
}

// ───── CrossDeviceE2ETest.kt ─────
/**
 * **跨语言**端到端：手机 Kotlin 客户端 ↔ 桌面 Node `DirectChannel`。
 *
 * ## 为什么这一层不能省
 *
 * `RelaySocketEndToEndTest` 里的「桌面」是 Kotlin 写的假服务端，与客户端
 * **共用** `WebSocketFrames` / `SecureChannel` / `RelayProtocol`。两端同一份实现，
 * 任何一处写错都是同时写错 —— 那 10 项全绿也说明不了「能和真的桌面通话」。
 *
 * 这里换掉一端：服务端由 `scripts/e3-desktop-relay.mjs` 用**桌面仓库的真实代码**
 * （`PairingService` + `loadOrCreateTls` + `DirectChannel`）在 Node 里跑起来。
 * 两种语言、两份独立实现，只有协议逐字节对齐才会通。
 *
 * ## 覆盖的四条（E3 卡验收）
 *
 * | # | 断言 | 要证的事 |
 * |---|---|---|
 * | 1 | 票据解析 → 六位码相等 → sessionKey 逐字节相等 | ECDH + HMAC 跨语言一致 |
 * | 2 | TLS 握手建立、welcome 到达、连接的是**非回环**地址 | 「连上了」不是「发起了连接」 |
 * | 3 | 指纹改一位 → 明确拒绝（且报的是指纹不匹配，不是笼统 TLS 错） | 证书固定真的生效 |
 * | 4 | 4401 → `shouldRetry == false` 且**不重连** | 未授权不被当成网络抖动 |
 * | 5 | 桌面 `usage_updated` 推过来 → 手机 C1 的 tokens 变化 | 事件真的穿过了 socket |
 *
 * ## 已知的边界（报告里必须重复一遍）
 *
 * 桌面这一侧是 Node 进程，不是 Electron 应用本体：真实桌面还要经过
 * `startLanStack()` 的开关判断。本测试证明的是 **`DirectChannel` 这一层**
 * 与手机互通 —— 它是链路上最长的一段，但不是全部。
 */
@RunWith(RobolectricTestRunner::class)
class CrossDeviceE2ETest {

  private val cleanups = mutableListOf<() -> Unit>()

  @After
  fun tearDown() {
    cleanups.forEach { runCatching { it() } }
    cleanups.clear()
  }

  // ─────────────────────── 启动真桌面 ───────────────────────

  /**
   * 一个跑着的桌面进程。
   *
   * 只暴露测试真正需要的东西：连上它、让它推事件、看它收到过哪些帧。
   * 内部细节（端口、票据）都从 `E3_READY` 那一行拿 —— 不从脚本参数推，
   * 因为「桌面实际用了哪个端口」只有它自己知道。
   */
  private inner class Desktop(
    val phoneIdentity: EcdhPairing.Identity,
  ) {
    private val proc: Process
    private val reader: BufferedReader
    /** 桌面 stdout 的**唯一**消费队列（见 init 里的单读线程说明） */
    private val lines = LinkedBlockingQueue<String>()
    /** 读到但还没人认领的行（换个 prefix 等时不能丢） */
    private val pending = mutableListOf<String>()
    private var stdoutThread: Thread? = null
    val host: String
    val port: Int
    val fingerprint: String
    val deviceId: String
    val qr: String
    val desktopCode: String
    val desktopSessionKey: String
    val loopback: Boolean

    init {
      val script = File(repoRoot(), "scripts/e3-desktop-relay.mjs")
      check(script.isFile) { "缺少 ${script.absolutePath}" }
      val pb = ProcessBuilder(nodeExecutable(), script.absolutePath)
      pb.redirectErrorStream(true)
      pb.environment()["E3_PHONE_PUB"] = phoneIdentity.publicKeyB64
      proc = pb.start()
      reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
      cleanups += { proc.destroyForcibly() }

      // **单读线程 + 队列**，不用「每次 awaitLine 起一个 readLine 线程」：
      // 两个线程同时 `readLine()` 会在同一个 `BufferedReader` 上抢行，
      // 表现为「第一行读到了，后面 push 的响应永远等不到」然后挂到超时。
      stdoutThread = Thread({
        try {
          while (true) {
            val l = reader.readLine() ?: break
            lines.offer(l)
          }
        } catch (_: Throwable) {
        }
      }, "desktop-stdout").apply { isDaemon = true; start() }

      val line = awaitLine(prefix = "E3_READY ", timeoutMs = 60_000)
        ?: error("桌面没起来。脚本输出：\n${drainAvailable()}")
      val obj = Json.parseToJsonElement(line.removePrefix("E3_READY ")) as JsonObject
      host = obj.str("host")
      port = obj.str("port").toInt()
      fingerprint = obj.str("fingerprint")
      deviceId = obj.str("deviceId")
      qr = obj.str("qr")
      desktopCode = obj.str("code")
      // sessionKey 不在 E3_READY 里：设备表要等手机发完 pair-confirm 才写入。
      // 这里直接取票据自己算一份「手机侧」的值；桌面设备表里存的那一份
      // 由脚本在确认后通过 E3_DEVICE_PAIRED 报回，两者必须逐字节相同。
      desktopSessionKey = EcdhPairing.pair(
        EcdhPairing.parseTicket(qr, System.currentTimeMillis()),
        phoneIdentity,
      ).sessionKey
      loopback = host == "127.0.0.1" || host == "localhost"
    }

    /**
     * 走真实的配对确认，拿回桌面设备表里存的 sessionKey。
     *
     * 这一步以前是脚本在内部直接 `completePairing()` 作弊完成的 ——
     * 那样跨语言 E2E 永远测不到「手机发的 pair-confirm 能不能被真桌面接住」。
     */
    fun confirmPairing(): String? {
      val nonce = EcdhPairing.parseTicket(qr, System.currentTimeMillis()).nonce
      val outcome = PairConfirmClient.confirm(
        host = host,
        port = port,
        expectedFingerprint = fingerprint,
        nonce = nonce,
        expectedDeviceId = deviceId,
      )
      check(outcome is PairConfirmClient.Outcome.Confirmed) { "配对确认失败：$outcome" }
      return pairedSessionKeyOrNull(timeoutMs = 30_000L)
    }

    /**
     * 等桌面报出设备表里的 sessionKey；没有设备时返回 null。
     *
     * 用于「拒绝之后不该留下设备」这类断言：脚本会在设备真的进表时立刻
     * 报 `E3_DEVICE_PAIRED {sessionKey}`，否则等满超时报 `null`。
     */
    fun pairedSessionKeyOrNull(timeoutMs: Long): String? {
      val line = awaitLine(prefix = "E3_DEVICE_PAIRED ", timeoutMs = timeoutMs)
        ?: return null
      val raw = line.removePrefix("E3_DEVICE_PAIRED ").trim()
      if (raw == "null") return null
      val obj = Json.parseToJsonElement(raw) as JsonObject
      return obj.str("sessionKey")
    }

    /** 让桌面 broadcast 一条事件，等它回 `E3_PUSHED <seq>` */
    fun push(bodyJson: String): Long {
      proc.outputStream.write(("PUSH $bodyJson\n").toByteArray(Charsets.UTF_8))
      proc.outputStream.flush()
      val line = awaitLine("E3_PUSHED ", timeoutMs = 15_000)
        ?: error("桌面没有回 E3_PUSHED：\n${drainAvailable()}")
      return line.removePrefix("E3_PUSHED ").trim().toLong()
    }

    fun quit() {
      runCatching {
        proc.outputStream.write("QUIT\n".toByteArray(Charsets.UTF_8))
        proc.outputStream.flush()
      }
    }

    /**
     * 读到以 [prefix] 开头的一行。
     *
     * **不能每次调用都起一个 `readLine()` 线程**：两个线程在同一个
     * `BufferedReader` 上抢行，会丢行 —— 症状是「第一行（E3_READY）读到了，
     * 后面 push 的响应永远等不到」，然后整类挂到超时。
     * 所以读只有一个线程（init 里起），这里只消费队列。
     *
     * 也不能用 `Process.waitFor(timeout)`：那等的是「进程退出」，
     * 而这里要等的是「进程活着并吐了一行」。
     */
    private fun awaitLine(prefix: String, timeoutMs: Long): String? {
      val hit = pending.indexOfFirst { it.startsWith(prefix) }
      if (hit >= 0) return pending.removeAt(hit)

      val until = System.currentTimeMillis() + timeoutMs
      while (System.currentTimeMillis() < until) {
        val l = lines.poll(50, TimeUnit.MILLISECONDS) ?: continue
        if (l.startsWith(prefix)) return l
        pending += l
      }
      return null
    }

    private fun drainAvailable(): String {
      val sb = StringBuilder()
      pending.forEach { sb.append(it).append('\n') }
      var l = lines.poll()
      while (l != null) {
        sb.append(l).append('\n')
        l = lines.poll()
      }
      return sb.toString().ifEmpty { "(无输出)" }
    }
  }

  /** 与 `verify*.mjs` 的写法一致：从测试脚本的位置往上找仓库根 */
  private fun repoRoot(): File {
    var d = File(System.getProperty("user.dir") ?: ".").absoluteFile
    repeat(4) {
      if (File(d, "scripts/e3-desktop-relay.mjs").isFile) return d
      d = d.parentFile ?: return@repeat
    }
    // 本工程的 JVM 测试 cwd 是 `app/`，所以根是它的上一级
    return File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
  }

  /** node 从 PATH 找；找不到时给出可读的失败原因，而不是 NotSuchFile() */
  private fun nodeExecutable(): String {
    val fromEnv = System.getenv("MORROW_NODE")
    if (!fromEnv.isNullOrBlank()) return fromEnv
    val exe = if (System.getProperty("os.name").lowercase().contains("win")) "node.exe" else "node"
    val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    for (dir in path) {
      val f = File(dir, exe)
      if (f.isFile) return f.absolutePath
    }
    return "node"
  }

  private fun JsonObject.str(key: String): String =
    (this[key] ?: error("E3_READY 缺字段 $key")).jsonPrimitive.content

  // ─────────────────────── 客户端收集器 ───────────────────────

  private class Collector : RelaySocketClient.Listener {
    val ready = LinkedBlockingQueue<RelayProtocol.Welcome>(1)
    val events = LinkedBlockingQueue<String>()
    val closed = LinkedBlockingQueue<RelayProtocol.DisconnectInfo>(1)
    val closedLatch = CountDownLatch(1)

    override fun onReady(welcome: RelayProtocol.Welcome) {
      ready.offer(welcome)
    }

    override fun onEvent(json: String) {
      events.offer(json)
    }

    override fun onClosed(info: RelayProtocol.DisconnectInfo) {
      closed.offer(info)
      closedLatch.countDown()
    }

    fun awaitReady(ms: Long = 15_000): RelayProtocol.Welcome? = ready.poll(ms, TimeUnit.MILLISECONDS)
    fun awaitEvent(ms: Long = 15_000): String? = events.poll(ms, TimeUnit.MILLISECONDS)
    fun awaitClosed(ms: Long = 20_000): RelayProtocol.DisconnectInfo? {
      closedLatch.await(ms, TimeUnit.MILLISECONDS)
      return closed.peek()
    }
  }

  private fun connect(
    desktop: Desktop,
    sessionKey: String = "",
    fingerprint: String = desktop.fingerprint,
    deviceId: String = desktop.deviceId,
  ): Pair<Collector, RelaySocketClient> {
    val c = Collector()
    val client = RelaySocketClient(
      host = desktop.host,
      port = desktop.port,
      deviceId = deviceId,
      sessionKeyBase64 = sessionKey.ifEmpty { desktop.desktopSessionKey },
      expectedFingerprint = fingerprint,
      listener = c,
      readTimeoutMs = 15_000,
    )
    val t = Thread({ client.runBlockingLoop() }, "relay-client")
    t.isDaemon = true
    t.start()
    cleanups += { client.close() }
    return c to client
  }

  // ══════════════════════ 1. 配对链跨语言一致 ══════════════════════

  @Test
  fun 手机解析桌面票据后算出的六位码与会话密钥与桌面完全一致() {
    val identity = EcdhPairing.generateIdentity()
    val desktop = Desktop(identity)
    try {
      // 票据是**桌面真签发的**（含真实证书指纹与真实 relayHint），
      // 手机侧走 EcdhPairing.parseTicket 的完整严格解析。
      val ticket = EcdhPairing.parseTicket(desktop.qr, System.currentTimeMillis())

      assertEquals("票据里的 pubKey 必须是我自己的公钥", identity.publicKeyB64, ticket.pubKey)
      assertEquals(desktop.deviceId, ticket.deviceId)
      assertEquals("桌面签发票据时注入了真实证书指纹", desktop.fingerprint, ticket.desktopFingerprint)

      // relayHint 能被手机侧解析成真实地址（不是 `host:0` 那种占位）
      val hint = RelayHint.parse(ticket.relayHint)
      assertNotNull("票据必须带可解析的 relayHint：${ticket.relayHint}", hint)
      assertEquals(desktop.host, hint!!.host)
      assertEquals(desktop.port, hint.port)

      // 两端各算一遍：桌面用私钥+手机公钥，手机用私钥+桌面公钥
      val result = EcdhPairing.pair(ticket, identity)
      assertEquals("六位码两端必须相同（不同即中间人）", desktop.desktopCode, result.code)

      // 关键一步：手机把 nonce 报给桌面，桌面才把设备写进表。
      // 拿到的是桌面设备表里**真实存下**的 sessionKey，
      // 与手机自己算的必须逐字节相同 —— 否则后面所有帧都解不开。
      val desktopStored = desktop.confirmPairing()
      assertNotNull("桌面必须确认这次配对（设备表为空说明 pair-confirm 没生效）", desktopStored)
      assertEquals(
        "sessionKey 必须逐字节相同 —— 不同则后面所有帧都解不开",
        desktopStored,
        result.sessionKey,
      )
    } finally {
      desktop.quit()
    }
  }

  // ══════════════════════ 2. 连接真的建立 ══════════════════════

  @Test
  fun 连接建立到非回环地址并收到welcome() {
    val identity = EcdhPairing.generateIdentity()
    val desktop = Desktop(identity)
    try {
      val ticket = EcdhPairing.parseTicket(desktop.qr, System.currentTimeMillis())
      val pair = EcdhPairing.pair(ticket, identity)
      // 桌面必须先认识这台设备，否则 hello 会被 4401 拒 ——
      // 这一步是真实链路的顺序，不能为了测试方便跳过。
      assertEquals("桌面存的 sessionKey 必须与手机算的一致", pair.sessionKey, desktop.confirmPairing())

      // 这条不是形式主义：手机永远不是 127.0.0.1。若 DirectChannel bind 到回环，
      // 手机在真机上就永远连不上，而回环测试会全绿。
      assertFalse("桌面必须监听在非回环地址（实际 ${desktop.host}）", desktop.loopback)

      val (c, _) = connect(desktop, sessionKey = pair.sessionKey)
      val welcome = c.awaitReady()
      assertNotNull("必须真正收到 welcome（不是「发起了连接」）", welcome)
      assertEquals(desktop.deviceId, welcome!!.deviceId)
      assertEquals("welcome 里的指纹必须与票据一致", ticket.desktopFingerprint, welcome.fingerprint)

      // 冷启动时桌面缓冲为空 → 它**如实**回 snapshot_required，
      // 而不是 replayCount=0 假装「没有要补的」。这条是协议语义的一部分。
      assertTrue(
        "空缓冲时应是 snapshotRequired（实际 replayCount=${welcome.replayCount}）",
        welcome.snapshotRequired,
      )
    } finally {
      desktop.quit()
    }
  }

  // ══════════════════════ 3. 指纹固定生效 ══════════════════════

  @Test
  fun 指纹改一位时连接被拒且原因是证书指纹不匹配() {
    val identity = EcdhPairing.generateIdentity()
    val desktop = Desktop(identity)
    try {
      val ticket = EcdhPairing.parseTicket(desktop.qr, System.currentTimeMillis())
      val pair = EcdhPairing.pair(ticket, identity)
      // 先把设备配上：本测试要验的是**指纹**（TLS 层），
      // 不是未配对（应用层）。不配的话 TLS 就断了，测不到指纹那一层。
      assertEquals(pair.sessionKey, desktop.confirmPairing())

      // 改**首位**而不是末位：base64url 的末位只有 2 个有效位，
      // 换一个末位字符可能解出完全相同的 32 字节（A/B 就是同一组），
      // 那样服务端会正常握手，测的就不是「指纹不匹配」。
      // 首位 6 位全部有效，改动一定改变摘要；同时保持形状合法。
      val fp = desktop.fingerprint
      val flipped = (if (fp.first() == 'A') 'B' else 'A') + fp.drop(1)
      assertEquals("形状必须仍然合法，只是值不同", fp.length, flipped.length)
      assertTrue("仍然能解析成 32 字节", RelaySocketClient.SSLSocketFactoryFactory.canParseFingerprint(flipped))
      assertFalse(
        "改动后的指纹归一化后必须真的不同（否则测不到指纹比对）",
        RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals(flipped, fp),
      )

      val (c, client) = connect(desktop, sessionKey = pair.sessionKey, fingerprint = flipped)
      val info = c.awaitClosed()
      assertNotNull("指纹不匹配必须结束连接", info)
      assertEquals(
        "必须归到 UNAUTHORIZED（可重连的话等于给中间人反复试的机会）",
        RelayProtocol.Disconnect.UNAUTHORIZED,
        info!!.kind,
      )
      assertFalse("不可重连", info.shouldRetry)
      assertTrue(
        "错误信息要能指向真正的原因（实际：${info.reason}）",
        info.reason.contains("指纹") || info.reason.contains("证书"),
      )
      // 诊断信息：用户能拿实际指纹去和电脑上显示的那串对照。
      //
      // **这里不能直接比字符串**：手机记录的是 `SHA256:<base64url>`，
      // 而桌面票据里是裸 base64url。信任判定由 `fingerprintEquals`
      // 归一化完成，所以「这两串指的是同一张证书」才是要断言的事实 ——
      // 直接比字符串会把一个正确的实现判成错。
      val observed = client.observedFingerprint
      assertNotNull("必须记录实际观察到的指纹供对照", observed)
      assertTrue(
        "实际指纹必须与票据里那串归一化后相等（实际 $observed / 票据 ${desktop.fingerprint}）",
        RelaySocketClient.SSLSocketFactoryFactory.fingerprintEquals(observed!!, desktop.fingerprint),
      )
      // 附带一条**记录现状**的事实（不是要求）：两种书写形式不同。
      // 目前桌面没有任何界面展示这个值，所以用户还不会被误导；
      // 一旦桌面加上「和手机对照」的显示，必须用带 `SHA256:` 的形式，
      // 否则同一张证书在两台设备上显示成两个不同的字符串。
      // 若将来这条断言变红（说明有人统一了书写形式），那是好事：
      // 正确做法是删掉它，而不是放宽。
      assertEquals("现状：手机带 SHA256: 前缀，桌面票据里是裸串", "SHA256:" + desktop.fingerprint, observed)
      // 而且它根本没走到应用层 —— welcome 不该到达
      assertEquals(null, c.awaitReady(500))
    } finally {
      desktop.quit()
    }
  }

  // ══════════════════════ 4. 4401 不重连 ══════════════════════

  @Test
  fun hello验签失败时桌面回4401且手机不再重连() {
    val identity = EcdhPairing.generateIdentity()
    val desktop = Desktop(identity)
    try {
      val ticket = EcdhPairing.parseTicket(desktop.qr, System.currentTimeMillis())
      val good = EcdhPairing.pair(ticket, identity)
      // 先正常配上：本测试要验的是「用错 key 时被 4401 拒」，
      // 前提是这台设备本来就在桌面的设备表里。
      assertEquals(good.sessionKey, desktop.confirmPairing())

      // 换一把 sessionKey：TLS 与 WebSocket 握手都会成功，
      // 只有 hello 的 HMAC 对不上 —— 这正是「密码错」而不是「网络断」。
      val wrong = EcdhPairing.sessionKey(ByteArray(32) { 7 })
      val (c, _) = connect(desktop, sessionKey = wrong)
      val info = c.awaitClosed()
      assertNotNull(info)
      assertEquals(RelayProtocol.Disconnect.UNAUTHORIZED, info!!.kind)
      assertFalse(info.shouldRetry)
      // 桌面源码里 close 的 reason 是 'unauthorized'
      assertTrue("若是 4401，不该被归成网络错误（实际 ${info.reason}）", info.kind != RelayProtocol.Disconnect.RETRYABLE)

      // 换正确的 key 再连一次：证明上面失败的原因是 key 而不是服务坏了。
      // 不做这一步的话，一个「桌面根本没起来」也会让上面几条通过。
      val (c2, _) = connect(desktop, sessionKey = good.sessionKey)
      assertNotNull("正确 key 必须能连上", c2.awaitReady())
    } finally {
      desktop.quit()
    }
  }

  @Test
  fun 编排层在收到4401后不重试() {
    val identity = EcdhPairing.generateIdentity()
    val desktop = Desktop(identity)
    try {
      val ticket = EcdhPairing.parseTicket(desktop.qr, System.currentTimeMillis())
      // 先正常配上：否则 4401 的原因是「设备不在表里」，
      // 而本项要验的是「key 不对 → 不重试」。
      desktop.confirmPairing()
      val wrongKey = EcdhPairing.sessionKey(ByteArray(32) { 9 })

      val state = RelayConnectionState(post = { it() })
      val attempts = AtomicInteger(0)
      val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
      )
      cleanups += { scope.cancel() }

      val stored = RelayCredentials.Stored(
        deviceId = desktop.deviceId,
        privateKeyB64 = identity.privateKeyB64,
        publicKeyB64 = identity.publicKeyB64,
        sessionKeyB64 = wrongKey,
        desktopFingerprint = desktop.fingerprint,
        relayHost = desktop.host,
        relayPort = desktop.port,
      )
      val controller = RelayConnectionController(
        scope = scope,
        loadCredentials = { RelayCredentials.Load.Ok(stored) },
        state = state,
        clientFactory = { s, listener ->
          attempts.incrementAndGet()
          RelaySocketClient(
            host = s.relayHost!!,
            port = s.relayPort!!,
            deviceId = s.deviceId,
            sessionKeyBase64 = s.sessionKeyB64,
            expectedFingerprint = s.desktopFingerprint,
            listener = listener,
            readTimeoutMs = 10_000,
          )
        },
        onEventJson = { /* 本项只验重连策略 */ },
        // 退避压到 50ms：若逻辑错了（把 4401 当可重试），几十毫秒内就会重连，
        // 测试能在 1 秒内看见 attempts > 1，而不是等 30 秒超时才失败。
        backoffMillis = listOf(50L, 50L, 50L, 50L, 50L, 50L),
      )
      controller.start()

      val until = System.currentTimeMillis() + 20_000
      while (System.currentTimeMillis() < until && state.connection.phase == ConnectionPhase.Connecting) {
        Thread.sleep(25)
      }
      Thread.sleep(400)   // 给「错误地重连」留出发生的时间
      assertEquals("4401 不该重连（连接次数）", 1, attempts.get())
      assertEquals(ConnectionPhase.Offline, state.connection.phase)
      assertEquals(RelayProtocol.Disconnect.UNAUTHORIZED, state.lastDisconnect?.kind)
    } finally {
      desktop.quit()
    }
  }

  // ══════════════ 4b. 配对确认的失败路径（不写盘、不乱报成功） ══════════════

  @Test
  fun 错误的nonce被桌面拒绝且不产生设备() {
    val identity = EcdhPairing.generateIdentity()
    val desktop = Desktop(identity)
    try {
      val ticket = EcdhPairing.parseTicket(desktop.qr, System.currentTimeMillis())
      // 用一个不存在的 nonce：桌面票据表里没有它，必须拒绝。
      val outcome = PairConfirmClient.confirm(
        host = desktop.host,
        port = desktop.port,
        expectedFingerprint = desktop.fingerprint,
        nonce = "f".repeat(32),
        expectedDeviceId = ticket.deviceId,
      )
      assertTrue(
        "错误的 nonce 必须被拒（实际 $outcome）",
        outcome is PairConfirmClient.Outcome.Rejected,
      )
      // 桌面设备表仍然是空的：拒绝时不能留下半条记录。
      // 脚本在 30 秒内等不到设备会报 null，这里用短超时即可确认。
      val stored = desktop.pairedSessionKeyOrNull(timeoutMs = 1_500L)
      assertNull("拒绝时不该产生设备", stored)
    } finally {
      desktop.quit()
    }
  }

  @Test
  fun 指纹不匹配时配对确认直接失败且不泄露会话密钥() {
    val identity = EcdhPairing.generateIdentity()
    val desktop = Desktop(identity)
    try {
      val ticket = EcdhPairing.parseTicket(desktop.qr, System.currentTimeMillis())
      val fp = desktop.fingerprint
      val flipped = (if (fp.first() == 'A') 'B' else 'A') + fp.drop(1)
      val outcome = PairConfirmClient.confirm(
        host = desktop.host,
        port = desktop.port,
        expectedFingerprint = flipped,
        nonce = ticket.nonce,
        expectedDeviceId = ticket.deviceId,
      )
      assertTrue(
        "指纹不对必须拒绝（中间人信号，不能当网络错误重试）",
        outcome is PairConfirmClient.Outcome.Rejected,
      )
    } finally {
      desktop.quit()
    }
  }

  @Test
  fun 桌面不在线时报Unreachable而不是假装成功() {
    // 一个必然连不上的端口：本机 1 号端口不会有服务在听
    val outcome = PairConfirmClient.confirm(
      host = "127.0.0.1",
      port = 1,
      expectedFingerprint = "SHA256:" + "A".repeat(43),
      nonce = "a".repeat(32),
      expectedDeviceId = "dev-x",
      connectTimeoutMs = 1500,
      readTimeoutMs = 1500,
    )
    assertTrue(
      "连不上必须是 Unreachable（可重试），不能报成 Confirmed（实际 $outcome）",
      outcome is PairConfirmClient.Outcome.Unreachable,
    )
  }

  // ══════════════════════ 5. 事件到达 → UI 数字变 ══════════════════════

  @Test
  fun 桌面推送的事件经真实socket抵达并被归并进统计() {
    val identity = EcdhPairing.generateIdentity()
    val desktop = Desktop(identity)
    try {
      val ticket = EcdhPairing.parseTicket(desktop.qr, System.currentTimeMillis())
      val pair = EcdhPairing.pair(ticket, identity)
      assertEquals(pair.sessionKey, desktop.confirmPairing())

      val vm = EventStreamViewModel(
        scope = kotlinx.coroutines.CoroutineScope(
          kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined,
        ),
        applyMs = 0L,
      )
      // 用 SnapshotDispatcher 而不是 MainThreadDispatcher：这一层不测线程切换
      // （D1d 的 MainThreadDispatcherTest 专测那个），只测「事件真的到了并落进状态」。
      val sink = RelayEventSink(vm, RelayEventSink.SnapshotDispatcher())

      val (c, _) = connect(desktop, sessionKey = pair.sessionKey)
      assertNotNull("先确认连接建立", c.awaitReady())

      // 桌面是权威事件源：这里让它广播两条真实信封
      desktop.push(
        """{"type":"run_started","sessionId":"ses-e3","runId":"run-e3",""" +
          """"userMessage":{"text":"帮我整理一下"},"assistantMessageId":"m-e3","at":${System.currentTimeMillis()}}""",
      )
      desktop.push(
        """{"type":"usage_updated","sessionId":"ses-e3","runId":"run-e3",""" +
          """"usage":{"inputTokens":120,"outputTokens":400},"at":${System.currentTimeMillis()}}""",
      )

      // 一条条从 socket 收、交给 sink —— 与生产 `RelayConnectionController`
      // 的 onEvent 分支完全同一条路（它内部也是 client.onEvent → sink.onText）
      var handled = 0
      val deadline = System.currentTimeMillis() + 20_000
      while (handled < 2 && System.currentTimeMillis() < deadline) {
        val json = c.awaitEvent(2_000) ?: continue
        sink.onText(json)
        handled++
      }
      assertEquals("两条事件都应到达并被接受（accepted=${sink.accepted}）", 2, handled)
      assertEquals("不该有解析失败：${sink.lastProblem}", 0, sink.failures)

      val today = vm.state.today
      assertNotNull("今日累计必须被建立（不是 null 的「还不知道」）", today)
      assertEquals("tokens = 120 + 400", 520L, today!!.tokens)
      assertEquals("一轮开始 → rounds +1", 1, today.rounds)
      assertEquals("一轮开始 → running +1", 1, today.running)
      assertEquals("消息进了会话", true, vm.state.sessions["ses-e3"]?.messages?.isNotEmpty() == true)
      assertTrue("seq 由真实信封推进（lastSeq=${vm.state.lastSeq}）", vm.state.lastSeq >= 2L)
    } finally {
      desktop.quit()
    }
  }
}