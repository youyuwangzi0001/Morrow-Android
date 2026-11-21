package studio.morrow.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

import studio.morrow.android.MainActivity
import studio.morrow.android.MorrowUiState

object NotifyImportance {
  const val MIN = 1
  const val LOW = 2
  const val DEFAULT = 3
  const val HIGH = 4
}

enum class NotifyKind(
  /** 通知渠道 id。Android 8 起渠道是用户可见的开关粒度，必须一类别一渠道 */
  val channelId: String,
  /** 渠道名（设置页里用户看到的文字） */
  val channelName: String,
  /** 重要性。HIGH 会横幅弹出，只在「需要人立刻决定」时使用 */
  val importance: Int,
  val survivesDoNotDisturb: Boolean,
  /** 是否可折叠成汇总（汇总通知里显示「另有 N 条」） */
  val summarizable: Boolean,
  val channelDescription: String
) {
  /** 需要人立刻决定：审批 */
  Approval(
    channelId = "morrow_approval",
    channelName = "审批请求",
    importance = NotifyImportance.HIGH,
    survivesDoNotDisturb = true,
    summarizable = false,
    channelDescription = "桌面上的 agent 在等你批准某个操作。关掉它，那些任务会一直停着"
  ),

  /** 任务完成 */
  Completed(
    channelId = "morrow_completed",
    channelName = "任务完成",
    importance = NotifyImportance.DEFAULT,
    survivesDoNotDisturb = false,
    summarizable = true,
    channelDescription = "任务跑完了。免打扰期间这类通知合并成一条汇总"
  ),

  /** 任务卡住（需要人看一眼，但不会立刻阻塞别人） */
  Stuck(
    channelId = "morrow_stuck",
    channelName = "任务卡住",
    importance = NotifyImportance.DEFAULT,
    survivesDoNotDisturb = false,
    summarizable = true,
    channelDescription = "任务停下来等人看一眼。免打扰期间这类通知合并成一条汇总"
  ),

  Progress(
    channelId = "morrow_progress",
    channelName = "长任务进度",
    importance = NotifyImportance.LOW,
    survivesDoNotDisturb = false,
    summarizable = true,
    channelDescription = "正在跑的任务的进度更新。不挂角标、不发出声音"
  );

  companion object {
    /** 全部渠道（建渠道时遍历） */
    fun all(): List<NotifyKind> = entries.toList()

    /** 按渠道 id 反查；未知 id 返回 null（不静默当成某一类） */
    fun fromChannelId(channelId: String): NotifyKind? = entries.firstOrNull { it.channelId == channelId }
  }
}

object NotifyChannels {

  data class Registration(
    val channelIds: List<String>,
    val importanceMismatches: Map<String, Int>
  ) {
    /** 是否是干净的首次安装状态（没有任何渠道被旧的错误重要性锁住） */
    val isClean: Boolean get() = importanceMismatches.isEmpty()
  }

  fun register(context: Context): Registration {
    val manager = context.getSystemService(NotificationManager::class.java)
      ?: return Registration(emptyList(), emptyMap())

    for (kind in NotifyKind.all()) {
      manager.createNotificationChannel(kind.toChannel())
    }

    val mismatches = buildMap {
      for (kind in NotifyKind.all()) {
        // 下一次启动会重新建出来。
        val stored = manager.getNotificationChannel(kind.channelId) ?: continue
        if (stored.importance != kind.importance) {
          put(kind.channelId, stored.importance)
        }
      }
    }

    return Registration(
      channelIds = NotifyKind.all().map { it.channelId },
      importanceMismatches = mismatches
    )
  }

  private fun NotifyKind.toChannel(): NotificationChannel =
    NotificationChannel(channelId, channelName, importance).apply {
      description = channelDescription
      setShowBadge(importance >= NotifyImportance.DEFAULT)
      // 只有需要人立刻决定的类别才震动
      enableVibration(survivesDoNotDisturb)
      // 只有审批尝试突破免打扰。
      // 不能指望这个标志把审批送出来。
      setBypassDnd(survivesDoNotDisturb)
    }
}

data class DigestState(
  val suppressedCount: Int,
  /** 最近一条被压下的类别，用于汇总文案里的提示 */
  val lastKind: NotifyKind?
) {
  val isEmpty: Boolean get() = suppressedCount == 0
}

/** 一次投递决策的结果 */
sealed interface NotifyDecision {
  /** 照常发单条 */
  data class Deliver(val content: NotifyContent) : NotifyDecision

  data class SuppressIntoDigest(val digest: NotifyContent, val silent: Boolean) : NotifyDecision

  /** 完全不发（例如免打扰下的进度更新且汇总已达上限） */
  data object Drop : NotifyDecision
}

internal object DigestCounter {
  var suppressed: Int = 0
    private set
  var lastKind: NotifyKind? = null
    private set

  fun record(kind: NotifyKind): Int {
    suppressed += 1
    lastKind = kind
    return suppressed
  }

  fun clear() {
    suppressed = 0
    lastKind = null
  }

  /** 测试用：清回初始态。进程级单例在测试之间会串味。 */
  fun resetForTest() = clear()
}

class NotifyPolicy {

  /** 汇总通知的稳定 id（同一条不断更新，不堆积） */
  fun digestNotificationId(): Int = DIGEST_ID

  /** 当前汇总状态（诊断/测试用） */
  fun digestState(): DigestState = DigestState(DigestCounter.suppressed, DigestCounter.lastKind)

  fun decide(content: NotifyContent, doNotDisturb: Boolean): NotifyDecision {
    if (content.kind.survivesDoNotDisturb || !doNotDisturb) {
      return NotifyDecision.Deliver(content)
    }

    // 免打扰下其余类别进汇总
    if (!content.kind.summarizable) {
      return NotifyDecision.Drop
    }

    val count = DigestCounter.record(content.kind)

    val digest = NotifyContent.digest(
      kind = content.kind,
      suppressedCount = count,
      lastKindLabel = DigestCounter.lastKind?.let { kindLabel(it) } ?: ""
    )

    return NotifyDecision.SuppressIntoDigest(digest = digest, silent = true)
  }

  /** 用户点开汇总（或关闭免打扰）后清空累积 */
  fun clearDigest() {
    DigestCounter.clear()
  }

  private fun kindLabel(kind: NotifyKind): String = when (kind) {
    NotifyKind.Approval -> "审批"
    NotifyKind.Completed -> "完成"
    NotifyKind.Stuck -> "卡住"
    NotifyKind.Progress -> "进度"
  }

  companion object {
    /** 汇总通知的固定 id。用一个小常量而不是随机数：随机 id 会让通知堆积 */
    const val DIGEST_ID = 0x4D4F52 // "MOR"

    internal const val REQUEST_CLEAR_DIGEST = 3003
  }
}

/** 固定文案：写死在这里，避免调用方拼字符串时把变量带进去 */
object NotifyPhrase {
  const val APPROVAL_TITLE = "等待你批准"
  const val COMPLETED_TITLE = "任务完成"
  const val STUCK_TITLE = "任务卡住"
  const val PROGRESS_TITLE = "正在执行"
  const val DIGEST_TITLE = "Morrow 有新的进展"

  /** 汇总通知上的「全部清除」动作（M-10） */
  const val DIGEST_ACTION_CLEAR = "全部清除"

