// 数字与时间的统一格式化：全仓唯一来源。
//
//

package studio.morrow.android

import java.util.Locale

/** 数字与时间的统一格式化。**不要在别处再写第二份。** */
object Formatters {

  fun bytes(count: Long): String {
    val n = count.coerceAtLeast(0)
    if (n < 1024) return "$n B"

    val units = listOf("KB", "MB", "GB", "TB")
    var value = n / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
      value /= 1024.0
      unit++
    }

    var decimals = if (unit >= 2) 2 else 1
    var rounded = roundTo(value, decimals)
    if (rounded >= 1024.0 && unit < units.lastIndex) {
      rounded = roundTo(rounded / 1024.0, 1)
      unit++
      decimals = 1
    }
    return fixed(rounded, decimals) + " " + units[unit]
  }

  fun relative(millis: Long, nowMillis: Long): String? {
    if (millis <= 0L) return null
    val delta = nowMillis - millis
    if (delta < 0L) return "刚刚"
    val minutes = delta / 60_000L
    return when {
      minutes < 1L -> "刚刚"
      minutes < 60L -> "$minutes 分钟前"
      minutes < 60L * 24 -> "${minutes / 60} 小时前"
      minutes < 60L * 24 * 2 -> "昨天"
      minutes < 60L * 24 * 30 -> "${minutes / (60 * 24)} 天前"
      else -> "${minutes / (60 * 24 * 30)} 个月前"
    }
  }

  fun durationSpoken(millis: Long): String {
    val total = (millis + 500) / 1000
    val minutes = total / 60
    val seconds = total % 60
    if (minutes == 0L) return "$seconds 秒"
    return if (seconds == 0L) "$minutes 分" else "$minutes 分 $seconds 秒"
  }

  fun durationClock(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
  }

  fun durationNotice(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    if (s < 60) return "用时 $s 秒"
    val m = s / 60
    if (m < 60) return "用时 $m 分钟"
    return "用时 ${m / 60} 小时 ${m % 60} 分钟"
  }

  /** token 数：`999` / `1.5K` / `18.4K` / `412K` / `1.2M` / `3.4G` */
  fun tokens(count: Long): String {
    val n = count.coerceAtLeast(0)
    if (n < 1_000) return n.toString()
    val units = listOf("K", "M", "G")
    var value = n / 1000.0
    var unit = 0
    while (value >= 1000.0 && unit < units.lastIndex) {
      value /= 1000.0
      unit++
    }
    return if (value >= 100) "${Math.round(value)}${units[unit]}" else fixed(value, 1) + units[unit]
  }

  private fun fixed(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

  /** 按小数位四舍五入（`roundTo` 自己实现，避免依赖 Compose 的 roundToInt）。 */
  private fun roundTo(value: Double, decimals: Int): Double {
    val factor = Math.pow(10.0, decimals.toDouble())
    return Math.round(value * factor) / factor
  }
}
