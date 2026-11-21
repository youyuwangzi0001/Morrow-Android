package studio.morrow.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/** 表格列对齐。解析自 `|---:|:---:|` 那一行。 */
enum class MdAlign { Start, Center, End }

/** 列表里的一项。`checked` 非 null 表示这是任务清单。 */
data class MdItem(
  val text: String,
  val checked: Boolean? = null,
  /** 缩进层级：0 = 顶层。用 2 个空格或 1 个 Tab 进一级。 */
  val depth: Int = 0,
)

/** diff 的一行。`Hunk` 是 `@@ -1,4 +1,6 @@` 那一行。 */
enum class MdDiffKind { Added, Removed, Context, Hunk, Meta }

data class MdDiffLine(val kind: MdDiffKind, val text: String)

sealed interface MdBlock {
  data class Heading(val level: Int, val text: String) : MdBlock
  data class Paragraph(val text: String) : MdBlock
  data class Bullet(val items: List<MdItem>) : MdBlock
  data class Ordered(val start: Int, val items: List<MdItem>) : MdBlock
  data class Code(val lang: String?, val code: String) : MdBlock
  data class Quote(val text: String) : MdBlock
  data class Table(
    val header: List<String>,
    val align: List<MdAlign>,
    val rows: List<List<String>>,
  ) : MdBlock

  data class Diff(val lines: List<MdDiffLine>) : MdBlock
  data object Rule : MdBlock
}

/** 行内元素。 */
sealed interface MdInline {
  data class Plain(val text: String) : MdInline
  data class Code(val text: String) : MdInline
  data class Strong(val children: List<MdInline>) : MdInline
  data class Emphasis(val children: List<MdInline>) : MdInline
  data class Strike(val children: List<MdInline>) : MdInline
  data class Link(val text: String, val url: String) : MdInline
}

object MarkdownParser {

  /** 识别围栏：``` 或 ~~~（3 个及以上）。返回围栏字符，不是围栏则 null。 */
  private fun fenceOf(trimmed: String): String? = when {
    trimmed.startsWith("```") -> "```"
    trimmed.startsWith("~~~") -> "~~~"
    else -> null
  }

  fun parse(source: String): List<MdBlock> {
    if (source.isBlank()) return emptyList()
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
      val text = paragraph.toString().trim()
      paragraph.clear()
      if (text.isNotEmpty()) out += MdBlock.Paragraph(text)
    }

    var i = 0
    while (i < lines.size) {
      val raw = lines[i]
      val trimmed = raw.trim()

      // ── 围栏代码块 ──
      val fence = fenceOf(trimmed)
      if (fence != null) {
        flushParagraph()
        val lang = trimmed.removePrefix(fence).trim().trim('{', '}').removePrefix(".").ifEmpty { null }
        val body = StringBuilder()
        i++
        while (i < lines.size && fenceOf(lines[i].trim()) == null) {
          if (body.isNotEmpty()) body.append('\n')
          body.append(lines[i])
          i++
        }
        if (i < lines.size) i++ // 跳过收尾围栏
        val code = body.toString()
        out += when {
          lang != null && isDiffLang(lang) -> MdBlock.Diff(parseDiffLines(code))
          lang == null && looksLikeUnifiedDiff(code) -> MdBlock.Diff(parseDiffLines(code))
          else -> MdBlock.Code(lang, code)
        }
        continue
      }

      // ── 空行：断开段落 ──
      if (trimmed.isEmpty()) {
        flushParagraph()
        i++
        continue
      }

      // ── 分隔线 ──
      if (isRule(trimmed)) {
        flushParagraph()
        out += MdBlock.Rule
        i++
        continue
      }

      // ── 标题 ──
      val heading = headingOf(trimmed)
      if (heading != null) {
        flushParagraph()
        out += heading
        i++
        continue
      }

      if (trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim())) {
        flushParagraph()
        val header = splitRow(trimmed)
        val align = alignOf(lines[i + 1].trim())
        i += 2
        val rows = mutableListOf<List<String>>()
        while (i < lines.size && lines[i].trim().startsWith("|")) {
          rows += splitRow(lines[i].trim())
          i++
        }
        out += MdBlock.Table(header, align, rows)
        continue
      }

