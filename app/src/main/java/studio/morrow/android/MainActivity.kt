package studio.morrow.android

import android.app.Application
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import studio.morrow.android.ApprovalDecisionInbox
import studio.morrow.android.FcmReceiver
import studio.morrow.android.ForegroundSyncService
import studio.morrow.android.ForegroundSyncState
import studio.morrow.android.MorrowDeepLink
import studio.morrow.android.MorrowDestination
import studio.morrow.android.NotifyChannels
import studio.morrow.android.PendingPull
import studio.morrow.android.RelayNotifications
import studio.morrow.android.RelaySyncTracking
import studio.morrow.android.AndroidKeystoreProvider
import studio.morrow.android.EcdhPairing
import studio.morrow.android.PairRequest
import studio.morrow.android.PairingFlow
import studio.morrow.android.RelayConnectionController
import studio.morrow.android.RelayConnectionState
import studio.morrow.android.RelayCredentials
import studio.morrow.android.RelayEventSink
import studio.morrow.android.RelayHint
import studio.morrow.android.RelayProtocol
import studio.morrow.android.RelaySocketClient
import studio.morrow.android.SecretKeyProvider
import studio.morrow.android.IgnoreBridges
import studio.morrow.android.EventStreamViewModel

// ───── MainActivity.kt ─────
/**
 * 唯一 Activity：无边框（edge-to-edge）全幅渲染。
 * 1. `enableEdgeToEdge()` 必须在 `setContent` 之前调用，否则首帧会先按系统栏 inset 布局再跳动；
 * 2. 系统栏必须显式给**透明**样式。`enableEdgeToEdge()` 的默认参数在深色模式下会给
 */
class MainActivity : FragmentActivity() {
  /**
   * 事件流与 relay 接收器。
   */
  private val streamScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
  private val stream by lazy { EventStreamViewModel(streamScope) }

  /** relay 事件 → 系统通知。生产里唯一会主动打扰用户的路径。 */
  private val relayNotifications by lazy { RelayNotifications(this) }

  /** relay 事件 → 前台服务跟盯启停（切后台后仍有常驻进度）。 */
  private val relaySyncTracking by lazy {
    RelaySyncTracking(
      onStart = { id, step -> ForegroundSyncService.start(this, id, step, 0) },
      onUpdate = { id, step, startedAt -> ForegroundSyncService.update(this, id, step, 0, startedAt) },
      onStop = {
        ForegroundSyncService.stop(this)
        // 这一轮在后台结束时：服务一停，系统又可以随时回收进程，
        // 连接没有继续维持的理由，主动断开而不是留一条「假在线」。
        if (!appForeground) relayController.stop()
      },
    )
  }

  /**
   * 通知点击落点。消费后置 null，避免重组重复跳转。
   */
  private var deepLink by mutableStateOf<MorrowDestination?>(null)

  /**
   * 本机生物识别能力。每次回到前台重算：用户可能刚去设置里录了指纹。
   * 默认 Unavailable 而不是 Ready —— 探测完成前宁可只允许拒绝。
   */
  private var biometricCapability by mutableStateOf(BiometricGate.Capability.Unavailable)

  /** 主题偏好（设置页切换后立即生效并落盘）。 */
  private var themeMode by mutableStateOf(MorrowThemeMode.Ink)

  /** 系统通知总开关（设置页只读展示 + 跳系统设置）。 */
  private var notificationsEnabled by mutableStateOf(true)

  /** 成片预览（null = 没打开）。 */
  private var preview by mutableStateOf<PreviewState?>(null)

  /** 等待 result 帧的预览标题；只在失败回帧时用来保留标题。 */
  private var pendingPreviewTitle: String? = null

  /** 应用是否在前台（决定切后台时连接留不留）。 */
  private var appForeground = false

  /**
   * Android 13+ 通知运行期权限。
   *
   * 必须在 STARTED 之前注册；「没给」时前台服务仍能跑，通知只是不可见
   * （见 `ForegroundSyncService` 的 Visibility 说明）。
   */
  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  /**
   * relay 明文 → EventStreamViewModel。
   *
   * 生产里由 relay 客户端在收到解密后的 JSON 时调用 [RelayEventSink.onText]；
   * 真正的 socket 客户端属 B3 的手机侧，尚未落地 —— 所以这里先把接收器
   * 建好并把「怎么进来」这条路径测通，避免又出现「逻辑有、没人调用」。
   */
  /**
   * 配对流程（E2b）。
   *
   * 这里把它放在 Activity 而不是 Compose 的 `remember`：
   * 旋转屏幕会重建 Compose 树，若状态跟着重来，用户在高风险操作
   * （正在输六位码）中途转一下屏就要重新粘贴票据 —— 而票据只有 90 秒。
   */
  private val pairingFlow = PairingFlow()

