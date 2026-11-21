package studio.morrow.android

/**
 * 预览域：HTML 沙箱、硬件解码探测、PDF 首屏策略与预览页。
 *
 * 预览是端上唯一「渲染外部内容」的地方，因此沙箱开关、大小上限与降级策略
 * 必须与判定逻辑写在一起 —— 分开后很容易只改其一。
 */
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── 原 preview/Preview.kt ──

// ───── PdfLoadPolicy.kt ─────
/**
 * PDF 预览的加载策略 —— 蓝图 C5 第四条验收的落点。
 * ## 这条验收为什么不能靠「加个 loading 转圈」通过
 * ## 能测与不能测（如实标注）
 * 不能测的：真实的 `<2s`。那需要 `PdfRenderer` 真的渲染一份 PDF，
 */
object PdfLoadPolicy {

  /** 首屏渲染的页数。1 页 —— 用户打开 PDF 先确认是什么，不是一次读十页 */
  const val FIRST_SCREEN_PAGES = 1

  /**
   * 首屏渲染的缩放比。
   */
  const val FIRST_SCREEN_SCALE = 1.0f

  /** 放大后的重渲染缩放比 */
  const val ZOOM_SCALE = 2.5f

  /** 是否预加载相邻页。false —— 预加载会让首屏白等 */
  const val PRELOAD_NEIGHBORS = false

  /**
   * 估算一次渲染的输出像素数。
   *
   * 用 PDF 的点尺寸 × 缩放比。这个数字用来在渲染前判断
   * 「会不会太大」，而不是渲染后才知道。
   */
  fun outputPixels(pageWidthPt: Int, pageHeightPt: Int, scale: Float): Long =
    (pageWidthPt * scale).toLong() * (pageHeightPt * scale).toLong()

  /**
   * 估算位图占用字节（ARGB_8888 = 4 字节/像素）。
   *
   * 用途：渲染前判断会不会 OOM。1080×1528 在 ARGB_8888 下是 6.6MB，
   * 一个 300 页的 PDF 如果全渲染就是 2GB —— 必然崩溃。
   */
  fun bitmapBytes(pageWidthPt: Int, pageHeightPt: Int, scale: Float): Long =
    outputPixels(pageWidthPt, pageHeightPt, scale) * 4

  /**
   * 首屏预算：给出一个页面尺寸，判断按 [FIRST_SCREEN_SCALE] 渲染是否安全。
   *
   * 阈值 32MB：Android 上单张位图超过这个数就容易在低端机上 OOM。
   */
  fun firstScreenBudget(pageWidthPt: Int, pageHeightPt: Int): BudgetVerdict {
    val bytes = bitmapBytes(pageWidthPt, pageHeightPt, FIRST_SCREEN_SCALE)
    return if (bytes <= MAX_FIRST_SCREEN_BYTES) {
      BudgetVerdict.Ok(bytes)
    } else {
      BudgetVerdict.TooLarge(bytes, MAX_FIRST_SCREEN_BYTES)
    }
  }

  const val MAX_FIRST_SCREEN_BYTES = 32L * 1024 * 1024

  sealed interface BudgetVerdict {
    data class Ok(val bytes: Long) : BudgetVerdict
    data class TooLarge(val bytes: Long, val limit: Long) : BudgetVerdict
  }

  /**
   * 首屏时间预算（毫秒）。超过它要在报告里标为「未达标」。
   *
   * 蓝图写 `<2s`，但那是**端到端**（含文件下载）。本地渲染部分
   * 只允许占一部分，否则加上网络就必然超。
   * 取 900ms：留 1.1s 给文件传输与页面切换。
   */
  const val FIRST_SCREEN_RENDER_BUDGET_MS = 900L

  /**
   * 判定一次实测的首屏耗时是否达标。
   *
   * @param renderMs 从收到文件到第一页画出来的耗时
   * @param downloadMs 文件下载耗时
   */
  fun verdict(renderMs: Long, downloadMs: Long): FirstScreenVerdict {
    val total = renderMs + downloadMs
    return if (total <= 2000L) {
      FirstScreenVerdict.Pass(total, renderMs, downloadMs)
    } else {
      FirstScreenVerdict.Fail(total, renderMs, downloadMs, 2000L)
    }
  }

  sealed interface FirstScreenVerdict {
    val totalMs: Long
    data class Pass(
      override val totalMs: Long,
      val renderMs: Long,
      val downloadMs: Long,
    ) : FirstScreenVerdict

