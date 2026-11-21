package studio.morrow.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 预览页的三种不可渲染状态 + 关闭。
 *
 * 图片/PDF/视频的渲染依赖真实解码器与文件系统，JVM 里只验证「路由对了、
 * 标题在、关闭能退」，不在 Robolectric 里假装渲染成功。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewScreenTest {

  @get:Rule
  val compose = createComposeRule()

  private fun render(state: PreviewState, onClose: () -> Unit = {}) {
    compose.setContent {
      MorrowTheme { PreviewScreen(state = state, onClose = onClose) }
    }
  }

  @Test
  fun 取件中显示标题与等待文案() {
    render(PreviewState.Loading("成片.mp4"))
    compose.onNodeWithText("成片.mp4").assertIsDisplayed()
    compose.onNodeWithTag(PreviewTags.LOADING).assertIsDisplayed()
  }

  @Test
  fun 取件失败显示原因而不是空白() {
    render(PreviewState.Failed("成片.mp4", "文件 12.4 MB，超过手机预览上限 4 MB，请在桌面打开"))
    compose.onNodeWithText("文件 12.4 MB，超过手机预览上限 4 MB，请在桌面打开").assertIsDisplayed()
    compose.onNodeWithTag(PreviewTags.ERROR).assertIsDisplayed()
  }

  @Test
  fun 手机不能预览的类型给出桌面提示() {
    render(
      PreviewState.Ready(
        title = "漫画包",
        mime = "application/octet-stream",
        kind = "comic",
        bytes = byteArrayOf(1),
      )
    )
    compose.onNodeWithTag(PreviewTags.UNSUPPORTED).assertIsDisplayed()
  }

  @Test
  fun 关闭回到作品页() {
    var closed = 0
    render(PreviewState.Loading("封面"), onClose = { closed += 1 })
    compose.onNodeWithTag(PreviewTags.CLOSE).performClick()
    assertEquals(1, closed)
  }
}