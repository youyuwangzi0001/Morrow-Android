package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ───── WorksModelTest.kt ─────
/**
 * 作品模型测试。
 *
 * 重点在蓝图 C5 第二条验收：
 *
 * > 进度无值时显示时长而非假百分比
 *
 * 这条的实质是「0% 和『不知道』不能混在一起」，
 * 与 C1 的 `UnknownTodayBlock`、C4 的未连接空态是同一条原则。
 */
class WorksModelTest {

  // ═══════════ 进度：无值 vs 零 ═══════════

  @Test
  fun 进度无值时是Elapsed而不是Percent零() {
    // 桌面 `progress?: number` 注释：「不可计算时省略」。
    // 省略 ≠ 0。若塌成 0.0，界面会画一根空进度条，
    // 用户以为刚起步，真实情况是「算不出来」。
    val p = StageProgress.of(null, startedAtMillis = 1_000L, nowMillis = 61_000L)

    assertTrue("无进度必须是 Elapsed", p is StageProgress.Elapsed)
    assertEquals("已跑 60 秒", 60_000L, (p as StageProgress.Elapsed).millis)
  }

  @Test
  fun 进度有值时是Percent() {
    val p = StageProgress.of(42, startedAtMillis = 0L, nowMillis = 1000L)
    assertTrue(p is StageProgress.Percent)
    assertEquals(42, (p as StageProgress.Percent).value)
  }

  @Test
  fun 零进度是合法的Percent零而不是Elapsed() {
    // 反向确认：桌上说 0 就是 0（能算出来确实是 0%），
    // 不能因为「0 看起来像没数据」就把它当 Elapsed。
    val p = StageProgress.of(0, startedAtMillis = 0L, nowMillis = 1000L)
    assertTrue("0 是有效百分比", p is StageProgress.Percent)
    assertEquals(0, (p as StageProgress.Percent).value)
  }

  @Test
  fun 百分比越界会被夹住而不是崩溃() {
    // 桌面偶尔会给出 100.0001 这种浮点误差
    assertEquals(100, (StageProgress.of(150, 0L, 0L) as StageProgress.Percent).value)
    assertEquals(0, (StageProgress.of(-5, 0L, 0L) as StageProgress.Percent).value)
  }

  @Test
  fun 时钟回拨时时长不会变负数() {
    // now 早于 started（设备时间被改）：不能显示负时长
    val p = StageProgress.of(null, startedAtMillis = 10_000L, nowMillis = 5_000L)
    assertEquals(0L, (p as StageProgress.Elapsed).millis)
  }

  // ═══════════ 三条子链 ═══════════

  @Test
  fun 三种形态的子链与桌面一致() {
    // 桌面 `studioStages()`（src/shared/studio.ts:1376）
    assertEquals(
      listOf("blueprint", "outline", "writing", "review", "delivery"),
      StageCatalog.chainOf(StudioKind.Short)
    )
    assertEquals(
      listOf("blueprint", "outline", "writing", "visuals", "review", "delivery"),
      StageCatalog.chainOf(StudioKind.Novel)
    )
    assertEquals(
      listOf(
        "blueprint", "outline", "storyboard", "visuals",
        "voice", "music", "timeline", "review", "delivery"
      ),
      StageCatalog.chainOf(StudioKind.Drama)
    )
  }

  @Test
  fun 漫剧不写正文中短篇不画分镜() {
    // 差异不是取舍，是三种作品真实的产出流程
    assertFalse("漫剧没有 writing", "writing" in StageCatalog.chainOf(StudioKind.Drama))
    assertFalse("中短篇没有 storyboard", "storyboard" in StageCatalog.chainOf(StudioKind.Short))
    assertTrue("漫剧有配音", "voice" in StageCatalog.chainOf(StudioKind.Drama))
  }

  @Test
  fun 十个阶段定义一个不少() {
    assertEquals(10, StageCatalog.defs.size)
  }

