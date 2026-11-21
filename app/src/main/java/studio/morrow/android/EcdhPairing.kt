package studio.morrow.android

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object EcdhPairing {

  const val CURVE = "secp256r1"

  /** P-256 的坐标长度（字节） */
  const val COORD_BYTES = 32
  /** 未压缩点长度：1 字节前缀 + 2 × 32 */
  const val UNCOMPRESSED_BYTES = 65

  const val TICKET_INVALID = "TICKET_INVALID"
  const val TICKET_EXPIRED = "TICKET_EXPIRED"

  class PairingException(val code: String, message: String) : Exception(message)

  data class Ticket(
    val deviceId: String,
    val pubKey: String,
    val desktopPub: String,
    val nonce: String,
    val exp: Long,
    val relayHint: String? = null,
    val desktopFingerprint: String? = null,
  )

  /** 手机侧的长期 ECDH 密钥对 */
  class Identity(val privateKey: ECPrivateKey, val publicKeyB64: String) {
    val privateKeyB64: String
      get() = java.util.Base64.getEncoder().encodeToString(privateKey.encoded)
  }

  /** 生成 P-256 密钥对，公钥导出为未压缩点（65 字节 base64） */
  fun generateIdentity(): Identity {
    val gen = KeyPairGenerator.getInstance("EC")
    gen.initialize(ECGenParameterSpec(CURVE))
    val kp = gen.generateKeyPair()
    val pub = kp.public as ECPublicKey
    return Identity(kp.private as ECPrivateKey, encodeUncompressed(pub))
  }

  fun encodeUncompressed(pub: ECPublicKey): String {
    val x = fixedWidth(pub.w.affineX)
    val y = fixedWidth(pub.w.affineY)
    val out = ByteArray(UNCOMPRESSED_BYTES)
    out[0] = 0x04
    x.copyInto(out, 1)
    y.copyInto(out, 1 + COORD_BYTES)
    return Base64.getEncoder().encodeToString(out)
  }

  private fun fixedWidth(v: BigInteger): ByteArray {
    val raw = v.toByteArray()
    val out = ByteArray(COORD_BYTES)
    when {
      raw.size == COORD_BYTES -> raw.copyInto(out)
      raw.size == COORD_BYTES + 1 && raw[0] == 0.toByte() -> raw.copyInto(out, 0, 1, raw.size)
      raw.size < COORD_BYTES -> raw.copyInto(out, COORD_BYTES - raw.size)
      else -> throw PairingException(TICKET_INVALID, "坐标长度异常 ${raw.size}")
    }
    return out
  }

  /** 把 base64 的未压缩点还原成 Java 公钥 */
  fun decodePublicKey(b64: String): ECPublicKey {
    val raw = try {
      Base64.getDecoder().decode(b64)
    } catch (t: Throwable) {
      throw PairingException(TICKET_INVALID, "公钥不是合法 base64")
    }
    if (raw.size != UNCOMPRESSED_BYTES || raw[0] != 0x04.toByte()) {
      throw PairingException(
        TICKET_INVALID,
        "公钥必须是 65 字节未压缩点（04||X||Y），实际 ${raw.size} 字节",
      )
    }
    val x = BigInteger(1, raw.copyOfRange(1, 1 + COORD_BYTES))
    val y = BigInteger(1, raw.copyOfRange(1 + COORD_BYTES, UNCOMPRESSED_BYTES))

    val params: ECParameterSpec = curveParams()
    val spec = ECPublicKeySpec(ECPoint(x, y), params)
    return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
  }

  /** 取 P-256 的曲线参数：生成一对临时密钥，从公钥里读出来 */
  private fun curveParams(): ECParameterSpec {
    val gen = KeyPairGenerator.getInstance("EC")
    gen.initialize(ECGenParameterSpec(CURVE))
    return (gen.generateKeyPair().public as ECPublicKey).params
  }

  fun sharedSecret(privateKey: ECPrivateKey, peerPubB64: String): ByteArray {
    val peer = decodePublicKey(peerPubB64)
    val ka = KeyAgreement.getInstance("ECDH")
    ka.init(privateKey)
    ka.doPhase(peer, true)
    return ka.generateSecret()
  }

  fun pairingCode(sharedSecret: ByteArray, nonce: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
    val digest = mac.doFinal("pair-code:$nonce".toByteArray(Charsets.UTF_8))
    val n = ((digest[0].toLong() and 0xFF) shl 24) or
      ((digest[1].toLong() and 0xFF) shl 16) or
      ((digest[2].toLong() and 0xFF) shl 8) or
      (digest[3].toLong() and 0xFF)
    return (n % 1_000_000L).toString().padStart(6, '0')
  }

  /** 会话密钥：**就是**共享秘密的 base64（与桌面 `completePairing` 一致） */
  fun sessionKey(sharedSecret: ByteArray): String =
    Base64.getEncoder().encodeToString(sharedSecret)

  fun parseTicket(qr: String, nowMillis: Long): Ticket {
    val json = try {
      String(Base64.getUrlDecoder().decode(qr), Charsets.UTF_8)
    } catch (t: Throwable) {
      throw PairingException(TICKET_INVALID, "二维码不是合法 base64url")
    }

    val obj = JsonLite.parseObject(json)
      ?: throw PairingException(TICKET_INVALID, "二维码内容不是 JSON 对象")

    val expRaw = obj["exp"]
    val exp = expRaw?.toLongOrNull()
      ?: throw PairingException(TICKET_INVALID, "缺 exp")
    // 过期优先：这一条必须在其余字段校验之前
    if (nowMillis > exp) {
      throw PairingException(TICKET_EXPIRED, "票据已过期（exp=$exp now=$nowMillis）")
    }

    val deviceId = obj["deviceId"] ?: throw PairingException(TICKET_INVALID, "缺 deviceId")
    val pubKey = obj["pubKey"] ?: throw PairingException(TICKET_INVALID, "缺 pubKey")
    val desktopPub = obj["desktopPub"]
      ?: throw PairingException(TICKET_INVALID, "缺 desktopPub（没有它算不出共享秘密）")
    val nonce = obj["nonce"] ?: throw PairingException(TICKET_INVALID, "缺 nonce")

    val forbidden = obj.keys.filter { Regex("session|secret|private", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    if (forbidden.isNotEmpty()) {
      throw PairingException(TICKET_INVALID, "票据含密钥字段：${forbidden.joinToString(",")}")
    }

    return Ticket(deviceId, pubKey, desktopPub, nonce, exp, obj["relayHint"], obj["fingerprint"])
  }

  data class PairResult(val code: String, val sessionKey: String, val deviceId: String, val nonce: String)

  fun pair(ticket: Ticket, identity: Identity): PairResult {
    val shared = sharedSecret(identity.privateKey, ticket.desktopPub)
    return PairResult(
      code = pairingCode(shared, ticket.nonce),
      sessionKey = sessionKey(shared),
      deviceId = ticket.deviceId,
      nonce = ticket.nonce,
    )
  }

  internal object JsonLite {
    fun parseObject(text: String): Map<String, String>? {
      val t = text.trim()
      if (!t.startsWith("{") || !t.endsWith("}")) return null
      val body = t.substring(1, t.length - 1).trim()
      if (body.isEmpty()) return emptyMap()

      val out = LinkedHashMap<String, String>()
      var i = 0
      while (i < body.length) {
        // 读 key
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != '"') return null
        val keyStart = i + 1
        var j = keyStart
        while (j < body.length && body[j] != '"') {
          if (body[j] == '\\') j++   // 跳过转义
          j++
        }
        if (j >= body.length) return null
        val key = body.substring(keyStart, j)
        i = j + 1

        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != ':') return null
        i++
        while (i < body.length && body[i].isWhitespace()) i++

        // 读 value：只接受字符串或数字
        val value: String
        if (i < body.length && body[i] == '"') {
          val vs = i + 1
          var k = vs
          while (k < body.length && body[k] != '"') {
            if (body[k] == '\\') k++
            k++
          }
          if (k >= body.length) return null
          value = unescape(body.substring(vs, k))
          i = k + 1
        } else {
          val vs = i
          while (i < body.length && body[i] != ',' && body[i] != '}') i++
          val raw = body.substring(vs, i).trim()
          if (raw.isEmpty() || raw.startsWith("{") || raw.startsWith("[")) return null
          value = raw
        }

        out[key] = value

        while (i < body.length && body[i].isWhitespace()) i++
        if (i < body.length) {
          if (body[i] == ',') {
            i++
            var j2 = i
            while (j2 < body.length && body[j2].isWhitespace()) j2++
            if (j2 >= body.length) return null
          } else {
            return null
          }
        }
      }
      return out
    }

    private fun unescape(s: String): String {
      if (!s.contains('\\')) return s
      val sb = StringBuilder(s.length)
      var i = 0
      while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
          when (val n = s[i + 1]) {
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            else -> sb.append(n)
          }
          i += 2
        } else {
          sb.append(c)
          i++
        }
      }
      return sb.toString()
    }
  }
}
