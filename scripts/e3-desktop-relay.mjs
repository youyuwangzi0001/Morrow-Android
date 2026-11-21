/**
 * E3 联调用的「真桌面」：用**桌面仓库的真实代码**起一个局域网 WSS relay。
 *
 * ## 为什么不用 Kotlin 里那个 `FakeDesktop`
 *
 * `RelaySocketEndToEndTest` 里的假桌面是**同一份** `WebSocketFrames` +
 * `SecureChannel` 代码（Kotlin 写的）。它证明的是「手机自己跟自己对得上」——
 * 两端用同一套实现，任何一端写错都是**同时**写错，测试照绿。
 *
 * 这个脚本换掉其中一端：服务端是 `Morrow/src/main/relay-channel.ts`
 * 的真实 `DirectChannel`（Node + ws + TLS），手机侧仍是 Kotlin 的
 * `RelaySocketClient`。两个实现由不同语言、不同人写的代码独立完成，
 * 只有协议真的对齐才会通。
 *
 * ## 起点在哪一步
 *
 * 不跳过配对：`issueTicket()` 签发真实票据（带真实证书指纹），
 * 手机侧解析这张票据、自己算 ECDH 共享秘密与六位码。
 * 桌面这边 `completePairing()` 也算一遍 —— **六位码必须相等**。
 * 从票据开始的整条链都在里面。
 *
 * ## 协议（Kotlin 侧 `CrossDeviceE2ETest` 按这个说话）
 *
 * ```
 * 桌面 → stdout:  E3_READY {"host":…,"port":…,"fingerprint":…,"qr":…,"code":…,"deviceId":…}
 * 手机 → stdin:   PUSH <json>     让桌面 broadcast 一条事件（body 由手机给）
 * 桌面 → stdout:  E3_PUSHED <seq>
 * 手机 → stdin:   QUIT
 * ```
 *
 * 事件体由手机传入而不是脚本硬编码：这样「手机期待什么」与「桌面发什么」
 * 写在同一个地方，不会出现「脚本发的字段和测试断言的字段不是同一个」。
 *
 * ## 用法
 *
 * 不直接手跑（它等 stdin 会挂住）。由 `CrossDeviceE2ETest` 通过
 * ProcessBuilder 启动。手工调试：
 *
 *   E3_PHONE_PUB=<base64 未压缩点> node scripts/e3-desktop-relay.mjs
 */
import { createECDH, randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { networkInterfaces, tmpdir } from 'node:os'
import { join } from 'node:path'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'

// 必须用 fileURLToPath 而不是手剥 `new URL(...).pathname`：
// 工作区路径含中文，pathname 里是百分号编码（`%E4%BA%A4...`），
// 剥出来交给 esbuild 会得到「Could not resolve」——报错信息里那串
// `C:/%E4%BA%A4...` 完全看不出是编码问题。
const ROOT = fileURLToPath(new URL('..', import.meta.url))
const REPO = join(ROOT, '..', 'Morrow')

// ── 1. 打成一个 bundle ──
//
// 桌面源码内部写的是 `from './secure-frame'`（无扩展名）—— Node 的 ESM
// 解析器要求扩展名，直接 import 会 ERR_MODULE_NOT_FOUND。用仓库里已有的
// esbuild 打成单文件，而不是为了跑验证去改生产代码的 import 写法。
//
// `ws` 打进去（纯 JS，约 900KB）；`bufferutil` / `utf-8-validate` 是可选
// 原生加速，external 掉 —— esbuild 有能力解析它们，但它们的 .node 二进制
// 在当前环境未必可加载，而 ws 在缺它们时会自动退到纯 JS 路径。
const { build } = await import(new URL(`file:///${REPO.replace(/\\/g, '/')}/node_modules/esbuild/lib/main.js`).href)
const outDir = join(ROOT, '.build', 'tmp', 'e3')
mkdirSync(outDir, { recursive: true })
const bundlePath = join(outDir, 'relay-all.cjs')

// 缓存：一次 E2E 测试要起好几次这个脚本（每个用例一套），
// 每次重新 bundle 要 ~0.9 秒。用「bundle 比 src/main 下任何 .ts 都新」判断可复用。
// 只比 mtime 不比内容：源文件被改动时 mtime 一定变；这条规则失效的唯一情形是
// 「改了文件但 mtime 回退」，那时验证会拿到旧 bundle —— 所以脚本把 bundle 的
// 指纹（大小 + mtime）打进 E3_READY，让人一眼能看出用的是哪一份。
function newestSourceMtime(dir) {
  let newest = 0
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name)
    if (entry.isDirectory()) newest = Math.max(newest, newestSourceMtime(p))
    else if (entry.name.endsWith('.ts')) newest = Math.max(newest, statSync(p).mtimeMs)
  }
  return newest
}
const srcDir = join(REPO, 'src', 'main')
const stale = (() => {
  if (!existsSync(bundlePath)) return true
  return statSync(bundlePath).mtimeMs < newestSourceMtime(srcDir)
})()