  @Test
  fun 阶段文案与桌面逐字一致() {
    // 桌面 STUDIO_STAGE_DEFS（src/shared/studio.ts:1362）
    val expected = mapOf(
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
    assertEquals("十条必须逐字相同", expected, StageCatalog.defs)
  }

  @Test
  fun 生成的流水线初始全部等待且无进度() {
    val pipe = StageCatalog.pipeline(StudioKind.Drama)
    assertEquals(9, pipe.size)
    assertTrue("全部等待", pipe.all { it.status == StageStatus.Waiting })
    assertTrue("全部无百分比（是 Elapsed）", pipe.all { it.progress is StageProgress.Elapsed })
  }

  // ═══════════ 19 种产物 kind 映射 ═══════════

  @Test
  fun 桌面二十种交付kind逐个映射() {
    // 桌面 StudioDelivery['kind']（src/shared/studio.ts:696）
    // 初版报告与测试都写成 19，实测是 20 —— 漏数了 cover 之外还差一个，
    // 逐字对齐后确认 20。
    val expecting = mapOf(
      "mp4" to PreviewKind.Video, "mov" to PreviewKind.Video,
      "mkv" to PreviewKind.Video, "webm" to PreviewKind.Video,
      "wav" to PreviewKind.None, "mp3" to PreviewKind.None,
      "m4a" to PreviewKind.None, "flac" to PreviewKind.None,
      "comic" to PreviewKind.None, "srt" to PreviewKind.None,
      "ass" to PreviewKind.None, "epub" to PreviewKind.None,
      "pdf" to PreviewKind.Pdf, "docx" to PreviewKind.None,
      "txt" to PreviewKind.None, "md" to PreviewKind.None,
      "package" to PreviewKind.None, "cover" to PreviewKind.Image,
      "folder" to PreviewKind.None, "png" to PreviewKind.Image,
    )
    assertEquals("StudioDelivery 共 20 种", 20, expecting.size)
    expecting.forEach { (raw, kind) ->
      assertEquals("$raw 应映射为 $kind", kind, PreviewKind.forRawKind(raw))
    }
  }

  @Test
  fun 桌面六种ArtifactKind也全部覆盖() {
    // 桌面 ArtifactKind（src/shared/types.ts:1036）——
    // 这是另一套产物体系，蓝图 C5 说的「HTML 预览」正是它。
    // 初版报告把 html 当成 StudioDelivery 的一员，两处都说错了。
    val artifactKinds = listOf("html", "markdown", "code", "svg", "text", "file")
    assertEquals("ArtifactKind 共 6 种", 6, artifactKinds.size)
    // html 必须能预览（沙箱 WebView 就是为它准备的）
    assertEquals(PreviewKind.Html, PreviewKind.forRawKind("html"))
    // 其余五种手机上不做预览，落到「在桌面打开」
    listOf("markdown", "code", "svg", "text", "file").forEach {
      assertEquals("$it 不可预览", PreviewKind.None, PreviewKind.forRawKind(it))
    }
  }

  @Test
  fun 未知kind一律不可预览() {
    // fail-closed：不认识的产物不能「猜一个预览方式」
    assertEquals(PreviewKind.None, PreviewKind.forRawKind("future_format"))
    assertEquals(PreviewKind.None, PreviewKind.forRawKind(""))
  }

  @Test
  fun 四种可预览方式各自有产物() {
    assertEquals(PreviewKind.Pdf, PreviewKind.forRawKind("pdf"))
    assertEquals(PreviewKind.Image, PreviewKind.forRawKind("png"))
    assertEquals(PreviewKind.Video, PreviewKind.forRawKind("mp4"))
    assertEquals(PreviewKind.Html, PreviewKind.forRawKind("html"))
  }

  // ═══════════ 作品与作品库 ═══════════

  private fun stage(id: String, status: StageStatus, progress: StageProgress = StageProgress.Elapsed(0L)) =
    WorkStage(id, id, id, "", status, progress, null)

  private fun work(
    id: String,
    kind: StudioKind = StudioKind.Short,
    stages: List<WorkStage> = StageCatalog.pipeline(kind),
    updated: Long = 0L,
  ) = Work(id, "作品 $id", kind, "", stages, emptyList(), updated)

  @Test
  fun 当前阶段是第一个未完成的() {
    val w = work(
      "a",
      stages = listOf(
        stage("a", StageStatus.Done),
        stage("b", StageStatus.Running),
        stage("c", StageStatus.Waiting),
      )
    )
    assertEquals("b", w.activeStage?.id)
  }

  @Test
  fun 全部完成时没有当前阶段() {
    val w = work(
      "a",
      stages = listOf(stage("a", StageStatus.Done), stage("b", StageStatus.Skipped))
    )
    assertNull("跳过的也算完成", w.activeStage)
  }

  @Test
  fun 完成度按已完成阶段计数() {
    val w = work(
      "a",
      stages = listOf(
        stage("a", StageStatus.Done),
        stage("b", StageStatus.Skipped),
        stage("c", StageStatus.Running),
        stage("d", StageStatus.Waiting),
      )
    )
    assertEquals(2 to 4, w.stageProgress())
  }

  @Test
  fun 作品库按最近更新倒序() {
    val list = listOf(
      work("old", updated = 100L),
      work("new", updated = 900L),
      work("mid", updated = 500L),
    )
    assertEquals(listOf("new", "mid", "old"), WorksLibrary.sorted(list).map { it.id })
  }

  @Test
  fun 正在跑与失败的作品各自能筛出来() {
    val running = work("r", stages = listOf(stage("x", StageStatus.Running)))
    val failed = work("f", stages = listOf(stage("y", StageStatus.Failed)))
    val idle = work("i", stages = listOf(stage("z", StageStatus.Waiting)))
    val all = listOf(running, failed, idle)

    assertEquals(listOf("r"), WorksLibrary.running(all).map { it.id })
    assertEquals(listOf("f"), WorksLibrary.failed(all).map { it.id })
  }

  @Test
  fun 有失败阶段的作品既不算正在跑也不算完成() {
    // 失败的作品需要用户注意，但它不在「正在跑」里 ——
    // 顶栏读数如果把失败算进运行中，用户会以为还在跑。
    val w = work("f", stages = listOf(stage("x", StageStatus.Failed)))
    assertTrue("失败能被单独筛出", WorksLibrary.failed(listOf(w)).isNotEmpty())
    assertTrue("不在运行中列表", WorksLibrary.running(listOf(w)).isEmpty())
    assertEquals("失败不算当前阶段之外的已完成", 0, w.stageProgress().first)
  }

  // ═══════════ 产物 ═══════════

  @Test
  fun 不能预览的产物仍然存在只是不可点() {
    // 藏起来会让作品库看起来缺东西；用户需要知道「它做出来了」
    val p = WorkProduct("1", "漫画包", "comic", 1024L, 0L, null)
    assertFalse("不可预览", p.previewable)
    assertNotNull("但对象本身要在", p)
  }

  @Test
  fun 可预览的产物按kind判定() {
    assertTrue(WorkProduct("1", "成片", "mp4", 0, 0, "https://x").previewable)
    assertTrue(WorkProduct("2", "PDF", "pdf", 0, 0, "https://x").previewable)
    assertFalse(WorkProduct("3", "字幕", "srt", 0, 0, "https://x").previewable)
  }

  // ═══════════ 形态解析 ═══════════

  @Test
  fun 未知形态按中短篇处理() {
    assertEquals(StudioKind.Short, studioKindOf(null))
    assertEquals(StudioKind.Short, studioKindOf("future"))
    assertEquals(StudioKind.Drama, studioKindOf("drama"))
    assertEquals(StudioKind.Novel, studioKindOf("novel"))
  }

  @Test
  fun 阶段状态全部八个都能解析() {
    val cases = mapOf(
      "waiting" to StageStatus.Waiting, "ready" to StageStatus.Ready,
      "running" to StageStatus.Running, "review" to StageStatus.Review,
      "done" to StageStatus.Done, "failed" to StageStatus.Failed,
      "stale" to StageStatus.Stale, "skipped" to StageStatus.Skipped,
    )
    cases.forEach { (wire, st) -> assertEquals(wire, st, StageStatus.fromWire(wire)) }
    // 未知按等待：不会误报「完成」也不会误报「失败」
    assertEquals(StageStatus.Waiting, StageStatus.fromWire("unknown"))
  }
}

// ───── WorksScreenTest.kt ─────
/**
 * 作品页渲染测试。
 *
 * 重点覆盖蓝图 C5 的第二条验收：
 *
 * > 进度无值时显示时长而非假百分比
 *
 * 这条在界面上的判据是**不能出现任何百分比数字，也不能出现进度条** ——
 * 一个 0% 的空进度条就是「假百分比」的典型形态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class WorksScreenTest {

  @get:Rule val compose = createComposeRule()

  private fun stage(
    id: String,
    status: StageStatus = StageStatus.Waiting,
    progress: StageProgress = StageProgress.Elapsed(0L),
    message: String? = null,
  ): WorkStage {
    val (label, short, desc) = StageCatalog.defs.getValue(id)
    return WorkStage(id, label, short, desc, status, progress, message)
  }

  private fun work(
    id: String = "w1",
    kind: StudioKind = StudioKind.Short,
    stages: List<WorkStage> = StageCatalog.pipeline(kind),
    products: List<WorkProduct> = emptyList(),
    updated: Long = 0L,
    title: String = "重构登录页",
  ) = Work(id, title, kind, "", stages, products, updated)

  private fun render(
    works: List<Work>,
    online: Boolean = true,
    onOpenStage: (String) -> Unit = {},
    onOpenProduct: (WorkProduct) -> Unit = {},
  ) {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        WorksScreen(
          works = works,
          onBack = {},
          onOpenStage = onOpenStage,
          onOpenProduct = onOpenProduct,
          online = online,
        )
      }
    }
    compose.waitForIdle()
  }

  // ═══════════ 验收 2：进度无值时显示时长 ═══════════

  @Test
  fun 进度无值时显示时长而不显示百分比() {
    // 桌面 `progress?: number` 不可计算时省略 → 手机显示「已跑 09:41」
    val w = work(
      stages = listOf(
        stage("blueprint", StageStatus.Done),
        stage("outline", StageStatus.Running, StageProgress.Elapsed(581_000L)),
        stage("writing"),
        stage("review"),
        stage("delivery"),
      )
    )
    render(listOf(w))

    compose.onNodeWithText("09:41").assertIsDisplayed()
    // 关键：不能出现任何百分比
    assertEquals(
      "进度无值时不能出现百分比",
      0,
      compose.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().size
    )
    // 「完成度暂不可算」要明说，而不是让用户对着一个空条猜
    compose.onNodeWithText("完成度暂不可算").assertIsDisplayed()
  }

  @Test
  fun 进度有值时显示真实百分比() {
    val w = work(
      stages = listOf(
        stage("blueprint", StageStatus.Done),
        stage("outline", StageStatus.Running, StageProgress.Percent(42)),
        stage("writing"), stage("review"), stage("delivery"),
      )
    )
    render(listOf(w))

    compose.onNodeWithText("42%").assertIsDisplayed()
    // 有百分比时不说「暂不可算」
    assertEquals(
      0,
      compose.onAllNodesWithText("完成度暂不可算").fetchSemanticsNodes().size
    )
  }

  @Test
  fun 零进度显示为0percent而不是时长() {
    // 反向：桌面说 0 就是真 0%，不能因为「看起来像没数据」当 Elapsed
    val w = work(
      stages = listOf(
        stage("blueprint", StageStatus.Running, StageProgress.Percent(0)),
        stage("outline"), stage("writing"), stage("review"), stage("delivery"),
      )
    )
    render(listOf(w))
    compose.onNodeWithText("0%").assertIsDisplayed()
  }

  @Test
  fun 时长格式超过一小时带小时位() {
    assertEquals("09:41", formatElapsedForTest(581_000L))
    assertEquals("1:02:33", formatElapsedForTest(3_753_000L))
    assertEquals("00:00", formatElapsedForTest(0L))
    assertEquals("00:00", formatElapsedForTest(-5000L))
  }

  /** 通过反射拿 internal 函数会太绕；这里直接调 UI 层的同一份实现 */
  private fun formatElapsedForTest(millis: Long): String =
    formatClockDuration(millis)