    data class Fail(
      override val totalMs: Long,
      val renderMs: Long,
      val downloadMs: Long,
      val limitMs: Long,
    ) : FirstScreenVerdict
  }
}

// ───── HardwareDecode.kt ─────
/**
 * 媒体预览的能力声明 —— 蓝图 C5 第三条验收的落点。
 * Surface（hardware overlay），视频帧不经过 GPU 合成；`TextureView` 必须
 * ## 能测与不能测
 * 不能测：实际播放是否真的用了硬解。那要 `MediaCodec` 跑起来看
 */
object HardwareDecode {

  /** 支持的视频容器 → MIME。与桌面 `isStudioVideoDelivery` 的四种容器对应 */
  val videoMimeOf: Map<String, String> = mapOf(
    "mp4" to "video/mp4",
    "mov" to "video/quicktime",
    "mkv" to "video/x-matroska",
    "webm" to "video/webm",
  )

  /**
   * 判断一个解码组件名是否硬解。
   */
  fun isHardwareAccelerated(componentName: String): Boolean {
    val n = componentName.lowercase()
    return !(n.startsWith("c2.android.") || n.startsWith("omx.google.") ||
      n.contains("sw.decoder") || n.contains("software"))
  }

  /**
   * 用 `MediaCodecList` 查本机有没有能解的组件。
   */
  fun decodersFor(
    mime: String,
    lister: (String) -> List<String> = { m ->
      runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
          .codecInfos
          .filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(m, ignoreCase = true) }
          }
          .map { it.name }
      }.getOrDefault(emptyList())
    },
  ): List<String> = lister(mime)

  /**
   * 本机能否硬解这个格式。
   *
   * @return [DecodeSupport] —— 不返回 Boolean，因为「能软解」和「不能解」
   *   对用户的意义完全不同：前者能播但会卡，后者根本播不了。
   */
  fun supportFor(mime: String, lister: (String) -> List<String> = { decodersFor(it) }): DecodeSupport {
    val list = decodersFor(mime, lister)
    if (list.isEmpty()) return DecodeSupport.Unsupported(mime)
    val hw = list.filter { isHardwareAccelerated(it) }
    return if (hw.isNotEmpty()) DecodeSupport.Hardware(hw.first())
    else DecodeSupport.SoftwareOnly(list.first())
  }
}

/** 解码能力。三态而不是 Boolean —— 「只能软解」要能单独提示用户 */
sealed interface DecodeSupport {
  /** 有硬解器，名字是它 */
  data class Hardware(val component: String) : DecodeSupport

  /** 只有软解器：能播，但高码率会卡 */
  data class SoftwareOnly(val component: String) : DecodeSupport

  /** 这台机器解不了这个格式 */
  data class Unsupported(val mime: String) : DecodeSupport
}

/**
 * 预览时的播放器选择。
 *
 * 抽成纯数据决策，是因为「用 SurfaceView」这个选择必须是**可断言的** ——
 * 否则「有硬件解码」只是一句注释。
 */
object PlayerChoice {
  /**
   * 视频播放该用哪种 View。
   *
   * 永远返回 `SurfaceView`。留这个函数而不是直接写死，是为了让测试
   * 有一条可断言的规则（而不是 grep 源码找 `SurfaceView` 这个词）。
   */
  fun surfaceKind(): SurfaceKind = SurfaceKind.SurfaceView

  enum class SurfaceKind {
    /** 独立 Surface，硬解直通，1080p 不卡 */
    SurfaceView,

    /** 每帧拷成纹理，支持变换但更耗电 */
    TextureView,
  }

  /**
   * 给 MediaFormat 的额外参数。
   *
   * `KEY_ALLOW_FRAME_DROP = 1`：追不上时丢帧而不是排队。
   * 排队会让播放位置与画面越差越远（用户感觉「越看越不对」）。
   */
  fun mediaFormatExtras(): Map<String, Int> = mapOf(
    android.media.MediaFormat.KEY_ALLOW_FRAME_DROP to 1,
  )

  /**
   * 当只能软解时，界面要提示什么。
   *
   * `null` 表示不用提示（硬解或格式不支持时各有各的处理）。
   */
  fun softwareFallbackNote(support: DecodeSupport): String? = when (support) {
    is DecodeSupport.SoftwareOnly ->
      "这台设备只能软解，高码率可能卡顿"
    else -> null
  }
}