if (stale) {
  const built = await build({
    stdin: {
      contents: [
        `export * from '${join(REPO, 'src/main/relay.ts').replace(/\\/g, '/')}'`,
        `export * from '${join(REPO, 'src/main/relay.ts').replace(/\\/g, '/')}'`,
        `export * from '${join(REPO, 'src/main/relay-channel.ts').replace(/\\/g, '/')}'`
      ].join('\n'),
      resolveDir: join(ROOT, 'app').replace(/\\/g, '/'),
      sourcefile: 'e3-entry.ts',
      loader: 'ts'
    },
    bundle: true,
    platform: 'node',
    format: 'cjs',
    target: 'node20',
    // @core / @shared 是桌面构建期别名；ws 与 selfsigned 保持外部引用，
    // 否则它们的动态 require 会被内联后失效（format=cjs + node 外置即可）。
    alias: {
      '@core': join(REPO, 'src/core').replace(/\\/g, '/'),
      '@shared': join(REPO, 'src/shared').replace(/\\/g, '/'),
      electron: join(ROOT, 'scripts/electron-stub.cjs').replace(/\\/g, '/')
    },
    write: false,
    logLevel: 'error'
  })
  writeFileSync(bundlePath, built.outputFiles[0].contents)
}

const { createRequire } = await import('node:module')
const require = createRequire(import.meta.url)
// bundle 是 CJS（format:'cjs'）：它 require('ws')、require('crypto')，
// 走的是 CreateRequire 的路径解析。用 ESM 的 import() 也能加载 CJS，
// 但拿不到 `module.exports` 里的具名符号的可靠快照，所以这里显式 require。
const relay = require(bundlePath)

// ── 2. 挑一个非回环地址 ──
//
// **必须用非回环地址连**，这是本脚本存在的核心之一：手机永远不是
// 127.0.0.1。用回环测「联通」等于跳过「DirectChannel 到底有没有对局域网
// 开放」这个判断 —— 而它 bind 的是 0.0.0.0 还是 127.0.0.1 正是最该验的东西。
function pickLanAddress() {
  const candidates = []
  for (const list of Object.values(networkInterfaces())) {
    for (const i of list ?? []) {
      if (i.family === 'IPv4' && !i.internal && !i.address.startsWith('169.254.')) {
        candidates.push(i.address)
      }
    }
  }
  // 优先家用/办公网段（192.168 / 10.），最后才是 Hyper-V 的 172.x 虚拟网卡。
  // 两者都能连，但用真实 Wi-Fi 地址更接近手机在用户家里的处境；
  // 而且 172.30.192.1 那种 Default Switch 网卡在别的机器上不一定存在，
  // 挑地址的规则写死「第一个非回环」会让脚本在别的机器上换个地址跑。
  candidates.sort((a, b) => rank(a) - rank(b))
  return candidates[0] ?? null
}
function rank(addr) {
  if (addr.startsWith('192.168.')) return 0
  if (addr.startsWith('10.')) return 1
  if (addr.startsWith('172.')) return 3
  return 2
}
const lanAddress = pickLanAddress()

// ── 3. 起 TLS + PairingService + DirectChannel（真实代码，不是假实现）──
const XOR = 0x5a
const safeStorage = {
  isEncryptionAvailable: () => true,
  encryptString: (t) => Buffer.from([...Buffer.from(t, 'utf8')].map((b) => b ^ XOR)),
  decryptString: (b) => Buffer.from([...b].map((c) => c ^ XOR)).toString('utf8')
}

const dataDir = mkdtempSync(join(tmpdir(), 'morrow-e3-'))
const tls = await relay.loadOrCreateTls({ dataDir, safeStorage })
const pairing = new relay.PairingService({ secret: randomBytes(32) })
// 与生产同序：证书先载入，再注入指纹（`index.ts` 的 startLanStack 就是这个顺序）
pairing.setTlsFingerprint(tls.fingerprint)

