package studio.morrow.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 富文本**渲染层**测试。
 *
 * 这份文件是补上去的：`MarkdownParserTest` 的类注释一直写着「渲染层另有
 * `RichTextRenderTest`」，但那个文件从来不存在（文档 M-17 也记了这一条）。
 * 结果解析器有 30+ 条断言，而「解析对了但画错了」这一类缺陷完全没有防线 ——
 * 折叠阈值、diff 配色、表格对齐、链接点击都属于这一类。
 *
 * 分工：解析器的正确性在 `MarkdownParserTest`（不启 Robolectric，快），
 * 这里只断言**渲染后的可见结果**，不重复解析断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h731dp-420dpi")
class RichTextRenderTest {

  @get:Rule
  val compose = createComposeRule()

  private fun render(source: String, collapseAfter: Int? = null) {
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        MarkdownView(source = source, collapseAfterBlocks = collapseAfter)
      }
    }
    compose.waitForIdle()
  }

  @Test
  fun 八种块各画各的_不会互相退化成纯文本() {
    // 一条内容里塞满 8 种块：任何一块「解析出来但没画」都会在这里暴露。
    render(
      listOf(
        "# 标题一",
        "一个段落。",
        "> 引用一句",
        "- 无序甲",
        "1. 有序乙",
        "```\nval x = 1\n```",
        "| 列A | 列B |\n| --- | --- |\n| a1 | b1 |",
        "```diff\n+新增\n-删除\n 上下文\n```",
      ).joinToString("\n\n")
    )

    compose.onNodeWithText("标题一").assertIsDisplayed()
    compose.onNodeWithText("一个段落。", substring = true).assertIsDisplayed()
    compose.onNodeWithText("引用一句", substring = true).assertIsDisplayed()
    compose.onNodeWithText("无序甲", substring = true).assertIsDisplayed()
    compose.onNodeWithText("有序乙", substring = true).assertIsDisplayed()
    compose.onNodeWithText("val x = 1", substring = true).assertIsDisplayed()
    // 表格与 diff 有专属容器：靠 testTag 断言，而不是猜文字
    compose.onNodeWithTag(RichTags.TABLE).assertIsDisplayed()
    compose.onNodeWithTag(RichTags.DIFF).assertIsDisplayed()
  }

  @Test
  fun 代码块超过二十四行折叠并可展开() {
    val code = (1..40).joinToString("\n") { "line$it" }
    render("```\n$code\n```")

    // 注意 `substring = true` 是必须的：整个代码块是**一个** Text，
    // 内容是多行字符串。`substring = false` 要求整段完全相等，
    // 永远匹配不到其中某一行 —— 这会让「断言不存在」假通过、
    // 让「断言存在」假失败。第一版就是这么写错的。
    compose.onNodeWithText("line1", substring = true).assertIsDisplayed()
    compose.onNodeWithText("line40", substring = true).assertDoesNotExist()
    // 折叠按钮的文案要说明「还有多少行」——用户据此决定值不值得展开
    compose.onNodeWithText("展开 40 行", substring = true).assertExists()

    compose.onNodeWithTag(RichTags.COLLAPSE).performClick()
    compose.waitForIdle()

    // 先证明点击真的改变了状态（文案翻转），再证明内容被放出来。
    // 只断言前者会漏掉「按钮翻转但内容没变」，只断言后者
    // 在匹配器写错时会以另一种面貌失败，定位不到真因。
    compose.onNodeWithText("收起", substring = true).assertExists()
    compose.onNodeWithText("展开 40 行", substring = true).assertDoesNotExist()
    compose.onNodeWithText("line40", substring = true).assertExists()
  }

  @Test
  fun diff超过二十行折叠且统计加减行数() {
    val diff = (1..40).joinToString("\n") { if (it % 3 == 0) "+加$it" else " 文$it" }
    render("```diff\n$diff\n```")

    val added = (1..40).count { it % 3 == 0 }
    // 表头右侧显示「+N −0」这类统计，是用户判断改动量的唯一依据
    compose.onNodeWithText("+$added", substring = true).assertIsDisplayed()

    compose.onNodeWithText("文40", substring = true).assertDoesNotExist()
    compose.onNodeWithTag(RichTags.COLLAPSE).performClick()
    compose.waitForIdle()
    compose.onNodeWithText("收起", substring = true).assertExists()
    compose.onNodeWithText("文40", substring = true).assertExists()
  }

  @Test
  fun 折叠阈值由调用方决定_不影响默认行为() {
    // collapseAfterBlocks 只截断**块**的个数（消息级折叠），
    // 与代码块/diff 的行折叠是两套阈值，不能互相顶替。
    val source = (1..6).joinToString("\n\n") { "段落$it" }
    render(source, collapseAfter = 2)

    compose.onNodeWithText("段落1", substring = true).assertIsDisplayed()
    compose.onNodeWithText("段落2", substring = true).assertIsDisplayed()
    compose.onNodeWithText("段落6", substring = true).assertDoesNotExist()
  }

  @Test
  fun 空内容不渲染任何块_也不崩() {
    // MarkdownView 在 parsed.isEmpty() 时直接 return。
    // 这看起来很琐碎，但「空 message.text」在流式首帧是常态。
    render("")
    compose.onNodeWithText("段落", substring = true).assertDoesNotExist()
  }

  @Test
  fun 表格列数与表头一致() {
    render("| 名字 | 数量 |\n| --- | --- |\n| 甲 | 3 |")
    compose.onNodeWithTag(RichTags.TABLE).assertIsDisplayed()
    // 表头与单元格都要在：只画表头会让人以为表是空的
    compose.onNodeWithText("名字", substring = true).assertIsDisplayed()
    compose.onNodeWithText("甲", substring = true).assertIsDisplayed()
    compose.onNodeWithText("3", substring = true).assertIsDisplayed()
  }
}
