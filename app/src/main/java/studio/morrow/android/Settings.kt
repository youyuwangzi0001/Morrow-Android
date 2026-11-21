package studio.morrow.android

/**
 * 设置域：偏好持久化与设置页（外观、连接、通知、关于）。
 *
 * 偏好读写的键名与页面上的开关一一对应，合在一处避免「加了开关忘了落盘」。
 */
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ── 原 settings/MorrowPreferences.kt ──

/**
 * 手机端本地偏好（目前只有主题）。
 *
 * 用 SharedPreferences 而不是 DataStore：这里只有一个「重启后记住主题」
 * 的需求，异步 Flow 反而要在启动时处理「还没读出来」的中间态。
 * 主题是纯显示偏好，读失败退回深色即可，不需要错误通道。
 */
object MorrowPreferences {

  private const val FILE = "morrow.settings"
  private const val KEY_THEME = "theme"

  fun theme(context: Context): MorrowThemeMode {
    val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
      .getString(KEY_THEME, null)
    return when (raw) {
      "paper" -> MorrowThemeMode.Paper
      else -> MorrowThemeMode.Ink
    }
  }

  fun setTheme(context: Context, mode: MorrowThemeMode) {
    context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_THEME, if (mode == MorrowThemeMode.Paper) "paper" else "ink")
      .apply()
  }
}
// ── 原 settings/SettingsScreen.kt ──

/** 设置页的测试标签。 */
object SettingsTags {
  const val SCREEN = "settings-screen"
  const val BACK = "settings-back"
  const val THEME_INK = "settings-theme-ink"
  const val THEME_PAPER = "settings-theme-paper"
  const val CLEAR_PAIRING = "settings-clear-pairing"
  const val NOTIFICATIONS = "settings-notifications"
  const val OPEN_NOTIFICATION_SETTINGS = "settings-open-notifications"
  const val VERSION = "settings-version"
  const val CLEARED = "settings-cleared"
}

/**
 * 设置页：外观、连接、通知、关于。
 *
 * 「清除配对」做成两步（先变成「确认清除」，再点一次）。这个动作会删掉
 * 本机私钥与会话密钥，误触后只能重新配对，所以不能只靠一次点击。
 */
