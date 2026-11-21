package studio.morrow.android

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

@Composable
fun MorrowApp(
  initialSwarm: SwarmState? = null,
  approvals: ApprovalQueue? = null,
  relayConnection: Connection? = null,
  pairingState: studio.morrow.android.PairingFlow.State? = null,
  pairRequest: String? = null,
  /** 复制配对请求。生产用系统剪贴板；测试注入一个记录器。 */
  onCopyPairRequest: (String) -> Unit = {},
  /** 用户粘贴了票据文本 */
  onPairingTicket: (String) -> Unit = {},
  /** 用户提交六位码 */
  onPairingCode: (String) -> Unit = {},
  /** 从「已读票据」进到「输码」 */
  onPairingContinue: () -> Unit = {},
  /** 取消配对（回工位） */
  onPairingCancel: () -> Unit = {},
  /** 生物识别能力。生产由 BiometricPromptLauncher 探测；测试注入 */
  biometricCapability: BiometricGate.Capability = BiometricGate.Capability.Ready,
  works: List<Work> = emptyList(),
  onWorksOpened: () -> Unit = {},
  deepLink: MorrowDestination? = null,
  onDeepLinkHandled: () -> Unit = {},
  initialArtifacts: List<Artifact> = emptyList(),
  stream: EventStreamViewModel? = null,
  onApprovalDecision: (id: String, decision: String) -> Boolean = { _, _ -> false },
  onApprovalAllowRequest: ((id: String) -> Unit)? = null,

  /** 当前主题（设置页可切换）。 */
  themeMode: MorrowThemeMode = MorrowThemeMode.Ink,
  onThemeChange: (MorrowThemeMode) -> Unit = {},
  /** 设置页「清除配对」：删凭据、停连接、重新进入未配对。 */
  onClearPairing: () -> Unit = {},
  /** 系统通知总开关（用于设置页状态文案与跳转）。 */
  notificationsEnabled: Boolean = true,
  dndAccessGranted: Boolean = true,
  syncNotificationHidden: Boolean = false,
  /** 去系统设置授予勿扰访问权限（M-09）。 */
  onOpenDndAccessSettings: () -> Unit = {},
  onOpenNotificationSettings: () -> Unit = {},

  /** 成片预览的当前状态（null = 没有打开预览）。 */
  preview: PreviewState? = null,
  onRequestPreview: ((workId: String, productId: String, title: String) -> Unit)? = null,
  onClosePreview: () -> Unit = {},
  onSwarmCommand: ((SwarmCommand) -> Unit)? = null,
  onSendMessage: (sessionId: String, text: String, onResult: (Boolean, String?) -> Unit) -> Unit =
    { _, _, onResult -> onResult(false, "未连接桌面") },
  onStopRun: (sessionId: String, runId: String, onResult: (Boolean) -> Unit) -> Unit =
    { _, _, onResult -> onResult(false) },
) {
  val streamState = stream?.state
  // 「工位页算不算在线」只有一个判定入口。
  //
  //
  val online = isRelayOnline(relayConnection, ForegroundSyncState.current())
  val projected: WorkbenchData? = streamState?.let { st ->
    WorkbenchProjection.project(
      state = st,
      connection = relayConnection ?: Connection(
        phase = if (online) ConnectionPhase.Online else ConnectionPhase.Unpaired,
      ),
      syncNotificationHidden = syncNotificationHidden,
    )
  }
  val p = MorrowTheme.palette
  val pageBg = MorrowTheme.bg

  var route by remember { mutableStateOf(Route.Workbench) }
  // ── M-16：草稿跨屏幕旋转保留 ──
  //
  //
  // 不存在的流，比不恢复更糟。
  var composer by rememberSaveable(stateSaver = ComposerStateSaver) {
    mutableStateOf(ComposerState())
  }
  var thread by remember { mutableStateOf(ThreadState(title = "", subtitle = "")) }
  // 「还没有协作成员」，而不是编几个假成员出来。
  var swarm by remember {
    mutableStateOf(initialSwarm ?: SwarmState(goal = "", planVersion = "v1"))
  }
  //
  val approvalQueue = remember(approvals) { approvals ?: ApprovalQueue({ _, _, _ -> true }) }
  var queueVersion by remember { mutableStateOf(0) }
  // 不在工位页也开一个每秒定时器白耗电。
  var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
  val bioBlocked = when (val v = BiometricGate.gate(biometricCapability)) {
    is BiometricGate.Verdict.DenyOnly -> v.why
    is BiometricGate.Verdict.Proceed -> null
  }

  // ── 事件流 → 页面数据（生产路径） ──
  var decidedApprovals by remember { mutableStateOf(setOf<String>()) }
  var approvalActionError by remember { mutableStateOf<String?>(null) }
  val streamApprovals = streamState?.let { ApprovalProjection.fromState(it, nowMillis) } ?: emptyList()
        @Suppress("UNUSED_EXPRESSION") queueVersion
        val pendingApprovalItems: List<ApprovalItem> = if (streamState != null) {
          streamApprovals.filterNot { it.id in decidedApprovals }
        } else {
          approvalQueue.pending
        }

  //
  // 还是「这个会话本来就是空的」。
  //
  val syncingFirstSnapshot: Boolean =
    streamState != null && online && !streamState.snapshotLoaded &&
      streamState.sessions.isEmpty()

  val liveThread: ThreadState? = streamState?.let { st ->
    val sessionId = st.activeSessionId ?: st.sessions.keys.firstOrNull()
    val session = sessionId?.let { st.sessions[it] }
    if (session == null) {
      null
    } else {
      ThreadState(
        title = session.title.ifBlank { "未命名会话" },
        subtitle = if (st.runs.containsKey(session.id)) "正在运行" else "已连接",
        messages = session.messages.map { message ->
          ThreadMessage(
            id = message.id,
            author = when (message.role) {
              studio.morrow.android.MessageRole.User -> Author.User
              studio.morrow.android.MessageRole.System -> Author.System
              studio.morrow.android.MessageRole.Assistant -> Author.Assistant
            },
            // 思考同理会话。
            text = message.text.ifBlank {
              message.blocks.filterIsInstance<UiBlock.Text>()
                .joinToString("\n\n") { it.text }
            },
            streaming = message.streaming,
            thinking = message.thinking?.takeIf { it.isNotBlank() }
              ?: message.blocks.filterIsInstance<UiBlock.Thinking>()
                .joinToString("\n\n") { it.text }
                .takeIf { it.isNotBlank() },
            blockErrors = message.blocks.filterIsInstance<UiBlock.Error>().map { it.message },
            tokensUsed = message.tokensUsed,
            elapsedMillis = st.runs[session.id]?.let { run ->
              (System.currentTimeMillis() - run.startedAtMillis).coerceAtLeast(0)
            },
            artifact = if (message.id == session.messages.lastOrNull { it.role == studio.morrow.android.MessageRole.Assistant }?.id) {
              st.artifacts?.lastOrNull()?.let { art ->
                ArtifactRef(
                  name = art.name,
                  format = art.format,
                  bytes = art.bytes ?: -1L,
                  insertions = null,
                  deletions = null,
                )
              }
            } else null,
            // 工具调用、计划、产物与失败态都要跟着消息走：
            toolGroup = ToolProjection.groupOf(message.id, message.tools),
            plan = st.activePlan?.let { plan ->
              // 挂到每条消息会变成重复卡片。
              if (message.id == session.messages.lastOrNull { it.role == studio.morrow.android.MessageRole.Assistant }?.id) {
                PlanCard(
                  id = "plan-${session.id}",
                  title = plan.title,
                  steps = plan.steps.map { PlanStep(it.text, StepState.Running) },
                  approved = !plan.planMode,
                  summary = plan.summary,
                  risks = plan.risks,
                )
              } else null
            },
          )
        },
        composer = composer,
      )
    }
  }
  var threadSendError by remember { mutableStateOf<String?>(null) }

  val liveSwarm: SwarmState? = streamState?.let { SwarmProjection.fromState(it, swarm) }

  val liveWorks: List<Work>? = streamState?.let { WorksProjection.from(it.works) }

  var pendingSessionFocus by remember { mutableStateOf<String?>(null) }
  /** 通知深链要聚焦的审批（滚动定位）；手动进入审批页时为 null。 */
  var approvalFocus by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(deepLink) {
    when (val dest = deepLink) {
      is MorrowDestination.Approvals -> {
        approvalFocus = dest.focusId
        route = Route.Approvals
        onDeepLinkHandled()
      }
      is MorrowDestination.Activity -> {
        pendingSessionFocus = dest.focusId
        route = Route.Thread
        onDeepLinkHandled()
      }
      MorrowDestination.Workbench -> {
        route = Route.Workbench
        onDeepLinkHandled()
      }
      null -> Unit
    }
  }
  LaunchedEffect(pendingSessionFocus, streamState?.sessions?.keys) {
    val focus = pendingSessionFocus ?: return@LaunchedEffect
    if (streamState?.sessions?.containsKey(focus) == true) {
      stream?.selectSession(focus)
      pendingSessionFocus = null
    }
  }

  AdaptiveScaffold(
    background = {
      Box(
        Modifier
          .fillMaxSize()
          .drawBehind {
            drawRect(pageBg)
            drawRect(
              brush = Brush.radialGradient(
                colors = listOf(p.goldDim.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * 0.78f, size.height * -0.06f),
                radius = size.width * 1.15f
              )
            )
          }
      )
    }
  ) {
    Box(Modifier.fillMaxSize()) {
      when (route) {
      Route.Workbench -> {
        val actionable = pendingApprovalItems.filterNot { it.isExpired(nowMillis) }
        val oldest = actionable.minOfOrNull { it.createdAtMillis }
        WorkbenchRoute(
          projected = projected,
          relayConnection = relayConnection,
          onOpenThread = { route = Route.Thread },
          approvals = if (actionable.isEmpty()) null
          else PendingApprovals(count = actionable.size, oldestAtMillis = oldest ?: nowMillis),
          onOpenApprovals = { route = Route.Approvals },
          onOpenWorks = { route = Route.Works },
          hasWorks = (liveWorks ?: works).isNotEmpty(),
          onOpenSettings = { route = Route.Settings },
          artifacts = initialArtifacts,
          onOpenPairing = { route = Route.Pairing },
          syncingFirstSnapshot = syncingFirstSnapshot,
        )
      }
      Route.Settings -> {
        val pairedState = pairingState as? studio.morrow.android.PairingFlow.State.Paired
        SettingsScreen(
          themeMode = themeMode,
          paired = pairedState != null,
          deviceName = pairedState?.deviceId,
          notificationsEnabled = notificationsEnabled,
          dndAccessGranted = dndAccessGranted,
          unparsedTypes = streamState?.unparsedTypes.orEmpty(),
          biometric = biometricCapability,
          versionName = studio.morrow.android.BuildConfig.VERSION_NAME,
          onThemeChange = onThemeChange,
          onClearPairing = onClearPairing,
          onOpenNotificationSettings = onOpenNotificationSettings,
          onOpenDndAccessSettings = onOpenDndAccessSettings,
          onBack = { route = Route.Workbench },
        )
      }
      Route.Pairing -> {
        PairingRoute(
          state = pairingState ?: studio.morrow.android.PairingFlow.State.Idle,
          nowMillis = nowMillis,
          pairRequest = pairRequest,
          onCopyPairRequest = onCopyPairRequest,
          onPasteTicket = onPairingTicket,
          onSubmitCode = onPairingCode,
          onContinueToCode = onPairingContinue,
          onRetry = onPairingCancel,
          onCancel = {
            onPairingCancel()
            route = Route.Workbench
          },
        )
        // 配对完成后自动回工位。
        //
        //
        LaunchedEffect(pairingState) {
          if (pairingState is studio.morrow.android.PairingFlow.State.Paired) {
            kotlinx.coroutines.delay(1200)
            if (route == Route.Pairing) route = Route.Workbench
          }
        }
      }
      Route.Thread -> {
        val baseThread = liveThread ?: thread
        ThreadRoute(
          state = baseThread.copy(composer = composer),
          onComposerChange = { composer = it },
          onBack = { route = Route.Workbench },
          workspaceFiles = streamState?.workspaceFiles.orEmpty(),
          onOpenSessions = if (streamState != null) ({ route = Route.Sessions }) else null,
          swarmMembers = streamState?.members?.size ?: swarm.members.size,
          onOpenSwarm = { route = Route.Swarm },
          sendError = threadSendError,
          onStopRun = {
            val sessionId = streamState?.activeSessionId
            val runId = sessionId?.let { streamState?.runs?.get(it)?.runId }
            if (streamState != null && sessionId != null && runId != null) {
              onStopRun(sessionId, runId) { ok ->
                if (ok) {
                  composer = composer.copy(generating = false)
                  threadSendError = null
                } else {
                  threadSendError = "停止指令没有发出去（未连接桌面）"
                }
              }
            } else {
              composer = composer.copy(generating = false)
            }
          },
          onSend = { request ->
            val sessionId = streamState?.activeSessionId ?: streamState?.sessions?.keys?.firstOrNull()
            if (streamState != null && sessionId != null) {
              composer = composer.copy(sending = true)
              onSendMessage(sessionId, request.text) { ok, error ->
                composer = if (ok) {
                  ThreadReducer.afterSend(composer)
                } else {
                  composer.copy(sending = false, canSend = request.text.isNotBlank())
                }
                threadSendError = if (ok) null else (error ?: "桌面没有受理这条消息")
              }
            } else {
              //
              threadSendError = "未连接桌面，这条消息没有发出去。草稿已保留。"
            }
          },
        )
      }
      Route.Sessions -> {
        val sections = streamState?.let { SessionListProjection.sectionsOf(it) } ?: emptyList()
        SessionListScreen(
          sections = sections,
          online = relayConnection?.phase == ConnectionPhase.Online,
          nowMillis = nowMillis,
          onBack = { route = Route.Thread },
          onSelect = { id ->
            stream?.selectSession(id)
            route = Route.Thread
          },
        )
      }
      Route.Approvals -> {
        // 否则顶栏会写「3 条待审批」而列表是空的。
        LaunchedEffect(Unit) {
          while (true) {
            kotlinx.coroutines.delay(1000)
            nowMillis = System.currentTimeMillis()
            val gone = approvalQueue.sweepExpired()
            if (gone.isNotEmpty()) queueVersion++
          }
        }
        // 读 version 触发重组
        @Suppress("UNUSED_EXPRESSION") queueVersion
        ApprovalScreen(
          items = pendingApprovalItems,
          nowMillis = nowMillis,
          onAllow = { id ->
            if (streamState != null) {
              if (onApprovalAllowRequest != null) {
                onApprovalAllowRequest(id)
              } else if (bioBlocked == null && onApprovalDecision(id, ApprovalDecision.Once.wire)) {
                //
                // 会退化成调用方自觉。
                decidedApprovals = decidedApprovals + id
                approvalActionError = null
              } else {
                approvalActionError = if (bioBlocked != null) {
                  "这台设备无法验证身份（${biometricCapability}），只能在桌面批准"
                } else {
                  "未连接桌面，审批没有发出去"
                }
              }
            } else {
              approvalQueue.decide(id, ApprovalQueue.Decision.AllowOnce)
              queueVersion++
            }
          },
          onDeny = { id ->
            if (streamState != null) {
              if (onApprovalDecision(id, ApprovalDecision.Deny.wire)) {
                decidedApprovals = decidedApprovals + id
                approvalActionError = null
              } else {
                approvalActionError = "未连接桌面，审批没有发出去"
              }
            } else {
              approvalQueue.decide(id, ApprovalQueue.Decision.Deny)
              queueVersion++
            }
          },
          onDenyAll = {
            if (streamState != null) {
              var failed = false
              pendingApprovalItems.forEach { item ->
                if (onApprovalDecision(item.id, ApprovalDecision.Deny.wire)) {
                  decidedApprovals = decidedApprovals + item.id
                } else {
                  failed = true
                }
              }
              approvalActionError = if (failed) "部分审批未发出：未连接桌面" else null
            } else {
              approvalQueue.denyAll()
              queueVersion++
            }
          },
          onBack = { route = Route.Workbench },
          biometricUnavailable = bioBlocked,
          biometricCapability = biometricCapability,
          actionError = approvalActionError ?: stream?.lastInvokeError,
        focusId = approvalFocus,
        )
      }
      Route.Works -> {
        LaunchedEffect(Unit) { onWorksOpened() }
        WorksScreen(
          works = liveWorks ?: works,
          online = online,
          onBack = { route = Route.Workbench },
          onOpenStage = { route = Route.Works },
          onOpenProduct = { product ->
            if (onRequestPreview != null && product.workId.isNotBlank()) {
              onRequestPreview(product.workId, product.id, product.label)
            }
          },
        )
      }
      Route.Swarm -> {
        val shownSwarm = liveSwarm ?: swarm
        val canIntervene = onSwarmCommand != null &&
          liveSwarm != null &&
          liveSwarm.projectId != null &&
          liveSwarm.sessionId != null

        fun commandFor(index: Int): SwarmCommand.Stop? {
          val member = shownSwarm.members.firstOrNull { it.index == index } ?: return null
          val taskId = member.taskId ?: return null
          val projectId = shownSwarm.projectId ?: return null
          val sessionId = shownSwarm.sessionId ?: return null
          return SwarmCommand.Stop(projectId, sessionId, taskId, index)
        }

        SwarmScreen(
          state = shownSwarm,
          actionsEnabled = canIntervene,
          onBack = { route = Route.Thread },
          onAction = { index, action ->
            val member = shownSwarm.members.firstOrNull { it.index == index }
            if (canIntervene && member?.taskId != null) {
              val base = commandFor(index)
              if (base != null) {
                when (action) {
                  MemberAction.Stop -> onSwarmCommand?.invoke(base)
                  MemberAction.Retry -> onSwarmCommand?.invoke(
                    SwarmCommand.Retry(base.projectId, base.sessionId, base.taskId, index)
                  )
                  else -> Unit
                }
              }
            } else if (liveSwarm == null) {
              swarm = SwarmReducer.intervene(
                swarm, index, action, nowMillis = System.currentTimeMillis(),
              ).first
            }
          },
          onInstruction = if (canIntervene && onSwarmCommand != null) {
            { index, text ->
              val member = shownSwarm.members.firstOrNull { it.index == index }
              val taskId = member?.taskId
              val projectId = shownSwarm.projectId
              val sessionId = shownSwarm.sessionId
              if (taskId != null && projectId != null && sessionId != null) {
                onSwarmCommand.invoke(SwarmCommand.Instruction(projectId, sessionId, taskId, index, text))
              }
            }
          } else null,
          onConcurrencyChange = { limit -> swarm = swarm.copy(concurrencyLimit = limit) },
          onStuckAlertToggle = { on -> swarm = swarm.copy(stuckAlertEnabled = on) },
          onStopAll = {
            if (canIntervene) {
              shownSwarm.members.forEach { m ->
                if (actionsFor(m).contains(MemberAction.Stop)) commandFor(m.index)?.let { onSwarmCommand?.invoke(it) }
              }
            } else if (liveSwarm == null) {
              // 停止全部 = 对每个可停止的成员下发停止。
              val now = System.currentTimeMillis()
              swarm.members.forEach { m ->
                if (actionsFor(m).contains(MemberAction.Stop)) {
                  swarm = SwarmReducer.intervene(swarm, m.index, MemberAction.Stop, now).first
                }
              }
            }
          },
        )
      }
      }
      preview?.let { shown ->
        PreviewScreen(state = shown, onClose = onClosePreview)
      }
    }
  }
}