      // ── 引用 ──
      if (trimmed.startsWith(">")) {
        flushParagraph()
        val quoted = mutableListOf<String>()
        while (i < lines.size && lines[i].trim().startsWith(">")) {
          quoted += lines[i].trim().removePrefix(">").removePrefix(" ").trimEnd()
          i++
        }
        out += MdBlock.Quote(quoted.joinToString("\n").trim())
        continue
      }

      // ── 无序 / 任务清单 ──
      if (bulletOf(raw) != null) {
        flushParagraph()
        val items = mutableListOf<MdItem>()
        while (i < lines.size) {
          val item = bulletOf(lines[i]) ?: break
          items += item
          i++
          // 续行（缩进且不是新的列表项）并到上一项
          while (i < lines.size && lines[i].isNotBlank() &&
            lines[i].first().isWhitespace() && bulletOf(lines[i]) == null &&
            orderedOf(lines[i]) == null && !startsOtherBlock(lines[i].trim())
          ) {
            val lastIndex = items.lastIndex
            items[lastIndex] = items[lastIndex].let { it.copy(text = it.text + " " + lines[i].trim()) }
            i++
          }
        }
        out += MdBlock.Bullet(items)
        continue
      }

      // ── 有序清单 ──
      val firstOrdered = orderedOf(raw)
      if (firstOrdered != null) {
        flushParagraph()
        val items = mutableListOf<MdItem>()
        while (i < lines.size) {
          val item = orderedOf(lines[i]) ?: break
          items += item.second
          i++
        }
        out += MdBlock.Ordered(firstOrdered.first, items)
        continue
      }

