package studio.morrow.android

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Random

object WebSocketFrames {

  const val OP_CONTINUATION = 0x0
  const val OP_TEXT = 0x1
  const val OP_BINARY = 0x2
  const val OP_CLOSE = 0x8
  const val OP_PING = 0x9
  const val OP_PONG = 0xA

  /** 控制帧载荷上限（RFC 6455 §5.5） */
  const val MAX_CONTROL_PAYLOAD = 125

  /** 单条消息上限。与桌面 `maxPayload: 8 * 1024 * 1024` 对齐 */
  const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

  class FrameError(message: String) : Exception(message)

  data class Frame(
    val fin: Boolean,
    val opcode: Int,
    val payload: ByteArray,
  ) {
    val isControl: Boolean get() = opcode and 0x8 != 0
  }

  fun encode(
    opcode: Int,
    payload: ByteArray,
    random: java.util.Random,
    fin: Boolean = true,
    masked: Boolean = true,
  ): ByteArray {
    require(!(opcode and 0x8 != 0) || payload.size <= MAX_CONTROL_PAYLOAD) {
      "控制帧载荷不能超过 $MAX_CONTROL_PAYLOAD 字节"
    }
    val out = java.io.ByteArrayOutputStream(payload.size + 14)
    out.write((if (fin) 0x80 else 0x00) or (opcode and 0x0F))

    val maskBit = if (masked) 0x80 else 0x00
    when {
      payload.size <= 125 -> out.write(maskBit or payload.size)
      payload.size <= 0xFFFF -> {
        out.write(maskBit or 126)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
      }
      else -> {
        out.write(maskBit or 127)
        val len = payload.size.toLong()
        for (i in 7 downTo 0) out.write(((len ushr (8 * i)) and 0xFF).toInt())
      }
    }

    if (!masked) {
      out.write(payload, 0, payload.size)
      return out.toByteArray()
    }

    val mask = ByteArray(4).also { random.nextBytes(it) }
    out.write(mask, 0, 4)
    for (i in payload.indices) {
      out.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
    }
    return out.toByteArray()
  }

  fun read(input: InputStream, expectMasked: Boolean = false): Frame {
    val b0 = input.read()
    if (b0 < 0) throw EOFException("连接已关闭")
    val fin = b0 and 0x80 != 0
    val rsv = b0 and 0x70
    if (rsv != 0) throw FrameError("RSV 位必须为 0（未协商扩展），实际 $rsv")
    val opcode = b0 and 0x0F

    val b1 = input.read()
    if (b1 < 0) throw EOFException("读数长度时连接关闭")
    val masked = b1 and 0x80 != 0
    if (masked != expectMasked) {
      throw FrameError(
        if (expectMasked) "本应带掩码的帧没有掩码" else "服务端帧不该带掩码",
      )
    }

    var len = (b1 and 0x7F).toLong()
    if (len == 126L) {
      len = ((readByte(input).toLong() shl 8) or readByte(input).toLong())
    } else if (len == 127L) {
      len = 0
      for (i in 0 until 8) len = (len shl 8) or readByte(input).toLong()
      if (len < 0) throw FrameError("64 位长度超出范围")
    }

    if (len > MAX_MESSAGE_BYTES) throw FrameError("帧过大：$len 字节")
    if (opcode and 0x8 != 0 && len > MAX_CONTROL_PAYLOAD) {
      throw FrameError("控制帧载荷超过 $MAX_CONTROL_PAYLOAD")
    }

    val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
    val payload = ByteArray(len.toInt())
    readFully(input, payload)
    if (mask != null) {
      for (i in payload.indices) {
        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
      }
    }
    return Frame(fin, opcode, payload)
  }

  /** close 帧载荷：2 字节大端关闭码 + 可选原因（UTF-8） */
  fun encodeClose(code: Int, reason: String): ByteArray {
    val r = reason.toByteArray(Charsets.UTF_8)
    val payload = ByteArray(2 + minOf(r.size, MAX_CONTROL_PAYLOAD - 2))
    payload[0] = ((code ushr 8) and 0xFF).toByte()
    payload[1] = (code and 0xFF).toByte()
    r.copyInto(payload, 2, 0, minOf(r.size, MAX_CONTROL_PAYLOAD - 2))
    return payload
  }

  /** 解析 close 帧载荷；空载荷表示「无关闭码」（按 1005 处理） */
  fun parseClose(payload: ByteArray): Pair<Int, String> {
    if (payload.isEmpty()) return 1005 to ""
    if (payload.size == 1) throw FrameError("close 帧载荷长度非法（1 字节）")
    val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    val reason = String(payload, 2, payload.size - 2, Charsets.UTF_8)
    return code to reason
  }

  /** 生成握手请求（RFC 6455 §4.1） */
  fun handshakeRequest(host: String, port: Int, path: String = "/", key: String): String = buildString {
    append("GET ").append(path).append(" HTTP/1.1\r\n")
    append("Host: ").append(host).append(':').append(port).append("\r\n")
    append("Upgrade: websocket\r\n")
    append("Connection: Upgrade\r\n")
    append("Sec-WebSocket-Key: ").append(key).append("\r\n")
    append("Sec-WebSocket-Version: 13\r\n")
    append("Origin: https://").append(host).append(':').append(port).append("\r\n")
    append("\r\n")
  }

  fun verifyHandshakeResponse(responseText: String, key: String) {
    val lines = responseText.split("\r\n")
    val status = lines.firstOrNull() ?: throw FrameError("握手响应为空")
    if (!status.startsWith("HTTP/1.1 101")) {
      throw FrameError("握手失败：$status")
    }
    val headers = lines.drop(1)
      .filter { it.contains(':') }
      .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }

    val upgrade = headers["upgrade"]?.lowercase()
    if (upgrade != "websocket") throw FrameError("缺 Upgrade: websocket（实际 $upgrade）")
    val conn = headers["connection"]?.lowercase() ?: ""
    if (!conn.contains("upgrade")) throw FrameError("缺 Connection: Upgrade（实际 $conn）")

    val expected = acceptKey(key)
    val actual = headers["sec-websocket-accept"]
    if (actual != expected) {
      throw FrameError("Sec-WebSocket-Accept 不匹配（期望 $expected，实际 $actual）")
    }
  }

  /** `base64(SHA1(clientKey + GUID))`，GUID 是 RFC 6455 规定的固定串 */
  fun acceptKey(clientKey: String): String {
    val guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    val sha1 = java.security.MessageDigest.getInstance("SHA-1")
    val digest = sha1.digest((clientKey + guid).toByteArray(Charsets.US_ASCII))
    return java.util.Base64.getEncoder().encodeToString(digest)
  }

  private fun readByte(input: InputStream): Int {
    val b = input.read()
    if (b < 0) throw EOFException("连接已关闭")
    return b
  }

  private fun readFully(input: InputStream, buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
      val n = input.read(buf, off, buf.size - off)
      if (n < 0) throw EOFException("期望 ${buf.size} 字节，只读到 $off")
      off += n
    }
  }

  fun writeFrame(
    output: OutputStream,
    opcode: Int,
    payload: ByteArray,
    random: java.util.Random,
    masked: Boolean,
  ) {
    output.write(encode(opcode, payload, random, masked = masked))
    output.flush()
  }
}