/** 当前支持的页面。C3–C5 落地时在这里加。 */
private enum class Route { Workbench, Thread, Sessions, Swarm, Approvals, Works, Pairing, Settings }

@Composable
private fun ThreadRoute(
  state: ThreadState,
  onComposerChange: (ComposerState) -> Unit,
  onBack: () -> Unit,
  swarmMembers: Int,
  onOpenSwarm: () -> Unit,
  onSend: (SendRequest) -> Unit,
  sendError: String? = null,
  /** 打开会话列表。null = 没有事件流，不渲染入口 */
  onOpenSessions: (() -> Unit)? = null,
  /** 停止当前轮次。唤起方负责判断「有没有真的停掉」 */
  onStopRun: (() -> Unit)? = null,
  workspaceFiles: List<WorkspaceFile> = emptyList(),
) {
  val files: List<WorkspaceFile> = workspaceFiles

  ThreadScreen(
    state = state,
    onBack = onBack,
    sendError = sendError,
    onOpenSessions = onOpenSessions,
    onComposerTextChange = { text ->
      onComposerChange(ThreadReducer.onDraftChange(state.composer, text, files))
    },
    onModeChange = { mode -> onComposerChange(state.composer.copy(mode = mode)) },
    onMentionSelect = { file -> onComposerChange(ThreadReducer.selectMention(state.composer, file)) },
    onMentionDismiss = { onComposerChange(ThreadReducer.dismissMention(state.composer)) },
    onMentionMove = { delta -> onComposerChange(ThreadReducer.moveMentionSelection(state.composer, delta)) },
    onMentionConfirm = {
      val pick = state.composer.mentionResults.getOrNull(state.composer.mentionSelected)
      if (pick != null) {
        onComposerChange(ThreadReducer.selectMention(state.composer, pick))
      }
    },
    onSend = {
      ThreadReducer.onSend(state.composer)?.let(onSend)
    },
    swarmMembers = swarmMembers,
    onOpenSwarm = onOpenSwarm,
    onStop = {
      if (onStopRun != null) {
        onStopRun.invoke()
      } else {
        onComposerChange(state.composer.copy(generating = false))
      }
    },
  )
}