      // ── 普通段落行 ──
      if (paragraph.isNotEmpty()) paragraph.append('\n')
      paragraph.append(trimmed)
      i++
    }
    flushParagraph()
    return out
  }

  /** ```` ``` ```` / ```` ```ts ```` / ```` ```diff ```` 里的语言标签是不是 diff 系列。 */
  fun isDiffLang(lang: String): Boolean {
    val l = lang.lowercase()
    return l == "diff" || l == "patch" || l == "udiff" || l == "unified-diff"
  }

  fun looksLikeUnifiedDiff(code: String): Boolean {
    val lines = code.split('\n').filter { it.isNotBlank() }
    if (lines.size < 3) return false
    val allPrefixed = lines.all { line ->
      line.startsWith("+") || line.startsWith("-") ||
        line.startsWith(" ") || line.startsWith("@@") || line.startsWith("\\")
    }
    if (!allPrefixed) return false
    val hasAnchor = lines.any {
      it.startsWith("@@") || it.startsWith("---") || it.startsWith("+++")
    }
    return hasAnchor
  }

  fun parseDiffLines(patch: String): List<MdDiffLine> {
    val out = mutableListOf<MdDiffLine>()
    for (line in patch.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
      if (line.isEmpty()) continue
      val kind = when {
        line.startsWith("+++") || line.startsWith("---") -> MdDiffKind.Meta
        line.startsWith("@@") -> MdDiffKind.Hunk
        line.startsWith("+") -> MdDiffKind.Added
        line.startsWith("-") -> MdDiffKind.Removed
        line.startsWith("\\") -> MdDiffKind.Meta
        line.startsWith("diff ") || line.startsWith("index ") -> MdDiffKind.Meta
        else -> MdDiffKind.Context
      }
      out += MdDiffLine(kind, line)
    }
    return out
  }

  // ── 行级识别 ──

  private fun headingOf(trimmed: String): MdBlock.Heading? {
    var level = 0
    while (level < trimmed.length && level < 6 && trimmed[level] == '#') level++
    if (level == 0) return null
    if (level >= trimmed.length || !trimmed[level].isWhitespace()) return null
    return MdBlock.Heading(level, trimmed.substring(level).trim().trimEnd('#').trim())
  }

  private fun isRule(trimmed: String): Boolean {
    if (trimmed.length < 3) return false
    val ch = trimmed[0]
    if (ch != '-' && ch != '*' && ch != '_') return false
    return trimmed.all { it == ch }
  }

  private fun isTableSeparator(trimmed: String): Boolean {
    if (!trimmed.startsWith("|")) return false
    val cells = splitRow(trimmed)
    if (cells.isEmpty()) return false
    return cells.all { cell ->
      val c = cell.trim()
      c.isNotEmpty() && c.all { it == '-' || it == ':' }
    } && trimmed.contains('-')
  }

  private fun alignOf(separator: String): List<MdAlign> = splitRow(separator).map { cell ->
    val c = cell.trim()
    when {
      c.startsWith(":") && c.endsWith(":") -> MdAlign.Center
      c.endsWith(":") -> MdAlign.End
      else -> MdAlign.Start
    }
  }

  /** 切一行表格。`\|` 是转义的竖线，不能当分隔符。 */
  fun splitRow(trimmed: String): List<String> {
    var body = trimmed
    if (body.startsWith("|")) body = body.substring(1)
    if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < body.length) {
      val c = body[i]
      if (c == '\\' && i + 1 < body.length && body[i + 1] == '|') {
        current.append('|')
        i += 2
        continue
      }
      if (c == '|') {
        cells += current.toString().trim()
        current.clear()
        i++
        continue
      }
      current.append(c)
      i++
    }
    cells += current.toString().trim()
    return cells
  }

  private fun indentDepth(raw: String): Int {
    var spaces = 0
    for (c in raw) {
      when (c) {
        ' ' -> spaces++
        '\t' -> spaces += 2
        else -> return spaces / 2
      }
    }
    return spaces / 2
  }

  private fun bulletOf(raw: String): MdItem? {
    val t = raw.trim()
    if (t.length < 2) return null
    val marker = t[0]
    if (marker != '-' && marker != '*' && marker != '+') return null
    if (!t[1].isWhitespace()) return null
    var text = t.substring(1).trim()
    var checked: Boolean? = null
    if (text.startsWith("[ ]")) {
      checked = false
      text = text.substring(3).trim()
    } else if (text.startsWith("[x]") || text.startsWith("[X]")) {
      checked = true
      text = text.substring(3).trim()
    }
    if (text.isEmpty() && checked == null) return null
    return MdItem(text = text, checked = checked, depth = indentDepth(raw))
  }

  private fun orderedOf(raw: String): Pair<Int, MdItem>? {
    val t = raw.trim()
    var i = 0
    while (i < t.length && t[i].isDigit() && i < 9) i++
    if (i == 0 || i >= t.length) return null
    if (t[i] != '.' && t[i] != ')') return null
    if (i + 1 >= t.length || !t[i + 1].isWhitespace()) return null
    val number = t.substring(0, i).toIntOrNull() ?: return null
    return number to MdItem(text = t.substring(i + 1).trim(), depth = indentDepth(raw))
  }

  /** 这一行是否开启另一种块（用于终止列表项的续行合并）。 */
  private fun startsOtherBlock(trimmed: String): Boolean =
    fenceOf(trimmed) != null || isRule(trimmed) || headingOf(trimmed) != null ||
      trimmed.startsWith(">") || trimmed.startsWith("|")

  // ── 行内解析 ──

  fun parseInline(text: String): List<MdInline> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<MdInline>()
    val plain = StringBuilder()
    fun flushPlain() {
      if (plain.isNotEmpty()) {
        out += MdInline.Plain(plain.toString())
        plain.clear()
      }
    }

    var i = 0
    while (i < text.length) {
      val c = text[i]

      // 转义
      if (c == '\\' && i + 1 < text.length) {
        plain.append(text[i + 1])
        i += 2
        continue
      }

      // 行内代码：内容原样，不再解析
      if (c == '`') {
        val close = text.indexOf('`', i + 1)
        if (close > i + 1) {
          flushPlain()
          out += MdInline.Code(text.substring(i + 1, close))
          i = close + 1
          continue
        }
      }

      // 链接 [text](url)
      if (c == '[') {
        val closeBracket = text.indexOf(']', i + 1)
        if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
          val closeParen = text.indexOf(')', closeBracket + 2)
          if (closeParen > closeBracket) {
            flushPlain()
            val label = text.substring(i + 1, closeBracket)
            val url = text.substring(closeBracket + 2, closeParen).trim().substringBefore(' ')
            out += MdInline.Link(label, url)
            i = closeParen + 1
            continue
          }
        }
      }

      // 删除线 ~~text~~
      if (c == '~' && i + 1 < text.length && text[i + 1] == '~') {
        val close = text.indexOf("~~", i + 2)
        if (close > i + 2) {
          flushPlain()
          out += MdInline.Strike(parseInline(text.substring(i + 2, close)))
          i = close + 2
          continue
        }
      }

      // 粗体 **text** / __text__
      if ((c == '*' || c == '_') && i + 1 < text.length && text[i + 1] == c) {
        val marker = "$c$c"
        val close = text.indexOf(marker, i + 2)
        if (close > i + 2) {
          flushPlain()
          out += MdInline.Strong(parseInline(text.substring(i + 2, close)))
          i = close + 2
          continue
        }
      }

      // 斜体 *text* / _text_
      if (c == '*' || c == '_') {
        val close = text.indexOf(c, i + 1)
        if (close > i + 1) {
          flushPlain()
          out += MdInline.Emphasis(parseInline(text.substring(i + 1, close)))
          i = close + 1
          continue
        }
      }

      plain.append(c)
      i++
    }
    flushPlain()
    return out
  }

  /** 行内元素拼回纯文本。测试与「复制」用；界面不直接用它。 */
  fun inlinePlainText(nodes: List<MdInline>): String = buildString {
    nodes.forEach { node ->
      when (node) {
        is MdInline.Plain -> append(node.text)
        is MdInline.Code -> append(node.text)
        is MdInline.Link -> append(node.text)
        is MdInline.Strong -> append(inlinePlainText(node.children))
        is MdInline.Emphasis -> append(inlinePlainText(node.children))
        is MdInline.Strike -> append(inlinePlainText(node.children))
      }
    }
  }

  fun summaryOf(source: String, maxChars: Int = 80): String {
    val blocks = parse(source)
    val text = blocks.asSequence()
      .mapNotNull { block ->
        when (block) {
          is MdBlock.Heading -> block.text
          is MdBlock.Paragraph -> block.text
          is MdBlock.Quote -> block.text
          is MdBlock.Bullet -> block.items.firstOrNull()?.text
          is MdBlock.Ordered -> block.items.firstOrNull()?.text
          is MdBlock.Code -> block.code.lineSequence().firstOrNull { it.isNotBlank() }
          is MdBlock.Table -> block.header.joinToString(" · ")
          is MdBlock.Diff -> null
          MdBlock.Rule -> null
        }
      }
      .firstOrNull { it.isNotBlank() }
      ?: return ""
    val oneLine = text.replace('\n', ' ').trim()
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars).trimEnd() + "…"
  }
}

