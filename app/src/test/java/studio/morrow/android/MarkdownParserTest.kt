package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 富文本解析的纯函数测试。
 *
 * 这一组**不启动 Robolectric**：解析器与 Compose 无关，失败时能直接指到
 * 是哪条语法没解析对，而不用在一屏渲染断言里猜。渲染层另有 `RichTextRenderTest`。
 */
class MarkdownParserTest {

  // ─────────────── 块级 ───────────────

  @Test
  fun 标题按井号数分级且井号后必须有空格() {
    val blocks = MarkdownParser.parse("# 一级\n\n### 三级")
    assertEquals(2, blocks.size)
    assertEquals(MdBlock.Heading(1, "一级"), blocks[0])
    assertEquals(MdBlock.Heading(3, "三级"), blocks[1])

    // `#include <stdio.h>` 是代码不是标题 —— 井号后没有空格
    val c = MarkdownParser.parse("#include <stdio.h>")
    assertEquals(1, c.size)
    assertTrue("应解析成段落", c[0] is MdBlock.Paragraph)
  }

  @Test
  fun 无序清单解析出每项文本() {
    val blocks = MarkdownParser.parse("- 甲\n- 乙\n- 丙")
    assertEquals(1, blocks.size)
    val list = blocks[0] as MdBlock.Bullet
    assertEquals(listOf("甲", "乙", "丙"), list.items.map { it.text })
    assertTrue("不是任务清单时 checked 为 null", list.items.all { it.checked == null })
  }

  @Test
  fun 任务清单解析勾选状态() {
    val list = MarkdownParser.parse("- [x] 已完成\n- [ ] 未完成")[0] as MdBlock.Bullet
    assertEquals(true, list.items[0].checked)
    assertEquals(false, list.items[1].checked)
    assertEquals("已完成", list.items[0].text)
  }

  @Test
  fun 有序清单保留起始序号() {
    val list = MarkdownParser.parse("3. 三\n4. 四")[0] as MdBlock.Ordered
    assertEquals("保留起始序号（不是永远从 1 开始）", 3, list.start)
    assertEquals(listOf("三", "四"), list.items.map { it.text })
  }

  @Test
  fun 围栏代码块保留原文与语言标签() {
    val blocks = MarkdownParser.parse("```kotlin\nval a = 1\nval b = 2\n```")
    assertEquals(1, blocks.size)
    val code = blocks[0] as MdBlock.Code
    assertEquals("kotlin", code.lang)
    assertEquals("val a = 1\nval b = 2", code.code)
  }

  @Test
  fun 未闭合的代码块也能解析到文末() {
    val blocks = MarkdownParser.parse("```ts\nconst a = 1")
    assertEquals(1, blocks.size)
    assertEquals("const a = 1", (blocks[0] as MdBlock.Code).code)
  }

  @Test
  fun 表格解析表头与对齐方式() {
    val blocks = MarkdownParser.parse(
      """
      | 月份 | 金额 | 备注 |
      | :--- | ---: | :---: |
      | 7 月 | 12 | 无 |
      | 8 月 | 34 | 有 |
      """.trimIndent()
    )
    assertEquals(1, blocks.size)
    val table = blocks[0] as MdBlock.Table
    assertEquals(listOf("月份", "金额", "备注"), table.header)
    assertEquals(listOf(MdAlign.Start, MdAlign.End, MdAlign.Center), table.align)
    assertEquals(2, table.rows.size)
    assertEquals(listOf("7 月", "12", "无"), table.rows[0])
  }

  @Test
  fun 表格里的转义竖线不被当成分隔符() {
    val table = MarkdownParser.parse(
      """
      | 命令 | 说明 |
      | --- | --- |
      | a \| b | 管道 |
      """.trimIndent()
    )[0] as MdBlock.Table
    assertEquals("a | b", table.rows[0][0])
    assertEquals("管道", table.rows[0][1])
  }

  @Test
  fun 引用块合并连续多行() {
    val quote = MarkdownParser.parse("> 第一行\n> 第二行")[0] as MdBlock.Quote
    assertEquals("第一行\n第二行", quote.text)
  }

  @Test
  fun 分隔线被识别成规则而不是列表项() {
    val blocks = MarkdownParser.parse("上\n\n---\n\n下")
    assertTrue(blocks.any { it is MdBlock.Rule })
    // `---` 不能被当成「一个空的列表项」
    assertFalse(blocks.any { it is MdBlock.Bullet })
  }

  @Test
  fun 段落之间按空行切分() {
    val blocks = MarkdownParser.parse("第一段\n\n第二段")
    assertEquals(2, blocks.size)
    assertEquals(MdBlock.Paragraph("第一段"), blocks[0])
    assertEquals(MdBlock.Paragraph("第二段"), blocks[1])
  }

  @Test
  fun 空输入不产生空段落() {
    assertTrue(MarkdownParser.parse("").isEmpty())
    assertTrue("只有空白也不该造出一个空段落", MarkdownParser.parse("   \n\n  ").isEmpty())
  }

  // ─────────────── diff ───────────────

  @Test
  fun 语言标签为diff的代码块解析成行级diff() {
    val blocks = MarkdownParser.parse(
      "```diff\n--- a/x.ts\n+++ b/x.ts\n@@ -1,2 +1,2 @@\n-old\n+new\n context\n```"
    )
    val diff = blocks[0] as MdBlock.Diff
    assertEquals(
      listOf(
        MdDiffKind.Meta,   // --- a/x.ts
        MdDiffKind.Meta,   // +++ b/x.ts
        MdDiffKind.Hunk,
        MdDiffKind.Removed,
        MdDiffKind.Added,
        MdDiffKind.Context,
      ),
      diff.lines.map { it.kind },
    )
  }

