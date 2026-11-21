package studio.morrow.android

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class ApprovalDecision(val wire: String) {
  Once("once"),
  Deny("deny");

  companion object {
    fun fromWire(raw: String?): ApprovalDecision =
      if (raw == Once.wire) Once else Deny
  }
}

object ApprovalProjection {

  fun fromState(
    state: MorrowUiState,
    nowMillis: Long,
    monotonicNow: Long = nowMillis,
    /** 每条审批「手机第一次看到它」时的单调时钟读数；缺省回退为当前读数 */
    firstSeenAt: (String) -> Long = { monotonicNow },
  ): List<ApprovalItem> =
    state.pendingApprovals.values
      .sortedBy { if (it.requestedAtMillis > 0L) it.requestedAtMillis else Long.MAX_VALUE }
      .map { fromUi(it, state, nowMillis, monotonicNow, firstSeenAt(it.requestId)) }

  fun fromUi(
    approval: UiApproval,
    state: MorrowUiState,
    nowMillis: Long,
    monotonicNow: Long = nowMillis,
    seenAtMillis: Long = monotonicNow,
  ): ApprovalItem {
    val sessionTitle = state.sessions[approval.sessionId]?.title?.takeIf { it.isNotBlank() }
      ?: "当前会话"
    val summary = approval.summary.trim()
    // 比在手机上编一个不存在的路径/命令更安全。
    val target = summary.lineSequence().firstOrNull()?.take(160) ?: ""
    return ApprovalItem(
      id = approval.requestId,
      toolName = approval.toolName,
      title = approval.title.ifBlank { approval.toolName.ifBlank { "需要授权" } },
      target = target,
      paramSummary = summary.take(400),
      category = ToolCategory.fromWire(approval.category),
      risk = ToolRisk.fromWire(approval.risk),
      danger = DangerTools.isDanger(approval.toolName),
      memberLabel = null,
      sessionTitle = sessionTitle,
      createdAtMillis = if (approval.requestedAtMillis > 0L) approval.requestedAtMillis else nowMillis,
      allowAlwaysOnDesktop = approval.allowAlways ?: false,
      elapsedMillis = (monotonicNow - seenAtMillis).coerceAtLeast(0L),
    )
  }
}

object AndroidBiometricGate {

  /** 系统能力状态 → 闸门能力。拆成纯函数才能逐值测。 */
  fun capabilityOf(status: Int): BiometricGate.Capability = when (status) {
    BiometricManager.BIOMETRIC_SUCCESS -> BiometricGate.Capability.Ready
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricGate.Capability.NoHardware
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricGate.Capability.NotEnrolled
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricGate.Capability.Unavailable
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricGate.Capability.PolicyBlocked
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricGate.Capability.PolicyBlocked
    else -> BiometricGate.Capability.Unavailable
  }

  fun capability(context: Context): BiometricGate.Capability = try {
    capabilityOf(
      BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    )
  } catch (_: Throwable) {
    // 界面会降级为仅拒绝，不会把审批误发出去。
    BiometricGate.Capability.Unavailable
  }

  fun authenticate(
    activity: FragmentActivity,
    title: String,
    onResult: (BiometricGate.AuthResult) -> Unit,
  ) {
    var delivered = false
    fun deliver(result: BiometricGate.AuthResult) {
      if (delivered) return
      delivered = true
      onResult(result)
    }

    val prompt = BiometricPrompt(
      activity,
      ContextCompat.getMainExecutor(activity),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          deliver(BiometricGate.AuthResult.Succeeded)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          deliver(errorOf(errorCode, errString.toString()))
        }
      },
    )

    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle("验证后才会把「允许」发给桌面")
      .setNegativeButtonText("取消")
      .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
      .build()

    runCatching { prompt.authenticate(info) }.onFailure {
      deliver(BiometricGate.AuthResult.Error(-1, it.message ?: "无法启动生物识别"))
    }
  }

  /** 系统错误码 → 闸门结果。取消类都是 Canceled（最终按拒绝处理）。 */
  fun errorOf(code: Int, message: String): BiometricGate.AuthResult = when (code) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED -> BiometricGate.AuthResult.Cancelled

    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricGate.AuthResult.Lockout

    else -> BiometricGate.AuthResult.Error(code, message)
  }
}