  fun completedTitle(reason: String?): String = when (reason) {
    "cancelled" -> "已取消"
    "max-steps" -> "跑满步数上限"
    else -> COMPLETED_TITLE
  }

  const val APPROVAL_ACTION_ALLOW = "允许"
  const val APPROVAL_ACTION_DENY = "拒绝"

  /** 「另有 N 条」——数字由 [NotifyContent.summaryBody] 统一格式化 */
  fun moreItems(n: Int): String = "另有 $n 条"
}

class NotifyContent private constructor(
  val kind: NotifyKind,
  val title: String,
  val body: String,
  /** 点击落点（应用内路由）。null 表示只打开应用首页 */
  val deepLink: String?,
  /** 动作按钮（最多 3 个，Android 对通知动作有数量上限） */
  val actions: List<NotifyAction>,
  /** 分组键：同一任务的通知归到一组，便于系统折叠 */
  val groupKey: String
) {
  override fun toString(): String =
    "NotifyContent(kind=$kind, title=$title, body=$body, deepLink=$deepLink, actions=$actions, groupKey=$groupKey)"

  companion object {
    fun of(
      kind: NotifyKind,
      /** 要展示的文件名（可以传完整路径，会被削成基名） */
      fileName: String? = null,
      /** 附加的固定短句，必须来自 [NotifyPhrase] 或调用方自备的常量文案 */
      detail: String? = null,
      /** 额外条目数（用于汇总/折叠） */
      extraCount: Int = 0,
      /** 已耗时（秒），0 表示不展示 */
      seconds: Long = 0,
      /** 工具标识（如 read_file），只允许白名单里的短标识 */
      tool: String? = null,
      /** 点击落点 */
      deepLink: String? = null,
      /** 分组键（通常是任务 id，非敏感） */
      groupKey: String = kind.channelId,
      reason: String? = null,
    ): NotifyContent {
      val title = when (kind) {
        NotifyKind.Approval -> NotifyPhrase.APPROVAL_TITLE
        NotifyKind.Completed -> NotifyPhrase.completedTitle(reason)
        NotifyKind.Stuck -> NotifyPhrase.STUCK_TITLE
        NotifyKind.Progress -> NotifyPhrase.PROGRESS_TITLE
      }

      val parts = buildList {
        // ① 文件名（削成基名）
        fileName?.let { name ->
          fileNameOf(name)?.let { add(it) }
        }
        tool?.let { t -> if (isSafeToolId(t)) add(t) }
        detail?.let { d -> if (isSafeDetail(d)) add(d) }
        // ④ 耗时
        if (seconds > 0) add(formatDuration(seconds))
        // ⑤ 额外条目数
        if (extraCount > 0) add(NotifyPhrase.moreItems(extraCount))
      }

      val body = parts.joinToString(" · ")

      val actions = when (kind) {
        NotifyKind.Approval -> listOf(
          NotifyAction(NotifyActionId.ALLOW, NotifyPhrase.APPROVAL_ACTION_ALLOW),
          NotifyAction(NotifyActionId.DENY, NotifyPhrase.APPROVAL_ACTION_DENY)
        )
        else -> emptyList()
      }

      return NotifyContent(
        kind = kind,
        title = title,
        body = body,
        deepLink = deepLink?.takeIf { isSafeDeepLink(it) },
        actions = actions,
        groupKey = groupKey
      )
    }

    fun fileNameOf(raw: String): String? {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) return null
      if (trimmed.any { it.code < 0x20 || it.code == 0x7f }) return null

      val idx = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
      val base = if (idx >= 0) trimmed.substring(idx + 1) else trimmed
      if (base.isEmpty() || base == "." || base == "..") return null
      if (base.contains('/') || base.contains('\\')) return null
      if (base.length > MAX_FILE_NAME) return null
      if (!SAFE_FILE_NAME.matches(base)) return null
      if (base.count { it == ' ' } > MAX_FILE_NAME_SPACES) return null
      return base
    }

    /** 工具标识白名单形态：`[a-z][a-z0-9_]{0,31}`。不含参数、不含路径 */
    fun isSafeToolId(tool: String): Boolean =
      tool.length in 1..32 && tool.matches(Regex("^[a-z][a-z0-9_]*$"))

    fun isSafeDetail(detail: String): Boolean {
      if (detail.isEmpty() || detail.length > MAX_DETAIL) return false
      if (detail.any { it == '\n' || it == '\r' || it.code < 0x20 || it.code == 0x7f }) return false
      if (detail.contains('/') || detail.contains('\\')) return false
      return true
    }

    /** 落点只允许应用内路由：`morrow://` 前缀 + 无控制字符 + 长度受限 */
    fun isSafeDeepLink(link: String): Boolean {
      if (link.length > MAX_DEEP_LINK) return false
      if (link.any { it.code < 0x20 || it.code == 0x7f }) return false
      return link.startsWith("morrow://")
    }

    fun digest(kind: NotifyKind, suppressedCount: Int, lastKindLabel: String): NotifyContent {
      val head = "共 $suppressedCount 条进展"
      val body = if (lastKindLabel.isEmpty()) head else "$head · 最近：$lastKindLabel"
      return NotifyContent(
        kind = kind,
        title = NotifyPhrase.DIGEST_TITLE,
        body = body,
        // 汇总点开进入「进展列表」，不是某一条任务
        deepLink = "morrow://activity",
        actions = emptyList(),
        groupKey = DIGEST_GROUP
      )
    }

    /** 汇总通知的分组键 */
    const val DIGEST_GROUP = "morrow_digest"

    /** 时长文案。转调 [Formatters.durationNotice] —— 全仓唯一一份。 */
    fun formatDuration(seconds: Long): String = Formatters.durationNotice(seconds)

    const val MAX_FILE_NAME = 64
    const val MAX_DETAIL = 24
    const val MAX_DEEP_LINK = 256

    /** 文件名里最多允许几个空格（普通文件名极少有空格；命令行通常有很多） */
    const val MAX_FILE_NAME_SPACES = 2

    private val SAFE_FILE_NAME = Regex("^[\\p{L}\\p{N} ._\\-()\\[\\]]{1,64}$")
  }
}

/** 动作按钮。id 是稳定枚举，不是字符串 —— 防手滑拼错导致按钮失效 */
data class NotifyAction(val id: NotifyActionId, val label: String)

enum class NotifyActionId { ALLOW, DENY }

class MorrowNotifier(private val context: Context) {

  /** 投递结果。用密封类而不是 Boolean：不同失败原因需要不同的界面反应 */
  sealed interface PostResult {
    /** 已经交给系统 */
    data class Posted(val notificationId: Int) : PostResult

    /** 对应渠道被用户关闭（或整个应用的通知权限被关） */
    data class ChannelBlocked(val channelId: String) : PostResult

    data class TakenOverByForegroundService(val taskId: String) : PostResult
  }

