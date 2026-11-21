#!/usr/bin/env node
/**
 * 核对移动端三处调整（蓝图 03 章 C1 / C2 / C3）是否严格按文档规则算出。
 *
 * 为什么需要它：C3 的规则是「降饱和 8%、亮度 +3%」，这些值如果凭感觉手填，
 * 三个月后没人说得清当初为什么是这个数。这里把规则写成代码，重算并比对，
 * 硬编码值只要偏离规则一个色阶就报错。
 *
 * 用法：node scripts/mobile-adjust.mjs
 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const TOKENS = resolve(ROOT, 'app/src/main/java/studio/morrow/android/DesignTokens.kt')
// MobileAdjustments 已并入 Shell.kt（扁平结构），锚点 `val InkMobile = MobileAdjustments(` 仍唯一
const ADJ = resolve(ROOT, 'app/src/main/java/studio/morrow/android/Shell.kt')

const tokensSrc = readFileSync(TOKENS, 'utf8')
const adjSrc = readFileSync(ADJ, 'utf8')

const hex2rgb = (h) => [
  parseInt(h.slice(1, 3), 16),
  parseInt(h.slice(3, 5), 16),
  parseInt(h.slice(5, 7), 16)
]
const rgb2hex = (a) =>
  '#' + a.map((v) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('').toUpperCase()

function rgb2hsl(r, g, b) {
  r /= 255
  g /= 255
  b /= 255
  const mx = Math.max(r, g, b)
  const mn = Math.min(r, g, b)
  let h = 0
  let s = 0
  const l = (mx + mn) / 2
  if (mx !== mn) {
    const d = mx - mn
    s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn)
    switch (mx) {
      case r: h = (g - b) / d + (g < b ? 6 : 0); break
      case g: h = (b - r) / d + 2; break
      default: h = (r - g) / d + 4
    }
    h /= 6
  }
  return [h * 360, s, l]
}

function hsl2rgb(h, s, l) {
  h = ((h % 360) + 360) % 360 / 360
  const f = (p, q, t) => {
    if (t < 0) t += 1
    if (t > 1) t -= 1
    if (t < 1 / 6) return p + (q - p) * 6 * t
    if (t < 1 / 2) return q
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6
    return p
  }
  if (s === 0) return [l * 255, l * 255, l * 255]
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s
  const p = 2 * l - q
  return [f(p, q, h + 1 / 3) * 255, f(p, q, h) * 255, f(p, q, h - 1 / 3) * 255]
}

/** C3 规则：饱和度 −0.08，亮度 +0.03 */
function applyC3(hex) {
  const [h, s, l] = rgb2hsl(...hex2rgb(hex))
  return rgb2hex(hsl2rgb(h, Math.max(0, s - 0.08), Math.min(1, l + 0.03)))
}

function parsePalette(name) {
  const start = tokensSrc.indexOf(`val ${name} = MorrowPalette(`)
  const end = tokensSrc.indexOf('\n)', start)
  const block = tokensSrc.slice(start, end)
  const out = {}
  for (const m of block.matchAll(/^\s{2}([a-zA-Z0-9]+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)/gm)) {
    out[m[1]] = `#${m[2].slice(2).toUpperCase()}`
  }
  return out
}

function parseAdjust(name) {
  const start = adjSrc.indexOf(`val ${name} = MobileAdjustments(`)
  const end = adjSrc.indexOf('\n)', start)
  const block = adjSrc.slice(start, end)
  const out = {}
  for (const m of block.matchAll(/^\s{2}([a-zA-Z0-9]+)\s*=\s*(Color\(0x([0-9A-Fa-f]{8})\)|null)/gm)) {
    out[m[1]] = m[3] ? `#${m[3].slice(2).toUpperCase()}` : null
  }
  return out
}

const ink = parsePalette('InkPalette')
const paper = parsePalette('PaperPalette')
const inkAdj = parseAdjust('InkMobile')
const paperAdj = parseAdjust('PaperMobile')

const fails = []
let checked = 0

