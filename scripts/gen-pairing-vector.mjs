#!/usr/bin/env node
/**
 * 生成「桌面真实票据」固定向量，供手机侧 DeskPairingVectorTest 断言。
 *
 * ## 为什么必须用这个脚本，而不是在 Kotlin 测试里自己拼票据
 *
 * 自己拼自己解，永远能过。真正的风险在两端**产出方式**的差异。
 *
 * ## 私钥编码：踩了三次才走对
 *
 * Java 的 `PKCS8EncodedKeySpec` 只接受 PKCS#8（DER）。而 Node 的
 * `ECDH.getPrivateKey()` 返回的是 **裸 32 字节标量**，既不是 SEC1 也不是 PKCS#8
 * （实测：长度 32，首字节是标量高位，不是 0x30）。所以：
 *
 * 1. 直接喂给 `PKCS8EncodedKeySpec` → `InvalidKeySpecException`
 * 2. 手工套 SEC1 头再用 `createPrivateKey` 转 → OpenSSL 报 asn1 too long
 *    （因为我把 32 字节标量当成 SEC1 DER 了）
 * 3. **正确做法**：手工拼 PKCS#8。结构极简且固定：
 *    ```
 *    30 41                          SEQUENCE, 0x41 字节
 *      02 01 00                     INTEGER 0（版本）
 *      30 13                        SEQUENCE, 0x13 字节
 *        06 07 2a8648ce3d0201       OID 1.2.840.10045.2.1 (ecPublicKey)
 *        06 08 2a8648ce3d030107     OID 1.2.840.10045.3.1.7 (prime256v1)
 *      04 20 <32 字节标量>          OCTET STRING
 *    ```
 *    总长固定 0x43 = 67 字节。不需要处理长度字节的三档编码 —— 长度是常量。
 *
 * 用法：
 *   node scripts/gen-pairing-vector.mjs
 */
import { createECDH, createHmac } from 'node:crypto'

const DESKTOP_PRIV_HEX =
  'c9a3f1b2d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f'
const PHONE_PRIV_B64 = '0a7dLI4cFwsL4hvNb4FL16HGvse9YKogVRkWCwe/VWE='
const NONCE = 'deadbeef'
const DEVICE_ID = 'desk-real-0001'
const RELAY_HINT = '192.168.1.10:7788'
const EXP = 2_000_000_000_000

const phone = createECDH('prime256v1')
phone.setPrivateKey(Buffer.from(PHONE_PRIV_B64, 'base64'))
const phonePubB64 = phone.getPublicKey('base64', 'uncompressed')

const desktop = createECDH('prime256v1')
desktop.setPrivateKey(Buffer.from(DESKTOP_PRIV_HEX, 'hex'))
const desktopPubB64 = desktop.getPublicKey('base64', 'uncompressed')

const ticket = {
  deviceId: DEVICE_ID,
  pubKey: phonePubB64,
  desktopPub: desktopPubB64,
  nonce: NONCE,
  exp: EXP,
  relayHint: RELAY_HINT,
}
const qr = Buffer.from(JSON.stringify(ticket), 'utf8').toString('base64url')

const shared = desktop.computeSecret(phone.getPublicKey())
const mac = createHmac('sha256', shared).update(`pair-code:${NONCE}`).digest()
const code = (mac.readUInt32BE(0) % 1_000_000).toString().padStart(6, '0')

/**
 * 裸 32 字节标量 → PKCS#8 DER。
 *
 * ## 关键：OCTET STRING 里装的不是标量，而是 ECPrivateKey 结构
 *
 * 我第一版把标量直接放进外层 OCTET STRING，结构上「看起来像」PKCS#8，
 * `xxd` 也对得上，但 OpenSSL / ConScrypt 都拒绝解析 ——
 * 因为 RFC 5958 规定 `privateKey` 字段装的是**内层 ECPrivateKey**
 * （RFC 5915）：
 *
 * ```
 * SEQUENCE {                        ← 外层 PKCS#8
 *   INTEGER 0
 *   SEQUENCE { OID ecPublicKey, OID prime256v1 }
 *   OCTET STRING {                  ← 里面还有一层！
 *     SEQUENCE {                    ← ECPrivateKey
 *       INTEGER 1                   ← 版本必须是 1（不是 0）
 *       OCTET STRING <32 字节标量>
 *     }
 *   }
 * }
 * ```
 *
 * 写错时的现象极具欺骗性：长度、首字节、ASN.1 标签全部「正常」，
 * 只有真正解析的那一刻才失败。这也是为什么必须用 `openssl` 独立验证，
 * 而不是只看自己算出来的字节数对不对。
 */
function scalarToPkcs8(scalar32) {
  if (scalar32.length !== 32) throw new Error(`期望 32 字节标量，实际 ${scalar32.length}`)

  // 标量最高位 ≥ 0x80 时要补前导零，否则会被当成负数
  const needsPad = scalar32[0] >= 0x80
  const octetContent = needsPad
    ? Buffer.concat([Buffer.from([0x00]), scalar32])
    : scalar32

  // 内层 ECPrivateKey：SEQUENCE { INTEGER 1, OCTET STRING <标量> }
  const ecPrivInner = Buffer.concat([
    Buffer.from('020101', 'hex'),                    // 版本 1
    Buffer.from([0x04, octetContent.length]),
    octetContent,
  ])
  const ecPriv = Buffer.concat([
    Buffer.from([0x30, ecPrivInner.length]),
    ecPrivInner,
  ])

  // 外层 PKCS#8
  const algorithm = Buffer.from('301306072a8648ce3d020106082a8648ce3d030107', 'hex')
  const version = Buffer.from('020100', 'hex')
  const inner = Buffer.concat([
    version,
    algorithm,
    Buffer.from([0x04, ecPriv.length]),   // OCTET STRING 装 ECPrivateKey
    ecPriv,
  ])

  if (inner.length >= 0x80) throw new Error(`内层长度 ${inner.length} 超出单字节范围`)
  return Buffer.concat([Buffer.from([0x30, inner.length]), inner])
}

const pkcs8 = scalarToPkcs8(phone.getPrivateKey())
console.log('// 标量首字节:', '0x' + phone.getPrivateKey()[0].toString(16), '(≥0x80 需补零)')

console.log('// 复制到 DeskPairingVectorTest：')
console.log(`realQr        = "${qr}"`)
console.log(`expectedCode  = "${code}"`)
console.log(`expectedSessionKey = "${shared.toString('base64')}"`)
console.log(`phonePrivPkcs8B64  = "${pkcs8.toString('base64')}"`)
console.log('')
console.log('// 自检：')
console.log('qr 带 padding:', qr.includes('='))
console.log('phone pub 字节数:', Buffer.from(phonePubB64, 'base64').length)
console.log('desktop pub 字节数:', Buffer.from(desktopPubB64, 'base64').length)
console.log('pkcs8 字节数:', pkcs8.length, '(应为 67)')
console.log('pkcs8 首字节 0x30:', pkcs8[0] === 0x30)