  fun post(content: NotifyContent, decision: NotifyDecision.Deliver): PostResult {
    val channelId = content.kind.channelId
    if (!isChannelEnabled(channelId)) return PostResult.ChannelBlocked(channelId)

    if (content.kind == NotifyKind.Progress) {
      val running = ForegroundSyncState.runningTaskId()
      if (running != null && running == content.progressTaskId()) {
        return PostResult.TakenOverByForegroundService(running)
      }
    }

    val builder = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setContentTitle(content.title)
      .setContentText(content.body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
      .setPriority(priorityOf(content.kind))
      .setCategory(categoryOf(content.kind))
      .setAutoCancel(true)
      .setOnlyAlertOnce(content.kind == NotifyKind.Progress)
      .setContentIntent(contentIntent(content))

    for (action in content.actions) {
      actionIntent(content, action)?.let { builder.addAction(actionIcon(action), action.label, it) }
    }

    val id = content.notificationId()
    NotificationManagerCompat.from(context).notify(id, builder.build())
    return PostResult.Posted(id)
  }

  fun postDigest(decision: NotifyDecision.SuppressIntoDigest): PostResult {
    val content = decision.digest
    val channelId = content.kind.channelId
    if (!isChannelEnabled(channelId)) return PostResult.ChannelBlocked(channelId)

    val builder = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setContentTitle(content.title)
      .setContentText(content.body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setSilent(decision.silent)
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      // ── M-10：「全部清除」入口 ──
      //
      //
      // 清除只是撤一条通知，不需要拉起任何服务。
      .addAction(
        0,
        NotifyPhrase.DIGEST_ACTION_CLEAR,
        PendingIntent.getBroadcast(
          context,
          NotifyPolicy.REQUEST_CLEAR_DIGEST,
          Intent(context, DigestActionReceiver::class.java).apply {
            action = DigestActionReceiver.ACTION_CLEAR_DIGEST
          },
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
      )
      .setContentIntent(contentIntent(content))

    NotificationManagerCompat.from(context).notify(NotifyPolicy.DIGEST_ID, builder.build())
    return PostResult.Posted(NotifyPolicy.DIGEST_ID)
  }

  /** 用户看过汇总后撤掉它 */
  fun cancelDigest() {
    NotificationManagerCompat.from(context).cancel(NotifyPolicy.DIGEST_ID)
  }

  fun cancelSingleProgress(taskId: String) {
    val id = progressNotificationId(taskId)
    NotificationManagerCompat.from(context).cancel(id)
  }

  private fun isChannelEnabled(channelId: String): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
      ?: return false
    val channel = manager.getNotificationChannel(channelId) ?: return false
    return channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
  }

  /** 点击正文的落点：只认 morrow:// 深链，其余一律首页 */
  private fun contentIntent(content: NotifyContent): PendingIntent {
    val dest = MorrowDeepLink.parse(content.deepLink)
    return activityIntent(MorrowDeepLink.toUri(dest))
  }

  private fun actionIntent(content: NotifyContent, action: NotifyAction): PendingIntent? {
    val approvalId = (MorrowDeepLink.parse(content.deepLink) as? MorrowDestination.Approvals)
      ?.focusId
      ?: return null

    val base = "${MorrowDeepLink.SCHEME}://${MorrowDeepLink.HOST_APPROVALS}?focus=$approvalId"

    return when (action.id) {
      NotifyActionId.ALLOW -> activityIntent("$base&${MorrowDeepLink.PARAM_DECISION}=allow")

      NotifyActionId.DENY -> {
        val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
          data = Uri.parse("$base&${MorrowDeepLink.PARAM_DECISION}=deny")
          setPackage(context.packageName)
        }
        PendingIntent.getBroadcast(
          context,
          REQUEST_DENY,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
      }
    }
  }

  private fun activityIntent(uri: String): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      action = Intent.ACTION_VIEW
      data = Uri.parse(uri)
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
      context,
      REQUEST_OPEN,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun actionIcon(action: NotifyAction): Int = when (action.id) {
    NotifyActionId.ALLOW -> android.R.drawable.checkbox_on_background
    NotifyActionId.DENY -> android.R.drawable.checkbox_off_background
  }

  private fun priorityOf(kind: NotifyKind): Int = when (kind.importance) {
    NotifyImportance.HIGH -> NotificationCompat.PRIORITY_HIGH
    NotifyImportance.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
    NotifyImportance.LOW -> NotificationCompat.PRIORITY_LOW
    else -> NotificationCompat.PRIORITY_MIN
  }

  private fun categoryOf(kind: NotifyKind): String = when (kind) {
    NotifyKind.Approval -> NotificationCompat.CATEGORY_CALL
    NotifyKind.Stuck -> NotificationCompat.CATEGORY_ERROR
    NotifyKind.Completed -> NotificationCompat.CATEGORY_STATUS
    NotifyKind.Progress -> NotificationCompat.CATEGORY_PROGRESS
  }

  private companion object {
    const val REQUEST_OPEN = 1001
    const val REQUEST_DENY = 1002
  }
}

internal fun NotifyContent.notificationId(): Int {
  val focusId = when (val dest = MorrowDeepLink.parse(deepLink)) {
    is MorrowDestination.Approvals -> dest.focusId
    is MorrowDestination.Activity -> dest.focusId
    MorrowDestination.Workbench -> null
  }
  val key = focusId ?: groupKey
  return stableNotificationId(key)
}

internal fun progressNotificationId(taskId: String): Int = stableNotificationId(taskId)

internal fun NotifyContent.progressTaskId(): String {
  val focusId = when (val dest = MorrowDeepLink.parse(deepLink)) {
    is MorrowDestination.Approvals -> dest.focusId
    is MorrowDestination.Activity -> dest.focusId
    MorrowDestination.Workbench -> null
  }
  return focusId ?: groupKey
}

/** 把任意 key 映到 1..100,000 的稳定 id */
private fun stableNotificationId(key: String): Int {
  val h = key.hashCode()
  return if (h == Int.MIN_VALUE) 1 else kotlin.math.abs(h) % 100_000 + 1
}

object ApprovalActions {

  class ApprovalRequest private constructor(
    /** 审批 id（来自深链的 focus 参数，已过白名单） */
    val approvalId: String,
    /** 是否允许 */
    val allow: Boolean
  ) {
    override fun toString(): String = "ApprovalRequest(id=$approvalId, allow=$allow)"

    companion object {
      fun verified(approvalId: String, allow: Boolean): ApprovalRequest? {
        if (!SAFE_APPROVAL_ID.matches(approvalId)) return null
        return ApprovalRequest(approvalId, allow)
      }

      private val SAFE_APPROVAL_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
  }

  /** 执行结果。用密封类而不是 Boolean：失败原因要能区分 */
  sealed interface Result {
    /** 已下发 */
    data class Dispatched(val approvalId: String, val allow: Boolean) : Result

    /** 这条审批已经处理过（重复点击 / 通知残留） */
    data class AlreadyHandled(val approvalId: String) : Result

    /** 找不到这条审批（已被撤销 / 已过期） */
    data class Unknown(val approvalId: String) : Result
  }

  private val handled = object : LinkedHashSet<String>() {
    override fun add(element: String): Boolean {
      val added = super.add(element)
      while (size > MAX_REMEMBERED) remove(first())
      return added
    }
  }

  const val MAX_REMEMBERED = 256

  fun perform(
    request: ApprovalRequest,
    knownApprovalIds: Set<String>,
    sink: (approvalId: String, allow: Boolean) -> Boolean
  ): Result {
    if (request.approvalId !in knownApprovalIds) return Result.Unknown(request.approvalId)

    synchronized(handled) {
      if (!handled.add(request.approvalId)) return Result.AlreadyHandled(request.approvalId)
    }

    val ok = sink(request.approvalId, request.allow)
    if (!ok) {
      synchronized(handled) { handled.remove(request.approvalId) }
      return Result.Unknown(request.approvalId)
    }
    return Result.Dispatched(request.approvalId, request.allow)
  }

  fun dispatchOnce(
    approvalId: String,
    allow: Boolean,
    sink: (approvalId: String, allow: Boolean) -> Boolean
  ): Boolean {
    synchronized(handled) {
      if (!handled.add(approvalId)) return false
    }
    val ok = runCatching { sink(approvalId, allow) }.getOrDefault(false)
    if (!ok) synchronized(handled) { handled.remove(approvalId) }
    return ok
  }

  /** 清空去重记录（测试与「切换桌面」时用） */
  fun resetForTest() {
    synchronized(handled) { handled.clear() }
  }
}

object ApprovalDecisionInbox {

  /** 一条待下发的决策 */
  data class Pending(val approvalId: String, val allow: Boolean, val atMillis: Long)

  private var sink: ((approvalId: String, allow: Boolean) -> Boolean)? = null

  private val queue = mutableListOf<Pending>()

  /** 最多记住多少条未发出的决策。长时间离线不应该无限占内存 */
  const val MAX_QUEUE = 64

  fun registerSink(newSink: ((String, Boolean) -> Boolean)?) {
    sink = newSink
  }

  fun offer(approvalId: String, allow: Boolean, nowMillis: Long, context: Context? = null): Boolean {
    val current = sink
    if (current != null && current(approvalId, allow)) return true

    synchronized(queue) {
      // （用户先点拒绝又点允许，最终意图是允许）
      queue.removeAll { it.approvalId == approvalId }
      queue.add(Pending(approvalId, allow, nowMillis))
      while (queue.size > MAX_QUEUE) queue.removeAt(0)
    }
    if (context != null) persist(context)
    return false
  }

  /** 现在队列里有哪些待发决策（按到达顺序） */
  fun pending(): List<Pending> = synchronized(queue) { queue.toList() }

  fun flush(context: Context? = null): Int {
    val current = sink ?: return 0
    var sent = 0
    synchronized(queue) {
      val iterator = queue.iterator()
      while (iterator.hasNext()) {
        val item = iterator.next()
        if (current(item.approvalId, item.allow)) {
          iterator.remove()
          sent++
        } else {
          // 通道又断了：后面的也不必再试，保持顺序
          break
        }
      }
    }
    if (context != null) persist(context)
    return sent
  }

  fun restore(context: Context) {
    val raw = prefs(context).getString(KEY_PENDING, null) ?: return
    val restored = raw.lineSequence().mapNotNull { line ->
      val parts = line.split('|')
      if (parts.size != 3) return@mapNotNull null
      val id = parts[0].takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val at = parts[2].toLongOrNull() ?: return@mapNotNull null
      Pending(id, parts[1] == "1", at)
    }.toList()
    synchronized(queue) {
      queue.clear()
      queue.addAll(restored.takeLast(MAX_QUEUE))
    }
  }

  private fun persist(context: Context) {
    val raw = synchronized(queue) {
      queue.joinToString("\n") { "${it.approvalId}|${if (it.allow) 1 else 0}|${it.atMillis}" }
    }
    prefs(context).edit().putString(KEY_PENDING, raw).apply()
  }

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun clearForTest(context: Context? = null, clearDisk: Boolean = true) {
    sink = null
    synchronized(queue) { queue.clear() }
    if (context != null && clearDisk) prefs(context).edit().clear().apply()
  }

  /** 生产：清除配对时一起清掉未发决策（旧桌面的审批不该发到新桌面）。 */
  fun clear(context: Context) {
    clearForTest(context, clearDisk = true)
  }

  private const val PREFS = "morrow.approval_inbox"
  private const val KEY_PENDING = "pending"
}

class DigestActionReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_CLEAR_DIGEST) return
    // 所以用户连点两次也没关系。
    MorrowNotifier(context.applicationContext).cancelDigest()
  }