@Composable
private fun PairingRoute(
  state: studio.morrow.android.PairingFlow.State,
  nowMillis: Long,
  pairRequest: String?,
  onCopyPairRequest: (String) -> Unit,
  onPasteTicket: (String) -> Unit,
  onSubmitCode: (String) -> Unit,
  onContinueToCode: () -> Unit,
  onRetry: () -> Unit,
  onCancel: () -> Unit,
) {
  PairingScreen(
    state = state,
    nowMillis = nowMillis,
    pairRequest = pairRequest,
    onCopyPairRequest = onCopyPairRequest,
    onPasteTicket = onPasteTicket,
    onSubmitCode = onSubmitCode,
    onContinueToCode = onContinueToCode,
    onRetry = onRetry,
    onCancel = onCancel,
  )
}

@Composable
private fun WorkbenchRoute(
  onOpenThread: () -> Unit,
  approvals: PendingApprovals? = null,
  onOpenApprovals: () -> Unit = {},
  onOpenWorks: () -> Unit = {},
  /** 进入设置页。 */
  onOpenSettings: () -> Unit = {},
  /** 作品库是否有作品（决定空成品时是否仍显示「去作品页」入口） */
  hasWorks: Boolean = false,
  artifacts: List<Artifact> = emptyList(),
  projected: WorkbenchData? = null,
  relayConnection: Connection? = null,
  /** 进入配对页。只在未配对时渲染入口 */
  onOpenPairing: () -> Unit = {},
  /** F-20：首连后快照还没到。为 true 时工位页显示「正在同步」。 */
  syncingFirstSnapshot: Boolean = false,
) {
  val context = LocalContext.current
  val state = ForegroundSyncState.current()
  val isOnline = isRelayOnline(relayConnection, state)
  val running = (state as? SyncState.Running)?.let {
    RunningTask(
      taskId = it.taskId,
      title = null,
      stage = null,
      step = it.step,
      totalSteps = it.totalSteps,
      startedAtMillis = it.startedAtMillis
    )
  }
  // 「补拉中」有两个来源，都要看：
  val inFlight = MainActivity.pendingPullRequest.value
  var pendingPull by remember { mutableStateOf(false) }
  LaunchedEffect(state, inFlight) {
    pendingPull = inFlight != null || PendingPull.isPending(context)
  }

  val connection = relayConnection ?: Connection(
    phase = if (isOnline) ConnectionPhase.Online else ConnectionPhase.Unpaired
  )

  WorkbenchScreen(
    data = projected?.copy(
      // （事件要等桌面推，前台服务本地就知道）
      running = running ?: projected.running,
      approvals = approvals ?: projected.approvals,
      syncingFirstSnapshot = syncingFirstSnapshot,
    ) ?: WorkbenchData(
      connection = connection,
      running = running,
      approvals = approvals,
      today = null,
      artifacts = artifacts,
      syncedAtMillis = null,
      pendingPull = pendingPull,
      syncingFirstSnapshot = syncingFirstSnapshot
    ),
    onStopTask = { ForegroundSyncService.stop(context) },
    onOpenThread = onOpenThread,
    onOpenApprovals = onOpenApprovals,
    onOpenWorks = onOpenWorks,
    hasWorks = hasWorks,
    onOpenSettings = onOpenSettings,
    onOpenPairing = onOpenPairing
  )
}