data class ApprovalItem(
  val id: String,
  /** 工具名，如 `run_command`、`mcp_call` */
  val toolName: String,
  /** 桌面给的一句话标题，如「运行命令」 */
  val title: String,
  /** 影响范围：目标路径 / 命令 / 窗口标题等，已由 Relay 脱敏 */
  val target: String,
  /** 参数摘要（非原文 JSON），一行以内 */
  val paramSummary: String,
  val category: ToolCategory,
  val risk: ToolRisk,
  val danger: Boolean,
  /** 来自哪个协作成员。null = 主对话（非协作任务） */
  val memberLabel: String?,
  /** 属于哪个会话，用来在卡上标注出处 */
  val sessionTitle: String,
  val createdAtMillis: Long,
  val allowAlwaysOnDesktop: Boolean,
  val elapsedMillis: Long = 0L,
) {
  fun canAllow(
    nowMillis: Long,
    capability: BiometricGate.Capability = BiometricGate.Capability.Ready,
  ): Boolean = !danger &&
    !expiredMonotonic() &&
    !isExpired(nowMillis) &&
    BiometricGate.gate(capability) is BiometricGate.Verdict.Proceed

  fun isExpired(nowMillis: Long): Boolean = nowMillis - createdAtMillis >= TTL_MILLIS

  /** 单调时钟口径的过期：从手机看到它开始算满 [TTL_MILLIS] */
  fun expiredMonotonic(): Boolean = elapsedMillis >= TTL_MILLIS

  /** 单调时钟口径的剩余秒数（向上取整，最小 0） */
  fun remainingSecondsMonotonic(): Int {
    val left = TTL_MILLIS - elapsedMillis
    if (left <= 0) return 0
    return ((left + 999) / 1000).toInt()
  }

  /** 剩余可操作秒数（向上取整，最小 0） */
  fun remainingSeconds(nowMillis: Long): Int {
    val left = TTL_MILLIS - (nowMillis - createdAtMillis)
    if (left <= 0) return 0
    return ((left + 999) / 1000).toInt()
  }

  companion object {
    const val TTL_MILLIS = 120_000L
  }
}

enum class ToolCategory(val label: String) {
  Read("只读"),
  Write("写入"),
  Exec("执行"),
  Meta("元信息"),
  ;

  companion object {
    /** 桌面 `ToolCategory` 是 `'read' | 'write' | 'exec' | 'meta'`（types.ts:322） */
    fun fromWire(raw: String): ToolCategory = when (raw) {
      "read" -> Read
      "write" -> Write
      "exec" -> Exec
      "meta" -> Meta
      else -> Exec
    }
  }
}

enum class ToolRisk(val label: String) {
  Low("低风险"),
  Medium("中风险"),
  High("高风险"),
  ;

  companion object {
    /** 桌面 `ToolRisk` 是 `'low' | 'medium' | 'high'`（types.ts:324） */
    fun fromWire(raw: String): ToolRisk = when (raw) {
      "low" -> Low
      "medium" -> Medium
      "high" -> High
      else -> High
    }
  }
}

object DangerTools {
  /** 外部副作用：发请求、调 MCP、操作浏览器 */
  val external: Set<String> = setOf(
    "http_request",
    "mcp_call",
    "mcp_list",
    "mcp_resources",
    "mcp_prompt",
    "browser_click",
    "browser_type",
    "browser_press",
    "browser_tab",
  )

  /** UI 自动化：这些会在桌面上真的动鼠标键盘、装软件、下载文件 */
  val automation: Set<String> = setOf(
    "computer_click",
    "computer_type",
    "computer_key",
    "computer_launch_app",
    "computer_install_package",
    "computer_download_file",
    "computer_run_installer",
    "computer_invoke",
    "computer_set_value",
    "computer_drag",
  )

