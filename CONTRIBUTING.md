# 贡献指南

## 环境

JDK 17、Android SDK（platform 36、build-tools 35.0.0）、Gradle 8.14.3。本仓库不带 Gradle wrapper。

工程路径与 SDK 路径都必须是纯 ASCII。`aapt2` 与模拟器会把非 ASCII 路径段读成 `????`，表现为「找不到资源」或「找不到 system-image」。

```bash
gradle :app:assembleDebug
bash scripts/verify.sh
```

## 结构

| 目录 | 内容 |
|---|---|
| `app/src/main/java/studio/morrow/android/` | 14 个生产文件，按域组织，无子包 |
| `app/src/test/java/studio/morrow/android/` | 28 个测试文件，919 项 JVM 用例 |
| `docs/` | 正文、截图与示意图（`docs/cards/` 与 `app/build-evidence/` 是历史记录与中间产物，不入库） |
| `scripts/` | 验收、向量生成、素材生成 |
| `keystore/` | 交付签名材料 |
| `deliver/` | 已签名 APK 与校验和 |

## 改代码的约定

1. **先定域，再落文件**。写进该域已有文件；只有真正的新域才新建文件。同一域需要拆分时最多拆成「模型」与「界面」两个文件。
2. **不建按类型分的目录**。没有 `util/`、`common/`、`components/`。
3. **测试类名不改**。`scripts/verify.sh` 用「类名 → 证据日志」映射，改名会让验证链与历史证据错位。
4. **测试类不合并**。Robolectric 的 `@Config(qualifiers = ...)` 依赖每类一个沙箱，合并会破坏隔离。只做文件级合并。
5. **静态断言注释感知**。新增断言时沿用 `code_has`，别用裸 `grep`——KDoc 里的一句话能让被删掉的调用看起来还活着。

## 改文档的约定

- 双语两个文件：`README.md` 英文、`README.zh.md` 中文，同序互链、内容完整对应，不逐段对照。
- `docs/` 正文只用中文，一个主题一个文件，平铺不建子目录。
- 数字只写在 [docs/BUILD.md](docs/BUILD.md) 与 [docs/VERIFICATION.md](docs/VERIFICATION.md)，其他地方引用结论。
- 截图放在 `docs/screens/`，图注必须写明数据来源与它是不是样例数据。
- 没验证过的事写「未验证」，不写「应该没问题」。

## 提交与版本

- 提交信息写清「改了什么、为什么」。
- 版本号只在 `app/build.gradle.kts`、CHANGELOG 与文档地图里出现。
- 发版顺序：改 CHANGELOG → 提交 → 打 tag → 发布 Release，附 APK 与 `SHA256SUMS.txt`。

## 与桌面仓库的关系

两个仓库共享协议、不共享代码。改协议时两边都要改，并向 `scripts/` 里的向量脚本同步生成新向量，再跑一次 `node scripts/e3-desktop-relay.mjs` 确认跨语言仍能握手。
