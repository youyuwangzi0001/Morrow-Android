package studio.morrow.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `works.preview` 回包解析。
 *
 * 取件失败有多种原因（文件被删、超过 4 MiB、桌面回 not_found），但它们
 * 最终都必须落到「没有可用字节」——绝不能返回一个空/坏图让用户以为
 * 产物就是这样。
 */
class PreviewModelTest {

  private fun payload(base64: String, kind: String = "png", label: String = "封面"): Map<String, Any?> =
    mapOf(
      "preview" to mapOf(
        "workId" to "w1",
        "productId" to "d1",
        "label" to label,
        "kind" to kind,
        "mime" to "image/png",
        "bytes" to 3,
        "dataBase64" to base64,
      )
    )

  @Test
  fun 合法回包解码成可预览状态() {
    val state = PreviewPayload.readyOf(payload("AQID"))
    requireNotNull(state)
    assertEquals("封面", state.title)
    assertEquals(PreviewKind.Image, state.previewKind)
    assertTrue(state.bytes.contentEquals(byteArrayOf(1, 2, 3)))
  }

  @Test
  fun 缺字段或坏base64返回null() {
    assertNull(PreviewPayload.readyOf(emptyMap()))
    assertNull(PreviewPayload.readyOf(mapOf("preview" to "不是对象")))
    assertNull(PreviewPayload.readyOf(mapOf("preview" to mapOf("kind" to "png"))))
    assertNull(PreviewPayload.readyOf(payload("不是 base64")))
  }

  @Test
  fun 空文件与超限文件都不渲染() {
    assertNull(PreviewPayload.readyOf(payload("")))
    val tooLarge = java.util.Base64.getEncoder().encodeToString(ByteArray(PreviewPayload.MAX_BYTES + 1))
    assertNull(PreviewPayload.readyOf(payload(tooLarge)))
  }

  @Test
  fun 未知类型归入不可预览() {
    val state = PreviewPayload.readyOf(payload("AQID", kind = "comic"))
    requireNotNull(state)
    assertEquals(PreviewKind.None, state.previewKind)
  }
}