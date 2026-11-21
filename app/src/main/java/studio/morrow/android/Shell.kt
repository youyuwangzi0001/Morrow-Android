package studio.morrow.android

/**
 * 界面外壳域：根导航、页面装配、分辨率适配层、insets 体系与主题装配。
 *
 * 这一层决定「一屏能放多少」，改动会同时影响所有页面：适配档位、脚手架留白
 * 与主题色必须在同一处对齐，否则短屏上会出现某页贴边、某页被挤出内容。
 */
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

// ── 原 ui/MorrowApp.kt ──

/**
 * 应用外壳。
 * 五页里只有两页存在，引入 Navigation Compose 会带来
 * 「路由字符串 + 参数序列化 + 返回栈」三套概念，而当前只有两个页面。
 */
@Composable
fun MorrowApp(
  /**
   * 初始协作状态。生产传 null（D 轨落地后由事件流填充）；
   */
  initialSwarm: SwarmState? = null,
  /**
   * 审批队列。生产由 D 轨事件流填（桌面推来 permission_requested）；
   * 测试传一个填好的队列，用来验证「审批页真的到得了、按下去真的下发」。
   *
   * 与 [initialSwarm] 同一个理由：页面存在性必须有一条测试证明它到得了。
   */
  approvals: ApprovalQueue? = null,
  /**
   * relay 真实连接状态（E1b）。
   */
  relayConnection: Connection? = null,
  /**
   * 配对流程状态（E2b）。null = 不提供配对入口。
   *
   * 只在未配对时被读到。传 null 时工位页不渲染「去配对」——
   * C 轨的 137 项渲染测试都是这个前提，它们不需要知道配对的存在。
   */
  pairingState: studio.morrow.android.PairingFlow.State? = null,
  /**
   * 本机的配对请求文本（`MORROWPAIR1.<base64url>`）。
   *
   * 桌面签发票据必须拿到手机公钥，否则签出来的票「给的是另一台手机」，
   * 手机侧会拒绝。传 null 时不渲染这一步（旧测试全都不受影响）。
   */
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
  /**
   * 作品库。生产由 D 轨事件流填充；测试注入样本。
   *
   * 与 [initialSwarm] / [approvals] 同一个理由：页面存在性必须有一条测试
   * 证明它到得了 —— C3 的成员协作页曾经「渲染测试全绿但页面进不去」。
   */
  works: List<Work> = emptyList(),
  /**
   * 进入作品页时的刷新回调（生产 = 发 `works.get`）。
   *
   * 作品库不在状态帧里，靠按需拉取；用户重进页面就该看到最新摘要。
   */
  onWorksOpened: () -> Unit = {},
  /**
   * 通知点击带来的落点。null = 正常启动。
   *
   * 由 MainActivity 从 `intent.data` 解析；消费后必须回调
   * [onDeepLinkHandled] 清空，否则每次重组都会把用户拽回通知目标页。
   */
  deepLink: MorrowDestination? = null,
  onDeepLinkHandled: () -> Unit = {},
  /**
   * 「最近成品」的样本数据。生产由 D 轨事件流填充。
   *
   * 加这个参数是因为「去作品页」的入口挂在成品块里 —— 成品为空时
   * 那一块显示空态且没有入口，测试就没法走真实入口验证作品页到得了。
   */
  initialArtifacts: List<Artifact> = emptyList(),
  /**
   * 事件流（D 轨）。生产由 Activity 持有并喂 raw 事件；测试可注入。
   * 让 20 项 C1 测试全红」更安全，且切换点只有一处，D 轨后续可以直接删掉旧参数。
   */
  stream: EventStreamViewModel? = null,
  /**
   * 审批决定下发（`approval.decide`）。返回 true = 已交给 relay 发出。
   *
   * 为什么是布尔返回：未连接时**不能假装成功**（按钮消失、下一轮事件
   * 永远不来）。调用方据此决定显示「未连接桌面」而不是乐观移除。
   */
  onApprovalDecision: (id: String, decision: String) -> Boolean = { _, _ -> false },
  /**
   * 生产环境的「允许」前置流程：弹生物识别，成功才下发 once。
   *
   * null = 不启用闸门（测试/DEMO 直接下发，保持既有断言不变）。
   * 生产必须传：否则「允许」会绕过指纹/面容直接授权。
   */
  onApprovalAllowRequest: ((id: String) -> Unit)? = null,

  /** 当前主题（设置页可切换）。 */
  themeMode: MorrowThemeMode = MorrowThemeMode.Ink,
  onThemeChange: (MorrowThemeMode) -> Unit = {},
  /** 设置页「清除配对」：删凭据、停连接、重新进入未配对。 */
  onClearPairing: () -> Unit = {},
  /** 系统通知总开关（用于设置页状态文案与跳转）。 */
  notificationsEnabled: Boolean = true,
  onOpenNotificationSettings: () -> Unit = {},

  /** 成片预览的当前状态（null = 没有打开预览）。 */
  preview: PreviewState? = null,
  /**
   * 请求从桌面取一件成片的字节（`works.preview`）。
   *
   * null = 不支持预览（测试/演示）：点击产物行只保留行本身，不跳转。
   */
  onRequestPreview: ((workId: String, productId: String, title: String) -> Unit)? = null,
  onClosePreview: () -> Unit = {},
  /**
   * 协作干预上行。null = 只读（测试/旧版桌面）。
   *
   * 只有拿到 projectId/sessionId/taskId 三件套时界面才放开按钮：
   * 缺一个就发不出去，可点的按钮只会骗用户。
   */
  onSwarmCommand: ((SwarmCommand) -> Unit)? = null,
  /**
   * 对话发送（`message.send`）。
   *
   * 回调形（不是返回 Boolean）是因为「发出去」与「桌面受理」是两件事：
   * socket 写成功不代表桌面收下了。真正的结果由桌面 `result` 帧决定，
   * 回调 (ok, error) 里 ok 为 false 时界面要保留草稿并说明原因。
   */
  onSendMessage: (sessionId: String, text: String, onResult: (Boolean, String?) -> Unit) -> Unit =
    { _, _, onResult -> onResult(false, "未连接桌面") },
) {
  // 读一次 state 让 Compose 订阅；stream 为 null 时不订阅任何东西
  val streamState = stream?.state
  // 「工位页算不算在线」只有一个判定入口。
  //
  // 之前这条规则写在两处（投影处 + 渲染处），各自都「照抄同一条」——
  // 但两处独立就一定会漂移：改一处忘了另一处，表现是
  // 「今日统计按未连接算成空态、头部却显示在线」这种自相矛盾的界面。
  //
  // 判定顺序：有 relay 状态就用它（那是「与桌面连通」的唯一真相），
  // 否则退回前台服务状态。两者都在算「在线」时以 relay 为准 ——
  // 前台服务可能在跑但连接已经断了，那时说「在线」会让用户以为桌面收得到指令。
  val online = relayConnection?.phase == ConnectionPhase.Online ||
    (relayConnection == null && ForegroundSyncState.current() is SyncState.Running)
  val projected: WorkbenchData? = streamState?.let { st ->
    WorkbenchProjection.project(
      state = st,
      connection = relayConnection ?: Connection(
        phase = if (online) ConnectionPhase.Online else ConnectionPhase.Unpaired,
      ),
    )
  }
  val p = MorrowTheme.palette
  // 用 MorrowTheme.bg（已应用 C1 抬底），不用 palette.bg（桌面原值），
  // 否则令牌层抬了底、界面还是旧底色，两处不一致。
  val pageBg = MorrowTheme.bg

  var route by remember { mutableStateOf(Route.Workbench) }
  var composer by remember { mutableStateOf(ComposerState()) }
  var thread by remember { mutableStateOf(ThreadState(title = "", subtitle = "")) }
  // 协作状态。真实数据由 D 轨事件流填充；现在为空 —— 页面会显示
  // 「还没有协作成员」，而不是编几个假成员出来。
  var swarm by remember {
    mutableStateOf(initialSwarm ?: SwarmState(goal = "", planVersion = "v1"))
  }
  // 审批队列：生产里由 D 轨事件流填充；这里没有来源时用一个空队列，
  // 页面显示「没有待你批准的请求」，而不是编几条假审批。
  //
  // queueVersion 是**重组触发器**：ApprovalQueue 是普通类（不是 Compose 状态），
  // 它内部变了不会让 Compose 重组。所有会改队列的操作都要顺手 version++，
  // 否则会出现「点了拒绝，队列里没了，界面还在」——
  // 这个坑 C2 用 @Volatile、C3 用普通 var 各踩过一次，这是第三次，写成显式计数。
  val approvalQueue = remember(approvals) { approvals ?: ApprovalQueue({ _, _, _ -> true }) }
  var queueVersion by remember { mutableStateOf(0) }
  // 每秒一 tick：倒计时与「到点自动失效」都靠它。只在审批页存在时跑，
  // 不在工位页也开一个每秒定时器白耗电。
  var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
  val bioBlocked = when (val v = BiometricGate.gate(biometricCapability)) {
    is BiometricGate.Verdict.DenyOnly -> v.why
    is BiometricGate.Verdict.Proceed -> null
  }

  // ── 事件流 → 页面数据（生产路径） ──
  // 已下发成功的审批先从界面移除：桌面执行后才会回 `permission_resolved`，
  // 不乐观移除的话用户会在这几百毫秒里重复点击同一张卡。
  var decidedApprovals by remember { mutableStateOf(setOf<String>()) }
  var approvalActionError by remember { mutableStateOf<String?>(null) }
  val streamApprovals = streamState?.let { ApprovalProjection.fromState(it, nowMillis) } ?: emptyList()
  // 在**函数体**读取版本号：ApprovalQueue 是普通类，内部变化不会触发重组。
        // 只在分支内读的话，重组只发生在外层 content lambda，而
        // pendingApprovalItems 是在函数体算好的——会被捕获成旧列表
        // （实测：点了拒绝，队列剩 1 条，审批页仍显示 2 条）。
        @Suppress("UNUSED_EXPRESSION") queueVersion
        val pendingApprovalItems: List<ApprovalItem> = if (streamState != null) {
          streamApprovals.filterNot { it.id in decidedApprovals }
        } else {
          approvalQueue.pending
        }

  // 对话页：有事件流时用真实会话；没有时保留 C2 的本地空态（测试注入）。
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
            text = message.text,
            streaming = message.streaming,
            // 工具调用、计划、产物与失败态都要跟着消息走：
            // 只映射 text 会让手机退化成「只能读文字的聊天窗」——
            // 用户在电脑上执行了什么命令、改了哪些文件，手机上一概看不见。
            toolGroup = ToolProjection.groupOf(message.id, message.tools),
            plan = st.plan?.let { plan ->
              // 计划卡只挂在**会话最后一条**助手消息上：桌面一次运行只有一个计划，
              // 挂到每条消息会变成重复卡片。
              if (message.id == session.messages.lastOrNull { it.role == studio.morrow.android.MessageRole.Assistant }?.id) {
                PlanCard(
                  id = "plan-${session.id}",
                  title = plan.title,
                  steps = emptyList(),
                  approved = !plan.planMode,
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

  // 协作页：有事件流时用真实成员；没有时保留 C3 的本地样本/空态。
  // 并发上限、卡住提醒是纯客户端设置，从本地 base 继承。
  val liveSwarm: SwarmState? = streamState?.let { SwarmProjection.fromState(it, swarm) }

  // 作品库：桌面摘要按需拉取。null = 还没拉到 → 保留注入的样本/空态，
  // 页面文案由 online 区分「未知」与「没有」。
  val liveWorks: List<Work>? = streamState?.let { WorksProjection.from(it.works) }

  // 通知深链：审批直接进审批页；「进展」落到对应会话的对话页。
  // 冷启动时会话可能还没到（快照未应用），先记下 focus，等会话出现再切。
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
      // 背景层不吃 inset：这是「无边框」成立的判据，画到屏幕最边缘
      Box(
        Modifier
          .fillMaxSize()
          .drawBehind {
            drawRect(pageBg)
            // 右上角一枚极淡的金光：桌面版是同一个位置、同一个色温。
            // alpha 压在 0.14 以下，保证不会与卡片底色争注意力。
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
        // 工位徽标与审批页**同源**：两处都用 pendingApprovalItems，
        // 避免「徽标说 3 条、点进去 1 条」这种自相矛盾。
        // 已过期的不计入 —— 它们点不了，算进去只会让用户白点一次。
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
          // 作品库入口独立于「最近成品」：作品存在但最近没有成品时也要能进去
          hasWorks = (liveWorks ?: works).isNotEmpty(),
          onOpenSettings = { route = Route.Settings },
          artifacts = initialArtifacts,
          // 只有未配对时工位页才渲染这个入口（见 ConnectionHeader）。
          // 这里不看 pairingState 是否为 null —— 若为 null 说明调用方
          // 根本不支持配对，入口也不会显示，点了也不会到这。
          onOpenPairing = { route = Route.Pairing },
        )
      }
      Route.Settings -> {
        val pairedState = pairingState as? studio.morrow.android.PairingFlow.State.Paired
        SettingsScreen(
          themeMode = themeMode,
          paired = pairedState != null,
          deviceName = pairedState?.deviceId,
          notificationsEnabled = notificationsEnabled,
          biometric = biometricCapability,
          versionName = studio.morrow.android.BuildConfig.VERSION_NAME,
          onThemeChange = onThemeChange,
          onClearPairing = onClearPairing,
          onOpenNotificationSettings = onOpenNotificationSettings,
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
        // 配对页那句「正在回到工位…」原先没有任何代码去实现它：
        // 用户看到「配对成功」后再也不动，只能自己按页面上的「取消」——
        // 而那是 `onPairingCancel()`，会把刚配好的流程 reset 掉。
        //
        // 停 1.2 秒是刻意的：让「配对成功」这句话被看见，而不是闪一下就没了。
        LaunchedEffect(pairingState) {
          if (pairingState is studio.morrow.android.PairingFlow.State.Paired) {
            kotlinx.coroutines.delay(1200)
            // 只在仍停在配对页时才跳：用户可能已经自己离开了
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
          swarmMembers = streamState?.members?.size ?: swarm.members.size,
          onOpenSwarm = { route = Route.Swarm },
          sendError = threadSendError,
          onSend = { request ->
            val sessionId = streamState?.activeSessionId ?: streamState?.sessions?.keys?.firstOrNull()
            if (streamState != null && sessionId != null) {
              // 生产路径：真正下发 message.send。**桌面回执 ok 才清草稿** ——
              // 失败时保留用户输入，否则「发送失败」等于把内容吃掉了。
              composer = composer.copy(sending = true)
              onSendMessage(sessionId, request.text) { ok, error ->
                composer = if (ok) {
                  ThreadReducer.afterSend(composer)
                } else {
                  // 保住草稿的同时放开重发（sending 必须复位，否则按钮永远转圈）
                  composer.copy(sending = false, canSend = request.text.isNotBlank())
                }
                threadSendError = if (ok) null else (error ?: "桌面没有受理这条消息")
              }
            } else {
              // 没有事件流（测试 / C2 本地空态）：保留原来的本地回显。
              composer = ThreadReducer.afterSend(composer)
              thread = thread.copy(
                messages = thread.messages + ThreadMessage(
                  // 本地 id 要避免和桌面事件 id 撞车。服务端 id 由事件带下来，
                  // 这里用 `local-` 前缀 + 递增序号，撞不上。
                  id = "local-${thread.messages.size}",
                  author = Author.User,
                  text = request.text,
                )
              )
            }
          },
        )
      }
      Route.Approvals -> {
        // 每秒 tick：倒计时与超时失效都要它。到点后 sweep 真的把条目删掉，
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
                // 先过生物识别。成功 → once，取消/失败 → deny（取消即拒绝），
                // 由 MainActivity 的回调最终调用 onApprovalDecision。
                onApprovalAllowRequest(id)
              } else if (onApprovalDecision(id, "once")) {
                // 生产：交给 relay 下发。发送成功才乐观移除；桌面执行后
                // 还会回 `permission_resolved`（那时列表本来就没了）。
                decidedApprovals = decidedApprovals + id
                approvalActionError = null
              } else {
                approvalActionError = "未连接桌面，审批没有发出去"
              }
            } else {
              approvalQueue.decide(id, ApprovalQueue.Decision.AllowOnce)
              queueVersion++
            }
          },
          onDeny = { id ->
            if (streamState != null) {
              if (onApprovalDecision(id, "deny")) {
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
                if (onApprovalDecision(item.id, "deny")) {
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
          // 本地下发失败优先；否则显示桌面 result 帧里带回的原因
          // （典型：审批已超时，桌面回 not_found）。
          actionError = approvalActionError ?: stream?.lastInvokeError,
        focusId = approvalFocus,
        )
      }
      Route.Works -> {
        // 每次进入作品页拉一次最新摘要（桌面可能刚建了作品）。
        LaunchedEffect(Unit) { onWorksOpened() }
        WorksScreen(
          works = liveWorks ?: works,
          online = online,
          onBack = { route = Route.Workbench },
          onOpenStage = { route = Route.Works },
          onOpenProduct = { product ->
            // 预览需要作品 id + 产物 id 才能向桌面取件。
            if (onRequestPreview != null && product.workId.isNotBlank()) {
              onRequestPreview(product.workId, product.id, product.label)
            }
          },
        )
      }
      Route.Swarm -> {
        val shownSwarm = liveSwarm ?: swarm
        // 干预需要真实项目/会话/任务 id；缺任何一件就保持只读。
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
                  // 发指令/插话走输入框（SwarmScreen 里弹），这里不处理
                  else -> Unit
                }
              }
            } else if (liveSwarm == null) {
              // 纯本地演示数据：保留乐观更新，让既有 C3 测试行为不变。
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
              // 用 reduce 一样走 intervene，保证单成员与全体的状态变化语义一致。
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
      // 预览盖在作品页之上：关闭后回到原来的路由，不额外加一层返回栈。
      preview?.let { shown ->
        PreviewScreen(state = shown, onClose = onClosePreview)
      }
    }
  }
}

/** 当前支持的页面。C3–C5 落地时在这里加。 */
private enum class Route { Workbench, Thread, Swarm, Approvals, Works, Pairing, Settings }

/**
 * 对话页路由。
 */
@Composable
private fun ThreadRoute(
  state: ThreadState,
  onComposerChange: (ComposerState) -> Unit,
  onBack: () -> Unit,
  swarmMembers: Int,
  onOpenSwarm: () -> Unit,
  onSend: (SendRequest) -> Unit,
  sendError: String? = null,
) {
  // @ 面板的候选来自工作区文件列表（D 轨以后由 relay 提供）。
  // 当前给空列表：面板会显示「没有匹配的文件」，而不是列出几个不存在的路径。
  val files = emptyList<WorkspaceFile>()

  ThreadScreen(
    state = state,
    onBack = onBack,
    sendError = sendError,
    onComposerTextChange = { text ->
      onComposerChange(ThreadReducer.onDraftChange(state.composer, text, files))
    },
    onModeChange = { mode -> onComposerChange(state.composer.copy(mode = mode)) },
    onMentionSelect = { file -> onComposerChange(ThreadReducer.selectMention(state.composer, file)) },
    onMentionDismiss = { onComposerChange(ThreadReducer.dismissMention(state.composer)) },
    onSend = {
      ThreadReducer.onSend(state.composer)?.let(onSend)
    },
    swarmMembers = swarmMembers,
    onOpenSwarm = onOpenSwarm,
    onStop = {
      // 停止生成：当前没有真实的生成流可停（D 轨没落地）。
      // 这里明确做成「清掉生成态」而不是留空实现 ——
      // 留空会让按钮变成装饰（B 轨踩过），而清生成态至少让界面回到可发送状态。
      onComposerChange(state.composer.copy(generating = false))
    },
  )
}

/**
 * 工位页路由：把 B 轨状态翻成工位页数据。
 * 注意 `title` 传 null：`ForegroundSyncState` 只知道 taskId，
 */
/**
 * 配对页路由。
 * 若只在状态变化时重组，用户会看到倒计时停在「剩余 62 秒」不动，
 * 页面不自己算 —— 否则两处各算一遍，`>` 与 `>=` 迟早有一处写错。
 */
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
  /**
   * 事件流投影出的工位数据。null = 走 C1 当时的行为（今日统计空态 +
   * 用 [artifacts] 样本）。D 轨后续把审批与作品也切过来后，
   * [artifacts] 与 [approvals] 两个参数即可删掉。
   */
  projected: WorkbenchData? = null,
  /**
   * relay 真实连接状态。null = 只看前台服务（C1 的旧行为）。
   *
   * 传进来的理由与 [MorrowApp] 的同名参数一致：前台服务在跑 ≠ 与桌面连上了。
   */
  relayConnection: Connection? = null,
  /** 进入配对页。只在未配对时渲染入口 */
  onOpenPairing: () -> Unit = {},
) {
  val context = LocalContext.current
  val state = ForegroundSyncState.current()
  // 有 relay 状态就以它为准：它是「与桌面连通」的唯一真相。
  // 前台服务可能在跑但连接已断 —— 那时说「在线」会让用户以为桌面收得到指令。
  val isOnline = relayConnection?.phase == ConnectionPhase.Online ||
    (relayConnection == null && state is SyncState.Running)
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
  // 1. MainActivity.onResume 取走请求后发布到这里（这一次真的要补）；
  // 2. PendingPull 里还躺着标记（进程被杀后新起的进程，Activity 还没跑 onResume）。
  // 只看 (2) 会漏掉「刚取走正在补」的那一段时间，只看 (1) 会漏掉冷启动首帧。
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
      // 运行中任务仍用 B4 的前台服务状态：它比事件流更实时
      // （事件要等桌面推，前台服务本地就知道）
      running = running ?: projected.running,
      // 审批与作品仍走各自已测的入口，等 D 轨后续批次一起切
      approvals = approvals ?: projected.approvals,
    ) ?: WorkbenchData(
      connection = connection,
      running = running,
      approvals = approvals,
      today = null,
      artifacts = artifacts,
      syncedAtMillis = null,
      pendingPull = pendingPull
    ),
    // 「停止跟盯」是工位页唯一能按下去的控件，所以它必须真的有效。
    // 走 B4 已经做好并测过（3 项「生产入口」测试）的 stop 入口，
    // 不在这里自己拼 Intent —— 拼法只要和那边不一致（例如漏 action），
    // 服务会把它当成一个未知命令静默忽略，按钮就变成装饰。
    onStopTask = { ForegroundSyncService.stop(context) },
    onOpenThread = onOpenThread,
    onOpenApprovals = onOpenApprovals,
    onOpenWorks = onOpenWorks,
    // 作品库入口独立于「最近成品」：作品存在但最近没有成品时也要能进去
    hasWorks = hasWorks,
    onOpenSettings = onOpenSettings,
    onOpenPairing = onOpenPairing
  )
}
// ── 原 adaptive/AdaptiveScaffold.kt ──