  companion object {
    const val ACTION_CLEAR_DIGEST = "studio.morrow.android.DIGEST_CLEAR"
  }
}

class ApprovalActionReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val uri = intent.data ?: return
    if (uri.scheme != MorrowDeepLink.SCHEME) return

    val allow = MorrowDeepLink.requestedDecision(uri) ?: return
    if (allow) return

    val approvalId = MorrowDeepLink.parse(uri.toString())
      .let { it as? MorrowDestination.Approvals }
      ?.focusId
      ?: return

    ApprovalDecisionInbox.offer(
      approvalId = approvalId,
      allow = false,
      nowMillis = System.currentTimeMillis(),
      context = context.applicationContext,
    )
  }
}

object DoNotDisturb {

  fun canBypass(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return try {
      manager.isNotificationPolicyAccessGranted
    } catch (_: SecurityException) {
      false
    } catch (_: Throwable) {
      false
    }
  }

  fun isOn(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return try {
      manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    } catch (_: SecurityException) {
      false
    }
  }
}

object FcmPayload {

  const val KEY_KIND = "kind"
  const val KEY_TASK_ID = "taskId"
  const val KEY_FOCUS = "focus"
  const val KEY_DETAIL_CODE = "detailCode"
  const val KEY_SNAPSHOT_REQUIRED = "snapshotRequired"

  val ALLOWED_KEYS: Set<String> = setOf(
    KEY_KIND, KEY_TASK_ID, KEY_FOCUS, KEY_DETAIL_CODE, KEY_SNAPSHOT_REQUIRED
  )

  /** `kind` 的取值 → 本地语义。未知值不进这张表 */
  private val KIND_MAP: Map<String, NotifyKind> = mapOf(
    "approval" to NotifyKind.Approval,
    "completed" to NotifyKind.Completed,
    "stuck" to NotifyKind.Stuck,
    "progress" to NotifyKind.Progress
  )

  private val DETAIL_MAP: Map<String, String> = mapOf(
    "tool_run" to "需要你允许执行工具",
    "permission_change" to "需要你确认权限变更",
    "dangerous_action" to "需要你确认危险操作",
    "needs_input" to "等着你补充信息",
    "tool_failed" to "工具执行失败",
    "step_done" to "又完成一步"
  )

  fun knownDetailCodes(): Set<String> = DETAIL_MAP.keys

  /** 单个字段值的长度上限。超过就不读 —— 长值多半是内容而不是标识 */
  const val MAX_VALUE_LENGTH = 128

  /** data 最多允许几个键。正常推送只有 2–4 个；12 已经非常宽松 */
  const val MAX_DATA_KEYS = 12

  /** 解析被拒的原因。每一种都要能在诊断里说清「为什么这条没变成通知」 */
  enum class Reason {
    /** 带了 `notification` 段：系统会自己弹，绕过我们的渠道与清洗 */
    HAS_NOTIFICATION_BLOCK,

    /** 没有 `kind`，无法判断这是哪一类 */
    MISSING_KIND,

    /** `kind` 是我们不认识的取值（桌面可能更新了） */
    UNKNOWN_KIND,