  // ═══════════ 阶段流水线 ═══════════

  @Test
  fun 五个阶段全部渲染出来() {
    render(listOf(work(kind = StudioKind.Short)))
    listOf("blueprint", "outline", "writing", "review", "delivery").forEach { id ->
      compose.onNodeWithTag(WorksTags.stage("w1", id)).assertIsDisplayed()
    }
  }

  @Test
  fun 漫剧渲染九个阶段() {
    render(listOf(work(kind = StudioKind.Drama)))
    // 逐个确认 9 个阶段格都在（不用 substring 匹配 —— 那样的断言
    // 只能证明「有 9 个以某前缀开头的节点」，证明不了是哪 9 个）
    listOf(
      "blueprint", "outline", "storyboard", "visuals", "voice",
      "music", "timeline", "review", "delivery",
    ).forEach { id ->
      compose.onNodeWithTag(WorksTags.stage("w1", id)).assertIsDisplayed()
    }
    // 反向：漫剧不该出现 writing（那是中短篇/长篇的阶段）
    assertEquals(
      "漫剧不写正文，不该有 writing 格",
      0,
      compose.onAllNodesWithTag(WorksTags.stage("w1", "writing")).fetchSemanticsNodes().size
    )
  }

  @Test
  fun 失败阶段明确写出失败在哪个环节() {
    // 用户最需要知道的是「卡在哪」，不是「有失败」
    val w = work(
      stages = listOf(
        stage("blueprint", StageStatus.Done),
        stage("outline", StageStatus.Done),
        stage("writing", StageStatus.Failed, message = "模型返回空内容"),
        stage("review"), stage("delivery"),
      )
    )
    render(listOf(w))
    compose.onNodeWithText("失败在").assertIsDisplayed()
    compose.onNodeWithText("正文写作").assertIsDisplayed()
    compose.onNodeWithText("模型返回空内容").assertIsDisplayed()
  }

