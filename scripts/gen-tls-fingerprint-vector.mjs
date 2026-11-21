/**
 * 生成「桌面 TLS 证书指纹」的跨语言固定向量，供手机侧 `TlsFingerprintVectorTest` 断言。
 *
 * ## 为什么必须直接 import 桌面的 certFingerprint
 *
 * 这个值的用途是**证书固定**：手机连桌面时比对 sha256 指纹。
 * 如果手机自己实现一份「看起来一样」的算法，两边算出的字符串不同，
 * 用户看到的就是「指纹不匹配 → 证书不受信任」，而证书完全正确。
 * 手抄一遍参数就是在测「手机实现与我的抄本一致」，桌面源码从未被验证 ——
 * 这个坑 D1e 踩过一次，这里从一开始就 import。
 *
 * ## 为什么要内嵌一张**固定证书**
 *
 * 现场生成的证书每次都不一样，向量不可复现。所以：
 * 1. 生成一次证书，把 **DER 的 base64** 存进脚本与 Kotlin 两侧；
 * 2. 两侧各自从同一份 DER 算指纹；
 * 3. 值必须逐字符相同。
 *
 * 用 DER 而不是 PEM：PEM 的换行/首尾空格会随生成器变化，
 * 而桌面 `certFingerprint()` 正是先去掉 PEM 头再取 DER —— 那一步本身也值得验证，
 * 所以这里**同时**验证 PEM 输入与 DER 输入得到同一结果。
 *
 * 用法：
 *   node scripts/gen-tls-fingerprint-vector.mjs          # 打印向量与校验结果
 *   node scripts/gen-tls-fingerprint-vector.mjs --check  # 与 Kotlin 里的硬编码值比对
 */
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'

// 直接 import 桌面真实实现；桌面改一个字符，重跑本脚本的值就会变
const { certFingerprint } = await import('../../Morrow/src/main/relay.ts')

/**
 * 固定证书的 DER（base64，无换行）。
 *
 * 自签证书，CN=morrow-lan，有效期 3650 天，RSA-2048，sha256 签名 ——
 * 与桌面 `tls.ts` 的 `runGenerator` 参数一致。私钥已丢弃（只验证指纹，不需要它）。
 */
const FIXED_CERT_DER_B64 =
  'MIIDAzCCAeugAwIBAgIJcrSsqs73kWYIMA0GCSqGSIb3DQEBCwUAMBUxEzARBgNVBAMTCm1vcnJv' +
  'dy1sYW4wHhcNMjYxMTIwMTI1NzI4WhcNMjcxMTIwMTI1NzI4WjAVMRMwEQYDVQQDEwptb3Jyb3ct' +
  'bGFuMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAzHtS6yUzWstCO8MwvOqZ7maOEx7v' +
  'i6LxS/RQaEwFDX1REzhr88tF+5W9JGi+St9ByGAPPqJQL92hDtjQ+VysONuTQcHpuPEmtXNRK4r3' +
  'S7nHxid0tGHxPmW0pUlB9U1GmswfVqnzSRR1zRuHQd0yqV+OhJN3CAeVfFRJvd+uYh4HIeRAgxYj' +
  'TvPOLcQhUbV7O01r5rUFmHYcntPSJi0aaAIfEIx9q1+IQBZgkA/B2iRd4YEwl0aSUIUwBhhHk69d' +
  'gO8QpxXAzBIZAUw8ErcygdNpplOkB+NzmUD0N/zueDXwF8hs9RPVTnaaLVLAeZTXu+zhghVXTT1O' +
  'zzBCUiE8UwIDAQABo1YwVDAMBgNVHRMBAf8EAjAAMA4GA1UdDwEB/wQEAwIFoDAdBgNVHSUEFjAU' +
  'BggrBgEFBQcDAQYIKwYBBQUHAwIwFQYDVR0RBA4wDIIKbW9ycm93LWxhbjANBgkqhkiG9w0BAQsF' +
  'AAOCAQEAcGEaA6/PF5Afxj20uxyx3nZowxZv5LhsPKEuok8yEDMdKAx8uoRiN/t0n0g6DakphuGF' +
  'J8AP7ikrr2178hfLQHNszx1n2tm3GXJNns0DtH7ch5+W/rOO1uSjQyTKaZb+EWV46OaUKICNQdA5' +
  '9LbwKJYAMor2ovT+dyzVaRhuvUBezGnSxPDgAJEGySyTzCI3tPqNxpuj4zDcNAexawap9BXRLUOW' +
  'qdqaRtDRlui7FtUQ6TsN2tcaJGrj9ihNSnPQOZq6MZgKVWeYS69J5PNKq9exDJeW2pggAXq3TwOD' +
  'OvCbFKCcg2hEsRABpeW/jbUH0rW0DVWNtR+d0d+NG3U0Rw=='