  /**
   * 配对确认用的协程作用域。
   *
   * 与 `streamScope` 分开：确认只跑一次、只活到写完凭据，
   * 不该跟着事件流的生命周期走（事件流在后台会被 stop）。
   * Main.immediate 是因为结果要直接改 Compose 状态。
   */
  private val pairingScope =
    CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

  /**
   * 配对页状态。用 `mutableStateOf` 包一层是因为 `PairingFlow.state`
   * 是普通字段，改了不会触发重组（C2 的 `@Volatile`、C3 的 `var current`
   * 都是同一个坑，这里第三次遇到，直接用 Compose 状态包住）。
   */
  private var pairingState by mutableStateOf<PairingFlow.State>(PairingFlow.State.Idle)

  /** 首次配对用的手机密钥对；配对成功后随凭据一起落盘，不再需要 */
  private var pendingIdentity: EcdhPairing.Identity? = null

  /**
   * 取本机的待配对密钥对（没有就生成）。
   * 就是它的公钥）。所以这个身份必须在用户去电脑上生成配对码之前就有，
   * 且在整个配对过程中不能变 —— 变了桌面算出的六位码就对不上。
   */
  internal fun pendingIdentityForTest(): EcdhPairing.Identity =
    pendingIdentity ?: EcdhPairing.generateIdentity().also { pendingIdentity = it }

  /**
   * 测试用：替换「向桌面确认」这一步的真实网络往返。
   *
   * 生产环境永远是 null，走 [PairConfirmClient.confirm]。
   * 单元测试里票据是编造的（`192.168.1.10:7788` 这种地址根本不存在），
   * 真去连只会超时，于是所有「提交正确码后应已配对」的用例都会停在 Saving ——
   * 那些用例验证的是校验与写盘逻辑，不该被一次真实 TLS 往返挡住。
   */
  private var confirmForTest: (() -> PairConfirmClient.Outcome)? = null

  internal fun setConfirmOutcomeForTest(outcome: PairConfirmClient.Outcome) {
    confirmForTest = { outcome }
  }

  /**
   * 本机的配对请求文本。
   * 必须与 `onPairingTicket` 用的是**同一个身份**：桌面签发的票据里
   */
  private fun currentPairRequestText(): String {
    val identity = pendingIdentity ?: EcdhPairing.generateIdentity().also { pendingIdentity = it }
    return PairRequest.encode(deviceId = null, publicKeyB64 = identity.publicKeyB64)
  }

  /**
   * 送去 Compose 的配对请求文本。
   * 更重要的是**不能在 composition 里产生副作用的时机生成密钥对** ——
   */
  private var pairRequestState by mutableStateOf<String?>(null)

  private fun syncPairingState() {
    pairingState = pairingFlow.state
    if (pairingState is PairingFlow.State.Paired) {
      // 已配对：不再需要请求文本，藏掉那一段
      pairRequestState = null
    } else if (pendingIdentity == null || pairRequestState == null) {
      // `pendingIdentity == null` 这个条件不能省：取消配对会把身份置 null，
      // 此时 `pairRequestState` 还留着上一把钥匙的文本（非 null，不会进
      // 后面的分支）。用户再次进配对页看到 / 复制的是**已丢弃的公钥**，
      // 而 `onPairingTicket` 会用新身份 —— 桌面照旧文本签票，
      // 手机上只会说「这张配对码是给另一台手机的」。
      // 第一版只判 `== null`，就是这么漏的（`取消后重新配对` 那条测试抓到）。
      pairRequestState = currentPairRequestText()
    }
  }

  /**
   * 复制配对请求到系统剪贴板。
   */
  private fun copyPairRequest(text: String) {
    val clipboard = getSystemService(android.content.ClipboardManager::class.java)
    if (clipboard == null) {
      // 剪贴板服务拿不到（极罕见）。不静默失败：把文本留在页面上，
      // 用户还能手动选中复制。
      android.widget.Toast
        .makeText(this, "复制失败：系统剪贴板不可用，请手动选中复制", android.widget.Toast.LENGTH_LONG)
        .show()
      return
    }
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Morrow 配对请求", text))
    android.widget.Toast.makeText(this, "已复制，粘到电脑的「手机配对」里", android.widget.Toast.LENGTH_SHORT).show()
  }

