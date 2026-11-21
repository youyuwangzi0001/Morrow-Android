/**
 * 给模拟器截图用的「真桌面」：起一个局域网 WSS DirectChannel，
 * 用桌面仓库真实代码签发票据，并在手机连上后广播一批真实形状的运行时事件，
 * 让工位 / 对话 / 协作 / 审批 / 作品页都有内容可拍。
 *
 * 与 e3-desktop-relay.mjs 的区别：
 * - 那个由 CrossDeviceE2ETest 驱动，只 PUSH 一条事件；
 * - 这里自己在连接建立后发事件，不需要手机侧回 PUSH。
 *
 * 协议形状按 MorrowAndroid 的 EventParser.kt / State.parseSnapshot 与
 * Morrow/src/main/relay-channel.ts 的 makeEnvelope 写，不是编的。
 *
 * 用法：
 *   E3_PHONE_PUB=<base64> E3_DEVICE_ID=<id> node scripts/capture-relay.mjs
 * 数据（code / qr / fingerprint / hint）写到 .build/tmp/capture-relay.json 顶层。
 */
import { randomBytes, createECDH } from 'node:crypto'
import { mkdirSync, mkdtempSync, writeFileSync, readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { networkInterfaces, tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createInterface } from 'node:readline'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const REPO = join(ROOT, '..', 'Morrow')
const OUT = join(ROOT, '.build', 'tmp')

const { build } = await import(new URL(`file:///${REPO.replace(/\\/g, '/')}/node_modules/esbuild/lib/main.js`).href)
mkdirSync(join(OUT, 'e3'), { recursive: true })
const bundlePath = join(OUT, 'e3', 'relay-all.cjs')

function newestSourceMtime(dir) {
  let newest = 0
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name)
    if (entry.isDirectory()) newest = Math.max(newest, newestSourceMtime(p))
    else if (entry.name.endsWith('.ts')) newest = Math.max(newest, statSync(p).mtimeMs)
  }
  return newest
}
const stale = (() => {
  if (!existsSync(bundlePath)) return true
  return statSync(bundlePath).mtimeMs < newestSourceMtime(join(REPO, 'src', 'main'))
})()
if (stale) {
  const built = await build({
    stdin: {
      contents: [
        `export * from '${join(REPO, 'src/main/relay.ts').replace(/\\/g, '/')}'`,
        `export * from '${join(REPO, 'src/main/relay-channel.ts').replace(/\\/g, '/')}'`
      ].join('\n'),
      resolveDir: join(ROOT, 'app').replace(/\\/g, '/'),
      sourcefile: 'capture-entry.ts',
      loader: 'ts'
    },
    bundle: true,
    platform: 'node',
    format: 'cjs',
    target: 'node20',
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
const relay = require(bundlePath)

function pickLanAddress() {
  const candidates = []
  for (const list of Object.values(networkInterfaces())) {
    for (const i of list ?? []) {
      if (i.family === 'IPv4' && !i.internal && !i.address.startsWith('169.254.')) candidates.push(i.address)
    }
  }
  const rank = (a) => (a.startsWith('192.168.') ? 0 : a.startsWith('10.') ? 1 : a.startsWith('172.') ? 3 : 2)
  candidates.sort((a, b) => rank(a) - rank(b))
  return candidates[0] ?? null
}

const XOR = 0x5a
const safeStorage = {
  isEncryptionAvailable: () => true,
  encryptString: (t) => Buffer.from([...Buffer.from(t, 'utf8')].map((b) => b ^ XOR)),
  decryptString: (b) => Buffer.from([...b].map((c) => c ^ XOR)).toString('utf8')
}

const lanAddress = pickLanAddress()
const dataDir = mkdtempSync(join(tmpdir(), 'morrow-capture-'))
const tls = await relay.loadOrCreateTls({ dataDir, safeStorage })
const pairing = new relay.PairingService({ secret: randomBytes(32) })
pairing.setTlsFingerprint(tls.fingerprint)

const deviceId = process.env.E3_DEVICE_ID || 'phone-capture-0001'
const phonePub = process.env.E3_PHONE_PUB
if (!phonePub) {
  console.error('缺少 E3_PHONE_PUB')
  process.exit(2)
}

// 脚本里的 `@@T-<ms>@@` 占位符要换成本次运行的真实时间戳：
// 写死 epoch 会让工位页显示「已用 8623h 44m」—— 那是 1970 年到现在。
// 数据只是形状真实，时间必须是「刚刚」，否则截图里的相对时间是假的。
const stampScript = (node) => {
  const base = Date.now()
  const walk = (value) => {
    if (typeof value === 'string') {
      const m = value.match(/^__T(\d+)__$/)
      return m ? base - Number(m[1]) : value
    }
    if (Array.isArray(value)) return value.map(walk)
    if (value && typeof value === 'object') {
      const out = {}
      for (const [k, v] of Object.entries(value)) out[k] = walk(v)
      return out
    }
    return value
  }
  return walk(node)
}
const script = stampScript(
  JSON.parse(readFileSync(process.env.CAPTURE_SCRIPT_FILE || join(OUT, 'capture-script.json'), 'utf8'))
)

const log = []
const channel = new relay.DirectChannel({
  pairing,
  tls,
  host: '0.0.0.0',
  // 首连必发快照（lastSeq === 0 时桌面会调一次）：用它把会话、审批、成员、
  // 作品、用量一次铺满，页面才有真实形状可拍。
  snapshot: () => script.snapshot ?? null,
  // 手机上行白名单里的 works.get / works.preview / snapshot.get 要有人接，
  // 否则作品页永远停在「读不到作品库」。这里按 relay-phone.ts 的
  // PhoneInvokeResult 形状回一个 ok 结果，内容是脚本给的。
  invoke: async (request) => {
    process.stdout.write(`INVOKE ${request.method}\n`)
    if (request.method === 'works.get') {
      return { ok: true, result: { works: script.works ?? [] } }
    }
    if (request.method === 'snapshot.get') {
      return { ok: true, result: { snapshot: script.snapshot ?? {} } }
    }
    if (request.method === 'ping') {
      return { ok: true, result: { at: Date.now() } }
    }
    if (request.method === 'message.send') {
      // 手机真发来了消息：把它原样记下来，并**按真实桌面的形状**回一个 runId，
      // 然后推这一轮的事件。这样验的是手机侧的整条往返：
      //   输入 → 上行 message.send → 收到 result → 事件下行 → 界面画出这一轮
      // 桌面侧「真的起了一轮 agent」由 tests/unit/phone-send-triggers-run.test.ts
      // 用真实 AgentService.send() 证明，两者合起来覆盖完整链路。
      process.stdout.write(
        `INVOKE message.send sessionId=${request.params.sessionId} text=${JSON.stringify(request.params.text)}\n`
      )
      const runId = `run-phone-${Date.now()}`
      const assistantMessageId = `asst-phone-${Date.now()}`
      const sessionId = String(request.params.sessionId)
      setTimeout(() => {
        const push = (body) => {
          const env = channel.broadcast('event', body, { sessionId, runId })
          process.stdout.write(`PUSHED seq=${env.seq} type=${body.type}\n`)
        }
        const now = Date.now()
        push({
          type: 'run_started',
          runId,
          sessionId,
          at: now,
          userMessage: { id: `user-${now}`, role: 'user', text: String(request.params.text), createdAt: now },
          assistantMessageId
        })
        push({ type: 'message_started', runId, sessionId, at: now + 5, messageId: assistantMessageId, role: 'assistant' })
        const chunks = [
          '收到。我先看了一下工作区：',
          '销售明细共 1 284 行。\n\n',
          '| 指标 | 值 |\n| :--- | ---: |\n| 订单数 | 1 284 |\n| 异常订单 | 7 |\n\n',
          '复盘页已重新生成。'
        ]
        let at = now + 10
        for (const textDelta of chunks) {
          push({ type: 'message_delta', runId, sessionId, at: (at += 30), messageId: assistantMessageId, textDelta })
        }
        push({
          type: 'message_completed',
          runId,
          sessionId,
          at: (at += 30),
          messageId: assistantMessageId,
          message: {
            id: assistantMessageId,
            role: 'assistant',
            text: chunks.join(''),
            blocks: [
              { type: 'text', text: chunks.join('') }
            ],
            createdAt: now
          }
        })
        push({ type: 'run_finished', runId, sessionId, at: (at += 30), reason: 'complete' })
      }, 400)
      return { ok: true, result: { runId, assistantMessageId } }
    }
    return { ok: false, error: { code: 'unsupported', message: `截图中继不处理 ${request.method}` } }
  },
  onFrame: (dir, summary) => {
    log.push(`${dir} ${summary}`)
    process.stdout.write(`FRAME ${dir} ${summary}\n`)
  }
})
await channel.connect()
const port = channel.port()
const hint = lanAddress ? `${lanAddress}:${port}` : null
const issued = pairing.issueTicket(deviceId, phonePub, hint ?? undefined)

// **不要在这里 completePairing**。
//
// 那个方法会把票据标记成 used（relay.ts:927，防重放的正确设计）。
// 以前截图脚本图省事、在同一秒核销了票据，于是二十秒后手机发
// `pair-confirm` 时桌面按规则回「票据已使用，重放被拒绝」——
// 屏幕上看起来像配对坏了，其实是脚本自己提前消费了票。
//
// 这里要的只是「桌面侧算出的 sessionKey」用于自检。它等于 ECDH(桌面私钥, 手机公钥)，
// 直接算一遍即可，不需要动票据状态。
const desktopSessionKey = pairing.ecdh.computeSecret(Buffer.from(phonePub, 'base64')).toString('base64')

const now = Date.now()

writeFileSync(
  join(OUT, 'capture-relay.json'),
  JSON.stringify(
    {
      host: lanAddress,
      port,
      fingerprint: tls.fingerprint,
      deviceId,
      ticket: issued.qr,
      code: issued.code,
      desktopSessionKey
    },
    null,
    2
  )
)
process.stdout.write('CAPTURE_READY ' + JSON.stringify({ host: lanAddress, port, code: issued.code }) + '\n')

const waitLink = async (want, ms) => {
  const until = Date.now() + ms
  while (Date.now() < until) {
    if (channel.linkCount() >= want) return true
    await new Promise((r) => setTimeout(r, 100))
  }
  return false
}

const linked = await waitLink(1, 180000)
process.stdout.write(`LINKED=${linked} links=${channel.linkCount()}\n`)

if (linked) {
  await new Promise((r) => setTimeout(r, 2500))
  const events = script.events || []
  for (const body of events) {
    const env = channel.broadcast('event', body, {
      sessionId: body.sessionId ?? 'ses-capture',
      runId: body.runId ?? 'run-capture'
    })
    process.stdout.write(`PUSHED seq=${env.seq} type=${body.type}\n`)
    await new Promise((r) => setTimeout(r, 350))
  }

  // 快照里的审批只有 120 秒有效期（Approvals.kt 的 TTL_MILLIS），
  // 而截图要一张张来。所以监听脚本文件的改动：文件一变就重新读一份、
  // 用「此刻」重算时间戳，再广播一条 snapshot。审批页因此可以随时刷新。
  const scriptFile = process.env.CAPTURE_SCRIPT_FILE || join(OUT, 'capture-script.json')
  let lastMtime = statSync(scriptFile).mtimeMs
  const hold = Number(process.env.CAPTURE_HOLD_MS || 600000)
  const until = Date.now() + hold
  while (Date.now() < until) {
    await new Promise((r) => setTimeout(r, 1000))
    let mtime
    try {
      mtime = statSync(scriptFile).mtimeMs
    } catch {
      continue
    }
    if (mtime === lastMtime) continue
    lastMtime = mtime
    let next
    try {
      next = stampScript(JSON.parse(readFileSync(scriptFile, 'utf8')))
    } catch (e) {
      process.stdout.write(`RESNAP_SKIP ${e.message}\n`)
      continue
    }
    if (!next.snapshot) continue
    const env = channel.broadcast('event', { type: 'snapshot', at: Date.now(), snapshot: next.snapshot }, {})
    process.stdout.write(`RESNAP seq=${env.seq}\n`)
  }
}

await channel.disconnect()
process.stdout.write('CAPTURE_BYE\n')
process.exit(0)
