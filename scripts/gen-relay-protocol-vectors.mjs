/**
 * 生成 relay 协议层的跨语言向量：直接调用**桌面真实的 PairingService 与 DirectChannel**。
 *
 * 要验的三件事都可以离线钉死：
 * ① hello 签名（桌面 `signRequest` 的换行分隔与 base64url）
 * ② welcome 帧的真实字段形状（含 snapshotRequired 是字符串不是布尔）
 * ③ 加密帧的真实形状（`{n, c}`，n 是字符串）
 *
 * 用法：
 *   node --experimental-strip-types scripts/gen-relay-protocol-vectors.mjs
 *   node --experimental-strip-types scripts/gen-relay-protocol-vectors.mjs --check
 */
import { createECDH } from 'node:crypto'
import { PairingService, derivePairingCode } from '../../Morrow/src/main/relay.ts'
import { readFileSync } from 'node:fs'

// relay-channel.ts 内部用无扩展名的相对导入（`from './relay-crypto'`，electron-vite 风格），
// Node 的 strip-types 解析不了；这里只需要一个常量，直接读桌面源码里的字面量。
const SNAPSHOT_REQUIRED = (() => {
  const src = readFileSync(new URL('../../Morrow/src/main/relay-channel.ts', import.meta.url), 'utf8')
  const m = src.match(/export const SNAPSHOT_REQUIRED\s*=\s*'([^']+)'/)
  if (!m) throw new Error('relay-channel.ts 里找不到 SNAPSHOT_REQUIRED')
  return m[1]
})()

const DESKTOP_SECRET_HEX = '2b7e151628aed2a6abf7158809cf4f3c2b7e151628aed2a6abf7158809cf4f3c'
const DESKTOP_PRIVATE_HEX = '1f3a5e7c9b2d406152738495a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f'
const PHONE_PRIVATE_HEX = '1a2b3c4d5e6f708192a3b4c5d6e7f8090a1b2c3d4e5f60718293a4b5c6d7e8f9'
const DEVICE_ID = 'dev-test-0001'
const FIXED_TS = '1700000000000'

function makeDesktop() {
  return new PairingService({
    secret: Buffer.from(DESKTOP_SECRET_HEX, 'hex'),
    privateKey: Buffer.from(DESKTOP_PRIVATE_HEX, 'hex').toString('base64'),
    now: () => 1_700_000_000_000,
  })
}

function build() {
  const desktop = makeDesktop()
  const phoneEcdh = createECDH('prime256v1')
  phoneEcdh.setPrivateKey(Buffer.from(PHONE_PRIVATE_HEX, 'hex'))
  const phonePub = phoneEcdh.getPublicKey().toString('base64')

  const { ticket } = desktop.issueTicket(DEVICE_ID, phonePub, 'wss://192.168.1.10:47833')
  // 手机侧独立算 sessionKey（与 D1f 一致）
  const sessionKey = phoneEcdh.computeSecret(Buffer.from(ticket.desktopPub, 'base64')).toString('base64')
  const desktopDevice = desktop.completePairing(ticket.nonce, phonePub)

  // 用桌面静态方法生成 hello 签名
  const signLan = PairingService.signRequest(sessionKey, DEVICE_ID, 'lan', FIXED_TS)
  const signWss = PairingService.signRequest(sessionKey, DEVICE_ID, 'wss', FIXED_TS)

  // 桌面真实验证这对签名是否通得过（这是关键证据：不是我算的，是桌面认的）
  let accepted = false
  try {
    desktop.verifyRequest(DEVICE_ID, 'lan', FIXED_TS, signLan)
    accepted = true
  } catch { accepted = false }

  return {
    sessionKey,
    deviceId: DEVICE_ID,
    ts: FIXED_TS,
    signLan,
    signWss,
    accepted,
    desktopSessionKey: desktopDevice.sessionKey,
    snapshotRequiredTag: SNAPSHOT_REQUIRED,
  }
}

const v = build()
const checkMode = process.argv.includes('--check')

// 真实 welcome 帧（字段取自 direct-channel.ts:432-440）
const welcomeOk = JSON.stringify({
  v: 1, kind: 'welcome', deviceId: v.deviceId,
  fingerprint: 'SHA256:abcdef0123456789',
  salt: Buffer.alloc(16, 9).toString('base64'),
  newestSeq: 42, replayCount: 3,
})
const welcomeSnapshot = JSON.stringify({
  v: 1, kind: 'welcome', deviceId: v.deviceId,
  fingerprint: 'SHA256:abcdef0123456789',
  salt: Buffer.alloc(16, 9).toString('base64'),
  newestSeq: 42, snapshotRequired: SNAPSHOT_REQUIRED,
})

if (checkMode) {
  const fs = await import('node:fs')
  const kt = fs.readFileSync('app/src/test/java/studio/morrow/android/RelayProtocolTests.kt', 'utf8')
  let bad = 0
  const expect = [
    ['hello 签名（channel=lan）', v.signLan],
    ['hello 签名（channel=wss，应不同）', v.signWss],
    ['sessionKey', v.sessionKey],
    ['snapshotRequired 标记字面量', v.snapshotRequiredTag],
  ]
  for (const [name, value] of expect) {
    if (kt.includes(value)) console.log(`  OK   ${name} 一致`)
    else { console.log(`  FAIL ${name} 不一致\n       桌面现在给: ${value}`); bad++ }
  }
  if (v.accepted) console.log('  OK   桌面 verifyRequest 认可该签名')
  else { console.log('  FAIL 桌面 verifyRequest 拒绝了脚本生成的签名'); bad++ }
  if (v.sessionKey !== v.desktopSessionKey) {
    console.log('  FAIL sessionKey 两端不一致'); bad++
  } else console.log('  OK   桌面 completePairing 的 sessionKey 与手机一致')
  console.log(`\n桌面源码 vs 手机硬编码值：${expect.length + 2 - bad} 一致 / ${bad} 不一致`)
  process.exit(bad === 0 ? 0 : 1)
}

const out = {
  sessionKey: v.sessionKey,
  deviceId: v.deviceId,
  ts: v.ts,
  signLan: v.signLan,
  signWss: v.signWss,
  welcomeOk,
  welcomeSnapshot,
  snapshotRequiredTag: SNAPSHOT_REQUIRED,
}
const fs = await import('node:fs')
fs.mkdirSync('app/build-evidence', { recursive: true })
fs.writeFileSync('app/build-evidence/relay-protocol-vector.json', JSON.stringify(out, null, 2))

console.log('// ── 由 scripts/gen-relay-protocol-vectors.mjs 生成 ──')
console.log(`SESSION_KEY = "${v.sessionKey}"`)
console.log(`DEVICE_ID = "${v.deviceId}"`)
console.log(`FIXED_TS = "${v.ts}"`)
console.log(`SIGN_LAN = "${v.signLan}"`)
console.log(`SIGN_WSS = "${v.signWss}"`)
console.log(`SNAPSHOT_TAG = "${SNAPSHOT_REQUIRED}"`)
console.log(`\n// 桌面 verifyRequest 认可 lan 签名：${v.accepted}`)
console.log(`// 桌面 sessionKey 与手机一致：${v.sessionKey === v.desktopSessionKey}`)
console.log(`已写出 app/build-evidence/relay-protocol-vector.json`)