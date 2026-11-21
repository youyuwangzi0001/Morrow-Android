/**
 * 生成配对/ECDH 的跨语言固定向量：直接调用**桌面真实的 PairingService**，
 * 输出手机端要硬编码的期望值。
 *
 * ## 为什么一定 import 桌面源码
 *
 * 上一批（D1e）踩过这个坑：生成脚本手抄了一遍桌面参数，
 * 结果「跨语言互通」实际只证明「手机实现与我的抄本一致」，桌面源码从未被验证。
 * 这一批从一开始就用 `import { PairingService }`，桌面改一个字符，
 * 重跑本脚本得到的值就会变，手机侧测试立刻红。
 *
 * ## 用法
 *   node --experimental-strip-types scripts/gen-pairing-vectors.mjs          # 输出向量
 *   node --experimental-strip-types scripts/gen-pairing-vectors.mjs --check  # 与 Kotlin 硬编码值比对
 */
import { createECDH, randomBytes } from 'node:crypto'
import { PairingService, derivePairingCode } from '../../Morrow/src/main/relay.ts'

// ── 固定输入（手机端 RelayCryptoTests.kt 硬编码同一份）──
// 私钥固定，公钥才固定，向量才可复现。
// 手机侧固定私钥（32 字节标量，hex）。用标量而不是 PKCS8：
// PKCS8 里要按 DER 结构去挖 32 字节，容易写错且难核。
const PHONE_PRIVATE_HEX = '1a2b3c4d5e6f708192a3b4c5d6e7f8090a1b2c3d4e5f60718293a4b5c6d7e8f9'

// 桌面固定的 HMAC secret（不进二维码，只用于 PairingService 构造）
const DESKTOP_SECRET_HEX = '2b7e151628aed2a6abf7158809cf4f3c2b7e151628aed2a6abf7158809cf4f3c'
// 桌面固定的长期私钥（32 字节，必须落在 P-256 的合法标量范围内）
const DESKTOP_PRIVATE_HEX = '1f3a5e7c9b2d406152738495a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f'

const DEVICE_ID = 'dev-test-0001'
// 固定的 nonce：桌面 issueTicket 内部随机生成 nonce，导致每次跑出的六位码都不同，
// 向量就没法复现。这里用**同一个 nonce** 调桌面导出的纯函数 derivePairingCode，
// 保证向量稳定；并额外用桌面真实票据验证「票据里的 nonce 确实进入派生」。
const FIXED_NONCE = '50b5f6dd15b3e01da6287730afcd437f'
const FIXED_RELAY_HINT = 'wss://192.168.1.10:47833'

const FIXED_NOW = 1_700_000_000_000

/** 用固定私钥造一条桌面 PairingService（票据 nonce 仍随机，故只对比密钥与码的派生关系） */
function makeDesktop() {
  return new PairingService({
    secret: Buffer.from(DESKTOP_SECRET_HEX, 'hex'),
    privateKey: Buffer.from(DESKTOP_PRIVATE_HEX, 'hex').toString('base64'),
    now: () => FIXED_NOW,
  })
}