    /** 某个字段形态不合法（过长、含控制字符等） */
    UNSAFE_DATA,

    /** data 为空 / 键太多 */
    EMPTY_OR_OVERSIZED,
  }

  /** 解析成功的结果 */
  data class Parsed(
    val kind: NotifyKind,
    /** 任务标识（已过白名单）；null 表示这条推送不带任务 */
    val taskId: String?,
    /** 聚焦目标（已过白名单） */
    val focus: String?,
    /** 细节码（已确认存在于 [DETAIL_MAP]）；null 表示不带细节 */
    val detailCode: String?,
    val snapshotRequired: Boolean
  ) {
    /** 细节码对应的本地文案；没有细节就是 null */
    val detailText: String? get() = detailCode?.let { DETAIL_MAP[it] }
  }

  sealed interface Result {
    data class Accepted(val parsed: Parsed) : Result
    data class Rejected(val reason: Reason, val atMillis: Long) : Result
  }

  fun parse(
    data: Map<String, String>?,
    hasNotificationBlock: Boolean,
    nowMillis: Long = System.currentTimeMillis()
  ): Result {
    // 必须记录，否则服务器配错了永远发现不了。
    if (hasNotificationBlock) {
      return reject(Reason.HAS_NOTIFICATION_BLOCK, nowMillis)
    }

    if (data.isNullOrEmpty() || data.size > MAX_DATA_KEYS) {
      return reject(Reason.EMPTY_OR_OVERSIZED, nowMillis)
    }

    // ② 任何一个值形态不合法就整条拒绝。
    for ((key, value) in data) {
      if (key in ALLOWED_KEYS && !isSafeValue(value)) {
        return reject(Reason.UNSAFE_DATA, nowMillis)
      }
    }

    val rawKind = data[KEY_KIND]?.takeIf { it.isNotBlank() }
      ?: return reject(Reason.MISSING_KIND, nowMillis)

    val kind = KIND_MAP[rawKind]
      ?: return reject(Reason.UNKNOWN_KIND, nowMillis)

    val detailCode = data[KEY_DETAIL_CODE]
      ?.takeIf { it.isNotBlank() }
      ?.takeIf { it in DETAIL_MAP }

    FcmDiagnostics.recordAccepted()
    return Result.Accepted(
      Parsed(
        kind = kind,
        taskId = data[KEY_TASK_ID]?.takeIf { it.isNotBlank() },
        focus = data[KEY_FOCUS]?.takeIf { it.isNotBlank() },
        detailCode = detailCode,
        snapshotRequired = data[KEY_SNAPSHOT_REQUIRED] == "true"
      )
    )
  }

  private fun reject(reason: Reason, nowMillis: Long): Result.Rejected {
    FcmDiagnostics.record(reason, nowMillis)
    return Result.Rejected(reason, nowMillis)
  }

  /** 字段值白名单：非空、不太长、无控制字符 */
  private fun isSafeValue(value: String): Boolean {
    if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) return false
    if (value.any { it.code < 0x20 || it.code == 0x7f }) return false
    return true
  }

  fun toContent(parsed: Parsed): NotifyContent {
    val safeFocus = parsed.focus?.takeIf { MorrowDeepLink.isSafeFocusId(it) }
    val deepLink = when (parsed.kind) {
      NotifyKind.Approval ->
        "${MorrowDeepLink.SCHEME}://${MorrowDeepLink.HOST_APPROVALS}" +
          (safeFocus?.let { "?focus=$it" } ?: "")
      else ->
        "${MorrowDeepLink.SCHEME}://${MorrowDeepLink.HOST_ACTIVITY}" +
          (safeFocus?.let { "?focus=$it" } ?: "")
    }
    return NotifyContent.of(
      kind = parsed.kind,
      detail = parsed.detailText,
      deepLink = deepLink,
      // 有任务就用任务作分组键，否则按类别合并
      groupKey = parsed.taskId ?: parsed.kind.channelId
    )
  }
}

object FcmDiagnostics {

  data class Entry(val reason: FcmPayload.Reason, val atMillis: Long)

  const val MAX_ENTRIES = 200

  private val entries = ArrayDeque<Entry>()

  /** 累计收到多少条被拒的推送（不因环形溢出而归零） */
  @Volatile
  var rejectedTotal: Long = 0L
    private set

  /** 累计接受多少条 */
  @Volatile
  var acceptedTotal: Long = 0L
    private set

  fun record(reason: FcmPayload.Reason, atMillis: Long) {
    synchronized(entries) {
      entries.addLast(Entry(reason, atMillis))
      while (entries.size > MAX_ENTRIES) entries.removeFirst()
      rejectedTotal++
    }
  }

  fun recordAccepted() {
    acceptedTotal++
  }

  /** 最近的记录（最新的在最后） */
  fun recent(): List<Entry> = synchronized(entries) { entries.toList() }

  /** 最近一条被拒记录的原因（诊断页可以直接显示这一句） */
  fun lastReason(): FcmPayload.Reason? = synchronized(entries) { entries.lastOrNull()?.reason }

  fun resetForTest() {
    synchronized(entries) {
      entries.clear()
      rejectedTotal = 0
      acceptedTotal = 0
    }
  }
}

object FcmReceiver {

  private val policy = NotifyPolicy()

  fun onMessage(
    context: Context,
    data: Map<String, String>?,
    hasNotificationBlock: Boolean
  ): FcmPayload.Result {
    val result = FcmPayload.parse(data, hasNotificationBlock)

    when (result) {
      is FcmPayload.Result.Rejected -> {
        return result
      }

      is FcmPayload.Result.Accepted -> {
        val parsed = result.parsed
        PendingPull.mark(context, parsed.snapshotRequired)

        val content = FcmPayload.toContent(parsed)
        val decision = policy.decide(content, doNotDisturb = DoNotDisturb.isOn(context))
        val notifier = MorrowNotifier(context)
        when (decision) {
          is NotifyDecision.Deliver -> notifier.post(content, decision)
          is NotifyDecision.SuppressIntoDigest -> notifier.postDigest(decision)
          NotifyDecision.Drop -> Unit
        }
        return result
      }
    }
  }

  fun onResume(context: Context): PendingPull.Request? = PendingPull.consume(context)

  fun clearDigest() = policy.clearDigest()

  /** 当前汇总累积了几条。诊断用；界面将来可以显示「另有 N 条未看」 */
  fun digestState() = policy.digestState()
}

sealed interface MorrowDestination {
  /** 首页（工位页）。也是所有无法识别落点的归宿 */
  data object Workbench : MorrowDestination

  /** 审批页（并聚焦某一条审批） */
  data class Approvals(val focusId: String?) : MorrowDestination

  /** 进展列表（汇总通知的落点） */
  data class Activity(val focusId: String?) : MorrowDestination
}

object MorrowDeepLink {

  const val SCHEME = "morrow"
  const val HOST_WORKBENCH = "workbench"
  const val HOST_APPROVALS = "approvals"
  const val HOST_ACTIVITY = "activity"

  /** focus 参数只允许这种形态（任务 id / 审批 id，字母数字与 - _，长度受限） */
  private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

  fun isSafeFocusId(value: String): Boolean = SAFE_ID.matches(value)

