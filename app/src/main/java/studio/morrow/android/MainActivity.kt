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

class MainActivity : FragmentActivity() {
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
        if (!appForeground) relayController.stop()
      },
    )
  }

  private var deepLink by mutableStateOf<MorrowDestination?>(null)

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

  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  private val pairingFlow = PairingFlow()

  private val pairingScope =
    CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

  private var pairingState by mutableStateOf<PairingFlow.State>(PairingFlow.State.Idle)

  /** 首次配对用的手机密钥对；配对成功后随凭据一起落盘，不再需要 */
  private var pendingIdentity: EcdhPairing.Identity? = null

  internal fun pendingIdentityForTest(): EcdhPairing.Identity =
    pendingIdentity ?: EcdhPairing.generateIdentity().also { pendingIdentity = it }

  private var confirmForTest: (() -> PairConfirmClient.Outcome)? = null

  internal fun setConfirmOutcomeForTest(outcome: PairConfirmClient.Outcome) {
    confirmForTest = { outcome }
  }

  private fun currentPairRequestText(): String {
    val identity = pendingIdentity ?: EcdhPairing.generateIdentity().also { pendingIdentity = it }
    return PairRequest.encode(deviceId = null, publicKeyB64 = identity.publicKeyB64)
  }

  private var pairRequestState by mutableStateOf<String?>(null)

  private fun syncPairingState() {
    pairingState = pairingFlow.state
    if (pairingState is PairingFlow.State.Paired) {
      // 已配对：不再需要请求文本，藏掉那一段
      pairRequestState = null
    } else if (pendingIdentity == null || pairRequestState == null) {
      // 手机上只会说「这张配对码是给另一台手机的」。
      pairRequestState = currentPairRequestText()
    }
  }

  private fun copyPairRequest(text: String) {
    val clipboard = getSystemService(android.content.ClipboardManager::class.java)
    if (clipboard == null) {
      // 用户还能手动选中复制。
      android.widget.Toast
        .makeText(this, "复制失败：系统剪贴板不可用，请手动选中复制", android.widget.Toast.LENGTH_LONG)
        .show()
      return
    }
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Morrow 配对请求", text))
    android.widget.Toast.makeText(this, "已复制，粘到电脑的「手机配对」里", android.widget.Toast.LENGTH_SHORT).show()
  }

  private fun onPairingTicket(raw: String) {
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

  private fun onPairingCode(code: String) {
    val identity = pendingIdentity
    val current = pairingFlow.state as? PairingFlow.State.CodeEntering
    if (identity == null || current == null) {
      syncPairingState()
      return
    }
    val now = System.currentTimeMillis()

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

    //
    // 这里**不存一份缺字段的凭据**：
    //   完全不知道原因；
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
            // 重试一万次也一样，必须回电脑重新生成配对码。
            pairingFlow.onRejected(outcome.reason, System.currentTimeMillis())
          is PairConfirmClient.Outcome.Unreachable ->
            pairingFlow.onRejected(
              "没能联系上电脑（${outcome.reason}）。请确认手机与电脑在同一局域网、电脑上的远程通道仍开着",
              System.currentTimeMillis(),
            )
        }
        syncPairingState()
      }
    }
  }

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
    RelayEventSink(
      stream,
      // M-03：事件应用成功后回一条 ack。
      //
      // 而抛错会把「处理事件」这条主路径带崩。
      sendAck = { seq -> runCatching { sendFrameOffMainThread(RelayProtocol.ackFrame(seq)) } },
    ).also { sink ->
      sink.onResult = { frame ->
        // 而不是全局的「最近一次失败」。
        pendingInvokes.remove(frame.id)?.complete(frame)
        stream.onInvokeResult(frame.ok, frame.error)
        if (frame.ok) {
          frame.result?.let { result ->
            IgnoreBridges.worksOf(result)?.let(stream::onWorksLoaded)
            val ready = PreviewPayload.readyOf(result)
            if (ready != null) {
              pendingPreviewTitle = null
              preview = ready
            } else {
              pendingPreviewTitle?.let { title ->
                pendingPreviewTitle = null
                preview = PreviewState.Failed(title, PREVIEW_UNUSABLE)
              }
            }
          }
        } else {
          // 否则审批/发送的失败会把预览页误弹出来。
          pendingPreviewTitle?.let { title ->
            pendingPreviewTitle = null
            preview = PreviewState.Failed(title, frame.error ?: "桌面没有返回这件产物")
          }
        }
      }
    }
  }

  val relayState: RelayConnectionState by lazy { RelayConnectionState() }

  protected open val credentials: RelayCredentials
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

  private val relayController: RelayConnectionController by lazy {
    RelayConnectionController(
      scope = streamScope,
      loadCredentials = { credentials.load() },
      state = relayState,
      clientFactory = { stored, listener ->
        // 地址不全根本走不到这里。
        RelaySocketClient(
          host = stored.relayHost!!,
          port = stored.relayPort!!,
          deviceId = stored.deviceId,
          sessionKeyBase64 = stored.sessionKeyB64,
          expectedFingerprint = stored.desktopFingerprint,
          listener = listener,
          //
          lastSeq = maxOf(stream.state.lastSeq, MorrowPreferences.lastSeq(this)),
        )
      },
      onEventJson = { relaySink.onText(it) },
      onWelcome = { welcome ->
        if (welcome.snapshotRequired) {
          sendInvoke("snapshot.get", emptyMap())
        }
      },
      onReadyAction = {
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

  private val pendingInvokes =
    java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<RelayEventSink.ResultFrame>>()

  /** 等回执的上限。桌面在本机 LAN 上，正常回执在毫秒级；超过这个数基本就是没送到。 */
  private val INVOKE_TIMEOUT_MS = 15_000L

  private fun sendInvoke(method: String, params: Map<String, Any?>): Boolean {
    val seq = invokeCounter.incrementAndGet()
    val frame = RelayProtocol.invokeFrame(id = "phone-$seq", method = method, params = params, seq = seq)
    return sendFrameOffMainThread(frame)
  }

  private fun sendFrameOffMainThread(frame: String): Boolean {
    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
      return relayController.send(frame)
    }
    return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
      relayController.send(frame)
    }
  }

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
      if (!sendFrameOffMainThread(frame)) {
        val detail = relayController.lastSendFailure
        return false to if (detail != null) "未连接桌面，指令没有发出去（$detail）" else "未连接桌面，指令没有发出去"
      }
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

  private suspend fun stopRunAwaiting(sessionId: String, runId: String): Boolean {
    val (ok, _) = sendInvokeAwaiting(
      "run.stop",
      mapOf("sessionId" to sessionId, "runId" to runId),
      timeoutMillis = 8_000L,
    )
    return ok
  }

  /** 审批决策下发。通知按钮、生物识别回调、界面按钮都走这一处。 */
  private fun decideApproval(id: String, allow: Boolean): Boolean =
    // ── F-11：同一 id 只下发一次 ──
    //
    //
    ApprovalActions.dispatchOnce(id, allow) { approvalId, allowIt ->
      sendInvoke(
        "approval.decide",
        mapOf(
          "requestId" to approvalId,
          "decision" to (if (allowIt) ApprovalDecision.Once else ApprovalDecision.Deny).wire,
        ),
      )
    }

  private fun clearPairing() {
    relayController.stop()
    RelayCredentials(this).clear()
    ApprovalDecisionInbox.clear(this)
    pairingFlow.reset()
    pairingState = pairingFlow.state
    pairRequestState = currentPairRequestText()
  }

  private fun openDndAccessSettings() {
    runCatching {
      startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }
  }

  private fun openNotificationSettings() {
    runCatching {
      startActivity(
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
      )
    }
  }

  private fun requestWorks() {
    sendInvoke("works.get", emptyMap())
  }

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

    deepLink = intent?.dataString?.takeIf { it.isNotBlank() }?.let { MorrowDeepLink.parse(it) }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    stream.onBatchApplied = { before, after, events ->
      relayNotifications.onBatch(before, after, events)
      relaySyncTracking.onBatch(after)
      if (after.lastSeq > before.lastSeq) {
        MorrowPreferences.setLastSeq(this, after.lastSeq)
      }
    }

    // 用户在通知栏点过之后，界面再点不会再发一条。
    ApprovalDecisionInbox.registerSink { approvalId, allow ->
      decideApproval(approvalId, allow)
    }
    ApprovalDecisionInbox.flush(this)
    if (pairingFlow.state !is PairingFlow.State.Paired) {
      pairRequestState = currentPairRequestText()
    }
    setContent {
      MorrowTheme(mode = themeMode) {
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
          onApprovalDecision = { id, decision ->
            decideApproval(id, allow = ApprovalDecision.fromWire(decision) == ApprovalDecision.Once)
          },
          onApprovalAllowRequest = { id ->
            AndroidBiometricGate.authenticate(this, title = "批准这次操作") { result ->
              decideApproval(id, allow = BiometricGate.resolve(result) == BiometricGate.Decision.Allow)
            }
          },
          biometricCapability = biometricCapability,
          onSendMessage = { sessionId, text, onResult ->
            streamScope.launch {
              val (ok, error) = sendMessageAwaiting(sessionId, text)
              onResult(ok, error)
            }
          },
          onStopRun = { sessionId, runId, onResult ->
            streamScope.launch {
              onResult(stopRunAwaiting(sessionId, runId))
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
          dndAccessGranted = DoNotDisturb.canBypass(this),
          // 没任务时查它没有意义）。
          syncNotificationHidden = ForegroundSyncState.isRunning() &&
            ForegroundSyncState.visibility(this) != ForegroundSyncService.Visibility.Visible,
          onOpenDndAccessSettings = ::openDndAccessSettings,
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

  override fun onResume() {
    super.onResume()
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
    relayController.start()
    biometricCapability = AndroidBiometricGate.capability(this)
  }

  override fun onStop() {
    appForeground = false
    // 也不会漏掉期间的审批/完成事件。
    if (ForegroundSyncState.runningTaskId() == null) {
      relayController.stop()
    }
    super.onStop()
  }

  override fun onDestroy() {
    stream.onCleared()
    streamScope.cancel()
    super.onDestroy()
  }

  companion object {
    internal const val PREVIEW_UNUSABLE = "这件产物手机上显示不了（可能超过 4 MB），请在桌面打开"

    val pendingPullRequest = mutableStateOf<PendingPull.Request?>(null)

    /** 测试用：重置到冷启动状态 */
    fun resetPendingPullForTest() {
      pendingPullRequest.value = null
    }

    /** 凭据文件名（落在 filesDir 下，已被 backup_rules 排除云备份） */
    const val CREDENTIALS_FILE = "relay-credentials.bin"
  }
}

class MorrowApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    NotifyChannels.register(this)
    ApprovalDecisionInbox.restore(this)
  }
}