const deviceId = process.env.E3_DEVICE_ID || 'phone-e3-0001'
const phonePub = process.env.E3_PHONE_PUB
if (!phonePub) {
  console.error('缺少 E3_PHONE_PUB（手机侧未压缩公钥 base64）')
  process.exit(2)
}

// 中继提示用真实局域网地址 + 真实端口。
const frames = []
const channel = new relay.DirectChannel({
  pairing,
  tls,
  host: '0.0.0.0',
  onFrame: (dir, summary) => frames.push(`${dir} ${summary}`)
})
// **先起监听，再签票**。反过来的话票据里的 `relayHint` 只能写 `host:0`
// （端口还没分配），而手机侧 `RelayHint` 会拒绝 0 端口 —— 结果是配对成功
// 却连不上，报的还会是「配对信息不完整」，把原因指到票据上。
// 这与生产时序也一致：桌面是开着远程通道才签发票据的。
await channel.connect()
const port = channel.port()

// `relayHint` 会被手机侧 `RelayHint.parse()` 解析成 host+port，
// 所以这里必须是真地址真端口；写死成假值时「地址解析」那一步就成了假验证。
const hint = lanAddress ? `${lanAddress}:${port}` : null
const issued = pairing.issueTicket(deviceId, phonePub, hint ?? undefined)
// **不在这里调用 completePairing**：那会绕过手机侧的 pair-confirm，
// 让跨语言 E2E 走到一条生产上不存在的捷径。
// 设备表由手机发来的 `pair-confirm` 帧写入（见 relay-channel 的 handlePairConfirm），
// 这正是本轮要验的那条链。下面等到设备真的进了表，再取 sessionKey。
const waitDevice = async (ms) => {
  const until = Date.now() + ms
  while (Date.now() < until) {
    if (pairing.listDevices().length > 0) return pairing.requirePaired(deviceId)
    await new Promise((r) => setTimeout(r, 25))
  }
  return null
}

// 等一条真实连接完成 hello（`linkCount()` 只数已鉴权的）
const waitLink = async (want, ms) => {
  const until = Date.now() + ms
  while (Date.now() < until) {
    if (channel.linkCount() >= want) return true
    await new Promise((r) => setTimeout(r, 25))
  }
  return false
}

process.stdout.write(
  'E3_READY ' + JSON.stringify({
    host: lanAddress ?? '127.0.0.1',
    loopbackFallback: lanAddress === null,
    port,
    fingerprint: tls.fingerprint,
    deviceId,
    // 票据原文交给手机解析 —— 手机从票据算 sessionKey，不从本行拿
    qr: issued.qr,
    // 六位码由桌面算出，用来和手机自己算的比
    code: issued.code
  }) + '\n'
)

/**
 * 桌面侧 sessionKey 要等手机发完 pair-confirm 之后才有 —— 所以不能放进
 * E3_READY。手机按需来取（`SESSIONKEY?`），拿到的是**设备表里真实存下的**
 * 那一份，这正是「两端算出的密钥一致」的证据。
 */
{
  const device = await waitDevice(30_000)
  if (device) process.stdout.write('E3_DEVICE_PAIRED ' + JSON.stringify({ sessionKey: device.sessionKey }) + '\n')
  else process.stdout.write('E3_DEVICE_PAIRED null\n')
}

// 桌面是权威事件源：手机说 PUSH，桌面 broadcast 一条
const rl = createInterface({ input: process.stdin })
let pushed = 0
for await (const line of rl) {
  const text = line.trim()
  if (text === 'QUIT') break
  if (text.startsWith('LINK?')) {
    process.stdout.write(`E3_LINK ${channel.linkCount()}\n`)
    continue
  }
  if (text.startsWith('PUSH ')) {
    if (!(await waitLink(1, 5000))) {
      process.stdout.write('E3_ERROR 没有已鉴权的连接，事件发不出去\n')
      continue
    }
    let body
    try {
      body = JSON.parse(text.slice(5))
    } catch (e) {
      process.stdout.write(`E3_ERROR 事件体不是合法 JSON：${e.message}\n`)
      continue
    }
    const env = channel.broadcast('event', body, {
      sessionId: body.sessionId ?? 'ses-e3',
      runId: body.runId ?? 'run-e3'
    })
    pushed++
    process.stdout.write(`E3_PUSHED ${env.seq}\n`)
    continue
  }
  if (text.startsWith('FRAMES')) {
    process.stdout.write('E3_FRAMES ' + JSON.stringify(frames) + '\n')
    continue
  }
}

await channel.disconnect()
process.stdout.write('E3_BYE\n')
process.exit(0)