internal fun isRelayOnline(
  connection: Connection?,
  syncState: SyncState,
): Boolean = connection?.phase == ConnectionPhase.Online ||
  (connection == null && syncState is SyncState.Running)

enum class WidthClass {
  /** < 600dp：手机竖屏，单列 */
  Compact,

  /** 600–840dp：折叠屏展开 / 横屏，双列 */
  Medium,

  /** > 840dp：平板，三列 */
  Expanded
}

/** 当前设备形态的判定结果。页面据此决定列数与留白，不再自己算。 */
data class AdaptiveSpec(
  val widthClass: WidthClass,
  /** 可用高度小于 640dp → 短屏：压缩英雄区、隐藏装饰性地平线 */
  val isShortScreen: Boolean,
  /** 系统字体是否已放大到需要降级（> 1.3×）：并排 chip 改换行流式，不截断 */
  val isLargeFont: Boolean,
  /** 内容栏最大宽度：超宽屏居中，不把卡片拉成横幅 */
  val contentMaxWidth: Dp,
  /** 列数，与 [widthClass] 对应 */
  val columns: Int
) {
  /** 页面可用「是否单列」直接分支，避免到处写 `== WidthClass.Compact` */
  val isSingleColumn: Boolean get() = columns == 1
}

object Adaptive {

