package studio.morrow.android

/**
 * 作品域：作品模型、流水线投影与作品页。
 *
 * 作品页展示的是桌面创作台的只读镜像：阶段名、进度与成片清单都来自投影，
 * 缺数据时保持空态而不是编造进度。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── 原 works/WorksModel.kt ──

/**
 * 作品库与阶段流水线的手机端模型。
 * 这条与 C1 的 `UnknownTodayBlock`（今日统计不能显示四个 0）、
 * C4 的未连接空态（不能写「没有」而要写「不知道」）是同一条原则：
 * > **「零」和「不知道」是两个不同的值，不能共用一种表示。**
 */

/** 作品形态。与桌面 `StudioKind` 一致 */
enum class StudioKind(val label: String) {
  Short("中短篇"),
  Novel("长篇"),
  Drama("漫剧"),
}

/** 阶段状态。与桌面 `StudioStatus` 一致（8 个值） */
enum class StageStatus(val label: String) {
  Waiting("等待"),
  Ready("就绪"),
  Running("进行中"),
  Review("待检查"),
  Done("完成"),
  Failed("失败"),
  Stale("已过期"),
  Skipped("已跳过"),
  ;

  /** 这个状态下是否「已经跑完」（成功或跳过） */
  val isFinished: Boolean get() = this == Done || this == Skipped

  /** 这个状态下是否「正在跑」 */
  val isActive: Boolean get() = this == Running

  companion object {
    fun fromWire(raw: String): StageStatus = when (raw) {
      "waiting" -> Waiting
      "ready" -> Ready
      "running" -> Running
      "review" -> Review
      "done" -> Done
      "failed" -> Failed
      "stale" -> Stale
      "skipped" -> Skipped
      // 未知状态按等待处理：它不会让界面声称「完成」，也不会声称「失败」
      else -> Waiting
    }
  }
}

/**
 * 一个阶段的进度。
 *
 * 用密封接口而不是两个字段，是为了让「有百分比」与「只有时长」在类型上互斥 ——
 * 调用方拿不到「既没有百分比又忘了给时长」这种状态。
 */
sealed interface StageProgress {
  /**
   * 可计算的完成度（0–100）。
   *
   * 只在阶段真的能算出百分比时存在（桌面 `progress?: number`）。
   * 不可计算时是 [Elapsed]。
   */
  data class Percent(val value: Int) : StageProgress {
    init {
      require(value in 0..100) { "百分比必须在 0..100，实际 $value" }
    }
  }

  /**
   * 只跑过一段时间，完成度无法计算。
   *
   * 界面显示 `12:34` 这样的时长。这是**唯一确定的事实**：
   * 见到它就知道真的跑了这么久，而不像 `0%` 那样什么也不知道。
   */
  data class Elapsed(val millis: Long) : StageProgress

  companion object {
    /**
     * 从桌面的可空 progress 构造。
     *
     * @param percent 桌面 `progress?: number`，null 表示不可计算
     * @param startedAtMillis 阶段开始时刻，用来算「已跑多久」
     * @param nowMillis 当前时刻
     */
    fun of(percent: Number?, startedAtMillis: Long, nowMillis: Long): StageProgress {
      if (percent != null) {
        return Percent(percent.toInt().coerceIn(0, 100))
      }
      return Elapsed((nowMillis - startedAtMillis).coerceAtLeast(0))
    }
  }
}

/** 一个阶段。 */
data class WorkStage(
  val id: String,
  /** 完整名，如「故事蓝图」 */
  val label: String,
  /** 短名，如「蓝图」 —— 手机横向空间窄，流水线上用短名 */
  val short: String,
  /** 一句话说明，用于点击后展开 */
  val description: String,
  val status: StageStatus,
  val progress: StageProgress,
  /** 桌面给的本阶段消息（可能为空） */
  val message: String?,
)