/**
 * 分辨率适配层（蓝图 A4 卡）。
 * 不自己查 Configuration、不自己判宽度。否则每个页面一套判断，迟早互相矛盾。
 */
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
      // 内容栏上限按宽度档递增：手机满宽，平板收到 720dp 居中
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
  // screenWidthDp 是 dp 值；fontScale 由系统「字体大小」设置决定
  return Adaptive.specOf(
    widthDp = cfg.screenWidthDp.toFloat(),
    heightDp = cfg.screenHeightDp.toFloat(),
    fontScale = cfg.fontScale
  )
}

/**
 * 自适应外壳。在 [MorrowScaffold]（负责 insets）之上再加两层能力：
 * 注意限宽只作用于**内容**，背景仍由 MorrowScaffold 铺满整屏 —— 否则平板两侧会出现色块。
 */
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

/**
 * 按列数把若干子项排成 1/2/3 列。
 */
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
  // 多列：按 columns 分桶，每桶是一列，桶内纵向堆叠
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
// ── 原 system/MorrowScaffold.kt ──

// ───── MorrowScaffold.kt ─────
/**
 * 统一 insets 脚手架。蓝图 03 章要求「这四件事必须在任何页面写之前定死」：
 * 为什么必须「只吃 padding」：
 */
object MorrowInsets {