@Composable
fun SettingsScreen(
  themeMode: MorrowThemeMode,
  paired: Boolean,
  deviceName: String?,
  notificationsEnabled: Boolean,
  biometric: BiometricGate.Capability,
  versionName: String,
  onThemeChange: (MorrowThemeMode) -> Unit,
  onClearPairing: () -> Unit,
  onOpenNotificationSettings: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val p = MorrowTheme.palette
  var confirmingClear by remember { mutableStateOf(false) }

  Column(
    modifier
      .fillMaxSize()
      .background(MorrowTheme.bg)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s3)
      .testTag(SettingsTags.SCREEN),
  ) {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "←",
        color = p.text2,
        fontSize = MorrowType.title,
        modifier = Modifier
          .testTag(SettingsTags.BACK)
          .clickable(onClick = onBack)
          .padding(horizontal = 8.dp, vertical = 4.dp),
      )
      Spacer(Modifier.width(MorrowSpacing.s2))
      Text("设置", color = p.text, fontSize = MorrowType.title, fontWeight = FontWeight.Medium)
    }

    Spacer(Modifier.height(MorrowSpacing.s4))

    Section(title = "外观") {
      Row(horizontalArrangement = Arrangement.spacedBy(MorrowSpacing.s2)) {
        ThemeChoice(
          label = "深色",
          selected = themeMode == MorrowThemeMode.Ink,
          tag = SettingsTags.THEME_INK,
          onClick = { onThemeChange(MorrowThemeMode.Ink) },
        )
        ThemeChoice(
          label = "浅色",
          selected = themeMode == MorrowThemeMode.Paper,
          tag = SettingsTags.THEME_PAPER,
          onClick = { onThemeChange(MorrowThemeMode.Paper) },
        )
      }
    }

    Section(title = "连接") {
      Text(
        text = when {
          paired && !deviceName.isNullOrBlank() -> "已配对 · $deviceName"
          paired -> "已配对"
          else -> "未配对"
        },
        color = p.text2,
        fontSize = MorrowType.sm,
      )
      Spacer(Modifier.height(MorrowSpacing.s2))
      if (confirmingClear) {
        Text(
          text = "确认清除配对？本机密钥会被删除，需要重新配对",
          color = p.rust,
          fontSize = MorrowType.sm,
        )
        Spacer(Modifier.height(MorrowSpacing.s2))
        ActionRow(
          label = "确认清除",
          tag = SettingsTags.CLEAR_PAIRING,
          color = p.rust,
          onClick = {
            confirmingClear = false
            onClearPairing()
          },
        )
      } else {
        ActionRow(
          label = "清除配对",
          tag = SettingsTags.CLEAR_PAIRING,
          color = p.rust,
          onClick = { if (paired) confirmingClear = true },
        )
      }
    }

    Section(title = "通知") {
      Text(
        text = if (notificationsEnabled) "系统通知：已开启" else "系统通知：已关闭",
        color = if (notificationsEnabled) p.text2 else p.rust,
        fontSize = MorrowType.sm,
        modifier = Modifier.testTag(SettingsTags.NOTIFICATIONS),
      )
      Spacer(Modifier.height(MorrowSpacing.s2))
      Text(
        text = "审批、任务完成、成员卡住与执行进度会通过系统通知提醒；" +
          "免打扰期间只有审批会单独提醒，其余合并成一条汇总。",
        color = p.hint,
        fontSize = MorrowType.xs,
      )
      Spacer(Modifier.height(MorrowSpacing.s2))
      ActionRow(
        label = "打开系统通知设置",
        tag = SettingsTags.OPEN_NOTIFICATION_SETTINGS,
        color = p.patina,
        onClick = onOpenNotificationSettings,
      )
    }

    Section(title = "关于") {
      Text(
        text = "Morrow 安卓端",
        color = p.text2,
        fontSize = MorrowType.sm,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = "版本 $versionName",
        color = p.hint,
        fontSize = MorrowType.xs,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.testTag(SettingsTags.VERSION),
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = when (biometric) {
          BiometricGate.Capability.Ready -> "生物识别：可用（审批允许前需要验证）"
          BiometricGate.Capability.NoHardware -> "生物识别：无硬件（审批只能拒绝）"
          BiometricGate.Capability.NotEnrolled -> "生物识别：未录入（审批只能拒绝）"
          BiometricGate.Capability.Unavailable -> "生物识别：暂不可用（审批只能拒绝）"
          BiometricGate.Capability.PolicyBlocked -> "生物识别：被系统策略禁止（审批只能拒绝）"
        },
        color = p.hint,
        fontSize = MorrowType.xs,
      )
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  val p = MorrowTheme.palette
  Column(
    Modifier
      .fillMaxWidth()
      .padding(bottom = MorrowSpacing.s4),
  ) {
    Text(
      text = title,
      color = p.text3,
      fontSize = MorrowType.xs,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.height(MorrowSpacing.s2))
    Box(
      Modifier
        .fillMaxWidth()
        .background(p.surface, RoundedCornerShape(MorrowRadius.card))
        .border(0.5.dp, p.lineStrong, RoundedCornerShape(MorrowRadius.card))
        .padding(MorrowSpacing.s3),
    ) {
      Column { content() }
    }
  }
}

@Composable
private fun ThemeChoice(
  label: String,
  selected: Boolean,
  tag: String,
  onClick: () -> Unit,
) {
  val p = MorrowTheme.palette
  Text(
    text = label,
    color = if (selected) p.bg else p.text2,
    fontSize = MorrowType.sm,
    modifier = Modifier
      .background(
        if (selected) p.gold else Color.Transparent,
        RoundedCornerShape(MorrowRadius.control),
      )
      .border(
        0.5.dp,
        if (selected) p.gold else p.lineStrong,
        RoundedCornerShape(MorrowRadius.control),
      )
      .clickable(onClick = onClick)
      .testTag(tag)
      .padding(horizontal = MorrowSpacing.s3, vertical = 8.dp),
  )
}

@Composable
private fun ActionRow(
  label: String,
  tag: String,
  color: Color,
  onClick: () -> Unit,
) {
  val p = MorrowTheme.palette
  Row(
    Modifier
      .fillMaxWidth()
      .border(0.5.dp, color.copy(alpha = 0.45f), RoundedCornerShape(MorrowRadius.control))
      .clickable(onClick = onClick)
      .testTag(tag)
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = color, fontSize = MorrowType.base)
    Spacer(Modifier.weight(1f))
    Text("→", color = p.hint, fontSize = MorrowType.base, fontFamily = FontFamily.Monospace)
  }
}
