package studio.morrow.android

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

object PairConfirmClient {

  /** 结果：成功、桌面拒绝、或者网络层失败（三种处置完全不同） */
  sealed class Outcome {
    /** 桌面已把本机写入设备表 */
    data object Confirmed : Outcome()

    /** 桌面明确拒绝（票据已用 / 过期 / 不存在）。必须回电脑重新生成配对码。 */
    data class Rejected(val reason: String) : Outcome()

    data class Unreachable(val reason: String) : Outcome()
  }

  fun confirm(
    host: String,
    port: Int,
    expectedFingerprint: String,
    nonce: String,
    expectedDeviceId: String,
    connectTimeoutMs: Int = 8_000,
    readTimeoutMs: Int = 10_000,
  ): Outcome {
    var socket: SSLSocket? = null
    try {
      val factory = RelaySocketClient.SSLSocketFactoryFactory.create(expectedFingerprint, null)
      val raw = factory.createSocket() as SSLSocket
      socket = raw
      raw.soTimeout = readTimeoutMs
      raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
      raw.startHandshake()

      val output = raw.outputStream
      val input = raw.inputStream

      val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
      output.write(WebSocketFrames.handshakeRequest(host, port, "/", key).toByteArray(Charsets.US_ASCII))
      output.flush()
      val responseText = readHttpResponseHead(input)
      WebSocketFrames.verifyHandshakeResponse(responseText, key)

      // 发确认帧（明文：桌面此时还没有会话密钥）
      WebSocketFrames.writeFrame(
        output,
        WebSocketFrames.OP_TEXT,
        RelayProtocol.pairConfirmFrame(nonce).toByteArray(Charsets.UTF_8),
        SecureRandom(),
        masked = true,
      )

      while (true) {
        val frame = WebSocketFrames.read(input, expectMasked = false)
        when (frame.opcode) {
          WebSocketFrames.OP_TEXT -> {
            val text = String(frame.payload, Charsets.UTF_8)
            return if (RelayProtocol.parsePairConfirmOk(text, expectedDeviceId)) {
              Outcome.Confirmed
            } else {
              Outcome.Rejected("桌面的回答不是配对确认（可能是版本不一致）")
            }
          }
          WebSocketFrames.OP_PING -> {
            WebSocketFrames.writeFrame(
              output,
              WebSocketFrames.OP_PONG,
              frame.payload,
              SecureRandom(),
              masked = true,
            )
          }
          WebSocketFrames.OP_PONG -> Unit
          WebSocketFrames.OP_CLOSE -> {
            val (code, reason) = WebSocketFrames.parseClose(frame.payload)
            return Outcome.Rejected(
              "电脑拒绝了配对（$code${if (reason.isBlank()) "" else " $reason"}）",
            )
          }
          else -> Unit
        }
      }
    } catch (e: java.security.cert.CertificateException) {
      // 指纹不匹配是中间人信号，不是「网络不好」
      return Outcome.Rejected(e.message ?: "电脑证书指纹不匹配")
    } catch (e: javax.net.ssl.SSLHandshakeException) {
      var cause: Throwable? = e
      while (cause != null) {
        if (cause is java.security.cert.CertificateException) {
          return Outcome.Rejected(cause.message ?: "电脑证书指纹不匹配")
        }
        cause = cause.cause
      }
      return Outcome.Unreachable("TLS 握手失败：${e.message ?: e::class.java.simpleName}")
    } catch (e: Throwable) {
      return Outcome.Unreachable(e.message ?: e::class.java.simpleName)
    } finally {
      try {
        socket?.close()
      } catch (_: Throwable) {
      }
    }
  }

  /** 读 HTTP 响应头（与 RelaySocketClient 的实现同一规则，见那里的说明） */
  private fun readHttpResponseHead(input: InputStream): String {
    val sb = StringBuilder()
    var matched = 0
    while (true) {
      val b = input.read()
      if (b < 0) throw IOException("握手期间连接关闭")
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
}
