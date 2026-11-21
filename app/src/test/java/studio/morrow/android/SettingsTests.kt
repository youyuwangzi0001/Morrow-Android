package studio.morrow.android

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
    dndAccessGranted: Boolean = true,
    onOpenDndAccess: () -> Unit = {},
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
          dndAccessGranted = dndAccessGranted,
          onOpenNotificationSettings = onOpenNotifications,
          onOpenDndAccessSettings = onOpenDndAccess,
          onBack = {},
        )
      }
    }
  }

  // ───────────────── M-09：勿扰访问权限要能看见、能去开 ─────────────────

  @Test
  fun 未授予勿扰访问权限时给出告警与入口() {
    // 回归 M-09：审批渠道挂了 setBypassDnd(true)，但**只有在用户授予
    // 「勿扰访问权限」后才生效**，未授予时系统静默忽略、不报错。
    // 不给这个入口，用户永远不会知道「审批其实突破不了免打扰」。
    render(dndAccessGranted = false)
    // 设置页是垂直滚动列，这些内容在首屏之外 —— 先滚到它再断言可见。
    compose.onNodeWithTag(SettingsTags.SCREEN)
      .performScrollToNode(hasTestTag(SettingsTags.DND_ACCESS_HINT))
    compose.onNodeWithTag(SettingsTags.DND_ACCESS_HINT).assertIsDisplayed()
    compose.onNodeWithTag(SettingsTags.SCREEN)
      .performScrollToNode(hasTestTag(SettingsTags.OPEN_DND_ACCESS))
    compose.onNodeWithTag(SettingsTags.OPEN_DND_ACCESS).assertIsDisplayed()
    compose.onNodeWithText("未授予", substring = true).assertExists()
  }

  @Test
  fun 已授予时不显示告警入口但保留状态说明() {
    // 已授予就不该再摆一个「去授予」的按钮 —— 那会让人以为还没开。
    // 但状态说明要保留：用户需要能确认「审批确实能突破免打扰」。
    render(dndAccessGranted = true)
    compose.onNodeWithTag(SettingsTags.SCREEN)
      .performScrollToNode(hasTestTag(SettingsTags.DND_ACCESS_HINT))
    compose.onNodeWithTag(SettingsTags.DND_ACCESS_HINT).assertIsDisplayed()
    compose.onNodeWithTag(SettingsTags.OPEN_DND_ACCESS).assertDoesNotExist()
    compose.onNodeWithText("已授予", substring = true).assertExists()
  }

  @Test
  fun 点击授予入口会触发回调() {
    var opened = 0
    render(dndAccessGranted = false, onOpenDndAccess = { opened += 1 })
    compose.onNodeWithTag(SettingsTags.SCREEN)
      .performScrollToNode(hasTestTag(SettingsTags.OPEN_DND_ACCESS))
    compose.onNodeWithTag(SettingsTags.OPEN_DND_ACCESS).performClick()
    assertEquals("必须真的调起系统设置页", 1, opened)
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

  // ───────────────── M-19：lastSeq 跨重启保留 ─────────────────

  @Test
  fun lastSeq跨重启保留() {
    // 回归 M-19：这个值原先只活在内存里，进程被杀就回 0，
    // 于是每次冷启动都告诉桌面「我什么都没收到」，重拉一份全量快照 ——
    // 补漏协议白写。
    MorrowPreferences.setLastSeq(app, 3496L)
    assertEquals("必须真的落盘", 3496L, MorrowPreferences.lastSeq(app))
  }

  @Test
  fun 默认lastSeq为零表示没有状态() {
    // 0 = 首次配对 / 重装。桌面据此改发全量快照，这是正确行为，
    // 不能因为「想避免全量」就编一个假的非零起始值。
    assertEquals(0L, MorrowPreferences.lastSeq(app))
  }

  @Test
  fun 不写入非正序号() {
    // seq <= 0 表示「这条事件没带序号」，写进去只会让下次 hello 报一个
    // 不存在的起点。守卫放在 setter 里，调用方不必每处都判。
    MorrowPreferences.setLastSeq(app, 100L)
    MorrowPreferences.setLastSeq(app, 0L)
    MorrowPreferences.setLastSeq(app, -5L)
    assertEquals("非正序号不能覆盖已有值", 100L, MorrowPreferences.lastSeq(app))
  }
}