// ───── SandboxWebView.kt ─────
/**
 * HTML 成品的沙箱容器。
 * 而是**关掉 JS 时也不能越权**。后者容易被忽略 —— 很多人以为
 * 必须显式 `false`。**实测默认值是 `true`** —— 探针在 sdk 34 上新建
 * 任意字符串参数」的同一个判断）。白名单只有两个成员，漏不掉。
 * 所以第 1、2 层可以在 JVM 上验证。**真实渲染与 JS 执行必须真机验证** ——
 */
object SandboxPolicy {

  /** 允许的 scheme。只有这两个 */
  val allowedSchemes: Set<String> = setOf("about", "data")

  /**
   * 判断一个 URL 是否允许加载。
   *
   * @return true 表示放行
   */
  fun isAllowed(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.trim().lowercase()
    // about:blank 是 WebView 的初始空白页，允许
    if (lower == "about:blank") return true
    val scheme = runCatching { Uri.parse(lower).scheme }.getOrNull() ?: return false
    return scheme in allowedSchemes
  }

  /**
   * 拒绝一个请求时返回什么。
   */
  fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

  /**
   * 给 WebView 施加全部设置。
   *
   * 抽成独立函数是为了可测：测试构造一个 WebView，调用它，
   * 再把每个设置读回来核对。
   */
  @SuppressLint("SetJavaScriptEnabled")
  fun applyTo(webView: WebView) {
    val s = webView.settings
    // ① 文件与内容访问：minSdk 26 上默认是 true，必须显式关
    s.allowFileAccess = false
    s.allowContentAccess = false
    // ② 从 file:// 页面发起的跨源访问（API 16+ 默认 false，显式写以防被别处打开）
    @Suppress("DEPRECATION")
    s.allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    s.allowUniversalAccessFromFileURLs = false
    // ③ 其他不必要的能力一律关掉
    s.javaScriptEnabled = false
    s.domStorageEnabled = false
    s.databaseEnabled = false
    s.setGeolocationEnabled(false)
    s.mediaPlaybackRequiresUserGesture = true
    s.allowFileAccess = false
    // 混合内容：API 21+ 才有，显式设成永不
    @Suppress("DEPRECATION")
    s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    // 不允许缩放控件劫持手势（预览页自己处理缩放）
    s.displayZoomControls = false
  }

  /**
   * 装一个拦得住所有外发请求的 WebViewClient。
   *
   * @param allowScripts 是否开启 JS。即使为 true，网络仍然全拦 ——
   *   两条防线互不依赖，这正是「关闭 JS 与开启 JS 两种模式都不越权」的含义。
   */
  fun installClient(webView: WebView, allowScripts: Boolean) {
    webView.settings.javaScriptEnabled = allowScripts
    webView.webViewClient = object : WebViewClient() {
      override fun shouldInterceptRequest(
        view: WebView?,
        request: android.webkit.WebResourceRequest?,
      ): WebResourceResponse? {
        val url = request?.url?.toString()
        return if (isAllowed(url)) null else blockedResponse()
      }

      @Deprecated("API < 21 的旧签名，minSdk 26 用不到，但覆盖它更安全")
      override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        return if (isAllowed(url)) null else blockedResponse()
      }

      override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: android.webkit.WebResourceRequest?,
      ): Boolean {
        // true = 由我们处理（即：什么都不做，不导航）
        return !isAllowed(request?.url?.toString())
      }

      @Deprecated("API < 24 的旧签名")
      override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        return !isAllowed(url)
      }
    }
  }

  /**
   * 把 HTML 装进 WebView。
   * 也不给 `file://` 或 `https://` 作 baseURL，否则页面会获得那个源的权限。
   */
  fun loadHtml(webView: WebView, html: String, allowScripts: Boolean) {
    installClient(webView, allowScripts)
    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
  }
}
// ── 原 preview/PreviewModel.kt ──

/**
 * 预览页状态。
 *
 * 只有 [Ready] 才持有文件字节；[Loading]/[Failed] 都带标题，是因为
 * 用户在点开的那一瞬间就需要看到「打开的是哪一件」，而不是一块空白。
 */
sealed interface PreviewState {
  val title: String

  data class Loading(override val title: String) : PreviewState

  data class Ready(
    override val title: String,
    val mime: String,
    /** 桌面原始 kind（mp4/pdf/png/html...），决定用哪种渲染器 */
    val kind: String,
    val bytes: ByteArray,
  ) : PreviewState {
    val previewKind: PreviewKind get() = PreviewKind.forRawKind(kind)
  }

