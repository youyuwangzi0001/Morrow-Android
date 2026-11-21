package studio.morrow.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureChannel(
  sessionKeyBase64: String,
  connectionSaltBase64: String,
  private val deviceId: String,
) {

  companion object {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val SALT_BYTES = 16

    private const val VERSION = "morrow-frame:v1"

    /** 帧错误码，与桌面同名，便于两端对照日志 */
    const val FRAME_TAMPERED = "FRAME_TAMPERED"
    const val FRAME_REPLAYED = "FRAME_REPLAYED"
    const val FRAME_MALFORMED = "FRAME_MALFORMED"

    /** 方向。桌面用 `'phone-to-desktop' | 'desktop-to-phone'` 字符串 */
    const val P2D = "phone-to-desktop"
    const val D2P = "desktop-to-phone"
  }

  class FrameError(val code: String, message: String) : Exception(message)

  /** 线上帧：只有计数与密文（nonce / AAD 都可从计数重建，不必传） */
  data class SealedFrame(val n: String, val c: String)

  private val keys: Map<String, ByteArray>
  private val aadPrefix: Map<String, ByteArray>

  private val sendCounter = mutableMapOf(P2D to 0L, D2P to 0L)

  private val recvCounter = mutableMapOf(P2D to -1L, D2P to -1L)

  init {
    val ikm = decodeBase64(sessionKeyBase64)
    if (ikm.isEmpty()) throw FrameError(FRAME_MALFORMED, "sessionKey 不能为空")
    val salt = decodeBase64(connectionSaltBase64)
    if (salt.size != SALT_BYTES) {
      throw FrameError(FRAME_MALFORMED, "connectionSalt 必须是 16 字节（防止 nonce 重用）")
    }

    keys = mapOf(
      P2D to hkdfSha256(ikm, salt, "$VERSION:$P2D:$deviceId".toByteArray(), KEY_BYTES),
      D2P to hkdfSha256(ikm, salt, "$VERSION:$D2P:$deviceId".toByteArray(), KEY_BYTES),
    )
    aadPrefix = mapOf(
      P2D to "$VERSION|$deviceId|p2d|".toByteArray(),
      D2P to "$VERSION|$deviceId|d2p|".toByteArray(),
    )
  }

  /** 加密一条消息，返回可直接 JSON 化的帧 */
  fun seal(direction: String, plaintext: String): SealedFrame =
    seal(direction, plaintext.toByteArray(Charsets.UTF_8))

  fun seal(direction: String, plaintext: ByteArray): SealedFrame {
    val counter = sendCounter.getValue(direction)
    sendCounter[direction] = counter + 1

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.ENCRYPT_MODE,
      SecretKeySpec(keyOf(direction), "AES"),
      GCMParameterSpec(TAG_BYTES * 8, nonceFor(counter)),
    )
    cipher.updateAAD(aadFor(direction, counter))
    val ct = cipher.doFinal(plaintext)   // Java 的 doFinal 输出已含 tag

    return SealedFrame(n = counter.toString(), c = encodeBase64Url(ct))
  }

  /** 解密一条帧。异常都转成带 code 的 [FrameError] */
  fun open(direction: String, frame: SealedFrame): ByteArray {
    val counter = frame.n.toLongOrNull()
      ?: throw FrameError(FRAME_MALFORMED, "计数不是整数")
    if (counter < 0) throw FrameError(FRAME_MALFORMED, "计数必须非负")

    // 防重放：必须**严格大于**已见最大计数
    val seen = recvCounter.getValue(direction)
    if (counter <= seen) {
      throw FrameError(FRAME_REPLAYED, "计数 $counter 不新于已见 $seen，拒绝重放")
    }

    val payload = try {
      decodeBase64Url(frame.c)
    } catch (t: Throwable) {
      throw FrameError(FRAME_MALFORMED, "密文不是合法 base64url")
    }
    if (payload.size < TAG_BYTES) {
      throw FrameError(FRAME_MALFORMED, "密文短于 GCM tag 长度")
    }

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE,
      SecretKeySpec(keyOf(direction), "AES"),
      GCMParameterSpec(TAG_BYTES * 8, nonceFor(counter)),
    )
    cipher.updateAAD(aadFor(direction, counter))
    val plain = try {
      cipher.doFinal(payload)   // Java 接受「密文||tag」的合并形式
    } catch (t: Throwable) {
      throw FrameError(FRAME_TAMPERED, "GCM 认证失败：密文、AAD 或密钥不匹配")
    }

    // 计数只在**这里**推进：解密与认证都成功了才前进。
    //
    // 如果为了「先占位防止并发重放」而在解密前就推进，攻击者只要发一个
    // 计数很大的坏帧，就能把窗口顶到前面 —— 之后所有真帧都会被判成
    // FRAME_REPLAYED。这是「先污染窗口再拒绝真消息」的拒绝服务。
    //
    // 所以只有解密成功才推进计数，失败时状态不变、可重试。
    recvCounter[direction] = counter
    return plain
  }

  internal fun derivedKeyHexForTest(direction: String): String =
    keyOf(direction).joinToString("") { "%02x".format(it) }

  /** 当前发送计数（诊断用） */
  fun sentCount(direction: String): Long = sendCounter.getValue(direction)

  /** 当前已接受的最大计数（诊断用） */
  fun acceptedCount(direction: String): Long = recvCounter.getValue(direction)

  private fun keyOf(direction: String): ByteArray =
    keys[direction] ?: throw FrameError(FRAME_MALFORMED, "未知方向 $direction")

  private fun nonceFor(counter: Long): ByteArray {
    val nonce = ByteArray(NONCE_BYTES)
    for (i in 0 until 8) {
      nonce[NONCE_BYTES - 1 - i] = ((counter ushr (8 * i)) and 0xFF).toByte()
    }
    return nonce
  }

  private fun aadFor(direction: String, counter: Long): ByteArray {
    val prefix = aadPrefix.getValue(direction)
    val out = ByteArray(prefix.size + 8)
    prefix.copyInto(out, 0)
    for (i in 0 until 8) {
      out[out.size - 1 - i] = ((counter ushr (8 * i)) and 0xFF).toByte()
    }
    return out
  }

  private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val prk = hmacSha256(salt, ikm)

    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    mac.update(info)
    mac.update(0x01)
    val t1 = mac.doFinal()

    return if (length <= t1.size) t1.copyOf(length) else expandMore(prk, t1, info, length)
  }

  /** 极少走到（只有 length > 32 时）。保留完整实现，避免将来要更长密钥时静默截断 */
  private fun expandMore(prk: ByteArray, t1: ByteArray, info: ByteArray, length: Int): ByteArray {
    val out = ByteArray(length)
    t1.copyInto(out, 0)
    var filled = t1.size
    var prev = t1
    var counter = 2
    while (filled < length) {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(prk, "HmacSHA256"))
      mac.update(prev)
      mac.update(info)
      mac.update(counter.toByte())
      prev = mac.doFinal()
      val n = minOf(prev.size, length - filled)
      prev.copyInto(out, filled, 0, n)
      filled += n
      counter++
    }
    return out
  }

  private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    val k = if (key.isEmpty()) ByteArray(32) else key
    mac.init(SecretKeySpec(k, "HmacSHA256"))
    return mac.doFinal(data)
  }

  private fun decodeBase64(s: String): ByteArray = try {
    Base64.getDecoder().decode(s)
  } catch (t: Throwable) {
    throw FrameError(FRAME_MALFORMED, "不是合法 base64")
  }

  private fun decodeBase64Url(s: String): ByteArray = try {
    Base64.getUrlDecoder().decode(s)
  } catch (t: Throwable) {
    throw FrameError(FRAME_MALFORMED, "不是合法 base64url")
  }

  private fun encodeBase64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