/**
 * 阶段定义表 —— 与桌面 `STUDIO_STAGE_DEFS`（`src/shared/studio.ts:1362`）逐字对应。
 *
 * 10 个阶段的 label / short / description 全部照抄，
 * 因为它们是桌面团队定过的文案，手机端改一个字都会让两边对不上。
 */
object StageCatalog {
  /** id → (label, short, description) */
  val defs: Map<String, Triple<String, String, String>> = linkedMapOf(
    "blueprint" to Triple("故事蓝图", "蓝图", "故事主线、人物、结构、风格与预算"),
    "outline" to Triple("大纲与节拍", "大纲", "章节细纲、剧本节拍与镜头计划"),
    "writing" to Triple("正文写作", "写作", "纸页手稿、候选稿与版本"),
    "storyboard" to Triple("分镜", "分镜", "镜头、台词、运镜与连续性"),
    "visuals" to Triple("画面", "画面", "分镜图、参考锁定与视频升级"),
    "voice" to Triple("配音", "配音", "角色声线、逐句配音与时间码"),
    "music" to Triple("音乐", "音乐", "配乐、音效与混音"),
    "timeline" to Triple("时间线", "时间线", "多轨剪辑、字幕与预览"),
    "review" to Triple("检查", "检查", "连续性、视觉、响度与缺失素材"),
    "delivery" to Triple("交付", "交付", "成片、成书与可继续的工程包"),
  )

  /**
   * 三种形态各走哪条子链 —— 与桌面 `studioStages()`（`src/shared/studio.ts:1376`）逐字对应。
   *
   * 中短篇 5 段、长篇 6 段、漫剧 9 段。差异不是取舍，是三种作品真实的产出流程：
   * 中短篇不画分镜也不配音，漫剧不写正文。
   */
  fun chainOf(kind: StudioKind): List<String> = when (kind) {
    StudioKind.Drama -> listOf(
      "blueprint", "outline", "storyboard", "visuals",
      "voice", "music", "timeline", "review", "delivery",
    )
    StudioKind.Novel -> listOf(
      "blueprint", "outline", "writing", "visuals", "review", "delivery",
    )
    StudioKind.Short -> listOf(
      "blueprint", "outline", "writing", "review", "delivery",
    )
  }

  /** 按形态生成一条流水线，所有阶段初始为「等待」且进度不可计算 */
  fun pipeline(kind: StudioKind): List<WorkStage> = chainOf(kind).map { id ->
    val (label, short, desc) = defs.getValue(id)
    WorkStage(
      id = id,
      label = label,
      short = short,
      description = desc,
      status = StageStatus.Waiting,
      progress = StageProgress.Elapsed(0L),
      message = null,
    )
  }
}

/** 作品形态从 wire 值解析。未知按中短篇（最轻的一条链） */
fun studioKindOf(raw: String?): StudioKind = when (raw) {
  "short" -> StudioKind.Short
  "novel" -> StudioKind.Novel
  "drama" -> StudioKind.Drama
  else -> StudioKind.Short
}

/**
 * 一件成品（桌面 `StudioDelivery` 的手机端投影）。
 *
 * 桌面有 19 种 `kind`，手机端按「怎么预览」归成四类 ——
 * 因为手机上只有四种预览手段（沙箱 WebView / 图片 / 视频 / PDF），
 * 其余一律「在桌面打开」。
 */
data class WorkProduct(
  val id: String,
  val label: String,
  /** 桌面原始 kind，如 `mp4`、`comic`、`pdf` */
  val rawKind: String,
  val bytes: Long,
  val createdAtMillis: Long,
  /** 下载 / 预览地址。null 表示还没有（产物刚建但文件未生成） */
  val url: String?,
  /** 所属作品 id（预览取件需要它） */
  val workId: String = "",
) {
  /** 用哪种预览方式打开 */
  val previewKind: PreviewKind get() = PreviewKind.forRawKind(rawKind)

  /**
   * 是否能在手机上预览。
   *
   * 不能预览的产品**仍然显示**（用户需要知道「它做出来了」），
   * 只是点下去提示「在桌面打开」—— 藏起来会让作品库看起来缺东西。
   */
  val previewable: Boolean get() = previewKind != PreviewKind.None
}

