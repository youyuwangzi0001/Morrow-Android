# 构建、签名与测试

这一篇面向要跑起来、改起来、出包的人。看懂代码怎么分域看 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 环境

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `compileOptions` 与 `jvmTarget` 都是 17 |
| Gradle | 8.14.3 | 本仓库不带 wrapper，用本机的 |
| Android Gradle Plugin | 8.13.0 | 与 compileSdk 36 配套 |
| Kotlin | 2.2.21 | Compose 编译器随 Kotlin 版本走 |
| compileSdk / targetSdk / minSdk | 36 / 35 / 26 | minSdk 26 即 Android 8.0 |
| build-tools | 35.0.0 | `verify.sh` 用其中的 `aapt2` 与 `apksigner` |

依赖版本集中在 `gradle/libs.versions.toml`，改版本只改那一个文件。

## 仓库结构

```text
app/src/main/java/studio/morrow/android/   14 个生产文件，按域组织，无子包
app/src/test/java/studio/morrow/android/   28 个测试文件，919 项 JVM 用例
docs/                                       正文、截图、示意图与证据
scripts/                                    验收、向量生成、素材生成
keystore/                                   交付签名材料
deliver/                                    已签名 APK 与 SHA256SUMS.txt
```

## 构建

```bash
gradle :app:assembleDebug      # 调试包，套 .debug 包名后缀
gradle :app:assembleRelease    # 交付包，需要 keystore/keystore.properties
```

没有 wrapper 是刻意的：这个工程在只装了 Gradle 的构建机上跑，加 wrapper 会引入一份与机器版本可能不一致的分发。

有两条环境前提值得单独写出来：

1. **工作区路径必须是纯 ASCII**。`gradle.properties` 里开了 `android.overridePathCheck=true`，AGP 能接受中文路径，但 Android SDK 里的 `aapt2` 与模拟器会把非 ASCII 段读成 `????`，表现为「找不到 system-image」或资源编译失败。构建与截图分到两个目录就是为了避开这一点。
2. **SDK 位置**。`verify.sh` 优先用工程同级的 `.sdk`，不存在才退回 `ANDROID_HOME`。

## 签名

交付包用 `keystore/release.keystore` 签名，密码与别名在同目录的 `keystore.properties`。这两份材料**随仓库分发**，因为分发包必须能被同一把钥匙重新签名——否则覆盖安装会因签名不一致失败。

这不是上架密钥。真要上架，必须换成正式密钥并把材料移出仓库；现在这套只保证「同一个包能覆盖安装」。

调试包用工程内的 `keystore/debug.keystore`，同样是自签名，密码是公开的 `android`。

## 测试

三层证据，按可信度从高到低：

| 层 | 规模 | 怎么跑 | 证明什么 |
|---|---|---|---|
| JVM 用例 | 919 项 / 28 个文件 | `bash scripts/run-jvm-tests.sh` | 状态归并、投影、协议向量、加密、Robolectric 界面 |
| 静态验收 | 265 项 | `bash scripts/verify.sh` | 清单、签名、insets、令牌一致性与源码不变式 |
| 模拟器运行 | 手工 | `gradle :app:assembleRelease` 后装到模拟器 | 界面能起来、能配对（见 [VERIFICATION.md](VERIFICATION.md)） |

没有仪器测试（`app/src/androidTest` 为空），也没有真机运行证据。这三层之外的结论都不该写进文档。

界面用例跑在 Robolectric 上，用 `@Config(qualifiers = ...)` 给不同屏幕尺寸各开一个沙箱。因此测试文件只做文件级合并，测试类逐字保留——合并类会破坏沙箱隔离。

## 验收脚本

```bash
bash scripts/verify.sh          # 构建 + 清单/签名 + 静态断言 + JVM 测试证据
bash scripts/run-jvm-tests.sh   # 只跑 JVM 用例
node scripts/run-all-jvm.py     # 按 verify.sh 的类清单并行跑全仓 JVM 测试
```

`verify.sh` 的静态断言直接引用生产文件的路径。移动或合并文件时必须同步更新这些路径，并重新生成 `docs/evidence/` 下的证据日志；否则断言会「因为找不到文件而跳过」，看起来还是绿的。

同样地，所有源码断言都走注释感知的匹配：命中行若全是注释则视为未命中。没有这层，把真实调用删掉、只在 KDoc 里留一句同样能让断言通过。

## 与桌面仓库的关系

这两个仓库共享协议，不共享代码：

- 协议常量、帧格式、配对与加密在两边各有一份实现，靠向量脚本对齐——仓库里的 `scripts/gen-*.mjs` 生成向量，Kotlin 侧读同一份向量做断言。
- 联调可以用 `scripts/e3-desktop-relay.mjs`：它加载桌面仓库的真实 `DirectChannel` 与 `PairingService`，跑通一次真实配对。
- 桌面端的 `npm run verify` 不构建安卓；这里也不跑桌面的测试。两个仓库各自独立发版。

## 提交前检查

```bash
bash scripts/verify.sh
```

一条命令覆盖构建、清单、签名、静态断言与 JVM 用例。改动涉及协议时，额外跑一次 `node scripts/e3-desktop-relay.mjs` 确认跨语言仍能握手。

## 第三方组件与许可

| 组件 | 用途 |
|---|---|
| AndroidX Core KTX / Activity Compose / Fragment | 基础组件与 Activity 集成 |
| Jetpack Compose（BOM 2025.09.00）+ Material 3 | 全部界面 |
| androidx.biometric | 审批时的系统生物识别 |
| kotlinx.serialization | JSON 解析 |
| kotlinx.coroutines | 异步与事件流 |
| JUnit 4 / Robolectric / Compose UI Test | 测试 |

项目本体是 MIT 许可，见 [LICENSE](../LICENSE)。
