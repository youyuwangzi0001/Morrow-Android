# 交付物与验证记录

这一篇是唯一可以引用交付哈希与检查结果的地方。其他地方只写结论，不复制数字。

## 交付产物

| 文件 | 字节数 | SHA-256 |
|---|---|---|
| `Morrow-Android-0.2.0.apk` | 2,405,362 | `5c85b2f08c83c48ba9c8b6b58d2e51dd014e34bbdcb0b2a14663bd9ae616306e` |

APK 的静态事实（`aapt2 dump badging` 实测）：

| 字段 | 值 |
|---|---|
| 包名 | `studio.morrow.android` |
| versionName / versionCode | `0.2.0` / 2 |
| minSdk / targetSdk / compileSdk | 26 / 35 / 36 |
| 声明的权限 | `INTERNET`、`USE_BIOMETRIC`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`、`USE_FINGERPRINT` |
| 签名 | release keystore（自签名，`CN=Morrow Release`） |

`INTERNET` 权限在 0.2.0 的包里是存在的。较早的文档曾写过「随包 APK 缺 INTERNET 权限」，那是 0.2.0 之前的构建状态，已不再成立。

## 测试与检查

| 检查 | 规模 | 结果 |
|---|---|---|
| JVM 用例 | 1017 项，79 个测试类 | 通过 |
| 静态验收 | 309 项断言 | 通过 |
| 生产代码规模 | 17 个文件 | 无子包 |
| 构建 | `:app:assembleRelease` | 产出已签名 APK |

前两项由 `bash scripts/verify.sh` 覆盖；它同时校验清单、签名、insets 与设计令牌一致性。

此前一轮新增 `ToolSyncTests`（17 项），把 `verify.sh` 的断言从 265 项扩到 276 项。

本轮（F1 卡）新增：

| 测试 | 项数 | 守什么 |
|---|---|---|
| `MarkdownParserTest` | 25 | Markdown 与 diff 的解析：标题、清单、围栏代码、表格与对齐、转义竖线、引用、分隔线、diff 语言识别、文件头不被误判为增删行、无标签 diff 识别、YAML 不被误判、行内样式与链接、转义、摘要截断 |
| `MessageCodecTest` | 16 | 消息编解码：缺 id 丢弃、角色映射、无 blocks 兜底、各块解析、工具状态四映射、错误块、未知块跳过、工具合并、三条到达路径一致 |
| `relay-phone.test.ts`（桌面仓库） | +3 | 结构化块下发、命令类工具不下发 diff、计划带步骤 |

`verify.sh` 从 276 项扩到 293 项。新增的 17 项里，15 项要求「能力真的存在」（解析器独立成纯函数、表格有专用块且横向滚动、diff 解析到行级、链接用 `LinkAnnotation.Url`、长消息可展开、工具步骤可展开 diff、insets 纳入 IME、三条路径共用 codec、`UiBlock` 模型、桌面下发 `blocks` 与 `toolDiff`、计划带步骤、两份新测试日志非空），另 2 项守「不许退回去」（助手正文不能又是裸 `Text`、输入框不能又丢选区）。

## 模拟器运行记录

本轮在 Android 14（API 34，x86_64，1080×1920）模拟器上安装了 `Morrow-Android-0.2.0.apk`，并用桌面仓库的真实中继完成了一次真实配对：

1. 中继端（`Morrow/src/main/relay-channel.ts` 的 `DirectChannel` + `Morrow/src/main/relay.ts` 的 `PairingService`）签发票据并输出六位码。
2. 手机端粘贴票据、输入六位码，界面转为「配对成功」。
3. 中继日志依次出现 `welcome`、`snapshot`，连接计数变为 1。
4. 重启应用后工位页显示在线、运行中任务与两条待审批，倒计时正常走字。

这一条记录证明的是「安装、配对、握手、快照、界面投影能跑通」，不构成真机证据。

## 截图清单

| 文件 | 页面 | 状态 |
|---|---|---|
| `screens/workbench-unpaired.png` | 工位 | 未配对空态 |
| `screens/workbench-online.png` | 工位 | 在线，含运行中任务与两条待审批 |
| `screens/sessions.png` | 会话 | 待我决定 / 运行中 / 其他会话三组 |
| `screens/session-switched.png` | 会话 | 切换后只显示目标会话自己的内容 |
| `screens/thread-rich.png` | 对话 | 表格、行级 diff、展开全文入口（README 正文引用这张） |
| `screens/thread-ime.png` | 对话 | 键盘弹出，输入框完整露在键盘上方 |
| `screens/thread-reply.png` | 对话 | 手机发消息触发的一轮：计划卡 + 回复表格 |
| `screens/swarm.png` | 成员协作 | 总目标 + 三个成员卡 |
| `screens/approvals.png` | 审批 | 两条待处理，四段信息与倒计时 |
| `screens/settings-dark.png` | 设置 | 深色主题 |
| `screens/settings-light.png` | 设置 | 浅色主题 |
| `screens/pairing-step1.png` | 配对 | 第一步，配对请求 |
| `screens/pairing-step2.png` | 配对 | 第二步，票据与六位码 |

全部截图由模拟器 `screencap` 直接导出，未经缩放或重绘。界面里的会话名、审批内容与成员任务来自中继侧的样例载荷，不是真实用户数据。

另有 `diagrams/architecture.png`、`diagrams/pairing-flow.png` 两张示意图与 `cover.png` 一张封面，均由 `scripts/build-assets.py` 生成。

## 手机发消息触发桌面的验收记录

这一节记的是用户追加的硬要求：**如果发消息不能让电脑真的开始干活，手机就只是只读监视器，不能叫远程控制中心**。它分两层验证，一层管「桌面真起了一轮」，一层管「手机真的发出了这条指令」。

### 第一层：桌面侧真的起了一轮（单元级，可重跑）

`Morrow/tests/unit/phone-send-triggers-run.test.ts`（2 项）：

| 断言 | 说明 |
|---|---|
| 回执带真 `runId` + `assistantMessageId` | 不是 `ok: true` 了事 |
| `run_started` 的 `runId` 与回执一致 | 证明这一轮就是回执里那一个 |
| 用户消息原文到得了手机 | `run_started.userMessage.text` 逐字相等 |
| 中间有真实 `message_delta` | 不是空跑 |
| 跑到 `run_finished` 且 `reason === 'complete'` | 这一轮真的结束了 |
| 收尾 `message_completed` 带非空 `blocks[]` | 结构化内容闭环 |
| 会话不存在时回失败，且 500ms 内无 `run_started` | 反证「不假成功」 |

这一层**不桩 `AgentService`**：用真实 `send()`，只把 Provider 换成仓库自带的 `demo`（不需要联网与 API Key）。执行 `npx vitest run tests/unit/phone-send-triggers-run.test.ts` 可重跑。

过程中撞到一条真实产品约束并如实记录：演示 Provider 被 `agent.ts:3359` 硬性限制在 Morrow 的演示工作区，所以真实使用中**手机发消息要求桌面已经连好真实模型** —— 这不是手机端能绕开的。

### 第二层：手机端真的发得出去（真机，模拟器截图）

在 Android 14（API 34，x86_64，1080×1920）模拟器上安装 `Morrow-Android-0.2.0.apk`，用 `scripts/capture-relay.mjs` 起真实 LAN 中继、走完 ECDH 配对，然后在对话页输入并点发送。中继日志原文：

```
FRAME in invoke message.send id=phone-2
INVOKE message.send sessionId=ses-capture-1 text="Please write the anomaly section into report"
PUSHED seq=4  type=run_started
PUSHED seq=5  type=message_started
PUSHED seq=6..9 type=message_delta
PUSHED seq=10 type=message_completed
PUSHED seq=11 type=run_finished
```

界面结果见 `screens/thread-reply.png`：用户气泡 → 计划卡（4 步 + 风险）→ 助手回复里的表格。

### 这一层修掉的三个真实缺陷

| 缺陷 | 现场症状 | 修法 |
|---|---|---|
| 上行在主线程写 socket | 点发送必失败，界面只报「未连接桌面」 | `sendInvoke` 与 `sendInvokeAwaiting` 改走 IO 线程；诊断信息把 `NetworkOnMainThreadException` 抓了出来 |
| 失败原因不可见 | 无法区分「没连」「握手中」「写失败」 | `RelaySocketClient.lastSendFailure` 记录具体原因，界面显示它 |
| 空闲 30 秒被当断线 | 中继日志每 ~30 秒一串 `welcome replay=0`，点发送约一半概率落空 | 读超时改为心跳周期；连续 3 轮无任何帧才判死 |

第三条的根因值得单独写一句：`relay-channel.ts` 从不发 WebSocket ping，所以**安静是正常状态**；把 `soTimeout` 抛出的超时当网络错误处理，等于每 30 秒主动杀掉一条健康连接。现在超时后发 ping 继续读，`RelaySocketEndToEndTest` 有两条用例分别守「不误杀」与「对端装死仍能判死」。

## 会话列表与按会话隔离的验收记录

这一节记的是「手机能不能找到并切换要控制的那个任务」。它是远程控制的前提：找不到任务，其余能力都用不上；切换之后串内容，用户会对着 A 的界面操作 B。

### 缺口是什么（修复前的代码事实）

`Route` 枚举只有 `Workbench / Thread / Swarm / Approvals / Works / Pairing / Settings` 七项，没有会话列表；`ThreadScreen` 顶栏只有一个返回按钮；`selectSession` 在生产代码里只被通知深链调用过一次。手机端永远显示 `activeSessionId` 指向的那一个会话。

同时 `MorrowUiState` 顶层有 `plan` / `todos` / `goal` 三个单值，而对应事件 `plan_updated` / `todos_updated` / `goal_updated` 都自带 `sessionId`。归并器把 `sessionId` 丢掉了，直接写顶层——于是最后一个下发计划的会话抢走它。

### 修复后

| 项 | 内容 |
|---|---|
| 新页面 | `Sessions.kt`：`SessionListProjection`（纯函数投影）+ `SessionListScreen` |
| 分组 | 待我决定 / 运行中 / 其他会话，顺序即优先级 |
| 状态优先级 | 待审 > 运行 > 空闲（同一轮既在跑又有审批时，用户该看到「有事等你」） |
| 入口 | 对话页顶栏「会话 ›」，仅在 `streamState != null` 时渲染 |
| 选中 | `stream.selectSession(id)` + 回到对话页 |
| 隔离 | `plan` / `todos` / `goal` 移入 `SessionUi`，归并走 `patchSession(state, action.sessionId)`；读取走 `activePlan` / `activeTodos` / `activeGoal` |

### 断言

| 测试 | 项数 | 覆盖 |
|---|---|---|
| `SessionListTests` | 20 | 状态判定与优先级、摘要提取（含整表过滤与纯表格兜底）、空标题兜底、分组与组内排序（时间未知排最后）、顶栏读数、相对时间六档、事件流一路投影 |
| `SessionListScreenTest` | 9 | 三组渲染、审批数标签、当前标记、点击回调、返回回调、两种空态、预览上屏、时间未知不编造「刚刚」 |
| `ToolSyncTests.计划待办目标按会话隔离` | 1 | A 会话下发三者、B 会话只下发待办；切到 B 后 `activePlan` 与 `activeGoal` **必须为 null** |

`verify.sh` 新增 H1 卡 10 条断言，守「有投影层、接进路由、选中真切会话、顶栏有入口、待审分组优先、三者按会话隔离、投影只读当前会话、两份新测试日志非空」。

### 模拟器证据

`docs/screens/sessions.png`：三条会话分别落在三个分组；待审那张写「2 个待批准」，运行中那张写「运行中」，当前会话带「当前」标记。

`docs/screens/session-switched.png`：点第二张卡后的对话页，标题、副标题与正文都换成了目标会话的内容，**没有任何属于别的会话的计划卡**。修复前同一操作会出现 `Q3 销售复盘` 计划卡（属于另一个会话）。

## 验证边界

以下事项**没有**验证过，不能当成已验证：

- 真机运行。所有界面证据来自模拟器。
- 通知投递、通知动作按钮、免打扰合并的实际表现。
- 生物识别弹窗。模拟器没有录入指纹，截图里的「只能在桌面批准」正是这个状态的写真。
- 后台保活与前台服务在系统省电策略下的存活时间。
- 公网中继。它仍是占位实现，跨网络连接不可用。
- 与除本仓库记录之外的其他桌面版本配对。

## 复现

```bash
# 1. 校验分发文件
Get-FileHash deliver\Morrow-Android-0.2.0.apk -Algorithm SHA256

# 2. 构建并验收
bash scripts/verify.sh

# 3. 装到设备
adb install -r deliver\Morrow-Android-0.2.0.apk
```

构建前请确认工程路径与 Android SDK 路径都是纯 ASCII，否则 `aapt2` 与模拟器会读不到资源。