  @Test
  fun 失败但没原因时明说原因未上报() {
    // 与 C3 卡住依据同一条原则：不编造理由
    val w = work(
      stages = listOf(
        stage("blueprint", StageStatus.Failed, message = null),
        stage("outline"), stage("writing"), stage("review"), stage("delivery"),
      )
    )
    render(listOf(w))
    compose.onNodeWithText("原因未上报").assertIsDisplayed()
  }

  @Test
  fun 全部完成时说明全部阶段已完成() {
    val w = work(
      stages = listOf(
        stage("blueprint", StageStatus.Done),
        stage("outline", StageStatus.Done),
        stage("writing", StageStatus.Done),
        stage("review", StageStatus.Skipped),
        stage("delivery", StageStatus.Done),
      )
    )
    render(listOf(w))
    compose.onNodeWithText("全部 5 个阶段已完成").assertIsDisplayed()
  }

  // ═══════════ 成品 ═══════════

  @Test
  fun 能预览的成品标记为预览() {
    val w = work(
      stages = listOf(stage("blueprint", StageStatus.Done), stage("outline", StageStatus.Done),
        stage("writing", StageStatus.Done), stage("review", StageStatus.Done),
        stage("delivery", StageStatus.Done)),
      products = listOf(WorkProduct("p1", "成片", "mp4", 18_000_000L, 0L, "https://x")),
    )
    render(listOf(w))
    compose.onNodeWithText("视频").assertIsDisplayed()
    compose.onNodeWithText("预览 →").assertIsDisplayed()
  }

