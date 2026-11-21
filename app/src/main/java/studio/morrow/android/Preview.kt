package studio.morrow.android

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

object PdfLoadPolicy {

  /** 首屏渲染的页数。1 页 —— 用户打开 PDF 先确认是什么，不是一次读十页 */
  const val FIRST_SCREEN_PAGES = 1

  const val FIRST_SCREEN_SCALE = 1.0f

  /** 放大后的重渲染缩放比 */
  const val ZOOM_SCALE = 2.5f

  /** 是否预加载相邻页。false —— 预加载会让首屏白等 */
  const val PRELOAD_NEIGHBORS = false

  fun outputPixels(pageWidthPt: Int, pageHeightPt: Int, scale: Float): Long =
    (pageWidthPt * scale).toLong() * (pageHeightPt * scale).toLong()

  fun bitmapBytes(pageWidthPt: Int, pageHeightPt: Int, scale: Float): Long =
    outputPixels(pageWidthPt, pageHeightPt, scale) * 4

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

  const val FIRST_SCREEN_RENDER_BUDGET_MS = 900L

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

object HardwareDecode {

  /** 支持的视频容器 → MIME。与桌面 `isStudioVideoDelivery` 的四种容器对应 */
  val videoMimeOf: Map<String, String> = mapOf(
    "mp4" to "video/mp4",
    "mov" to "video/quicktime",
    "mkv" to "video/x-matroska",
    "webm" to "video/webm",
  )

  fun isHardwareAccelerated(componentName: String): Boolean {
    val n = componentName.lowercase()
    return !(n.startsWith("c2.android.") || n.startsWith("omx.google.") ||
      n.contains("sw.decoder") || n.contains("software"))
  }

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

object PlayerChoice {
  fun surfaceKind(): SurfaceKind = SurfaceKind.SurfaceView

  enum class SurfaceKind {
    /** 独立 Surface，硬解直通，1080p 不卡 */
    SurfaceView,

    /** 每帧拷成纹理，支持变换但更耗电 */
    TextureView,
  }

  fun mediaFormatExtras(): Map<String, Int> = mapOf(
    android.media.MediaFormat.KEY_ALLOW_FRAME_DROP to 1,
  )

  fun softwareFallbackNote(support: DecodeSupport): String? = when (support) {
    is DecodeSupport.SoftwareOnly ->
      "这台设备只能软解，高码率可能卡顿"
    else -> null
  }
}

object SandboxPolicy {

  /** 允许的 scheme。只有这两个 */
  val allowedSchemes: Set<String> = setOf("about", "data")

  fun isAllowed(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.trim().lowercase()
    if (lower == "about:blank") return true
    val scheme = runCatching { Uri.parse(lower).scheme }.getOrNull() ?: return false
    return scheme in allowedSchemes
  }

  fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

  @SuppressLint("SetJavaScriptEnabled")
  fun applyTo(webView: WebView) {
    val s = webView.settings
    s.allowFileAccess = false
    s.allowContentAccess = false
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
        return !isAllowed(request?.url?.toString())
      }

