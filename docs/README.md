# Morrow 安卓端文档地图

本页是这套文档的入口。它回答三件事：第一次用该按什么顺序读、遇到具体任务查哪一篇、改文档要守哪些约定。

英文首页见 [README.md](../README.md)，中文首页见 [README.zh.md](../README.zh.md)。桌面端的文档在另一个仓库：<https://github.com/youyuwangzi0001/Morrow>。

当前版本 **0.2.0**，与桌面端同版本号；两端通过加密中继通道通信，各自独立发版。

## 第一次使用

按顺序读三页，大约 15 分钟：

1. [GETTING-STARTED.md](GETTING-STARTED.md) —— 装上、配对、看懂工位页。
2. [PAIRING.md](PAIRING.md) —— 配对与通道的完整流程，连不上时看它。
3. [TROUBLESHOOTING.md](TROUBLESHOOTING.md) —— 按现象索引，只在卡住时查。

只想先装上看一眼也可以：不配对时应用停在未配对态，不会编造任何任务或审批。

## 按任务找文档

| 你想做的事 | 读这篇 |
|---|---|
| 装上 APK、完成第一次配对 | [GETTING-STARTED.md](GETTING-STARTED.md) |
| 搞清八个页面各自能做什么 | [SCREENS.md](SCREENS.md) |
| 把手机和电脑连起来，或搞清安全边界 | [PAIRING.md](PAIRING.md) |
| 从源码构建、签名、跑测试 | [BUILD.md](BUILD.md) |
| 看懂源码分域、事件归并、状态投影 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| 某个页面空白、连不上、收不到通知 | [TROUBLESHOOTING.md](TROUBLESHOOTING.md) |
| 核对交付哈希、测试结果与未验证项 | [VERIFICATION.md](VERIFICATION.md) |

## 按角色阅读

- **使用者**：GETTING-STARTED → SCREENS → 需要时查 TROUBLESHOOTING。
- **接不进桌面的人**：PAIRING → TROUBLESHOOTING 的「连接」一节。
- **开发者**：BUILD → ARCHITECTURE → VERIFICATION。

## 文档约定

| 约定 | 具体要求 |
|---|---|
| 一主题一文件 | 正文平铺在 `docs/` 下，不建子目录；`screens/` 与 `diagrams/` 只放素材 |
| 双语两个文件 | 根 `README.md` 写英文、`README.zh.md` 写中文，两份同序互链、内容完整对应；`docs/` 正文只用中文 |
| 不写文件内目录 | 导航只在本页维护，避免各篇各写一套目录然后漂移 |
| 事实分级 | 已实现直陈；有限制当场写明；未实现或未验证用「未实现」「未验证」标注，并集中到该篇的「已知限制」 |
| 不承诺未来 | 不写路线图，不写「后续将支持」；能力以当前代码为准 |
| 版本只写一处 | 版本号只在 `app/build.gradle.kts`、本页与 CHANGELOG 出现，正文提版本时写「当前版本」 |
| 术语一致 | 工位、审批、成员协作、作品、配对、票据、投影，七个固定说法 |

`app/build-evidence/` 与 `docs/evidence/*.tmp` 是测试与向量脚本的中间产物，已列入 `.gitignore`：它们由 `scripts/gen-*.mjs` 与探针测试每次重新生成，记录的是某一次本机运行。要引用结论请用 [VERIFICATION.md](VERIFICATION.md)，那里是核对后的说法。

## 设计卡片

`docs/cards/` 原本是 0.2.0 之前按卡推进时的设计记录：每张卡一份，写于实现当时，说明那一层要解决什么问题、为什么这样取值。它们已经不再入库——卡片里写的文件路径是**当时**的目录结构（`ui/theme/Tokens.kt`、`notify/Notify.kt` 等），而 0.2.0 已按功能域把这些子包全部拉平到根包，留着只会与真实结构混在一起。

现在看代码结构请以 [ARCHITECTURE.md](ARCHITECTURE.md) 的表为准。当时的设计取舍已经沉淀到那篇正文里。

## 截图与示意图

| 类型 | 位置 | 说明 |
|---|---|---|
| 界面截图 | `screens/*.png` | 1080×1920，来自模拟器安装 release 包后的 `adb exec-out screencap`；其中工位未配对页同时有一份真机截图佐证 |
| 示意图 | `diagrams/*.png` | 手工绘制并提交，只解释结构与时序，图注第一句必须写「示意图」 |
| 封面 | `cover.png` | 2560×1280，由三张真实界面拼成，不加装饰性文字 |

三条硬规则：

1. 图注必须写明数据来源。连的是桌面仓库的真实中继，其中的会话、审批与成员是**测试样例数据**，不能写成真实用户作品。
2. 不把示意图当截图用，也不给截图加营销贴纸。
3. 换图时重跑 `scripts/build-assets.py` 生成封面与示意图，不手工缩放。

## 版本与证据

- 版本号：`0.2.0`，发布资产见 <https://github.com/youyuwangzi0001/Morrow-Android/releases>。
- [VERIFICATION.md](VERIFICATION.md) 是唯一可以引用交付哈希与检查结果的地方。
- `docs/evidence/` 下的日志是脚本真实输出的记录，引用前先看时间戳。
- 逐版本变更见 [CHANGELOG.md](../CHANGELOG.md)。

## 维护这套文档

改一处文档的最小流程：

1. 先判断它属于「第一次使用」「按任务操作」「开发与证据」中的哪一类，再决定改现有文件还是新建。
2. 数字类事实（用例数、文件数、静态验收项数）在 [BUILD.md](BUILD.md) 与 [VERIFICATION.md](VERIFICATION.md) 各出现一次，改完两处对齐。
3. 新增一篇正文，回本页补「按任务找文档」一行，并在两份 README 的文档入口处各补一行。
4. 截了新图，先改 `docs/screens/` 里的文件，再回引用它的正文核对 alt 与图注。