/**
 * 预览方式。四种，对应手机上真的能做到的四件事。
 *
 * 桌面有 19 种产物 kind，`comic`（漫画包）、`epub`、`package`（工程包）、
 * `folder` 这些在手机上预览不了 —— 归入 [None]，显示「在桌面打开」。
 */
enum class PreviewKind {
  /** HTML：沙箱 WebView */
  Html,

  /** 图片：直接解码显示 */
  Image,

  /** 视频：硬解播放 */
  Video,

  /** PDF：分页渲染 */
  Pdf,

  /** 手机上预览不了，只能去桌面 */
  None,
  ;

  companion object {
    /**
     * 映射 **两套**桌面产物体系的 kind。
     * 但报告与测试必须分开说清来源 —— 初版报告把 StudioDelivery 写成 19 个
     */
    fun forRawKind(raw: String): PreviewKind = when (raw) {
      // 沙箱 WebView：HTML 是唯一「能执行代码」的产物，所以它需要最严的沙箱
      "html", "htm" -> Html
      // 图片（cover 是封面图，png 是位图）
      "png", "jpg", "jpeg", "webp", "gif", "cover" -> Image
      // 视频
      "mp4", "mov", "mkv", "webm" -> Video
      // PDF
      "pdf" -> Pdf
      // 音频、字幕、文本、漫画包、工程包、目录 —— 手机上不做预览
      "wav", "mp3", "m4a", "flac",
      "srt", "ass",
      "epub", "docx", "txt", "md",
      "comic", "package", "folder",
      -> None
      else -> None
    }
  }
}

/** 作品（桌面 `StudioProject` 的手机端投影） */
data class Work(
  val id: String,
  val title: String,
  val kind: StudioKind,
  /** 一句话故事 */
  val logline: String,
  val stages: List<WorkStage>,
  val products: List<WorkProduct>,
  /** 最近一次活动的时刻，作品库按它倒序 */
  val updatedAtMillis: Long,
) {
  /** 当前进行到哪一阶段（第一个非完成且非跳过的）；全部完成则 null */
  val activeStage: WorkStage?
    get() = stages.firstOrNull { !it.status.isFinished }

  /** 完成度：已完成的阶段数 / 总阶段数 */
  fun stageProgress(): Pair<Int, Int> =
    stages.count { it.status.isFinished } to stages.size
}

/** 作品库的排序与筛选 */
object WorksLibrary {
  /** 按最近更新倒序 —— 用户打开作品库时最关心「刚才那个跑完没有」 */
  fun sorted(works: List<Work>): List<Work> = works.sortedByDescending { it.updatedAtMillis }

  /** 正在跑的作品（有任一阶段 Running），用于顶栏读数 */
  fun running(works: List<Work>): List<Work> =
    works.filter { w -> w.stages.any { it.status.isActive } }

  /** 有失败阶段的作品 —— 比「正在跑」更需要用户注意 */
  fun failed(works: List<Work>): List<Work> =
    works.filter { w -> w.stages.any { it.status == StageStatus.Failed } }
}
// ── 原 works/WorksProjection.kt ──

/**
 * 桌面作品摘要 → 作品页模型。
 */
object WorksProjection {

  /** null = 未知（保持页面「未连接/未知」文案）；空列表 = 明确没有作品。 */
  fun from(views: List<WorkView>?): List<Work>? =
    views?.map(::workOf)?.sortedByDescending { it.updatedAtMillis }

