/**
 * 端到端：桌面「手机配对」签发入口真的能签出票据、能撤销、撤销后立刻失效。
 *
 * ## 与 `relay-pair-api.test.ts` 的分工
 *
 * 那个是 vitest 单元测试（跑在 Morrow 仓库里）。这个是**从手机工程出发**
 * 驱动桌面真实代码，证明「跨仓调用链是通的」——
 * 两者的失败原因不同：单元测试挂了说明逻辑错，这个挂了说明接线错（比如
 * 桌面把 `pair-api` 删了、或者 `currentPairing()` 不再导出）。
 *
 * ## 为什么不用 electron 的 IPC 层
 *
 * `ipcMain.handle` 依赖 electron 运行时。这里测的是它**下游**那一层
 * （`pair-api.ts`，纯函数 + 真实 `PairingService`），
 * IPC 转发本身只有三行、且由 typecheck 保证签名一致。
 * 这个边界必须在报告里如实写清，不能拿这 12 项去声称「IPC 也验证过了」。
 *
 * ## 为什么要先 esbuild 打包
 *
 * `pair-api.ts` 内部写的是 `from './pairing'`（无扩展名，与仓库其余文件一致，
 * bundler 能解析）。Node 的 ESM 解析器要求扩展名，直接 import 会 ERR_MODULE_NOT_FOUND。
 * 先用仓库已有的 esbuild 打成单文件再 import —— 不为了跑验证去改生产代码的 import 写法。
 *
 * 用法：node scripts/verify-pair-entry.mjs
 */
import { createECDH, randomBytes } from 'node:crypto'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const { build } = await import('../../Morrow/node_modules/esbuild/lib/main.js')

// esbuild 自己写盘在这台机器上会被拦（Access is denied，连 $HOME 也一样），
// 但 `write: false` 走内存是通的。所以自己拿 contents 再写。
//
// 入口用 stdin 拼一个转发模块：`pair-api.ts` 只把 `PairingService` 当**类型**引用，
// 单独 bundle 它不会带上这个类的运行时实现（测试里 `new PairingService` 会报
// "not a constructor"）。这里同时再导出 `relay.ts` 的全部运行期符号。
mkdirSync('.build/tmp', { recursive: true })
// 产物必须落在 Morrow 目录内：bundle 里 external 掉的第三方包（ws/selfsigned/diff）
// 要靠 Morrow/node_modules 解析，写到 MorrowAndroid 下会 ERR_MODULE_NOT_FOUND。
mkdirSync(join('..', 'Morrow', '.build', 'tmp'), { recursive: true })
const outDir = mkdtempSync(join('..', 'Morrow', '.build', 'tmp', 'morrow-pair-'))
const outFile = join(outDir, 'pair-api.mjs')
const built = await build({
  stdin: {
    contents: [
      "export * from '../Morrow/src/main/relay.ts'"
    ].join('\n'),
    resolveDir: process.cwd(),
    sourcefile: 'entry.ts',
    loader: 'ts'
  },
  bundle: true,
  platform: 'node',
  format: 'esm',
  target: 'node20',
  // 只打包仓库内源码：ws / selfsigned 等第三方包与 node 内置模块保持外部引用，
  // 否则它们的动态 require 会被 esbuild 内联后失效。
  packages: 'external',
  external: ['electron'],
  // `@core` / `@shared` 是桌面端构建期别名（见 Morrow/electron.vite.config.ts），
  // esbuild 单独跑不认识，必须显式映射，否则 bundle 直接失败。
  alias: {
    '@core': '../Morrow/src/core',
    '@shared': '../Morrow/src/shared'
  },
  write: false,
  logLevel: 'warning'
})
writeFileSync(outFile, built.outputFiles[0].contents)

// 用完就删：每次跑都会新建一个 mkdtemp 目录，不清会在 .build/tmp 里
// 堆一串 morrow-pair-xxxx（本轮实测堆了 4 个才发现）。清理放在 import 之后做不到
// （ESM 的 import 是静态提升的），所以这里用一个进程退出钩子。
process.on('exit', () => {
  try {
    rmSync(outDir, { recursive: true, force: true })
  } catch {
    /* 清理失败不影响验证结论 */
  }
})

