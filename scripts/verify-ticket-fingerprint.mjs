/**
 * 端到端证据：桌面真实 `PairingService` 签发的票据里**真的带指纹**，
 * 且这个指纹就是 `certFingerprint()` 对桌面当前 TLS 证书算出的那串。
 *
 * 与 `gen-tls-fingerprint-vector.mjs` 的分工：
 * - 那个脚本证明「两端对同一份 DER 算法一致」（固定向量）；
 * - 这个脚本证明「桌面签发票据时确实把那个值放进了 qr」（接线正确）。
 *
 * 两者都必要：算法一致但没接线，手机上一样拿不到指纹。
 *
 * 用法：node --experimental-strip-types scripts/verify-ticket-fingerprint.mjs
 */
import { createECDH, randomBytes } from 'node:crypto'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const { PairingService } = await import('../../Morrow/src/main/relay.ts')
const { loadOrCreateTls, certFingerprint } = await import('../../Morrow/src/main/relay.ts')

const XOR = 0x5a
const fakeSafeStorage = {
  isEncryptionAvailable: () => true,
  encryptString: (t) => Buffer.from([...Buffer.from(t, 'utf8')].map((b) => b ^ XOR)),
  decryptString: (b) => Buffer.from([...b].map((c) => c ^ XOR)).toString('utf8')
}

let bad = 0
const fail = (msg) => {
  console.log(`  FAIL ${msg}`)
  bad++
}
const ok = (msg) => console.log(`  OK   ${msg}`)

// ── 1. 起一套真实的桌面 TLS 材料 ──
const dataDir = mkdtempSync(join(tmpdir(), 'morrow-e2c-'))
const tls = await loadOrCreateTls({ dataDir, safeStorage: fakeSafeStorage })
ok(`TLS 证书已就绪，指纹 ${tls.fingerprint}（${tls.fingerprint.length} 字符）`)

// ── 2. 按生产时序构造 PairingService 并注入指纹 ──
const pairing = new PairingService({ secret: randomBytes(32) })
if (pairing.hasTlsFingerprint()) fail('构造后不该有指纹（注入前）')
else ok('构造后还没有指纹（与生产的时序一致：证书晚于服务载入）')

pairing.setTlsFingerprint(tls.fingerprint)
if (!pairing.hasTlsFingerprint()) fail('setTlsFingerprint 之后应有指纹')
else ok('注入后 hasTlsFingerprint() 为 true')

// ── 3. 用一台假手机签发真实票据 ──
const phone = createECDH('prime256v1')
phone.generateKeys()
const phonePub = phone.getPublicKey().toString('base64')
const issued = pairing.issueTicket('phone-e2c-0001', phonePub, '192.168.1.10:7788')

if (issued.ticket.fingerprint !== tls.fingerprint) {
  fail(`ticket.fingerprint 与 tls.fingerprint 不一致：${issued.ticket.fingerprint}`)
} else {
  ok('ticket.fingerprint 等于桌面证书指纹')
}

// ── 4. 从 qr 里读回来（手机只拿得到 qr） ──
const round = PairingService.parseTicket(issued.qr)
if (round.fingerprint !== tls.fingerprint) {
  fail(`qr 解出的 fingerprint 不一致：${round.fingerprint}`)
} else {
  ok('qr 解出的 fingerprint 与桌面证书指纹一致（这是手机唯一能拿到它的路径）')
}

// ── 5. 指纹确实是这张证书的 sha256（不是别的东西） ──
const recomputed = certFingerprint(tls.cert)
if (recomputed !== round.fingerprint) {
  fail(`证书重算不一致：${recomputed}`)
} else {
  ok('票据里的指纹 == sha256(证书 DER) 的 base64url')
}

// ── 6. 它是 base64url 而不是标准 base64 ──
const hasUrlChar = round.fingerprint.includes('-') || round.fingerprint.includes('_')
const hasStdChar = round.fingerprint.includes('+') || round.fingerprint.includes('/')
ok(`本次指纹${hasUrlChar ? '含' : '不含'} - 或 _（随机，不作断言）；${hasStdChar ? '含' : '不含'} + 或 /`)
if (hasStdChar) fail('base64url 不应含 + 或 /')

// ── 7. 生产接线：index.ts 里必须真的调用了它，且顺序正确 ──
//
// 这一步为什么必须查源码：`setTlsFingerprint` 本身有测试不代表生产会调它。
// E1b 已经踩过一次「组件有测试、生产没接线，测试全绿」。
// 上面 1–6 项证明的是「注入之后票据会带指纹」这个契约，
// 而**注入这一动作发生在 `startLanStack` 里** —— 那一段依赖 electron，
// 在 Node 脚本里 import 不起来，只能对源码做结构检查。
//
// 顺序也要查：必须在 `loadOrCreateTls` 之后（否则还拿不到指纹），
// 且在 `new DirectChannel` 之前（否则第一个连接可能已经握手完了）。
{
  const { readFileSync } = await import('node:fs')
  const src = readFileSync(new URL('../../Morrow/src/main/relay.ts', import.meta.url), 'utf8')
  // 不绑变量名：注入点写成 `service.setTlsFingerprint(...)` 还是
  // `pairing.setTlsFingerprint(...)` 是重构自由的范畴。要守的是
  // 「loadOrCreateTls 之后、new DirectChannel 之前，把 tls.fingerprint 注进去了」。
  // （E2d 把局部变量重命名成 service，旧写法就误报成「生产不会注入」——
  //   结构检查绑住具体标识符，重构一次就假红，久了就会被当成噪音删掉。）
  const injectRe = /\.setTlsFingerprint\(tls\.fingerprint\)/
  const atInject = src.search(injectRe)
  const atTls = src.indexOf('await loadOrCreateTls(')
  const atDirect = src.indexOf('new DirectChannel(')
  if (atInject < 0) {
    fail('index.ts 里没有任何 *.setTlsFingerprint(tls.fingerprint) —— 生产不会注入指纹')
  } else if (atTls < 0 || atInject < atTls) {
    fail('setTlsFingerprint 在 loadOrCreateTls 之前，那时还没有指纹')
  } else if (atDirect < 0 || atInject > atDirect) {
    fail('setTlsFingerprint 在 new DirectChannel 之后，第一条连接可能已经握手完')
  } else {
    ok('生产接线在 startLanStack 里，顺序为 loadOrCreateTls → setTlsFingerprint → new DirectChannel')
  }
  // 降级路径也必须清掉指纹（否则票据会带上一轮的值，手机记一个连不上的指纹）
  if (!/\.setTlsFingerprint\(null\)/.test(src)) {
    fail('index.ts 的 catch 分支没有清掉指纹（局域网起不来时会用旧指纹签发）')
  } else {
    ok('局域网失败时清掉指纹（不拿旧值签发）')
  }
}

// ── 8. 没有指纹时不编造（局域网起不来时的降级路径） ──
pairing.setTlsFingerprint(null)
const bare = pairing.issueTicket('phone-e2c-0002', phonePub, '192.168.1.10:7788')
if ('fingerprint' in bare.ticket) fail('清掉指纹后票据仍带 fingerprint 字段')
else ok('清掉指纹后票据不带该字段（手机侧会明确拒绝，而不是存空串）')

console.log()
console.log(bad === 0 ? '结论：桌面票据携带真实证书指纹，接线正确' : `结论：${bad} 项不通过`)
process.exit(bad === 0 ? 0 : 1)
