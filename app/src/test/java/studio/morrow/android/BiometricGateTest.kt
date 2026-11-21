package studio.morrow.android

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批的生物识别闸门。
 *
 * 两条安全不变量：
 * 1. 设备没有可用生物识别时，**不能**出现可点的「允许」；
 * 2. 取消/锁定/异常一律按拒绝处理 —— 「保持待批」会让用户以为还没决定。
 */
class BiometricGateTest {

  @Test
  fun 只有就绪才允许继续() {
    assertEquals(
      BiometricGate.Verdict.Proceed(allow = true),
      BiometricGate.gate(BiometricGate.Capability.Ready),
    )
    val blocked = mapOf(
      BiometricGate.Capability.NoHardware to BiometricGate.NoBiometricReason.NoHardware,
      BiometricGate.Capability.NotEnrolled to BiometricGate.NoBiometricReason.NotEnrolled,
      BiometricGate.Capability.Unavailable to BiometricGate.NoBiometricReason.Unavailable,
      BiometricGate.Capability.PolicyBlocked to BiometricGate.NoBiometricReason.PolicyBlocked,
    )
    blocked.forEach { (capability, reason) ->
      assertEquals(
        BiometricGate.Verdict.DenyOnly(reason),
        BiometricGate.gate(capability),
      )
    }
  }

  @Test
  fun 认证结果只有成功才算允许() {
    assertEquals(BiometricGate.Decision.Allow, BiometricGate.resolve(BiometricGate.AuthResult.Succeeded))
    assertEquals(BiometricGate.Decision.Deny, BiometricGate.resolve(BiometricGate.AuthResult.Cancelled))
    assertEquals(BiometricGate.Decision.Deny, BiometricGate.resolve(BiometricGate.AuthResult.Lockout))
    assertEquals(
      BiometricGate.Decision.Deny,
      BiometricGate.resolve(BiometricGate.AuthResult.Error(7, "vendor")),
    )
  }

  @Test
  fun 系统能力码逐一映射() {
    assertEquals(BiometricGate.Capability.Ready, AndroidBiometricGate.capabilityOf(BiometricManager.BIOMETRIC_SUCCESS))
    assertEquals(BiometricGate.Capability.NoHardware, AndroidBiometricGate.capabilityOf(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
    assertEquals(BiometricGate.Capability.NotEnrolled, AndroidBiometricGate.capabilityOf(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
    assertEquals(BiometricGate.Capability.Unavailable, AndroidBiometricGate.capabilityOf(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
    assertEquals(BiometricGate.Capability.PolicyBlocked, AndroidBiometricGate.capabilityOf(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
    assertEquals("未知状态按不可用处理", BiometricGate.Capability.Unavailable, AndroidBiometricGate.capabilityOf(9999))
  }

  @Test
  fun 取消与锁定都不是失败错误() {
    assertTrue(
      AndroidBiometricGate.errorOf(androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON, "取消") is BiometricGate.AuthResult.Cancelled,
    )
    assertTrue(
      AndroidBiometricGate.errorOf(androidx.biometric.BiometricPrompt.ERROR_LOCKOUT, "锁定") is BiometricGate.AuthResult.Lockout,
    )
    assertTrue(
      AndroidBiometricGate.errorOf(androidx.biometric.BiometricPrompt.ERROR_UNABLE_TO_PROCESS, "坏了") is BiometricGate.AuthResult.Error,
    )
  }
}