  @Test
  fun 不能预览的成品明写在桌面打开() {
    // 藏起来会让作品库看起来缺东西；用户需要知道「它做出来了」
    val w = work(
      products = listOf(WorkProduct("p1", "漫画包", "comic", 5_000_000L, 0L, null)),
    )
    render(listOf(w))
    compose.onNodeWithText("在桌面").assertIsDisplayed()
    assertEquals(
      "不能预览时不该出现「预览 →」",
      0,
      compose.onAllNodesWithText("预览 →").fetchSemanticsNodes().size
    )
  }

  @Test
  fun 点能预览的成品会带出它() {
    var opened: WorkProduct? = null
    val product = WorkProduct("p1", "成片", "mp4", 1000L, 0L, "https://x")
    val w = work(products = listOf(product))
    render(listOf(w), onOpenProduct = { opened = it })

    compose.onNodeWithTag(WorksTags.product("w1", "p1")).performClick()
    compose.waitForIdle()
    assertEquals("p1", opened?.id)
  }

  @Test
  fun 点不能预览的成品没有反应() {
    var opened: WorkProduct? = null
    val w = work(products = listOf(WorkProduct("p1", "漫画包", "comic", 1000L, 0L, null)))
    render(listOf(w), onOpenProduct = { opened = it })

    compose.onNodeWithTag(WorksTags.product("w1", "p1")).performClick()
    compose.waitForIdle()
    assertNull("不可预览的产物点了不该有任何反应", opened)
  }

