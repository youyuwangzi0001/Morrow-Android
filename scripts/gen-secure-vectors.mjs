/**
 * 生成跨语言测试向量：直接调用**桌面真实的 SecureChannel 类**加密固定明文，
 * 输出手机端要硬编码的期望值。
 *
 * ## 为什么必须 import 桌面源码，而不是「照抄参数」
 *
 * 第一版这个脚本是手抄参数的（自己写 hkdfSync / createCipheriv）。
 * 那样测出来的结论是「手机实现与我的抄本一致」，**桌面源码本身从未被验证**。
 * 若 `relay.ts` 哪天改了 HKDF 的 info 串或 AAD 拼接，这个脚本仍会
 * 输出旧向量、手机测试继续绿，真机才失败——这正是本批要防的那类缺陷。
 *
 * 现在改成 `import { SecureChannel } from '../../Morrow/src/main/relay.ts'`，
 * 桌面改一个字符，重跑本脚本得到的向量就会变，手机侧向量测试立刻红。
 *
 * 用法：
 *   node --experimental-strip-types scripts/gen-secure-vectors.mjs
 *   node --experimental-strip-types scripts/gen-secure-vectors.mjs --check   # 只比对不输出
 *
 * `--check` 用于 verify.sh：确认代码里硬编码的向量与桌面当前实现仍然一致。
 */
import { SecureChannel } from '../../Morrow/src/main/relay.ts'

// ── 固定输入（手机端 RelayCryptoTests.kt 硬编码同一份）──
// 选这种可读性极差的固定值是有意的：任何偏差（比如误用 17 字节 salt）
// 都会立刻算错，而不会碰巧「差不多对」。
const sessionKeyBase64 = Buffer.alloc(32, 7).toString('base64')   // 32 字节全 0x07
const connectionSaltBase64 = Buffer.alloc(16, 9).toString('base64') // 16 字节全 0x09
const deviceId = 'dev-test-0001'

const cases = [
  { direction: 'desktop-to-phone', counter: 0, plaintext: '你好，Morrow' },
  { direction: 'desktop-to-phone', counter: 1, plaintext: '' },
  { direction: 'desktop-to-phone', counter: 2, plaintext: '{"v":1,"kind":"event","seq":1}' },
  { direction: 'phone-to-desktop', counter: 0, plaintext: '{"cmd":"stop"}' },
]

/** 用桌面真实实现算出全部向量 */
function buildVectors() {
  // 按方向分组：同一方向的多条帧走**同一条连接**，计数才会 0,1,2 递增；
  // 每个方向一条独立连接，P2D/D2P 才都从 0 开始（双向密钥分离的验证需要这点）。
  const out = []
  for (const direction of ['desktop-to-phone', 'phone-to-desktop']) {
    const group = cases.filter((c) => c.direction === direction)
    if (group.length === 0) continue
    const ch = new SecureChannel({ sessionKey: sessionKeyBase64, connectionSalt: connectionSaltBase64, deviceId })
    group.forEach((c, i) => {
      const frame = ch.seal(c.direction, c.plaintext)
      if (frame.n !== String(i)) {
        throw new Error(`计数器不符：第 ${i} 条，桌面给 ${frame.n}（同方向必须连续递增）`)
      }
      out.push({ ...c, ciphertextBase64Url: frame.c })
    })
  }
  return out
}

/** 由桌面实现派生出的双向密钥 hex（手机测试里用于「密钥派生是否一致」断言） */
function derivedKeys() {
  const ch = new SecureChannel({ sessionKey: sessionKeyBase64, connectionSalt: connectionSaltBase64, deviceId })
  // SecureChannel 不暴露密钥，用「seal 同一明文分别用两个方向」无法反推密钥；
  // 改用桌面的 hkdf 再算一遍 —— 但那是手抄。
  // 所以这里改为：由桌面 seal 出的密文反推不可行，改为暴露 keys 的只读快照。
  const keys = /** @type {any} */ (ch).keys
  return {
    p2d: Buffer.from(keys['phone-to-desktop']).toString('hex'),
    d2p: Buffer.from(keys['desktop-to-phone']).toString('hex'),
  }
}

const vectors = buildVectors()
const keys = derivedKeys()
const checkMode = process.argv.includes('--check')

if (checkMode) {
  // 只做一致性核对：从 Kotlin 测试文件里抽出硬编码值，与桌面当前实现比对。
  // 这一步把「向量是手抄的」变成「向量由桌面源码确证」。
  const fs = await import('node:fs')
  const kt = fs.readFileSync('app/src/test/java/studio/morrow/android/RelayCryptoTests.kt', 'utf8')
  let bad = 0

  for (const v of vectors) {
    if (!kt.includes(v.ciphertextBase64Url)) {
      console.log(`  FAIL ${v.direction} counter=${v.counter} 的密文与桌面当前实现不一致`)
      console.log(`       桌面现在给: ${v.ciphertextBase64Url}`)
      bad++
    } else {
      console.log(`  OK   ${v.direction} counter=${v.counter} 密文一致`)
    }
  }

  for (const [name, hex, ktName] of [
    ['p2d 密钥', keys.p2d, 'expectedP2dKeyHex'],
    ['d2p 密钥', keys.d2p, 'expectedD2pKeyHex'],
  ]) {
    if (!kt.includes(hex)) {
      console.log(`  FAIL ${name} 与桌面当前实现不一致（${ktName}）`)
      console.log(`       桌面现在给: ${hex}`)
      bad++
    } else {
      console.log(`  OK   ${name} 一致`)
    }
  }

  // 固定输入也必须一致，否则「同样的值算出不同结果」会被误判为算法不同
  if (!kt.includes(sessionKeyBase64) || !kt.includes(connectionSaltBase64)) {
    console.log('  FAIL Kotlin 测试里的固定输入与脚本不一致')
    bad++
  }

  const total = vectors.length + 3
  console.log(`\n桌面源码 vs 手机硬编码向量：${total - bad} 一致 / ${bad} 不一致`)
  process.exit(bad === 0 ? 0 : 1)
}

console.log('// ── 由 scripts/gen-secure-vectors.mjs 生成，勿手改 ──')
console.log('// 值来自桌面真实 SecureChannel（import 而非手抄），桌面改动会自动反映到这里')
console.log(`sessionKeyBase64 = "${sessionKeyBase64}"`)
console.log(`connectionSaltBase64 = "${connectionSaltBase64}"`)
console.log(`deviceId = "${deviceId}"`)
console.log()
console.log('// 派生出的双向密钥（用于排错时比对）')
console.log(`p2dKeyHex = "${keys.p2d}"`)
console.log(`d2pKeyHex = "${keys.d2p}"`)
console.log()
console.log('vectors = listOf(')
for (const v of vectors) {
  console.log('  Vector(')
  console.log(`    direction = "${v.direction}",`)
  console.log(`    counter = ${v.counter},`)
  console.log(`    plaintext = ${JSON.stringify(v.plaintext)},`)
  console.log(`    ciphertextBase64Url = "${v.ciphertextBase64Url}",`)
  console.log('  ),')
}
console.log(')')