  @Test
  fun 文件头不会被当成新增或删除行() {
    // 顺序错了的话 `+++ b/x` 会被 + 规则吃掉，头两行变成一绿一红
    val diff = MarkdownParser.parse("```diff\n--- a/x\n+++ b/x\n```")[0] as MdBlock.Diff
    assertEquals(MdDiffKind.Meta, diff.lines[0].kind)
    assertEquals(MdDiffKind.Meta, diff.lines[1].kind)
  }

  @Test
  fun 无语言标签但形态是diff的代码块也走diff视图() {
    val patch = "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b"
    val blocks = MarkdownParser.parse("```\n$patch\n```")
    assertTrue("应识别成 diff", blocks[0] is MdBlock.Diff)
    assertTrue(MarkdownParser.looksLikeUnifiedDiff(patch))
  }

  @Test
  fun 普通缩进代码不会被误判成diff() {
    // 以 `- ` 开头的 YAML 列表、以 `+` 开头的行在真实代码里很常见
    val yaml = "- name: a\n  value: 1\n- name: b\n  value: 2"
    assertFalse(MarkdownParser.looksLikeUnifiedDiff(yaml))
    assertTrue(MarkdownParser.parse("```\n$yaml\n```")[0] is MdBlock.Code)
  }

  // ─────────────── 行内 ───────────────

  @Test
  fun 行内解析粗体斜体删除线与行内代码() {
    val nodes = MarkdownParser.parseInline("**粗** *斜* ~~删~~ `code`")
    val kinds = nodes.map {
      when (it) {
        is MdInline.Strong -> "strong"
        is MdInline.Emphasis -> "em"
        is MdInline.Strike -> "strike"
        is MdInline.Code -> "code"
        is MdInline.Link -> "link"
        is MdInline.Plain -> "plain"
      }
    }
    assertEquals(listOf("strong", "plain", "em", "plain", "strike", "plain", "code"), kinds)
    assertEquals("粗", MarkdownParser.inlinePlainText((nodes[0] as MdInline.Strong).children))
    assertEquals("code", (nodes.last() as MdInline.Code).text)
  }

  @Test
  fun 双星号优先于单星号解析() {
    // 先判 `*` 会把 `**a**` 读成「斜体 + 斜体」，文字里留下两个星号
    val nodes = MarkdownParser.parseInline("**a**")
    assertEquals(1, nodes.size)
    assertTrue(nodes[0] is MdInline.Strong)
  }

  @Test
  fun 链接解析出文字与地址() {
    val nodes = MarkdownParser.parseInline("见 [文档](https://example.com/a)")
    val link = nodes.filterIsInstance<MdInline.Link>().single()
    assertEquals("文档", link.text)
    assertEquals("https://example.com/a", link.url)
  }

  @Test
  fun 行内代码里的星号不被解析成强调() {
    val nodes = MarkdownParser.parseInline("`a*b*c`")
    assertEquals(1, nodes.size)
    assertEquals("a*b*c", (nodes[0] as MdInline.Code).text)
  }

  @Test
  fun 反斜杠转义星号() {
    val nodes = MarkdownParser.parseInline("\\*不是斜体\\*")
    assertEquals("*不是斜体*", MarkdownParser.inlinePlainText(nodes))
  }

  // ─────────────── 摘要 ───────────────

  @Test
  fun 摘要取第一个非空块并压成一行() {
    val summary = MarkdownParser.summaryOf("## 标题\n\n这是一段很长的正文内容需要被截断处理才行呢")
    assertEquals("标题", summary)
  }

  @Test
  fun 摘要超长时截断并加省略号() {
    val summary = MarkdownParser.summaryOf("一".repeat(200), maxChars = 10)
    assertEquals(11, summary.length)
    assertTrue(summary.endsWith("…"))
  }

  @Test
  fun 纯diff消息的摘要为空而不是贴出补丁() {
    // 补丁第一行是 `--- a/x`，当成摘要显示毫无意义
    assertEquals("", MarkdownParser.summaryOf("```diff\n--- a/x\n+++ b/x\n```"))
  }

  // ─────────────── 组合 ───────────────

  @Test
  fun 一段真实回复能整体解析() {
    val source = """
      ## 销售复盘

      先看数据：

      | 月份 | 金额 |
      | --- | ---: |
      | 7 月 | 12 |

      下面改动了两处：

      ```diff
      --- a/report.md
      +++ b/report.md
      @@ -1 +1 @@
      -旧结论
      +新结论
      ```

      1. 读 CSV
      2. 聚合

      > 注：数据截至 8 月
    """.trimIndent()
    val blocks = MarkdownParser.parse(source)
    val kinds = blocks.map {
      when (it) {
        is MdBlock.Heading -> "heading"
        is MdBlock.Paragraph -> "para"
        is MdBlock.Table -> "table"
        is MdBlock.Diff -> "diff"
        is MdBlock.Ordered -> "ordered"
        is MdBlock.Quote -> "quote"
        is MdBlock.Code -> "code"
        is MdBlock.Bullet -> "bullet"
        MdBlock.Rule -> "rule"
      }
    }
    assertEquals(listOf("heading", "para", "table", "para", "diff", "ordered", "quote"), kinds)
  }
}
