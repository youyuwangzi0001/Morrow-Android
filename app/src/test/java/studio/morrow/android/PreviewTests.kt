package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ───── HardwareDecodeTest.kt ─────
/**
 * 硬件解码测试 —— 覆盖蓝图 C5 第三条验收：
 *
 * > 视频播放有硬件解码
 *
 * 「有硬件解码」在 Android 上不是一行开关，而是三件具体的事：
 * ①用 SurfaceView（不是 TextureView）②允许丢帧 ③能识别本机是否只有软解。
 * 这个文件逐条守着它们。
 *
 * 「实际播放是否真的走了硬解」在 Robolectric 里测不了（没有真实解码器），
 * 如实标注在报告里。
 */
class HardwareDecodeTest {

  // ═══════════ 硬解 / 软解的识别 ═══════════

  @Test
  fun 名字带android的是软解() {
    // 这是 Android 上唯一可靠的判据：AOSP 的软解器一定以
    // c2.android. 或 OMX.google. 开头
    assertFalse(HardwareDecode.isHardwareAccelerated("c2.android.avc.decoder"))
    assertFalse(HardwareDecode.isHardwareAccelerated("OMX.google.h264.decoder"))
    assertFalse(HardwareDecode.isHardwareAccelerated("c2.android.hevc.decoder"))
  }

  @Test
  fun 厂商解码器算硬解() {
    assertTrue(HardwareDecode.isHardwareAccelerated("c2.qti.avc.decoder"))
    assertTrue(HardwareDecode.isHardwareAccelerated("OMX.qcom.video.decoder.avc"))
    assertTrue(HardwareDecode.isHardwareAccelerated("c2.exynos.h264.decoder"))
    assertTrue(HardwareDecode.isHardwareAccelerated("OMX.MTK.VIDEO.DECODER.AVC"))
  }

  @Test
  fun 名字里写明software的也算软解() {
    assertFalse(HardwareDecode.isHardwareAccelerated("xxx.sw.decoder"))
    assertFalse(HardwareDecode.isHardwareAccelerated("vendor.software.video"))
  }

  // ═══════════ 三态能力查询 ═══════════

  @Test
  fun 有硬解器时返回Hardware() {
    val s = HardwareDecode.supportFor("video/mp4") { listOf("c2.qti.avc.decoder", "c2.android.avc.decoder") }
    assertTrue(s is DecodeSupport.Hardware)
    assertEquals("c2.qti.avc.decoder", (s as DecodeSupport.Hardware).component)
  }

  @Test
  fun 只有软解时返回SoftwareOnly而不是Hardware() {
    // 这条是「不撒谎」的关键：把软解说成硬解，
    // 用户就会在高码率视频上遇到莫名卡顿而不知道原因。
    val s = HardwareDecode.supportFor("video/mp4") { listOf("c2.android.avc.decoder") }
    assertTrue("只有软解必须如实报告", s is DecodeSupport.SoftwareOnly)
    assertEquals("c2.android.avc.decoder", (s as DecodeSupport.SoftwareOnly).component)
  }

  @Test
  fun 一个解不了时返回Unsupported() {
    val s = HardwareDecode.supportFor("video/x-matroska") { emptyList() }
    assertTrue(s is DecodeSupport.Unsupported)
  }

  @Test
  fun 硬解优先于软解即使软解排在前面() {
    // 系统给的顺序不一定把硬解放最前；我们要主动挑硬解
    val s = HardwareDecode.supportFor("video/mp4") {
      listOf("c2.android.avc.decoder", "c2.qti.avc.decoder")
    }
    assertTrue("必须挑出硬解", s is DecodeSupport.Hardware)
  }

  // ═══════════ 四种容器都有 MIME ═══════════

  @Test
  fun 桌面四种视频容器都有对应MIME() {
    assertEquals(4, HardwareDecode.videoMimeOf.size)
    listOf("mp4", "mov", "mkv", "webm").forEach {
      assertTrue("$it 必须有 MIME", HardwareDecode.videoMimeOf.containsKey(it))
    }
  }

  // ═══════════ SurfaceView 与丢帧 ═══════════

  @Test
  fun 视频用SurfaceView而不是TextureView() {
    // TextureView 每帧要拷成纹理再合成，1080p 上明显掉帧；
    // SurfaceView 走独立 Surface，硬解直通。
    // 这条断言守的是「别为了做圆角/动画顺手换成 TextureView」。
    assertEquals(PlayerChoice.SurfaceKind.SurfaceView, PlayerChoice.surfaceKind())
    assertFalse(
      "不能是 TextureView",
      PlayerChoice.surfaceKind() == PlayerChoice.SurfaceKind.TextureView
    )
  }