/** 渲染层测试用的固定 tag 前缀。 */
object RichTags {
  const val CODE_BLOCK = "md-code"
  const val TABLE = "md-table"
  const val DIFF = "md-diff"
  const val LINK = "md-link"
  const val COLLAPSE = "md-collapse"
  fun block(index: Int) = "md-block-$index"
}

/** 代码块默认最多画多少行，超出折叠。40 行 ≈ 一屏不到的等宽文字。 */
private const val CODE_COLLAPSE_LINES = 24

/** diff 默认最多画多少行。比代码块更紧：用户先要看「改了哪几处」。 */
private const val DIFF_COLLAPSE_LINES = 20

@Composable
fun MarkdownView(
  source: String,
  modifier: Modifier = Modifier,
  /** 供测试注入；生产传 null，由 [source] 解析。 */
  blocks: List<MdBlock>? = null,
  collapseAfterBlocks: Int? = null,
) {
  val parsed = blocks ?: remember(source) { MarkdownParser.parse(source) }
  if (parsed.isEmpty()) return
  val visible = if (collapseAfterBlocks != null && parsed.size > collapseAfterBlocks) {
    parsed.take(collapseAfterBlocks)
  } else {
    parsed
  }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2)) {
    visible.forEachIndexed { index, block ->
      Box(Modifier.fillMaxWidth().testTag(RichTags.block(index))) {
        BlockView(block)
      }
    }
  }
}