function build() {
  const desktop = makeDesktop()

  // 手机侧公钥：由固定**私钥标量**推出
  const phoneEcdh = createECDH('prime256v1')
  phoneEcdh.setPrivateKey(Buffer.from(PHONE_PRIVATE_HEX, 'hex'))
  const phonePubB64 = phoneEcdh.getPublicKey().toString('base64')

  // 手机侧独立计算：自己的私钥 + 桌面公钥（必须用桌面真实公钥）
  const desktopPubB64 = desktop.desktopPublicKey()
  const phoneShared = phoneEcdh.computeSecret(Buffer.from(desktopPubB64, 'base64'))

  // 票据由桌面真实签发。nonce 随机 → qr 每次不同，所以 Kotlin 里
  // 只硬编码「稳定量」：公钥、共享秘密、固定 nonce 下的六位码。
  const issued = desktop.issueTicket(DEVICE_ID, phonePubB64, FIXED_RELAY_HINT)
  const ticket = issued.ticket

  // 固定 nonce 下的六位码（可复现），用于跨语言断言
  const code = derivePairingCode(phoneShared, FIXED_NONCE)

  // 交叉核对：桌面真实票据的码 == 用派生共享秘密 + 那张票 nonce 独立算的码
  // 这一步证明 derivePairingCode 与 issueTicket 内部调用方式一致。
  const selfConsistent = issued.code === derivePairingCode(phoneShared, ticket.nonce)

  return {
    phonePubB64,
    desktopPubB64,
    ticket,
    qr: issued.qr,
    code,
    phoneSharedB64: phoneShared.toString('base64'),
    selfConsistent,
    // 桌面侧的 sessionKey：走完整流程拿到，与手机算的必须相同
    desktopSessionKey: desktop.completePairing(ticket.nonce, phonePubB64).sessionKey,
  }
}

const v = build()
const checkMode = process.argv.includes('--check')

if (checkMode) {
  const fs = await import('node:fs')
  const kt = fs.readFileSync('app/src/test/java/studio/morrow/android/RelayCryptoTests.kt', 'utf8')
  let bad = 0
  const expect = [
    ['手机公钥', v.phonePubB64],
    ['桌面公钥', v.desktopPubB64],
    ['固定 nonce 下的六位码', v.code],
    ['sessionKey（手机侧共享秘密）', v.phoneSharedB64],
  ]
  for (const [name, value] of expect) {
    if (kt.includes(value)) {
      console.log(`  OK   ${name} 一致`)
    } else {
      console.log(`  FAIL ${name} 与桌面当前实现不一致`)
      console.log(`       桌面现在给: ${value}`)
      bad++
    }
  }
  // desktopPub 进票据这条协议前提也要在 Kotlin 侧有对应断言
  if (v.ticket.desktopPub !== v.desktopPubB64) {
    console.log('  FAIL 票据里的 desktopPub 与 desktopPublicKey() 不一致')
    bad++
  }
  console.log(`\n桌面源码 vs 手机硬编码值：${expect.length + 1 - bad} 一致 / ${bad} 不一致`)
  process.exit(bad === 0 ? 0 : 1)
}

console.log('// ── 由 scripts/gen-pairing-vectors.mjs 生成，勿手改 ──')
console.log('// 值来自桌面真实 PairingService（import 而非手抄）')
console.log(`PHONE_PRIVATE_HEX = "${PHONE_PRIVATE_HEX}"`)
console.log(`PHONE_PUBLIC_KEY = "${v.phonePubB64}"`)
console.log(`DESKTOP_PUBLIC_KEY = "${v.desktopPubB64}"`)
console.log(`EXPECTED_CODE = "${v.code}"`)
console.log(`EXPECTED_SESSION_KEY = "${v.phoneSharedB64}"`)
console.log(`FIXED_NONCE = "${FIXED_NONCE}"`)
console.log()
console.log(`// 桌面真实签发的票据（nonce 随机，故写进 JSON 供测试读，不硬编码）`)
console.log(`// 桌面自洽：${v.selfConsistent}`)
console.log(`// 桌面 sessionKey 与手机一致：${v.desktopSessionKey === v.phoneSharedB64}`)

// 落盘一份 JSON，供 Kotlin 票据解析测试读取
const fs2 = await import('node:fs')
fs2.mkdirSync('app/build-evidence', { recursive: true })
fs2.writeFileSync('app/build-evidence/pairing-vector.json', JSON.stringify({
  qr: v.qr,
  ticket: v.ticket,
  fixedNonce: FIXED_NONCE,
  expectedCode: v.code,
  expectedSessionKey: v.phoneSharedB64,
  desktopPublicKey: v.desktopPubB64,
  phonePublicKey: v.phonePubB64,
}, null, 2))
console.log(`
已写出 app/build-evidence/pairing-vector.json`)