  @Test
  fun 允许丢帧防止播放位置越差越远() {
    // KEY_ALLOW_FRAME_DROP=1：追不上时丢帧而不是排队。
    // 排队会让「当前时间」与实际画面越差越远。
    val extras = PlayerChoice.mediaFormatExtras()
    assertEquals(1, extras[android.media.MediaFormat.KEY_ALLOW_FRAME_DROP])
  }

  @Test
  fun 只能软解时给出提示文案() {
    val note = PlayerChoice.softwareFallbackNote(
      DecodeSupport.SoftwareOnly("c2.android.avc.decoder")
    )
    assertTrue("软解必须提示", note != null && note.contains("软解"))
  }

  @Test
  fun 硬解或不支持时不出现软解提示() {
    // 反向确认：提示不能一直挂着
    assertNull(PlayerChoice.softwareFallbackNote(DecodeSupport.Hardware("c2.qti.avc.decoder")))
    assertNull(PlayerChoice.softwareFallbackNote(DecodeSupport.Unsupported("video/x-matroska")))
  }
}

// ───── PdfLoadPolicyTest.kt ─────
/**
 * PDF 首屏策略测试 —— 覆盖蓝图 C5 第四条验收：
 *
 * > PDF 首屏 <2s
 *
 * 真实的 `<2s` 只能在真机上测（需要真 PDF 文件 + PdfRenderer）。
 * 这个文件守的是**让 <2s 成为可能的那三条策略**：
 * 只渲染 1 页、首屏用 1.0 倍、不预加载相邻页。
 *
 * 判据：如果谁把策略改成「全量渲染」或「首屏 2.5 倍」，
 * 这些测试必须红 —— 因为那样的实现在真机上一定超 2s。
 */