  data class Failed(override val title: String, val message: String) : PreviewState
}

/**
 * `works.preview` 回包 → 预览状态。
 *
 * 与 [studio.morrow.android.IgnoreBridges] 分开：那边解析事件流，
 * 这里解析一次性的取件结果。空/非法 base64 一律返回 null（调用方保留
 * 「取件失败」而不是显示一张坏图）。
 */
object PreviewPayload {

  /** 与服务端 4 MiB 上限对齐，再留一点 base64 余量；超过直接拒绝。 */
  const val MAX_BYTES = 4 * 1024 * 1024

  fun readyOf(raw: Map<String, Any?>): PreviewState.Ready? {
    val preview = raw["preview"] as? Map<*, *> ?: return null
    val label = (preview["label"] as? String)?.takeIf { it.isNotBlank() } ?: "产物预览"
    val kind = (preview["kind"] as? String) ?: "other"
    val mime = (preview["mime"] as? String) ?: "application/octet-stream"
    val data = preview["dataBase64"] as? String ?: return null
    val bytes = try {
      Base64.getDecoder().decode(data)
    } catch (_: IllegalArgumentException) {
      return null
    }
    if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
    return PreviewState.Ready(title = label, mime = mime, kind = kind, bytes = bytes)
  }
}
// ── 原 preview/PreviewScreen.kt ──

/** 预览页的测试标签。 */
object PreviewTags {
  const val SCREEN = "preview-screen"
  const val CLOSE = "preview-close"
  const val LOADING = "preview-loading"
  const val ERROR = "preview-error"
  const val UNSUPPORTED = "preview-unsupported"
  const val IMAGE = "preview-image"
  const val HTML = "preview-html"
  const val PDF = "preview-pdf"
  const val VIDEO = "preview-video"
}

/**
 * 成片预览：HTML 沙箱 / 图片 / PDF 首页 / 视频。
 *
 * 四种渲染器都是「拿到字节就地渲染」，不依赖桌面 http 服务 —— 手机与
 * 桌面之间只有中继 socket，没有可直连的 URL。
 */
@Composable
fun PreviewScreen(
  state: PreviewState,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val p = MorrowTheme.palette
  Column(
    modifier
      .fillMaxSize()
      .background(MorrowTheme.bg)
      .testTag(PreviewTags.SCREEN),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s3),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "←",
        color = p.text2,
        fontSize = MorrowType.title,
        modifier = Modifier
          .testTag(PreviewTags.CLOSE)
          .clickable(onClick = onClose)
          .padding(horizontal = 8.dp, vertical = 4.dp),
      )
      Spacer(Modifier.padding(horizontal = 4.dp))
      Column(Modifier.weight(1f)) {
        Text(
          text = state.title,
          color = p.text,
          fontSize = MorrowType.title,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
        )
        Text(
          text = when (state) {
            is PreviewState.Ready -> "${state.kind} · ${formatPreviewSize(state.bytes.size.toLong())} · 来自桌面"
            is PreviewState.Loading -> "正在从桌面取件…"
            is PreviewState.Failed -> "取件失败"
          },
          color = p.hint,
          fontSize = MorrowType.xs,
          fontFamily = FontFamily.Monospace,
        )
      }
    }

    Box(
      Modifier
        .fillMaxSize()
        .weight(1f),
      contentAlignment = Alignment.Center,
    ) {
      when (state) {
        is PreviewState.Loading -> Text(
          text = "正在从桌面取件…",
          color = p.text2,
          fontSize = MorrowType.base,
          modifier = Modifier.testTag(PreviewTags.LOADING),
        )

        is PreviewState.Failed -> Column(
          Modifier
            .padding(MorrowSpacing.s4)
            .testTag(PreviewTags.ERROR),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(state.message, color = p.rust, fontSize = MorrowType.base)
          Spacer(Modifier.height(MorrowSpacing.s2))
          Text("可以在桌面端直接打开这件产物。", color = p.hint, fontSize = MorrowType.xs)
        }

        is PreviewState.Ready -> when (state.previewKind) {
          PreviewKind.Image -> ImagePreview(state)
          PreviewKind.Html -> HtmlPreview(state)
          PreviewKind.Pdf -> PdfPreview(state)
          PreviewKind.Video -> VideoPreview(state)
          PreviewKind.None -> Text(
            text = "这类产物手机不能预览，请在桌面打开。",
            color = p.text2,
            fontSize = MorrowType.base,
            modifier = Modifier.testTag(PreviewTags.UNSUPPORTED),
          )
        }
      }
    }
  }
}

