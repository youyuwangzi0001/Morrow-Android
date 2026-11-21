package studio.morrow.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RelayConnectionController(
  private val scope: CoroutineScope,
  private val loadCredentials: () -> RelayCredentials.Load,
  private val state: RelayConnectionState,
  /** 用凭据造客户端。测试可注入假实现，生产就是 `RelaySocketClient` */
  private val clientFactory: (RelayCredentials.Stored, RelaySocketClient.Listener) -> RelayClient,
  /** 事件出口：D1d 的 `RelayEventSink.onText`。**不直接改 UI 状态** */
  private val onEventJson: (String) -> Unit,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val onReadyAction: () -> Unit = {},
  private val onWelcome: (RelayProtocol.Welcome) -> Unit = {},
  /** 退避序列（测试注入短序列，避免真等 30 秒） */
  private val backoffMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000),
) {

  companion object {
    /** 连续失败上限：超过就停在 Offline，等用户手动重试或下次进前台 */
    const val MAX_CONSECUTIVE_FAILURES = 6
  }

  private var job: Job? = null
  @Volatile
  private var client: RelayClient? = null

  fun send(json: String): Boolean = client?.send(json) ?: false

  val lastSendFailure: String? get() = (client as? RelaySocketClient)?.lastSendFailure

  /** 当前是否在跑（用于 onStart/onStop 幂等） */
  @Volatile
  var isRunning: Boolean = false
    private set

  @Volatile
  var stopCallCount: Int = 0
    private set

  fun start() {
    if (isRunning) return
    isRunning = true
    job = scope.launch { runLoop() }
  }

  /** 停止：取消循环并关闭 socket。阻塞中的 read 会因 socket.close 抛异常退出 */
  fun stop() {
    stopCallCount++
    isRunning = false
    client?.close()
    client = null
    job?.cancel()
    job = null
  }

  /** 用户手动重试（界面上的「重试」按钮） */
  fun retryNow() {
    stop()
    start()
  }

  private suspend fun runLoop() {
    val load = loadCredentials()

    val stored = when (load) {
      is RelayCredentials.Load.Empty -> {
        state.markUnpaired()
        isRunning = false
        return
      }

      is RelayCredentials.Load.Unreadable -> {
        state.markUnpaired()
        state.recordUnreadable(load.reason, load.detail)
        isRunning = false
        return
      }

      is RelayCredentials.Load.Ok -> load.stored
    }

    if (!stored.hasEndpoint) {
      state.recordUnreadable(
        "NO_ENDPOINT",
        "配对信息里没有中继地址（票据缺 relayHint），请重新配对",
      )
      isRunning = false
      return
    }

    var failures = 0
    while (scope.isActive && isRunning) {
      state.markConnecting(deviceName = stored.deviceId)

      val ended = connectOnce(stored)

      // 用户要为一个已经恢复过的连接再等半分钟。
      if (state.connection.phase == ConnectionPhase.Online) {
        failures = 0
      }

      when {
        !isRunning -> return   // 被 stop() 打断，不算失败

        ended.shouldRetry -> {
          failures++
          if (failures >= MAX_CONSECUTIVE_FAILURES) {
            state.giveUp(ended, failures)
            isRunning = false
            return
          }
          val wait = backoffMillis[minOf(failures - 1, backoffMillis.size - 1)]
          delay(wait)
        }

        else -> {
          state.markClosed(ended)
          isRunning = false
          return
        }
      }
    }
  }

  private suspend fun connectOnce(stored: RelayCredentials.Stored): RelayProtocol.DisconnectInfo {
    var result: RelayProtocol.DisconnectInfo? = null

    val listener = object : RelaySocketClient.Listener {
      override fun onReady(welcome: RelayProtocol.Welcome) {
        state.markOnline(deviceName = stored.deviceId, nowMillis = nowMillis())
        // 桌面那 2000 条环形缓冲形同虚设。
        onWelcome(welcome)
        // 不能让作品库的异常把整条连接打断。
        try {
          onReadyAction()
        } catch (_: Throwable) {
        }
      }

      override fun onEvent(json: String) {
        state.markEvent(nowMillis())
        onEventJson(json)
      }

      override fun onClosed(info: RelayProtocol.DisconnectInfo) {
        result = info
      }
    }

    val c = clientFactory(stored, listener)
    client = c

    kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
      c.runBlockingLoop()
    }

    client = null
    return result ?: RelayProtocol.DisconnectInfo(
      RelayProtocol.Disconnect.PROTOCOL,
      "客户端未上报断开原因（实现缺陷）",
    )
  }
}
