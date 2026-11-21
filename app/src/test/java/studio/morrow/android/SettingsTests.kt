package studio.morrow.android

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页：主题、清配对、通知状态、版本。
 *
 * 「清除配对」会删本机私钥，误触代价是重新配对，所以专门钉住两步确认。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {

  @get:Rule
  val compose = createComposeRule()

  private fun render(
    theme: MorrowThemeMode = MorrowThemeMode.Ink,
    paired: Boolean = true,
    notificationsEnabled: Boolean = true,
    onTheme: (MorrowThemeMode) -> Unit = {},
    onClear: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
  ) {
    compose.setContent {
      MorrowTheme(mode = theme) {
        SettingsScreen(
          themeMode = theme,
          paired = paired,
          deviceName = "desktop-1",
          notificationsEnabled = notificationsEnabled,
          biometric = BiometricGate.Capability.Ready,
          versionName = "0.2.0",
          onThemeChange = onTheme,
          onClearPairing = onClear,
          onOpenNotificationSettings = onOpenNotifications,
          onBack = {},
        )
      }
    }
  }

  @Test
  fun 展示版本与通知状态() {
    render(notificationsEnabled = false)
    compose.onNodeWithText("系统通知：已关闭").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(SettingsTags.VERSION).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun 切换主题回调选中值() {
    val picked = mutableListOf<MorrowThemeMode>()
    render(onTheme = { picked += it })
    compose.onNodeWithTag(SettingsTags.THEME_PAPER).performClick()
    assertEquals(listOf(MorrowThemeMode.Paper), picked)
  }

  @Test
  fun 清除配对需要两步确认() {
    var cleared = 0
    render(onClear = { cleared += 1 })

    compose.onNodeWithTag(SettingsTags.CLEAR_PAIRING).performClick()
    compose.waitForIdle()
    assertEquals("第一次点击不能直接删密钥", 0, cleared)
    compose.onNodeWithText("确认清除配对？本机密钥会被删除，需要重新配对").assertIsDisplayed()

    compose.onNodeWithTag(SettingsTags.CLEAR_PAIRING).performClick()
    compose.waitForIdle()
    assertEquals(1, cleared)
  }

  @Test
  fun 从工位页进设置再返回() {
    compose.setContent {
      MorrowTheme {
        MorrowApp()
      }
    }
    compose.onNodeWithTag("workbench-settings").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()

    compose.onNodeWithTag(SettingsTags.BACK).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
  }
}

/** 主题偏好必须跨重启保留，否则每次冷启动都跳回深色。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsPreferencesTest {

  private val app: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun 默认深色并记住浅色() {
    MorrowPreferences.setTheme(app, MorrowThemeMode.Ink)
    assertEquals(MorrowThemeMode.Ink, MorrowPreferences.theme(app))

    MorrowPreferences.setTheme(app, MorrowThemeMode.Paper)
    assertEquals(MorrowThemeMode.Paper, MorrowPreferences.theme(app))
    assertTrue("必须真的落盘", MorrowPreferences.theme(app) == MorrowThemeMode.Paper)
  }

  @Test
  fun 未知值退回深色() {
    app.getSharedPreferences("morrow.settings", Context.MODE_PRIVATE)
      .edit().putString("theme", "future-theme").commit()
    assertEquals(MorrowThemeMode.Ink, MorrowPreferences.theme(app))
    assertFalse(MorrowPreferences.theme(app) == MorrowThemeMode.Paper)
  }
}