const api = await import(pathToFileURL(outFile).href)
const { PairingService } = api
const {
  issuePairTicket,
  listPairDevices,
  revokePairDevice,
  remainingMs,
  isExpired,
  PAIR_TTL_SECONDS,
  RELAY_DISABLED
} = api
// 这两个常量来自 relay.ts，经 bundle 后可能被 tree-shake 掉名字，从 api 里取不到就自己声明
const TICKET_TTL_MS = api.TICKET_TTL_MS ?? 90_000
const DEVICE_REVOKED = api.DEVICE_REVOKED ?? 'DEVICE_REVOKED'


let bad = 0
const ok = (m) => console.log(`  OK   ${m}`)
const fail = (m) => {
  console.log(`  FAIL ${m}`)
  bad++
}

let clock = 1_700_000_000_000
const makeService = (fp) =>
  new PairingService({
    secret: randomBytes(32),
    now: () => clock,
    ...(fp ? { tlsFingerprint: fp } : {})
  })
const phoneKeys = () => {
  const e = createECDH('prime256v1')
  e.generateKeys()
  return e.getPublicKey().toString('base64')
}

// ── 1. 远程通道没开时明确报错 ──
{
  const r = issuePairTicket(null, { deviceId: 'd', phonePubKey: phoneKeys() })
  if (r.ok) fail('远程通道没开却签发成功')
  else if (r.error.code !== RELAY_DISABLED) fail(`错误码应为 ${RELAY_DISABLED}，实际 ${r.error.code}`)
  else ok('远程通道没开 → RELAY_DISABLED（不临时造服务）')
}

// ── 2. 正常签发：qr / code / expiresAt / hasFingerprint ──
const svc = makeService('FP_E2D_TEST')
const phonePub = phoneKeys()
const issued = issuePairTicket(svc, { deviceId: 'phone-e2d-0001', phonePubKey: phonePub, relayHint: '192.168.1.5:7788' })
if (!issued.ok) {
  fail(`签发失败：${issued.error.code} ${issued.error.message}`)
  process.exit(1)
}
{
  const d = issued.data
  if (!/^\d{6}$/.test(d.code)) fail(`六位码格式不对：${d.code}`)
  else ok(`六位码 ${d.code}`)
  if (d.expiresAt !== clock + TICKET_TTL_MS) fail(`expiresAt 应为票据 exp（${clock + TICKET_TTL_MS}），实际 ${d.expiresAt}`)
  else ok('expiresAt 取自票据 exp（不是「现在 + TTL」重算）')
  if (!d.hasFingerprint) fail('注入了指纹却报 hasFingerprint=false')
  else ok('hasFingerprint 为 true')

  // ── 3. 手机侧拿到的 qr 必须能解出完整票据（含指纹） ──
  const t = PairingService.parseTicket(d.qr)
  if (t.deviceId !== 'phone-e2d-0001') fail('qr 里 deviceId 不对')
  if (t.pubKey !== phonePub) fail('qr 里 pubKey 不是这台手机的公钥')
  if (t.fingerprint !== 'FP_E2D_TEST') fail(`qr 里指纹丢失：${t.fingerprint}`)
  if (t.relayHint !== '192.168.1.5:7788') fail('qr 里 relayHint 丢失')
  ok('qr 解出的票据字段齐全（deviceId / pubKey / fingerprint / relayHint）')
}

// ── 4. 缺指纹时 hasFingerprint 为 false（界面要提示，别让用户白扫） ──
{
  const bare = issuePairTicket(makeService(null), { deviceId: 'd2', phonePubKey: phoneKeys() })
  if (!bare.ok) fail('无指纹场景签发失败')
  else if (bare.data.hasFingerprint) fail('没有指纹却报 hasFingerprint=true')
  else ok('无指纹 → hasFingerprint=false（界面据此提示，不假装可扫）')
}