  fun workOf(view: WorkView): Work {
    val kind = kindOf(view.kind)
    val chain = StageCatalog.chainOf(kind)
    val current = chain.indexOf(view.stage)
    val stages = chain.mapIndexed { index, id ->
      val (label, short, description) = StageCatalog.defs.getValue(id)
      val percent = when {
        index < current -> 100
        index == current -> view.progress
        else -> 0
      }
      val status = when {
        current < 0 -> StageStatus.Waiting
        index < current -> StageStatus.Done
        index > current -> StageStatus.Waiting
        view.progress >= 100 -> StageStatus.Done
        else -> StageStatus.Running
      }
      WorkStage(
        id = id,
        label = label,
        short = short,
        description = description,
        status = status,
        progress = StageProgress.Percent(percent),
        message = null,
      )
    }
    return Work(
      id = view.id,
      title = view.title.ifBlank { "未命名作品" },
      kind = kind,
      logline = view.logline,
      stages = stages,
      products = view.products.map { product ->
        WorkProduct(
          id = product.id,
          label = product.label,
          rawKind = product.kind,
          bytes = product.bytes,
          createdAtMillis = product.createdAtMillis,
          // 文件走 `works.preview` 按需取，这里没有直链
          url = null,
          workId = view.id,
        )
      },
      updatedAtMillis = view.updatedAtMillis,
    )
  }

  /**
   * 桌面 `StudioKind` → 手机枚举。
   *
   * 未知值按中短篇处理：它是三态里最轻量的一种，界面不会因此显示
   * 不存在的配音/分镜阶段。等桌面新增形态时这里要同步加分支。
   */
  fun kindOf(raw: String): StudioKind = when (raw) {
    "novel" -> StudioKind.Novel
    "drama" -> StudioKind.Drama
    else -> StudioKind.Short
  }
}
// ── 原 works/WorksScreen.kt ──

// ───── WorksScreen.kt ─────
/**
 * 作品页。
 * 刚更新完的作品更值得注意 —— 后者只是汇报，前者需要干预。
 */
@Composable
fun WorksScreen(
  works: List<Work>,
  onBack: () -> Unit,
  onOpenStage: (String) -> Unit,
  onOpenProduct: (WorkProduct) -> Unit,
  modifier: Modifier = Modifier,
  /** 是否已连上桌面。未连接时不能声称「没有作品」 */
  online: Boolean = true,
) {
  val p = MorrowTheme.palette
  val sorted = WorksLibrary.sorted(works)
  val running = WorksLibrary.running(sorted)
  val failed = WorksLibrary.failed(sorted)

  // 失败排最前；其余按更新时间
  val ordered = failed + sorted.filter { it !in failed }

  Column(modifier.fillMaxSize().testTag(WorksTags.SCREEN)) {
    // ── 顶栏 ──
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = MorrowSpacing.s4)
        .padding(top = MorrowSpacing.s4, bottom = MorrowSpacing.s2),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "←",
        color = p.text2,
        fontSize = MorrowType.title,
        modifier = Modifier
          .clip(RoundedCornerShape(MorrowRadius.control))
          .testTag("works-back")
          .semantics { contentDescription = "返回" }
          .clickable(onClick = onBack)
          .padding(horizontal = 8.dp, vertical = 4.dp),
      )
      Spacer(Modifier.width(MorrowSpacing.s2))
      Column(Modifier.weight(1f)) {
        Text("作品", color = p.text, fontSize = MorrowType.title)
        Text(
          text = readout(online, sorted.size, running.size, failed.size),
          color = p.text3,
          fontSize = MorrowType.xs,
        )
      }
    }

    if (sorted.isEmpty()) {
      EmptyWorks(online = online, modifier = Modifier.weight(1f))
    } else {
      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .testTag(WorksTags.LIST),
        contentPadding = PaddingValues(
          start = MorrowSpacing.s4,
          end = MorrowSpacing.s4,
          top = MorrowSpacing.s1,
          bottom = MorrowSpacing.s6,
        ),
        verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2),
      ) {
        items(ordered, key = { it.id }) { work ->
          WorkCard(
            work = work,
            onOpenStage = onOpenStage,
            onOpenProduct = onOpenProduct,
          )
        }
      }
    }
  }
}

