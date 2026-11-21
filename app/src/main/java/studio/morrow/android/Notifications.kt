package studio.morrow.android

/**
 * 通知域：渠道与投递策略、推送解析、前台同步服务、审批动作与深链。
 *
 * 这些类原本分散在 `notify/` 的七个文件里，但它们共享同一套渠道常量、
 * 免打扰判定与内容清洗规则：改一处渠道 id，另外几处必须同时改。合并到一个
 * 文件后，这条不变式只在一屏内可见。
 */

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

// ── 原 notify/Notify.kt ──

// ───── NotifyKind.kt ─────
/**
 * Android NotificationManager 的重要性常量。
 */
object NotifyImportance {
  const val MIN = 1
  const val LOW = 2
  const val DEFAULT = 3
  const val HIGH = 4
}

/**
 * 通知的四类语义（蓝图 B4 原文）：
 */
enum class NotifyKind(
  /** 通知渠道 id。Android 8 起渠道是用户可见的开关粒度，必须一类别一渠道 */
  val channelId: String,
  /** 渠道名（设置页里用户看到的文字） */
  val channelName: String,
  /** 重要性。HIGH 会横幅弹出，只在「需要人立刻决定」时使用 */
  val importance: Int,
  /**
   * 免打扰开启时是否仍然发单条通知。
   *
   * 只有「审批」为 true —— 它是唯一「不处理就会卡住别人」的类别。
   * 其余三类在免打扰下退化成一条汇总，用户主动点开才看到明细。
   */
  val survivesDoNotDisturb: Boolean,
  /** 是否可折叠成汇总（汇总通知里显示「另有 N 条」） */
  val summarizable: Boolean,
  /**
   * 渠道描述（系统设置页里渠道名下方的小字）。
   *
   * 写给用户看的，不是给开发者看的日志 —— 要说明「打开/关闭这个渠道会有什么后果」，
   * 否则用户在设置里看到一个「任务完成」根本不知道关掉会怎样。
   */
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

  /**
   * 长任务进度。
   *
   * 刻意用 LOW：进度更新可能每秒来一次，DEFAULT/HIGH 会变成骚扰。
   * 常驻进度走前台服务（ForegroundSyncService），不靠反复发通知。
   */
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

// ───── NotifyChannels.kt ─────
/**
 * 通知渠道注册。
 * 「审批请求」。所以四类语义必须映到四个渠道，不能共用一个。
 * 能做的只有让上层知道「这条渠道的实际重要性已经和代码不一致」。
 */
object NotifyChannels {

  /**
   * 注册结果。
   *
   * @param channelIds 已注册（或已存在）的渠道 id，顺序与 [NotifyKind.all] 一致
   * @param importanceMismatches 期望重要性与实际不符的渠道；空表示一切正常
   */
  data class Registration(
    val channelIds: List<String>,
    val importanceMismatches: Map<String, Int>
  ) {
    /** 是否是干净的首次安装状态（没有任何渠道被旧的错误重要性锁住） */
    val isClean: Boolean get() = importanceMismatches.isEmpty()
  }

  /**
   * 建齐四个渠道。可重复调用（每次启动都调也无害）。
   */
  fun register(context: Context): Registration {
    val manager = context.getSystemService(NotificationManager::class.java)
      ?: return Registration(emptyList(), emptyMap())

    for (kind in NotifyKind.all()) {
      manager.createNotificationChannel(kind.toChannel())
    }

    val mismatches = buildMap {
      for (kind in NotifyKind.all()) {
        // 读回来的可能是 null（渠道被用户或系统清掉），那种情况不算不一致：
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

  /**
   * 把一个 [NotifyKind] 变成渠道对象。
   *
   * 渠道参数**全部取自 NotifyKind**，这里不写任何字面量 ——
   * 否则「代码改了枚举、渠道还是旧值」这类不一致会没有任何报错地存在。
   */
  private fun NotifyKind.toChannel(): NotificationChannel =
    NotificationChannel(channelId, channelName, importance).apply {
      description = channelDescription
      // 低重要性（进度）不挂角标：一个每秒都在更新的通知挂个红点等于骚扰
      setShowBadge(importance >= NotifyImportance.DEFAULT)
      // 只有需要人立刻决定的类别才震动
      enableVibration(survivesDoNotDisturb)
      // 只有审批尝试突破免打扰。
      // 注意：真正生效需要用户授予「勿扰访问权限」，未授予时系统直接忽略这个标志，
      // 且**不会报错**。所以策略层（NotifyPolicy）必须自己再判一次，
      // 不能指望这个标志把审批送出来。
      setBypassDnd(survivesDoNotDisturb)
    }
}

// ───── NotifyPolicy.kt ─────
/**
 * 免打扰（DND）联动与通知汇总。
 * 而用户完全不知道有人在等。所以这里的规则是：
 * 与「免打扰只保留汇总」写在同一条验收里，说明两者必须共存。
 * - 只在**有被压下的条目**时才发；没东西可汇总就不发（不发空通知）
 */

/**
 * 汇总状态：被压下的条目计数与最近一条的类别。
 *
 * 刻意**不含时间戳**：汇总文案只用「数量 + 粗分类」，时间既不展示也不参与判断，
 * 存一个没人读的字段只会让后来的人以为「以后要用」，从而不敢删。
 */
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

  /**
   * 不发单条，但要更新汇总。
   *
   * `silent = true` 表示汇总更新不响铃 —— 免打扰期间用户的意图就是「别吵」。
   */
  data class SuppressIntoDigest(val digest: NotifyContent, val silent: Boolean) : NotifyDecision

  /** 完全不发（例如免打扰下的进度更新且汇总已达上限） */
  data object Drop : NotifyDecision
}

/**
 * 免打扰策略 + 汇总计数。
 * 这里负责的是**策略正确性**，两者不能互相替代。
 */
class NotifyPolicy {
  private var suppressed = 0
  private var lastKind: NotifyKind? = null

  /** 汇总通知的稳定 id（同一条不断更新，不堆积） */
  fun digestNotificationId(): Int = DIGEST_ID

  /** 当前汇总状态（诊断/测试用） */
  fun digestState(): DigestState = DigestState(suppressed, lastKind)

  /**
   * 决定一条通知该怎么发。
   *
   * @param doNotDisturb 系统免打扰是否开启
   */
  fun decide(content: NotifyContent, doNotDisturb: Boolean): NotifyDecision {
    // 审批永远照常发：它是唯一「不处理就卡住别人」的类别
    if (content.kind.survivesDoNotDisturb || !doNotDisturb) {
      return NotifyDecision.Deliver(content)
    }

    // 免打扰下其余类别进汇总
    if (!content.kind.summarizable) {
      // 理论上不会走到（不可汇总又不豁免 = 配置错误），保守丢弃而不是硬发
      return NotifyDecision.Drop
    }

    suppressed += 1
    lastKind = content.kind

    val digest = NotifyContent.digest(
      kind = content.kind,
      suppressedCount = suppressed,
      lastKindLabel = lastKind?.let { kindLabel(it) } ?: ""
    )

    return NotifyDecision.SuppressIntoDigest(digest = digest, silent = true)
  }

  /** 用户点开汇总（或关闭免打扰）后清空累积 */
  fun clearDigest() {
    suppressed = 0
    lastKind = null
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
  }
}

// ───── NotifyContent.kt ─────
/**
 * 通知正文的最小化。
 * ## 允许进入 body 的东西（只有这些）
 */

/** 固定文案：写死在这里，避免调用方拼字符串时把变量带进去 */
object NotifyPhrase {
  const val APPROVAL_TITLE = "等待你批准"
  const val COMPLETED_TITLE = "任务完成"
  const val STUCK_TITLE = "任务卡住"
  const val PROGRESS_TITLE = "正在执行"
  const val DIGEST_TITLE = "Morrow 有新的进展"

  const val APPROVAL_ACTION_ALLOW = "允许"
  const val APPROVAL_ACTION_DENY = "拒绝"

  /** 「另有 N 条」——数字由 [NotifyContent.summaryBody] 统一格式化 */
  fun moreItems(n: Int): String = "另有 $n 条"
}

/**
 * 通知内容。**只能通过 [NotifyContent.of] 构造。**
 *
 * 构造函数为 private：调用方无法绕过清洗直接 new 一个带任意 body 的对象。
 */
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
    /**
     * 从**结构化输入**构造通知内容。
     *
     * 注意参数类型：这里没有任何一个是「任意字符串」。`fileName` 会再被
     * [fileNameOf] 削一次基名（防调用方传进整条路径），`seconds` 是数字。
     */
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
      groupKey: String = kind.channelId
    ): NotifyContent {
      val title = when (kind) {
        NotifyKind.Approval -> NotifyPhrase.APPROVAL_TITLE
        NotifyKind.Completed -> NotifyPhrase.COMPLETED_TITLE
        NotifyKind.Stuck -> NotifyPhrase.STUCK_TITLE
        NotifyKind.Progress -> NotifyPhrase.PROGRESS_TITLE
      }

      val parts = buildList {
        // ① 文件名（削成基名）
        fileName?.let { name ->
          fileNameOf(name)?.let { add(it) }
        }
        // ② 工具标识（白名单形态：小写 + 下划线，长度受限）
        tool?.let { t -> if (isSafeToolId(t)) add(t) }
        // ③ 调用方给的固定短句 —— 也过一遍长度与字符检查
        detail?.let { d -> if (isSafeDetail(d)) add(d) }
        // ④ 耗时
        if (seconds > 0) add(formatDuration(seconds))
        // ⑤ 额外条目数
        if (extraCount > 0) add(NotifyPhrase.moreItems(extraCount))
      }

      // body 为空是合法状态（例如「任务完成」没有文件名时），
      // 但**不允许**因此塞进任何兜底文本 —— 兜底文本最容易带上敏感信息。
      val body = parts.joinToString(" · ")

      val actions = when (kind) {
        NotifyKind.Approval -> listOf(
          NotifyAction(NotifyActionId.ALLOW, NotifyPhrase.APPROVAL_ACTION_ALLOW),
          NotifyAction(NotifyActionId.DENY, NotifyPhrase.APPROVAL_ACTION_DENY)
        )
        // 其余三类只提供「打开」—— 完成/卡住/进度没有需要就地做的决定
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

    /**
     * 从任意字符串里取「基名」。
     * （`git push --force origin main` 恰好只有白名单字符，
     */
    fun fileNameOf(raw: String): String? {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) return null
      // 控制字符优先拒绝：`report.txt\nhttps://evil` 这类注入必须先挡
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

    /**
     * 附加短句的安全检查。
     *
     * 允许中文、字母、数字、常见标点与空格；**不允许**路径分隔符
     * （出现 `\` 或 `/` 说明这多半是路径而不是短句）、换行、以及超长内容。
     */
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

    /**
     * 汇总通知。由 [NotifyPolicy] 调用。
     *
     * 单独开一个工厂而不是让 Policy 直接 new：构造函数仍是 private，
     * 「任意字符串进 body」的入口依然不存在 —— 汇总文案也在这里生成。
     */
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

    /** 时长文案：只给到「分」，秒级精度对用户无意义且会让 body 频繁变化 */
    fun formatDuration(seconds: Long): String {
      val s = seconds.coerceAtLeast(0)
      if (s < 60) return "用时 ${s} 秒"
      val m = s / 60
      if (m < 60) return "用时 $m 分钟"
      val h = m / 60
      return "用时 $h 小时 ${m % 60} 分钟"
    }

    const val MAX_FILE_NAME = 64
    const val MAX_DETAIL = 24
    const val MAX_DEEP_LINK = 256

    /** 文件名里最多允许几个空格（普通文件名极少有空格；命令行通常有很多） */
    const val MAX_FILE_NAME_SPACES = 2

    /**
     * 文件名白名单。
     */
    private val SAFE_FILE_NAME = Regex("^[\\p{L}\\p{N} ._\\-()\\[\\]]{1,64}$")
  }
}

/** 动作按钮。id 是稳定枚举，不是字符串 —— 防手滑拼错导致按钮失效 */
data class NotifyAction(val id: NotifyActionId, val label: String)

enum class NotifyActionId { ALLOW, DENY }

// ───── MorrowNotifier.kt ─────
/**
 * 把 [NotifyDecision] 变成真实通知。
 * category 等字段，**忽略 extra**。如果「允许」和「拒绝」只在 extra 上有区别，
 * ### 2. PendingIntent 必须 IMMUTABLE
 * PendingIntent 也不能改写里面的 Intent。可变的 PendingIntent 是经典的
 */
class MorrowNotifier(private val context: Context) {

  /** 投递结果。用密封类而不是 Boolean：不同失败原因需要不同的界面反应 */
  sealed interface PostResult {
    /** 已经交给系统 */
    data class Posted(val notificationId: Int) : PostResult

    /** 对应渠道被用户关闭（或整个应用的通知权限被关） */
    data class ChannelBlocked(val channelId: String) : PostResult

    /**
     * 这条进度由前台服务的常驻通知接管，不再单独发。
     *
     * 同一任务在通知栏出现两条（常驻进度 + 单条进度）时，两条数字可能不一致，
     * 用户无法判断哪条是真的。所以「谁在跟盯」这件事必须有唯一来源。
     */
    data class TakenOverByForegroundService(val taskId: String) : PostResult
  }

  /**
   * 投递一条「单条」通知。
   *
   * 通知 id 取 `groupKey` 的稳定哈希：同一任务的多条通知互相覆盖而不是堆积
   * （一个跑了 5 分钟的任务不该在通知栏留下 40 条进度）。
   */
  fun post(content: NotifyContent, decision: NotifyDecision.Deliver): PostResult {
    val channelId = content.kind.channelId
    if (!isChannelEnabled(channelId)) return PostResult.ChannelBlocked(channelId)

    // 同一个任务已经在前台服务里跟盯时，进度单条会让通知栏出现两条
    // 互相矛盾的数字。让常驻通知独占，这里直接不投。
    // 判断放在渠道检查之后：渠道被关是更明确的失败原因，应该优先报出。
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
      // 正文可能为空（例如「任务完成」没有文件名）。空正文不该留一行空白，
      // 用 BigTextStyle 让它折叠成一行。
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

  /**
   * 投递/更新汇总通知。
   *
   * 固定 id（[NotifyPolicy.DIGEST_ID]）：反复更新同一条，而不是每次新增一条。
   * 静音：免打扰期间用户的意图就是「别吵」，汇总的更新也不该响。
   */
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
      // 汇总不自动消失：它是「还有 N 条你没看」的入口，划掉就等于承认看过了
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(contentIntent(content))

    NotificationManagerCompat.from(context).notify(NotifyPolicy.DIGEST_ID, builder.build())
    return PostResult.Posted(NotifyPolicy.DIGEST_ID)
  }

  /** 用户看过汇总后撤掉它 */
  fun cancelDigest() {
    NotificationManagerCompat.from(context).cancel(NotifyPolicy.DIGEST_ID)
  }

  /**
   * 撤掉某个任务的「进度单条」通知。
   * ## 为什么必须有这个方法
   */
  fun cancelSingleProgress(taskId: String) {
    // 通知 id 的算法必须与 post() 里的完全一致，否则撤不掉。
    // 这里刻意复用同一个函数而不是自己算 —— 两处算两遍是这类 bug 的温床。
    val id = progressNotificationId(taskId)
    NotificationManagerCompat.from(context).cancel(id)
  }

  // ───────────────── 内部 ─────────────────

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

  /**
   * 动作按钮的 Intent。
   *
   * 「允许」→ Activity（必须过生物识别，C4）；「拒绝」→ 广播（安全方向，立即生效）。
   */
  private fun actionIntent(content: NotifyContent, action: NotifyAction): PendingIntent? {
    val approvalId = (MorrowDeepLink.parse(content.deepLink) as? MorrowDestination.Approvals)
      ?.focusId
      ?: return null

    val base = "${MorrowDeepLink.SCHEME}://${MorrowDeepLink.HOST_APPROVALS}?focus=$approvalId"

    return when (action.id) {
      NotifyActionId.ALLOW -> activityIntent("$base&${MorrowDeepLink.PARAM_DECISION}=allow")

      NotifyActionId.DENY -> {
        val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
          // data 必须不同，否则两个 PendingIntent 会被系统判成同一个（见类注释）
          data = Uri.parse("$base&${MorrowDeepLink.PARAM_DECISION}=deny")
          // 广播 Intent 也必须显式指定包名，避免被其他应用截获
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
      // singleTop + CLEAR_TOP：点通知不该在栈里堆出第二个 MainActivity
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

  /**
   * NotificationCompat 的 priority 与渠道 importance 是两套刻度
   * （前者给 Android 7 及以下，后者给 8+）。两处都要设，否则 8 以下
   * 的通知会全是默认优先级。
   */
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

/**
 * 同一任务的通知复用同一个 id，不同任务的通知必须用不同 id。
 * ## 为什么不能只用 groupKey
 */
internal fun NotifyContent.notificationId(): Int {
  val focusId = when (val dest = MorrowDeepLink.parse(deepLink)) {
    is MorrowDestination.Approvals -> dest.focusId
    is MorrowDestination.Activity -> dest.focusId
    MorrowDestination.Workbench -> null
  }
  val key = focusId ?: groupKey
  return stableNotificationId(key)
}

/**
 * 某个任务的「进度单条」通知 id。
 *
 * 与 [notificationId] 必须给出同一个结果：前台服务启动时用它撤掉旧的单条，
 * 算得不一样就撤不掉，通知栏会同时挂着两条。
 */
internal fun progressNotificationId(taskId: String): Int = stableNotificationId(taskId)

/**
 * 这条通知属于哪个任务的进度。
 *
 * 进度通知的 `groupKey` 就是任务 id（发进度时调用方传任务 id 作 groupKey）。
 * 深链里若有 focus，以 focus 为准 —— 两个来源不一致时，
 * focus 更接近「这一条」的语义。
 */
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
  // abs 处理 Int.MIN_VALUE：Math.abs(Int.MIN_VALUE) 仍是负数
  val h = key.hashCode()
  return if (h == Int.MIN_VALUE) 1 else kotlin.math.abs(h) % 100_000 + 1
}
// ── 原 notify/Approval.kt ──

// ───── ApprovalActions.kt ─────
/**
 * 审批动作的执行闸门。
 * 这两条放在一起会得出一个关键结论：**通知上的「允许」按钮不能直接批准**。
 */
object ApprovalActions {

  /**
   * 一条待执行的审批决策。
   * 注意：这**不能**替代真正的生物识别（那需要 Android API），
   */
  class ApprovalRequest private constructor(
    /** 审批 id（来自深链的 focus 参数，已过白名单） */
    val approvalId: String,
    /** 是否允许 */
    val allow: Boolean
  ) {
    override fun toString(): String = "ApprovalRequest(id=$approvalId, allow=$allow)"

    companion object {
      /**
       * 只有在生物识别已通过（或用户已明确确认）时才调用。
       *
       * @param approvalId 已过 [MorrowDeepLink] 白名单的 id；非法形态返回 null
       */
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

  /**
   * 已处理的审批 id 集合（进程内）。
   * 用有限容量而不是无限 Set：长时间运行的应用不能无限记住历史，
   */
  private val handled = object : LinkedHashSet<String>() {
    override fun add(element: String): Boolean {
      val added = super.add(element)
      while (size > MAX_REMEMBERED) remove(first())
      return added
    }
  }

  const val MAX_REMEMBERED = 256

  /**
   * 执行一条**已校验**的审批。
   *
   * @param sink 真正下发的地方（接 B 轨的 relay 通道）。返回 false 表示下发失败
   */
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
      // 下发失败要把 id 放出来，否则用户再点一次会被当成「已处理」，
      // 这条审批就永远批不下去了 —— 失败必须可重试。
      synchronized(handled) { handled.remove(request.approvalId) }
      return Result.Unknown(request.approvalId)
    }
    return Result.Dispatched(request.approvalId, request.allow)
  }

  /** 清空去重记录（测试与「切换桌面」时用） */
  fun resetForTest() {
    synchronized(handled) { handled.clear() }
  }
}

// ───── ApprovalDecisionInbox.kt ─────
/**
 * 审批决策的本地收件箱。
 * （桌面关机、网络断了、App 刚被系统回收）。这时决策不能丢，也不能假装成功 ——
 */
object ApprovalDecisionInbox {

  /** 一条待下发的决策 */
  data class Pending(val approvalId: String, val allow: Boolean, val atMillis: Long)

  /**
   * 真正把决策送到桌面的地方。B 轨通道接好后由应用在启动时注册。
   *
   * 返回 false 表示「现在发不出去」（未连接 / 被拒），决策会继续留在队列里。
   */
  private var sink: ((approvalId: String, allow: Boolean) -> Boolean)? = null

  private val queue = mutableListOf<Pending>()

  /** 最多记住多少条未发出的决策。长时间离线不应该无限占内存 */
  const val MAX_QUEUE = 64

  fun registerSink(newSink: ((String, Boolean) -> Boolean)?) {
    sink = newSink
  }

  /**
   * 收下一条决策并尽力立刻发出。
   *
   * @return true 表示已经发出去了（不在队列里）；false 表示仍待发
   */
  fun offer(approvalId: String, allow: Boolean, nowMillis: Long, context: Context? = null): Boolean {
    val current = sink
    if (current != null && current(approvalId, allow)) return true

    synchronized(queue) {
      // 同一条审批的同一个决策只留一条；后到的决策覆盖先到的
      // （用户先点拒绝又点允许，最终意图是允许）
      queue.removeAll { it.approvalId == approvalId }
      queue.add(Pending(approvalId, allow, nowMillis))
      while (queue.size > MAX_QUEUE) queue.removeAt(0)
    }
    // 通知里的「拒绝」可能在没有 Activity 的进程里发生：只存内存会随
    // 进程回收一起丢掉，用户以为拒绝生效了，桌面还卡着。落盘。
    if (context != null) persist(context)
    return false
  }

  /** 现在队列里有哪些待发决策（按到达顺序） */
  fun pending(): List<Pending> = synchronized(queue) { queue.toList() }

  /**
   * 通道恢复后调用：把队列里能发的都发出去，发不出去的留在队列。
   *
   * @return 本次成功发出的条数
   */
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

  /**
   * 从磁盘恢复未发出的决策（Application.onCreate）。
   *
   * 恢复后**不立刻 flush**：此时 relay 通常还没连上，flush 只会全部失败。
   * 连接就绪时由 MainActivity 调 [flush]。
   */
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

/**
 * 通知动作按钮的接收器。
 * 精确形态。`focus` 必须过白名单，`decision` 必须是 `deny` ——
 * 允许（allow）**永远不走广播**，它必须推开应用过生物识别（C4）。
 * 注意本类不 `return` 任何结果、也不依赖 `goAsync()`：
 */
class ApprovalActionReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val uri = intent.data ?: return
    if (uri.scheme != MorrowDeepLink.SCHEME) return

    // 从通知来的「允许」必须走 Activity 的生物识别门，绝不能在后台直接生效
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

// ───── DoNotDisturb.kt ─────
/**
 * 系统免打扰（DND）状态的读取。
 * 否则两处判定不同步时，同一个免打扰状态会得出两种行为。
 */
object DoNotDisturb {

  /**
   * 免打扰是否正在拦截普通通知。
   *
   * 读不到时返回 `false`：宁可多发一条，也不要因为一次读失败
   * 让用户漏掉一条审批（审批是唯一会让桌面 agent 卡住的类别）。
   */
  fun isOn(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return try {
      manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    } catch (_: SecurityException) {
      // 极少数定制 ROM 上读这个需要权限；拿不到就按「没开免打扰」处理
      false
    }
  }
}
// ── 原 notify/NotifyPush.kt ──

// ───── FcmPayload.kt ─────
/**
 * 推送消息（Tier 3：FCM + 拉取）的解析。
 * - **data-only**（只有 `data`，没有 `notification` 段）：应用被杀时系统
 * 并且必须被**记录**（[FcmDiagnostics]），否则这类配置错误永远查不出来。
 * 桌面升级后可能发来手机端还不认识的 kind。丢弃是对的（不能猜语义），
 * 只有两样东西：`data: Map<String, String>` 与 `notification != null`。
 */
object FcmPayload {

  const val KEY_KIND = "kind"
  const val KEY_TASK_ID = "taskId"
  const val KEY_FOCUS = "focus"
  const val KEY_DETAIL_CODE = "detailCode"
  const val KEY_SNAPSHOT_REQUIRED = "snapshotRequired"

  /**
   * 允许出现的键。
   *
   * 表外的键一律**不读**（不是「读了再过滤」）。这样即便服务端多发了
   * 一个 `body` 或 `title`，它也没有任何机会进入通知 ——
   * 代码里根本没有读它的语句。
   */
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

  /**
   * `detailCode` 的取值 → 本地固定文案。
   */
  private val DETAIL_MAP: Map<String, String> = mapOf(
    "tool_run" to "需要你允许执行工具",
    "permission_change" to "需要你确认权限变更",
    "dangerous_action" to "需要你确认危险操作",
    "needs_input" to "等着你补充信息",
    "tool_failed" to "工具执行失败",
    "step_done" to "又完成一步"
  )

  /**
   * 已知的 detailCode。
   *
   * 目前只有测试在用：它逐条断言所有文案都在长度上限内、不含路径分隔符 ——
   * 这条断言的价值在于**加新码时不会漏掉约束**（码表是唯一需要维护的地方）。
   * 诊断页将来可以用它列出「服务端发过哪些码」。
   */
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
    /**
     * 是否需要全量快照。
     *
     * true 的典型场景：手机被系统杀了很久，期间的事件已经超出桌面端的
     * ring buffer —— 此时增量补拉会漏事件，必须先全量对齐。
     */
    val snapshotRequired: Boolean
  ) {
    /** 细节码对应的本地文案；没有细节就是 null */
    val detailText: String? get() = detailCode?.let { DETAIL_MAP[it] }
  }

  sealed interface Result {
    data class Accepted(val parsed: Parsed) : Result
    data class Rejected(val reason: Reason, val atMillis: Long) : Result
  }

  /**
   * 解析一条推送。
   *
   * @param data FCM 的 data 段（`RemoteMessage.getData()`）
   * @param hasNotificationBlock `RemoteMessage.getNotification() != null`
   * @param nowMillis 用于给拒绝记录打时间戳（便于诊断「什么时候开始异常的」）
   */
  fun parse(
    data: Map<String, String>?,
    hasNotificationBlock: Boolean,
    nowMillis: Long = System.currentTimeMillis()
  ): Result {
    // ① 带 notification 段：系统会自己弹一条未经清洗的通知。
    // 这不是「我们少做了一步」，而是「我们的所有策略被绕过了」，
    // 必须记录，否则服务器配错了永远发现不了。
    if (hasNotificationBlock) {
      return reject(Reason.HAS_NOTIFICATION_BLOCK, nowMillis)
    }

    if (data.isNullOrEmpty() || data.size > MAX_DATA_KEYS) {
      return reject(Reason.EMPTY_OR_OVERSIZED, nowMillis)
    }

    // ② 任何一个值形态不合法就整条拒绝。
    // 不「只丢掉坏字段继续用剩下的」—— 一条推送的字段是互相配合的
    // （kind 决定读哪个字段），丢掉一半再用另一半更容易产生错误语义。
    for ((key, value) in data) {
      if (key in ALLOWED_KEYS && !isSafeValue(value)) {
        return reject(Reason.UNSAFE_DATA, nowMillis)
      }
    }

    val rawKind = data[KEY_KIND]?.takeIf { it.isNotBlank() }
      ?: return reject(Reason.MISSING_KIND, nowMillis)

    val kind = KIND_MAP[rawKind]
      ?: return reject(Reason.UNKNOWN_KIND, nowMillis)

    // ③ detailCode 未知时**不拒绝整条**，只是不带细节：
    // 通知本身（哪一类）是确定的，降级成「没有副标题」比丢掉一条审批好。
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
        // 只有明确的 "true" 才算；"1" / "yes" 一律不算 ——
        // 宁可多做一次全量，也不要因为拼写差异漏掉全量对齐。
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

  /**
   * 把解析结果变成一条通知内容。
   * 不是从 payload 里读一个 URI —— payload 只有一个 id，拼不出别的。
   */
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

/**
 * 推送异常记录（诊断用）。
 */
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

// ───── FcmReceiver.kt ─────
/**
 * 推送到达与「打开应用」这两个事件的接线点。
 * 这个对象存在的唯一理由是：**把分散的三层串起来，并且只有这一个地方串**。
 * 一个**必须依赖 Firebase SDK 才能编译**的类里，本机无法测试，
 * 1. **补拉标记必须在通知投递之前留。** 投递可能因渠道被关而失败
 * 3. **投递必须在解析之后。** 通知内容由 `FcmPayload.toContent` 生成，
 */
object FcmReceiver {

  /**
   * 汇总计数必须**进程内唯一**。
   *
   * `NotifyPolicy` 是有状态的（记着免打扰期间累积了几条）。如果每次推送都
   * new 一个，汇总永远显示「另有 1 条」—— 用户永远看不到「另有 7 条」。
   */
  private val policy = NotifyPolicy()

  /**
   * 收到一条推送。
   *
   * @param data `RemoteMessage.getData()`
   * @param hasNotificationBlock `RemoteMessage.getNotification() != null`
   * @return 解析结果（被拒时也返回，便于调用方/测试断言原因）
   */
  fun onMessage(
    context: Context,
    data: Map<String, String>?,
    hasNotificationBlock: Boolean
  ): FcmPayload.Result {
    val result = FcmPayload.parse(data, hasNotificationBlock)

    when (result) {
      is FcmPayload.Result.Rejected -> {
        // 什么都没发生：不留补拉标记（否则打开时白拉一次）
        return result
      }

      is FcmPayload.Result.Accepted -> {
        val parsed = result.parsed
        // ① 先留标记：即便下面投递失败，事件本身也不能丢
        PendingPull.mark(context, parsed.snapshotRequired)

        // ② 再投递：内容由本地文案组成，策略在这里判定
        val content = FcmPayload.toContent(parsed)
        val decision = policy.decide(content, doNotDisturb = DoNotDisturb.isOn(context))
        val notifier = MorrowNotifier(context)
        when (decision) {
          is NotifyDecision.Deliver -> notifier.post(content, decision)
          is NotifyDecision.SuppressIntoDigest -> notifier.postDigest(decision)
          // Drop 是「不可汇总又不豁免」的配置错误兜底，正常不可达；
          // 显式列出而不是走 else，将来加新决策类型时编译器会提醒
          NotifyDecision.Drop -> Unit
        }
        return result
      }
    }
  }

  /**
   * 应用回到前台时调用（`Activity.onResume` / `onStart`）。
   * 拉失败必须 [PendingPull.restore] 放回去。
   * Tier 3 的语义是「不实时」—— 所以推送到达时不拉，只在这里拉。
   */
  fun onResume(context: Context): PendingPull.Request? = PendingPull.consume(context)

  /**
   * 清空免打扰期间的汇总累积。
   */
  fun clearDigest() = policy.clearDigest()

  /** 当前汇总累积了几条。诊断用；界面将来可以显示「另有 N 条未看」 */
  fun digestState() = policy.digestState()
}

// ───── MorrowDeepLink.kt ─────
/**
 * 通知点击落点的解析。
 * ## 为什么必须白名单解析，而不是直接把 URI 传给导航
 */
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

  /**
   * 这个字符串能不能安全地作为 focus 参数拼进深链。
   * 只在解析侧校验不够：`"ap-1&decision=deny"` 拼进 URL 后会被解析成
   */
  fun isSafeFocusId(value: String): Boolean = SAFE_ID.matches(value)

  /**
   * 解析深链。
   * 拒绝的就是 [MorrowDestination.Workbench] —— 宁可跳首页，也不要跳到
   */
  fun parse(raw: String?): MorrowDestination {
    if (raw.isNullOrBlank()) return MorrowDestination.Workbench

    val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull()
      ?: return MorrowDestination.Workbench

    // scheme 必须精确等于 morrow。`MORROW://` 也算不同 scheme（URI scheme 无大小写规范），
    // 一律不认 —— 通知里的链接是我们自己拼的，没有兼容外部写法的必要。
    if (uri.scheme != SCHEME) return MorrowDestination.Workbench

    val host = uri.host ?: return MorrowDestination.Workbench
    val focus = focusIdOf(uri)

    return when (host) {
      HOST_WORKBENCH -> MorrowDestination.Workbench
      HOST_APPROVALS -> MorrowDestination.Approvals(focus)
      HOST_ACTIVITY -> MorrowDestination.Activity(focus)
      // 未知 host：包括未来版本才有的页面（旧版本 App 收到新链接）
      else -> MorrowDestination.Workbench
    }
  }

  /**
   * 取 focus 参数：优先 query（`?focus=`），其次路径段。
   *
   * 非法形态返回 null 而不是原样返回 —— 页面会退化成「不聚焦」，
   * 而不是拿着一个可疑字符串去查表。
   */
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

  /**
   * 从深链里读「用户在通知上按的是允许还是拒绝」。
   * 的 Intent 只在 extra 上不同，**它们会被判成同一个 PendingIntent**，
   */
  fun requestedDecision(uri: android.net.Uri): Boolean? =
    when (uri.getQueryParameter(PARAM_DECISION)) {
      "allow" -> true
      "deny" -> false
      else -> null
    }
}
// ── 原 notify/Sync.kt ──

// ───── ForegroundSyncService.kt ─────
/**
 * 长任务跟盯的前台服务。
 * ## 为什么必须是前台服务，而不是普通通知
 * 「渲染到哪一步了」—— 这条进度必须一直在。
 * 代价是必须挂一条**不可划掉**的常驻通知（用户能看到、能点、能关服务）。
 * | 8.0（26） | 渠道必须存在，否则静默不显示 | 由 `MorrowApplication` 启动时注册 |
 */
class ForegroundSyncService : Service() {

  /**
   * 用户的「看得见程度」。
   * 而「跟盯中但用户看不见」这件事必须被界面知道 —— 否则用户以为任务丢了。
   */
  sealed interface Visibility {
    /** 常驻通知可见 */
    data object Visible : Visibility

    /**
     * 服务在跑，但通知不可见（Android 13+ 没给 POST_NOTIFICATIONS）。
     *
     * 这时任务仍在跟盯，但用户不会看到任何提示 ——
     * 界面必须能在应用内标注进度，否则「跟盯中」这件事对用户不存在。
     */
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
        update(taskId, step, total, startedAt)
        return START_NOT_STICKY
      }
    }

    // 默认动作 = 开始跟盯
    val taskId = intent?.getStringExtra(EXTRA_TASK_ID).orEmpty()
    if (taskId.isBlank()) {
      // 没有任务标识就没有可跟盯的东西。不停在「前台但什么都没跟」的状态，
      // 那会白占一个前台服务名额并让用户看到一条永远 0% 的通知。
      stopSelf()
      return START_NOT_STICKY
    }
    val step = intent?.getIntExtra(EXTRA_STEP, 0) ?: 0
    val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0
    start(taskId, step, total)
    return START_NOT_STICKY
  }

  private fun start(taskId: String, step: Int, totalSteps: Int) {
    val running = SyncState.Running(
      taskId = taskId,
      step = step,
      totalSteps = totalSteps,
      startedAtMillis = System.currentTimeMillis()
    )
    ForegroundSyncState.set(running)

    // 启动前先撤掉「同一任务」的普通进度单条通知，否则通知栏里会同时出现
    // 「常驻进度」和「这条任务的进度单条」，两条数字还可能不一致。
    // 去重必须在这里做，不能指望调用方记得。
    MorrowNotifier(this).cancelSingleProgress(taskId)

    val notification = buildNotification(running)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Android 10 起要显式传 type；14 起不传会抛 MissingForegroundServiceTypeException
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

    // 只更新、不重建：同一个 id 调 notify() 会原地替换。
    // 如果这里改成先 stopForeground 再 startForeground，通知会闪一下，
    // 并且在校验严格的系统上可能被判为「重复启动前台服务」。
    val manager = getSystemService(NotificationManager::class.java) ?: return
    manager.notify(ForegroundSyncState.SYNC_NOTIFICATION_ID, buildNotification(running))
  }

  /**
   * Android 15 的 dataSync 时限回调。
   *
   * 不实现这个方法 → 到点后系统抛 `ForegroundServiceDidNotStopInTimeException`，
   * 应用直接崩。这不是理论风险：时限是 6 小时累计，一个「渲染」类长任务
   * 加几次重试就会碰到。
   */
  override fun onTimeout(startId: Int) {
    stopSync(SyncStopReason.SYSTEM_TIMEOUT)
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    stopSync(SyncStopReason.SYSTEM_TIMEOUT)
  }

  override fun onDestroy() {
    // 走到这里说明不是我们主动 stopSync 的路径（系统回收 / 进程被杀前）。
    // 如果状态还是 Running，记录下来 —— 界面需要知道「跟盯被打断了」，
    // 而不是把它当成「任务正常结束」。
    if (ForegroundSyncState.isRunning()) {
      ForegroundSyncState.recordStop(SyncStopReason.SYSTEM_DESTROYED)
    }
    super.onDestroy()
  }

  private fun stopSync(reason: SyncStopReason) {
    ForegroundSyncState.recordStop(reason)
    // STOP_FOREGROUND_REMOVE：连通知一起撤掉。
    // 用 stopForeground(true) 在 API 24+ 已废弃，语义也不如显式常量清楚。
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
      // 常驻：不可划掉。这是前台服务的语义 —— 用户能停的是「服务」，不是「通知」。
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setContentIntent(openIntent(state.taskId))
      .addAction(0, "停止跟盯", stopIntent())

    if (percent != null) {
      builder.setProgress(100, percent, false)
    } else {
      // 总量未知：indeterminate 进度条。
      // 不显示「0%」是因为那会让人以为卡住了。
      builder.setProgress(0, 0, true)
    }

    return builder.build()
  }

  /** 进度文案：总量未知时只说步数，不说百分比 */
  private fun progressText(state: SyncState.Running): String {
    val percent = state.percent
    // 「已用时」把 startedAtMillis 变成用户看得懂的信息。
    // 不加这一项的话它就是个只写不读的字段（审查时删过一次类似的死字段）。
    val elapsed = formatElapsed(state.elapsedSeconds(System.currentTimeMillis()))
    return if (percent == null) {
      "已完成 ${state.step} 步 · 已用时 $elapsed"
    } else {
      "$percent% · ${state.step}/${state.totalSteps} 步 · 已用时 $elapsed"
    }
  }

  /** 时长只到分钟：秒级精度对「跟盯」这个场景没有意义，还会让通知每秒变一次 */
  private fun formatElapsed(seconds: Long): String = when {
    seconds < 60 -> "${seconds}秒"
    seconds < 3600 -> "${seconds / 60}分钟"
    else -> "${seconds / 3600}小时${(seconds % 3600) / 60}分"
  }

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

    /**
     * 开始跟盯。
     * **只能从 Activity 或 Application 调用**：Android 12 起禁止从后台
     */
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

// ───── PendingPull.kt ─────
/**
 * 「打开时补拉」的待办标记。
 * 为什么必须有这个东西：Tier 3 的全部价值在于「应用没在跑的时候也能知道
 * 消息，不够建立 WSS、不足以跑完一次握手。所以推送到达时**不能**去拉数据，
 * 这就带来三个必须做对的地方：
 * ### 1. 标记必须跨进程存活
 */
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
    /**
     * 第一次标记的绝对时刻。
     */
    val firstMarkedAtMillis: Long
  )

  /**
   * 收到一条推送时调用。可重复调用（幂等），每次都只是「覆盖 + 累加计数」。
   *
   * @param needsSnapshot 这条推送是否要求全量对齐。**true 会粘住**
   */
  fun mark(context: Context, needsSnapshot: Boolean, nowMillis: Long = System.currentTimeMillis()) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val editor = prefs.edit()

    val firstAt = prefs.getLong(KEY_FIRST_AT, 0L)
    if (firstAt == 0L) editor.putLong(KEY_FIRST_AT, nowMillis)

    editor.putBoolean(KEY_PENDING, true)
    // 粘性：已经要全量了，后来的增量推送不能把它降级
    if (needsSnapshot || prefs.getBoolean(KEY_SNAPSHOT, false)) {
      editor.putBoolean(KEY_SNAPSHOT, true)
    }
    editor.putInt(KEY_MARK_COUNT, prefs.getInt(KEY_MARK_COUNT, 0) + 1)
    editor.apply()
  }

  /** 有没有待补拉（不消费） */
  fun isPending(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

  /**
   * 取出并清空。
   * 读和清必须在同一个 `commit()` 里完成：如果先读再异步清，
   * 「拿走了」——必须确认写盘成功才能告诉调用方可以去拉。
   */
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

  /**
   * 补拉失败时把请求原样放回去（否则这批事件就永久丢了）。
   * 两件事都不能省：
   * 1. 全量要求必须保留 —— 重放时降级成增量，中间超出 ring buffer
   * 2. 首次标记时刻必须保留**原值**（而不是用 now 反推）——
   * 否则 consume→restore 之间的时间会被算进「离线时长」，
   */
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

// ───── SyncState.kt ─────
/**
 * 长任务进度的状态模型（纯逻辑，不接触 Android API）。
 * 状态本身的正确性不该依赖 Robolectric 能不能起 Service。
 */
sealed interface SyncState {

  /** 没有长任务在跑 */
  data object Idle : SyncState

  /**
   * 正在跑。
   */
  data class Running(
    val taskId: String,
    val step: Int,
    val totalSteps: Int,
    val startedAtMillis: Long
  ) : SyncState {

    /**
     * 进度百分比。null = 不确定（总量未知）。
     *
     * 注意 `coerceIn(0, 100)` 放在最后：桌面可能送来 step > totalSteps 的瞬间值
     * （并发任务的计数抖动），不夹住会出现「103%」这种明显错误的显示。
     */
    val percent: Int?
      get() = if (totalSteps <= 0) null
      else ((step.toLong() * 100) / totalSteps).toInt().coerceIn(0, 100)

    /** 已用时秒数（不小于 0：设备时钟回拨时不该显示负数） */
    fun elapsedSeconds(nowMillis: Long): Long =
      ((nowMillis - startedAtMillis) / 1000).coerceAtLeast(0)
  }
}

/**
 * 服务停止的原因。
 *
 * 不只是一个枚举 —— 它决定 UI 该怎么说。最容易被忽略的是 [SYSTEM_TIMEOUT]：
 * 这不是「任务完成了」，而是「系统不让我们继续跟盯了」，
 * 用户界面必须能区分这两件事，否则会看到「进度停在 47% 且再也没动」而无从判断。
 */
enum class SyncStopReason {
  /** 任务真的结束了 */
  COMPLETED,

  /** 用户手动取消跟盯 */
  USER_CANCELLED,

  /**
   * Android 15 起 dataSync 类前台服务有累计时限（6 小时 / 24 小时）。
   * 到点系统调用 `onTimeout`，必须主动停，否则抛
   * `ForegroundServiceDidNotStopInTimeException`。
   *
   * 这种情况下任务在桌面还在跑，只是手机不再跟盯。
   */
  SYSTEM_TIMEOUT,

  /** 系统直接回收了服务（低内存），走 onDestroy 而非 onTimeout */
  SYSTEM_DESTROYED,
}

/**
 * 进程内的前台服务状态。
 */
object ForegroundSyncState {

  /** 前台服务通知的固定 id。三个 id 空间互不重叠（见下方注释） */
  const val SYNC_NOTIFICATION_ID = 0x53594E43 // "SYNC" = 1,398,323,779

  /**
   * 状态本体。
   */
  private val stateHolder = mutableStateOf<SyncState>(SyncState.Idle)

  @Volatile
  private var lastStop: SyncStopReason? = null

  /** 当前状态。**在 @Composable 里读它会自动订阅变化** */
  fun current(): SyncState = stateHolder.value

  fun lastStopReason(): SyncStopReason? = lastStop

  /** 当前正在跟盯的任务 id；不在跑就是 null */
  fun runningTaskId(): String? = (stateHolder.value as? SyncState.Running)?.taskId

  fun isRunning(): Boolean = stateHolder.value is SyncState.Running

  /**
   * 此刻用户「看得见」这条常驻通知吗。
   *
   * 需要这个查询的原因是 `startForegroundService` 是异步的，启动那一刻
   * 拿不到结果；而「跟盯中但用户看不见」必须被界面知道。
   * 按需查「此刻」比缓一个启动瞬间的结果准确得多。
   */
  fun visibility(context: android.content.Context): ForegroundSyncService.Visibility {
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
      ?: return ForegroundSyncService.Visibility.NotificationSuppressed
    // 整个应用的通知被关掉，或者缺少 POST_NOTIFICATIONS 时，
    // areNotificationsEnabled() 为 false —— 服务照跑，但一条通知都看不见
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
// ── 原 notify/NotificationSignals.kt ──

/**
 * 一批事件里「值得打扰用户」的变化。
 *
 * 纯函数、不碰 Android：通知的**触发条件**和**怎么发**分开测，
 * 否则「审批来了但没发」这类缺陷只能靠真机发现。
 */
sealed interface NotificationSignal {
  /** 新审批。审批是唯一会让桌面 agent 停住的类别。 */
  data class Approval(
    val requestId: String,
    val toolName: String,
    val requestedAtMillis: Long,
  ) : NotificationSignal

  /** 一轮结束。[failed] 由 `run_finished.reason` 决定（不写进状态）。 */
  data class RunFinished(
    val sessionId: String,
    val runId: String,
    val failed: Boolean,
    val elapsedSeconds: Long,
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
  private val COMPLETED_REASONS = setOf("completed", "success", "succeeded")

  /**
   * @param events 这一批原始事件。只有 `run_finished` 需要读 reason。
   * @param nowMillis 用于算耗时；测试传固定值。
   */
  fun of(
    before: MorrowUiState,
    after: MorrowUiState,
    events: List<Map<String, Any?>>,
    nowMillis: Long,
  ): List<NotificationSignal> {
    val out = mutableListOf<NotificationSignal>()

    // ① 新审批：按 id 求差集，重复下发同一条不会重复通知。
    after.pendingApprovals.values
      .filter { it.requestId !in before.pendingApprovals }
      .sortedBy { if (it.requestedAtMillis > 0L) it.requestedAtMillis else Long.MAX_VALUE }
      .forEach { out += NotificationSignal.Approval(it.requestId, it.toolName, it.requestedAtMillis) }

    // ② 本轮结束。run 会从状态里删掉，所以「之前有、事件说结束」才算。
    events.filter { it["type"] == "run_finished" }.forEach { event ->
      val sessionId = event["sessionId"] as? String ?: return@forEach
      val prior = before.runs[sessionId] ?: return@forEach
      val reason = event["reason"] as? String
      out += NotificationSignal.RunFinished(
        sessionId = sessionId,
        runId = prior.runId,
        failed = reason != null && reason !in COMPLETED_REASONS,
        elapsedSeconds = secondsSince(prior.startedAtMillis, nowMillis),
      )
    }

    // ③ 成员卡住。首连快照（before.members == null）不通知：
    //    那些成员昨天就失败了，重连一次提醒一次等于骚扰。
    before.members?.let { priorMembers ->
      val alreadyFailed = priorMembers
        .filter { it.state in FAILED_MEMBER_STATES }
        .map { it.index }
        .toSet()
      after.members.orEmpty()
        .filter { it.state in FAILED_MEMBER_STATES && it.index !in alreadyFailed }
        .forEach { out += NotificationSignal.MemberStuck(it.index, it.title) }
    }

    // ④ 进度：同一 run（runId 相同）步数增加才算，新 run 的第一步不算。
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
// ── 原 notify/RelayNotifications.kt ──

/**
 * 事件流 → 系统通知。
 * 此前通知只有 FCM 推送这一条来路（且要 Google 服务）；普通 relay 连接
 */
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
          // 集合是进程内去重表，不是审计日志；只保留最近 200 条足够。
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

        is NotificationSignal.RunFinished -> deliver(
          NotifyContent.of(
            kind = if (signal.failed) NotifyKind.Stuck else NotifyKind.Completed,
            seconds = signal.elapsedSeconds,
            deepLink = activityLink(signal.sessionId),
            groupKey = signal.runId.ifBlank { "run-${signal.sessionId}" },
          )
        )

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
// ── 原 notify/RelaySyncTracking.kt ──

/**
 * 事件流 → 前台服务跟盯的启停。
 */
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

