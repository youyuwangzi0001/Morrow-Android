package studio.morrow.android

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Random
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

interface RelayClient {
  /** 阻塞跑到连接结束。返回前必须调用过 listener.onClosed */
  fun runBlockingLoop()

  /** 关闭连接（可从任意线程调用，用于打断阻塞读） */
  fun close()

  fun send(json: String): Boolean = false
}

class RelaySocketClient(
  private val host: String,
  private val port: Int,
  private val deviceId: String,
  private val sessionKeyBase64: String,
  /** 桌面证书指纹（`SHA256:…` 或裸 hex/base64），来自配对 */
  private val expectedFingerprint: String,
  private val listener: Listener,
  /** 已应用到的最大事件序号；重连时告诉桌面从哪儿补。0 = 需要全量快照 */
  private val lastSeq: Long = 0L,
  private val random: Random = SecureRandom(),
  private val connectTimeoutMs: Int = 8_000,
  private val readTimeoutMs: Int = 30_000,
  /** 允许连续多少个心跳周期毫无响应。3 × 30 秒 ≈ 90 秒 */
  private val maxIdleTimeouts: Int = 3,
) : RelayClient {

  interface Listener {
    /** welcome 已解析、SecureChannel 已就绪 */
    fun onReady(welcome: RelayProtocol.Welcome)

    /** 一条解密后的 JSON（桌面事件信封） */
    fun onEvent(json: String)

    /** 连接结束。`shouldRetry` 由协议层决定 */
    fun onClosed(info: RelayProtocol.DisconnectInfo)
  }

  class FingerprintMismatch(message: String) : IOException(message)

  /** 最近一次实际观察到的服务器指纹（诊断用，握手失败时能打印出来对照） */
  @Volatile
  var observedFingerprint: String? = null
    private set

  @Volatile
  private var socket: SSLSocket? = null
  @Volatile
  private var output: OutputStream? = null
  @Volatile
  private var input: InputStream? = null
  @Volatile
  private var channel: SecureChannel? = null

  /** 写帧互斥：send 可能从主线程调用，而读循环在 IO 线程。 */
  private val writeLock = Any()

  @Volatile
  var lastSendFailure: String? = null
    private set

  private fun failSend(reason: String): Boolean {
    lastSendFailure = reason
    return false
  }

  override fun send(json: String): Boolean {
    val ch = channel ?: return failSend("channel 未就绪")
    val out = output ?: return failSend("output 未就绪")
    synchronized(writeLock) {
      return try {
        val sealed = ch.seal(SecureChannel.P2D, json)
        val frame = "{\"n\":\"" + sealed.n + "\",\"c\":\"" + sealed.c + "\"}"
        WebSocketFrames.writeFrame(
          out,
          WebSocketFrames.OP_TEXT,
          frame.toByteArray(Charsets.UTF_8),
          random,
          masked = true,
        )
        out.flush()
        lastSendFailure = null
        true
      } catch (e: Throwable) {
        failSend("写入失败：${e::class.java.simpleName} ${e.message ?: ""}".trim())
      }
    }
  }

  /** 关闭连接。可从任意线程调用（socket.close 会让阻塞中的 read 抛异常） */
  @Volatile
  private var closedByUs = false

  override fun close() {
    closedByUs = true
    try {
      socket?.close()
    } catch (_: Throwable) {
    }
  }

  override fun runBlockingLoop() {
    var info: RelayProtocol.DisconnectInfo
    try {
      connectAndHandshake()
      info = readLoop()
    } catch (e: FingerprintMismatch) {
      info = RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.UNAUTHORIZED, e.message ?: "证书指纹不匹配")
    } catch (e: CloseSignal) {
      info = RelayProtocol.classifyClose(e.code, e.reason)
    } catch (e: RelayProtocol.ProtocolError) {
      info = RelayProtocol.classifyFailure(e)
    } catch (e: Throwable) {
      // 而指纹不匹配正是中间人的信号。
      val certFailure = findCertificateCause(e)
      info = when {
        closedByUs -> RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.RETRYABLE, "主动关闭")
        certFailure != null -> RelayProtocol.DisconnectInfo(
          RelayProtocol.Disconnect.UNAUTHORIZED,
          certFailure.message ?: "证书校验失败",
        )
        else -> RelayProtocol.classifyFailure(e)
      }
    } finally {
      closeQuietly()
    }
    listener.onClosed(info)
  }

  /** TLS 栈会把证书错误层层包装，沿着 cause 链找出来 */
  private fun findCertificateCause(t: Throwable?): java.security.cert.CertificateException? {
    var cur = t
    var depth = 0
    while (cur != null && depth < 8) {
      if (cur is java.security.cert.CertificateException) return cur
      cur = cur.cause
      depth++
    }
    return null
  }

  private fun connectAndHandshake() {
    val raw = SSLSocketFactoryFactory.create(expectedFingerprint, this)
    val s = raw.createSocket() as SSLSocket
    socket = s
    s.soTimeout = readTimeoutMs
    s.connect(InetSocketAddress(host, port), connectTimeoutMs)
    s.startHandshake()

    output = s.outputStream
    input = s.inputStream

    // ── WebSocket 握手 ──
    val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
    output!!.write(WebSocketFrames.handshakeRequest(host, port, "/", key).toByteArray(Charsets.US_ASCII))
    output!!.flush()

    val responseText = readHttpResponseHead(input!!)
    WebSocketFrames.verifyHandshakeResponse(responseText, key)

    // ── hello 鉴权 ──
    val hello = RelayProtocol.helloFrame(sessionKeyBase64, deviceId, System.currentTimeMillis(), lastSeq)
    WebSocketFrames.writeFrame(output!!, WebSocketFrames.OP_TEXT, hello.toByteArray(Charsets.UTF_8), random, masked = true)

    // ── 等 welcome（明文）──
    val welcome = awaitWelcome()
    channel = SecureChannel(sessionKeyBase64, welcome.salt, deviceId)
    listener.onReady(welcome)
  }

  private fun awaitWelcome(): RelayProtocol.Welcome {
    while (true) {
      val frame = WebSocketFrames.read(input!!, expectMasked = false)
      when (frame.opcode) {
        WebSocketFrames.OP_TEXT -> {
          val text = String(frame.payload, Charsets.UTF_8)
          return try {
            RelayProtocol.parseWelcome(text)
          } catch (e: RelayProtocol.ProtocolError) {
            throw RelayProtocol.ProtocolError(
              "WELCOME_EXPECTED",
              "握手后第一帧必须是 welcome：${e.message}",
            )
          }
        }
        WebSocketFrames.OP_PING -> pong(frame.payload)
        WebSocketFrames.OP_PONG -> Unit
        WebSocketFrames.OP_CLOSE -> {
          val (code, reason) = WebSocketFrames.parseClose(frame.payload)
          throw CloseSignal(code, reason)
        }
        else -> Unit
      }
    }
  }

  private fun readLoop(): RelayProtocol.DisconnectInfo {
    val pending = StringBuilder()   // 分片消息的重组缓冲
    var idleRounds = 0

    while (true) {
      val frame = try {
        WebSocketFrames.read(input!!, expectMasked = false)
      } catch (e: java.io.EOFException) {
        return RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.RETRYABLE, "连接被对端关闭")
      } catch (e: java.net.SocketTimeoutException) {
        // ── 空闲超时不是断线，是一次心跳时机 ──
        //
        // 得到「未连接桌面」。远程控制中心不能这样。
        //
        // 又不会误杀健康连接。
        idleRounds++
        if (idleRounds > maxIdleTimeouts) {
          return RelayProtocol.DisconnectInfo(
            RelayProtocol.Disconnect.RETRYABLE,
            "空闲 ${idleRounds} 个心跳周期无任何响应，判定连接已死",
          )
        }
        if (!sendPing()) {
          return RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.RETRYABLE, "心跳发送失败，连接已断")
        }
        continue
      } catch (e: WebSocketFrames.FrameError) {
        // 帧格式错误：可能是中间人改写，不重连
        return RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.TAMPERED, "帧格式错误：${e.message}")
      }

      idleRounds = 0

      when (frame.opcode) {
        WebSocketFrames.OP_PING -> {
          pong(frame.payload)
          continue
        }
        WebSocketFrames.OP_PONG -> continue
        WebSocketFrames.OP_CLOSE -> {
          val (code, reason) = WebSocketFrames.parseClose(frame.payload)
          try {
            WebSocketFrames.writeFrame(
              output!!, WebSocketFrames.OP_CLOSE,
              WebSocketFrames.encodeClose(code, ""), random, masked = true,
            )
          } catch (_: Throwable) {
          }
          return RelayProtocol.classifyClose(code, reason)
        }
        WebSocketFrames.OP_TEXT, WebSocketFrames.OP_CONTINUATION -> {
          pending.append(String(frame.payload, Charsets.UTF_8))
          if (!frame.fin) continue   // 等后续分片
          val text = pending.toString()
          pending.setLength(0)

          when (val incoming = RelayProtocol.classify(text)) {
            is RelayProtocol.Incoming.Welcome ->
              return RelayProtocol.DisconnectInfo(
                RelayProtocol.Disconnect.PROTOCOL, "连接中途收到第二个 welcome",
              )

            is RelayProtocol.Incoming.Malformed ->
              return RelayProtocol.DisconnectInfo(
                RelayProtocol.Disconnect.TAMPERED, "畸形帧：${incoming.reason}",
              )

            is RelayProtocol.Incoming.Secure -> {
              val ch = channel
                ?: return RelayProtocol.DisconnectInfo(
                  RelayProtocol.Disconnect.PROTOCOL, "channel 未就绪却收到加密帧",
                )
              val plain = try {
                ch.open(SecureChannel.D2P, incoming.frame)
              } catch (e: SecureChannel.FrameError) {
                // 篡改一定意味着有人在动密文）
                return when (e.code) {
                  SecureChannel.FRAME_REPLAYED ->
                    RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.REPLAYED, e.message ?: "重放")
                  else ->
                    RelayProtocol.DisconnectInfo(RelayProtocol.Disconnect.TAMPERED, e.message ?: "密文校验失败")
                }
              }
              val json = String(plain, Charsets.UTF_8)
              listener.onEvent(json)
            }
          }
        }
        WebSocketFrames.OP_BINARY -> {
          return RelayProtocol.DisconnectInfo(
            RelayProtocol.Disconnect.PROTOCOL, "收到未约定的二进制帧",
          )
        }
        else -> return RelayProtocol.DisconnectInfo(
          RelayProtocol.Disconnect.PROTOCOL, "未知 opcode ${frame.opcode}",
        )
      }
    }
  }

  private fun writeFrameLocked(opcode: Int, payload: ByteArray) {
    synchronized(writeLock) {
      WebSocketFrames.writeFrame(output!!, opcode, payload, random, masked = true)
      output!!.flush()
    }
  }

  private fun pong(payload: ByteArray) {
    try {
      writeFrameLocked(WebSocketFrames.OP_PONG, payload)
    } catch (_: Throwable) {
    }
  }

  private fun sendPing(): Boolean = try {
    writeFrameLocked(WebSocketFrames.OP_PING, ByteArray(0))
    true
  } catch (_: Throwable) {
    false
  }

  private fun readHttpResponseHead(input: InputStream): String {
    val sb = StringBuilder()
    var matched = 0
    while (true) {
      val b = input.read()
      if (b < 0) throw IOException("握手期间连接关闭")
      sb.append(b.toChar())
      // 以 \r\n\r\n 结束
      matched = when {
        matched == 0 && b == '\r'.code -> 1
        matched == 1 && b == '\n'.code -> 2
        matched == 2 && b == '\r'.code -> 3
        matched == 3 && b == '\n'.code -> return sb.toString()
        b == '\r'.code -> 1
        else -> 0
      }
      if (sb.length > 16_384) throw IOException("握手响应头过长")
    }
  }

  private fun closeQuietly() {
    try {
      socket?.close()
    } catch (_: Throwable) {
    }
    socket = null
    input = null
    output = null
  }

  /** close 帧带来的「对端关闭」，走协议分类而不是当异常 */
  class CloseSignal(val code: Int, val reason: String) : Exception("close $code")

  internal object SSLSocketFactoryFactory {

    /** SHA-256 摘要长度。写死而不是读算法，因为「指纹就是 32 字节」是协议约定 */
    private const val DIGEST_BYTES = 32

    fun create(expectedFingerprint: String, owner: RelaySocketClient?): javax.net.ssl.SSLSocketFactory {
      val tm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
          val cert = chain?.firstOrNull()
            ?: throw CertificateException("服务端未提供证书")
          val actual = fingerprintOf(cert)
          if (owner != null) owner.observedFingerprint = actual
          if (!fingerprintEquals(actual, expectedFingerprint)) {
            throw CertificateException(
              "桌面证书指纹不匹配：期望 $expectedFingerprint，实际 $actual",
            )
          }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
      }
      val ctx = SSLContext.getInstance("TLS")
      ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
      return ctx.socketFactory
    }

    fun fingerprintOf(cert: X509Certificate): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
      return "SHA256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun fingerprintEquals(actual: String, expected: String): Boolean {
      val a = normalize(actual) ?: return false
      val b = normalize(expected) ?: return false
      return a.contentEquals(b)
    }

    fun canParseFingerprint(s: String): Boolean = normalize(s) != null

    private fun normalize(s: String): ByteArray? {
      val t = s.trim()
      val body = if (t.startsWith("SHA256:", ignoreCase = true)) t.substring(7) else t

      // 于是比对永远失败，而用户看不出为什么。
      val looksHex = body.length % 2 == 0 && body.length >= 32 &&
        body.all { Character.digit(it, 16) >= 0 }
      if (looksHex) {
        val out = ByteArray(body.length / 2)
        for (i in out.indices) {
          val hi = Character.digit(body[i * 2], 16)
          val lo = Character.digit(body[i * 2 + 1], 16)
          out[i] = ((hi shl 4) or lo).toByte()
        }
        return out.takeIf { it.size == DIGEST_BYTES }
      }

      // **必须两种字母表都试。**
      val decoded = try {
        Base64.getDecoder().decode(body)
      } catch (_: Throwable) {
        try {
          Base64.getUrlDecoder().decode(body)
        } catch (_: Throwable) {
          null
        }
      } ?: return null

      return decoded.takeIf { it.size == DIGEST_BYTES }
    }
  }
}
