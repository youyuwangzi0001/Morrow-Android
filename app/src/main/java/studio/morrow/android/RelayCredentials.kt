package studio.morrow.android

import android.content.Context
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RelayCredentials(
  private val context: Context,
  private val keyAlias: String = DEFAULT_KEY_ALIAS,
  private val fileName: String = DEFAULT_FILE,
  private val keyProvider: SecretKeyProvider = AndroidKeystoreProvider(),
) {

  companion object {
    const val DEFAULT_KEY_ALIAS = "morrow_relay_credentials"
    const val DEFAULT_FILE = "relay-credentials.bin"

    /** 文件格式版本：将来换格式时用它判断，而不是靠"解析失败"猜 */
    private const val FORMAT_VERSION = 1
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    const val ERR_NO_KEYSTORE = "KEYSTORE_UNAVAILABLE"
    const val ERR_SAVE_FAILED = "SAVE_FAILED"
    const val ERR_CORRUPT = "CREDENTIALS_CORRUPT"
  }

  /** 持久化的凭据。两个字段都不可空——只有私钥没有 sessionKey 用不了，反之亦然 */
  data class Stored(
    val deviceId: String,
    val privateKeyB64: String,
    val publicKeyB64: String,
    val sessionKeyB64: String,
    /** 桌面证书指纹，连接时用于固定校验 */
    val desktopFingerprint: String,
    val relayHost: String? = null,
    val relayPort: Int? = null,
  ) {
    /** 地址是否齐备（齐备才允许发起连接） */
    val hasEndpoint: Boolean get() = !relayHost.isNullOrEmpty() && (relayPort ?: 0) in 1..65535
  }

  sealed class Load {
    data class Ok(val stored: Stored) : Load()

    /** 没有存过（首次安装 / 已清除）—— 不是错误 */
    data object Empty : Load()

    data class Unreadable(val reason: String, val detail: String) : Load()
  }

  sealed class SaveResult {
    data object Ok : SaveResult()
    data class Failed(val reason: String, val detail: String) : SaveResult()
  }

  fun load(): Load {
    val f = file()
    if (!f.exists()) return Load.Empty

    if (!keyProvider.hasKey(keyAlias)) {
      return Load.Unreadable(
        ERR_NO_KEYSTORE,
        "凭据文件存在但 Keystore 密钥不可用（可能被系统清除）",
      )
    }
    val key = keyProvider.keyOrCreate(keyAlias) ?: return Load.Unreadable(
      ERR_NO_KEYSTORE,
      "凭据文件存在但 Keystore 密钥不可用（可能被系统清除）",
    )

    return try {
      val encrypted = f.readBytes()
      val plain = decrypt(key, encrypted)
      parse(plain)
    } catch (t: Throwable) {
      Load.Unreadable(ERR_CORRUPT, t.message ?: t::class.java.simpleName)
    }
  }

  private fun parse(plain: ByteArray): Load {
    val map = LinkedHashMap<String, String>()
    for (line in String(plain, Charsets.UTF_8).split('\n')) {
      if (line.isBlank()) continue
      val i = line.indexOf('=')
      if (i <= 0) return Load.Unreadable(ERR_CORRUPT, "凭据行格式非法")
      map[line.substring(0, i)] = line.substring(i + 1)
    }

    val v = map["v"]?.toIntOrNull()
    if (v != FORMAT_VERSION) {
      return Load.Unreadable(ERR_CORRUPT, "凭据格式版本 $v != $FORMAT_VERSION")
    }

    fun need(name: String): String? = map[name]?.takeIf { it.isNotEmpty() }

    val deviceId = need("deviceId") ?: return Load.Unreadable(ERR_CORRUPT, "缺 deviceId")
    val priv = need("privateKey") ?: return Load.Unreadable(ERR_CORRUPT, "缺 privateKey")
    val pub = need("publicKey") ?: return Load.Unreadable(ERR_CORRUPT, "缺 publicKey")
    val session = need("sessionKey") ?: return Load.Unreadable(ERR_CORRUPT, "缺 sessionKey")
    val fingerprint = need("desktopFingerprint")
      ?: return Load.Unreadable(ERR_CORRUPT, "缺 desktopFingerprint")
    val relayHost = map["relayHost"]?.takeIf { it.isNotEmpty() }
    val relayPort = map["relayPort"]?.toIntOrNull()
    if (relayHost != null && (relayPort == null || relayPort !in 1..65535)) {
      return Load.Unreadable(ERR_CORRUPT, "有 relayHost 却没有合法的 relayPort")
    }

    val sessionBytes = runCatching { Base64.getDecoder().decode(session) }.getOrNull()
      ?: return Load.Unreadable(ERR_CORRUPT, "sessionKey 不是合法 base64")
    if (sessionBytes.size != 32) {
      return Load.Unreadable(ERR_CORRUPT, "sessionKey 必须是 32 字节，实际 ${sessionBytes.size}")
    }

    val pubBytes = runCatching { Base64.getDecoder().decode(pub) }.getOrNull()
      ?: return Load.Unreadable(ERR_CORRUPT, "publicKey 不是合法 base64")
    if (pubBytes.size != 65 || pubBytes[0] != 0x04.toByte()) {
      return Load.Unreadable(ERR_CORRUPT, "publicKey 必须是 65 字节未压缩点")
    }

    return Load.Ok(Stored(deviceId, priv, pub, session, fingerprint, relayHost, relayPort))
  }

  fun save(stored: Stored): SaveResult {
    val key = keyProvider.keyOrCreate(keyAlias) ?: return SaveResult.Failed(
      ERR_NO_KEYSTORE,
      "Keystore 不可用，拒绝以明文持久化凭据",
    )

    val plain = buildString {
      append("v=").append(FORMAT_VERSION).append('\n')
      append("deviceId=").append(stored.deviceId).append('\n')
      append("privateKey=").append(stored.privateKeyB64).append('\n')
      append("publicKey=").append(stored.publicKeyB64).append('\n')
      append("sessionKey=").append(stored.sessionKeyB64).append('\n')
      append("desktopFingerprint=").append(stored.desktopFingerprint).append('\n')
      //
      stored.relayHost?.let { append("relayHost=").append(it).append('\n') }
      stored.relayPort?.let { append("relayPort=").append(it).append('\n') }
    }.toByteArray(Charsets.UTF_8)

    return try {
      val encrypted = encrypt(key, plain)
      val target = file()
      target.parentFile?.mkdirs()
      val tmp = File(target.parentFile, "${target.name}.tmp")
      tmp.writeBytes(encrypted)
      if (!tmp.renameTo(target)) {
        target.writeBytes(encrypted)
        tmp.delete()
      }
      SaveResult.Ok
    } catch (t: Throwable) {
      SaveResult.Failed(ERR_SAVE_FAILED, t.message ?: t::class.java.simpleName)
    }
  }

  /** 清除凭据（撤销配对 / 用户主动断开） */
  fun clear(): Boolean {
    val f = file()
    val gone = if (f.exists()) f.delete() else true
    keyProvider.delete(keyAlias)
    return gone
  }

  /** 凭据文件是否存在（用于判断「配过但读不出来」与「从没配过」） */
  fun exists(): Boolean = file().exists()

  private fun file(): File = File(context.filesDir, fileName)

  /** 输出格式：`1 字节版本 || 12 字节 IV || 密文+tag` */
  private fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val iv = cipher.iv
    val ct = cipher.doFinal(plain)
    val out = ByteArray(1 + iv.size + ct.size)
    out[0] = FORMAT_VERSION.toByte()
    iv.copyInto(out, 1)
    ct.copyInto(out, 1 + iv.size)
    return out
  }

  private fun decrypt(key: SecretKey, data: ByteArray): ByteArray {
    require(data.size > 1 + IV_BYTES) { "凭据文件过短" }
    val ver = data[0].toInt()
    require(ver == FORMAT_VERSION) { "凭据文件版本 $ver 不受支持" }
    val iv = data.copyOfRange(1, 1 + IV_BYTES)
    val ct = data.copyOfRange(1 + IV_BYTES, data.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    return cipher.doFinal(ct)
  }
}
