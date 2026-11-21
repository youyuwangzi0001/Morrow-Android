# Changelog · 版本记录

Morrow 安卓端的版本按发布线记录。每节先给完整中文，再给完整英文；两段内容对应，但不逐句对照。

桌面端的版本记录在 [Morrow 仓库](https://github.com/youyuwangzi0001/Morrow/blob/master/CHANGELOG.md)，两端在此之后各自独立发版。

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