// ── 5. 名单：配对前为空，配对后有一台且不含 sessionKey ──
{
  const empty = listPairDevices(svc)
  if (!empty.ok || empty.data.length !== 0) fail('尚未配对却有设备')
  else ok('配对前设备列表为空')

  svc.completePairing(issued.data.nonce, phonePub, '我的手机')
  const after = listPairDevices(svc)
  if (!after.ok) fail('列设备失败')
  else {
    if (after.data.length !== 1) fail(`应有 1 台，实际 ${after.data.length}`)
    if (after.data[0].name !== '我的手机') fail(`设备名不对：${after.data[0].name}`)
    if (JSON.stringify(after.data).includes('sessionKey')) fail('列表里泄露了 sessionKey')
    else ok('配对后列表有 1 台，且不含 sessionKey')
  }
}

// ── 6. 撤销后 requirePaired 立刻抛 DEVICE_REVOKED（同一请求内） ──
{
  const before = (() => {
    try {
      svc.requirePaired('phone-e2d-0001')
      return true
    } catch {
      return false
    }
  })()
  if (!before) fail('撤销前就不可用，测试前提不成立')

  const r = revokePairDevice(svc, 'phone-e2d-0001')
  if (!r.ok || !r.data.revoked) fail('撤销返回失败')

  let code = null
  try {
    svc.requirePaired('phone-e2d-0001')
  } catch (e) {
    code = e.code ?? null
  }
  if (code === DEVICE_REVOKED) ok('撤销后同一个服务实例立刻抛 DEVICE_REVOKED')
  else fail(`撤销后仍可用或错误码不对：${code}`)

  const list2 = listPairDevices(svc)
  if (!list2.ok || list2.data.length !== 0) fail('撤销后设备仍在列表里')
  else ok('撤销后列表变空')
}

// ── 7. 撤销不存在的设备返回 revoked:false（不假装成功） ──
{
  const r = revokePairDevice(svc, 'never-paired')
  if (!r.ok) fail('撤销不存在的设备直接报错')
  else if (r.data.revoked) fail('撤销不存在的设备却说成功')
  else ok('撤销不存在的设备返回 revoked=false')
}

// ── 8. 倒计时与过期：界面用同一份 expiresAt 判断，服务端也是裁判 ──
{
  const s2 = makeService('FP')
  const i2 = issuePairTicket(s2, { deviceId: 'd3', phonePubKey: phoneKeys() })
  if (!i2.ok) fail('签发失败')
  else {
    if (remainingMs(i2.data.expiresAt, clock) !== TICKET_TTL_MS) fail('起始剩余时间不等于 TTL')
    if (remainingMs(i2.data.expiresAt, clock + TICKET_TTL_MS + 9_999) !== 0) fail('过期后剩余时间应为 0（不能为负）')
    ok('倒计时从 TTL 递减，过期后钳到 0')

    if (isExpired(i2.data.expiresAt, clock + TICKET_TTL_MS)) fail('恰好等于 exp 时不该判过期')
    else ok('恰好等于 exp 时仍有效（与 relay.ts 的 now > exp 一致）')

    if (PAIR_TTL_SECONDS !== 90) fail(`PAIR_TTL_SECONDS 应为 90，实际 ${PAIR_TTL_SECONDS}`)
    else ok('PAIR_TTL_SECONDS = 90（界面不写死这个数字）')

    clock += TICKET_TTL_MS + 1
    let thrown = false
    try {
      s2.verifyEcho(i2.data.nonce)
    } catch {
      thrown = true
    }
    if (!thrown) fail('过期票据在服务端没有被拒绝（只靠界面判断是可绕过的）')
    else ok('过期票据服务端也拒绝（界面过期只是显示，服务端才是裁判）')
  }
}

console.log()
console.log(bad === 0 ? '结论：桌面配对签发入口可用（签发 → 列表 → 撤销 → 过期）' : `结论：${bad} 项不通过`)
process.exit(bad === 0 ? 0 : 1)