  /** 并集，等于桌面 `ALWAYS_APPROVAL_TOOLS` */
  val all: Set<String> = external + automation

  fun isDanger(toolName: String): Boolean = toolName in all

  /** 给用户看的分类，用于卡上的说明文案 */
  fun reasonOf(toolName: String): String? = when (toolName) {
    in external -> "外部副作用动作：会在你的电脑之外产生效果，手机上无法核对结果"
    in automation -> "桌面自动化动作：会在你的电脑上真的移动鼠标、安装软件或下载文件"
    else -> null
  }
}

/** 拒绝原因 —— 会作为 deny 的 reason 一并回给桌面 */
enum class DenyReason(val wire: String, val label: String) {
  /** 用户主动点了拒绝 */
  User("user", "已拒绝"),

  /** 危险动作，手机端不提供允许 */
  Danger("danger", "危险动作，需在桌面处理"),

  /** 超过时限自动失效 */
  Expired("expired", "已超时失效"),

  /** 批量「全部拒绝」 */
  DismissAll("dismiss_all", "已全部拒绝"),
  ;
}

/** 审批队列的一条变更结果，供 UI 与日志使用 */
sealed interface ApprovalOutcome {
  /** 已下发决策 */
  data class Sent(val id: String, val allow: Boolean, val reason: DenyReason?) : ApprovalOutcome

  /** 这条已过期，没有下发（而是在本地判失效） */
  data class Failed(val id: String, val why: String) : ApprovalOutcome

  /** 这条审批不在队列里（已被处理 / 从未存在） */
  data class Missing(val id: String) : ApprovalOutcome
}

class ApprovalQueue(
  /** 真正下发决策的地方（接 B 轨 relay 通道）。返回 false 表示下发失败 */
  private val sink: (approvalId: String, allow: Boolean, reason: DenyReason?) -> Boolean,
  /** 当前时刻。注入而非直接读系统时间，测试才能确定性地跨过 2 分钟 */
  private val clock: () -> Long = System::currentTimeMillis,
) {

  /** 手机端能做的两种决策。没有 `Always` —— 这是蓝图要求，不是疏漏 */
  enum class Decision { AllowOnce, Deny }

  private val items = LinkedHashMap<String, ApprovalItem>()

  /** 已处理过的 id（进程内），用于挡重复点击与通知残留 */
  private val handled = HashSet<String>()

  /** 队列快照，按「最该先处理的」在前 */
  val pending: List<ApprovalItem>
    get() {
      val now = clock()
      return items.values
        .filter { !it.isExpired(now) }
        .sortedWith(
          compareByDescending<ApprovalItem> { it.danger }
            // 其次按风险等级
            .thenByDescending { it.risk.ordinal }
            .thenBy { it.createdAtMillis }
        )
    }

  val size: Int get() = pending.size

  fun get(id: String): ApprovalItem? {
    val it = items[id] ?: return null
    return if (it.isExpired(clock())) null else it
  }

  fun upsert(item: ApprovalItem): Boolean {
    synchronized(items) {
      if (item.id in handled) return false
      val existing = items[item.id]
      if (existing != null) {
        // 同一条审批重复推送：保留最早的产生时间。
        // 等于把「时效」这个约束悄悄取消了。
        items[item.id] = item.copy(createdAtMillis = existing.createdAtMillis)
        return false
      }
      items[item.id] = item
      return true
    }
  }

  /** 桌面撤回了一条审批（agent 自己放弃了 / 别的设备已处理） */
  fun revoke(id: String): Boolean {
    synchronized(items) { return items.remove(id) != null }
  }

  fun sweepExpired(): List<String> {
    val now = clock()
    synchronized(items) {
      val gone = items.values.filter { it.isExpired(now) }.map { it.id }
      gone.forEach { items.remove(it) }
      return gone
    }
  }

  fun decide(id: String, decision: Decision): ApprovalOutcome {
    val item = synchronized(items) { items[id] } ?: return ApprovalOutcome.Missing(id)

    if (item.isExpired(clock())) {
      synchronized(items) { items.remove(id) }
      return ApprovalOutcome.Failed(id, "已超时失效，未下发决策")
    }

    if (decision == Decision.AllowOnce && item.danger) {
      return ApprovalOutcome.Failed(id, "危险动作只能在桌面处理")
    }

    synchronized(items) {
      if (!handled.add(id)) return ApprovalOutcome.Missing(id)
    }

    val allow = decision == Decision.AllowOnce
    val reason = if (allow) null else DenyReason.User
    val ok = sink(id, allow, reason)

    if (!ok) {
      synchronized(items) { handled.remove(id) }
      return ApprovalOutcome.Failed(id, "下发失败，请重试")
    }

    synchronized(items) { items.remove(id) }
    return ApprovalOutcome.Sent(id, allow, reason)
  }

  fun denyAll(): List<ApprovalOutcome> {
    val ids = synchronized(items) { items.keys.toList() }
    return ids.map { decide(it, Decision.Deny) }
  }

  /** 清空（切换桌面 / 断开连接时）。不下发任何决策 —— 队列只是本地镜像 */
  fun clear() {
    synchronized(items) {
      items.clear()
      handled.clear()
    }
  }

  fun resetForTest() = clear()

  val knownIds: Set<String> get() = synchronized(items) { items.keys.toSet() }

  /** 通知栏链路的下发：仍然只能 once / deny */
  fun dispatchFromNotification(approvalId: String, allow: Boolean): Boolean {
    val outcome = decide(approvalId, if (allow) Decision.AllowOnce else Decision.Deny)
    return outcome is ApprovalOutcome.Sent
  }
}