@Composable
private fun BlockView(block: MdBlock) {
  when (block) {
    is MdBlock.Heading -> HeadingView(block)
    is MdBlock.Paragraph -> InlineText(block.text)
    is MdBlock.Bullet -> BulletView(block)
    is MdBlock.Ordered -> OrderedView(block)
    is MdBlock.Code -> CodeBlockView(block)
    is MdBlock.Quote -> QuoteView(block)
    is MdBlock.Table -> TableView(block)
    is MdBlock.Diff -> DiffView(block.lines, DIFF_COLLAPSE_LINES)
    MdBlock.Rule -> Box(
      Modifier
        .fillMaxWidth()
        .height(0.5.dp)
        .background(MorrowTheme.palette.lineStrong)
    )
  }
}

@Composable
private fun HeadingView(block: MdBlock.Heading) {
  val palette = MorrowTheme.palette
  val size = when (block.level) {
    1 -> MorrowType.h2
    2 -> MorrowType.bodyLg
    3 -> MorrowType.body
    else -> MorrowType.ui
  }
  Text(
    text = inlineText(block.text, palette),
    fontSize = size,
    fontWeight = FontWeight.Medium,
    lineHeight = size * MorrowType.lhTitle,
    color = palette.text,
    modifier = Modifier.padding(top = if (block.level <= 2) MorrowSpacing.s1 else 0.dp),
  )
}

@Composable
private fun InlineText(text: String) {
  SelectionContainer {
    Text(
      text = inlineText(text, MorrowTheme.palette),
      fontSize = MorrowType.body,
      lineHeight = MorrowType.body * MorrowType.lhCopy,
    )
  }
}

@Composable
private fun BulletView(block: MdBlock.Bullet) {
  val palette = MorrowTheme.palette
  Column(verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s1)) {
    block.items.forEach { item ->
      Row(
        Modifier
          .fillMaxWidth()
          .padding(start = (item.depth.coerceAtMost(4) * 14).dp),
        verticalAlignment = Alignment.Top,
      ) {
        val marker = when (item.checked) {
          true -> "✓"
          false -> "○"
          null -> "•"
        }
        Text(
          text = marker,
          color = when (item.checked) {
            true -> palette.patina
            false -> palette.hint
            null -> palette.text3
          },
          fontSize = MorrowType.body,
          fontFamily = if (item.checked == null) FontFamily.Default else FontFamily.Monospace,
          lineHeight = MorrowType.body * MorrowType.lhCopy,
          modifier = Modifier.width(16.dp),
        )
        Spacer(Modifier.width(MorrowSpacing.s2))
        SelectionContainer(Modifier.weight(1f)) {
          Text(
            text = inlineText(item.text, palette),
            fontSize = MorrowType.body,
            lineHeight = MorrowType.body * MorrowType.lhCopy,
            color = if (item.checked == true) MorrowTheme.text2 else palette.text,
          )
        }
      }
    }
  }
}

@Composable
private fun OrderedView(block: MdBlock.Ordered) {
  val palette = MorrowTheme.palette
  Column(verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s1)) {
    block.items.forEachIndexed { index, item ->
      Row(
        Modifier
          .fillMaxWidth()
          .padding(start = (item.depth.coerceAtMost(4) * 14).dp),
        verticalAlignment = Alignment.Top,
      ) {
        Text(
          text = "${block.start + index}.",
          color = palette.text3,
          fontSize = MorrowType.body,
          fontFamily = FontFamily.Monospace,
          lineHeight = MorrowType.body * MorrowType.lhCopy,
          modifier = Modifier.width(22.dp),
        )
        Spacer(Modifier.width(MorrowSpacing.s2))
        SelectionContainer(Modifier.weight(1f)) {
          Text(
            text = inlineText(item.text, palette),
            fontSize = MorrowType.body,
            lineHeight = MorrowType.body * MorrowType.lhCopy,
            color = palette.text,
          )
        }
      }
    }
  }
}

@Composable
private fun QuoteView(block: MdBlock.Quote) {
  val palette = MorrowTheme.palette
  Row(Modifier.fillMaxWidth()) {
    Box(
      Modifier
        .width(2.dp)
        .heightIn()
        .background(palette.lineGold)
    )
    Spacer(Modifier.width(MorrowSpacing.s3))
    SelectionContainer(Modifier.weight(1f)) {
      Text(
        text = inlineText(block.text, palette),
        fontSize = MorrowType.ui,
        lineHeight = MorrowType.ui * MorrowType.lhCopy,
        color = MorrowTheme.text2,
      )
    }
  }
}

