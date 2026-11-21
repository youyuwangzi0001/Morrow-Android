package studio.morrow.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MorrowPalette(
  val bg: Color,
  val bgElev: Color,
  val surface: Color,
  val surfaceHover: Color,
  val surfaceActive: Color,
  val surfaceInset: Color,
  val line: Color,
  val lineStrong: Color,
  val lineGold: Color,
  val text: Color,
  val text2: Color,
  val text3: Color,
  val textInvert: Color,
  val gold: Color,
  val goldDeep: Color,
  val goldBright: Color,
  val goldDim: Color,
  val goldGhost: Color,
  val patina: Color,
  val patinaDim: Color,
  val rust: Color,
  val rustDim: Color,
  val amber: Color,
  val amberDim: Color,
  val mauve: Color,
  val mauveDim: Color,
  val danger: Color,
  val userBubble: Color,
  val scrim: Color,
  val hint: Color,
)

/** 墨夜（默认） */
val InkPalette = MorrowPalette(
  bg = Color(0xFF0B0D10),
  bgElev = Color(0xFF10141A),
  surface = Color(0xFF141922),
  surfaceHover = Color(0xFF1A2029),
  surfaceActive = Color(0xFF202733),
  surfaceInset = Color(0xFF0E1217),
  line = Color(0x13EDE7DA),
  lineStrong = Color(0x25EDE7DA),
  lineGold = Color(0x52C8AE7D),
  text = Color(0xFFEDE7DA),
  text2 = Color(0xFFA39D91),
  text3 = Color(0xFF6B675F),
  textInvert = Color(0xFF12141A),
  gold = Color(0xFFC8AE7D),
  goldDeep = Color(0xFF9A7C4A),
  goldBright = Color(0xFFE4CE9E),
  goldDim = Color(0x24C8AE7D),
  goldGhost = Color(0x0FC8AE7D),
  patina = Color(0xFF7FA292),
  patinaDim = Color(0x217FA292),
  rust = Color(0xFFC2795F),
  rustDim = Color(0x21C2795F),
  amber = Color(0xFFC9A227),
  amberDim = Color(0x21C9A227),
  mauve = Color(0xFF9A8FA8),
  mauveDim = Color(0x219A8FA8),
  danger = Color(0xFFC2795F),
  userBubble = Color(0xFF1A212C),
  scrim = Color(0x9E060709),
  hint = Color(0xFF9E998F)
)

/** 素纸（light） */
val PaperPalette = MorrowPalette(
  bg = Color(0xFFF1EDE4),
  bgElev = Color(0xFFF6F2E9),
  surface = Color(0xFFFBF7EE),
  surfaceHover = Color(0xFFF0EADD),
  surfaceActive = Color(0xFFE9E2D3),
  surfaceInset = Color(0xFFEAE4D8),
  line = Color(0x1A1B1A17),
  lineStrong = Color(0x2E1B1A17),
  lineGold = Color(0x577A5418),
  text = Color(0xFF1B1A17),
  text2 = Color(0xFF5C574D),
  text3 = Color(0xFF8C867A),
  textInvert = Color(0xFFF8F4EA),
  gold = Color(0xFF7A5418),
  goldDeep = Color(0xFF5F3F12),
  goldBright = Color(0xFF86602A),
  goldDim = Color(0x1F7A5418),
  goldGhost = Color(0x0D7A5418),
  patina = Color(0xFF4F7263),
  patinaDim = Color(0x1F4F7263),
  rust = Color(0xFFA2543F),
  rustDim = Color(0x1FA2543F),
  amber = Color(0xFF9A7A12),
  amberDim = Color(0x1F9A7A12),
  mauve = Color(0xFF6F6480),
  mauveDim = Color(0x1F6F6480),
  danger = Color(0xFFA2543F),
  userBubble = Color(0xFFE8E1D2),
  scrim = Color(0x521E1B14),
  hint = Color(0xFF5E5A52)
)

object MorrowSpacing {
  val s1 = 4.dp
  val s2 = 8.dp
  val s3 = 12.dp
  val s4 = 16.dp
  val s5 = 20.dp
  val s6 = 24.dp
  val s7 = 32.dp
  val s8 = 40.dp
  val s9 = 56.dp
  val s10 = 72.dp
}

object MorrowRadius {
  val xs = 4.dp
  val sm = 5.dp
  val md = 7.dp
  val lg = 9.dp
  val xl = 12.dp

  /** 控件统一圆角（桌面 --control-radius = --r-sm） */
  val control: Dp = sm

  /** 卡片圆角（桌面 --r-card = --r-lg） */
  val card: Dp = lg
}

object MorrowType {
  val fontUi = 15.sp
  val display = 35.sp
  val displaySm = 27.sp
  val h1 = 25.sp
  val title = 21.sp
  val h2 = 18.sp
  val bodyLg = 16.sp
  val body = 15.sp
  val ui = 14.sp
  val base = 13.sp
  val sm = 12.sp
  val xs = 11.sp
  val code = 12.sp

  val lhTight = 1.2f
  val lhTitle = 1.32f
  val lhUi = 1.45f
  val lhCopy = 1.62f
  val lhRead = 1.72f

  fun mono(size: TextUnit = code): TextUnit = size
}