/**
 * 顶栏读数。
 *
 * 未连接时不说「0 件在跑」—— 那是替用户做判断（同 C1 / C4 的原则）。
 */
private fun readout(online: Boolean, total: Int, running: Int, failed: Int): String {
  if (!online) return "未连接 · 读不到作品"
  if (total == 0) return "还没有作品"
  val parts = mutableListOf("$total 件")
  if (running > 0) parts += "$running 件在跑"
  if (failed > 0) parts += "$failed 件失败"
  return parts.joinToString(" · ")
}

@Composable
private fun EmptyWorks(online: Boolean, modifier: Modifier = Modifier) {
  val p = MorrowTheme.palette
  Box(
    modifier = modifier.fillMaxWidth().testTag(WorksTags.EMPTY),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s1),
      modifier = Modifier.padding(horizontal = MorrowSpacing.s6),
    ) {
      Text(
        text = if (online) "电脑上还没有作品" else "还不知道有没有作品",
        color = p.text2,
        fontSize = MorrowType.ui,
      )
      Text(
        text = if (online) {
          "在桌面的创作台里新建一个，进度和成品会出现在这里。"
        } else {
          "未连接桌面时读不到作品库 —— 它不代表「没有作品」，只代表「还没连上」。"
        },
        color = p.text3,
        fontSize = MorrowType.xs,
      )
    }
  }
}

// ───── WorksComponents.kt ─────
/** 作品页 testTag */
object WorksTags {
  const val SCREEN = "works-screen"
  const val LIST = "works-list"
  const val EMPTY = "works-empty"
  fun card(id: String) = "works-card-$id"
  fun stage(id: String, stageId: String) = "works-stage-$id-$stageId"
  fun progress(id: String) = "works-progress-$id"
  fun product(workId: String, productId: String) = "works-product-$workId-$productId"
}

/**
 * 阶段流水线。
 */
@Composable
fun StagePipeline(
  workId: String,
  stages: List<WorkStage>,
  modifier: Modifier = Modifier,
) {
  val p = MorrowTheme.palette
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    stages.forEach { st ->
      val (fg, bg) = when (st.status) {
        StageStatus.Done -> p.patina to p.patina.copy(alpha = 0.14f)
        StageStatus.Skipped -> p.hint to p.hint.copy(alpha = 0.10f)
        StageStatus.Running -> p.gold to p.gold.copy(alpha = 0.16f)
        StageStatus.Review -> p.amber to p.amber.copy(alpha = 0.16f)
        StageStatus.Failed -> p.rust to p.rust.copy(alpha = 0.16f)
        StageStatus.Stale -> p.mauve to p.mauve.copy(alpha = 0.14f)
        StageStatus.Ready -> p.text2 to p.surfaceHover
        StageStatus.Waiting -> p.text3 to Color.Transparent
      }
      Column(
        modifier = Modifier
          .weight(1f)
          .testTag(WorksTags.stage(workId, st.id))
          .clip(RoundedCornerShape(MorrowRadius.xs))
          .background(bg)
          .then(
            if (st.status == StageStatus.Waiting) {
              Modifier.border(0.5.dp, p.lineStrong, RoundedCornerShape(MorrowRadius.xs))
            } else Modifier
          )
          .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
      ) {
        Text(
          text = st.short,
          color = fg,
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
        )
        // 一格小字标状态，代替图例
        Text(
          text = stageGlyph(st.status),
          color = fg.copy(alpha = 0.8f),
          fontSize = 8.sp,
          fontFamily = FontFamily.Monospace,
        )
      }
    }
  }
}

/** 8 种状态各自的字形。用符号而不是颜色：色弱用户也能读 */
private fun stageGlyph(s: StageStatus): String = when (s) {
  StageStatus.Done -> "✓"
  StageStatus.Skipped -> "–"
  StageStatus.Running -> "▶"
  StageStatus.Review -> "?"
  StageStatus.Failed -> "!"
  StageStatus.Stale -> "~"
  StageStatus.Ready -> "·"
  StageStatus.Waiting -> "·"
}