  // ═══════════ 顶栏读数与排序 ═══════════

  @Test
  fun 顶栏报运行中与失败的件数() {
    val running = work("a", stages = listOf(stage("blueprint", StageStatus.Running)))
    val failed = work("b", stages = listOf(stage("blueprint", StageStatus.Failed)))
    render(listOf(running, failed))

    compose.onNodeWithText("2 件 · 1 件在跑 · 1 件失败").assertIsDisplayed()
  }

  @Test
  fun 失败的作品排在最前() {
    // 一件失败的作品比十件刚更新完的作品更值得注意
    val failed = work("f", stages = listOf(stage("blueprint", StageStatus.Failed)), updated = 100L)
    val fresh = work("n", stages = listOf(stage("blueprint", StageStatus.Done)), updated = 900L)
    render(listOf(fresh, failed))

    val failedTop = compose.onNodeWithTag(WorksTags.card("f")).fetchSemanticsNode().boundsInRoot.top
    val freshTop = compose.onNodeWithTag(WorksTags.card("n")).fetchSemanticsNode().boundsInRoot.top
    org.junit.Assert.assertTrue("失败的要排前面（实际 f=$failedTop n=$freshTop）", failedTop < freshTop)
  }

  @Test
  fun 都正常时按最近更新倒序() {
    val old = work("old", stages = listOf(stage("blueprint", StageStatus.Done)), updated = 100L)
    val new = work("new", stages = listOf(stage("blueprint", StageStatus.Done)), updated = 900L)
    render(listOf(old, new))

    val newTop = compose.onNodeWithTag(WorksTags.card("new")).fetchSemanticsNode().boundsInRoot.top
    val oldTop = compose.onNodeWithTag(WorksTags.card("old")).fetchSemanticsNode().boundsInRoot.top
    org.junit.Assert.assertTrue("新的在前（实际 new=$newTop old=$oldTop）", newTop < oldTop)
  }

  // ═══════════ 空态 ═══════════

  @Test
  fun 在线空态说电脑上还没有作品() {
    render(emptyList(), online = true)
    compose.onNodeWithTag(WorksTags.EMPTY).assertIsDisplayed()
    compose.onNodeWithText("电脑上还没有作品").assertIsDisplayed()
  }

  @Test
  fun 未连接空态说不知道而不是没有作品() {
    // 与 C1 / C4 同一条原则：「没有」和「不知道」含义不同
    render(emptyList(), online = false)
    compose.onNodeWithText("还不知道有没有作品").assertIsDisplayed()
    assertEquals(
      "不能同时出现肯定句",
      0,
      compose.onAllNodesWithText("电脑上还没有作品").fetchSemanticsNodes().size
    )
  }

  @Test
  fun 未连接时顶栏不报零件在跑() {
    render(emptyList(), online = false)
    compose.onNodeWithText("未连接 · 读不到作品").assertIsDisplayed()
  }

  // ═══════════ 页面联动 ═══════════

  @Test
  fun 点看阶段详情带出作品id() {
    var opened: String? = null
    render(listOf(work(id = "w9")), onOpenStage = { opened = it })
    compose.onNodeWithText("看阶段详情").performClick()
    compose.waitForIdle()
    assertEquals("w9", opened)
  }