// ── C1 抬底：文档直接给值，只核对墨夜 ──
// 蓝图原文：「--bg 从 #0b0d10 提到 #0e1116，--surface 从 #141922 提到 #171d27」
const C1_EXPECTED = { bg: '#0E1116', surface: '#171D27' }
for (const [field, want] of Object.entries(C1_EXPECTED)) {
  checked++
  const base = ink[field]
  const got = inkAdj[field]
  if (!got) {
    fails.push(`墨夜 C1 ${field} 未覆盖（文档要求提到 ${want}）`)
  } else if (got !== want) {
    fails.push(`墨夜 C1 ${field} 应为 ${want}（文档值），实为 ${got}`)
  }
  if (base === want) {
    fails.push(`墨夜 C1 ${field} 与桌面原值相同，抬底没有生效`)
  }
}
// 素纸不做 C1，必须确实为 null（避免有人凭感觉给浅色主题编规则）
for (const field of ['bg', 'surface']) {
  checked++
  if (paperAdj[field] !== null) {
    fails.push(`素纸 C1 ${field} 应为 null（文档的抬底理由只针对深色，不应给浅色主题编值）`)
  }
}

// ── C3 状态色：按规则重算并比对（两套主题）──
for (const [themeName, palette, adj] of [
  ['Ink', ink, inkAdj],
  ['Paper', paper, paperAdj]
]) {
  for (const field of ['patina', 'rust', 'amber', 'mauve']) {
    checked++
    const expected = applyC3(palette[field])
    const got = adj[field]
    if (!got) {
      fails.push(`${themeName} C3 ${field} 缺失（应为 ${expected}）`)
    } else if (got !== expected) {
      fails.push(`${themeName} C3 ${field} 不符规则：按 −8% 饱和 / +3% 亮度 应为 ${expected}，实为 ${got}`)
    }
  }
}

// ── C2 减金：金色只保留「主操作按钮」与「运行中状态点」──
// 这是使用规则，不是色值，无法从令牌层机械验证。
// 这里只固定住「金色令牌本身未被滥用成大面积底色」这一条可判定的事实：
// 金色相关令牌必须仍是强调色（不可等于 surface/bg），供后续页面遵循「只留两处」。
checked++
if (ink.gold === ink.surface || ink.gold === ink.bg) {
  fails.push('C2：金色令牌不应等于底色或卡片色')
}

const C = { ok: '\x1b[32m', bad: '\x1b[31m', dim: '\x1b[2m', off: '\x1b[0m' }

console.log('═══ A2 · 移动端三处调整核对（C1 抬底 / C2 减金 / C3 降饱和）═══')
console.log(`${C.dim}规则源：Morrow-Android-移植蓝图.html「色彩：直接继承，只调三处」${C.off}`)
console.log(`${C.dim}实现：ui/theme/MobileAdjustments.kt${C.off}`)
console.log()

console.log('C1 抬底（墨夜，文档给值）：')
console.log(`  bg      ${ink.bg} → ${inkAdj.bg ?? '(未覆盖)'}`)
console.log(`  surface ${ink.surface} → ${inkAdj.surface ?? '(未覆盖)'}`)
console.log(`  ${C.dim}素纸不做 C1：bg/surface 保持桌面原值${C.off}`)
console.log()
console.log('C3 状态色（−8% 饱和 / +3% 亮度，按规则重算）：')
for (const [themeName, palette, adj] of [
  ['Ink', ink, inkAdj],
  ['Paper', paper, paperAdj]
]) {
  const parts = ['patina', 'rust', 'amber', 'mauve'].map(
    (f) => `${f} ${palette[f]}→${adj[f]}`
  )
  console.log(`  ${themeName.padEnd(6)} ${parts.join('  ')}`)
}
console.log()

if (fails.length) {
  console.log(`${C.bad}不一致 ${fails.length} 处：${C.off}`)
  for (const f of fails) console.log(`  ${C.bad}✗${C.off} ${f}`)
  console.log()
  console.log(`${C.bad}═══ 结果：FAIL ═══${C.off}`)
  process.exit(1)
}

console.log(`${C.ok}═══ 结果：PASS（${checked} 项全部符合文档规则）═══${C.off}`)