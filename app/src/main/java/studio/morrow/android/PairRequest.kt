package studio.morrow.android

import java.security.MessageDigest
import java.util.Base64

object PairRequest {
  /** 与桌面 `PAIR_REQUEST_PREFIX` 必须逐字一致（有测试守着） */
  const val PREFIX = "MORROWPAIR1."

  fun encode(deviceId: String?, publicKeyB64: String): String {
    val id = deviceId?.takeIf { it.isNotBlank() } ?: defaultDeviceId(publicKeyB64)
    val json = "{\"deviceId\":${quote(id)},\"pubKey\":${quote(publicKeyB64)}}"
    return PREFIX + base64Url(json.toByteArray(Charsets.UTF_8))
  }

  fun defaultDeviceId(publicKeyB64: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(publicKeyB64.toByteArray(Charsets.UTF_8))
    val hex = digest.take(6).joinToString("") { "%02x".format(it) }
    return "phone-$hex"
  }

  private fun base64Url(bytes: ByteArray): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  private fun quote(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
      }
    }
    sb.append('"')
    return sb.toString()
  }
}