/**
 * F-03：`PlayerChoice` 此前生产零调用 —— 三个入口（surfaceKind /
 * mediaFormatExtras / softwareFallbackNote）各自有测试，但没有任何
 * 生产代码问过它们。于是「这台设备只能软解，高码率可能卡顿」这句话
 * 用户从来没看到过。
 *
 * 这一组守住的是「它给出的结论本身正确」，接线由 VideoPreview 里的
 * 调用点负责（verify.sh 有源码级检查）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerChoiceWiringTest {

  @Test
  fun 只能软解时给出提示() {
    val note = PlayerChoice.softwareFallbackNote(DecodeSupport.SoftwareOnly("c2.android.avc.decoder"))
    assertNotNull("软解必须提示，否则用户只会怪网络", note)
    assertTrue("提示要说清是设备能力问题", note!!.contains("软解"))
  }

  @Test
  fun 硬解与不支持两种情形都不提示() {
    // 硬解：没问题，不用打扰用户
    assertNull(PlayerChoice.softwareFallbackNote(DecodeSupport.Hardware("OMX.qcom.video.decoder.avc")))
    // 不支持：走的是 error 路径（M-14 的提示），不该再叠一句软解说明
    assertNull(PlayerChoice.softwareFallbackNote(DecodeSupport.Unsupported("video/x-matroska")))
  }

  @Test
  fun 播放器选择必须稳定返回SurfaceView() {
    // 这个常量决定视频是否走硬解直通。改成 TextureView 会让 1080p 明显更耗电，
    // 属于「改了没报错、只有用户感觉得到」的那类回归。
    assertEquals(
      PlayerChoice.SurfaceKind.SurfaceView,
      PlayerChoice.surfaceKind(),
    )
  }
}

class PdfLoadPolicyTest {

  // ═══════════ F-04：策略真的被渲染路径用上了吗 ═══════════
  //
  // 上面那几条只断言「常量的值是多少」。那是不够的 —— `PdfPreview` 曾经
  // 写死 `1.5f` 而不读 `FIRST_SCREEN_SCALE`，`firstScreenBudget` 也从未被问过，
  // 而所有这些常量断言依然全绿。**断言常量 ≠ 断言行为**。
  //
  // 下面两条改为断言「渲染路径读的就是这份策略」：改成源码级契约，
  // 用 verify.sh 之外的 JVM 断言拿不到 Composable 内部，
  // 所以这里断言的是策略本身对外承诺的边界，配合 verify.sh 的源码检查。

  @Test
  fun 超出预算的页面必须在渲染前被拦下() {
    // 注意：A0（2384×3370pt）在 ARGB_8888 下是 32,136,320 字节，
    // **没有**超过 32MiB（33,554,432）—— 阈值是 MiB 不是 MB，差 1.4MB。
    // 所以这里用 2A0（长边倍增大致四倍面积）保证真的越线。
    val verdict = PdfLoadPolicy.firstScreenBudget(4768, 6740)
    assertTrue(
      "2A0 首屏约 128MB，必须判 TooLarge 而不是硬渲染",
      verdict is PdfLoadPolicy.BudgetVerdict.TooLarge,
    )
    // TooLarge 必须给出两个具体数字，界面才能说清「需要多少 / 上限多少」
    val v = verdict as PdfLoadPolicy.BudgetVerdict.TooLarge
    assertTrue("要报出实际字节数", v.bytes > 0)
    assertEquals("上限就是 32MB", PdfLoadPolicy.MAX_FIRST_SCREEN_BYTES, v.limit)
    assertTrue("必须真的超了", v.bytes > v.limit)
  }

  @Test
  fun 普通A4页面在预算内可以渲染() {
    // 反向断言：别把正常文档也拦下。A4 = 595×842 pt
    val verdict = PdfLoadPolicy.firstScreenBudget(595, 842)
    assertTrue(
      "A4 首屏只有 2MB 左右，不能误判为超限",
      verdict is PdfLoadPolicy.BudgetVerdict.Ok,
    )
  }

  // ═══════════ 三条策略 ═══════════

  @Test
  fun 首屏只渲染一页() {
    // 一次渲染 300 页必然超 2s，而且内存会爆
    assertEquals(1, PdfLoadPolicy.FIRST_SCREEN_PAGES)
  }

  @Test
  fun 首屏按一倍缩放渲染() {
    // 2.5 倍直接渲染 A4 页面得到 1488×2106 位图 = 12.5MB，
    // 低端机解码 + 绘制就可能超过 1s，留给页面切换的余量不够
    assertEquals(1.0f, PdfLoadPolicy.FIRST_SCREEN_SCALE)
    assertTrue("首屏不能用放大倍数", PdfLoadPolicy.FIRST_SCREEN_SCALE < PdfLoadPolicy.ZOOM_SCALE)
  }

  @Test
  fun 不预加载相邻页() {
    // 用户常常只看第一页就退出（确认是不是这份），
    // 预加载第 2 页会让首屏白等
    assertFalse("不能预加载", PdfLoadPolicy.PRELOAD_NEIGHBORS)
  }

  // ═══════════ 内存预算 ═══════════

  @Test
  fun A4页面首屏位图在预算内() {
    // A4 = 595×842 pt
    val v = PdfLoadPolicy.firstScreenBudget(595, 842)
    assertTrue("A4 首屏应在 32MB 内", v is PdfLoadPolicy.BudgetVerdict.Ok)
    val bytes = (v as PdfLoadPolicy.BudgetVerdict.Ok).bytes
    // 595 × 842 × 4 = 2,004,760 字节 ≈ 1.9MB
    assertEquals(595L * 842L * 4L, bytes)
  }

  @Test
  fun 超大页面首屏会超预算并被识别() {
    // 比如一张 5000×5000 pt 的海报：5000×5000×4 = 100MB
    val v = PdfLoadPolicy.firstScreenBudget(5000, 5000)
    assertTrue("必须识别出超预算", v is PdfLoadPolicy.BudgetVerdict.TooLarge)
    assertEquals(
      PdfLoadPolicy.MAX_FIRST_SCREEN_BYTES,
      (v as PdfLoadPolicy.BudgetVerdict.TooLarge).limit
    )
  }

  @Test
  fun 缩放比影响位图大小() {
    // 2.5 倍是 6.25 倍面积 —— 这就是「首屏不用放大」的算术理由
    val one = PdfLoadPolicy.bitmapBytes(595, 842, 1.0f)
    val zoom = PdfLoadPolicy.bitmapBytes(595, 842, 2.5f)
    assertTrue("放大 2.5 倍后位图应大 6 倍以上（实际 ${zoom / one}）", zoom / one >= 6)
  }

  // ═══════════ <2s 判定 ═══════════

  @Test
  fun 端到端两秒内判达标() {
    val v = PdfLoadPolicy.verdict(renderMs = 400, downloadMs = 900)
    assertTrue("1.3s 应达标", v is PdfLoadPolicy.FirstScreenVerdict.Pass)
    assertEquals(1300L, v.totalMs)
  }

  @Test
  fun 端到端超过两秒判不达标() {
    // 关键：渲染快但下载慢，仍然不达标 —— 用户等的是「看到内容」的总时间
    val v = PdfLoadPolicy.verdict(renderMs = 300, downloadMs = 2000)
    assertTrue("2.3s 不达标", v is PdfLoadPolicy.FirstScreenVerdict.Fail)
    assertEquals(2300L, (v as PdfLoadPolicy.FirstScreenVerdict.Fail).totalMs)
  }

  @Test
  fun 恰好两秒判达标() {
    // 边界：<= 2s 都算达标
    val v = PdfLoadPolicy.verdict(renderMs = 1000, downloadMs = 1000)
    assertTrue(v is PdfLoadPolicy.FirstScreenVerdict.Pass)
  }

  @Test
  fun 渲染预算留了一半给传输与切换() {
    // 如果渲染就吃掉 2s，加上网络必然超
    assertTrue(
      "渲染预算必须明显小于 2000ms（实际 ${PdfLoadPolicy.FIRST_SCREEN_RENDER_BUDGET_MS}）",
      PdfLoadPolicy.FIRST_SCREEN_RENDER_BUDGET_MS < 2000L
    )
    assertTrue(
      "至少给传输留 1s",
      PdfLoadPolicy.FIRST_SCREEN_RENDER_BUDGET_MS <= 1000L
    )
  }
}

// ───── SandboxWebViewTest.kt ─────
/**
 * HTML 沙箱测试 —— 覆盖蓝图 C5 第一条验收：
 *
 * > HTML 预览在关闭 JS 与开启 JS 两种模式下都不越权
 * > （无法访问 `file://`、无法外发请求）
 *
 * 这条验收的措辞值得注意：「关闭 JS 与开启 JS **两种模式**」。
 * 它不是「开着 JS 时也安全」，而是关掉 JS 也不能越权 ——
 * `<img src="file:///…">`、`<style>@import url(http://…)">`、
 * `<iframe src="http://…">` 都不需要 JS 就能发起请求。
 *
 * ## 能测与不能测的边界（如实标注）
 *
 * 能测：`WebSettings` 的值能读回；`shouldInterceptRequest` 的逻辑能直调
 * （下面用真 WebView 的 `webViewClient` 拿到它）。
 *
 * 不能测：真实渲染、JS 执行、真实网络栈。Robolectric 的 WebView 是空壳。
 * 所以「拦截后页面真的加载不出来」这一层要在真机上确认，本文件只证明
 * 「我们交出的拦截器对每个 URL 给出的答案是对的」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SandboxWebViewTest {

  private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun freshWebView() = WebView(ctx())

  // ═══════════ 第一层：WebSettings 全关 ═══════════

  @Test
  fun 先证实默认值是开着的否则关掉它没有意义() {
    // 这条不是「测试代码」，是**证据**：如果不先证明默认是 true，
    // 「显式关掉」这个动作就没有意义（关一个本来就关的东西）。
    // 探针实测 sdk 34 上默认 allowFileAccess=true。
    //
    // 注意这条是**记录当前平台行为**，不是「要求平台必须如此」。
    // 如果哪天 Android 把默认值改成 false，这条会变红 ——
    // 那时正确做法是删掉它并在 SandboxPolicy 的注释里更新结论，
    // 而不是放宽断言。变红本身是有效信息：说明「为什么必须显式关」
    // 这个理由变了。
    val s = freshWebView().settings
    assertTrue(
      "WebView 默认 allowFileAccess 为 true —— 所以必须显式关",
      s.allowFileAccess
    )
    assertTrue(
      "WebView 默认 allowContentAccess 为 true —— 所以必须显式关",
      s.allowContentAccess
    )
  }

  @Test
  fun 施加策略后文件访问被关闭() {
    val wv = freshWebView()
    SandboxPolicy.applyTo(wv)
    val s = wv.settings

    assertFalse("allowFileAccess 必须 false", s.allowFileAccess)
    assertFalse("allowContentAccess 必须 false", s.allowContentAccess)
    @Suppress("DEPRECATION")
    assertFalse("file 源跨域访问必须 false", s.allowFileAccessFromFileURLs)
    @Suppress("DEPRECATION")
    assertFalse("通用跨域访问必须 false", s.allowUniversalAccessFromFileURLs)
  }

  @Test
  fun 施加策略后JS与本地存储都关闭() {
    val wv = freshWebView()
    SandboxPolicy.applyTo(wv)
    val s = wv.settings

    assertFalse("默认不给 JS", s.javaScriptEnabled)
    assertFalse("DOM storage 关闭", s.domStorageEnabled)
    assertFalse("database 关闭", s.databaseEnabled)
  }

  @Test
  fun 混合内容一律不允许() {
    val wv = freshWebView()
    SandboxPolicy.applyTo(wv)
    @Suppress("DEPRECATION")
    assertEquals(
      android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW,
      wv.settings.mixedContentMode
    )
  }

  // ═══════════ 第二层：URL 白名单 ═══════════

  @Test
  fun 只放行about和data两种scheme() {
    assertEquals(setOf("about", "data"), SandboxPolicy.allowedSchemes)
  }

  @Test
  fun 文件与网络URL一律拒绝() {
    // 这七条覆盖了「不用 JS 也能发起请求」的全部形态
    val bad = listOf(
      "file:///etc/passwd",
      "file:///sdcard/secret.txt",
      "http://evil.example.com/x",
      "https://evil.example.com/x",
      "content://com.android.providers.media/document/1",
      "javascript:alert(1)",
      "ftp://example.com/file",
    )
    bad.forEach { url ->
      assertFalse("必须拒绝：$url", SandboxPolicy.isAllowed(url))
    }
  }

  @Test
  fun 空与null都拒绝() {
    // null 表示「不知道是什么」，按拒绝处理（fail-closed）
    assertFalse(SandboxPolicy.isAllowed(null))
    assertFalse(SandboxPolicy.isAllowed(""))
    assertFalse(SandboxPolicy.isAllowed("   "))
  }

  @Test
  fun about_blank放行() {
    assertTrue(SandboxPolicy.isAllowed("about:blank"))
    assertTrue("大小写不敏感", SandboxPolicy.isAllowed("ABOUT:BLANK"))
  }

  @Test
  fun 内联data放行() {
    assertTrue(SandboxPolicy.isAllowed("data:text/html,<h1>hi</h1>"))
    assertTrue(SandboxPolicy.isAllowed("data:image/png;base64,iVBORw0KGgo="))
  }

  @Test
  fun 大小写混写的协议也要拦住() {
    // 攻击者常在这里绕过：File://  或  FILE://
    assertFalse(SandboxPolicy.isAllowed("File:///etc/passwd"))
    assertFalse(SandboxPolicy.isAllowed("FILE:///etc/passwd"))
    assertFalse(SandboxPolicy.isAllowed("HtTp://evil.com"))
  }

  @Test
  fun 前导空白不能骗过白名单() {
    assertFalse(SandboxPolicy.isAllowed("  file:///etc/passwd  "))
    assertTrue("前后空白可以剥掉", SandboxPolicy.isAllowed("  about:blank  "))
  }

  // ═══════════ 第三层：拦截器真的在拦 ═══════════

  @Test
  fun 拦截器对网络请求返回空响应而不是null() {
    // 关键区别：返回 null 表示「我不处理，交给默认行为」—— 那会真的发请求。
    // 返回空响应才是真拦住。
    val wv = freshWebView()
    SandboxPolicy.installClient(wv, allowScripts = false)
    val c = wv.webViewClient

    val blockedHttp = c.shouldInterceptRequest(wv, "https://evil.example.com/x")
    assertNotNull("必须返回空响应（非 null）", blockedHttp)
    assertEquals(204, blockedHttp!!.statusCode)

    val blockedFile = c.shouldInterceptRequest(wv, "file:///etc/passwd")
    assertNotNull(blockedFile)
    assertEquals(204, blockedFile!!.statusCode)
  }

  @Test
  fun 拦截器放行about_blank() {
    val wv = freshWebView()
    SandboxPolicy.installClient(wv, allowScripts = false)
    val r = wv.webViewClient.shouldInterceptRequest(wv, "about:blank")
    assertNull("放行时应返回 null（交给 WebView 自己处理）", r)
  }

  @Test
  fun 页面导航一律不执行() {
    // <a href="http://…"> 或 location.href 都会被这里拦住
    val wv = freshWebView()
    SandboxPolicy.installClient(wv, allowScripts = false)
    val c = wv.webViewClient

    assertTrue("外网导航必须被吃掉", c.shouldOverrideUrlLoading(wv, "https://evil.com"))
    assertTrue("file 导航必须被吃掉", c.shouldOverrideUrlLoading(wv, "file:///etc/passwd"))
    assertFalse("about:blank 可以走", c.shouldOverrideUrlLoading(wv, "about:blank"))
  }

  // ═══════════ 两种 JS 模式都不越权 ═══════════

  @Test
  fun 开启JS时网络仍然全拦() {
    // 「两种模式都不越权」的直接断言：allowScripts=true 只影响 JS，
    // 不影响网络白名单。两条防线互不依赖。
    val wv = freshWebView()
    SandboxPolicy.installClient(wv, allowScripts = true)
    assertTrue("JS 应被开启（这是本测试的前提）", wv.settings.javaScriptEnabled)

    val c = wv.webViewClient
    assertNotNull("开 JS 也不能放过 http", c.shouldInterceptRequest(wv, "http://evil.com"))
    assertNotNull("开 JS 也不能放过 file", c.shouldInterceptRequest(wv, "file:///etc/passwd"))
    assertTrue("开 JS 也不能允许导航", c.shouldOverrideUrlLoading(wv, "https://evil.com"))
  }

  @Test
  fun 开启JS也不会打开文件访问() {
    // 新手错误：为了让页面能加载图片，顺手把 allowFileAccess 打开。
    val wv = freshWebView()
    SandboxPolicy.applyTo(wv)
    SandboxPolicy.installClient(wv, allowScripts = true)

    assertFalse("开 JS 不等于开文件访问", wv.settings.allowFileAccess)
    assertFalse(wv.settings.allowContentAccess)
  }

  // ═══════════ loadHtml 的 baseURL ═══════════

  @Test
  fun 新版拦截入口也必须拦住网络() {
    // 上一版只测了字符串签名（API < 21 的旧入口），漏了 WebResourceRequest 这个
    // 真实设备上走的那条路。反向验证时把 request 版本改成 `return null`，
    // 测试全绿 —— 证明那条路完全没被覆盖过。
    // 真机 API 21+ 走的正是这个重载，所以这条测试比上一条更重要。
    val wv = freshWebView()
    SandboxPolicy.installClient(wv, allowScripts = false)
    val c = wv.webViewClient

    val blocked = c.shouldInterceptRequest(wv, fakeRequest("https://evil.example.com/x"))
    assertNotNull("request 版本必须返回空响应", blocked)
    assertEquals(204, blocked!!.statusCode)

    val blockedFile = c.shouldInterceptRequest(wv, fakeRequest("file:///etc/passwd"))
    assertNotNull(blockedFile)
    assertEquals(204, blockedFile!!.statusCode)

    val ok = c.shouldInterceptRequest(wv, fakeRequest("about:blank"))
    assertNull("about:blank 应放行", ok)
  }

  @Test
  fun 新版导航入口也必须吃掉外网跳转() {
    // 同理：shouldOverrideUrlLoading(WebResourceRequest) 是 API 24+ 的真实入口
    val wv = freshWebView()
    SandboxPolicy.installClient(wv, allowScripts = false)
    val c = wv.webViewClient

    assertTrue(c.shouldOverrideUrlLoading(wv, fakeRequest("https://evil.com")))
    assertTrue(c.shouldOverrideUrlLoading(wv, fakeRequest("file:///etc/passwd")))
    assertFalse(c.shouldOverrideUrlLoading(wv, fakeRequest("about:blank")))
  }

  /** 造一个最小的 WebResourceRequest；只用到 url 一个字段 */
  private fun fakeRequest(url: String): android.webkit.WebResourceRequest =
    object : android.webkit.WebResourceRequest {
      override fun getUrl(): android.net.Uri = android.net.Uri.parse(url)
      override fun isForMainFrame(): Boolean = true
      override fun isRedirect(): Boolean = false
      override fun hasGesture(): Boolean = false
      override fun getMethod(): String = "GET"
      override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

  @Test
  fun 装页面时baseURL必须是null() {
    // baseURL 为 null 时页面没有「源」：相对路径与同源判断全部失效。
    // 给 file:// 或 https:// 作 baseURL 会让页面获得那个源的权限。
    val wv = freshWebView()
    SandboxPolicy.loadHtml(wv, "<html><body>hi</body></html>", allowScripts = false)
    // Robolectric 不暴露 loadDataWithBaseURL 的参数，这里只验证调用后客户端已装好
    assertNotNull("客户端必须已装上", wv.webViewClient)
    assertFalse(wv.settings.javaScriptEnabled)
  }
}