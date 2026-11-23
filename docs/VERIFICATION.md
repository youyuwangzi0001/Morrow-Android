# 交付物与验证记录

这一篇是唯一可以引用交付哈希与检查结果的地方。其他地方只写结论，不复制数字。

## 交付产物

| 文件 | 字节数 | SHA-256 |
|---|---|---|
| `Morrow-Android-0.2.0.apk` | 2,323,442 | `6e713bfd7a8099b0cfbad45f41e17a7405c3ade301beeb0ce74fb0e6f29a4428` |

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
| JVM 用例 | 939 项 `@Test`，29 个测试文件 | 通过 |
| 静态验收 | 276 项断言 | 通过 |
| 生产代码规模 | 14 个文件、17,474 行 | 无子包 |
| 构建 | `:app:assembleRelease` | 产出已签名 APK |

前两项由 `bash scripts/verify.sh` 覆盖；它同时校验清单、签名、insets 与设计令牌一致性。

本轮新增 `ToolSyncTests`（17 项），并把 `verify.sh` 的断言从 265 项扩到 276 项：新增的 11 项里有 7 项守「工具/待办/计划/目标/提示/错误七类事件必须接线到手机状态而不是归入 Ignore」，另外 4 项分别守工具调用进入归并器、对话页投影必须带上工具组、`message.send` 必须等桌面回执、桌面只下发摘要不下发命令原文。

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
| `screens/thread.png` | 对话 | 两条消息、五种模式、输入框 |
| `screens/swarm.png` | 成员协作 | 总目标 + 三个成员卡 |
| `screens/approvals.png` | 审批 | 两条待处理，四段信息与倒计时 |
| `screens/settings-dark.png` | 设置 | 深色主题 |
| `screens/settings-light.png` | 设置 | 浅色主题 |
| `screens/pairing-step1.png` | 配对 | 第一步，配对请求 |
| `screens/pairing-step2.png` | 配对 | 第二步，票据与六位码 |

全部截图由模拟器 `screencap` 直接导出，未经缩放或重绘。界面里的会话名、审批内容与成员任务来自中继侧的样例载荷，不是真实用户数据。

另有 `diagrams/architecture.png`、`diagrams/pairing-flow.png` 两张示意图与 `cover.png` 一张封面，均由 `scripts/build-assets.py` 生成。

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