  @Test
  fun 进度变化后界面跟着变() {
    // 作品不是 Compose 状态，靠传新列表重组
    var works by mutableStateOf(
      listOf(work(stages = listOf(
        stage("blueprint", StageStatus.Running, StageProgress.Percent(10)),
        stage("outline"), stage("writing"), stage("review"), stage("delivery"),
      )))
    )
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        WorksScreen(works = works, onBack = {}, onOpenStage = {}, onOpenProduct = {})
      }
    }
    compose.waitForIdle()
    compose.onNodeWithText("10%").assertIsDisplayed()

    works = listOf(work(stages = listOf(
      stage("blueprint", StageStatus.Done),
      stage("outline", StageStatus.Running, StageProgress.Percent(60)),
      stage("writing"), stage("review"), stage("delivery"),
    )))
    compose.waitForIdle()
    compose.onNodeWithText("60%").assertIsDisplayed()
  }
}

// ───── WorksNavigationTest.kt ─────
/**
 * 作品页的**到达性**测试。
 *
 * C3 踩过一次「渲染测试全绿但页面进不去」，C4 用反向验证（H 组失败 7 项）
 * 把这一类漏洞守住了。C5 沿用同一做法：走真实入口
 * MorrowApp → 工位页 → 点「去作品页」→ 作品页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class WorksNavigationTest {

  @get:Rule val compose = createComposeRule()

  private fun sampleWork(id: String = "w1") = Work(
    id = id,
    title = "重构登录页",
    kind = StudioKind.Drama,
    logline = "",
    stages = StageCatalog.pipeline(StudioKind.Drama).mapIndexed { i, st ->
      if (i == 0) st.copy(status = StageStatus.Done) else st
    },
    products = listOf(WorkProduct("p1", "分镜图", "png", 200_000L, 0L, "https://x")),
    updatedAtMillis = 0L,
  )

  @Test
  fun 从工位页点去作品页能进作品页() {
    // 工位页的「最近成品」块只在有成品时显示入口，
    // 所以要先给一个成品（Artifact）让那块渲染出来
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(
          works = listOf(sampleWork()),
          initialArtifacts = listOf(
            Artifact("Q3-sales-review.html", "HTML", 25_395L, 0L)
          ),
        )
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag("workbench-works").performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(WorksTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(WorksTags.card("w1")).assertIsDisplayed()
  }

  @Test
  fun 没有作品时作品页显示空态() {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(
          works = emptyList(),
          initialArtifacts = listOf(
            Artifact("a.html", "HTML", 1000L, 0L)
          ),
        )
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag("workbench-works").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(WorksTags.EMPTY).assertIsDisplayed()
  }

  @Test
  fun 返回能回到工位页() {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(
          works = listOf(sampleWork()),
          initialArtifacts = listOf(
            Artifact("a.html", "HTML", 1000L, 0L)
          ),
        )
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag("workbench-works").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(WorksTags.SCREEN).assertIsDisplayed()

    compose.onNodeWithTag("works-back").performClick()
    compose.waitForIdle()
    assertEquals(
      0,
      compose.onAllNodesWithTag(WorksTags.SCREEN).fetchSemanticsNodes().size
    )
  }

  @Test
  fun 作品页的进度无值时显示时长() {
    // 端到端确认「无进度显时长」这条验收在真实入口下也成立
    val work = Work(
      id = "w2", title = "长篇", kind = StudioKind.Novel, logline = "",
      stages = listOf(
        StageCatalog.pipeline(StudioKind.Novel)[0].copy(status = StageStatus.Done),
        StageCatalog.pipeline(StudioKind.Novel)[1].copy(
          status = StageStatus.Running,
          progress = StageProgress.Elapsed(125_000L),
        ),
      ) + StageCatalog.pipeline(StudioKind.Novel).drop(2),
      products = emptyList(),
      updatedAtMillis = 0L,
    )
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MorrowApp(
          works = listOf(work),
          initialArtifacts = listOf(
            Artifact("a.html", "HTML", 1000L, 0L)
          ),
        )
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag("workbench-works").performClick()
    compose.waitForIdle()
    compose.onNodeWithText("02:05").assertIsDisplayed()
    assertEquals(
      "无进度时不能出现百分比",
      0,
      compose.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().size
    )
  }
}