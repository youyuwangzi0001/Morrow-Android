package studio.morrow.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

  // ───────────────── M-14：解码失败要说人话 ─────────────────

  /**
   * 回归 M-14：`VideoPreview` 此前 `setVideoPath` 后直接 `start()`，
   * 没有 `setOnErrorListener` —— 手机解不了时只画一块黑，用户无从判断
   * 是文件坏了、下载不全，还是手机不支持。
   */
  @Test
  fun 解码失败时区分手机不支持与文件损坏() {
    val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val state = PreviewState.Ready(
      title = "第一集.mp4",
      mime = "video/mp4",
      kind = "mp4",
      bytes = byteArrayOf(1, 2, 3),
    )

    // MEDIA_ERROR_UNSUPPORTED：说清是「没有解码器」
    val unsupported = describeVideoFailure(ctx, state, -1010)
    assertTrue("必须指出解码器缺失", unsupported.contains("解码器"))
    // 且要带上本机能力探测的结论 —— 这正是 HardwareDecode 此前零调用的原因。
    // Robolectric 里没有解码器，命中的是「没有任何能解 … 的组件」那一支；
    // 断言只要求「点出了本机能力」，不锁死具体措辞。
    assertTrue(
      "要说明本机能否解这个容器（不能只说「播放失败」）",
      unsupported.contains("组件"),
    )
    assertTrue("要点出容器名", unsupported.contains("mp4"))

    // MEDIA_ERROR_MALFORMED：说清是「文件坏了」，两种问题的下一步完全不同
    val malformed = describeVideoFailure(ctx, state, -1007)
    assertTrue("必须指出文件本身有问题", malformed.contains("损坏") || malformed.contains("不完整"))
        assertNotEquals("两种原因不能给同一句话", unsupported, malformed)
  }

  @Test
  fun 未知容器给出可判定的说明而不是空话() {
    val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val odd = PreviewState.Ready(
      title = "奇怪的成品",
      mime = "application/octet-stream",
      kind = "xyz",
      bytes = byteArrayOf(1),
    )
    val msg = describeVideoFailure(ctx, odd, -1010)
    assertTrue("未知容器要明说，而不是笼统的「播放失败」", msg.contains("xyz"))
    assertTrue(msg.contains("不在已知的视频格式里"))
  }
}