  /**
   * 用户粘贴了票据文本。
   *
   * 这里是「配对入口 → 真正干活」的第一次跨越：之前 `PairingFlow`
   * 一条生产调用都没有，所有测试都靠手动驱动。
   */
  private fun onPairingTicket(raw: String) {
    // 已有密钥对就复用（重试时不该换钥匙：换了桌面那边算出的六位码就变了）
    val identity = pendingIdentity ?: EcdhPairing.generateIdentity().also { pendingIdentity = it }
    pairingFlow.beginScan()
    pairingFlow.onQrScanned(raw, identity, System.currentTimeMillis())
    syncPairingState()
  }

  /** 用户确认票据、准备输码 */
  private fun onPairingContinue() {
    pairingFlow.beginCodeEntry()
    syncPairingState()
  }

  /**
   * 用户提交六位码。
   * 码对之后要做的三件事**必须按顺序**：
   * 中间任何一步失败都要落到对应的 Failure，不能「假装成功」：
   */
  private fun onPairingCode(code: String) {
    val identity = pendingIdentity
    val current = pairingFlow.state as? PairingFlow.State.CodeEntering
    if (identity == null || current == null) {
      syncPairingState()
      return
    }
    val now = System.currentTimeMillis()

    // 先本地比码。码错时 PairingFlow 自己会记 attemptsLeft，不需要这里处理。
    val shared = runCatching {
      EcdhPairing.sharedSecret(identity.privateKey, current.ticket.desktopPub)
    }.getOrNull()
    if (shared == null) {
      pairingFlow.onQrScanned("", identity, now)   // 走统一的失败路径
      syncPairingState()
      return
    }
    val sessionKey = EcdhPairing.sessionKey(shared)
    val passed = pairingFlow.submitCode(code, now, sessionKey)
    syncPairingState()
    if (!passed) return

    val ticket = current.ticket
    val endpoint = RelayHint.parse(ticket.relayHint)
    if (endpoint == null) {
      pairingFlow.onSaveFailed(
        "配对码里没有中继地址（relayHint 缺失），无法连接。请在电脑上重新生成配对码",
        now,
      )
      syncPairingState()
      return
    }

    // 桌面证书指纹：票据里带就存下来做证书固定；没带就拒绝完成配对。
    //
    // 没有指纹就无法做 TLS 证书固定（桌面是自签证书，没有可信链）。
    // 这里**不存一份缺字段的凭据**：
    // - 存空串 → `load()` 的 `need("desktopFingerprint")` 判 CORRUPT，
    //   等于存了一份自己读不回来的文件，用户下次打开看到「配对失效」，
    //   完全不知道原因；
    // - 编一个值 → 更坏：让人以为固定生效了，那是假安全。
    val fingerprint = ticket.desktopFingerprint
    if (fingerprint.isNullOrBlank()) {
      pairingFlow.onSaveFailed(
        "配对码里没有电脑的证书指纹，无法安全连接。" +
          "这是电脑端配对信息的缺口，请更新电脑版后重新生成配对码",
        now,
      )
      syncPairingState()
      return
    }

    // 指纹要能解析成 32 字节 sha256 才存。
    // 存一份解析不出来的值，`load()` 照样成功（它只查字段在不在），
    // 但每次连接都会在 `checkServerTrusted` 里判「不匹配」——
    // 用户看到「证书不受信任」，而真实原因是配对时存了垃圾。
    if (!RelaySocketClient.SSLSocketFactoryFactory.canParseFingerprint(fingerprint)) {
      pairingFlow.onSaveFailed(
        "配对码里的证书指纹格式不对（应为 43 字符的 base64url 或 64 位 hex）。" +
          "请在电脑上重新生成配对码",
        now,
      )
      syncPairingState()
      return
    }

    // 前置条件都过了 → 去桌面确认这一次配对。
    // 这一步必须发生在写盘**之前**：桌面不认识这台设备的话，
    // 存下的凭据永远连不上（hello 会被 4401 拒），而用户会看到「配对成功」。
    confirmPairingOnDesktop(
      endpoint = endpoint,
      fingerprint = fingerprint,
      nonce = ticket.nonce,
      deviceId = ticket.deviceId,
      identity = identity,
      sessionKey = sessionKey,
      atMillis = now,
    )
  }