  /** 蓝图规定的三档边界 */
  const val COMPACT_MAX_DP = 600f
  const val MEDIUM_MAX_DP = 840f

  /** 短屏阈值：低于此高度压缩英雄区、隐藏装饰地平线 */
  const val SHORT_SCREEN_MAX_DP = 640f

  /** 字体缩放降级阈值：超过 1.3× 时并排 chip 必须改换行流式 */
  const val LARGE_FONT_THRESHOLD = 1.3f

  /** 超宽屏内容栏上限：蓝图要求 max-width 720dp 居中 */
  val CONTENT_MAX_WIDTH: Dp = 720.dp

  fun widthClassOf(widthDp: Float): WidthClass = when {
    widthDp < COMPACT_MAX_DP -> WidthClass.Compact
    widthDp <= MEDIUM_MAX_DP -> WidthClass.Medium
    else -> WidthClass.Expanded
  }

  fun specOf(widthDp: Float, heightDp: Float, fontScale: Float): AdaptiveSpec {
    val wc = widthClassOf(widthDp)
    return AdaptiveSpec(
      widthClass = wc,
      isShortScreen = heightDp < SHORT_SCREEN_MAX_DP,
      isLargeFont = fontScale > LARGE_FONT_THRESHOLD,
      contentMaxWidth = when (wc) {
        WidthClass.Compact -> Dp.Infinity
        WidthClass.Medium -> 640.dp
        WidthClass.Expanded -> CONTENT_MAX_WIDTH
      },
      columns = when (wc) {
        WidthClass.Compact -> 1
        WidthClass.Medium -> 2
        WidthClass.Expanded -> 3
      }
    )
  }
}