  fun parse(raw: String?): MorrowDestination {
    if (raw.isNullOrBlank()) return MorrowDestination.Workbench

    val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull()
      ?: return MorrowDestination.Workbench

    if (uri.scheme != SCHEME) return MorrowDestination.Workbench

    val host = uri.host ?: return MorrowDestination.Workbench
    val focus = focusIdOf(uri)

    return when (host) {
      HOST_WORKBENCH -> MorrowDestination.Workbench
      HOST_APPROVALS -> MorrowDestination.Approvals(focus)
      HOST_ACTIVITY -> MorrowDestination.Activity(focus)
      else -> MorrowDestination.Workbench
    }
  }

  private fun focusIdOf(uri: android.net.Uri): String? {
    val fromQuery = runCatching { uri.getQueryParameter("focus") }.getOrNull()
    val candidate = fromQuery?.takeIf { it.isNotBlank() }
      ?: uri.pathSegments?.firstOrNull()?.takeIf { it.isNotBlank() }

    return candidate?.takeIf { isSafeFocusId(it) }
  }

  /** 由目标反拼深链（发通知时用）。与 [parse] 必须互为逆运算 */
  fun toUri(dest: MorrowDestination): String = when (dest) {
    is MorrowDestination.Workbench -> "$SCHEME://$HOST_WORKBENCH"
    is MorrowDestination.Approvals -> buildString {
      append("$SCHEME://$HOST_APPROVALS")
      dest.focusId?.let { append("?focus=$it") }
    }
    is MorrowDestination.Activity -> buildString {
      append("$SCHEME://$HOST_ACTIVITY")
      dest.focusId?.let { append("?focus=$it") }
    }
  }

  /** 通知动作里表示「用户按的是允许」的参数名 */
  const val PARAM_DECISION = "decision"

  fun requestedDecision(uri: android.net.Uri): Boolean? =
    when (uri.getQueryParameter(PARAM_DECISION)) {
      "allow" -> true
      "deny" -> false // 兼容旧值；新代码用 ApprovalDecision
      else -> null
    }
}

class ForegroundSyncService : Service() {

  sealed interface Visibility {
    /** 常驻通知可见 */
    data object Visible : Visibility

    data object NotificationSuppressed : Visibility

    /** 进度渠道被用户关掉（importance = NONE，系统不会显示这条通知） */
    data object ChannelBlocked : Visibility
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopSync(SyncStopReason.USER_CANCELLED)
        return START_NOT_STICKY
      }

      ACTION_UPDATE -> {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
        val step = intent.getIntExtra(EXTRA_STEP, 0)
        val total = intent.getIntExtra(EXTRA_TOTAL, 0)
        val startedAt = intent.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())
        // 更新之前必须先确认「服务已在跑」。
        //
        if (ForegroundSyncState.runningTaskId() == null) {
          start(taskId, step, total)
          return START_NOT_STICKY
        }
        update(taskId, step, total, startedAt)
        return START_NOT_STICKY
      }
    }

    // 默认动作 = 开始跟盯
    val taskId = intent?.getStringExtra(EXTRA_TASK_ID).orEmpty()
    if (taskId.isBlank()) {
      stopSelf()
      return START_NOT_STICKY
    }
    val step = intent?.getIntExtra(EXTRA_STEP, 0) ?: 0
    val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0
    start(taskId, step, total)
    //
    //
    // 进度条接着走。
    //
    // 用户按下的「停止」自己爬起来，比不重建更糟。
    return START_REDELIVER_INTENT
  }

  private fun start(taskId: String, step: Int, totalSteps: Int) {
    val running = SyncState.Running(
      taskId = taskId,
      step = step,
      totalSteps = totalSteps,
      startedAtMillis = System.currentTimeMillis()
    )
    ForegroundSyncState.set(running)

    // 去重必须在这里做，不能指望调用方记得。
    MorrowNotifier(this).cancelSingleProgress(taskId)

    val notification = buildNotification(running)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        ForegroundSyncState.SYNC_NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      )
    } else {
      startForeground(ForegroundSyncState.SYNC_NOTIFICATION_ID, notification)
    }
  }

  private fun update(taskId: String, step: Int, totalSteps: Int, startedAtMillis: Long) {
    val running = SyncState.Running(taskId, step, totalSteps, startedAtMillis)
    ForegroundSyncState.set(running)

    val manager = getSystemService(NotificationManager::class.java) ?: return
    manager.notify(ForegroundSyncState.SYNC_NOTIFICATION_ID, buildNotification(running))
  }

  override fun onTimeout(startId: Int) {
    stopSync(SyncStopReason.SYSTEM_TIMEOUT)
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    stopSync(SyncStopReason.SYSTEM_TIMEOUT)
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    if (ForegroundSyncState.isRunning()) {
      stopSync(SyncStopReason.USER_CANCELLED)
    }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    // 而不是把它当成「任务正常结束」。
    if (ForegroundSyncState.isRunning()) {
      ForegroundSyncState.recordStop(SyncStopReason.SYSTEM_DESTROYED)
    }
    super.onDestroy()
  }

  private fun stopSync(reason: SyncStopReason) {
    ForegroundSyncState.recordStop(reason)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun buildNotification(state: SyncState.Running): Notification {
    val channelId = NotifyKind.Progress.channelId
    val percent = state.percent

    val builder = NotificationCompat.Builder(this, channelId)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle("正在跟盯任务")
      .setContentText(progressText(state))
      .setStyle(NotificationCompat.BigTextStyle().bigText(progressText(state)))
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setContentIntent(openIntent(state.taskId))
      .addAction(0, "停止跟盯", stopIntent())

    if (percent != null) {
      builder.setProgress(100, percent, false)
    } else {
      // 不显示「0%」是因为那会让人以为卡住了。
      builder.setProgress(0, 0, true)
    }

    return builder.build()
  }

  /** 进度文案：总量未知时只说步数，不说百分比 */
  private fun progressText(state: SyncState.Running): String {
    val percent = state.percent
    val elapsed = formatElapsed(state.elapsedSeconds(System.currentTimeMillis()))
    return if (percent == null) {
      "已完成 ${state.step} 步 · 已用时 $elapsed"
    } else {
      "$percent% · ${state.step}/${state.totalSteps} 步 · 已用时 $elapsed"
    }
  }

  /** 时长只到分钟：秒级精度对「跟盯」这个场景没有意义，还会让通知每秒变一次 */
  private fun formatElapsed(seconds: Long): String = Formatters.durationNotice(seconds)

  private fun openIntent(taskId: String): PendingIntent {
    val uri = Uri.parse("${MorrowDeepLink.SCHEME}://${MorrowDeepLink.HOST_ACTIVITY}?focus=$taskId")
    val intent = Intent(this, MainActivity::class.java).apply {
      action = Intent.ACTION_VIEW
      data = uri
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
      this,
      REQUEST_OPEN,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun stopIntent(): PendingIntent {
    val intent = Intent(this, ForegroundSyncService::class.java).apply {
      action = ACTION_STOP
    }
    return PendingIntent.getService(
      this,
      REQUEST_STOP,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  companion object {
    const val ACTION_START = "studio.morrow.android.FOREGROUND_SYNC_START"
    const val ACTION_UPDATE = "studio.morrow.android.FOREGROUND_SYNC_UPDATE"
    const val ACTION_STOP = "studio.morrow.android.FOREGROUND_SYNC_STOP"

    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_STEP = "step"
    const val EXTRA_TOTAL = "total"
    const val EXTRA_STARTED_AT = "started_at"

    private const val REQUEST_OPEN = 2001
    private const val REQUEST_STOP = 2002

    fun start(context: Context, taskId: String, step: Int, totalSteps: Int) {
      val intent = Intent(context, ForegroundSyncService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_TASK_ID, taskId)
        putExtra(EXTRA_STEP, step)
        putExtra(EXTRA_TOTAL, totalSteps)
      }
      context.startForegroundService(intent)
    }

    /** 更新进度。任务没在跟盯时会自动开始跟盯（幂等） */
    fun update(context: Context, taskId: String, step: Int, totalSteps: Int, startedAtMillis: Long) {
      val intent = Intent(context, ForegroundSyncService::class.java).apply {
        action = ACTION_UPDATE
        putExtra(EXTRA_TASK_ID, taskId)
        putExtra(EXTRA_STEP, step)
        putExtra(EXTRA_TOTAL, totalSteps)
        putExtra(EXTRA_STARTED_AT, startedAtMillis)
      }
      context.startForegroundService(intent)
    }

    /** 停止跟盯 */
    fun stop(context: Context) {
      context.startService(
        Intent(context, ForegroundSyncService::class.java).apply { action = ACTION_STOP }
      )
    }
  }
}

object PendingPull {

  private const val PREFS = "morrow.pending_pull"
  private const val KEY_PENDING = "pending"
  private const val KEY_SNAPSHOT = "snapshot_required"
  private const val KEY_MARK_COUNT = "mark_count"
  private const val KEY_FIRST_AT = "first_at"

  /** 补拉请求。`sinceFirstMarkMillis` 让实现能判断「隔了多久没同步」 */
  data class Request(
    /** 是否必须走全量快照（而不是增量补漏） */
    val snapshotRequired: Boolean,
    /** 从第一次标记到现在的毫秒数。隔得久说明漏得可能很多 */
    val sinceFirstMarkMillis: Long,
    /** 期间累计有多少条推送被合并进来（诊断用） */
    val markCount: Int,
    val firstMarkedAtMillis: Long
  )

  fun mark(context: Context, needsSnapshot: Boolean, nowMillis: Long = System.currentTimeMillis()) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val editor = prefs.edit()

    val firstAt = prefs.getLong(KEY_FIRST_AT, 0L)
    if (firstAt == 0L) editor.putLong(KEY_FIRST_AT, nowMillis)

    editor.putBoolean(KEY_PENDING, true)
    if (needsSnapshot || prefs.getBoolean(KEY_SNAPSHOT, false)) {
      editor.putBoolean(KEY_SNAPSHOT, true)
    }
    editor.putInt(KEY_MARK_COUNT, prefs.getInt(KEY_MARK_COUNT, 0) + 1)
    editor.apply()
  }

  /** 有没有待补拉（不消费） */
  fun isPending(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

  fun consume(context: Context, nowMillis: Long = System.currentTimeMillis()): Request? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(KEY_PENDING, false)) return null

    val firstAt = prefs.getLong(KEY_FIRST_AT, nowMillis)
    val request = Request(
      snapshotRequired = prefs.getBoolean(KEY_SNAPSHOT, false),
      sinceFirstMarkMillis = (nowMillis - firstAt).coerceAtLeast(0),
      markCount = prefs.getInt(KEY_MARK_COUNT, 0),
      firstMarkedAtMillis = firstAt
    )

    prefs.edit()
      .putBoolean(KEY_PENDING, false)
      .putBoolean(KEY_SNAPSHOT, false)
      .putInt(KEY_MARK_COUNT, 0)
      .putLong(KEY_FIRST_AT, 0L)
      .commit()

    return request
  }

  fun restore(context: Context, request: Request) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.edit()
      .putBoolean(KEY_PENDING, true)
      .putBoolean(KEY_SNAPSHOT, request.snapshotRequired)
      .putInt(KEY_MARK_COUNT, request.markCount)
      .putLong(KEY_FIRST_AT, request.firstMarkedAtMillis)
      .commit()
  }

  fun clearForTest(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
  }
}