/**
 * 进度读数 —— 蓝图 C5 第二条验收的落点。
 */
@Composable
fun JobProgress(
  progress: StageProgress,
  modifier: Modifier = Modifier,
  tag: String = "",
) {
  val p = MorrowTheme.palette
  when (progress) {
    is StageProgress.Percent -> Row(
      modifier = modifier.fillMaxWidth().testTag(tag),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .weight(1f)
          .height(3.dp)
          .clip(RoundedCornerShape(MorrowRadius.xs))
          .background(p.lineStrong)
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth(progress.value / 100f)
            .height(3.dp)
            .clip(RoundedCornerShape(MorrowRadius.xs))
            .background(p.gold)
        )
      }
      Spacer(Modifier.width(MorrowSpacing.s2))
      Text(
        text = "${progress.value}%",
        color = p.gold,
        fontSize = MorrowType.code,
        fontFamily = FontFamily.Monospace,
      )
    }

    is StageProgress.Elapsed -> Row(
      modifier = modifier.fillMaxWidth().testTag(tag),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 没有进度条：故意。空条会被读成 0%
      Text(
        text = "已跑",
        color = p.text3,
        fontSize = MorrowType.xs,
      )
      Spacer(Modifier.width(MorrowSpacing.s1))
      Text(
        text = formatClockDuration(progress.millis),
        color = p.text2,
        fontSize = MorrowType.code,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.width(MorrowSpacing.s2))
      Text(
        text = "完成度暂不可算",
        color = p.text3,
        fontSize = MorrowType.xs,
      )
    }
  }
}

/**
 * 时长格式化：`09:41` / `1:02:33`。
 *
 * 只到秒。不写「约」、不写「~」—— 这是精确到秒的实测时长。与对话域的
 * [formatDurationSpoken]（「3 分 12 秒」）区分：同一个数字在成片时长与聊天里
 * 是两种读法，不能互相顶替。
 */
internal fun formatClockDuration(millis: Long): String {
  val total = (millis / 1000).coerceAtLeast(0)
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) {
    "%d:%02d:%02d".format(h, m, s)
  } else {
    "%02d:%02d".format(m, s)
  }
}

/** 字节格式化。只到一位小数，避免「18.0143 MB」这种假精度 */
internal fun formatReadableBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024) return "%.1f KB".format(kb)
  val mb = kb / 1024.0
  if (mb < 1024) return "%.1f MB".format(mb)
  return "%.2f GB".format(mb / 1024.0)
}

/**
 * 一件成品的行。
 *
 * 能预览的显示「预览 →」；不能预览的显示「在桌面打开」并且**不可点**。
 * 后者仍然显示，是因为用户需要知道「它做出来了」——
 * 藏起来会让作品库看起来缺东西。
 */
@Composable
fun ProductRow(
  workId: String,
  product: WorkProduct,
  onOpen: (WorkProduct) -> Unit,
  modifier: Modifier = Modifier,
) {
  val p = MorrowTheme.palette
  val canOpen = product.previewable
  Row(
    modifier = modifier
      .fillMaxWidth()
      .testTag(WorksTags.product(workId, product.id))
      .clip(RoundedCornerShape(MorrowRadius.control))
      .then(if (canOpen) Modifier.clickable { onOpen(product) } else Modifier)
      .padding(vertical = 6.dp, horizontal = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = previewLabel(product.previewKind),
      color = if (canOpen) p.gold else p.text3,
      fontSize = MorrowType.xs,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.width(42.dp),
    )
    Text(
      text = product.label,
      color = if (canOpen) p.text else p.text2,
      fontSize = MorrowType.base,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = formatReadableBytes(product.bytes),
      color = p.text3,
      fontSize = MorrowType.xs,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.width(MorrowSpacing.s2))
    Text(
      text = if (canOpen) "预览 →" else "在桌面",
      color = if (canOpen) p.gold else p.text3,
      fontSize = MorrowType.xs,
    )
  }
}