/** 页面读取自适应的唯一入口 */
val LocalAdaptive = compositionLocalOf {
  AdaptiveSpec(
    widthClass = WidthClass.Compact,
    isShortScreen = false,
    isLargeFont = false,
    contentMaxWidth = Dp.Infinity,
    columns = 1
  )
}

/** 从 Composition 的 Configuration 算当前 Spec（页面也可直接用这个判断） */
@Composable
fun rememberAdaptiveSpec(): AdaptiveSpec {
  val cfg = LocalConfiguration.current
  val density = LocalDensity.current
  return Adaptive.specOf(
    widthDp = cfg.screenWidthDp.toFloat(),
    heightDp = cfg.screenHeightDp.toFloat(),
    fontScale = cfg.fontScale
  )
}

@Composable
fun AdaptiveScaffold(
  modifier: Modifier = Modifier,
  background: @Composable BoxScope.() -> Unit = {},
  content: @Composable BoxScope.() -> Unit
) {
  val spec = rememberAdaptiveSpec()
  MorrowScaffold(modifier = modifier, background = background) {
    CompositionLocalProvider(LocalAdaptive provides spec) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (spec.contentMaxWidth == Dp.Infinity) {
          content()
        } else {
          Box(Modifier.fillMaxSize().widthIn(max = spec.contentMaxWidth), contentAlignment = Alignment.TopCenter) {
            content()
          }
        }
      }
    }
  }
}

@Composable
fun AdaptiveColumns(
  spec: AdaptiveSpec,
  modifier: Modifier = Modifier,
  items: List<@Composable () -> Unit>
) {
  if (spec.columns <= 1) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      items.forEach { it() }
    }
    return
  }
  val columns = List(spec.columns) { mutableListOf<@Composable () -> Unit>() }
  items.forEachIndexed { i, item -> columns[i % spec.columns].add(item) }
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    columns.forEach { bucket ->
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        bucket.forEach { it() }
      }
    }
  }
}

