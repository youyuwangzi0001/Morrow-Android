package studio.morrow.android

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

interface SecretKeyProvider {
  fun keyOrCreate(alias: String): SecretKey?

  fun hasKey(alias: String): Boolean

  /** 删除密钥（清除凭据时调用） */
  fun delete(alias: String)

  /** 是否可用（用于启动时判断要不要提示用户「本机无法安全存储配对信息」） */
  fun isAvailable(): Boolean
}

class AndroidKeystoreProvider : SecretKeyProvider {

  companion object {
    private const val PROVIDER = "AndroidKeyStore"
  }

  private fun keyStore(): java.security.KeyStore? = runCatching {
    java.security.KeyStore.getInstance(PROVIDER).apply { load(null) }
  }.getOrNull()

  override fun isAvailable(): Boolean = keyStore() != null

  override fun hasKey(alias: String): Boolean {
    val ks = keyStore() ?: return false
    return runCatching { ks.containsAlias(alias) }.getOrDefault(false)
  }

  override fun keyOrCreate(alias: String): SecretKey? {
    val ks = keyStore() ?: return null

    val existing = runCatching { ks.getKey(alias, null) as? SecretKey }.getOrNull()
    if (existing != null) return existing

    return runCatching {
      val spec = android.security.keystore.KeyGenParameterSpec.Builder(
        alias,
        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
          android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(false)
        .build()

      val gen = javax.crypto.KeyGenerator.getInstance(
        android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, PROVIDER,
      )
      gen.init(spec)
      gen.generateKey()
      java.security.KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as? SecretKey
    }.getOrNull()
  }

  override fun delete(alias: String) {
    runCatching {
      val ks = java.security.KeyStore.getInstance(PROVIDER).apply { load(null) }
      if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }
  }
}

class InMemoryKeyProvider(
  /** 模拟「Keystore 不可用」（用于验证拒绝明文降级的路径） */
  private val available: Boolean = true,
) : SecretKeyProvider {

  private val keys = mutableMapOf<String, SecretKey>()

  override fun isAvailable(): Boolean = available

  override fun hasKey(alias: String): Boolean = available && keys.containsKey(alias)

  override fun keyOrCreate(alias: String): SecretKey? {
    if (!available) return null
    return keys.getOrPut(alias) {
      javax.crypto.spec.SecretKeySpec(ByteArray(32) { (0x40 + it).toByte() }, "AES")
    }
  }

  override fun delete(alias: String) {
    keys.remove(alias)
  }

  /** 测试用：清空内存密钥，模拟「Keystore 密钥被系统撤销」 */
  fun forgetAll() = keys.clear()
}

class PairingFlow(
  private val ticketTtlMillis: Long = 90_000,
  /** 允许的码错误次数。超过就判失败，避免无限试码 */
  private val maxCodeAttempts: Int = 3,
) {

  /** 配对失败的六种可判定原因 */
  enum class Failure(val code: String, val userHint: String, val canRetryFromScan: Boolean) {
    /** 二维码不是合法票据（坏 base64 / 坏 JSON / 缺字段 / 含密钥字段） */
    TICKET_INVALID(
      "TICKET_INVALID",
      "这个二维码不是 Morrow 的配对码",
      canRetryFromScan = true,
    ),

    /** 票据已过期（TTL 90 秒）。这是最常发生的一种：用户扫码慢或在输码时接了个电话 */
    TICKET_EXPIRED(
      "TICKET_EXPIRED",
      "配对码已过期（有效期 90 秒），请回电脑上重新生成",
      canRetryFromScan = true,
    ),

    /** 六位码输错，且已用完尝试次数 */
    CODE_MISMATCH(
      "CODE_MISMATCH",
      "六位码不对。请核对电脑上显示的数字后重新配对",
      canRetryFromScan = true,
    ),

    /** 桌面明确拒绝（票据已被使用 / nonce 不匹配 / 签名不过） */
    DESKTOP_REJECTED(
      "DESKTOP_REJECTED",
      "电脑拒绝了这次配对。请确认电脑上仍显示着配对码",
      canRetryFromScan = true,
    ),

    /** 凭据写盘失败（Keystore 不可用 / 磁盘满） */
    SAVE_FAILED(
      "SAVE_FAILED",
      "配对成功但保存失败，请检查设备存储后重试",
      canRetryFromScan = true,
    ),

    CANCELLED("CANCELLED", "已取消配对", canRetryFromScan = false),
  }

  sealed class State {
    /** 什么都没开始 */
    object Idle : State()

    /** 相机开着，等识别二维码 */
    object Scanning : State()

    data class TicketReady(
      val ticket: EcdhPairing.Ticket,
      val expectedCode: String,
      val expiresAtMillis: Long,
    ) : State()

    /** 等用户输六位码（或已经在输） */
    data class CodeEntering(
      val ticket: EcdhPairing.Ticket,
      val expectedCode: String,
      val expiresAtMillis: Long,
      val attemptsLeft: Int,
      /** 上一次输错时的提示，null = 还没输错过 */
      val lastMismatch: Boolean = false,
    ) : State()

    /** 码对上了，等桌面确认 */
    data class Verifying(
      val ticket: EcdhPairing.Ticket,
      val expectedCode: String,
    ) : State()

    /** 桌面确认了，正在写凭据 */
    data class Saving(
      val ticket: EcdhPairing.Ticket,
      val expectedCode: String,
      val sessionKey: String,
    ) : State()

    /** 完成 */
    data class Paired(val deviceId: String) : State()

    /** 终态失败。`atMillis` 用于界面显示「刚才出的问题」而不是陈年旧账 */
    data class Failed(val failure: Failure, val detail: String, val atMillis: Long) : State()
  }

  var state: State = State.Idle
    private set

  /** 用户点「去配对」/ 界面进入扫码页 */
  fun beginScan() {
    state = State.Scanning
  }

  fun onQrScanned(qrText: String, identity: EcdhPairing.Identity, nowMillis: Long) {
    if (state !is State.Scanning && state !is State.Failed) {
      // 只有扫码中（或刚失败准备重扫）才接受二维码。
      // 已经输了一半的码失去意义，宁可忽略。
      return
    }
    val ticket = try {
      EcdhPairing.parseTicket(qrText, nowMillis)
    } catch (e: EcdhPairing.PairingException) {
      state = State.Failed(
        failure = if (e.code == EcdhPairing.TICKET_EXPIRED) Failure.TICKET_EXPIRED else Failure.TICKET_INVALID,
        detail = e.message ?: e.code,
        atMillis = nowMillis,
      )
      return
    }

    //
    // 用户会反复重输同一串正确数字，永远配不上。
    //
    if (ticket.pubKey != identity.publicKeyB64) {
      state = State.Failed(
        Failure.TICKET_INVALID,
        "这张配对码是给另一台手机的（票据里的公钥与本机不符），请在电脑上重新生成",
        nowMillis,
      )
      return
    }

    val pair = try {
      EcdhPairing.pair(ticket, identity)
    } catch (e: EcdhPairing.PairingException) {
      state = State.Failed(Failure.TICKET_INVALID, e.message ?: e.code, nowMillis)
      return
    }

    state = State.TicketReady(
      ticket = ticket,
      expectedCode = pair.code,
      expiresAtMillis = ticket.exp,
    )
  }

  /** 用户确认要输码（也可以直接进 CodeEntering —— 这只是个显式的过渡） */
  fun beginCodeEntry() {
    val s = state as? State.TicketReady ?: return
    state = State.CodeEntering(
      ticket = s.ticket,
      expectedCode = s.expectedCode,
      expiresAtMillis = s.expiresAtMillis,
      attemptsLeft = maxCodeAttempts,
    )
  }

  fun submitCode(code: String, nowMillis: Long, sessionKey: String): Boolean {
    val s = state as? State.CodeEntering ?: return false

    // 先判过期：票据过期就连码都不用比了。
    if (nowMillis > s.expiresAtMillis) {
      state = State.Failed(
        Failure.TICKET_EXPIRED,
        "票据在输码期间过期（exp=${s.expiresAtMillis} now=$nowMillis）",
        nowMillis,
      )
      return false
    }

    if (code != s.expectedCode) {
      val left = s.attemptsLeft - 1
      state = if (left <= 0) {
        State.Failed(
          Failure.CODE_MISMATCH,
          "六位码连续输错 $maxCodeAttempts 次",
          nowMillis,
        )
      } else {
        s.copy(attemptsLeft = left, lastMismatch = true)
      }
      return false
    }

    state = State.Verifying(s.ticket, s.expectedCode)
    // 重算需要私钥，而这一层不该持有私钥。
    pendingSessionKey = sessionKey
    return true
  }

  /** 桌面确认通过 */
  fun onVerified() {
    val s = state as? State.Verifying ?: return
    val key = pendingSessionKey ?: run {
      state = State.Failed(Failure.SAVE_FAILED, "内部状态丢失 sessionKey", 0)
      return
    }
    state = State.Saving(s.ticket, s.expectedCode, key)
  }

  fun onRejected(detail: String, nowMillis: Long) {
    if (state !is State.Verifying && state !is State.Saving) return
    pendingSessionKey = null
    state = State.Failed(Failure.DESKTOP_REJECTED, detail, nowMillis)
  }

  /** 凭据已落盘 */
  fun onSaved() {
    val s = state as? State.Saving ?: return
    pendingSessionKey = null
    state = State.Paired(s.ticket.deviceId)
  }

  /** 凭据写盘失败 */
  fun onSaveFailed(detail: String, nowMillis: Long) {
    if (state !is State.Verifying && state !is State.Saving) return
    pendingSessionKey = null
    state = State.Failed(Failure.SAVE_FAILED, detail, nowMillis)
  }

  /** 用户取消（返回键 / 关闭扫码页） */
  fun cancel(nowMillis: Long) {
    pendingSessionKey = null
    state = State.Failed(Failure.CANCELLED, "用户取消", nowMillis)
  }

  fun reset() {
    pendingSessionKey = null
    state = State.Idle
  }

  private var pendingSessionKey: String? = null

  /** 是否处于可显示「去配对」入口的状态（即：还没配上、也还没在配） */
  val canStartPairing: Boolean
    get() = when (val s = state) {
      is State.Idle -> true
      is State.Failed -> s.failure != Failure.CANCELLED
      else -> false
    }

  /** 剩余有效秒数（仅 TicketReady / CodeEntering 有意义） */
  fun remainingSeconds(nowMillis: Long): Long? = when (val s = state) {
    is State.TicketReady -> ((s.expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
    is State.CodeEntering -> ((s.expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
    else -> null
  }

  fun isExpired(nowMillis: Long): Boolean = when (val s = state) {
    is State.TicketReady -> nowMillis > s.expiresAtMillis
    is State.CodeEntering -> nowMillis > s.expiresAtMillis
    else -> false
  }
}
