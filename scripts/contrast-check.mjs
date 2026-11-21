#!/usr/bin/env node
/**
 * 对比度检测：按蓝图 03 章「无障碍与可读性」的安卓线验收。
 *
 * 文档原文：「正文对比度 ≥7:1（AAA），元信息 ≥4.5:1；金色文字只用在 ≥15sp 的正文级别」。
 * 所以这里分三级：
 *   - text  （正文）      ≥ 7:1
 *   - text2 （次级正文）  ≥ 7:1
 *   - hint  （元信息）    ≥ 4.5:1
 *
 * 两个容易做错、做错就会得出虚假高分的地方：
 * 1. **必须用 C1 抬底后的真实底色**。抬底把 surface 从 #141922 抬到 #171D27，
 *    卡片上的对比度会随之下降（text2 从 6.53 降到 6.27）。若拿桌面原值算，会漏掉这个副作用。
 * 2. **必须做 alpha 合成**。桌面大量 token 是 8 位半透明，直接当纯色算会虚高。
 *
 * 用法：node scripts/contrast-check.mjs
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

function parsePalette(name) {
  const start = tokensSrc.indexOf(`val ${name} = MorrowPalette(`)
  const end = tokensSrc.indexOf('\n)', start)
  const block = tokensSrc.slice(start, end)
  const out = {}
  for (const m of block.matchAll(/^\s{2}([a-zA-Z0-9]+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)/gm)) {
    const hex = m[2].toUpperCase()
    out[m[1]] = {
      a: parseInt(hex.slice(0, 2), 16) / 255,
      r: parseInt(hex.slice(2, 4), 16),
      g: parseInt(hex.slice(4, 6), 16),
      b: parseInt(hex.slice(6, 8), 16)
    }
  }
  return out
}

function parseAdjust(name) {
  const start = adjSrc.indexOf(`val ${name} = MobileAdjustments(`)
  const end = adjSrc.indexOf('\n)', start)
  const block = adjSrc.slice(start, end)
  const out = {}
  for (const m of block.matchAll(/^\s{2}([a-zA-Z0-9]+)\s*=\s*(Color\(0x([0-9A-Fa-f]{8})\)|null)/gm)) {
    if (!m[3]) continue
    const hex = m[3].toUpperCase()
    out[m[1]] = {
      a: parseInt(hex.slice(0, 2), 16) / 255,
      r: parseInt(hex.slice(2, 4), 16),
      g: parseInt(hex.slice(4, 6), 16),
      b: parseInt(hex.slice(6, 8), 16)
    }
  }
  return out
}

function composite(fg, bgOpaque) {
  if (fg.a >= 1) return fg
  return {
    a: 1,
    r: fg.r * fg.a + bgOpaque.r * (1 - fg.a),
    g: fg.g * fg.a + bgOpaque.g * (1 - fg.a),
    b: fg.b * fg.a + bgOpaque.b * (1 - fg.a)
  }
}

function luminance(c) {
  const f = (v) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b)
}

function contrast(fg, bg) {
  const l1 = luminance(fg)
  const l2 = luminance(bg)
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1]
  return (hi + 0.05) / (lo + 0.05)
}

/** 文档三条线 */
const LINES = {
  text: { min: 7.0, label: '正文 (AAA)' },
  text2: { min: 7.0, label: '次级正文 (AAA)' },
  hint: { min: 4.5, label: '元信息 (AA)' }
}

/** 会被文字覆盖的背景角色。bg / surface 用 C1 后的实际值。 */
const BG_ROLES = ['bg', 'bgElev', 'surface', 'surfaceHover', 'surfaceActive', 'surfaceInset']

const results = []
let fails = 0

for (const [themeName, paletteName, adjName] of [
  ['Ink', 'InkPalette', 'InkMobile'],
  ['Paper', 'PaperPalette', 'PaperMobile']
]) {
  const pal = parsePalette(paletteName)
  const adj = parseAdjust(adjName)

  // C1 生效后的实际底色
  const realBg = adj.bg ?? pal.bg
  const realSurface = adj.surface ?? pal.surface
  const realBgOpaque = realBg.a < 1 ? composite(realBg, pal.bg) : realBg

  const bgOf = (role) => {
    if (role === 'bg') return realBgOpaque
    const base = role === 'surface' ? realSurface : pal[role]
    return base.a < 1 ? composite(base, realBgOpaque) : base
  }

  // 文字色：text 用桌面原值；text2 / hint 用移动端覆盖值
  const fgOf = (role) => (role === 'text' ? pal.text : (adj[role] ?? pal[role]))

  for (const fgRole of Object.keys(LINES)) {
    for (const bgRole of BG_ROLES) {
      const bg = bgOf(bgRole)
      const fgRaw = fgOf(fgRole)
      if (!fgRaw) {
        results.push({ themeName, fgRole, bgRole, ratio: null, min: LINES[fgRole].min })
        fails++
        continue
      }
      const fg = composite(fgRaw, bg)
      const ratio = contrast(fg, bg)
      const min = LINES[fgRole].min
      const ok = ratio >= min
      if (!ok) fails++
      results.push({ themeName, fgRole, bgRole, ratio, min, ok })
    }
  }
}

const C = { ok: '\x1b[32m', bad: '\x1b[31m', dim: '\x1b[2m', off: '\x1b[0m' }

console.log('═══ A2 · 对比度检测（蓝图 03 章安卓线：正文 7:1 / 元信息 4.5:1）═══')
console.log(`${C.dim}令牌源：${TOKENS}${C.off}`)
console.log(`${C.dim}移动端覆盖：${ADJ}（底色按 C1 抬底后的真实值计算）${C.off}`)
console.log()

for (const [themeName] of [['Ink'], ['Paper']]) {
  console.log(`${themeName}：`)
  for (const fgRole of Object.keys(LINES)) {
    const rows = results.filter((r) => r.themeName === themeName && r.fgRole === fgRole)
    const worst = rows.reduce(
      (a, b) => ((b.ratio ?? 0) < (a.ratio ?? 0) ? b : a),
      rows[0]
    )
    const bad = rows.filter((r) => !r.ok)
    const tag = bad.length ? `${C.bad}✗ ${bad.length} 组未达标${C.off}` : `${C.ok}全部达标${C.off}`
    console.log(
      `  ${LINES[fgRole].label.padEnd(16)} 需 ≥${LINES[fgRole].min.toFixed(1)}:1  ` +
        `最差 ${(worst.ratio ?? 0).toFixed(2)}:1 (on ${worst.bgRole})  ${tag}`
    )
    for (const b of bad) {
      console.log(`      ${C.bad}✗${C.off} ${b.fgRole} on ${b.bgRole} = ${(b.ratio ?? 0).toFixed(2)}:1`)
    }
  }
  console.log()
}

const total = results.length
console.log(`共检查 ${total} 组（2 主题 × 3 文字级 × ${BG_ROLES.length} 背景）`)
console.log()

if (fails) {
  console.log(`${C.bad}═══ 结果：FAIL（${fails} 组未达标）═══${C.off}`)
  process.exit(1)
}
console.log(`${C.ok}═══ 结果：PASS（全部达文档安卓线）═══${C.off}`)