object MorrowInsets {

  /** 手势条额外留白（蓝图规定 8dp） */
  val GestureBarExtra: Dp = 8.dp

  @Composable
  internal fun contentPadding(): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    val status = WindowInsets.statusBars.asPaddingValues()
    val nav = WindowInsets.navigationBars.asPaddingValues()
    val ime = WindowInsets.ime.asPaddingValues()
    val navBottom = nav.calculateBottomPadding()
    val imeBottom = ime.calculateBottomPadding()
    return PaddingValues(
      start = maxOf(
        status.calculateStartPadding(layoutDirection),
        nav.calculateStartPadding(layoutDirection)
      ),
      top = status.calculateTopPadding(),
      end = maxOf(
        status.calculateEndPadding(layoutDirection),
        nav.calculateEndPadding(layoutDirection)
      ),
      // 键盘可见时贴键盘；否则导航栏 + 手势条留白
      bottom = maxOf(navBottom + GestureBarExtra, imeBottom)
    )
  }
}

/** 供页面读取「内容区应该内缩多少」，避免各页自己算一遍 */
val LocalContentInsets = compositionLocalOf { PaddingValues(0.dp) }

@Composable
fun MorrowScaffold(
  modifier: Modifier = Modifier,
  background: @Composable BoxScope.() -> Unit = {},
  content: @Composable BoxScope.() -> Unit
) {
  ApplySystemBars()
  val padding = MorrowInsets.contentPadding()
  Box(modifier = modifier.fillMaxSize()) {
    background()
    CompositionLocalProvider(LocalContentInsets provides padding) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding)
      ) {
        content()
      }
    }
  }
}

object SystemBars {

  fun useDarkIcons(background: Color): Boolean = background.luminance() > 0.5f

  /** 应用到当前 window。Activity 与 Compose 层共用同一套判断。 */
  fun apply(activity: Activity, useDarkIcons: Boolean) {
    val window = activity.window
    window.isNavigationBarContrastEnforced = false
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = useDarkIcons
    controller.isAppearanceLightNavigationBars = useDarkIcons
  }
}

@Composable
fun ApplySystemBars() {
  val view = LocalView.current
  val darkIcons = SystemBars.useDarkIcons(MorrowTheme.bg)
  SideEffect {
    val activity = view.context.findActivity() ?: return@SideEffect
    SystemBars.apply(activity, darkIcons)
  }
}

/** 沿 ContextWrapper 链向上找 Activity；找不到返回 null（如 Compose 预览环境） */
internal tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is android.content.ContextWrapper -> baseContext.findActivity()
  else -> null
}

data class MobileAdjustments(
  val bg: Color?,
  val surface: Color?,
  val patina: Color,
  val rust: Color,
  val amber: Color,
  val mauve: Color,
  val text2: Color
)

/** 墨夜：C1 采用文档给出的确定值；C3 按规则计算（脚本可复算） */
val InkMobile = MobileAdjustments(
  bg = Color(0xFF0E1116),
  surface = Color(0xFF171D27),
  patina = Color(0xFF90A099),
  rust = Color(0xFFBE8672),
  amber = Color(0xFFCBA734),
  mauve = Color(0xFFA39FA7),
  text2 = Color(0xFFBCB6A9)
)

/** 素纸：只做 C3；C1 的理由（户外纯黑发灰）不适用浅色主题，故不覆盖 */
val PaperMobile = MobileAdjustments(
  bg = null,
  surface = null,
  patina = Color(0xFF5E736A),
  rust = Color(0xFFA35F4D),
  amber = Color(0xFFA0811B),
  mauve = Color(0xFF79747F),
  text2 = Color(0xFF48443C)
)

/** 取实际渲染用的底色（已应用 C1） */
fun MorrowPalette.mobileBg(m: MobileAdjustments): Color = m.bg ?: bg

/** 取实际渲染用的卡片底色（已应用 C1） */
fun MorrowPalette.mobileSurface(m: MobileAdjustments): Color = m.surface ?: surface

/** 取实际渲染用的次级正文色（已应用 7:1 提亮） */
fun MorrowPalette.mobileText2(m: MobileAdjustments): Color = m.text2

private fun materialScheme(p: MorrowPalette, m: MobileAdjustments, dark: Boolean) =
  if (dark) {
    darkColorScheme(
      primary = p.gold,
      onPrimary = p.textInvert,
      secondary = m.patina,
      background = p.mobileBg(m),
      onBackground = p.text,
      surface = p.mobileSurface(m),
      onSurface = p.text,
      surfaceVariant = p.bgElev,
      onSurfaceVariant = m.text2,
      outline = p.lineStrong,
      error = m.rust
    )
  } else {
    lightColorScheme(
      primary = p.gold,
      onPrimary = p.textInvert,
      secondary = m.patina,
      background = p.mobileBg(m),
      onBackground = p.text,
      surface = p.mobileSurface(m),
      onSurface = p.text,
      surfaceVariant = p.bgElev,
      onSurfaceVariant = m.text2,
      outline = p.lineStrong,
      error = m.rust
    )
  }