sealed interface SyncState {

  /** 没有长任务在跑 */
  data object Idle : SyncState

  data class Running(
    val taskId: String,
    val step: Int,
    val totalSteps: Int,
    val startedAtMillis: Long
  ) : SyncState {

    val percent: Int?
      get() = if (totalSteps <= 0) null
      else ((step.toLong() * 100) / totalSteps).toInt().coerceIn(0, 100)

    /** 已用时秒数（不小于 0：设备时钟回拨时不该显示负数） */
    fun elapsedSeconds(nowMillis: Long): Long =
      ((nowMillis - startedAtMillis) / 1000).coerceAtLeast(0)
  }
}

enum class SyncStopReason {
  /** 任务真的结束了 */
  COMPLETED,

  /** 用户手动取消跟盯 */
  USER_CANCELLED,

  SYSTEM_TIMEOUT,

  /** 系统直接回收了服务（低内存），走 onDestroy 而非 onTimeout */
  SYSTEM_DESTROYED,
}

object ForegroundSyncState {

  /** 前台服务通知的固定 id。三个 id 空间互不重叠（见下方注释） */
  const val SYNC_NOTIFICATION_ID = 0x53594E43 // "SYNC" = 1,398,323,779

  private val stateHolder = mutableStateOf<SyncState>(SyncState.Idle)

  @Volatile
  private var lastStop: SyncStopReason? = null

  /** 当前状态。**在 @Composable 里读它会自动订阅变化** */
  fun current(): SyncState = stateHolder.value

  fun lastStopReason(): SyncStopReason? = lastStop

  /** 当前正在跟盯的任务 id；不在跑就是 null */
  fun runningTaskId(): String? = (stateHolder.value as? SyncState.Running)?.taskId

  fun isRunning(): Boolean = stateHolder.value is SyncState.Running

  fun visibility(context: android.content.Context): ForegroundSyncService.Visibility {
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
      ?: return ForegroundSyncService.Visibility.NotificationSuppressed
    if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
      return ForegroundSyncService.Visibility.NotificationSuppressed
    }
    val channel = manager.getNotificationChannel(NotifyKind.Progress.channelId)
      ?: return ForegroundSyncService.Visibility.ChannelBlocked
    if (channel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
      return ForegroundSyncService.Visibility.ChannelBlocked
    }
    return ForegroundSyncService.Visibility.Visible
  }

  internal fun set(newState: SyncState) {
    stateHolder.value = newState
  }

  internal fun recordStop(reason: SyncStopReason) {
    stateHolder.value = SyncState.Idle
    lastStop = reason
  }

  fun resetForTest() {
    stateHolder.value = SyncState.Idle
    lastStop = null
  }
}

sealed interface NotificationSignal {
  /** 新审批。审批是唯一会让桌面 agent 停住的类别。 */
  data class Approval(
    val requestId: String,
    val toolName: String,
    val requestedAtMillis: Long,
  ) : NotificationSignal

  data class RunFinished(
    val sessionId: String,
    val runId: String,
    val failed: Boolean,
    val elapsedSeconds: Long,
    val reason: String? = null,
  ) : NotificationSignal

