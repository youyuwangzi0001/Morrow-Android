package studio.morrow.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class RelayConnectionState(
  /** 主线程投递器：生产用 Looper，测试可注入同步实现 */
  private val post: (() -> Unit) -> Unit = defaultMainPost(),
) {

  var connection: Connection by mutableStateOf(Connection.unpaired)
    private set

  /** 最近一次断开的原因（诊断用；UI 只在 Offline 时可能显示一行小字） */
  var lastDisconnect: RelayProtocol.DisconnectInfo? by mutableStateOf(null)
    private set

  /** 本进程内最近一次收到事件的时刻（用于「上次同步 12 分钟前」） */
  var lastEventMillis: Long? by mutableStateOf(null)
    private set

  /** 是否已配对（有可用的凭据）。与 ConnectionPhase.Unpaired 同义，但更直白 */
  val isPaired: Boolean get() = connection.phase != ConnectionPhase.Unpaired

  fun markConnecting(deviceName: String? = null) = writeOnMain {
    connection = Connection(
      phase = ConnectionPhase.Connecting,
      deviceName = deviceName ?: connection.deviceName,
      sinceMillis = null,
      lastEventMillis = lastEventMillis,
      tierLabel = connection.tierLabel,
    )
  }

  /** welcome 已到，SecureChannel 就绪 */
  fun markOnline(deviceName: String?, nowMillis: Long, tierLabel: String? = null) = writeOnMain {
    connection = Connection(
      phase = ConnectionPhase.Online,
      deviceName = deviceName ?: connection.deviceName,
      sinceMillis = nowMillis,
      lastEventMillis = lastEventMillis,
      tierLabel = tierLabel ?: connection.tierLabel,
    )
    lastDisconnect = null
  }

  /** 连接结束。`info.shouldRetry` 决定调用方是否再 markConnecting */
  fun markClosed(info: RelayProtocol.DisconnectInfo) = writeOnMain {
    lastDisconnect = info
    connection = Connection(
      phase = if (connection.phase == ConnectionPhase.Unpaired) {
        ConnectionPhase.Unpaired
      } else {
        ConnectionPhase.Offline
      },
      deviceName = connection.deviceName,
      sinceMillis = null,
      lastEventMillis = lastEventMillis,
      tierLabel = null,
    )
  }

  /** 收到一条事件：只更新「最后事件时刻」，不改 phase */
  fun markEvent(nowMillis: Long) = writeOnMain {
    lastEventMillis = nowMillis
    if (connection.phase == ConnectionPhase.Online) {
      connection = connection.copy(lastEventMillis = nowMillis)
    }
  }

  /** 用户在设置里清除配对（或凭据被系统清掉） */
  fun markUnpaired() = writeOnMain {
    connection = Connection.unpaired
    lastDisconnect = null
    lastEventMillis = null
  }

  fun recordUnreadable(reason: String, detail: String) = writeOnMain {
    lastUnreadable = UnreadableInfo(reason, detail)
    lastDisconnect = null
    connection = Connection.unpaired
  }

  /** 凭据读取失败的详情（null = 没发生过） */
  var lastUnreadable: UnreadableInfo? by mutableStateOf(null)
    private set

  data class UnreadableInfo(val reason: String, val detail: String)

  fun giveUp(info: RelayProtocol.DisconnectInfo, failures: Int) = writeOnMain {
    lastDisconnect = info
    lastGiveUpFailures = failures
    connection = Connection(
      phase = ConnectionPhase.Offline,
      deviceName = connection.deviceName,
      sinceMillis = null,
      lastEventMillis = lastEventMillis,
      tierLabel = null,
    )
  }

  /** 放弃时已尝试的次数（null = 没发生过） */
  var lastGiveUpFailures: Int? by mutableStateOf(null)
    private set

  /** 手动重试时清掉「已放弃」标记 */
  fun clearGiveUp() = writeOnMain {
    lastGiveUpFailures = null
  }

  /** 当前快照（给非 Compose 的调用方，例如通知文案） */
  fun snapshot(): Connection = connection

  private inline fun writeOnMain(crossinline block: () -> Unit) {
    post { block() }
  }

  companion object {
    fun defaultMainPost(): (() -> Unit) -> Unit = { block ->
      MainThreadHolder.post(Runnable { block() })
    }
  }
}