  /**
   * 向桌面确认配对，成功后才写盘。
   *
   * 走独立线程：这是一次真实的 TLS + WebSocket 往返（最长十几秒），
   * 放在主线程会让「确认」按钮卡住、且 ANR 风险。
   */
  private fun confirmPairingOnDesktop(
    endpoint: RelayHint.Endpoint,
    fingerprint: String,
    nonce: String,
    deviceId: String,
    identity: EcdhPairing.Identity,
    sessionKey: String,
    atMillis: Long,
  ) {
    pairingFlow.onVerified()
    syncPairingState()

    pairingScope.launch(kotlinx.coroutines.Dispatchers.IO) {
      val outcome = confirmForTest?.invoke() ?: PairConfirmClient.confirm(
        host = endpoint.host,
        port = endpoint.port,
        expectedFingerprint = fingerprint,
        nonce = nonce,
        expectedDeviceId = deviceId,
      )
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        when (outcome) {
          is PairConfirmClient.Outcome.Confirmed -> {
            persistPairedCredentials(
              deviceId = deviceId,
              identity = identity,
              sessionKey = sessionKey,
              fingerprint = fingerprint,
              endpoint = endpoint,
              atMillis = atMillis,
            )
          }
          is PairConfirmClient.Outcome.Rejected ->
            // 桌面明确拒绝：票据已用 / 过期 / 不是这张票。
            // 重试一万次也一样，必须回电脑重新生成配对码。
            pairingFlow.onRejected(outcome.reason, System.currentTimeMillis())
          is PairConfirmClient.Outcome.Unreachable ->
            // 连不上：可能是电脑刚关机、不在同一局域网、防火墙拦了。
            // 归到 DESKTOP_REJECTED（"电脑没有确认"）而不是 SAVE_FAILED ——
            // 用户要做的是「确认电脑在同一个网络」，不是「检查手机存储」。
            pairingFlow.onRejected(
              "没能联系上电脑（${outcome.reason}）。请确认手机与电脑在同一局域网、电脑上的远程通道仍开着",
              System.currentTimeMillis(),
            )
        }
        syncPairingState()
      }
    }
  }

  /**
   * 桌面已确认 → 把凭据写盘并立刻开始连接。
   *
   * 顺序：先 `onSaved()` 再 `retryNow()`。反过来的话连接会在
   * `pairingFlow` 还是 Saving 时启动，界面可能先跳到「已配对」又被
   * 连接状态覆盖一次。
   */
  private fun persistPairedCredentials(
    deviceId: String,
    identity: EcdhPairing.Identity,
    sessionKey: String,
    fingerprint: String,
    endpoint: RelayHint.Endpoint,
    atMillis: Long,
  ) {
    val stored = RelayCredentials.Stored(
      deviceId = deviceId,
      privateKeyB64 = identity.privateKeyB64,
      publicKeyB64 = identity.publicKeyB64,
      sessionKeyB64 = sessionKey,
      desktopFingerprint = fingerprint,
      relayHost = endpoint.host,
      relayPort = endpoint.port,
    )

    when (val result = credentials.save(stored)) {
      is RelayCredentials.SaveResult.Ok -> {
        pairingFlow.onSaved()
        pendingIdentity = null
        // 存好了 → 立刻尝试连接。不等到下次冷启动，
        // 否则用户配完对看到「未配对」会以为没成功。
        relayController.retryNow()
      }
      is RelayCredentials.SaveResult.Failed -> {
        pairingFlow.onSaveFailed("${result.reason}: ${result.detail}", atMillis)
      }
    }
  }

  /** 取消 / 重来 */
  private fun onPairingCancel() {
    pairingFlow.reset()
    pendingIdentity = null
    syncPairingState()
  }

  val relaySink: RelayEventSink by lazy {
    RelayEventSink(stream).also { sink ->
      sink.onResult = { frame ->
        // 按 request id 交给等待方：发消息/审批要的是「这一条有没有被受理」，
        // 而不是全局的「最近一次失败」。
        pendingInvokes.remove(frame.id)?.complete(frame)
        stream.onInvokeResult(frame.ok, frame.error)
        // `works.get` 的成功结果：载荷里有 works 数组才写入。
        // 其它 result（审批/发送）的 result 没有这个键，worksOf 返回 null。
        if (frame.ok) {
          frame.result?.let { result ->
            IgnoreBridges.worksOf(result)?.let(stream::onWorksLoaded)
            PreviewPayload.readyOf(result)?.let {
              pendingPreviewTitle = null
              preview = it
            }
          }
        } else {
          // 失败帧本身分不清是哪个请求；只有「正在等预览」时才当预览失败，
          // 否则审批/发送的失败会把预览页误弹出来。
          pendingPreviewTitle?.let { title ->
            pendingPreviewTitle = null
            preview = PreviewState.Failed(title, frame.error ?: "桌面没有返回这件产物")
          }
        }
      }
    }
  }

  /**
   * 连接状态（Compose 可观察）。
   *
   * 放在 Activity 而不是 Compose 的 `remember`：旋转/深色切换会重建 Activity，
   * 若状态跟着重建，界面会在重建瞬间回到「未配对」——
   * 用户看到连接莫名断开再重连（还会真的触发一次重连）。
   */
  val relayState: RelayConnectionState by lazy { RelayConnectionState() }

  /** 凭据存储（Keystore 加密，E1a 卡） */
  /**
   * 凭据存储。
   * 必须用具名参数：`RelayCredentials(context, keyAlias, fileName, keyProvider)`
   * AndroidKeyStore，必须注入假 `SecretKeyProvider`）。
   */
  protected open val credentials: RelayCredentials
    /**
     * 每次读都经过 `credentialsOverride`，**不能包在 `by lazy` 里**。
     * 这类「缓存把注入吃掉」的错只在测试里暴露，真机上不会出现，
     */
    get() = credentialsOverride ?: realCredentials

  private val realCredentials: RelayCredentials by lazy {
    RelayCredentials(
      context = this,
      fileName = CREDENTIALS_FILE,
      keyProvider = secretKeyProvider,
    )
  }

  /** 测试注入的替身（生产恒为 null） */
  private var credentialsOverride: RelayCredentials? = null
  private var testKeyProvider: SecretKeyProvider? = null

  /** Keystore 访问。生产用真 AndroidKeyStore；测试注入假实现（E1a 已抽出接口） */
  protected open val secretKeyProvider: SecretKeyProvider
    get() = testKeyProvider ?: AndroidKeystoreProvider()

  /**
   * 连接编排。
   */
  private val relayController: RelayConnectionController by lazy {
    RelayConnectionController(
      scope = streamScope,
      loadCredentials = { credentials.load() },
      state = relayState,
      clientFactory = { stored, listener ->
        // `!!` 在这里是安全的：controller 在调用 clientFactory 之前已经用
        // `stored.hasEndpoint` 拦过一轮（RelayConnection.kt 的 runLoop），
        // 地址不全根本走不到这里。
        RelaySocketClient(
          host = stored.relayHost!!,
          port = stored.relayPort!!,
          deviceId = stored.deviceId,
          sessionKeyBase64 = stored.sessionKeyB64,
          expectedFingerprint = stored.desktopFingerprint,
          listener = listener,
          // 断线重连时带上已经应用到的最大序号：桌面据此只补缺口。
          // 0 = 手机没有状态（首次/重装），桌面会改发全量快照。
          lastSeq = stream.state.lastSeq,
        )
      },
      onEventJson = { relaySink.onText(it) },
      onReadyAction = {
        // 先补发离线期间点下的「拒绝」，再拉作品库：审批是会让桌面
        // agent 一直等的那一类，顺序不能反。
        ApprovalDecisionInbox.flush(this)
        requestWorks()
      },
    )
  }

  /** 测试用：拿到 controller 观察真实连接次数 */
  internal fun relayControllerForTest(): RelayConnectionController = relayController

  /** 手机上行调用序号（仅用于日志与 id 去重，不参与补漏） */
  private val invokeCounter = java.util.concurrent.atomic.AtomicLong(0)

  /**
   * 等待桌面回执的上行调用：request id → 回执信号。
   *
   * 只有「必须确认对方受理」的指令（发消息、审批）才登记；
   * 发完即忘的通知类指令不进这张表。
   */
  private val pendingInvokes =
    java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<RelayEventSink.ResultFrame>>()

  /** 等回执的上限。桌面在本机 LAN 上，正常回执在毫秒级；超过这个数基本就是没送到。 */
  private val INVOKE_TIMEOUT_MS = 15_000L

  /**
   * 发一条上行 invoke。
   *
   * 未连接时直接返回 false：审批/消息都有时效，**不排队**。
   * 队列会在重连后补发用户早已放弃的指令，那比失败更危险。
   */
  private fun sendInvoke(method: String, params: Map<String, Any?>): Boolean {
    val seq = invokeCounter.incrementAndGet()
    val frame = RelayProtocol.invokeFrame(id = "phone-$seq", method = method, params = params, seq = seq)
    return relayController.send(frame)
  }

  /**
   * 下发一条会**改变桌面状态**的指令，并等桌面回执。
   *
   * 与 [sendInvoke] 的区别是「写进 socket」不算成功：TCP 只保证送到对端，
   * 桌面可能已经拒绝（会话不存在、运行已结束、白名单外的方法）。
   * 手机这边若只看 socket 结果，就会出现「界面显示已发送、电脑毫无反应」——
   * 用户以为指令生效了，实际上什么也没发生。
   *
   * 回执按 request id 关联（桌面 `result` 帧带回同一个 id），
   * 超时按失败处理：宁可让用户看到「没送到」并重发，也不要假装成功。
   */
  private suspend fun sendInvokeAwaiting(
    method: String,
    params: Map<String, Any?>,
    timeoutMillis: Long = INVOKE_TIMEOUT_MS,
  ): Pair<Boolean, String?> {
    val seq = invokeCounter.incrementAndGet()
    val id = "phone-$seq"
    val frame = RelayProtocol.invokeFrame(id = id, method = method, params = params, seq = seq)
    val waiter = CompletableDeferred<RelayEventSink.ResultFrame>()
    pendingInvokes[id] = waiter
    try {
      if (!relayController.send(frame)) return false to "未连接桌面，指令没有发出去"
      val result = withTimeoutOrNull(timeoutMillis) { waiter.await() }
        ?: return false to "桌面没有在 ${timeoutMillis / 1000} 秒内确认，指令可能没有执行"
      return result.ok to result.error
    } finally {
      pendingInvokes.remove(id)
    }
  }

  /** 发送一条用户消息；返回 true 仅当桌面回执 ok。 */
  private suspend fun sendMessageAwaiting(sessionId: String, text: String): Pair<Boolean, String?> =
    sendInvokeAwaiting("message.send", mapOf("sessionId" to sessionId, "text" to text))

  /** 审批决策下发。通知按钮、生物识别回调、界面按钮都走这一处。 */
  private fun decideApproval(id: String, allow: Boolean): Boolean =
    sendInvoke(
      "approval.decide",
      mapOf("requestId" to id, "decision" to if (allow) "once" else "deny"),
    )

  /**
   * 清除配对：停连接、删密钥文件与 Keystore 条目、清收件箱、回到未配对。
   *
   * 顺序有意义：先停连接再删凭据，否则正在重连的循环可能刚好把旧凭据
   * 读进内存并在删除后继续用。
   */
  private fun clearPairing() {
    relayController.stop()
    RelayCredentials(this).clear()
    ApprovalDecisionInbox.clear(this)
    pairingFlow.reset()
    pairingState = pairingFlow.state
    pairRequestState = currentPairRequestText()
  }

  private fun openNotificationSettings() {
    runCatching {
      startActivity(
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
      )
    }
  }

  /**
   * 拉作品库摘要（连接就绪、打开作品页时各一次）。
   *
   * 失败不做重试：页面保留「未知」文案，用户重进页面会再拉一次。
   */
  /** 拉作品库摘要（连接就绪、打开作品页时各一次）。
   *
   * 失败不做重试：页面保留「未知」文案，用户重进页面会再拉一次。
   */
  private fun requestWorks() {
    sendInvoke("works.get", emptyMap())
  }

  /**
   * 取一件成片的字节（`works.preview`）。
   *
   * 立刻显示 Loading：取件是一次往返，桌面可能正在读文件；空白等待会让
   * 用户以为点击没生效。结果由 result 帧回调写回 [preview]。
   */
  private fun requestPreview(workId: String, productId: String, title: String) {
    pendingPreviewTitle = title
    preview = PreviewState.Loading(title)
    if (!sendInvoke("works.preview", mapOf("workId" to workId, "productId" to productId))) {
      pendingPreviewTitle = null
      preview = PreviewState.Failed(title, "未连接桌面，取不到这件产物")
    }
  }

  /** 协作成员干预上行（停止/重试/发新指令）。 */
  private fun sendSwarmCommand(command: SwarmCommand) {
    val (method, params) = when (command) {
      is SwarmCommand.Stop -> "swarm.stopMember" to mapOf(
        "projectId" to command.projectId,
        "taskId" to command.taskId,
      )
      is SwarmCommand.Retry -> "swarm.retryMember" to mapOf(
        "sessionId" to command.sessionId,
        "projectId" to command.projectId,
        "taskId" to command.taskId,
      )
      is SwarmCommand.Instruction -> "swarm.instruction" to mapOf(
        "sessionId" to command.sessionId,
        "projectId" to command.projectId,
        "taskId" to command.taskId,
        "text" to command.text,
      )
    }
    sendInvoke(method, params)
  }

  /** 测试用：手动触发断开（Robolectric 的 onStop 不会自动走全生命周期回调） */
  internal fun controllerStopForTest() = relayController.stop()

  // ─────────────────── 配对相关（E2b）的测试钩子 ───────────────────

  /**
   * 注入假 Keystore。
   *
   * Robolectric 没有 AndroidKeyStore provider（E1a 已记录），
   * 所以测试必须换掉 `secretKeyProvider`。这里重建 credentials，
   * 因为 `by lazy` 一旦取过就不会再用新 provider。
   */
  internal fun installTestKeystore(provider: SecretKeyProvider) {
    testKeyProvider = provider
    credentialsOverride = RelayCredentials(
      context = this,
      fileName = CREDENTIALS_FILE,
      keyProvider = provider,
    )
  }

  /** 测试用：读到当前有效的凭据存储（注入了就用注入的那个） */
  internal fun credentialsForTest(): RelayCredentials = credentialsOverride ?: credentials

  /** 测试用：从「已读票据」推进到「输码」 */
  internal fun pairingContinueForTest() = onPairingContinue()

  /** 测试用：提交六位码 */
  internal fun pairingSubmitCodeForTest(code: String) = onPairingCode(code)

  /** 测试用：读当前配对状态 */
  internal fun pairingStateForTest(): PairingFlow.State = pairingState

  /** 测试用：读最近一次解析到的票据（诊断 relayHint/fingerprint 是否读到） */
  internal fun lastTicketForTest(): EcdhPairing.Ticket? = when (val s = pairingState) {
    is PairingFlow.State.TicketReady -> s.ticket
    is PairingFlow.State.CodeEntering -> s.ticket
    is PairingFlow.State.Verifying -> s.ticket
    is PairingFlow.State.Saving -> s.ticket
    else -> null
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // 应用已在运行时点通知：singleTop 复用实例，深链从这里进来。
    setIntent(intent)
    deepLink = intent.dataString?.takeIf { it.isNotBlank() }?.let { MorrowDeepLink.parse(it) }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
    )
    super.onCreate(savedInstanceState)

    themeMode = MorrowPreferences.theme(this)
    notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()

    // 通知点击（cold start）：morrow://approvals / morrow://activity。
    deepLink = intent?.dataString?.takeIf { it.isNotBlank() }?.let { MorrowDeepLink.parse(it) }

    // 渠道已在 Application 里建好；这里补运行期权限（Android 13+）。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 每批事件落地后发通知（审批 / 本轮结束 / 成员卡住 / 进度）。
    // 放在 setContent 之前：第一次连接的事件也不会漏。
    stream.onBatchApplied = { before, after, events ->
      relayNotifications.onBatch(before, after, events)
      relaySyncTracking.onBatch(after)
    }

    // 通知「拒绝」按钮的决策走收件箱：有连接就立刻发，没连接就落盘，
    // 等连上后 flush（见 onReadyAction）。这是收件箱唯一的真实 sink。
    ApprovalDecisionInbox.registerSink { approvalId, allow ->
      sendInvoke(
        "approval.decide",
        mapOf("requestId" to approvalId, "decision" to if (allow) "once" else "deny"),
      )
    }
    ApprovalDecisionInbox.flush(this)
    // 进 App 就把配对请求算好：用户可能直接进配对页，那时 `pairRequestState`
    // 必须是可用的。放在 setContent 之前（不在 composition 里），
    // 保证「生成密钥对」这个副作用只发生一次、时机确定。
    if (pairingFlow.state !is PairingFlow.State.Paired) {
      pairRequestState = currentPairRequestText()
    }
    setContent {
      MorrowTheme(mode = themeMode) {
        // 不用 Material3 的 Surface 包一层：它默认铺 colorScheme.surface（#171D27），
        // 与页面底色（C1 抬底后的 #0E1116）不同，会盖住 drawBehind 画的渐变，
        // 导致整屏比设计偏亮、底部出现色差。底色统一由 MorrowScaffold 的背景层负责。
        MorrowApp(
          stream = stream,
          relayConnection = relayState.connection,
          pairingState = pairingState,
          pairRequest = pairRequestState,
          onCopyPairRequest = ::copyPairRequest,
          onPairingTicket = ::onPairingTicket,
          onPairingCode = ::onPairingCode,
          onPairingContinue = ::onPairingContinue,
          onPairingCancel = ::onPairingCancel,
          // 审批只能 once / deny（手机端不提供 always）；发送失败由 MorrowApp 显示提示。
          onApprovalDecision = { id, decision ->
            decideApproval(id, allow = decision == "once")
          },
          // 允许前必须过指纹/面容：成功发 once，取消/失败按拒绝发 deny。
          onApprovalAllowRequest = { id ->
            AndroidBiometricGate.authenticate(this, title = "批准这次操作") { result ->
              decideApproval(id, allow = BiometricGate.resolve(result) == BiometricGate.Decision.Allow)
            }
          },
          biometricCapability = biometricCapability,
          onSendMessage = { sessionId, text, onResult ->
            // streamScope 在主线程：回执到达后要直接改 Compose 状态。
            streamScope.launch {
              val (ok, error) = sendMessageAwaiting(sessionId, text)
              onResult(ok, error)
            }
          },
          onWorksOpened = { requestWorks() },
          deepLink = deepLink,
          onDeepLinkHandled = { deepLink = null },
          themeMode = themeMode,
          onThemeChange = { mode ->
            themeMode = mode
            MorrowPreferences.setTheme(this, mode)
          },
          onClearPairing = ::clearPairing,
          notificationsEnabled = notificationsEnabled,
          onOpenNotificationSettings = ::openNotificationSettings,
          preview = preview,
          onRequestPreview = ::requestPreview,
          onClosePreview = {
            preview = null
            pendingPreviewTitle = null
          },
          onSwarmCommand = ::sendSwarmCommand,
        )
      }
    }
  }

  /**
   * 回到前台时检查「打开时补拉」（蓝图 Tier 3 的最后一环）。
   */
  override fun onResume() {
    super.onResume()
    // 用户可能刚在系统设置里关了通知；回到前台重读一次。
    notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
    val request = FcmReceiver.onResume(this) ?: return
    pendingPullRequest.value = request
  }

  /** 补拉做完（或失败放回）后清掉界面上的「补拉中」 */
  fun clearPendingPull() {
    pendingPullRequest.value = null
  }

  override fun onStart() {
    super.onStart()
    appForeground = true
    // 回到前台就开始连。放在 onStart（不是 onCreate）：
    // 切后台再回来时 onCreate 不会再执行，连接要能自己恢复。
    relayController.start()
    // 顺带重算生物识别能力（用户可能刚录入/删除了指纹）。
    biometricCapability = AndroidBiometricGate.capability(this)
  }

  override fun onStop() {
    appForeground = false
    // 跟盯中：前台服务在保进程，连接继续持有，用户切回来时不用重连、
    // 也不会漏掉期间的审批/完成事件。
    // 非跟盯：切后台就断开。手机端连接是「用户在看」才有意义，后台
    // 常驻连接会耗电，系统本来也会收紧后台网络；推送走 FcmReceiver。
    if (ForegroundSyncState.runningTaskId() == null) {
      relayController.stop()
    }
    super.onStop()
  }

  override fun onDestroy() {
    // 先落地缓冲再取消作用域：直接取消会让用户最后看到的半句话永远停在半句。
    stream.onCleared()
    streamScope.cancel()
    super.onDestroy()
  }

  companion object {
    /**
     * 最近一次「打开时补拉」的请求。
     *
     * 用 `mutableStateOf` 而不是普通字段：工位页要读它并在变化时重组，
     * 普通字段改了不会触发重组（界面会一直显示旧值，而且没有任何报错）。
     * 静态是因为目前还没有跨层事件总线；等 relay 通道接进来会换成状态流。
     */
    val pendingPullRequest = mutableStateOf<PendingPull.Request?>(null)

    /** 测试用：重置到冷启动状态 */
    fun resetPendingPullForTest() {
      pendingPullRequest.value = null
    }

    /** 凭据文件名（落在 filesDir 下，已被 backup_rules 排除云备份） */
    const val CREDENTIALS_FILE = "relay-credentials.bin"
  }
}

// ───── MorrowApplication.kt ─────
/**
 * 应用级入口。
 * 目前只做一件必须做的事：**注册通知渠道**。
 * 为什么必须在 `onCreate` 而不是「第一次发通知时」：
 */
class MorrowApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    NotifyChannels.register(this)
    // 通知里的拒绝可能发生在 Activity 未启动的进程里：先恢复落盘队列。
    ApprovalDecisionInbox.restore(this)
  }
}
