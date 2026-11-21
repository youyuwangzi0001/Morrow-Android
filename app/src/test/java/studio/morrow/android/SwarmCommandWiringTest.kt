package studio.morrow.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.morrow.android.ForegroundSyncState
import studio.morrow.android.SyncState
import studio.morrow.android.EventStreamViewModel

/**
 * 协作干预上行：有项目/会话/任务三件套才放开按钮，点下去真的发命令。
 *
 * 三个 id 缺一个都发不出去 —— 所以「能点」本身必须由 id 齐备决定，
 * 不能只看有没有注入回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SwarmCommandWiringTest {

  @get:Rule
  val compose = createComposeRule()

  private fun streamWithMembers(): EventStreamViewModel {
    val vm = EventStreamViewModel(CoroutineScope(StandardTestDispatcher()), applyMs = 0L) { 100L }
    vm.onRawEvents(
      listOf(
        mapOf(
          "type" to "super_updated", "seq" to 1L, "sessionId" to "s1", "runId" to "r1", "at" to 100L,
          "state" to mapOf(
            "status" to "running",
            "projectId" to "proj-1",
            "goal" to "把 Q3 复盘做完",
            "tasks" to listOf(
              mapOf(
                "index" to 0, "taskId" to "task-0", "title" to "前端重构", "agent" to "frontend",
                "agentTitle" to "前端", "status" to "running", "stepCount" to 3,
              ),
              mapOf(
                "index" to 1, "taskId" to "task-1", "title" to "后端接口", "agent" to "backend",
                "agentTitle" to "后端", "status" to "failed", "error" to "测试没过",
              ),
            ),
          ),
        ),
      ),
    )
    return vm
  }

  private fun openSwarm(commands: MutableList<SwarmCommand>) {
    val vm = streamWithMembers()
    compose.setContent {
      MorrowTheme(mode = MorrowThemeMode.Ink) {
        ForegroundSyncState.set(
          SyncState.Running(taskId = "task-0", step = 3, totalSteps = 8, startedAtMillis = 100L),
        )
        MorrowApp(stream = vm, onSwarmCommand = { commands += it })
      }
    }
    compose.waitForIdle()
    compose.onNodeWithTag("workbench.openThread").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(ComposerTags.SWARM_ENTRY).performClick()
    compose.waitForIdle()
  }

  @Test
  fun 停止成员下发stopMember() {
    val commands = mutableListOf<SwarmCommand>()
    openSwarm(commands)
    compose.onNodeWithTag(SwarmTags.action(0, MemberAction.Stop)).performClick()
    compose.waitForIdle()

    val command = commands.single() as SwarmCommand.Stop
    assertEquals("proj-1", command.projectId)
    assertEquals("s1", command.sessionId)
    assertEquals("task-0", command.taskId)
  }

  @Test
  fun 失败成员重试下发retryMember() {
    val commands = mutableListOf<SwarmCommand>()
    openSwarm(commands)
    compose.onNodeWithTag(SwarmTags.action(1, MemberAction.Retry)).performScrollTo().performClick()
    compose.waitForIdle()

    val command = commands.single() as SwarmCommand.Retry
    assertEquals("task-1", command.taskId)
  }

  @Test
  fun 发新指令先弹输入框再带上文本() {
    val commands = mutableListOf<SwarmCommand>()
    openSwarm(commands)
    compose.onNodeWithTag(SwarmTags.action(1, MemberAction.NewInstruction)).performScrollTo().performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(SwarmTags.INSTRUCTION_INPUT).performTextInput("把超时从 3s 改到 10s，补一条回归测试")
    compose.onNodeWithTag(SwarmTags.INSTRUCTION_SEND).performClick()
    compose.waitForIdle()

    val command = commands.single() as SwarmCommand.Instruction
    assertTrue(command.text.contains("10s"))
    assertEquals("task-1", command.taskId)
  }
}