      @Deprecated("API < 24 的旧签名")
      override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        return !isAllowed(url)
      }
    }
  }

  fun loadHtml(webView: WebView, html: String, allowScripts: Boolean) {
    installClient(webView, allowScripts)
    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
  }
}

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
  /** 视频解码失败时显示的说明块（M-14）。黑屏不可断言，这个 tag 可以。 */
  const val VIDEO_ERROR = "preview-video-error"

  /** 只能软解时的提示（F-03）。null = 不显示。 */
  const val VIDEO_SOFT_NOTE = "preview-video-soft-note"
}

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
  val html = remember(state.bytes) { state.bytes.toString(Charsets.UTF_8) }
  AndroidView(
    factory = { context ->
      WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        )
        SandboxPolicy.applyTo(this)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        SandboxPolicy.loadHtml(this, html, allowScripts = true)
      }
    },
    modifier = Modifier
      .fillMaxSize()
      .testTag(PreviewTags.HTML),
    onRelease = { it.destroy() },
  )
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
                //
                //
                // 两个实际后果：
                //    常量改了界面不会变，改的人以为生效了；
                val verdict = PdfLoadPolicy.firstScreenBudget(page.width, page.height)
                if (verdict is PdfLoadPolicy.BudgetVerdict.TooLarge) {
                  val mb = verdict.bytes / 1024 / 1024
                  val limitMb = verdict.limit / 1024 / 1024
                  error = "这一页需要 ${mb}MB 内存（上限 ${limitMb}MB），" +
                    "这台设备渲染不开。请在电脑上查看，或导出一份分页版本。"
                } else {
                  // 两边各写一个数字正是这条 F 项的原形。
                  val scale = PdfLoadPolicy.FIRST_SCREEN_SCALE
                  val width = (page.width * scale).toInt().coerceAtLeast(1)
                  val height = (page.height * scale).toInt().coerceAtLeast(1)
                  val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                  bmp.eraseColor(AndroidColor.WHITE)
                  page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                  bitmap = bmp
                }
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
  //
  // 只会反复重试或以为应用坏了。
  //
  // 让「手机不支持」和「文件有问题」分开。
  var error by remember(state.bytes) { mutableStateOf<String?>(null) }

  //
  // 用户才会怀疑是网络、是文件、还是手机。
  //
  val softwareNote = remember(state.bytes) {
    runCatching {
      val mime = HardwareDecode.videoMimeOf[state.kind.lowercase()] ?: return@runCatching null
      PlayerChoice.softwareFallbackNote(HardwareDecode.supportFor(mime))
    }.getOrNull()
  }

  if (error != null) {
    Column(
      Modifier
        .fillMaxSize()
        .padding(MorrowSpacing.s5)
        .testTag(PreviewTags.VIDEO_ERROR),
      verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s3),
    ) {
      Text("这段视频在手机上放不了", color = MorrowTheme.palette.rust, fontSize = MorrowType.base)
      Text(error!!, color = MorrowTheme.text2, fontSize = MorrowType.sm)
      Text(
        "成品本身是好的，可以回桌面用系统播放器看；需要手机也能看的话，导出时选 MP4（H.264）。",
        color = MorrowTheme.hint,
        fontSize = MorrowType.sm,
      )
    }
    return
  }

  Column(Modifier.fillMaxSize()) {
    // 也无法在暂停时读到。
    if (softwareNote != null) {
      Text(
        text = softwareNote,
        color = MorrowTheme.palette.rust,
        fontSize = MorrowType.xs,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = MorrowSpacing.s3, vertical = MorrowSpacing.s2)
          .testTag(PreviewTags.VIDEO_SOFT_NOTE),
      )
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
        setOnErrorListener { _, what, _ ->
          error = describeVideoFailure(context, state, what)
          true // 已消费：不再走系统默认的「无法播放」弹窗
        }
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
}

internal fun describeVideoFailure(
  context: android.content.Context,
  state: PreviewState.Ready,
  what: Int,
): String {
  val kind = when (what) {
    -1010 -> "手机上没有能解这个格式的解码器。"
    -1007 -> "文件内容不完整或已损坏，可能是传输时被截断。"
    else -> "播放器报告了一个解码错误（代码 $what）。"
  }
  //
  val mime = HardwareDecode.videoMimeOf[state.kind.lowercase()]
  val capable = mime != null && runCatching { HardwareDecode.decodersFor(mime) }.getOrNull().orEmpty().isNotEmpty()
  val hint = if (mime == null) {
    "这个容器（${state.kind}）不在已知的视频格式里。"
  } else if (capable) {
    "本机有 ${state.kind} 的解码组件，问题更可能出在文件本身。"
  } else {
    "本机没有任何能解 ${state.kind} 的组件。"
  }
  return kind + hint
}

private fun formatPreviewSize(bytes: Long): String = Formatters.bytes(bytes)