const der = Buffer.from(FIXED_CERT_DER_B64, 'base64')

/** 把 DER 包成 PEM（76 列换行），模拟桌面从磁盘读到的文件 */
function toPem(d) {
  const b64 = d.toString('base64')
  const lines = []
  for (let i = 0; i < b64.length; i += 64) lines.push(b64.slice(i, i + 64))
  return `-----BEGIN CERTIFICATE-----\n${lines.join('\n')}\n-----END CERTIFICATE-----\n`
}

const pem = toPem(der)

// ── 桌面算法算出的指纹 ──
const desktopFromPem = certFingerprint(pem)
const desktopFromDer = createHash('sha256').update(der).digest('base64url')

// ── 自检：两条路径必须得到同一个值 ──
// 若不等，说明桌面 certFingerprint 的 PEM 剥离逻辑有问题（比如换行没去干净）
if (desktopFromPem !== desktopFromDer) {
  console.error('FAIL 桌面 certFingerprint(PEM) 与 sha256(DER) 不一致')
  console.error('  PEM 路径:', desktopFromPem)
  console.error('  DER 路径:', desktopFromDer)
  process.exit(1)
}

/** 只在自检输出里展示，不参与 Kotlin 比对（它是推导值，不是硬编码值） */
const checks = {
  证书指纹: desktopFromPem,
  证书指纹不含填充: String(!desktopFromPem.includes('=')),
  证书指纹长度: String(desktopFromPem.length),
  DER长度: String(der.length)
}

/** 参与 Kotlin 比对的值：必须全部是 Kotlin 里真实存在的字面量 */
const kotlinChecks = {
  证书指纹: desktopFromPem,
  证书指纹长度: String(desktopFromPem.length),
  DER长度: String(der.length)
}

const checkMode = process.argv.includes('--check')

if (checkMode) {
  const kt = readFileSync('app/src/test/java/studio/morrow/android/RelayCryptoTests.kt', 'utf8')
  let bad = 0
  for (const [name, value] of Object.entries(kotlinChecks)) {
    if (kt.includes(value)) {
      console.log(`  OK   ${name} = ${value}`)
    } else {
      console.log(`  FAIL ${name}：Kotlin 里找不到 ${value}`)
      bad++
    }
  }
  // DER 原文必须一致，否则「同一份输入」这个前提就不成立。
  // Kotlin 里为了可读性把 base64 拆成了多段字符串拼接，所以比对前要
  // 去掉引号、加号与空白，只留 base64 字符本身。
  const squash = (t) => t.replace(/["+\s]/g, '')
  if (squash(kt).includes(squash(FIXED_CERT_DER_B64))) {
    console.log('  OK   DER base64 一致')
  } else {
    console.log('  FAIL Kotlin 里的 DER base64 与脚本不一致')
    bad++
  }
  // 固定向量必须含 base64url 专有字符，否则这组测试证明不了它想证明的事
  if (desktopFromPem.includes('-') || desktopFromPem.includes('_')) {
    console.log('  OK   向量含 base64url 专有字符（- 或 _）')
  } else {
    console.log('  FAIL 向量不含 - 或 _，测不到「标准解码器拒绝」这个缺陷')
    bad++
  }
  const total = Object.keys(kotlinChecks).length + 2
  console.log(`\n桌面源码 vs 手机硬编码：${total - bad} 一致 / ${bad} 不一致`)
  process.exit(bad === 0 ? 0 : 1)
}

console.log('// ── 由 scripts/gen-tls-fingerprint-vector.mjs 生成，勿手改 ──')
console.log('// 值来自桌面真实 certFingerprint()（import 而非手抄）')
console.log(`const val EXPECTED_FINGERPRINT = "${desktopFromPem}"`)
console.log()
console.log('自检：')
for (const [k, v] of Object.entries(checks)) console.log(`  ${k}: ${v}`)
// 只为了让人看到它确实含 base64url 专有字符（这就是那个缺陷的来源）
const hasUrlChar = desktopFromPem.includes('-') || desktopFromPem.includes('_')
console.log(`  含 base64url 专有字符（- 或 _）: ${hasUrlChar}`)
