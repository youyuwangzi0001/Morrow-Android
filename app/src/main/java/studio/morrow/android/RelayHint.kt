package studio.morrow.android

object RelayHint {

  data class Endpoint(val host: String, val port: Int)

  fun parse(hint: String?): Endpoint? {
    val raw = hint?.trim()
    if (raw.isNullOrEmpty()) return null

    val host: String
    val portText: String
    if (raw.startsWith("[")) {
      val close = raw.indexOf(']')
      if (close <= 1) return null
      host = raw.substring(1, close)
      // 右括号后必须是 `:port`
      if (close + 1 >= raw.length || raw[close + 1] != ':') return null
      portText = raw.substring(close + 2)
    } else {
      val idx = raw.lastIndexOf(':')
      if (idx <= 0) return null          // 没有冒号，或冒号在最前面（缺 host）
      host = raw.substring(0, idx)
      portText = raw.substring(idx + 1)
    }

    if (host.isBlank()) return null
    if (portText.isEmpty() || portText.any { !it.isDigit() }) return null
    val port = portText.toIntOrNull() ?: return null
    if (port !in 1..65535) return null

    return Endpoint(host, port)
  }
}