object BiometricGate {

  /** 闸门判定结果 */
  sealed interface Verdict {
    /** 可以继续下发（允许或拒绝都算「已决定」） */
    data class Proceed(val allow: Boolean) : Verdict

    /** 不能允许，只能拒绝 —— 并给出原因，界面要显示出来 */
    data class DenyOnly(val why: NoBiometricReason) : Verdict
  }

  enum class NoBiometricReason(val message: String) {
    /** 设备没有可用的生物识别硬件 */
    NoHardware("这台设备没有指纹/面容硬件，只能在桌面批准"),

    /** 有硬件但用户没录入 */
    NotEnrolled("还没有录入指纹或面容，只能在桌面批准"),

    /** 硬件暂时不可用（例如被其他应用占用、刚重启需先解锁） */
    Unavailable("生物识别暂时不可用，请先解锁设备后重试"),

    /** 系统策略禁止（例如设备管理员禁用） */
    PolicyBlocked("系统策略禁止生物识别，只能在桌面批准"),
  }

  fun gate(capability: Capability): Verdict = when (capability) {
    Capability.Ready -> Verdict.Proceed(allow = true)
    Capability.NoHardware -> Verdict.DenyOnly(NoBiometricReason.NoHardware)
    Capability.NotEnrolled -> Verdict.DenyOnly(NoBiometricReason.NotEnrolled)
    Capability.Unavailable -> Verdict.DenyOnly(NoBiometricReason.Unavailable)
    Capability.PolicyBlocked -> Verdict.DenyOnly(NoBiometricReason.PolicyBlocked)
  }

  fun resolve(result: AuthResult): Decision {
    return when (result) {
      AuthResult.Succeeded -> Decision.Allow
      AuthResult.Cancelled -> Decision.Deny
      is AuthResult.Error -> Decision.Deny
      AuthResult.Lockout -> Decision.Deny
    }
  }

  /** 设备能力。由 [BiometricPromptLauncher] 用真实 API 探测后填入 */
  enum class Capability {
    Ready,
    NoHardware,
    NotEnrolled,
    Unavailable,
    PolicyBlocked,
  }

  /** 认证结果 */
  sealed interface AuthResult {
    data object Succeeded : AuthResult
    data object Cancelled : AuthResult
    data object Lockout : AuthResult
    data class Error(val code: Int, val message: String) : AuthResult
  }

  /** 认证通过后交给下发的最终决策（与 [ApprovalQueue.Decision] 同构） */
  enum class Decision { Allow, Deny }
}

