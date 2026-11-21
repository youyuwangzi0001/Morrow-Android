/**
 * 反向验证：手机加密 → 桌面解密。
 *
 * 前面的向量测试证明「手机能解桌面密文」。反过来「桌面能解手机密文」
 * 不能靠推理：base64url 是否带填充、密文与 tag 的拼接顺序、
 * 计数器起始值，任一处不同都会有单向能通、反向不通的情况。
 *
 * 用法：
 *   bash scripts/run-jvm-tests.sh studio.morrow.android.SecureChannelSealProbe > /dev/null 2>&1
 *   node scripts/verify-phone-seal.mjs
 *
 * probe 测试会把手机加密的结果写进 build-evidence/phone-sealed.json，
 * 这个脚本读它并用桌面的 SecureChannel 逻辑解密。
 */
import fs from 'node:fs'
import { createDecipheriv, hkdfSync } from 'node:crypto'

// 测试的 JVM 工作目录是 app/，产出物落在 app/build-evidence/。
// 两个位置都找，避免因为 cwd 差异让人以为「没写出来」。
const CANDIDATES = ['build-evidence/phone-sealed.json', 'app/build-evidence/phone-sealed.json']
const FILE = CANDIDATES.find((f) => fs.existsSync(f))
if (!FILE) {
  console.error(`缺少 phone-sealed.json（找过 ${CANDIDATES.join(' 与 ')}）—— 先跑 SecureChannelSealProbe`)
  process.exit(2)
}
console.log(`读取 ${FILE}`)

const VERSION = 'morrow-frame:v1'
const KEY_BYTES = 32
const NONCE_BYTES = 12
const TAG_BYTES = 16

const nonceFor = (c) => {
  const n = Buffer.alloc(NONCE_BYTES)
  n.writeUInt32BE(0, 0)
  n.writeBigUInt64BE(BigInt(c), 4)
  return n
}
const aadFor = (prefix, c) => {
  const s = Buffer.alloc(8)
  s.writeBigUInt64BE(BigInt(c), 0)
  return Buffer.concat([prefix, s])
}
const direction2tag = (d) => (d === 'phone-to-desktop' ? 'p2d' : 'd2p')
const derive = (ikm, salt, dir, dev) =>
  Buffer.from(hkdfSync('sha256', ikm, salt, Buffer.from(`${VERSION}:${dir}:${dev}`), KEY_BYTES))

const data = JSON.parse(fs.readFileSync(FILE, 'utf8'))
const ikm = Buffer.from(data.sessionKeyBase64, 'base64')
const salt = Buffer.from(data.connectionSaltBase64, 'base64')

let ok = 0
let bad = 0
for (const f of data.frames) {
  const key = derive(ikm, salt, f.direction, data.deviceId)
  const prefix = Buffer.from(`${VERSION}|${data.deviceId}|${direction2tag(f.direction)}|`, 'utf8')
  const payload = Buffer.from(f.c, 'base64url')
  const ct = payload.subarray(0, payload.length - TAG_BYTES)
  const tag = payload.subarray(payload.length - TAG_BYTES)
  try {
    const d = createDecipheriv('aes-256-gcm', key, nonceFor(Number(f.n)))
    d.setAAD(aadFor(prefix, Number(f.n)))
    d.setAuthTag(tag)
    const plain = Buffer.concat([d.update(ct), d.final()]).toString('utf8')
    if (plain === f.expectedPlaintext) {
      console.log(`  OK   ${f.direction} n=${f.n} -> ${JSON.stringify(plain)}`)
      ok++
    } else {
      console.log(`  FAIL ${f.direction} n=${f.n} 明文不符: ${JSON.stringify(plain)} != ${JSON.stringify(f.expectedPlaintext)}`)
      bad++
    }
  } catch (e) {
    console.log(`  FAIL ${f.direction} n=${f.n} 解密抛错: ${e.message}`)
    bad++
  }
}

// base64url 不带填充的检查：带 '=' 桌面 Buffer.from(...,'base64url') 仍能解，
// 但说明格式与 Node 的输出不一致，属于「将来可能出问题」的一类。
const padded = data.frames.filter((f) => f.c.includes('='))
if (padded.length > 0) {
  console.log(`  WARN 有 ${padded.length} 条密文带 base64 填充（桌面输出不带）`)
  bad++
}

console.log(`\n桌面解手机密文：${ok} 通过 / ${bad} 失败`)
process.exit(bad === 0 ? 0 : 1)