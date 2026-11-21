#!/usr/bin/env node
/**
 * token 对照表：桌面 CSS 变量 ↔ 手机 Compose 令牌，逐项比对。
 *
 * 为什么需要它：A2 卡的验收标准是「token 名与桌面一一对应，缺失项报错」。
 * 手写对照表无法防止后续卡片新增颜色时漏 token，所以这里做成可重跑的比对脚本：
 * 桌面 styles.css 是唯一事实源，手机端缺哪个就报哪个。
 *
 * 用法：node scripts/token-parity.mjs
 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const CSS = resolve(ROOT, '../Morrow/src/renderer/styles.css')
const TOKENS = resolve(ROOT, 'app/src/main/java/studio/morrow/android/DesignTokens.kt')

// 手机端特有令牌：桌面没有对应 CSS 变量，属 A2 卡允许的移动端调整，在此显式登记。
const MOBILE_ONLY = {
  hint: 'text3 在手机近距离阅读下对比度不足，朝正文色提一档（详见 DesignTokens.kt 注释）'
}

/** 桌面语义色 → Compose 字段名。桌面用 kebab-case，Kotlin 用 camelCase。 */
const COLOR_MAP = {
  '--bg': 'bg',
  '--bg-elev': 'bgElev',
  '--surface': 'surface',
  '--surface-hover': 'surfaceHover',
  '--surface-active': 'surfaceActive',
  '--surface-inset': 'surfaceInset',
  '--line': 'line',
  '--line-strong': 'lineStrong',
  '--line-gold': 'lineGold',
  '--text': 'text',
  '--text-2': 'text2',
  '--text-3': 'text3',
  '--text-invert': 'textInvert',
  '--gold': 'gold',
  '--gold-deep': 'goldDeep',
  '--gold-bright': 'goldBright',
  '--gold-dim': 'goldDim',
  '--gold-ghost': 'goldGhost',
  '--patina': 'patina',
  '--patina-dim': 'patinaDim',
  '--rust': 'rust',
  '--rust-dim': 'rustDim',
  '--amber': 'amber',
  '--amber-dim': 'amberDim',
  '--mauve': 'mauve',
  '--mauve-dim': 'mauveDim',
  '--danger': 'danger',
  '--user-bubble': 'userBubble',
  '--scrim': 'scrim'
}

/** 圆角令牌：桌面 --r-* → Compose MorrowRadius 字段 */
const RADIUS_MAP = {
  '--r-xs': 'xs',
  '--r-sm': 'sm',
  '--r-md': 'md',
  '--r-lg': 'lg',
  '--r-xl': 'xl'
}

/** 间距令牌：桌面 --sp-N → Compose MorrowSpacing.sN */
const SPACING_MAP = Object.fromEntries(
  Array.from({ length: 10 }, (_, i) => [`--sp-${i + 1}`, `s${i + 1}`])
)

const css = readFileSync(CSS, 'utf8')
const kt = readFileSync(TOKENS, 'utf8')

/** 只在 :root 的第一个块（墨夜）里取值，避免读到 light 覆盖值 */
function parseRootBlock(text) {
  const start = text.indexOf(':root {')
  const end = text.indexOf('[data-theme=\'light\']')
  return text.slice(start, end > 0 ? end : undefined)
}

const inkBlock = parseRootBlock(css)
const paperStart = css.indexOf('[data-theme=\'light\']')
const paperBlock = css.slice(paperStart, css.indexOf(':root {', paperStart + 10))

/** 取某个块里所有 `--name: value;` */
function parseVars(block) {
  const out = {}
  for (const m of block.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)) {
    out[m[1]] = m[2].trim()
  }
  return out
}

const ink = parseVars(inkBlock)
const paper = parseVars(paperBlock)

/**
 * CSS 颜色 → #AARRGGBB 十六进制。
 * 支持 #rgb / #rrggbb / #rrggbbaa / rgba() / 变量引用（只跟一层）。
 */
function cssColorToHex(raw, vars, depth = 0) {
  if (!raw || depth > 4) return null
  let v = raw.trim()

  const varRef = v.match(/^var\((--[a-z0-9-]+)\)$/)
  if (varRef) return cssColorToHex(vars[varRef[1]], vars, depth + 1)

  let m = v.match(/^#([0-9a-fA-F]{3})$/)
  if (m) {
    const [r, g, b] = m[1].split('')
    return `#FF${r}${r}${g}${g}${b}${b}`.toUpperCase()
  }
  m = v.match(/^#([0-9a-fA-F]{6})$/)
  if (m) return `#FF${m[1]}`.toUpperCase()
  m = v.match(/^#([0-9a-fA-F]{8})$/)
  if (m) {
    const h = m[1]
    // CSS 是 #RRGGBBAA，Kotlin 是 #AARRGGBB
    return `#${h.slice(6, 8)}${h.slice(0, 6)}`.toUpperCase()
  }
  m = v.match(/^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:[\s,/]+([\d.]+))?\s*\)$/)
  if (m) {
    const to2 = (n) => Math.round(Number(n)).toString(16).padStart(2, '0')
    const a = m[4] === undefined ? 1 : Number(m[4])
    const toA = (n) => Math.round(Number(n) * 255).toString(16).padStart(2, '0')
    return `#${toA(a)}${to2(m[1])}${to2(m[2])}${to2(m[3])}`.toUpperCase()
  }
  return null
}

/** 从 Kotlin 里取某个参数（InkPalette / PaperPalette 块内的字段值） */
function parseKotlinPalette(source, paletteName) {
  const start = source.indexOf(`val ${paletteName} = MorrowPalette(`)
  if (start < 0) return {}
  const end = source.indexOf('\n)', start)
  const block = source.slice(start, end)
  const out = {}
  for (const m of block.matchAll(/^\s{2}([a-zA-Z0-9]+)\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)/gm)) {
    out[m[1]] = `#${m[2].slice(2).toUpperCase()}`
  }
  return out
}

