package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**截图脚本真实签发的票据**回归配对解析。
 *
 * 这条测试来自一次真实故障：模拟器里粘贴票据、点「下一步」，
 * 界面报「这个二维码不是 Morrow 的配对码 / 二维码内容不是 JSON 对象」，
 * 而同一串票据在桌面侧是 `PairingService.issueTicket()` 正常产出的。
 * 解析器与签发器对不上，属于最坏的一类缺陷 —— 用户永远配不上，
 * 而错误提示还在怪票据。
 */
class TicketParseRegressionTest {

  /** capture-relay.mjs 实际签发的票据（deviceId=phone-c02843d6c109）。 */
  private val realTicket =
    "eyJkZXZpY2VJZCI6InBob25lLWMwMjg0M2Q2YzEwOSIsInB1YktleSI6IkJIS1dCQ1JaZUtzYkY3K0I5cVlVaTBsMzN4LzZ6YVY0bVVRNFZIMTA5cVg3THJ3Ti9nS1YrZ1U1Zis0bjRQVnJmREp3UWtxNG1zSEt5d2MxU2oxdXlLYz0iLCJkZXNrdG9wUHViIjoiQkdZc3dTWDdBaDBuMmZGWmN5Z0RlUE4wQnJWU01KNlhHbS9wUXUvTnJDeXZFZi9HSUdKdm1yaUs1Sk1qL0I1VnJuZFpnRVk2MklkdTlWZEY4b3NmbE9zPSIsIm5vbmNlIjoiOTlhMmI0MDljMGU1OWFiNTNlZTdiZjhjYjI0ZGViMTYiLCJleHAiOjE3OTU0MzI2OTQxMzYsInJlbGF5SGludCI6IjE5Mi4xNjguMS41OjgzMzciLCJmaW5nZXJwcmludCI6IkZvQTA3Z2ozRDhQZ0d1cTdOeF90WHhVYlZ5SlhoRDlZT01aMGF0Z1BPOVUifQ"

  @Test
  fun 真实签发的票据必须能被解析() {
    val ticket = EcdhPairing.parseTicket(realTicket, 1_795_432_694_136L - 1000)
    assertEquals("phone-c02843d6c109", ticket.deviceId)
    assertTrue("desktopPub 必须解析出来", ticket.desktopPub.isNotBlank())
    assertTrue("relayHint 必须解析出来", ticket.relayHint == "192.168.1.5:8337")
    assertTrue("fingerprint 必须解析出来", !ticket.desktopFingerprint.isNullOrBlank())
    assertEquals(1_795_432_694_136L, ticket.exp)
  }

  @Test
  fun 解析出的公钥与桌面签发的完全相同() {
    val ticket = EcdhPairing.parseTicket(realTicket, 1_795_432_694_136L - 1000)
    assertEquals(
      "BHKWBCRZeKsbF7+B9qYUi0l33x/6zaV4mUQ4VH109qX7LrwN/gKV+gU5f+4n4PVrfDJwQkq4msHKywc1Sj1uyKc=",
      ticket.pubKey,
    )
    assertEquals("192.168.1.5:8337", ticket.relayHint)
  }

  @Test
  fun 票据里的base64填充与斜杠不被破坏() {
    // pubKey 里既有 `+` 也有 `/` 还有 `=`：任何一处被 URL 解码规则吃掉，
    // 都会让 JSON 解析失败或公钥对不上 —— 实际故障就是这个形态。
    assertTrue(realTicket.all { it != '=' && it != '+' && it != '/' })
    val ticket = EcdhPairing.parseTicket(realTicket, 1_795_432_694_136L - 1000)
    assertTrue("解码后的公钥应保留 +", ticket.pubKey.contains('+'))
    assertTrue("解码后的公钥应保留 /", ticket.pubKey.contains('/'))
    assertTrue("解码后的公钥应保留 =", ticket.pubKey.endsWith("="))
  }
  @Test
  fun 过期的票据报过期而不是格式错误() {
    // 90 秒 TTL 是最常被踩到的失败；此前「过期」会被字段校验与格式校验
    // 抢先报成「不是 Morrow 的配对码」，用户于是去检查自己复制的内容。
    val expiredAt = 1_795_432_694_136L + 1
    val e = try {
      EcdhPairing.parseTicket(realTicket, expiredAt)
      null
    } catch (ex: EcdhPairing.PairingException) {
      ex
    }
    requireNotNull(e) { "过期票据不该解析成功" }
    assertEquals(EcdhPairing.TICKET_EXPIRED, e.code)
  }

  @Test
  fun 缺exp的票据仍然报格式问题() {
    // 「先判过期」不能把「缺 exp」也说成过期 —— 那是两件事
    val json = """{"deviceId":"d","pubKey":"p","desktopPub":"q","nonce":"n"}"""
    val qr = java.util.Base64.getUrlEncoder().withoutPadding()
      .encodeToString(json.toByteArray(Charsets.UTF_8))
    val e = try {
      EcdhPairing.parseTicket(qr, 1L)
      null
    } catch (ex: EcdhPairing.PairingException) {
      ex
    }
    requireNotNull(e) { "缺 exp 的票据不该解析成功" }
    assertEquals(EcdhPairing.TICKET_INVALID, e.code)
  }
}
