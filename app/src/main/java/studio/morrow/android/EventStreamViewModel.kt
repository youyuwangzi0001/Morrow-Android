package studio.morrow.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EventStreamViewModel(
  private val scope: CoroutineScope,
  private val applyMs: Long = DELTA_APPLY_MS,
  private val now: () -> Long = { System.currentTimeMillis() },
) {

  companion object {
    /** 与桌面 `store.ts:492` 的 `DELTA_APPLY_MS` 同值 */
    const val DELTA_APPLY_MS = 24L
  }

  /** 当前状态。Compose 读它会订阅；只在 flush 时整体替换一次。 */
  var state: MorrowUiState by mutableStateOf(MorrowUiState())
    private set

  var lastInvokeError: String? by mutableStateOf(null)
    private set

  var onBatchApplied: ((before: MorrowUiState, after: MorrowUiState, events: List<Map<String, Any?>>) -> Unit)? = null

  fun onInvokeResult(ok: Boolean, error: String?) {
    lastInvokeError = if (ok) null else (error ?: "桌面未接受这次操作")
  }

  fun clearInvokeError() {
    lastInvokeError = null
  }

  fun onWorksLoaded(works: List<WorkView>?) {
    if (works == null) return
    state = state.copy(works = works)
  }

  /** 已缓冲的 delta（按到达顺序） */
  private val pending = mutableListOf<PendingDelta>()

  /** 待执行的 flush 任务。非 null 表示已排程 */
  private var flushJob: Job? = null

  /** 上一次真正把状态交给 Compose 的序号（用于统计与测试） */
  var lastFlushAtMillis: Long = 0L
    private set

  /** 真正执行的 flush 次数（不是事件数）。测试用来验证「不是每条事件都重组」 */
  var flushCount: Int = 0
    private set

  /** 缓冲里的分片数（测试用） */
  val pendingCount: Int get() = pending.size

  private data class PendingDelta(
    val sessionId: String,
    val messageId: String,
    val kind: DeltaKind,
    val text: String,
  )

  fun onEvent(parsed: EventParser.Parsed) {
    val action = parsed.action
    val before = state

    // ── seq 去重 ──
    //
    //  · 审批/工具事件 → 重复通知、重复计数；
    //
    if (parsed.seq > 0L && action !is EventAction.Snapshot && parsed.seq <= state.lastSeq) {
      return
    }

    if (action is EventAction.MessageDelta) {
      pending += PendingDelta(parsed.sessionId, action.messageId, action.kind, action.text)
      //
      // 缓冲只推迟上屏，不推迟 seq：seq 在解析后立刻推进，
      // 否则切走再回来会重复拉取这一段。
      if (parsed.seq > 0L) {
        state = state.copy(lastSeq = maxOf(state.lastSeq, parsed.seq))
      }
      scheduleFlush()
      return
    }

    // 顺序保证：先落地已缓冲的正文，再应用这条事件
    flushNow()
    applyToState(parsed)
    emitBatch(before, emptyList())
  }

  /** 只排程一次：已有定时任务时不再叠加（否则 40 条/秒会排 40 个 timer） */
  private fun scheduleFlush() {
    if (flushJob != null) return
    flushJob = scope.launch {
      delay(applyMs)
      flushJob = null
      flushNow()
    }
  }

  fun flushNow() {
    flushJob?.cancel()
    flushJob = null

    if (pending.isEmpty()) return

    // 手写第二份。
    //
    //
    val merged = EventReducer.coalesce(
      pending.map { EventAction.MessageDelta(it.sessionId, it.messageId, it.kind, it.text) },
    )
    pending.clear()

    var next = state
    for (action in merged) {
      next = EventReducer.apply(next, action)
    }
    state = next
    flushCount += 1
    lastFlushAtMillis = now()
  }

  /** 直接收一条原始 wire 事件（回放/补漏时用） */
  fun onRawEvent(raw: Map<String, Any?>) {
    val parsed = EventParser.parse(raw) ?: return
    onEvent(parsed)
  }

  fun onRawEvents(list: List<Map<String, Any?>>) {
    val before = state
    list.forEach { onRawEvent(it) }
    flushNow()
    emitBatch(before, list)
  }

  private fun emitBatch(before: MorrowUiState, events: List<Map<String, Any?>>) {
    val after = state
    if (after !== before) onBatchApplied?.invoke(before, after, events)
  }

  private fun applyToState(parsed: EventParser.Parsed) {
    state = EventReducer.apply(state, parsed.action, parsed.seq, now())
  }

  /** 切前台会话。也要先 flush，否则切走时最后几十毫秒的字会丢在缓冲里。 */
  fun selectSession(sessionId: String?) {
    flushNow()
    state = EventReducer.selectSession(state, sessionId)
  }

  fun onCleared() {
    flushNow()
  }
}