  /** 协作成员失败或上下文耗尽。 */
  data class MemberStuck(val index: Int, val title: String) : NotificationSignal

  /** 同一轮步数前进。 */
  data class Progress(
    val sessionId: String,
    val runId: String,
    val stepCount: Int,
    val elapsedSeconds: Long,
  ) : NotificationSignal
}

object NotificationSignals {

  private val FAILED_MEMBER_STATES = setOf("failed", "error", "exhausted")

  private val COMPLETED_REASONS = setOf("complete", "cancelled", "max-steps")

  fun of(
    before: MorrowUiState,
    after: MorrowUiState,
    events: List<Map<String, Any?>>,
    nowMillis: Long,
  ): List<NotificationSignal> {
    val out = mutableListOf<NotificationSignal>()

    after.pendingApprovals.values
      .filter { it.requestId !in before.pendingApprovals }
      .sortedBy { if (it.requestedAtMillis > 0L) it.requestedAtMillis else Long.MAX_VALUE }
      .forEach { out += NotificationSignal.Approval(it.requestId, it.toolName, it.requestedAtMillis) }

    events.filter { it["type"] == "run_finished" }.forEach { event ->
      val sessionId = event["sessionId"] as? String ?: return@forEach
      val prior = before.runs[sessionId] ?: return@forEach
      val reason = event["reason"] as? String
      out += NotificationSignal.RunFinished(
        sessionId = sessionId,
        runId = prior.runId,
        failed = reason == "error",
        elapsedSeconds = secondsSince(prior.startedAtMillis, nowMillis),
        reason = reason,
      )
    }

    before.members?.let { priorMembers ->
      val alreadyFailed = priorMembers
        .filter { it.state in FAILED_MEMBER_STATES }
        .map { it.index }
        .toSet()
      after.members.orEmpty()
        .filter { it.state in FAILED_MEMBER_STATES && it.index !in alreadyFailed }
        .forEach { out += NotificationSignal.MemberStuck(it.index, it.title) }
    }

    after.runs.forEach { (sessionId, run) ->
      val prior = before.runs[sessionId] ?: return@forEach
      if (prior.runId == run.runId && run.stepCount > prior.stepCount) {
        out += NotificationSignal.Progress(
          sessionId = sessionId,
          runId = run.runId,
          stepCount = run.stepCount,
          elapsedSeconds = secondsSince(run.startedAtMillis, nowMillis),
        )
      }
    }

    return out
  }

  private fun secondsSince(startMillis: Long, nowMillis: Long): Long =
    ((nowMillis - startMillis).coerceAtLeast(0L)) / 1_000L
}

class RelayNotifications(
  context: Context,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private val appContext = context.applicationContext
  private val notifier = MorrowNotifier(appContext)
  private val policy = NotifyPolicy()

  /** 已通知过的审批。重连重放同一批审批时不再响一次。 */
  private val notifiedApprovals = LinkedHashSet<String>()

  /** 每个 run 上次进度通知的时刻（节流，避免每步都发） */
  private val lastProgressAt = mutableMapOf<String, Long>()

  fun onBatch(
    before: MorrowUiState,
    after: MorrowUiState,
    events: List<Map<String, Any?>>,
  ) {
    val at = now()
    for (signal in NotificationSignals.of(before, after, events, at)) {
      when (signal) {
        is NotificationSignal.Approval -> {
          if (!notifiedApprovals.add(signal.requestId)) continue
          while (notifiedApprovals.size > MAX_SEEN) {
            notifiedApprovals.remove(notifiedApprovals.first())
          }
          deliver(
            NotifyContent.of(
              kind = NotifyKind.Approval,
              tool = signal.toolName,
              deepLink = approvalsLink(signal.requestId),
              groupKey = signal.requestId,
            )
          )
        }

        is NotificationSignal.RunFinished -> {
          deliver(
            NotifyContent.of(
              kind = if (signal.failed) NotifyKind.Stuck else NotifyKind.Completed,
              seconds = signal.elapsedSeconds,
              reason = signal.reason,
              deepLink = activityLink(signal.sessionId),
              groupKey = signal.runId.ifBlank { "run-${signal.sessionId}" },
            )
          )
          //
          //  · 前台服务继续挂着，白占一个名额；
          //    （用户也划不掉，因为它是 ongoing）；
          //
          //
          // 不受影响。反过来就会把完成通知一起撤掉。
          sendStopIntent()
        }

        is NotificationSignal.MemberStuck -> deliver(
          NotifyContent.of(
            kind = NotifyKind.Stuck,
            deepLink = "morrow://${MorrowDeepLink.HOST_WORKBENCH}",
            groupKey = "member-${signal.index}",
          )
        )

        is NotificationSignal.Progress -> {
          val key = signal.runId.ifBlank { signal.sessionId }
          val last = lastProgressAt[key] ?: 0L
          if (at - last < PROGRESS_INTERVAL_MILLIS) continue
          lastProgressAt[key] = at
          deliver(
            NotifyContent.of(
              kind = NotifyKind.Progress,
              seconds = signal.elapsedSeconds,
              deepLink = activityLink(signal.sessionId),
              groupKey = key,
            )
          )
        }
      }
    }
  }

  private fun deliver(content: NotifyContent) {
    when (val decision = policy.decide(content, DoNotDisturb.isOn(appContext))) {
      is NotifyDecision.Deliver -> notifier.post(content, decision)
      is NotifyDecision.SuppressIntoDigest -> notifier.postDigest(decision)
      NotifyDecision.Drop -> Unit
    }
  }

  private fun sendStopIntent() {
    runCatching {
      appContext.startService(
        android.content.Intent(appContext, ForegroundSyncService::class.java).apply {
          action = ForegroundSyncService.ACTION_STOP
        }
      )
    }
  }

  private fun approvalsLink(requestId: String): String =
    "morrow://${MorrowDeepLink.HOST_APPROVALS}" +
      if (MorrowDeepLink.isSafeFocusId(requestId)) "?focus=$requestId" else ""

  private fun activityLink(sessionId: String): String =
    if (MorrowDeepLink.isSafeFocusId(sessionId)) {
      "morrow://${MorrowDeepLink.HOST_ACTIVITY}?focus=$sessionId"
    } else {
      "morrow://${MorrowDeepLink.HOST_WORKBENCH}"
    }

  private companion object {
    const val MAX_SEEN = 200

    /** 同一步的多次事件会连发；5 秒内的进度合并成一条（只更新不响铃）。 */
    const val PROGRESS_INTERVAL_MILLIS = 5_000L
  }
}

class RelaySyncTracking(
  private val onStart: (taskId: String, step: Int) -> Unit,
  private val onUpdate: (taskId: String, step: Int, startedAtMillis: Long) -> Unit,
  private val onStop: () -> Unit,
) {
  private var taskId: String? = null

  fun onBatch(after: MorrowUiState) {
    val active = after.runs.entries.firstOrNull()
    if (active == null) {
      if (taskId != null) {
        taskId = null
        onStop()
      }
      return
    }

    val run = active.value
    val id = run.runId.ifBlank { "run-${active.key}" }
    if (taskId != id) {
      taskId = id
      onStart(id, run.stepCount)
    } else {
      onUpdate(id, run.stepCount, run.startedAtMillis)
    }
  }
}