@Composable
private fun CodeBlockView(block: MdBlock.Code) {
  val palette = MorrowTheme.palette
  var expanded by remember { mutableStateOf(false) }
  val lines = block.code.split('\n')
  val needsCollapse = lines.size > CODE_COLLAPSE_LINES
  val shown = if (!needsCollapse || expanded) block.code
  else lines.take(CODE_COLLAPSE_LINES).joinToString("\n")

  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(MorrowRadius.control))
      .background(palette.surfaceInset)
      .border(0.5.dp, palette.line, RoundedCornerShape(MorrowRadius.control))
      .testTag(RichTags.CODE_BLOCK),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = MorrowSpacing.s3, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = block.lang?.uppercase() ?: "CODE",
        color = palette.hint,
        fontSize = MorrowType.xs,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.weight(1f))
      if (needsCollapse) {
        Text(
          text = if (expanded) "收起" else "展开 ${lines.size} 行",
          color = MorrowTheme.text2,
          fontSize = MorrowType.xs,
          modifier = Modifier
            .clickable { expanded = !expanded }
            .testTag(RichTags.COLLAPSE),
        )
      }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.line))
    SelectionContainer {
      Text(
        text = AnnotatedString(shown),
        color = palette.text,
        fontSize = MorrowType.code,
        lineHeight = MorrowType.code * MorrowType.lhUi,
        fontFamily = FontFamily.Monospace,
        softWrap = false,
        modifier = Modifier
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = MorrowSpacing.s3, vertical = MorrowSpacing.s2),
      )
    }
  }
}