  /** 手势条额外留白（蓝图规定 8dp） */
  val GestureBarExtra: Dp = 8.dp

  /**
   * 内容区应使用的 padding：状态栏 + 导航栏，导航栏再加 [GestureBarExtra]。
   */
  @Composable
  internal fun contentPadding(): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    val status = WindowInsets.statusBars.asPaddingValues()
    val nav = WindowInsets.navigationBars.asPaddingValues()
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
      bottom = nav.calculateBottomPadding() + GestureBarExtra
    )
  }
}

/** 供页面读取「内容区应该内缩多少」，避免各页自己算一遍 */
val LocalContentInsets = compositionLocalOf { PaddingValues(0.dp) }

/**
 * 应用外壳。两层结构是刻意的：
 * 关键：**实际施加的 padding 与对外报告的值必须是同一个对象**。
 */
@Composable
fun MorrowScaffold(
  modifier: Modifier = Modifier,
  background: @Composable BoxScope.() -> Unit = {},
  content: @Composable BoxScope.() -> Unit
) {
  ApplySystemBars()
  val padding = MorrowInsets.contentPadding()
  Box(modifier = modifier.fillMaxSize()) {
    // 背景层：不加任何 inset，保证画到屏幕边缘
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

// ───── EdgeToEdge.kt ─────
/**
 * 无边框（edge-to-edge）的系统栏控制层。
 * 为什么必须在运行期做、不能只写在 themes.xml：
 */
object SystemBars {

  /**
   * 背景是否偏亮 → 是否使用深色图标。
   *
   * 用相对亮度 0.5 作分界，而不是判断「主题 == Paper」：
   * 将来若有自定义底色或半透明背景，按亮度判断仍然正确；按主题枚举判断会漏。
   */
  fun useDarkIcons(background: Color): Boolean = background.luminance() > 0.5f

  /** 应用到当前 window。Activity 与 Compose 层共用同一套判断。 */
  fun apply(activity: Activity, useDarkIcons: Boolean) {
    val window = activity.window
    // Android 10+ 默认给透明导航栏加系统遮罩，会露出灰条，破坏「无边框」。
    // 状态栏那条属性（isStatusBarContrastEnforced）在 API 35 已废弃：系统强制状态栏透明，
    // 已无实际作用，写它只会产生 deprecation 警告，故不使用。
    window.isNavigationBarContrastEnforced = false
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = useDarkIcons
    controller.isAppearanceLightNavigationBars = useDarkIcons
  }
}

/**
 * 在 Compose 里跟随主题应用系统栏图标反色。
 * 由 MorrowScaffold 统一调用一次，页面不需要各自处理。
 */
@Composable
fun ApplySystemBars() {
  val view = LocalView.current
  val darkIcons = SystemBars.useDarkIcons(MorrowTheme.bg)
  SideEffect {
    // Compose 里 view.context 常常是 ContextWrapper 而非 Activity 本身。
    // 直接 `as? Activity` 会静默失败 —— 反色看起来「写了」，实际一次都没执行。
    // 这里沿 context 链向上找真正的 Activity。
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
// ── 原 theme/MorrowTheme.kt ──

// ───── MobileAdjustments.kt ─────
/**
 * 移动端语义色覆盖层（对应蓝图 03 章 C1 / C2 / C3）。
 * 因此继承关系是：**桌面原值**（MorrowPalette）→（本层按文档规则覆盖）→ **实际渲染用值**。
 * 比对脚本核对第一段，`scripts/mobile-adjust.mjs` 核对第二段是否严格按规则算出。
 */
data class MobileAdjustments(
  /**
   * C1 抬底。文档只给墨夜的值与理由（「纯黑底配暖白字在户外会显得发灰，抬高半档反而更实」），
   * 该理由不适用于浅色主题，所以素纸留 `null` 直接继承桌面值 —— 不凭空给浅色主题编规则。
   */
  val bg: Color?,
  val surface: Color?,
  /**
   * C3 状态色降饱和。文档未区分主题，两套都按同一规则调整：
   * HSL 里饱和度 −0.08、亮度 +0.03，再转回 RGB 取整。
   */
  val patina: Color,
  val rust: Color,
  val amber: Color,
  val mauve: Color,
  /**
   * 次级正文色（文档「无障碍与可读性」要求正文 ≥7:1 AAA）。
   *
   * 桌面 text-2 在卡片上仅 6.27:1（应用 C1 抬底后还比原值 6.53 更低，因为底色抬亮了），
   * 达不到文档对安卓端的要求。这里朝正文色提亮到最差背景也 ≥7:1，并留余量。
   * 取值由 text2 → text 线性插值求出临界点后加余量取整（脚本可复算）。
   */
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

// ───── MorrowTheme.kt ─────
/**
 * Material3 色板映射。语义色的准源是 [MorrowPalette]，
 * 这里只是把同一批色值接进 Material3 组件体系，供未显式取色的控件使用。
 * 注意：这里**不能**写死色值。底色与状态色必须经过 [MobileAdjustments] 覆盖（C1/C3），
 * 否则「令牌层已经抬了底、Material3 组件却还是桌面原值」，
 */
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

/**
 * 排版：字号来自 [MorrowType]，行高按桌面 CSS 的比例。
 * 用系统无衬线承载中文（Manrope/Newsreader 不含中文字形，需打包字体，属后续卡）。
 */
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

/**
 * 形状：全部微圆角矩形。桌面 --r-sm=5dp 控件、--r-lg=9dp 卡片，手机端同值。
 * 不提供胶囊形 —— 用户明确要求与桌面一致，且蓝图里没有「药丸按钮」这个元素。
 */
private val MorrowShapes = androidx.compose.material3.Shapes(
  extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(MorrowRadius.xs),
  small = androidx.compose.foundation.shape.RoundedCornerShape(MorrowRadius.control),
  medium = androidx.compose.foundation.shape.RoundedCornerShape(MorrowRadius.md),
  large = androidx.compose.foundation.shape.RoundedCornerShape(MorrowRadius.card),
  extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(MorrowRadius.xl)
)

/**
 * 墨夜（默认）/ 素纸。只有两套，与桌面版一致，不新增第三套。
 *
 * 默认固定为墨夜：桌面版是深色优先的产品，手机端跟随同一气质；
 * 素纸由设置页显式切换（持久化属设置页卡片）。
 */
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