/** 从 Kotlin 里取 dp 值 */
function parseKotlinDp(source, objectName) {
  const start = source.indexOf(`object ${objectName} {`)
  if (start < 0) return {}
  const end = source.indexOf('\n}', start)
  const block = source.slice(start, end)
  const out = {}
  for (const m of block.matchAll(/val\s+([a-zA-Z0-9]+)\s*(?::\s*Dp\s*)?=\s*([a-zA-Z0-9.]+)\.dp/g)) {
    out[m[1]] = Number(m[2])
  }
  return out
}

/** CSS px 数值 */
function cssPx(raw) {
  const m = String(raw ?? '').match(/^(-?[\d.]+)px$/)
  return m ? Number(m[1]) : null
}

const inkKt = parseKotlinPalette(kt, 'InkPalette')
const paperKt = parseKotlinPalette(kt, 'PaperPalette')
const radiusKt = parseKotlinDp(kt, 'MorrowRadius')
const spacingKt = parseKotlinDp(kt, 'MorrowSpacing')

const fails = []
const warns = []
let checked = 0

// ── 颜色：两套主题逐项比对 ──
for (const [cssVar, ktField] of Object.entries(COLOR_MAP)) {
  for (const [themeName, cssVars, ktPalette] of [
    ['Ink  ', ink, inkKt],
    ['Paper', paper, paperKt]
  ]) {
    checked++
    if (!(cssVar in cssVars)) {
      fails.push(`${themeName} 桌面缺变量 ${cssVar}`)
      continue
    }
    const expected = cssColorToHex(cssVars[cssVar], cssVars)
    if (!expected) {
      warns.push(`${themeName} ${cssVar} 无法解析为颜色（${cssVars[cssVar]}），跳过`)
      continue
    }
    const actual = ktPalette[ktField]
    if (!actual) {
      fails.push(`${themeName} 手机缺令牌 ${ktField}（对应 ${cssVar}）`)
    } else if (actual !== expected) {
      fails.push(`${themeName} ${cssVar} 色值不符：桌面 ${expected} / 手机 ${actual}（${ktField}）`)
    }
  }
}

// 手机端多出来的字段：登记过的移动端令牌不报，其余提示人工确认
const extraFields = Object.keys(inkKt).filter(
  (f) => !Object.values(COLOR_MAP).includes(f) && !(f in MOBILE_ONLY)
)
for (const f of extraFields) {
  warns.push(`手机端有桌面未映射的字段 ${f}（如为移动端特有令牌请在此脚本登记）`)
}

// ── 圆角：桌面 px → 手机 dp，同值 ──
for (const [cssVar, ktField] of Object.entries(RADIUS_MAP)) {
  checked++
  const expected = cssPx(ink[cssVar])
  const actual = radiusKt[ktField]
  if (expected === null) {
    fails.push(`桌面缺圆角变量 ${cssVar}`)
  } else if (actual === undefined) {
    fails.push(`手机缺圆角令牌 ${ktField}（对应 ${cssVar}）`)
  } else if (actual !== expected) {
    fails.push(`圆角 ${cssVar} 不符：桌面 ${expected}dp / 手机 ${actual}dp`)
  }
}

// ── 间距：桌面 --sp-N px → 手机 sN dp ──
for (const [cssVar, ktField] of Object.entries(SPACING_MAP)) {
  checked++
  const expected = cssPx(ink[cssVar])
  const actual = spacingKt[ktField]
  if (expected === null) {
    fails.push(`桌面缺间距变量 ${cssVar}`)
  } else if (actual === undefined) {
    fails.push(`手机缺间距令牌 ${ktField}（对应 ${cssVar}）`)
  } else if (actual !== expected) {
    fails.push(`间距 ${cssVar} 不符：桌面 ${expected}dp / 手机 ${actual}dp`)
  }
}

// ── 报告 ──
const C = {
  ok: '\x1b[32m', bad: '\x1b[31m', warn: '\x1b[33m', dim: '\x1b[2m', off: '\x1b[0m'
}

console.log('═══ A2 · token 对照表（桌面 styles.css ↔ Compose DesignTokens.kt）═══')
console.log(`${C.dim}桌面源：${CSS}${C.off}`)
console.log(`${C.dim}手机端：${TOKENS}${C.off}`)
console.log()
console.log(`比对项：${checked}（颜色 ${Object.keys(COLOR_MAP).length}×2 主题 + 圆角 ${Object.keys(RADIUS_MAP).length} + 间距 10）`)
console.log()

if (warns.length) {
  console.log(`${C.warn}提示 ${warns.length} 条：${C.off}`)
  for (const w of warns) console.log(`  ${C.warn}!${C.off} ${w}`)
  console.log()
}

if (fails.length) {
  console.log(`${C.bad}不一致 ${fails.length} 处：${C.off}`)
  for (const f of fails) console.log(`  ${C.bad}✗${C.off} ${f}`)
  console.log()
  console.log(`${C.bad}═══ 结果：NOT PARITY ═══${C.off}`)
  process.exit(1)
}

console.log(`${C.ok}═══ 结果：FULL PARITY（${checked} 项全部一致）═══${C.off}`)