@Composable
private fun TableView(block: MdBlock.Table) {
  val palette = MorrowTheme.palette
  val columns = maxOf(
    block.header.size,
    block.rows.maxOfOrNull { it.size } ?: 0,
  )
  if (columns == 0) return
  val cell = { index: Int, isHeader: Boolean ->
    val width = when {
      isHeader -> 96.dp
      else -> 96.dp
    }
    width
  }

  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(MorrowRadius.control))
      .border(0.5.dp, palette.line, RoundedCornerShape(MorrowRadius.control))
      .testTag(RichTags.TABLE),
  ) {
    Box(Modifier.horizontalScroll(rememberScrollState())) {
      Column(Modifier.widthIn(min = (columns * 96).dp)) {
        // ── 表头 ──
        Row(Modifier.background(palette.surfaceHover)) {
          repeat(columns) { c ->
            TableCell(
              text = block.header.getOrNull(c) ?: "",
              align = block.align.getOrNull(c) ?: MdAlign.Start,
              header = true,
              width = cell(c, true),
            )
          }
        }
        block.rows.forEach { row ->
          Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.line))
          Row {
            repeat(columns) { c ->
              TableCell(
                text = row.getOrNull(c) ?: "",
                align = block.align.getOrNull(c) ?: MdAlign.Start,
                header = false,
                width = cell(c, false),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TableCell(
  text: String,
  align: MdAlign,
  header: Boolean,
  width: androidx.compose.ui.unit.Dp,
) {
  val palette = MorrowTheme.palette
  val textAlign = when (align) {
    MdAlign.Start -> androidx.compose.ui.text.style.TextAlign.Start
    MdAlign.Center -> androidx.compose.ui.text.style.TextAlign.Center
    MdAlign.End -> androidx.compose.ui.text.style.TextAlign.End
  }
  Box(
    Modifier
      .width(width)
      .padding(horizontal = MorrowSpacing.s2, vertical = 6.dp),
  ) {
    Text(
      text = inlineText(text, palette),
      color = if (header) palette.text else MorrowTheme.text2,
      fontSize = MorrowType.sm,
      fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
      textAlign = textAlign,
      lineHeight = MorrowType.sm * MorrowType.lhUi,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
internal fun DiffView(lines: List<MdDiffLine>, collapseAfter: Int = DIFF_COLLAPSE_LINES) {
  if (lines.isEmpty()) return
  val palette = MorrowTheme.palette
  var expanded by remember { mutableStateOf(false) }
  val needsCollapse = lines.size > collapseAfter
  val shown = if (!needsCollapse || expanded) lines else lines.take(collapseAfter)
  val added = lines.count { it.kind == MdDiffKind.Added }
  val removed = lines.count { it.kind == MdDiffKind.Removed }

  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(MorrowRadius.control))
      .background(palette.surfaceInset)
      .border(0.5.dp, palette.line, RoundedCornerShape(MorrowRadius.control))
      .testTag(RichTags.DIFF),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = MorrowSpacing.s3, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("DIFF", color = palette.hint, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
      Spacer(Modifier.width(MorrowSpacing.s2))
      Text("+$added", color = palette.patina, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
      Spacer(Modifier.width(MorrowSpacing.s1))
      Text("−$removed", color = palette.rust, fontSize = MorrowType.xs, fontFamily = FontFamily.Monospace)
      Spacer(Modifier.weight(1f))
      if (needsCollapse) {
        Text(
          text = if (expanded) "收起" else "展开 ${lines.size} 行",
          color = MorrowTheme.text2,
          fontSize = MorrowType.xs,
          modifier = Modifier
            .clickable { expanded = !expanded }
            .testTag(RichTags.COLLAPSE),
        )
      }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.line))
    SelectionContainer {
      Column(
        Modifier
          .horizontalScroll(rememberScrollState())
          .padding(vertical = 2.dp),
      ) {
        shown.forEach { line ->
          DiffLineRow(line)
        }
      }
    }
    if (needsCollapse && !expanded) {
      Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.line))
      Text(
        text = "还有 ${lines.size - collapseAfter} 行未显示",
        color = palette.hint,
        fontSize = MorrowType.xs,
        modifier = Modifier.padding(horizontal = MorrowSpacing.s3, vertical = 4.dp),
      )
    }
  }
}

@Composable
private fun DiffLineRow(line: MdDiffLine) {
  val palette = MorrowTheme.palette
  val (bg, fg) = when (line.kind) {
    MdDiffKind.Added -> palette.patinaDim.copy(alpha = 0.16f) to palette.patina
    MdDiffKind.Removed -> palette.rustDim.copy(alpha = 0.16f) to palette.rust
    MdDiffKind.Hunk -> palette.goldDim.copy(alpha = 0.20f) to palette.gold
    MdDiffKind.Meta -> Color.Transparent to palette.hint
    MdDiffKind.Context -> Color.Transparent to MorrowTheme.text2
  }
  Row(
    Modifier
      .fillMaxWidth()
      .background(bg)
      .padding(horizontal = MorrowSpacing.s3, vertical = 1.dp),
  ) {
    Text(
      text = line.text,
      color = fg,
      fontSize = MorrowType.code,
      lineHeight = MorrowType.code * MorrowType.lhUi,
      fontFamily = FontFamily.Monospace,
      softWrap = false,
    )
  }
}

@Composable
internal fun inlineText(text: String, palette: MorrowPalette): AnnotatedString {
  val nodes = remember(text) { MarkdownParser.parseInline(text) }
  return remember(nodes, palette) {
    buildAnnotatedString { appendInline(nodes, palette) }
  }
}

private fun AnnotatedString.Builder.appendInline(
  nodes: List<MdInline>,
  palette: MorrowPalette,
  style: SpanStyle = SpanStyle(),
) {
  withStyle(style) {
    nodes.forEach { node ->
      when (node) {
        is MdInline.Plain -> append(node.text)
        is MdInline.Code -> withStyle(
          SpanStyle(
            fontFamily = FontFamily.Monospace,
            color = palette.goldBright,
            background = palette.surfaceHover,
            fontSize = MorrowType.code,
          )
        ) { append(node.text) }
        is MdInline.Strong -> appendInline(
          node.children,
          palette,
          SpanStyle(fontWeight = FontWeight.Bold),
        )
        is MdInline.Emphasis -> appendInline(
          node.children,
          palette,
          SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        )
        is MdInline.Strike -> appendInline(
          node.children,
          palette,
          SpanStyle(textDecoration = TextDecoration.LineThrough),
        )
        is MdInline.Link -> withLink(
          LinkAnnotation.Url(
            url = node.url,
            styles = TextLinkStyles(
              style = SpanStyle(color = palette.gold, textDecoration = TextDecoration.Underline)
            ),
          )
        ) {
          withStyle(SpanStyle(color = palette.gold, textDecoration = TextDecoration.Underline)) {
            append(node.text)
          }
        }
      }
    }
  }
}