/** 审批页的 testTag 命名空间 */
object ApprovalTags {
  const val SCREEN = "approval-screen"
  const val LIST = "approval-list"
  const val EMPTY = "approval-empty"
  const val DENY_ALL = "approval-deny-all"
  const val ACTION_ERROR = "approval-action-error"
  const val CRYPTO_BANNER = "approval-crypto-banner"
  fun card(id: String) = "approval-card-$id"
  fun allow(id: String) = "approval-allow-$id"
  fun deny(id: String) = "approval-deny-$id"
  fun dangerNote(id: String) = "approval-danger-note-$id"
  fun countdown(id: String) = "approval-countdown-$id"

  /** 一条审批的按钮容器 —— 用来断言按钮的纵向顺序 */
  fun buttons(id: String) = "approval-buttons-$id"
}

@Composable
fun ApprovalCard(
  item: ApprovalItem,
  nowMillis: Long,
  onAllow: () -> Unit,
  onDeny: () -> Unit,
  modifier: Modifier = Modifier,
  /** 本机生物识别能力：DenyOnly 时「允许」必须是禁用态 */
  capability: BiometricGate.Capability = BiometricGate.Capability.Ready,
) {
  val p = MorrowTheme.palette
  val canAllow = item.canAllow(nowMillis, capability)
  val expired = item.isExpired(nowMillis)
  val danger = item.danger

  val accent = when {
    danger -> p.rust
    item.risk == ToolRisk.High -> p.rust
    item.risk == ToolRisk.Medium -> p.gold
    else -> p.hint
  }
  val bg = if (danger) p.rustDim else p.surface

  Column(
    modifier
      .fillMaxWidth()
      .testTag(ApprovalTags.card(item.id))
      .clip(RoundedCornerShape(MorrowRadius.card))
      .background(bg)
      .border(
        width = if (danger) 1.dp else 0.5.dp,
        color = if (danger) p.rust.copy(alpha = 0.55f) else p.lineStrong,
        shape = RoundedCornerShape(MorrowRadius.card),
      )
      .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s4),
    verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = item.memberLabel ?: item.sessionTitle,
        fontSize = MorrowType.sm, fontFamily = FontFamily.Monospace,
        color = p.text2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(MorrowSpacing.s2))
      RiskChip(item.risk, accent)
      Spacer(Modifier.width(MorrowSpacing.s1))
      Text(
        text = if (expired) "已失效" else "${item.remainingSeconds(nowMillis)}s",
        fontSize = MorrowType.code, fontFamily = FontFamily.Monospace,
        color = if (expired) p.rust else p.text3,
        modifier = Modifier.testTag(ApprovalTags.countdown(item.id)),
      )
    }

    // ── 四段信息 ──
    // ① 工具：做什么
    FieldRow("工具", item.toolName, mono = true, valueColor = p.text)
    // ② 目标：影响什么
    FieldRow("目标", item.target, mono = true, valueColor = p.text, maxLines = 2)
    // ③ 参数摘要：具体怎么做
    //
    // ── M-21：两段同源时不要重复 ──
    //
    //
    // 又不让用户读到两遍同样的字。
    val duplicated = item.paramSummary == item.target
    if (duplicated) {
      FieldRow(
        "参数",
        "与目标同一段（桌面只下发了一段摘要）",
        mono = false,
        valueColor = p.hint,
        maxLines = 2,
      )
    } else {
      FieldRow("参数", item.paramSummary, mono = true, valueColor = p.text2, maxLines = 2)
    }
    FieldRow("类别", "${item.category.label} · ${item.risk.label}", valueColor = accent)

    if (danger) {
      Text(
        text = DangerTools.reasonOf(item.toolName) ?: "危险动作，只能在桌面处理",
        fontSize = MorrowType.sm,
        color = p.rust,
        modifier = Modifier.testTag(ApprovalTags.dangerNote(item.id)),
      )
    }

    // ── 按钮：上拒绝 / 下允许 ──
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .testTag(ApprovalTags.buttons(item.id)),
      verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s1),
    ) {
      ApprovalButton(
        text = "拒绝",
        enabled = !expired,
        primary = false,
        accent = p.rust,
        testTag = ApprovalTags.deny(item.id),
        onClick = onDeny,
      )
      ApprovalButton(
        text = if (danger) "允许（不可用）" else "允许这一次",
        enabled = canAllow,
        primary = true,
        accent = p.patina,
        testTag = ApprovalTags.allow(item.id),
        onClick = onAllow,
      )
    }

    if (danger) {
      Text(
        text = "这类操作会在你的电脑上真的执行，手机上无法核对结果，因此只能拒绝。",
        fontSize = MorrowType.xs,
        color = p.text3,
      )
    } else if (!expired) {
      Text(
        text = "允许需指纹确认 · 手机不提供「始终允许」",
        fontSize = MorrowType.xs,
        color = p.text3,
      )
    }
  }
}