private val MorrowInk = materialScheme(InkPalette, InkMobile, dark = true)
private val MorrowPaper = materialScheme(PaperPalette, PaperMobile, dark = false)

private val MorrowTypography = androidx.compose.material3.Typography().let { base ->
  base.copy(
    displayLarge = TextStyle(
      fontSize = MorrowType.display, lineHeight = (MorrowType.display.value * MorrowType.lhTight).sp,
      fontWeight = FontWeight.Normal, letterSpacing = (-0.6).sp
    ),
    titleLarge = TextStyle(
      fontSize = MorrowType.title, lineHeight = (MorrowType.title.value * MorrowType.lhTitle).sp,
      fontWeight = FontWeight.Medium
    ),
    titleMedium = TextStyle(
      fontSize = MorrowType.h2, lineHeight = (MorrowType.h2.value * MorrowType.lhTitle).sp,
      fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(
      fontSize = MorrowType.bodyLg, lineHeight = (MorrowType.bodyLg.value * MorrowType.lhCopy).sp,
      fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
      fontSize = MorrowType.body, lineHeight = (MorrowType.body.value * MorrowType.lhCopy).sp
    ),
    bodySmall = TextStyle(
      fontSize = MorrowType.ui, lineHeight = (MorrowType.ui.value * MorrowType.lhUi).sp
    ),
    labelMedium = TextStyle(
      fontSize = MorrowType.base, lineHeight = (MorrowType.base.value * MorrowType.lhUi).sp,
      fontWeight = FontWeight.Medium
    ),
    labelSmall = TextStyle(
      fontSize = MorrowType.xs, lineHeight = (MorrowType.xs.value * MorrowType.lhUi).sp,
      fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp
    )
  )
}

private val MorrowShapes = androidx.compose.material3.Shapes(
  extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(MorrowRadius.xs),
  small = androidx.compose.foundation.shape.RoundedCornerShape(MorrowRadius.control),
  medium = androidx.compose.foundation.shape.RoundedCornerShape(MorrowRadius.md),
  large = androidx.compose.foundation.shape.RoundedCornerShape(MorrowRadius.card),
  extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(MorrowRadius.xl)
)

enum class MorrowThemeMode { Ink, Paper }

/** 当前主题的移动端覆盖值，页面需要取真实底色时用它，而不是直接用桌面原值 */
val LocalMorrowMobile = staticCompositionLocalOf { InkMobile }
val LocalMorrowPalette = staticCompositionLocalOf { InkPalette }

@Composable
fun MorrowTheme(
  mode: MorrowThemeMode = MorrowThemeMode.Ink,
  content: @Composable () -> Unit
) {
  val palette = when (mode) {
    MorrowThemeMode.Ink -> InkPalette
    MorrowThemeMode.Paper -> PaperPalette
  }
  val mobile = when (mode) {
    MorrowThemeMode.Ink -> InkMobile
    MorrowThemeMode.Paper -> PaperMobile
  }
  val scheme = when (mode) {
    MorrowThemeMode.Ink -> MorrowInk
    MorrowThemeMode.Paper -> MorrowPaper
  }
  CompositionLocalProvider(
    LocalMorrowPalette provides palette,
    LocalMorrowMobile provides mobile
  ) {
    MaterialTheme(
      colorScheme = scheme,
      typography = MorrowTypography,
      shapes = MorrowShapes,
      content = content
    )
  }
}

internal val ComposerStateSaver = listSaver<ComposerState, Any>(
  save = { listOf(it.draft, it.mode.name) },
  restore = { saved ->
    val draft = saved.getOrNull(0) as? String ?: ""
    ComposerState(
      draft = draft,
      mode = (saved.getOrNull(1) as? String)
        ?.let { name -> ComposerMode.entries.firstOrNull { it.name == name } }
        ?: ComposerMode.Work,
      // 而不是把存档时的旧布尔一起搬回来。
      canSend = draft.isNotBlank(),
    )
  },
)

object MorrowTheme {
  val palette: MorrowPalette
    @Composable @ReadOnlyComposable get() = LocalMorrowPalette.current

  /** 已应用 C1/C3 的移动端覆盖值 */
  val mobile: MobileAdjustments
    @Composable @ReadOnlyComposable get() = LocalMorrowMobile.current

  /** 实际渲染用的页面底色（C1 已生效） */
  val bg: androidx.compose.ui.graphics.Color
    @Composable @ReadOnlyComposable get() = palette.mobileBg(mobile)

  /** 实际渲染用的卡片底色（C1 已生效） */
  val surface: androidx.compose.ui.graphics.Color
    @Composable @ReadOnlyComposable get() = palette.mobileSurface(mobile)

  /** 实际渲染用的次级正文色（已提亮到 ≥7:1） */
  val text2: androidx.compose.ui.graphics.Color
    @Composable @ReadOnlyComposable get() = palette.mobileText2(mobile)

  /** 实际渲染用的提示文字色（元信息，≥4.5:1） */
  val hint: androidx.compose.ui.graphics.Color
    @Composable @ReadOnlyComposable get() = palette.hint
}