private fun previewLabel(k: PreviewKind): String = when (k) {
  PreviewKind.Html -> "HTML"
  PreviewKind.Image -> "图片"
  PreviewKind.Video -> "视频"
  PreviewKind.Pdf -> "PDF"
  PreviewKind.None -> "文件"
}

/**
 * 一件作品卡。
 *
 * 信息层级：标题 → 形态与完成度 → 阶段流水线 → 当前阶段读数 → 成品列表。
 * 把「当前阶段」单独一行而不是只靠格子高亮，是因为用户打开这一页
 * 最想知道的是「它现在在哪一步」，而不是自己去数哪一格亮着。
 */
@Composable
fun WorkCard(
  work: Work,
  onOpenStage: (String) -> Unit,
  onOpenProduct: (WorkProduct) -> Unit,
  modifier: Modifier = Modifier,
) {
  val p = MorrowTheme.palette
  val (done, total) = work.stageProgress()
  val active = work.activeStage
  val failed = work.stages.firstOrNull { it.status == StageStatus.Failed }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .testTag(WorksTags.card(work.id))
      .clip(RoundedCornerShape(MorrowRadius.card))
      .background(MorrowTheme.surface)
      .border(
        width = 0.5.dp,
        color = if (failed != null) p.rust.copy(alpha = 0.4f) else p.line,
        shape = RoundedCornerShape(MorrowRadius.card),
      )
      .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s3),
    verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2),
  ) {
    // ── 标题行 ──
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = work.title,
        color = p.text,
        fontSize = MorrowType.bodyLg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(MorrowSpacing.s2))
      Text(
        text = work.kind.label,
        color = p.text3,
        fontSize = MorrowType.xs,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.width(MorrowSpacing.s1))
      Text(
        // 完成度用「2/5」而不是百分比：阶段数是离散的，
        // 写 40% 会让人以为可以到 43%
        text = "$done/$total",
        color = if (done == total) p.patina else p.text2,
        fontSize = MorrowType.code,
        fontFamily = FontFamily.Monospace,
      )
    }

    StagePipeline(workId = work.id, stages = work.stages)

    // ── 当前阶段读数 ──
    if (failed != null) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("失败在", color = p.rust, fontSize = MorrowType.xs)
        Spacer(Modifier.width(MorrowSpacing.s1))
        Text(failed.label, color = p.rust, fontSize = MorrowType.base)
        Spacer(Modifier.weight(1f))
        Text(
          text = failed.message ?: "原因未上报",
          color = p.text3,
          fontSize = MorrowType.xs,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    } else if (active != null) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = active.label,
          color = p.text,
          fontSize = MorrowType.base,
        )
        Spacer(Modifier.width(MorrowSpacing.s2))
        JobProgress(
          progress = active.progress,
          modifier = Modifier.weight(1f),
          tag = WorksTags.progress(work.id),
        )
      }
    } else {
      Text(
        text = "全部 ${work.stages.size} 个阶段已完成",
        color = p.patina,
        fontSize = MorrowType.base,
      )
    }

    // ── 成品 ──
    if (work.products.isNotEmpty()) {
      Spacer(Modifier.height(2.dp))
      work.products.forEach { product ->
        ProductRow(workId = work.id, product = product, onOpen = onOpenProduct)
      }
    }

    // ── 进入该作品的阶段详情 ──
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(MorrowRadius.control))
        .border(0.5.dp, p.lineGold, RoundedCornerShape(MorrowRadius.control))
        .clickable { onOpenStage(work.id) }
        .padding(horizontal = 10.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("看阶段详情", color = p.gold, fontSize = MorrowType.base)
      Spacer(Modifier.weight(1f))
      Text("→", color = p.gold, fontSize = MorrowType.base, fontFamily = FontFamily.Monospace)
    }
  }
}