@Composable
private fun FieldRow(
  label: String,
  value: String,
  mono: Boolean = false,
  valueColor: Color,
  maxLines: Int = 1,
) {
  val p = MorrowTheme.palette
  Row(verticalAlignment = Alignment.Top) {
    Text(
      text = label,
      fontSize = MorrowType.xs,
      color = p.text3,
      modifier = Modifier.width(34.dp),
    )
    Text(
      text = value,
      fontSize = if (mono) MorrowType.code else MorrowType.ui,
      fontFamily = if (mono) FontFamily.Monospace else null,
      color = valueColor,
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun RiskChip(risk: ToolRisk, accent: Color) {
  Text(
    text = risk.label,
    fontSize = MorrowType.xs,
    color = accent,
    modifier = Modifier
      .clip(RoundedCornerShape(MorrowRadius.xs))
      .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(MorrowRadius.xs))
      .padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

@Composable
private fun ApprovalButton(
  text: String,
  enabled: Boolean,
  primary: Boolean,
  accent: Color,
  testTag: String,
  onClick: () -> Unit,
) {
  val p = MorrowTheme.palette
  val shape = RoundedCornerShape(MorrowRadius.control)
  val bg = when {
    !enabled -> p.surfaceHover
    primary -> accent.copy(alpha = 0.14f)
    else -> Color.Transparent
  }
  val borderColor = when {
    !enabled -> p.lineStrong
    else -> accent.copy(alpha = 0.55f)
  }
  val textColor = when {
    !enabled -> p.text3
    else -> accent
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(40.dp)
      .testTag(testTag)
      .clip(shape)
      .background(bg)
      .border(if (primary) 1.dp else 0.5.dp, borderColor, shape)
      .semantics {
        contentDescription = if (enabled) text else "$text，不可用"
        if (!enabled) disabled()
      }
      .then(
        if (enabled) {
          Modifier.androidClickablePublic(onClick)
        } else {
          Modifier
        }
      ),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = text,
      fontSize = MorrowType.ui, fontWeight = FontWeight.Medium,
      color = textColor,
    )
  }
}

@Composable
fun BiometricUnavailableBanner(why: BiometricGate.NoBiometricReason) {
  val p = MorrowTheme.palette
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .testTag(ApprovalTags.CRYPTO_BANNER)
      .clip(RoundedCornerShape(MorrowRadius.card))
      .background(p.rustDim)
      .border(1.dp, p.rust.copy(alpha = 0.4f), RoundedCornerShape(MorrowRadius.card))
      .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s2),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = why.message,
      fontSize = MorrowType.sm,
      color = p.rust,
    )
  }
}