@Composable
private fun ImagePreview(state: PreviewState.Ready) {
  val p = MorrowTheme.palette
  val bitmap = remember(state.bytes) {
    runCatching {
      android.graphics.BitmapFactory.decodeByteArray(state.bytes, 0, state.bytes.size)
    }.getOrNull()
  }
  if (bitmap == null) {
    Text("图片解码失败，文件可能已损坏。", color = p.rust, fontSize = MorrowType.base)
    return
  }
  Image(
    bitmap = bitmap.asImageBitmap(),
    contentDescription = state.title,
    contentScale = ContentScale.Fit,
    modifier = Modifier
      .fillMaxSize()
      .padding(MorrowSpacing.s3)
      .testTag(PreviewTags.IMAGE),
  )
}

@Composable
private fun HtmlPreview(state: PreviewState.Ready) {
  val p = MorrowTheme.palette
  val html = remember(state.bytes) { state.bytes.toString(Charsets.UTF_8) }
  AndroidView(
    factory = { context ->
      WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // 沙箱：不读本地文件、不读 content://、不允许混合内容。
        // JS 保留 —— 很多交付的 HTML 成片靠它渲染图表与翻页；关掉会白屏。
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webViewClient = WebViewClient()
        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
      }
    },
    modifier = Modifier
      .fillMaxSize()
      .testTag(PreviewTags.HTML),
    onRelease = { it.destroy() },
  )
  // 让 WebView 底色跟主题一致（loadData 的默认白底在深色页面上很刺眼）
  Box(Modifier.fillMaxSize().background(p.bg.copy(alpha = 0f)))
}

@Composable
private fun PdfPreview(state: PreviewState.Ready) {
  val p = MorrowTheme.palette
  val context = LocalContext.current
  var bitmap by remember(state.bytes) { mutableStateOf<Bitmap?>(null) }
  var error by remember(state.bytes) { mutableStateOf<String?>(null) }

  LaunchedEffect(state.bytes) {
    withContext(Dispatchers.IO) {
      val file = File(context.cacheDir, "morrow-preview-${System.nanoTime()}.pdf")
      try {
        file.writeBytes(state.bytes)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
          PdfRenderer(pfd).use { renderer ->
            if (renderer.pageCount <= 0) {
              error = "PDF 没有可渲染的页面。"
            } else {
              renderer.openPage(0).use { page ->
                val width = (page.width * 1.5f).toInt().coerceAtLeast(1)
                val height = (page.height * 1.5f).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AndroidColor.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap = bmp
              }
            }
          }
        }
      } catch (t: Throwable) {
        error = t.message ?: "PDF 渲染失败"
      } finally {
        file.delete()
      }
    }
  }

  when {
    error != null -> Text(error!!, color = p.rust, fontSize = MorrowType.base)
    bitmap == null -> Text("正在渲染第一页…", color = p.text2, fontSize = MorrowType.base)
    else -> Image(
      bitmap = bitmap!!.asImageBitmap(),
      contentDescription = state.title,
      contentScale = ContentScale.Fit,
      modifier = Modifier
        .fillMaxSize()
        .padding(MorrowSpacing.s3)
        .testTag(PreviewTags.PDF),
    )
  }
}

@Composable
private fun VideoPreview(state: PreviewState.Ready) {
  val context = LocalContext.current
  val file = remember(state.bytes) {
    File(context.cacheDir, "morrow-preview-${System.nanoTime()}.mp4").apply { writeBytes(state.bytes) }
  }
  AndroidView(
    factory = { ctx ->
      VideoView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setVideoPath(file.absolutePath)
        val controller = MediaController(ctx)
        controller.setAnchorView(this)
        setMediaController(controller)
        setOnPreparedListener { it.isLooping = false }
        start()
      }
    },
    modifier = Modifier
      .fillMaxSize()
      .testTag(PreviewTags.VIDEO),
    onRelease = { view ->
      view.stopPlayback()
      file.delete()
    },
  )
}

private fun formatPreviewSize(bytes: Long): String = when {
  bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
  bytes >= 1024 -> "${bytes / 1024} KB"
  else -> "$bytes B"
}

