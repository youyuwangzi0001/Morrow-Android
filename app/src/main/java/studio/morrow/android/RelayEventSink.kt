package studio.morrow.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import studio.morrow.android.EventAction
import studio.morrow.android.EventParser
import studio.morrow.android.EventStreamViewModel

class RelayEventSink(
  private val vm: EventStreamViewModel,
  private val dispatch: Dispatcher = MainThreadDispatcher(),
  private val sendAck: (Long) -> Unit = {},
) {

  /** 写入状态的执行方式 */
  interface Dispatcher {
    fun run(block: () -> Unit)
  }

  /** 切主线程（生产）。`mainExecutor` 在 Robolectric 里是同步执行的，测试可直接断言 */
  class MainThreadDispatcher(
    private val mainExecutor: (Runnable) -> Unit = { r -> MainThreadHolder.post(r) },
  ) : Dispatcher {
    override fun run(block: () -> Unit) = mainExecutor(Runnable(block))
  }

  /** 原地用快照包住（不依赖 Looper） */
  class SnapshotDispatcher : Dispatcher {
    override fun run(block: () -> Unit) {
      Snapshot.withMutableSnapshot { block() }
    }
  }

  /** 收到并成功应用的事件数 */
  var accepted: Int = 0
    private set

  /** 信封合法但不是运行时事件（invoke / result / ack） */
  var skippedNonEvent: Int = 0
    private set

  /** 一条上行 invoke 的应答。 */
  data class ResultFrame(
    val id: String,
    val ok: Boolean,
    /** 失败原因（桌面回帧的 `body.error.message`）；成功时为 null */
    val error: String?,
    val result: Map<String, Any?>? = null,
  )

  /** 最近一条结果（诊断用） */
  @Volatile
  var lastResult: ResultFrame? = null
    private set

  /** 结果回调。生产里用于把「审批已失效」这类失败显示给用户 */
  @Volatile
  var onResult: ((ResultFrame) -> Unit)? = null

  /** 信封不合法或 JSON 坏 */
  var invalid: Int = 0
    private set

  /** 信封合法、事件类型不认识（计入 Unparsed，不静默丢） */
  var unparsed: Int = 0
    private set

  /** 最近一次失败原因，便于把「为什么不动」显示在界面上/日志里 */
  var lastProblem: String? = null
    private set

  /** 全部失败（invalid + unparsed） */
  val failures: Int get() = invalid + unparsed

  /** 真机入口：一条解密后的明文 JSON */
  fun onText(text: String) {
    when (val r = RelayEnvelope.parseJson(text)) {
      is RelayEnvelope.Result.Ok -> applyEnvelope(r.envelope)
      is RelayEnvelope.Result.NotAnEvent -> {
        skippedNonEvent += 1
        if (r.kind == "result") acceptResult(r.envelope)
      }
      is RelayEnvelope.Result.Invalid -> {
        invalid += 1
        lastProblem = r.reason
      }
    }
  }

  /** 已解析成 Map 的信封（中继通道有时先解成对象再交过来） */
  fun onEnvelopeMap(raw: Map<String, Any?>) {
    when (val r = RelayEnvelope.parse(raw)) {
      is RelayEnvelope.Result.Ok -> applyEnvelope(r.envelope)
      is RelayEnvelope.Result.NotAnEvent -> {
        skippedNonEvent += 1
        if (r.kind == "result") acceptResult(r.envelope)
      }
      is RelayEnvelope.Result.Invalid -> {
        invalid += 1
        lastProblem = r.reason
      }
    }
  }

  private fun acceptResult(env: RelayEnvelope.Parsed?) {
    if (env == null) return
    val body = env.body
    val id = body["id"] as? String ?: ""
    val ok = body["ok"] == true
    val error = ((body["error"] as? Map<*, *>)?.get("message")) as? String
    val result = (body["result"] as? Map<*, *>)?.entries
      ?.associate { it.key.toString() to it.value }
    val frame = ResultFrame(id = id, ok = ok, error = error, result = result)
    lastResult = frame
    dispatch.run { onResult?.invoke(frame) }
  }

  private fun applyEnvelope(env: RelayEnvelope.Parsed) {
    val eventMap = RelayEnvelope.toEventMap(env)
    dispatch.run {
      val parsed = EventParser.parse(eventMap)
      if (parsed == null || parsed.action is EventAction.Unparsed) {
        unparsed += 1
        lastProblem = "UNPARSED: ${eventMap["type"] ?: "(无 type)"}"
        // 否则补漏会一直重拉这一条，形成死循环。
        vm.onEvent(
          EventParser.Parsed(
            seq = env.seq,
            sessionId = env.sessionId,
            action = if (parsed != null) parsed.action else EventAction.Unparsed(
              (eventMap["type"] as? String) ?: "?",
              "事件体无法解析",
            ),
          )
        )
      } else {
        accepted += 1
        vm.onEvent(parsed)
      }
    }
    //
    //
    // 本地另算一个必然对不上。
    if (env.seq > 0L) sendAck(env.seq)
  }
}

/** 主线程投递的默认实现（Android 上用 Looper，测试里由 Robolectric 同步执行） */
internal object MainThreadHolder {
  private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
  fun post(r: Runnable) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run() else handler.post(r)
  }
}