@Composable
fun ApprovalScreen(
  items: List<ApprovalItem>,
  nowMillis: Long,
  onAllow: (String) -> Unit,
  onDeny: (String) -> Unit,
  onDenyAll: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  /** 生物识别不可用时的原因。null = 可用 */
  biometricUnavailable: BiometricGate.NoBiometricReason? = null,
  biometricCapability: BiometricGate.Capability = BiometricGate.Capability.Ready,
  actionError: String? = null,
  focusId: String? = null,
) {
  val p = MorrowTheme.palette
  val listState = rememberLazyListState()
  LaunchedEffect(focusId, items.size) {
    val index = items.indexOfFirst { it.id == focusId }
    if (index >= 0) listState.animateScrollToItem(index)
  }

  Column(modifier.fillMaxSize().testTag(ApprovalTags.SCREEN)) {
    // ── 顶栏 ──
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = MorrowSpacing.s4)
        .padding(top = MorrowSpacing.s4, bottom = MorrowSpacing.s2),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BackButton(onClick = onBack, tag = "approval-back")
      Spacer(Modifier.width(MorrowSpacing.s2))
      Column(Modifier.weight(1f)) {
        Text("审批", fontSize = MorrowType.title, color = p.text)
        Text(
          text = if (items.isEmpty()) "没有待处理" else "${items.size} 条待处理",
          fontSize = MorrowType.xs,
          color = p.text3,
        )
      }
      // 显示出来只会让用户怀疑自己漏了什么。
      if (items.isNotEmpty()) {
        Text(
          text = "全部拒绝",
          fontSize = MorrowType.sm,
          color = p.rust,
          modifier = Modifier
            .clip(RoundedCornerShape(MorrowRadius.control))
            .border(0.5.dp, p.rust.copy(alpha = 0.5f), RoundedCornerShape(MorrowRadius.control))
            .testTag(ApprovalTags.DENY_ALL)
            .then(Modifier.androidClickablePublic(onDenyAll))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }

    if (actionError != null) {
      Text(
        text = actionError,
        fontSize = MorrowType.xs,
        color = p.rust,
        modifier = Modifier
          .padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s1)
          .fillMaxWidth()
          .testTag(ApprovalTags.ACTION_ERROR),
      )
    }

    if (biometricUnavailable != null) {
      Box(Modifier.padding(horizontal = MorrowSpacing.s4, vertical = MorrowSpacing.s1)) {
        BiometricUnavailableBanner(biometricUnavailable)
      }
    }

    // ── 列表 ──
    if (items.isEmpty()) {
      EmptyApprovals(modifier = Modifier.weight(1f))
    } else {
      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .testTag(ApprovalTags.LIST),
        state = listState,
        contentPadding = PaddingValues(
          start = MorrowSpacing.s4,
          end = MorrowSpacing.s4,
          top = MorrowSpacing.s2,
          bottom = MorrowSpacing.s6,
        ),
        verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s2),
      ) {
        items(items, key = { it.id }) { item ->
          ApprovalCard(
            item = item,
            nowMillis = nowMillis,
            onAllow = { onAllow(item.id) },
            onDeny = { onDeny(item.id) },
            capability = biometricCapability,
          )
        }
      }
    }
  }
}

@Composable
private fun EmptyApprovals(modifier: Modifier = Modifier) {
  val p = MorrowTheme.palette
  Box(
    modifier = modifier
      .fillMaxWidth()
      .testTag(ApprovalTags.EMPTY),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(MorrowSpacing.s1),
      modifier = Modifier.padding(horizontal = MorrowSpacing.s6),
    ) {
      Text(
        text = "没有待你批准的请求",
        fontSize = MorrowType.ui,
        color = p.text2,
      )
      Text(
        text = "桌面上的 agent 需要授权时会出现在这里；\n超过 2 分钟未处理的请求会自动失效。",
        fontSize = MorrowType.xs,
        color = p.text3,
      )
    }
  }
}

@Composable
fun BackButton(
  onClick: () -> Unit,
  tag: String,
  modifier: Modifier = Modifier,
  /** 热区边长。默认 44dp —— Material 的最小触控目标，也是各页顶栏放得下的尺寸。 */
  size: Dp = 44.dp,
) {
  Box(
    modifier = modifier
      .size(size)
      .clip(RoundedCornerShape(MorrowRadius.control))
      .testTag(tag)
      .semantics { contentDescription = "返回" }
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text("←", color = MorrowTheme.text2, fontSize = MorrowType.title)
  }
}

internal fun Modifier.androidClickablePublic(onClick: () -> Unit): Modifier =
  this.then(Modifier.clickable(onClick = onClick))
