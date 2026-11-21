# Changelog · 版本记录

Morrow 安卓端的版本按发布线记录。每节先给完整中文，再给完整英文；两段内容对应，但不逐句对照。

桌面端的版本记录在 [Morrow 仓库](https://github.com/youyuwangzi0001/Morrow/blob/master/CHANGELOG.md)，两端在此之后各自独立发版。

## 注释大幅精简与文档呈现（2026-11-25）

版本号仍为 0.2.0（原地覆盖本次发布）。这一轮不改动任何运行时行为，只动注释与文档。

### 中文

**变更概览**：注释占比 40% → 7.1% · 文档补上结构化消息能力 · 内部方案移出公开仓库

- **注释从 5,689 行压到 1,004 行**（占代码从 40% 降到 7.1%）。删的是多行块注释与超过 22 字的解释性长注，保留的是字段语义短注 —— `null = 桌面没给` 这类一句话说明信息密度最高，删掉反而会让人误读协议。
- **原文件留有备份**：`app/src/main/java/` 下的精简版本是权威版本；改动前的完整注释在构建机的本地备份里，未入库。逐条依据见下方「为什么敢删」。
- **为什么敢删**：这一层的注释此前积累了大量「当时怎么想的」叙述，其中相当一部分描述的是**已经改掉的行为**（例如多处写着「此前这里是 emptyList()，注释说尚未由 relay 提供」）。留着过时叙述比没有注释更糟 —— 读者会把历史当成现状。
- **删注释弄坏了一条安全断言，已修回**：`verify.sh` 的「重放防护且失败不污染窗口（D1e）」依赖 `SecureChannel.kt` 里的注释文本 `只有解密成功才推进计数`。删掉后该项失败。代码本身完好，但那条注释描述的是一条**防拒绝服务的不变量**（先污染窗口再拒绝真消息），所以恢复了完整说明，而不是去改断言迁就。随后排查了全部 21 条 grep 断言，确认其余目标的代码分支都在。
- **文档**：README 补上**结构化消息**能力 —— 桌面下发的表格、代码块、行级 diff 与工具卡在手机上按原样渲染，长消息默认折到 4 段。此前 README 完全没有提到这个能力，而它正是手机上最有辨识度的一块。配了 `docs/screens/thread-rich.png` 真实截图（表格 + diff + 折叠同屏），内嵌截图从 2 张增到 3 张，仍在方法论建议的 0–5 张内。
- **`docs/REBUILD-PLAN.md` 移出仓库**：这份 70 KB 的执行方案记录了本机基线与逐轮过程，是写给自己看的工程记录，不面向读者。加入 `.gitignore`，避免随源码一起公开。

### English

**Summary**: comments cut from 40% to 7.1% · docs gained the structured-message capability · internal plan kept out of the public repo

- **Comments went from 5,689 lines to 1,004** (40% of code down to 7.1%). What was removed: multi-line block comments and explanatory one-liners longer than roughly 22 characters. What stayed: the short field-level notes — a single line like `null = 桌面没给` carries the most information per byte, and removing it invites misreading the protocol.
- **The originals were backed up**: the trimmed sources under `app/src/main/java/` are authoritative; the full pre-trim comments live in a local backup on the build machine and are not committed. The reasoning is below.
- **Why the removal was safe**: a good part of that material was "what we were thinking at the time", and several notes described **behaviour that no longer exists** (for example places still saying "previously this was `emptyList()`, the comment claimed relay had not provided it yet"). Stale narration is worse than no comment — a reader takes history for current fact.
- **One deletion broke a security assertion and was reverted**: `verify.sh`'s "replay protection, failed frames do not poison the window (D1e)" asserts on the comment text `只有解密成功才推进计数` inside `SecureChannel.kt`. Removing it failed the check. The code was intact, but that comment documents a **denial-of-service invariant** (poison the window, then reject the real message), so the full explanation was restored rather than the assertion being relaxed. All 21 grep assertions were then audited and the remaining targets still match real code branches.
- **Docs**: the README gained the **structured messages** capability — tables, code blocks, line-level diffs and tool cards sent by the desktop render as-is on the phone, with long messages folded to four blocks. The README had not mentioned this at all, yet it is the most distinctive thing the phone does. It ships with a real screenshot, `docs/screens/thread-rich.png` (a table, a diff and folding in one view), taking embedded screenshots from two to three — still inside the 0–5 the guidelines recommend.
- **`docs/REBUILD-PLAN.md` was kept out of the repository**: the 70 KB execution plan records this machine's baselines and the round-by-round process. It is engineering notes written for the author, not for readers. It is now in `.gitignore` so it does not travel with the source.

## 手机能选任务了，且不再串内容（2026-11-23）

版本号仍为 0.2.0（原地覆盖本次发布）。前两轮把「看」和「控制」补齐了，这一轮补的是**前提**：找不到要控制的任务，其余能力都用不上。

### 中文

**变更概览**：新增会话列表页 · 计划/待办/目标改为按会话隔离 · 30 项新测试 + 10 项验收断言

- **手机此前无法选择任务**。`Route` 只有工位、对话、协作、审批、作品、配对、设置七页，**没有会话列表，也没有切换会话的入口**；`selectSession` 在生产代码里只被通知深链调用过一次。手机永远只能看桌面 `activeSessionId` 指向的那一个会话。而 Qoder 手机版首屏就是任务列表（运行中 / 已就绪 / 已归档分栏，每张卡带机器名），Trae 是任务中心（可按全部 / 云端 / 设备筛选）——**远程控制的前提是「找得到那个任务」**。
- **会话列表（新增 `Sessions.kt`）**：按「待我决定 / 运行中 / 其他会话」三段分组，顺序即优先级——需要用户介入的永远排最前。一张卡给出状态点（金=运行、琥珀=待审、青=空闲）、标题、相对时间与两行摘要；待审卡片把具体条数写进标签（「2 个待批准」），比笼统的「待你决定」有用。选中即 `selectSession(id)` 并回到对话页。入口在对话页顶栏的「会话 ›」，只在有事件流时出现。
- **摘要会压掉 Markdown 噪声**。代码围栏、整张表格、`@@` 行都不进列表——实测只过滤表格分隔行时，摘要里会留下「| 月份 | 金额 | | 7 月 | 128.4 |」，在列表里读不出任何意思。整条消息只有表格时退回用表格内容拼一句，不留空白。
- **计划 / 待办 / 目标此前是全局单值**（真机抓到的缺陷）。这三个事件都自带 `sessionId`，却被写成 `state.copy(plan = action.plan)` 存进 `MorrowUiState` 的顶层字段——于是**最后一个下发计划的会话抢走了它**：切到 B 会话，屏幕上显示的是 A 会话的「Q3 销售复盘」计划卡。单会话时完全看不出来，这正是它长期没被发现的原因。现在三者都挂在 `SessionUi` 上，归并走 `patchSession(state, action.sessionId)`，读取侧新增 `activePlan` / `activeTodos` / `activeGoal` 只取当前会话那一份。
- **测试**：新增 `SessionListTests` 20 项（状态判定、摘要提取、分组排序、顶栏读数、相对时间、事件流串联）与 `SessionListScreenTest` 9 项（三组渲染、审批数、当前标记、点击回调、两种空态、时间未知不编造），并在 `ToolSyncTests` 新增「计划待办目标按会话隔离」1 项——该断言写在修复前会直接失败。全量 79 个测试类 1017 项通过；`verify.sh` 从 299 项扩到 309 项，新增的 H1 卡 10 条断言守「有投影层、接进路由、选中真切会话、顶栏有入口、待审分组优先、三者按会话隔离、投影只读当前会话、两份新测试日志非空」。
- **交付**：`deliver/Morrow-Android-0.2.0.apk` 2,405,362 字节，SHA-256 `5c85b2f08c83c48ba9c8b6b58d2e51dd014e34bbdcb0b2a14663bd9ae616306e`。

### English

**Summary**: new session list · plan/todos/goal isolated per session · 30 new tests + 10 acceptance assertions

- **The phone could not choose a task**. `Route` had only workbench, thread, swarm, approvals, works, pairing and settings — **no session list and no way to switch sessions**; `selectSession` was called from a single notification deep link. The phone could only ever show whichever session the desktop pointed at. Qoder's mobile app opens on a task list (running / idle / archived, each card naming its machine) and Trae has a task centre — **remote control starts with being able to find the task**.
- **Session list (new `Sessions.kt`)**: three sections — needs you, running, others — where order is priority, because nothing else matters while a request is waiting. Each card carries a status dot (gold running, amber awaiting, teal idle), title, relative time and a two-line preview; awaiting cards put the exact count in the label ("2 awaiting approval"). Selecting a card calls `selectSession(id)` and returns to the thread. The entry sits in the thread header as "会话 ›" and only appears when there is an event stream.
- **Previews strip Markdown noise**. Fences, whole tables and `@@` lines never reach the list — filtering only the separator row leaves "| 月份 | 金额 | | 7 月 | 128.4 |" in the preview, which reads as nothing at all. When a message is nothing but a table, the preview falls back to its contents instead of going blank.
- **Plan / todos / goal used to be global single values** (found on a real device). All three events carry a `sessionId`, yet were stored as `state.copy(plan = action.plan)` on `MorrowUiState` — so **the last session to publish a plan won**: switching to session B showed session A's "Q3 销售复盘" card. With a single session this is invisible, which is exactly why it survived so long. All three now live on `SessionUi`, written through `patchSession(state, action.sessionId)`, and the new `activePlan` / `activeTodos` / `activeGoal` accessors read only the current session's copy.
- **Tests**: `SessionListTests` (20) and `SessionListScreenTest` (9) cover status precedence, preview extraction, grouping and ordering, header readout, relative time, event-stream end-to-end, three-section rendering, approval counts, the active marker, tap callbacks, both empty states and "unknown time must not be invented", plus one new `ToolSyncTests` case — "plan/todos/goal are isolated per session" — which fails outright before the fix. The full suite is 1017 tests across 79 classes; `verify.sh` grows from 299 to 309 assertions with ten new ones guarding the projection, routing, real session switching, the header entry, awaiting-first grouping, per-session isolation, current-session reads and the two new evidence logs.
- **Delivery**: `deliver/Morrow-Android-0.2.0.apk`, 2,405,362 bytes, SHA-256 `5c85b2f08c83c48ba9c8b6b58d2e51dd014e34bbdcb0b2a14663bd9ae616306e`.
## 手机能真正让电脑干活（2026-11-23）

版本号仍为 0.2.0（原地覆盖本次发布）。上一轮把「看」补齐了；这一轮补的是「控制」——手机发出的消息必须真的让桌面起一轮任务，否则它只是只读监视器。同时修掉了三个让这条路走不通的缺陷。

### 中文

**变更概览**：上行真的触发一轮 · 三个连接缺陷 · 配对票据回归 · 2 项桌面端到端 + 5 项票据解析测试 + 6 项验收断言

- **点发送必失败：主线程写 socket**。`sendInvoke` / `sendInvokeAwaiting` 此前在 UI 线程同步写 socket，Android 的 StrictMode 直接抛 `NetworkOnMainThreadException`——**所有上行指令一条都发不出去**，而界面只会说「未连接桌面，指令没有发出去」，看起来像网络问题。现在写帧统一切到 IO 线程（`sendFrameOffMainThread`），已在非主线程时不再套一层 `runBlocking`。
- **失败原因不可见**。同一个「未连接桌面」把三种情况混成一句：真的没连、握手中、连接在但写失败。新增 `RelaySocketClient.lastSendFailure`，界面显示具体原因（例如「写入失败：NetworkOnMainThreadException」）——上面那条缺陷正是靠它才被指认出来的。
- **空闲 30 秒被当成断线**。`soTimeout` 抛出的 `SocketTimeoutException` 直接冒到 `catch (Throwable)` → `classifyFailure` → RETRYABLE，于是**每 30 秒主动杀掉一条健康连接再重连**。根因是桌面端从不发 WebSocket ping，安静本就是正常状态。现在读超时只是心跳时机：超时后发 ping 继续读，连续 3 轮（约 90 秒）无任何帧才判死。中继日志里「每 30 秒一串 `welcome replay=0`」随之消失。
- **配对票据：过期被报成格式错**。票据只有 90 秒有效期，而字段与格式校验排在过期检查之前，一张刚过期的票会被报成「这个二维码不是 Morrow 的配对码 / 二维码内容不是 JSON 对象」——用户按提示去检查自己复制的内容，而真正的原因是超时。现在 `parseTicket` 先读 `exp` 判过期，再校验其余字段。
- **截图脚本会提前核销票据**。`scripts/capture-relay.mjs` 为了拿 `desktopSessionKey`，在签发票据的同一秒就调了 `completePairing()`，把票标记成 used；等手机 20 秒后发 `pair-confirm` 时桌面按防重放规则拒绝。产品行为正确，是脚本错——改为独立算 ECDH，不动票据状态。
- **测试**：桌面新增 `phone-send-triggers-run.test.ts`（2 项，用真实 `AgentService.send()`，不桩），断言「回执带真 runId → run_started 同 runId → 有 message_delta → run_finished complete → message_completed 带 blocks」，并反证「会话不存在时不凭空起一轮」；手机新增 `TicketParseRegressionTest`（5 项，用截图脚本真实签发的票据逐字段回归，并守「过期优先于格式错」）。全量 77 个测试类 987 项通过；`verify.sh` 从 293 项扩到 299 项，新增的 6 项守上行链路（端到端测试存在、桌面 `message.send` 真调 `agent.send`、写帧不在主线程、失败带诊断、空闲超时按心跳处理）。
- **交付**：`deliver/Morrow-Android-0.2.0.apk` 2,388,978 字节，SHA-256 `67030e1212152aef5b3ed122c8fac647ba973eda3f1867d553a62f59c6af8f5e`，同一张 release 证书签名。打包前删除了 808.1 MB 可重建内容（Robolectric 每次跑测试解压的 conscrypt DLL、上百个临时证书目录、Gradle 构建产物与旧日志）。

### English

**Summary**: uplink actually starts a run · three connection defects fixed · ticket parsing regression · 2 desktop end-to-end + 5 ticket tests + 6 acceptance assertions

- **Every send failed: socket writes on the UI thread**. `sendInvoke` / `sendInvokeAwaiting` wrote to the socket synchronously on the main thread; Android's StrictMode answered with `NetworkOnMainThreadException`, so **no uplink command could ever be sent** — while the UI only said "not connected to the desktop", which reads like a network problem. Frame writes now go through `sendFrameOffMainThread`, and skip the extra `runBlocking` when already off the main thread.
- **Failures had no diagnosable cause**. One message — "not connected" — covered three different situations: truly disconnected, still handshaking, connected but the write failed. `RelaySocketClient.lastSendFailure` now records the specific reason and the UI shows it ("write failed: NetworkOnMainThreadException"); that record is exactly how the defect above was identified.
- **Thirty seconds of silence was treated as a disconnect**. `SocketTimeoutException` from `soTimeout` bubbled into `catch (Throwable)` → `classifyFailure` → RETRYABLE, so **a healthy connection was killed and rebuilt every 30 seconds**. The root cause: the desktop never sends WebSocket pings, so silence is the normal state. Read timeouts are now heartbeat ticks — send a ping and keep reading, and only declare the link dead after three consecutive rounds (about 90 seconds) without a single frame. The "one `welcome replay=0` every 30 seconds" pattern in the relay log disappeared.
- **Pairing tickets: expiry reported as a format error**. Tickets live 90 seconds, but field and format validation ran before the expiry check, so a just-expired ticket was reported as "this QR code is not a Morrow pairing code / its content is not a JSON object" — sending the user to inspect what they pasted when the real cause was the timeout. `parseTicket` now reads `exp` and checks expiry first.
- **The screenshot script consumed the ticket early**. To obtain `desktopSessionKey`, `scripts/capture-relay.mjs` called `completePairing()` in the same second it issued the ticket, marking it used; when the phone sent `pair-confirm` twenty seconds later the desktop correctly rejected the replay. The product behaved correctly — the script was wrong. It now derives the key independently and leaves ticket state alone.
- **Tests**: the desktop gains `phone-send-triggers-run.test.ts` (2 cases, real `AgentService.send()`, no stubs) asserting "receipt carries a real runId → run_started with the same runId → message_delta present → run_finished complete → message_completed carries blocks", plus the counter-check that a missing session starts nothing; the phone gains `TicketParseRegressionTest` (5 cases) replaying a ticket actually issued by the capture script and guarding "expiry outranks format errors". The full suite is 987 tests across 77 classes; `verify.sh` grows from 293 to 299 assertions, six of them covering the uplink (the end-to-end test exists, the desktop `message.send` really calls `agent.send`, writes are off the main thread, failures carry a reason, and idle timeouts are heartbeats).
- **Delivery**: `deliver/Morrow-Android-0.2.0.apk`, 2,388,978 bytes, SHA-256 `67030e1212152aef5b3ed122c8fac647ba973eda3f1867d553a62f59c6af8f5e`, signed with the same release certificate. 808.1 MB of rebuildable material was deleted before packaging (the conscrypt DLLs Robolectric extracts on every run, hundreds of temporary certificate directories, Gradle build output and old logs).
## 手机真正看得懂桌面在做什么（2026-11-23）

版本号仍为 0.2.0（原地覆盖本次发布）。这一轮修的是两条被用户直接指出的缺陷：手机上 diff、链接、表格、计划一概看不见、历史消息展不开；键盘弹出后输入框收不到键盘上方、光标挪不动。根因都不在渲染层，而在更上游。

### 中文

**变更概览**：结构化块协议 · Markdown/表格/行级 diff 渲染 · 计划步骤 · IME 与光标修复 · 41 项新测试 + 17 项验收断言

- **根因一：桌面只下发纯文本**。`phoneMessage()` 此前把消息拍成 `{id, role, text, createdAt}`，`text` 来自 `messagePlainText()` —— 那个函数只保留 `text` / `error` 块并 join 成字符串，表格、代码块、diff 在这一步就没了。手机无论怎么写渲染器都拿不到数据。现在新增 `blocks: PhoneBlock[]`，文字 / 思考 / 工具 / 错误各自成块下发；旧的 `text` 字段保留，已装机的旧版手机不会白屏。
- **根因二：diff 只有计数**。文件写入类工具现在下发 `meta.diff` 的行级原文（16 KB 上限，超限整块丢弃而不是截断——截断的补丁会显示一个「改到一半」的假象）。命令类工具的 `output` 仍然一律不出桌面：那是可能含密钥的原文，也是手机屏幕放不下的日志。
- **根因三：没有渲染层**。新增 `RichText.kt`：`MarkdownParser` 是纯函数（不依赖 Compose，25 项用例在普通 JVM 里 0.06 秒跑完），渲染层覆盖标题、段落、有序/无序/任务清单、围栏代码块（等宽 + 横向滚动 + 24 行折叠）、引用、**表格**（表头底色、按对齐方式对齐、横向滚动、不压列宽）、**行级 unified diff**（红绿低透明度、`@@` 高亮、20 行折叠）、分隔线；行内支持粗体/斜体/删除线/行内代码/**可点击链接**。
- **历史消息可展开**：长消息按**块数**折叠（超过 4 块），末尾给「展开全文 · 还有 N 段」。按块判而不是按字数判：一条 3000 字的表格和一条 3000 字的段落，前者必须折叠。工具执行组展开后，改过文件的那一步下面还有一层「查看 diff」——一次执行组可能有十几个文件改动，全铺开会把「哪几步失败」淹掉。思考过程默认收起成一行「思考过程 · N 字」。
- **计划带步骤**：`plan_updated` 此前只下发标题，手机上的计划卡永远只有「计划 / 已批准 / 3 步」。现在带 `steps` / `summary` / `risks` / `status`，卡片画出完整步骤清单与风险，手机在远程场景里第一次能判断「这个计划靠不靠谱」。
- **键盘遮挡**：全仓此前没有任何 `imePadding()` / IME insets，而 `enableEdgeToEdge()` 之后 `adjustResize` 对 API 30+ 的边到边窗口已失效。`MorrowInsets.contentPadding()` 现在纳入 `WindowInsets.ime`，**与导航栏取较大者而不是相加**——相加会在键盘上方多出一条导航栏高的空隙，看起来像输入框浮在半空。
- **光标挪不动**：输入框此前用 `TextFieldValue(state.draft)` 重建，`onValueChange` 只把 `text` 抛上去，选区丢失；`TextFieldValue(text)` 的默认选区是偏移 0，于是每次重组光标跳回开头，用户点中间改字立刻被吞。现在 `Composer` 自己持有完整 `TextFieldValue`，外部文本变化（发送后清空、`@` 面板插入路径）时才以外部为准重建。同时去掉写死的 `heightIn(max = 40 + 20 × 4dp)`——那假设每行 20dp，与 `MorrowType.body × lhCopy` 不一致，字体放大或长行折行时会被裁切。
- **三条路径共用一份解析**：同一份消息 JSON 会从快照、`messages_replaced`、`message_completed` 三处到达。新增 `MessageCodec.kt` 的 `UiMessageCodec`，三处共用，否则新增字段必然漏一处，表现为「首连能看到 diff、重连之后就没了」。`mergeTools()` 合并 blocks 里的完成态（有 diff）与增量事件的实时态（状态更准）。
- **测试**：新增 `MarkdownParserTest` 25 项、`MessageCodecTest` 16 项、`relay-phone.test.ts` 3 项；全量 74 个测试类 980 项通过。`verify.sh` 从 276 项扩到 293 项，新增的 17 项里 15 项守「能力真的存在」（解析器、表格、diff、链接、折叠、IME、codec、blocks、计划步骤），另 2 项守「不许退回去」（助手正文不能又是裸 `Text`、输入框不能又丢选区）。

### English

**Summary**: structured block protocol · Markdown/table/line-level diff rendering · plan steps · IME and caret fixes · 41 new tests + 17 acceptance assertions

- **Root cause 1: the desktop only sent plain text**. `phoneMessage()` flattened every message to `{id, role, text, createdAt}`, where `text` came from `messagePlainText()` — a function that keeps only `text`/`error` blocks and joins them into a string. Tables, code blocks and diffs were gone at that step, so no amount of renderer work on the phone could recover them. It now sends `blocks: PhoneBlock[]` with separate text / thinking / tool / error blocks; the old `text` field is kept so already-installed clients do not go blank.
- **Root cause 2: diffs were only counts**. File-writing tools now send the line-level `meta.diff` (16 KB cap; an over-long patch is dropped whole rather than truncated, because a truncated patch shows a "changed halfway" illusion). Command output still never leaves the desktop: it can contain secrets and is far too long for a phone.
- **Root cause 3: there was no rendering layer**. New `RichText.kt`: `MarkdownParser` is a pure function (no Compose dependency — its 25 cases run in 0.06 s on a plain JVM), and the renderer covers headings, paragraphs, ordered/unordered/task lists, fenced code (monospace, horizontal scroll, 24-line collapse), quotes, **tables** (header tint, per-column alignment, horizontal scroll, no column squeezing), **line-level unified diffs** (muted red/green, `@@` highlighted, 20-line collapse) and rules; inline bold/italic/strikethrough/code/**clickable links**.
- **History is expandable**: long messages collapse by **block count** (over four), ending with "expand · N more". Block count rather than character count is the right signal: a 3000-character table and a 3000-character paragraph need different treatment, and only the former must collapse. Inside an expanded tool group, each step that touched a file carries a further "view diff" — a single group can hold a dozen file edits, and laying them all out buries "which steps failed". Thinking collapses to one line, "thinking · N chars".
- **Plans carry steps**: `plan_updated` used to send only a title, so the phone's plan card could never say more than "plan / approved / 3 steps". It now carries `steps` / `summary` / `risks` / `status`, and the card draws the full step list and risks — the first time a remote user can judge whether a plan is sound.
- **Keyboard covering the composer**: the codebase had no `imePadding()` or IME insets at all, and after `enableEdgeToEdge()` the `adjustResize` soft-input mode no longer applies to edge-to-edge windows on API 30+. `MorrowInsets.contentPadding()` now includes `WindowInsets.ime` and takes the **larger** of it and the navigation bar rather than their sum — summing leaves a navigation-bar-high gap above the keyboard that reads as a floating composer.
- **Caret could not move**: the composer rebuilt `TextFieldValue(state.draft)` and passed only `text` back up, discarding the selection; `TextFieldValue(text)` defaults to offset 0, so every recomposition snapped the caret to the start and edits made mid-string were swallowed. `Composer` now owns the full `TextFieldValue` and only rebuilds from outside text changes (clearing after send, inserting an `@` path). The hard-coded `heightIn(max = 40 + 20 × 4dp)` is gone too: it assumed 20dp per line, inconsistent with `MorrowType.body × lhCopy`, and clipped under font scaling or long wrapped lines.
- **One codec, three arrival paths**: the same message JSON arrives via the snapshot, `messages_replaced` and `message_completed`. The new `UiMessageCodec` in `MessageCodec.kt` is shared by all three; without that, adding a field always misses one path and shows up as "diff is visible on first connect and gone after a reconnect". `mergeTools()` merges the completed state from blocks (which carries the diff) with the live state from incremental events (which is more current).
- **Tests**: `MarkdownParserTest` (25), `MessageCodecTest` (16) and three new `relay-phone.test.ts` cases; the full 74-class suite passes with 980 tests. `verify.sh` grows from 276 to 293 assertions: 15 of the 17 new ones require the capability to exist (parser, tables, diff, links, collapsing, IME, codec, blocks, plan steps) and two forbid regressions (assistant text must not go back to a bare `Text`, the composer must not drop the selection again).

## 手机看得见桌面在做什么（2026-11-23）

版本号仍为 0.2.0（原地覆盖本次发布）。这一轮把手机从「只能读文字的聊天窗」补成真正的远程观察端：命令执行、文件增删改、待办、提示都能在手机上看清，发送消息也改为以桌面回执为准。

### 中文

**变更概览**：7 类事件真正接线 · 工具调用可见 · 发送等回执 · 17 项新测试 + 11 项验收断言

- **工具执行可见**：`tool_started` / `tool_finished` 从「明确忽略」改为真实状态。手机上能看到每一步的人话标题（「修改 src/a.ts」「删除 out/b.txt」「运行命令：npm test」）、成功/失败/被拒绝、新增与删除行数、是否新建或删除、命令退出码与超时。断线期间只收到结束事件的调用也会补出一条步骤，不再凭空消失。
- **只下发摘要，不下发原文**：桌面侧新增 `phoneToolSummary` / `phoneToolResult`，只挑工具名、标题、类别与计数。命令原文、文件内容、命令输出**始终不出桌面**——这既是最敏感的内容，也是手机屏幕放不下的日志。原有「不下发工具输入输出」的安全测试全部保留并通过。
- **待办与提示上屏**：`todos_updated` 在工位页显示为「桌面待办」（只列未完成与进行中，已完成折叠成计数）；`notice` / `error` 变成可追溯的提示条，最多保留最近 5 条、最新的在前。
- **发送以回执为准**：`message.send` 原先只看 socket 写入是否成功，于是会出现「界面显示已发送、电脑毫无反应」。现在按 request id 等待桌面 `result` 帧，超时按失败处理；失败时保留草稿并给出原因，不再把内容吃掉。
- **计划与目标**：`plan_updated` / `goal_updated` 进入状态，计划卡挂在会话最后一条助手消息上，避免每条消息重复出卡。
- **测试**：新增 `ToolSyncTests` 17 项（解析、归并、幂等、断线补步骤、投影摘要、不泄露原文），修正 `EventReducerTest` 中按旧语义写的 Ignore 清单（13+16 → 20+8+1，仍覆盖全部 29 种）。`verify.sh` 新增 11 项静态断言，其中三项专门守「投影不能只映射文字」「发送不能假装成功」「桌面不能下发原文」。

### English

**Summary**: seven event types wired up · tool activity visible · send waits for desktop ack · 17 new tests + 11 acceptance assertions

- **Tool activity is visible**: `tool_started` / `tool_finished` are no longer explicitly ignored. Each step shows a human-readable title ("modify src/a.ts", "delete out/b.txt", "run: npm test"), its outcome (ok / failed / denied), added and removed line counts, whether a file was created or deleted, the command exit code and timeouts. Calls whose start event was missed during a disconnect are back-filled from the finish event instead of vanishing.
- **Summaries only, never raw payloads**: the desktop now exposes `phoneToolSummary` / `phoneToolResult`, which pick only the tool name, title, category and counts. Command text, file contents and command output never leave the desktop — they are both the most sensitive material and the logs a phone screen cannot hold. All existing safety tests still pass.
- **Todos and notices on screen**: `todos_updated` renders as a "desktop todos" block (open and in-progress items, completed folded into a count); `notice` / `error` become traceable banners keeping the latest five, newest first.
- **Send waits for an ack**: `message.send` used to treat a successful socket write as success, which produced "the phone says sent, the computer does nothing". It now waits for the desktop `result` frame keyed by request id and treats a timeout as failure, keeping the draft and explaining why.
- **Plans and goals**: `plan_updated` / `goal_updated` now reach the state; the plan card attaches to the last assistant message so it does not repeat on every message.
- **Tests**: `ToolSyncTests` adds 17 cases (parsing, reduction, idempotence, disconnect back-fill, projection summaries, no raw payload leakage); `EventReducerTest`'s ignore list is corrected from the old 13+16 split to 20+8+1, still covering all 29 types. `verify.sh` gains 11 static assertions, three of which specifically guard against a text-only projection, a send that pretends to succeed, and the desktop leaking raw payloads.

## 配对确认落盘（2026-11-23）

版本号仍为 0.2.0（原地覆盖本次发布）。这一轮把手机配对补成一次真正落盘的双向确认，并重出签名包。

### 中文

**变更概览**：补一帧握手确认 · 6 项安全边界测试 · 重出签名 APK

- **配对确认真正落盘**：此前手机算出密钥后只写自己的盘就显示「配对成功」，桌面设备表始终是空的，面板上仍是「还没有配对的手机」。现在手机在算完密钥后补发一帧 `pairing.confirm`，桌面校验通过后才写入设备表并通知设置页刷新，两端状态至此一致。
- **一次性票据**：同一张票据只能确认一次，重放会被拒绝并断开；不存在的 nonce 一律按同样方式拒绝，不泄露票据是否存在。
- **安全边界测试**：新增 6 项测试覆盖「设备表真的写入」「配完就能连」「重放被拒」「不泄露存在性」「变更通知」「参数校验」，配对这条链路的每个拒绝分支都有对应用例。
- **重出签名包**：`deliver/Morrow-Android-0.2.0.apk` 用同一张 release 证书重新签名，哈希见 `deliver/SHA256SUMS.txt`。

### English

**Summary**: handshake confirm frame · 6 security-boundary tests · APK rebuilt and re-signed

- **Pairing confirm is persisted**: the phone used to write its own key and immediately show "paired", leaving the desktop device table empty and the panel still saying no phone was paired. The phone now sends a `pairing.confirm` frame after deriving keys; the desktop verifies it, writes the device entry and notifies the settings page, so both sides agree.
- **Single-use tickets**: a ticket can only be confirmed once; replays are rejected and disconnected. Unknown nonces are rejected the same way, so ticket existence is not leaked.
- **Security-boundary tests**: six new tests cover the device entry actually being written, connecting right after pairing, replay rejection, non-disclosure of ticket existence, change notification, and parameter validation — every rejection branch has a case.
- **Signed APK rebuilt**: `deliver/Morrow-Android-0.2.0.apk` is re-signed with the same release certificate; hashes are in `deliver/SHA256SUMS.txt`.

## 安卓端独立仓库（2026-11-22）

版本号 0.2.0。这一节不是功能变更，而是把安卓端从桌面仓库里拆出来，单独建仓、单独发版。

### 中文

**变更概览**：拆出独立仓库 1 个 · 文档正文 7 篇 · 封面与示意图 3 张 · 真实界面截图 9 张

- **仓库拆分**：安卓源码、`docs/`、`scripts/`、`keystore/` 与已签名的 `deliver/Morrow-Android-0.2.0.apk` 全部迁到 `Morrow-Android` 仓库；桌面仓库的 v0.2.0 发布页移除安卓包，只保留两个 Windows 包与更新后的 `SHA256SUMS.txt`。
- **文档体系**：按桌面仓库同一套写作规范重建，双语首页 + 文档地图 + 上手、页面、配对、构建、架构、排错、验证七篇正文，不再依赖桌面仓库的 `docs/MOBILE.md` 与 `docs/PAIRING.md` 传递安卓事实。
- **真实截图**：在 Android 14 模拟器上安装 release 包，用桌面仓库的真实中继完成一次配对，逐页截取工位、对话、协作、审批、设置与配对两页，共 9 张 1080×1920 图像；封面与两张示意图由 `scripts/build-assets.py` 从这些截图生成。
- **事实校正**：修正「随包 APK 缺 `INTERNET` 权限」的说法——该权限在 0.2.0 的包里存在；`aapt2 dump badging` 的输出记在验证页。

### English

**Summary**: 1 standalone repository · 7 documentation pages · 3 generated assets · 9 real interface screenshots

- **Repository split**: the Android sources, `docs/`, `scripts/`, `keystore/` and the signed `deliver/Morrow-Android-0.2.0.apk` move to the `Morrow-Android` repository. The desktop repository's v0.2.0 release drops the Android package and keeps the two Windows builds with an updated `SHA256SUMS.txt`.
- **Documentation**: rebuilt under the same writing rules as the desktop repository — a bilingual front page, a documentation map, and seven body pages covering onboarding, screens, pairing, build, architecture, troubleshooting and verification. Android facts no longer have to travel through the desktop repository's `docs/MOBILE.md` and `docs/PAIRING.md`.
- **Real screenshots**: the release APK was installed on an Android 14 emulator and paired against a real relay from the desktop repository; nine 1080×1920 captures cover the workbench, conversation, collaboration, approvals, settings and both pairing steps. The cover and two diagrams are generated from those captures by `scripts/build-assets.py`.
- **Facts corrected**: the claim that the shipped APK lacks the `INTERNET` permission is wrong — the permission is present in 0.2.0, and the `aapt2 dump badging` output is recorded on the verification page.

## 0.2.0（2026-11-21）

版本号 0.2.0。安卓端从调试包升级为同版本号的 release 签名包，与桌面端共用加密中继通道。

### 中文

**变更概览**：七个页面接入事件流 · 审批生物识别 · 通知与后台 · 作品预览 · 设置页

- **页面接线**：工位、对话、协作、审批、作品、配对、设置七个页面接入真实事件流；对话消息、协作成员、审批队列与作品摘要不再停在空态。
- **审批与安全**：审批允许接入系统生物识别，取消、锁定或验证失败一律拒绝；拒绝回执落盘并在重连后补发。
- **通知与后台**：四个通知渠道、审批动作、完成/卡住/进度提醒、深链定位；长任务由前台服务保活，后台结束时停服务并断开。
- **协作干预**：可停止成员、重试失败成员、发送新指令；桌面缺项目、会话或成员任务 id 时保持只读。
- **作品预览**：新增 `works.preview` 上行与预览页，支持图片、PDF 首页、受限 WebView 中的 HTML 与系统解码器支持的视频，单件上限 4 MiB。
- **设置与偏好**：主题持久化、通知状态跳转、两步清除配对、版本与生物识别状态展示。
- **签名**：改用 release keystore 签名，包名与正式包一致，体积约 2.3 MB。
- **已知限制**：没有真机或模拟器运行证据；通知投递、生物识别弹窗与后台保活未在实体设备验证；未接 FCM。

### English

**Summary**: seven pages wired to the event stream · biometric approvals · notifications and background · work previews · settings screen

- **Pages wired**: the workbench, conversation, collaboration, approvals, works, pairing and settings pages now consume the real event stream; conversations, members, approvals and work summaries no longer sit in empty states.
- **Approvals and security**: allowing a request goes through system biometrics; cancellation, lockout or failure is a denial. Denial receipts are persisted and replayed after a reconnect.
- **Notifications and background**: four channels, approval actions, completion/stuck/progress alerts and deep links; a foreground service keeps long runs alive and disconnects when the run ends.
- **Collaboration controls**: stop a member, retry a failed member, or send a new instruction; the page stays read-only when the desktop omits the project, session or task id.
- **Work previews**: a new `works.preview` request and a preview screen for images, the first PDF page, HTML inside a restricted WebView and system-decoded video, capped at 4 MiB per file.
- **Settings**: persisted theme, notification-status shortcut, two-step pairing removal, version and biometric status.
- **Signing**: release keystore signing with the production package name; the APK is about 2.3 MB.
- **Known limits**: no real-device or emulator evidence; notification delivery, the biometric prompt and background keep-alive are unverified on hardware; no FCM.

## 0.1.0（2026-10-24）

首次随桌面端一起发布，形态是调试包 `1.0.0-debug`，只用于侧载试用，通道与配对尚未接通。

### 中文

- 发布调试包，包名带 `.debug` 后缀。
- 工位、对话、协作、审批、作品五个页面已有界面，但生产路径未接数据源。
- 未接通知、未接前台服务、未接作品预览。

### English

- The debug package ships with a `.debug` package suffix.
- Five pages have their interface, but the production path is not connected to a data source.
- No notifications, no foreground service, no work previews.


