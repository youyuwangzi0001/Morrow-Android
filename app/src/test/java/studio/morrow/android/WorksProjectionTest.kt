package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.morrow.android.WorkProductView
import studio.morrow.android.WorkView

/**
 * 桌面作品摘要 → 作品页模型。
 *
 * 守住两件事：流水线能按当前阶段还原出「完成 / 进行中 / 等待」，
 * 以及没有成片数据时 products 真的是空 —— 不编造产品行。
 */
class WorksProjectionTest {

  private fun view(
    id: String = "w1",
    kind: String = "short",
    stage: String = "writing",
    progress: Int = 40,
    updatedAt: Long = 1_000L,
  ) = WorkView(
    id = id,
    title = "末日咖啡馆",
    kind = kind,
    logline = "末日后唯一还开着的咖啡馆",
    updatedAtMillis = updatedAt,
    stage = stage,
    progress = progress,
    chapters = 3,
    shots = 0,
    assets = 2,
    words = 1_200L,
  )

  @Test
  fun 当前阶段前后分别映射完成与等待() {
    val work = WorksProjection.workOf(view(stage = "writing", progress = 40))
    val chain = work.stages.map { it.id }
    assertEquals(listOf("blueprint", "outline", "writing", "review", "delivery"), chain)

    val blueprint = work.stages[0]
    assertEquals(StageStatus.Done, blueprint.status)
    assertEquals(100, (blueprint.progress as StageProgress.Percent).value)

    val writing = work.stages[2]
    assertEquals(StageStatus.Running, writing.status)
    assertEquals(40, (writing.progress as StageProgress.Percent).value)

    val review = work.stages[3]
    assertEquals(StageStatus.Waiting, review.status)
    assertEquals(0, (review.progress as StageProgress.Percent).value)
  }

  @Test
  fun 进度满百的当前阶段算完成() {
    val work = WorksProjection.workOf(view(stage = "writing", progress = 100))
    assertEquals(StageStatus.Done, work.stages[2].status)
    assertTrue("全部完成时不应再有进行中", work.stages.none { it.status == StageStatus.Running })
  }

  @Test
  fun 三种形态各走自己的流水线() {
    assertEquals(5, WorksProjection.workOf(view(kind = "short")).stages.size)
    assertEquals(6, WorksProjection.workOf(view(kind = "novel")).stages.size)
    assertEquals(9, WorksProjection.workOf(view(kind = "drama")).stages.size)
    assertEquals(StudioKind.Drama, WorksProjection.kindOf("drama"))
    assertEquals(StudioKind.Short, WorksProjection.kindOf("unknown-kind"))
  }

  @Test
  fun 成片清单映射为可预览产品并带上作品id() {
    val work = WorksProjection.workOf(
      view().copy(
        products = listOf(WorkProductView("d1", "成片", "mp4", 2_048L, 5L)),
      )
    )
    val product = work.products.single()
    assertEquals("d1", product.id)
    assertEquals("成片", product.label)
    assertEquals("w1", product.workId)
    assertEquals(2_048L, product.bytes)
    assertTrue(product.previewable)
  }

  @Test
  fun 摘要没有成片时不编造产品行() {
    val work = WorksProjection.workOf(view())
    assertTrue(work.products.isEmpty())
    assertEquals("末日咖啡馆", work.title)
    assertEquals(1_000L, work.updatedAtMillis)
  }

  @Test
  fun 未知与空作品列表语义不同() {
    assertNull("null = 还没拉到，不能当没有作品", WorksProjection.from(null))
    assertTrue(WorksProjection.from(emptyList())!!.isEmpty())
    // 多个作品按更新时间倒序
    val list = WorksProjection.from(listOf(view(id = "a", updatedAt = 1L), view(id = "b", updatedAt = 2L)))!!
    assertEquals(listOf("b", "a"), list.map { it.id })
  }

  // ─────────────── P1-15 / M-13：直读桌面状态，不靠 indexOf 推演 ───────────────

  @Test
  fun 桌面报失败时不再显示进行中() {
    // 回归：旧实现只用 chain.indexOf(stage) 一个标量推演 ——
    // 前面的都「完成」、后面的都「等待」、当前那个按 progress 猜。
    // 桌面把 writing 标成 failed，手机照样显示「进行中」，因为 indexOf 只知道位置。
    val w = view(stage = "outline", progress = 60).copy(
      stageStatuses = mapOf(
        "writing" to StageStatusView(status = "failed", progress = 60, message = "模型中断"),
      )
    )
    val work = WorksProjection.workOf(w)
    val writing = work.stages.first { it.id == "writing" }
    assertEquals("必须显示失败，不能是进行中", StageStatus.Failed, writing.status)
    assertEquals("桌面的消息要带出来", "模型中断", writing.message)
    assertTrue("这一条来自桌面上报", writing.reported)
  }

  @Test
  fun 桌面报跳过与过期时如实显示() {
    // skipped / stale 是两个旧推演完全不认识的态：推演只会说「完成」或「等待」。
    val w = view(stage = "writing", progress = 100).copy(
      stageStatuses = mapOf(
        "blueprint" to StageStatusView(status = "skipped", progress = null, message = null),
        "outline" to StageStatusView(status = "stale", progress = 30, message = "上游改过"),
      )
    )
    val work = WorksProjection.workOf(w)
    assertEquals(StageStatus.Skipped, work.stages.first { it.id == "blueprint" }.status)
    assertEquals(StageStatus.Stale, work.stages.first { it.id == "outline" }.status)
  }

  @Test
  fun 桌面没上报的阶段标记为未上报而不是假装等待() {
    // 只有 blueprint 有上报，其余靠推演 —— 那些必须 reported=false，
    // 界面据此标「状态未上报」。假装「等待」会让用户以为还没轮到。
    val w = view(stage = "blueprint", progress = 10).copy(
      stageStatuses = mapOf("blueprint" to StageStatusView("running", 10, null))
    )
    val work = WorksProjection.workOf(w)
    assertTrue("上报过的阶段", work.stages.first { it.id == "blueprint" }.reported)
    val others = work.stages.filter { it.id != "blueprint" }
    assertTrue("其余阶段都在推演", others.isNotEmpty())
    assertTrue("没上报的必须标记出来", others.none { it.reported })
  }

  @Test
  fun 完全没有状态上报时保持旧推演行为() {
    // 旧版桌面不带 stageStatuses：推演是唯一的依据，行为不能变，
    // 但每一条都要标记 reported=false。
    val work = WorksProjection.workOf(view(stage = "writing", progress = 40))
    val chain = work.stages.map { it.id }
    val idx = chain.indexOf("writing")
    assertTrue("之前已完成", work.stages.take(idx).all { it.status == StageStatus.Done })
    assertTrue("之后仍等待", work.stages.drop(idx + 1).all { it.status == StageStatus.Waiting })
    assertTrue("全部都是推演出来的", work.stages.none { it.reported })
  }
}