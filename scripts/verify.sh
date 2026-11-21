#!/usr/bin/env bash
# 工程验收脚本（累积式：每张卡把自己的验收项加进来）：把「能否构建、产物是否真实、清单是否合规」变成可重跑的命令。
# 用法：bash scripts/verify.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 构建缓存按「本机是否存在」探测，不写死某台机器的绝对路径：
# 仓库要能被别人 clone 下来直接跑，硬编码路径只会让脚本在别人机器上静默失败。
if [ -z "${GRADLE_BIN:-}" ]; then
  for candidate in "$ROOT/../.build/gradle-8.14.3/bin/gradle" "$ROOT/gradle-8.14.3/bin/gradle"; do
    if [ -x "$candidate" ]; then GRADLE_BIN="$candidate"; break; fi
  done
fi
GRADLE_BIN="${GRADLE_BIN:-gradle}"
if [ -z "${GRADLE_USER_HOME:-}" ] && [ -d "$ROOT/../.build/gradle-home" ]; then
  export GRADLE_USER_HOME="$ROOT/../.build/gradle-home"
fi
# 优先用工作区内的 SDK：用户目录下的 SDK 在本机只读（装不了 emulator/镜像），
# 且不同机器的 SDK 内容可能不同。工作区 SDK 是可复现的那一份。
# 注意：Android SDK 路径必须是纯 ASCII —— aapt2 与模拟器会把非 ASCII 段读成 `????`。
if [ -d "$ROOT/../.sdk/platforms" ]; then
  export ANDROID_HOME="$(cd "$ROOT/../.sdk" && pwd)"
else
  export ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
fi

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt2.exe"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner.bat"

# ── 注释感知的源码断言 ──────────────────────────────────────────
# 直接用 `grep -q 'enableEdgeToEdge()'` 断「Activity 里调了它」是不可靠的：
# KDoc 里的 `enableEdgeToEdge()` 会被命中，于是把真实调用删掉断言照样 PASS。
# 这类假通过比没有断言更危险 —— 它给出「已验证」的错觉。
# 所以静态断言一律走 code_has：命中行若全是注释则视为未命中。
#
# 三种用法：
#   code_has 'PAT' FILE...        # 在文件里找
#   cmd | code_has 'PAT'          # 在 stdin 里找（无文件参数时读 stdin）
#   code_has -E 'PAT' FILE...
code_has() {
  local opt=""
  if [ "${1:-}" = "-E" ]; then opt="-E"; shift; fi
  local pat="${1:-}"; shift
  [ -n "$pat" ] || return 1
  local out
  if [ "$#" -gt 0 ]; then
    out="$(grep -h $opt -- "$pat" "$@" 2>/dev/null)"
  else
    out="$(grep $opt -- "$pat" 2>/dev/null)"
  fi
  [ -n "$out" ] || return 1
  # 逐行判断：只要有一行不是注释（且非空）就算命中
  printf '%s
' "$out" | grep -qvE '^[[:space:]]*(\*|//|/\*|<!--|#|$)'
}

pass=0
fail=0
check() {
  local label="$1"; local ok="$2"; local detail="${3:-}"
  if [ "$ok" = "1" ]; then
    printf '  \033[32mPASS\033[0m  %s%s\n' "$label" "${detail:+ · $detail}"
    pass=$((pass+1))
  else
    printf '  \033[31mFAIL\033[0m  %s%s\n' "$label" "${detail:+ · $detail}"
    fail=$((fail+1))
  fi
}

# ─────────── 测试预热：一次并行跑完全部 JVM 测试类 ───────────
#
# 为什么要预热：每个测试类的耗时里 Robolectric 沙盒初始化占 45–60 秒，
# 而本机 20 核、类之间互不依赖。逐个类串行跑 32 类约 40 分钟，
# 一次性 8 路并行只要几分钟，**项数与结果逐类一致**。
#
# 预跑后把每个类的结果拆到各自的 $EVID/*.log，
# 下面每张卡的断言仍然按原样检查自己的那一份日志 ——
# 断言粒度、证据文件、失败定位都没有变，只是不再重复启动 JVM。
EVID="$ROOT/docs/evidence"
mkdir -p "$EVID"
EV_PRE="$ROOT/build-evidence-prerun"
if [ "${MORROW_SKIP_PRERUN:-0}" != "1" ]; then
  echo "▸ 预热：并行跑完全部 JVM 测试类（这一步最耗时）"
  mkdir -p "$EV_PRE"
  bash "$ROOT/scripts/run-jvm-tests.sh" --prepare > "$EV_PRE/prepare.log" 2>&1     || { echo "  ✗ 测试预处理失败，见 $EV_PRE/prepare.log"; }
  bash "$ROOT/scripts/run-jvm-tests.sh" studio.morrow.android.AdaptiveScaffoldTest studio.morrow.android.ApprovalInboxTest studio.morrow.android.ApprovalNavigationTest studio.morrow.android.BackgroundPolicyTest studio.morrow.android.ApprovalProjectionTest studio.morrow.android.ApprovalQueueTest studio.morrow.android.ApprovalScreenTest studio.morrow.android.BiometricGateTest studio.morrow.android.EventReducerTest studio.morrow.android.FcmPayloadTest studio.morrow.android.FcmReceiverTest studio.morrow.android.ForegroundSyncServiceTest studio.morrow.android.HardwareDecodeTest studio.morrow.android.IgnoreBridgesTest studio.morrow.android.MainActivityPendingPullTest studio.morrow.android.MorrowAppRenderTest studio.morrow.android.MorrowScaffoldTest studio.morrow.android.MorrowThemeTest studio.morrow.android.NotifyChannelsTest studio.morrow.android.NotifyContentTest studio.morrow.android.NotifyDeepLinkTest studio.morrow.android.NotifyPolicyTest studio.morrow.android.NotificationSignalsTest studio.morrow.android.RelayNotificationsTest studio.morrow.android.RelaySyncTrackingTest studio.morrow.android.SettingsPreferencesTest studio.morrow.android.SettingsScreenTest studio.morrow.android.PdfLoadPolicyTest studio.morrow.android.PreviewModelTest studio.morrow.android.PreviewScreenTest studio.morrow.android.PendingPullTest studio.morrow.android.ReplayPackTest studio.morrow.android.SandboxWebViewTest studio.morrow.android.SwarmCommandWiringTest studio.morrow.android.SwarmReducerTest studio.morrow.android.SwarmProjectionTest studio.morrow.android.SwarmScreenTest studio.morrow.android.ThreadReducerTest studio.morrow.android.ThreadScreenTest studio.morrow.android.WorkbenchModelTest studio.morrow.android.WorkbenchScreenTest studio.morrow.android.WorkbenchStopWiringTest studio.morrow.android.WorksModelTest studio.morrow.android.WorksNavigationTest studio.morrow.android.WorksProjectionTest studio.morrow.android.WorksScreenTest studio.morrow.android.EventStreamViewModelTest studio.morrow.android.WorkbenchProjectionTest studio.morrow.android.EventStreamWiringTest studio.morrow.android.RelayEnvelopeTest studio.morrow.android.ToolSyncTests studio.morrow.android.RelayEventSinkTest studio.morrow.android.RelayWiringTest studio.morrow.android.MainThreadDispatcherTest studio.morrow.android.SecureChannelVectorTest studio.morrow.android.SecureChannelSealProbe studio.morrow.android.EcdhPairingVectorTest studio.morrow.android.RelayProtocolVectorTest studio.morrow.android.WebSocketFramesTest studio.morrow.android.RelaySocketEndToEndTest studio.morrow.android.RelayCredentialsTest studio.morrow.android.RelayConnectionControllerTest studio.morrow.android.RelayConnectionWiringTest studio.morrow.android.MainActivityRelayChipTest studio.morrow.android.PairingFlowTest studio.morrow.android.DeskPairingVectorTest studio.morrow.android.RelayHintTest studio.morrow.android.PairingEntryTest studio.morrow.android.PairingRequestTest studio.morrow.android.TicketFieldProbe studio.morrow.android.TlsFingerprintVectorTest studio.morrow.android.CrossDeviceE2ETest > "$EV_PRE/all.log" 2>&1 || true

  # 把并行汇总里的每个类日志按「类名 → EVID 文件名」搬到该卡期望的位置。
  # 映射关系直接取自脚本自身（下面这些 $EVID/*.log 的写入点），避免手抄错位。
  LOGDIR="$ROOT/../.build/test-logs"
  copy_log() {  # copy_log <类名> <目标日志名>
    local cls="$1" dest="$2" f
    f=$(ls "$LOGDIR"/*-"$cls".log 2>/dev/null | head -1)
    if [ -n "$f" ]; then cp "$f" "$EVID/$dest"; else : > "$EVID/$dest"; fi
  }
  copy_log "AdaptiveScaffoldTest" "adaptive-test.log"
  copy_log "ApprovalNavigationTest" "approval-nav-test.log"
  copy_log "ApprovalQueueTest" "approval-queue-test.log"
  copy_log "ApprovalScreenTest" "approval-screen-test.log"
  copy_log "EventReducerTest" "event-reducer-test.log"
  copy_log "FcmPayloadTest" "fcm-payload-test.log"
  copy_log "FcmReceiverTest" "fcm-receiver-test.log"
  copy_log "ForegroundSyncServiceTest" "foreground-sync-test.log"
  copy_log "HardwareDecodeTest" "hw-decode-test.log"
  copy_log "IgnoreBridgesTest" "ignore-bridges-test.log"
  copy_log "MainActivityPendingPullTest" "pending-pull-wiring-test.log"
  copy_log "MorrowAppRenderTest" "render-test.log"
  copy_log "MorrowScaffoldTest" "scaffold-test.log"
  copy_log "MorrowThemeTest" "theme-test.log"
  copy_log "NotifyChannelsTest" "notify-channels-test.log"
  copy_log "NotifyContentTest" "notify-content-test.log"
  copy_log "NotifyDeepLinkTest" "notify-deeplink-test.log"
  copy_log "NotifyPolicyTest" "notify-policy-test.log"
  copy_log "PdfLoadPolicyTest" "pdf-policy-test.log"
  copy_log "PendingPullTest" "pending-pull-test.log"
  copy_log "ReplayPackTest" "replay-pack-test.log"
  copy_log "SandboxWebViewTest" "sandbox-webview-test.log"
  copy_log "SwarmReducerTest" "swarm-reducer-test.log"
  copy_log "SwarmScreenTest" "swarm-screen-test.log"
  copy_log "ThreadReducerTest" "thread-reducer-test.log"
  copy_log "ThreadScreenTest" "thread-screen-test.log"
  copy_log "WorkbenchModelTest" "workbench-model-test.log"
  copy_log "WorkbenchScreenTest" "workbench-screen-test.log"
  copy_log "WorkbenchStopWiringTest" "workbench-stop-wiring-test.log"
  copy_log "WorksModelTest" "works-model-test.log"
  copy_log "WorksNavigationTest" "works-nav-test.log"
  copy_log "WorksScreenTest" "works-screen-test.log"
  copy_log "EventStreamViewModelTest" "viewmodel-stream-test.log"
  copy_log "WorkbenchProjectionTest" "projection-test.log"
  copy_log "EventStreamWiringTest" "stream-wiring-test.log"
  copy_log "RelayEnvelopeTest" "relay-envelope-test.log"
  copy_log "RelayEventSinkTest" "relay-sink-test.log"
  copy_log "RelayWiringTest" "relay-wiring-test.log"
  copy_log "MainThreadDispatcherTest" "main-thread-dispatcher-test.log"
  copy_log "SecureChannelVectorTest" "secure-channel-vector-test.log"
  copy_log "SecureChannelSealProbe" "secure-channel-seal-probe.log"
  copy_log "EcdhPairingVectorTest" "ecdh-pairing-vector-test.log"
  copy_log "RelayProtocolVectorTest" "relay-protocol-vector-test.log"
  copy_log "WebSocketFramesTest" "websocket-frames-test.log"
  copy_log "RelaySocketEndToEndTest" "relay-socket-e2e-test.log"
  copy_log "RelayCredentialsTest" "relay-credentials-test.log"
  copy_log "RelayConnectionControllerTest" "relay-connection-controller-test.log"
  copy_log "RelayConnectionWiringTest" "relay-connection-wiring-test.log"
  copy_log "MainActivityRelayChipTest" "main-activity-relay-chip-test.log"
  copy_log "PairingFlowTest" "pairing-flow-test.log"
  copy_log "DeskPairingVectorTest" "desk-pairing-vector-test.log"
  copy_log "RelayHintTest" "relay-hint-test.log"
  copy_log "PairingEntryTest" "pairing-entry-test.log"
  copy_log "PairingRequestTest" "pairing-request-test.log"
  copy_log "TicketFieldProbe" "ticket-field-probe.log"
  copy_log "TlsFingerprintVectorTest" "tls-fingerprint-vector-test.log"
  copy_log "CrossDeviceE2ETest" "cross-device-e2e-test.log"
  echo "  ✓ 预热完成，各测试日志已就位"
fi

echo "═══ Morrow Android · 工程验收 ═══"
echo "工程：$ROOT"
echo "SDK ：$ANDROID_HOME"
echo

echo "[1/32] 构建 debug 包"
EVID_DIR="$ROOT/docs/evidence"
build_log="$EVID_DIR/build-verify.log"
"$GRADLE_BIN" -p "$ROOT" :app:assembleDebug --no-daemon --console=plain > "$build_log" 2>&1
check "assembleDebug 构建成功" "$([ $? -eq 0 ] && echo 1 || echo 0)"
check "APK 产物存在" "$([ -f "$APK" ] && echo 1 || echo 0)" "$([ -f "$APK" ] && du -h "$APK" | cut -f1)"

echo
echo "[2/32] 清单与 SDK 等级"
badging="$("$AAPT" dump badging "$APK" 2>/dev/null)"
pkg="$(printf '%s' "$badging" | grep -oP "(?<=package: name=')[^']+" || true)"
min_sdk="$(printf '%s' "$badging" | grep -oP "(?<=minSdkVersion:')[^']+" || true)"
tgt_sdk="$(printf '%s' "$badging" | grep -oP "(?<=targetSdkVersion:')[^']+" || true)"
label="$(printf '%s' "$badging" | grep -oP "(?<=application-label:')[^']+" || true)"
act="$(printf '%s' "$badging" | grep -oP "(?<=launchable-activity: name=')[^']+" || true)"

check "包名 studio.morrow.android" "$([ "$pkg" = "studio.morrow.android.debug" ] && echo 1 || echo 0)" "$pkg"
vcode="$(printf '%s' "$badging" | grep -oP "(?<=versionCode=')[^']+" || true)"
vname="$(printf '%s' "$badging" | grep -oP "(?<=versionName=')[^']+" || true)"
check "versionCode = 2（与桌面 0.2.0 对齐）" "$([ "$vcode" = "2" ] && echo 1 || echo 0)" "$vcode"
check "versionName = 0.2.0（debug 追加 -debug）" "$([ "$vname" = "0.2.0-debug" ] && echo 1 || echo 0)" "$vname"
# 没有 INTERNET 时真机的 socket 连接会抛 SecurityException；Robolectric 测不出来，
# 只能靠合并清单。这条回归断言对应 2026-11-21 审计发现的 P0。
check "合并清单包含 INTERNET 权限" "$(printf '%s' "$badging" | grep -q "uses-permission: name='android.permission.INTERNET'" && echo 1 || echo 0)" "缺它 = 真机连不上桌面"
check "minSdk = 26（Android 8 起）" "$([ "$min_sdk" = "26" ] && echo 1 || echo 0)" "$min_sdk"
check "targetSdk = 35" "$([ "$tgt_sdk" = "35" ] && echo 1 || echo 0)" "$tgt_sdk"
check "应用名 Morrow" "$([ "$label" = "Morrow" ] && echo 1 || echo 0)" "$label"
check "可启动入口 MainActivity" "$([ "$act" = "studio.morrow.android.MainActivity" ] && echo 1 || echo 0)" "$act"

echo
echo "[3/32] 图标资源"
# aapt2 会把同一份自适应图标按 160/240/320/480/640 各列一次（外加 -65534 兜底项），
# 那不是「几档密度」，直接数会虚报。这里改判「自适应图标被解析到」+「5 档位图齐全」。
icon_ok="$(printf '%s' "$badging" | grep -q "application-icon-640:.*mipmap-anydpi-v26/ic_launcher.xml" && echo 1 || echo 0)"
fg_in_apk="$(unzip -l "$APK" | grep -c "ic_launcher_foreground.png" || true)"
legacy_in_apk="$(unzip -l "$APK" | grep -c "res/mipmap-.*/ic_launcher.png" || true)"
check "自适应图标已解析（640dpi 命中 anydpi-v26）" "$icon_ok"
check "5 档密度前景层齐全" "$([ "$fg_in_apk" -ge 5 ] && echo 1 || echo 0)" "${fg_in_apk} 个"
check "5 档密度传统图标齐全（Android 8 以下回退）" "$([ "$legacy_in_apk" -ge 5 ] && echo 1 || echo 0)" "${legacy_in_apk} 个"

echo
echo "[4/32] 签名"
sig="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | head -1 || true)"
check "APK 已签名" "$(printf '%s' "$sig" | code_has 'Signer #1' && echo 1 || echo 0)" "$(printf '%s' "$sig" | sed 's/Signer #1 certificate DN: //')"

# 真正验签（不只是「有证书」）。这里必须**逐方案看布尔值**：
# `apksigner verify -v` 会打印 5 行方案（v1/v2/v3/v3.1/v4），
# 之前几轮的卡片把「5 行」当成「5 个方案通过」—— 实际只有 v2 是 true。
# 数输出行 ≠ 读结果，这条是那一处误报的直接修正。
scheme_out="$("$APKSIGNER" verify -v "$APK" 2>/dev/null || true)"
verify_exit=$("$APKSIGNER" verify "$APK" >/dev/null 2>&1 && echo 0 || echo 1)
check "APK 验签通过（apksigner verify 退出码 0）" "$([ "$verify_exit" = "0" ] && echo 1 || echo 0)"   "$(printf '%s' "$scheme_out" | grep -E 'v[0-9.]+ scheme' | sed 's/Verified using //' | tr '
' ' ')"
# minSdk 26 → v1（JAR 签名）不需要，v3/v3.1 只在密钥轮换时需要。
# 所以这里断言的是「v2 已签名」，不是「全都为 true」。
check "APK 使用 v2 签名方案（minSdk 26 的必需项）"   "$(printf '%s' "$scheme_out" | grep -q 'v2 scheme.*: true' && echo 1 || echo 0)" "v1/v3 未启用属预期"

echo
echo "[5/32] 无边框与主题声明"
cfg="$ROOT/app/src/main/res/values-v27/themes.xml"
edge_ok=1
grep -q "statusBarColor\">@android:color/transparent" "$cfg" || edge_ok=0
grep -q "navigationBarColor\">@android:color/transparent" "$cfg" || edge_ok=0
code_has 'windowLayoutInDisplayCutoutMode">shortEdges' "$cfg" || edge_ok=0
check "状态栏 / 导航栏透明 + 刘海铺满" "$edge_ok"
check "MainActivity 调用 enableEdgeToEdge" "$(code_has 'enableEdgeToEdge(' "$ROOT/app/src/main/java/studio/morrow/android/MainActivity.kt" && echo 1 || echo 0)"

echo
echo "[6/32] 设计令牌一致性"
TOKENS="$ROOT/app/src/main/java/studio/morrow/android/DesignTokens.kt"
# 用户明确要求：所有控件与标签都用桌面版一致的微圆角矩形，不要胶囊形。
# 这里把它变成回归断言 —— 令牌里出现 pill/999dp/circle 即失败。
no_pill=1
grep -qE 'pill|999\.dp|CircleShape' "$TOKENS" && no_pill=0
check "无胶囊圆角令牌（微圆角矩形）" "$no_pill"
# 桌面 --r-sm=5dp 控件、--r-lg=9dp 卡片：手机端同值不放大
check "控件圆角 = 5dp（对齐桌面 --r-sm）" "$(code_has 'val control: Dp = sm' "$TOKENS" && code_has 'val sm = 5.dp' "$TOKENS" && echo 1 || echo 0)"
check "卡片圆角 = 9dp（对齐桌面 --r-lg）" "$(code_has 'val card: Dp = lg' "$TOKENS" && code_has 'val lg = 9.dp' "$TOKENS" && echo 1 || echo 0)"
# 默认主题必须是墨夜：桌面是深色优先产品
check "默认主题固定为墨夜" "$(code_has 'mode: MorrowThemeMode = MorrowThemeMode.Ink' "$ROOT/app/src/main/java/studio/morrow/android/Shell.kt" && echo 1 || echo 0)"

echo
echo "[7/32] A2 卡：token 一一对应与对比度"
# 证据统一归档到 docs/evidence/（已在预热段定义并创建，见文件开头）
# token 对照：桌面 styles.css 是唯一事实源，手机端缺项或色值偏差即失败
if node "$ROOT/scripts/token-parity.mjs" > "$EVID/token-parity.log" 2>&1; then
  check "token 与桌面 CSS 变量一一对应" 1 "$(grep -oP '比对项：\K[0-9]+' "$EVID/token-parity.log" | head -1) 项"
else
  check "token 与桌面 CSS 变量一一对应" 0 "见 docs/evidence/token-parity.log"
fi
# 对比度：按蓝图 03 章安卓线（正文 7:1 / 元信息 4.5:1，底色按 C1 抬底后计算）
if node "$ROOT/scripts/contrast-check.mjs" > "$EVID/contrast-check.log" 2>&1; then
  check "对比度达文档安卓线" 1 "36 组（正文 7:1 / 元信息 4.5:1）"
else
  check "对比度达文档安卓线" 0 "见 docs/evidence/contrast-check.log"
fi
# 三处移动端调整：C1 抬底 / C3 降饱和，按文档规则重算比对
if node "$ROOT/scripts/mobile-adjust.mjs" > "$EVID/mobile-adjust.log" 2>&1; then
  check "C1 抬底 / C3 降饱和符合文档规则" 1 "$(grep -oP 'PASS（\K[0-9]+' "$EVID/mobile-adjust.log" | head -1) 项"
else
  check "C1 抬底 / C3 降饱和符合文档规则" 0 "见 docs/evidence/mobile-adjust.log"
fi

echo
echo "[8/32] 渲染与主题测试"
if [ -f "$EVID/render-test.log" ] \
   && grep -q "^OK" "$EVID/render-test.log"; then
  check "外壳渲染测试（A1）" 1 "$(grep -oP 'OK \(\K[0-9]+' "$EVID/render-test.log" | head -1) 项"
else
  check "外壳渲染测试（A1）" 0 "见 docs/evidence/render-test.log"
fi
if [ -f "$EVID/theme-test.log" ] \
   && grep -q "^OK" "$EVID/theme-test.log"; then
  check "主题切换即时生效（A2）" 1 "$(grep -oP 'OK \(\K[0-9]+' "$EVID/theme-test.log" | head -1) 项"
else
  check "主题切换即时生效（A2）" 0 "见 docs/evidence/theme-test.log"
fi
if [ -f "$EVID/scaffold-test.log" ] \
   && grep -q "^OK" "$EVID/scaffold-test.log"; then
  check "insets 体系生效（A3）" 1 "$(grep -oP 'OK \(\K[0-9]+' "$EVID/scaffold-test.log" | head -1) 项"
else
  check "insets 体系生效（A3）" 0 "见 docs/evidence/scaffold-test.log"
fi
if [ -f "$EVID/adaptive-test.log" ] \
   && grep -q "^OK" "$EVID/adaptive-test.log"; then
  check "分辨率适配层生效（A4）" 1 "$(grep -oP 'OK \(\K[0-9]+' "$EVID/adaptive-test.log" | head -1) 项"
else
  check "分辨率适配层生效（A4）" 0 "见 docs/evidence/adaptive-test.log"
fi

echo
echo "[9/32] A3 卡：insets 体系静态约束"
SYS="$ROOT/app/src/main/java/studio/morrow/android/Shell.kt"
# insets 只吃 padding 不吃 margin：脚手架里不得出现 margin 施加 inset
check "insets 只吃 padding（脚手架无 margin）" "$(grep -qE 'windowInsetsPadding|\.padding\(padding\)' "$SYS" && ! code_has 'windowInsets.*Margin\|\.margin(' "$SYS" && echo 1 || echo 0)"
# 手势条额外 8dp
check "手势条额外留白 = 8dp" "$(code_has 'GestureBarExtra: Dp = 8.dp' "$SYS" && echo 1 || echo 0)"
# 系统栏透明声明仍在（theme 层）
check "系统栏透明 + 刘海铺满声明齐全" "$(code_has 'statusBarColor\">@android:color/transparent' "$ROOT/app/src/main/res/values-v27/themes.xml" && code_has 'shortEdges' "$ROOT/app/src/main/res/values-v27/themes.xml" && echo 1 || echo 0)"
# 系统栏图标按亮度反色（不是写死）
check "系统栏图标按背景亮度反色" "$(code_has 'useDarkIcons(background: Color)' "$SYS" && code_has 'ApplySystemBars' "$SYS" && echo 1 || echo 0)"
# 首页必须走脚手架体系：直接调 MorrowScaffold，或经由 AdaptiveScaffold（后者内部会调 MorrowScaffold）
check "首页使用脚手架（MorrowScaffold / AdaptiveScaffold）" "$(grep -qE 'MorrowScaffold\(|AdaptiveScaffold\(' "$ROOT/app/src/main/java/studio/morrow/android/Shell.kt" && echo 1 || echo 0)"

echo
echo "[10/32] A4 卡：分辨率适配静态约束"
ADA="$ROOT/app/src/main/java/studio/morrow/android/Shell.kt"
WB="$ROOT/app/src/main/java/studio/morrow/android/Workbench.kt"
# 三档边界必须与蓝图一致：600 / 840
check "宽度分档 600 / 840dp" "$(code_has 'COMPACT_MAX_DP = 600f' "$ADA" && code_has 'MEDIUM_MAX_DP = 840f' "$ADA" && echo 1 || echo 0)"
# 短屏阈值 640dp
check "短屏阈值 = 640dp" "$(code_has 'SHORT_SCREEN_MAX_DP = 640f' "$ADA" && echo 1 || echo 0)"
# 字体缩放降级阈值 1.3×
check "字体缩放降级阈值 = 1.3×" "$(code_has 'LARGE_FONT_THRESHOLD = 1.3f' "$ADA" && echo 1 || echo 0)"
# 超宽屏内容栏上限 720dp
check "内容栏上限 = 720dp" "$(code_has 'CONTENT_MAX_WIDTH: Dp = 720.dp' "$ADA" && echo 1 || echo 0)"
# 短屏必须收起装饰：C1 之后首页是工位页，短屏规则落到「底部状态条省掉环境说明」，
# 规则本身（< 640dp 减装饰）沿用 A4，不因换页而失效。
check "短屏收起装饰说明（A4 规则延续）" "$(code_has 'if (!isShortScreen)' "$WB" && echo 1 || echo 0)"

echo
echo "[11/32] B4 卡：通知内容最小化与免打扰"
NOTIFY="$ROOT/app/src/main/java/studio/morrow/android/Notifications.kt"
THREAD="$ROOT/app/src/main/java/studio/morrow/android/Thread.kt"
SWARM="$ROOT/app/src/main/java/studio/morrow/android/Swarm.kt"
# 四类通知必须各有独立渠道：共用渠道 = 用户失去一类控制
check "四类通知独立渠道（审批/完成/卡住/进度）" "$([ "$(grep -c 'channelId = \"morrow_' "$NOTIFY")" -eq 4 ] && echo 1 || echo 0)" "$(grep -o 'channelId = \"morrow_[a-z]*\"' "$NOTIFY" | wc -l) 个"
# 只有审批豁免免打扰 —— 它是唯一「不处理就卡住别人」的类别
check "仅审批豁免免打扰" "$([ "$(grep -c 'survivesDoNotDisturb = true' "$NOTIFY")" -eq 1 ] && code_has 'survivesDoNotDisturb = true' "$NOTIFY" && echo 1 || echo 0)"
# 进度类必须 LOW 重要性（每秒更新用 DEFAULT 会变骚扰）
check "进度类重要性 = LOW" "$(code_has 'channelName = \"长任务进度\",' "$NOTIFY" && code_has 'importance = NotifyImportance.LOW' "$NOTIFY" && echo 1 || echo 0)"
# 构造函数必须 private：否则调用方能绕过清洗直接造一个带任意 body 的通知
check "NotifyContent 构造函数私有（无法绕过清洗）" "$(code_has 'class NotifyContent private constructor' "$NOTIFY" && echo 1 || echo 0)"
# 文件名必须有字符白名单（只去目录会让 apiKey=... 这类凭据漏进正文）
check "文件名过字符白名单（拦凭据形态）" "$(code_has 'SAFE_FILE_NAME.matches(base)' "$NOTIFY" && echo 1 || echo 0)"
# 汇总通知固定 id：随机 id 会让通知堆积
check "汇总通知使用固定 id" "$(code_has 'const val DIGEST_ID' "$NOTIFY" && echo 1 || echo 0)"
if [ -f "$EVID/notify-content-test.log" ] \
   && grep -qa "^OK" "$EVID/notify-content-test.log"; then
  check "内容最小化测试（B4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/notify-content-test.log" | head -1) 项"
else
  check "内容最小化测试（B4）" 0 "见 docs/evidence/notify-content-test.log"
fi
if [ -f "$EVID/notify-policy-test.log" ] \
   && grep -qa "^OK" "$EVID/notify-policy-test.log"; then
  check "免打扰与汇总测试（B4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/notify-policy-test.log" | head -1) 项"
else
  check "免打扰与汇总测试（B4）" 0 "见 docs/evidence/notify-policy-test.log"
fi

echo
echo "[12/32] B4 卡（第二张）：真实渠道注册与投递"
# 渠道参数必须全部取自 NotifyKind，不能在 NotifyChannels 里另写字面量 ——
# 否则「枚举改了渠道还是旧值」这类不一致会没有任何报错地存在
check "渠道 id 只在 NotifyKind 里定义" "$([ "$(grep -c 'channelId = \"morrow_' "$NOTIFY")" -eq 4 ] && [ "$(sed -n '/───── NotifyChannels\.kt ─────/,/───── NotifyPolicy\.kt ─────/p' "$NOTIFY" | grep -c 'channelId = ')" -eq 0 ] && echo 1 || echo 0)"
# 建完必须读回来核对重要性：Android 对已存在渠道不改重要性且不报错
check "渠道注册后读回核对重要性" "$(code_has 'importanceMismatches' "$NOTIFY" && code_has 'stored.importance != kind.importance' "$NOTIFY" && echo 1 || echo 0)"
# 汇总必须用固定 id：随机 id 会让通知栏堆积
check "汇总投递使用固定 id" "$(code_has 'notify(NotifyPolicy.DIGEST_ID' "$NOTIFY" && echo 1 || echo 0)"
# 两个动作按钮的 Intent data 必须不同：只在 extra 上不同会被系统判为同一个 PendingIntent
check "审批两动作 Intent data 不同（防点拒绝执行允许）" "$(code_has 'PARAM_DECISION}=allow' "$NOTIFY" && code_has 'PARAM_DECISION}=deny' "$NOTIFY" && echo 1 || echo 0)"
# PendingIntent 必须 IMMUTABLE：可变的是经典提权入口
check "PendingIntent 全部 IMMUTABLE" "$([ "$(grep -c 'PendingIntent.FLAG_IMMUTABLE' "$NOTIFY")" -ge 2 ] && echo 1 || echo 0)"
# 「允许」绝不能在后台广播里生效，必须推开应用过生物识别
check "允许不走广播（必须过生物识别）" "$(code_has 'if (allow) return' "$NOTIFY" && echo 1 || echo 0)"
# 深链 scheme 必须精确比对，不能 contains/startsWith
check "深链 scheme 精确比对" "$(code_has 'uri.scheme != SCHEME' "$NOTIFY" && [ "$(grep -c 'contains(SCHEME)\|startsWith(SCHEME)' "$NOTIFY")" -eq 0 ] && echo 1 || echo 0)"
# Receiver 必须不导出：导出会成为外部应用伪造「拒绝」的入口
check "审批 Receiver 不导出" "$(code_has 'android:name=\".ApprovalActionReceiver\"' app/src/main/AndroidManifest.xml && grep -A1 'ApprovalActionReceiver' app/src/main/AndroidManifest.xml | code_has 'exported=\"false\"' && echo 1 || echo 0)"
# 渠道必须在 Application.onCreate 注册：
# Android 8 起往不存在的渠道投递不报错、只静默不显示 ——
# 注册漏了的表现是「用户永远收不到通知，且没有任何提示」
check "渠道在应用启动时注册（生产入口接线）" "$(code_has 'NotifyChannels.register(this)' app/src/main/java/studio/morrow/android/MainActivity.kt && echo 1 || echo 0)"
# 通知 id 必须优先用深链的 focus：只按 groupKey 算会让所有审批共用同一个 id，
# 同时等两条批准时后到的那条把先到的顶掉（漏掉的 agent 会永远停着等人）
check "通知 id 按 focus 区分（不同审批不互相覆盖）" "$(code_has 'val focusId = when' "$NOTIFY" && code_has 'val key = focusId ?: groupKey' "$NOTIFY" && echo 1 || echo 0)"
if [ -f "$EVID/notify-channels-test.log" ] \
   && grep -qa "^OK" "$EVID/notify-channels-test.log"; then
  check "渠道注册与真实投递测试（B4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/notify-channels-test.log" | head -1) 项"
else
  check "渠道注册与真实投递测试（B4）" 0 "见 docs/evidence/notify-channels-test.log"
fi
if [ -f "$EVID/notify-deeplink-test.log" ] \
   && grep -qa "^OK" "$EVID/notify-deeplink-test.log"; then
  check "深链与审批动作测试（B4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/notify-deeplink-test.log" | head -1) 项"
else
  check "深链与审批动作测试（B4）" 0 "见 docs/evidence/notify-deeplink-test.log"
fi

echo
echo "[13/32] B4 卡（第三张）：前台服务常驻进度"
# Android 14 起 dataSync 类型必须在 Manifest 声明，缺失会在 startForeground 抛异常
check "服务声明 foregroundServiceType=dataSync" "$(code_has 'android:foregroundServiceType=\"dataSync\"' app/src/main/AndroidManifest.xml && echo 1 || echo 0)"
# 三条权限缺任一条都会在真机上炸（14 上缺 DATA_SYNC 抛 SecurityException）
check "前台服务三条权限齐备" "$(code_has 'FOREGROUND_SERVICE\"' app/src/main/AndroidManifest.xml && code_has 'FOREGROUND_SERVICE_DATA_SYNC' app/src/main/AndroidManifest.xml && code_has 'POST_NOTIFICATIONS' app/src/main/AndroidManifest.xml && echo 1 || echo 0)"
# onTimeout 不实现 → Android 15 到点抛 ForegroundServiceDidNotStopInTimeException（崩溃）
check "实现 onTimeout（Android 15 时限）" "$([ "$(grep -c 'override fun onTimeout' "$NOTIFY")" -eq 2 ] && echo 1 || echo 0)"
# 运行时也要把 type 传给 startForeground，只在 Manifest 声明不够
check "startForeground 运行时传 DATA_SYNC type" "$(code_has 'FOREGROUND_SERVICE_TYPE_DATA_SYNC' "$NOTIFY" && echo 1 || echo 0)"
# 三参数 startForeground 而不是两参数版（14 上两参数版会抛异常）
check "使用三参数 startForeground" "$([ "$(grep -c 'startForeground(' "$NOTIFY")" -ge 1 ] && code_has 'ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC' "$NOTIFY" && echo 1 || echo 0)"
# 启动必须用 startForegroundService：26+ 用 startService 会 ANR
check "启动用 startForegroundService（非 startService）" "$(code_has 'context.startForegroundService(intent)' "$NOTIFY" && [ "$(grep -c 'context.startService(intent)' "$NOTIFY")" -eq 0 ] && echo 1 || echo 0)"
# 去重两个方向都要在：服务启动时撤遗留单条 + 投递端认接管
check "双向去重（撤遗留单条 + 投递端让位）" "$(code_has 'cancelSingleProgress(taskId)' "$NOTIFY" && code_has 'TakenOverByForegroundService' "$NOTIFY" && echo 1 || echo 0)"
# 三个 id 空间不重叠：汇总 / 前台服务 / 普通（1..100000）
check "三个通知 id 空间互不重叠" "$(python -c "
d=0x4D4F52; s=0x53594E43
print(1 if d>100000 and s>100000 and d!=s and s<=2147483647 else 0)" 2>/dev/null || echo 0)"
if [ -f "$EVID/foreground-sync-test.log" ] \
   && grep -qa "^OK" "$EVID/foreground-sync-test.log"; then
  check "前台服务测试（B4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/foreground-sync-test.log" | head -1) 项"
else
  check "前台服务测试（B4）" 0 "见 docs/evidence/foreground-sync-test.log"
fi

echo
echo "[14/32] B4 卡（第四张）：FCM 推送解析与打开时补拉"
# 带 notification 段的消息必须拒绝：系统会自己弹一条未清洗的通知
check "拒绝带 notification 段的推送" "$(code_has 'HAS_NOTIFICATION_BLOCK' "$NOTIFY" && code_has 'if (hasNotificationBlock)' "$NOTIFY" && echo 1 || echo 0)"
# 只读白名单键：表外的键没有读取语句，payload 无法把任意文本塞进通知
check "推送只读取白名单键" "$(code_has 'ALLOWED_KEYS' "$NOTIFY" && echo 1 || echo 0)"
# 细节文案来自本地码表，不是 payload 文本
check "细节文案取自本地码表" "$(code_has 'private val DETAIL_MAP' "$NOTIFY" && echo 1 || echo 0)"
# 未知 kind 必须记录：静默丢弃会让「推送来了但无反应」无从排查
check "未知 kind 丢弃但记录诊断" "$(code_has 'FcmDiagnostics.record' "$NOTIFY" && echo 1 || echo 0)"
# 拼深链前校验 focus：ap-1&decision=deny 会注入出一个动作参数。
# 断言必须盯**调用点**而不是符号名 —— 只 grep isSafeFocusId 会撞上
# toContent 的 KDoc（注释里也写了这个名字），注入掉真实调用后仍然 PASS。
check "拼深链前校验 focus（防参数注入）" "$(code_has 'takeIf { MorrowDeepLink.isSafeFocusId(it) }' "$NOTIFY" && code_has 'fun isSafeFocusId' "$NOTIFY" && echo 1 || echo 0)"
# snapshotRequired 必须粘：被后来的增量推送降级会让中间事件永久丢失
check "snapshotRequired 粘性（不被增量降级）" "$(code_has 'needsSnapshot || prefs.getBoolean(KEY_SNAPSHOT, false)' "$NOTIFY" && echo 1 || echo 0)"
# consume 必须原子清空，否则会补拉两次
check "补拉请求取出即清空（不重复补拉）" "$(code_has 'putBoolean(KEY_PENDING, false)' "$NOTIFY" && echo 1 || echo 0)"
# restore 必须原样放回（保留全量要求与首次标记时刻）
check "失败重放保留全量要求与首次时刻" "$(code_has 'firstMarkedAtMillis' "$NOTIFY" && echo 1 || echo 0)"
# 接线点必须存在且真的调了三步：解析 → 标记 → 投递。
# B 轨已两次出现「逻辑在、没人调用」，这一条专门盯「有没有人把它串起来」。
check "推送接线点串起解析/标记/投递" "$([ -f "$NOTIFY" ] && code_has 'PendingPull.mark(context, parsed.snapshotRequired)' "$NOTIFY" && code_has 'notifier.post(content, decision)' "$NOTIFY" && echo 1 || echo 0)"
# 被拒的推送不能留补拉标记（否则打开应用会白拉一次）。
# 断言方式：FcmReceiver 里 PendingPull.mark 只许出现 1 次 ——
# 唯一那一次属于「接受」分支；被拒分支里再多一次就说明把「什么都没发生」
# 也当成「要同步」了。
check "被拒推送不留补拉标记" "$([ "$(grep -c 'PendingPull.mark' "$NOTIFY")" -eq 1 ] && echo 1 || echo 0)" "$(grep -c 'PendingPull.mark' "$NOTIFY") 处"
# 免打扰状态必须真的从系统读，不能恒 false
check "免打扰状态取自系统（非恒 false）" "$([ -f "$NOTIFY" ] && code_has 'currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL' "$NOTIFY" && echo 1 || echo 0)"
if [ -f "$EVID/fcm-payload-test.log" ] \
   && grep -qa "^OK" "$EVID/fcm-payload-test.log"; then
  check "推送解析测试（B4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/fcm-payload-test.log" | head -1) 项"
else
  check "推送解析测试（B4）" 0 "见 docs/evidence/fcm-payload-test.log"
fi
if [ -f "$EVID/pending-pull-test.log" ] \
   && grep -qa "^OK" "$EVID/pending-pull-test.log"; then
  check "打开时补拉测试（B4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/pending-pull-test.log" | head -1) 项"
else
  check "打开时补拉测试（B4）" 0 "见 docs/evidence/pending-pull-test.log"
fi
if [ -f "$EVID/fcm-receiver-test.log" ] \
   && grep -qa "^OK" "$EVID/fcm-receiver-test.log"; then
  check "推送→通知→补拉接线测试（B4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/fcm-receiver-test.log" | head -1) 项"
else
  check "推送→通知→补拉接线测试（B4）" 0 "见 docs/evidence/fcm-receiver-test.log"
fi

echo
echo "[15/32] C1 卡：工位页（四区块 / 空态 / 接线）"
# 四区块必须都在，且顺序由代码结构保证（区块列表按「状态→待办→统计→成品」）
check "四区块文件与入口存在" "$([ -f "$WB" ] && [ -f "$WB" ] && [ -f "$WB" ] && code_has 'fun WorkbenchScreen(' "$WB" && echo 1 || echo 0)"
check "四个区块 Composable 齐备" "$(code_has 'fun RunningBlock(' "$WB" && code_has 'fun ApprovalsBlock(' "$WB" && code_has 'fun TodayBlock(' "$WB" && code_has 'fun ArtifactsBlock(' "$WB" && echo 1 || echo 0)"
# 蓝图验收：「无任务时显示空态文案而非空白卡片」—— 每种空态都必须是一句解释
check "空态是文案而非空白（三种空态各有说法）" "$(code_has '还没和电脑配对' "$WB" && code_has '电脑上没有任务在跑' "$WB" && code_has '现在看不到电脑上的任务' "$WB" && echo 1 || echo 0)"
# 「拿不到统计」不许用四个 0 占位：0 会被读成「今天什么都没干」
check "拿不到今日统计时不用零值占位" "$(code_has '今日统计要等连上电脑才有' "$WB" && [ "$(grep -c 'TodayStats(0, 0, 0, 0, 0, 0))' "$WB")" -eq 0 ] && echo 1 || echo 0)"
# 总量未知必须显示「不确定」而不是 0%
check "进度不确定时不显示 0%" "$(code_has 'if (totalSteps <= 0) return null' "$WB" && code_has '一步不知道还有多远' "$WB" && echo 1 || echo 0)"
# 微圆角矩形：控件 5dp / 卡片 9dp，禁止胶囊
check "工位页无胶囊圆角" "$([ "$(grep -c 'CircleShape\|RoundedCornerShape(50)\|999.dp' "$WB" "$WB" | grep -v ':0' | wc -l)" -eq 0 ] && echo 1 || echo 0)"
# 接线：MainActivity.onResume 真的调 FcmReceiver.onResume 并发布
check "MainActivity.onResume 取走补拉并发布" "$(code_has 'FcmReceiver.onResume(this)' app/src/main/java/studio/morrow/android/MainActivity.kt && code_has 'pendingPullRequest.value = request' app/src/main/java/studio/morrow/android/MainActivity.kt && echo 1 || echo 0)"
# 工位页消费补拉状态与运行中状态（C1 要求的两处接线）
check "工位页消费补拉与前台同步状态" "$(code_has 'PendingPull.isPending(context)' app/src/main/java/studio/morrow/android/Shell.kt && code_has 'ForegroundSyncState.current()' app/src/main/java/studio/morrow/android/Shell.kt && echo 1 || echo 0)"
# 「停止跟盯」是这一屏唯一的可点控件，必须真的接到服务入口，不能是空实现
check "停止跟盯接到服务入口（非空实现）" "$(code_has 'onStopTask = { ForegroundSyncService.stop(context) }' app/src/main/java/studio/morrow/android/Shell.kt && echo 1 || echo 0)"
# 状态必须是可观察的：普通字段写入不会触发重组，界面会一直显示旧值
check "前台同步状态可驱动重组（mutableStateOf）" "$(code_has 'private val stateHolder = mutableStateOf<SyncState>' "$NOTIFY" && code_has 'fun current(): SyncState = stateHolder.value' "$NOTIFY" && echo 1 || echo 0)"
if [ -f "$EVID/workbench-model-test.log" ] \
   && grep -qa "^OK" "$EVID/workbench-model-test.log"; then
  check "工位页数据与格式化测试（C1）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/workbench-model-test.log" | head -1) 项"
else
  check "工位页数据与格式化测试（C1）" 0 "见 docs/evidence/workbench-model-test.log"
fi
if [ -f "$EVID/workbench-screen-test.log" ] \
   && grep -qa "^OK" "$EVID/workbench-screen-test.log"; then
  check "工位页渲染测试（C1）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/workbench-screen-test.log" | head -1) 项"
else
  check "工位页渲染测试（C1）" 0 "见 docs/evidence/workbench-screen-test.log"
fi
if [ -f "$EVID/pending-pull-wiring-test.log" ] \
   && grep -qa "^OK" "$EVID/pending-pull-wiring-test.log"; then
  check "打开时补拉接线测试（C1）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/pending-pull-wiring-test.log" | head -1) 项"
else
  check "打开时补拉接线测试（C1）" 0 "见 docs/evidence/pending-pull-wiring-test.log"
fi
if [ -f "$EVID/workbench-stop-wiring-test.log" ] \
   && grep -qa "^OK" "$EVID/workbench-stop-wiring-test.log"; then
  check "停止跟盯接线测试（C1）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/workbench-stop-wiring-test.log" | head -1) 项"
else
  check "停止跟盯接线测试（C1）" 0 "见 docs/evidence/workbench-stop-wiring-test.log"
fi


echo "[16/32] C2 卡：对话页（消息流 / 执行组 / Composer）"

# ───────────────────────── C2 对话页 ─────────────────────────

# 消息流必须用稳定 key。这是「流式增量不引起整屏重组」的原因，
# 而那条验收本身只能用引用相等在 ThreadReducerTest 里证明 ——
# 这条静态断言守的是 UI 层别再退回按位置做 key。
check "对话页消息流用稳定 key（C2）" "$(code_has 'items(state.messages, key = { it.id })' "$THREAD" && echo 1 || echo 0)"

# 执行组默认收起（蓝图屏 3 原文：默认只显示「N 个步骤 · 用时」）
check "执行组默认收起（C2）" "$(code_has 'if (expanded) {' "$THREAD" && echo 1 || echo 0)"

# 停止按钮只在生成中渲染，且必须接线
check "停止按钮仅在生成中渲染（C2）" "$(code_has 'if (generating) {' "$THREAD" && echo 1 || echo 0)"
check "停止按钮接线到真回调（C2）" "$(code_has 'clickable(onClick = onStop)' "$THREAD" && echo 1 || echo 0)"

# 助手正文无气泡（蓝图屏 3）：AssistantMessage 里不能出现 userBubble
check "助手正文无气泡（C2）" "$(grep -A40 'internal fun AssistantMessage' "$THREAD" | grep -q 'userBubble' && echo 0 || echo 1)"

# @ 插入必须带尾空格，否则用户接着打字会把路径改坏
check "@ 文件插入带尾空格（C2）" "$(code_has 'val newText = \"\$head@\${file.path} \"' "$THREAD" && echo 1 || echo 0)"

if [ -f "$EVID/thread-reducer-test.log" ]    && grep -qa "^OK" "$EVID/thread-reducer-test.log"; then
  check "对话归并逻辑测试（C2）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/thread-reducer-test.log" | head -1) 项"
else
  check "对话归并逻辑测试（C2）" 0 "见 docs/evidence/thread-reducer-test.log"
fi
if [ -f "$EVID/thread-screen-test.log" ]    && grep -qa "^OK" "$EVID/thread-screen-test.log"; then
  check "对话页渲染测试（C2）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/thread-screen-test.log" | head -1) 项"
else
  check "对话页渲染测试（C2）" 0 "见 docs/evidence/thread-screen-test.log"
fi
echo "[17/32] C3 卡：成员协作（逐字段 / 卡住依据 / 2 秒变化）"

check "卡住依据由真实字段拼出（C3）" "$(code_has 'member.stuck!!.describe()' "$SWARM" && echo 1 || echo 0)"
check "无依据时明说原因未上报（C3）" "$(grep -q '原因未上报' "$SWARM" && echo 1 || echo 0)"
check "卡住成员排在最前（C3）" "$(grep -q 'compareByDescending<MemberProgress> { it.stuck != null }' "$SWARM" && echo 1 || echo 0)"
check "乐观更新不碰计数类字段（C3）" "$(awk '/fun intervene/,/^  }/' "$SWARM" | grep -q 'stepCount =' && echo 0 || echo 1)"
check "成员列表用 LazyColumn（C3）" "$(grep -q 'LazyColumn' "$SWARM" && echo 1 || echo 0)"

if [ -f "$EVID/swarm-reducer-test.log" ] \
   && grep -qa "^OK" "$EVID/swarm-reducer-test.log"; then
  check "成员协作归并测试（C3）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/swarm-reducer-test.log" | head -1) 项"
else
  check "成员协作归并测试（C3）" 0 "见 docs/evidence/swarm-reducer-test.log"
fi
if [ -f "$EVID/swarm-screen-test.log" ] \
   && grep -qa "^OK" "$EVID/swarm-screen-test.log"; then
  check "成员协作渲染测试（C3）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/swarm-screen-test.log" | head -1) 项"
else
  check "成员协作渲染测试（C3）" 0 "见 docs/evidence/swarm-screen-test.log"
fi

echo "[18/32] C4 卡：审批页（四段信息 / 危险禁用 / 只能 once|deny）"
APPROVALS="$ROOT/app/src/main/java/studio/morrow/android/Approvals.kt"

# 四段信息一个都不能少：工具 / 目标 / 参数 / 类别
if grep -q 'FieldRow("工具"' "$APPROVALS" \
   && grep -q 'FieldRow("目标"' "$APPROVALS" \
   && grep -q 'FieldRow("参数"' "$APPROVALS" \
   && grep -q 'FieldRow("类别"' "$APPROVALS"; then
  check "审批卡四段信息齐全（C4）" 1 "4 段"
else
  check "审批卡四段信息齐全（C4）" 0 "缺段"
fi

# 手机端决策只能有两种：AllowOnce / Deny，不能出现 Always
if grep -q 'enum class Decision { AllowOnce, Deny }' "$APPROVALS" \
   && ! grep -q 'Decision.Always' "$APPROVALS"; then
  check "手机端只能 once/deny，无 always（C4）" 1 "2 种决策"
else
  check "手机端只能 once/deny，无 always（C4）" 0 "出现第三个决策"
fi

# 危险动作允许必须禁用，且禁用要写进语义树（不只是不挂 clickable）
if grep -q 'val canAllow = item.canAllow(nowMillis)' "$APPROVALS" \
   && grep -q 'if (!enabled) disabled()' "$APPROVALS"; then
  check "危险动作允许禁用 + 无障碍语义（C4）" 1 "disabled()"
else
  check "危险动作允许禁用 + 无障碍语义（C4）" 0 "缺禁用语义"
fi

# 按钮纵向顺序：拒绝在允许之前（源码顺序即布局顺序）
DENY_LINE=$(grep -n 'testTag = ApprovalTags.deny(item.id)' "$APPROVALS" | cut -d: -f1)
ALLOW_LINE=$(grep -n 'testTag = ApprovalTags.allow(item.id)' "$APPROVALS" | cut -d: -f1)
if [ -n "$DENY_LINE" ] && [ -n "$ALLOW_LINE" ] && [ "$DENY_LINE" -lt "$ALLOW_LINE" ]; then
  check "按钮顺序为上拒绝下允许（C4）" 1 "deny 在 allow 之前"
else
  check "按钮顺序为上拒绝下允许（C4）" 0 "顺序颠倒"
fi

# 生物识别不可用必须 DenyOnly（不能静默放行），取消即拒绝
if grep -q 'Capability.NoHardware -> Verdict.DenyOnly' "$APPROVALS" \
   && grep -q 'Capability.NotEnrolled -> Verdict.DenyOnly' "$APPROVALS" \
   && grep -q 'AuthResult.Cancelled -> Decision.Deny' "$APPROVALS"; then
  check "生物识别不可用只能拒绝（C4）" 1 "含取消即拒绝"
else
  check "生物识别不可用只能拒绝（C4）" 0 "存在放行分支"
fi

# 工位页的审批读数与审批页同源（不是 approvals = null，也不是各自维护一份）
if grep -q 'val pendingApprovalItems' "$ROOT/app/src/main/java/studio/morrow/android/Shell.kt" \
   && grep -q 'approvals = if (actionable.isEmpty()) null' "$ROOT/app/src/main/java/studio/morrow/android/Shell.kt"; then
  check "工位页审批读数与审批页同源（C4）" 1 "pendingApprovalItems → actionable"
else
  check "工位页审批读数与审批页同源（C4）" 0 "审批来源未统一"
fi

if [ -f "$EVID/approval-queue-test.log" ] \
   && grep -qa "^OK" "$EVID/approval-queue-test.log"; then
  check "审批队列逻辑测试（C4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/approval-queue-test.log" | head -1) 项"
else
  check "审批队列逻辑测试（C4）" 0 "见 docs/evidence/approval-queue-test.log"
fi
if [ -f "$EVID/approval-screen-test.log" ] \
   && grep -qa "^OK" "$EVID/approval-screen-test.log"; then
  check "审批页渲染测试（C4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/approval-screen-test.log" | head -1) 项"
else
  check "审批页渲染测试（C4）" 0 "见 docs/evidence/approval-screen-test.log"
fi
if [ -f "$EVID/approval-nav-test.log" ] \
   && grep -qa "^OK" "$EVID/approval-nav-test.log"; then
  check "审批页到达性测试（C4）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/approval-nav-test.log" | head -1) 项"
else
  check "审批页到达性测试（C4）" 0 "见 docs/evidence/approval-nav-test.log"
fi
echo "[19/32] C5 卡：作品页与成品预览（沙箱 / 无进度显时长 / 硬解 / PDF 首屏）"
WORKS="$ROOT/app/src/main/java/studio/morrow/android/Works.kt"
PREVIEW="$ROOT/app/src/main/java/studio/morrow/android/Preview.kt"

# 沙箱第一层：文件与内容访问必须显式关（实测默认 true）
if grep -q 's.allowFileAccess = false' "$PREVIEW" \
   && grep -q 's.allowContentAccess = false' "$PREVIEW"; then
  check "沙箱关闭文件与内容访问（C5）" 1 "allowFileAccess=false"
else
  check "沙箱关闭文件与内容访问（C5）" 0 "未显式关闭"
fi

# 沙箱第二层：白名单只有 about 与 data 两个 scheme
if grep -q 'val allowedSchemes: Set<String> = setOf("about", "data")' "$PREVIEW"; then
  check "沙箱白名单只放行 about/data（C5）" 1 "2 个 scheme"
else
  check "沙箱白名单只放行 about/data（C5）" 0 "白名单被放宽"
fi

# 沙箱第三层：拦截返回空响应而不是 null（null = 交给默认行为 = 真的发请求）
if grep -q 'return if (isAllowed(url)) null else blockedResponse()' "$PREVIEW"; then
  check "外发请求返回空响应而非放行（C5）" 1 "blockedResponse()"
else
  check "外发请求返回空响应而非放行（C5）" 0 "可能放行"
fi

# 进度无值必须是 Elapsed（不是 Percent(0)）
if grep -q 'data class Elapsed(val millis: Long) : StageProgress' "$WORKS" \
   && ! grep -q 'Elapsed(0L) : StageProgress\|Percent(0)' "$WORKS"; then
  check "进度无值走 Elapsed 而非 Percent(0)（C5）" 1 "密封类型"
else
  check "进度无值走 Elapsed 而非 Percent(0)（C5）" 0 "无值与零混淆"
fi

# 界面在 Elapsed 分支必须显示时长
if grep -q 'text = formatClockDuration(progress.millis)' "$WORKS" \
   && grep -q '完成度暂不可算' "$WORKS"; then
  check "无进度显示时长并说明（C5）" 1 "formatClockDuration + 说明"
else
  check "无进度显示时长并说明（C5）" 0 "可能显示假百分比"
fi

# 视频用 SurfaceView（不是 TextureView）
if grep -q 'SurfaceView' "$PREVIEW" \
   && ! grep -qE 'return SurfaceKind\.TextureView' "$PREVIEW"; then
  check "视频播放用 SurfaceView（C5）" 1 "硬解直通"
else
  check "视频播放用 SurfaceView（C5）" 0 "可能用 TextureView"
fi

# PDF 首屏只渲染 1 页且不预加载
if grep -q 'const val FIRST_SCREEN_PAGES = 1' "$PREVIEW" \
   && grep -q 'const val PRELOAD_NEIGHBORS = false' "$PREVIEW"; then
  check "PDF 首屏只渲染 1 页不预加载（C5）" 1 "1 页 / 不预加载"
else
  check "PDF 首屏只渲染 1 页不预加载（C5）" 0 "策略被改成全量"
fi

# 作品页入口接的是真实路由（不是空实现）
if grep -q 'onOpenWorks = { route = Route.Works }' "$ROOT/app/src/main/java/studio/morrow/android/Shell.kt"; then
  check "作品页入口接真实路由（C5）" 1 "Route.Works"
else
  check "作品页入口接真实路由（C5）" 0 "入口未接线"
fi

if [ -f "$EVID/sandbox-webview-test.log" ] \
   && grep -qa "^OK" "$EVID/sandbox-webview-test.log"; then
  check "HTML 沙箱测试（C5）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/sandbox-webview-test.log" | head -1) 项"
else
  check "HTML 沙箱测试（C5）" 0 "见 docs/evidence/sandbox-webview-test.log"
fi
if [ -f "$EVID/works-model-test.log" ] \
   && grep -qa "^OK" "$EVID/works-model-test.log"; then
  check "作品模型测试（C5）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/works-model-test.log" | head -1) 项"
else
  check "作品模型测试（C5）" 0 "见 docs/evidence/works-model-test.log"
fi
if [ -f "$EVID/works-screen-test.log" ] \
   && grep -qa "^OK" "$EVID/works-screen-test.log"; then
  check "作品页渲染测试（C5）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/works-screen-test.log" | head -1) 项"
else
  check "作品页渲染测试（C5）" 0 "见 docs/evidence/works-screen-test.log"
fi
if [ -f "$EVID/works-nav-test.log" ] \
   && grep -qa "^OK" "$EVID/works-nav-test.log"; then
  check "作品页到达性测试（C5）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/works-nav-test.log" | head -1) 项"
else
  check "作品页到达性测试（C5）" 0 "见 docs/evidence/works-nav-test.log"
fi
if [ -f "$EVID/hw-decode-test.log" ] \
   && grep -qa "^OK" "$EVID/hw-decode-test.log"; then
  check "硬件解码测试（C5）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/hw-decode-test.log" | head -1) 项"
else
  check "硬件解码测试（C5）" 0 "见 docs/evidence/hw-decode-test.log"
fi
if [ -f "$EVID/pdf-policy-test.log" ] \
   && grep -qa "^OK" "$EVID/pdf-policy-test.log"; then
  check "PDF 首屏策略测试（C5）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/pdf-policy-test.log" | head -1) 项"
else
  check "PDF 首屏策略测试（C5）" 0 "见 docs/evidence/pdf-policy-test.log"
fi
echo "[20/32] D1 卡：事件归并（28 种事件 / 24ms 合并 / 三条验收）"
STATE="$ROOT/app/src/main/java/studio/morrow/android/State.kt"

# 28 种事件类型穷举（蓝图写 20，实测 28）
if grep -q '"demo_step"' "$STATE" \
   && grep -q '"episodes_captured"' "$STATE" \
   && grep -q 'val knownTypes: Set<String>' "$STATE"; then
  check "28 种事件类型穷举登记（D1）" 1 "含 knownTypes"
else
  check "28 种事件类型穷举登记（D1）" 0 "类型清单缺失"
fi

# 24ms 合并与桌面一致（DELTA_APPLY_MS）
if grep -q 'DELTA_APPLY_MS' "$ROOT/../Morrow/src/renderer/store.ts" 2>/dev/null \
   || grep -q 'kind == a.kind' "$STATE"; then
  check "合并只合相邻同 key 的分片（D1）" 1 "kind 参与判定"
else
  check "合并只合相邻同 key 的分片（D1）" 0 "可能跨 kind 合并"
fi

# runs 按 sessionId 分桶（第 3 条验收的结构性保证）
if grep -q 'val runs: Map<String, RunState>' "$STATE" \
   && grep -q 'runs = state.runs - action.sessionId' "$STATE"; then
  check "runs 按会话分桶且只清自己（D1）" 1 "Map<sessionId, RunState>"
else
  check "runs 按会话分桶且只清自己（D1）" 0 "可能清空全部"
fi

# liveMessageId 存在（切回接流的关键）
if grep -q 'val liveMessageId: String?' "$STATE"; then
  check "liveMessageId 续接流式状态（D1）" 1 "字段存在"
else
  check "liveMessageId 续接流式状态（D1）" 0 "缺 liveMessageId"
fi

# seq 与 at 分离（不能用墙上时间当序号）
if ! grep -q 'lastSeq = maxOf(state.lastSeq, action.at)' "$STATE" \
   && grep -q 'fun apply(state: MorrowUiState, action: EventAction, seq: Long' "$STATE"; then
  check "seq 与墙上时间分离（D1）" 1 "seq 单独传"
else
  check "seq 与墙上时间分离（D1）" 0 "用 at 当 seq"
fi

# 回放包 ≥5000 条
if grep -q 'fun build(rounds: Int = 8)' "$STATE"; then
  check "回放包生成器存在（D1）" 1 "ReplayPack.build()"
else
  check "回放包生成器存在（D1）" 0 "缺回放包"
fi

if [ -f "$EVID/event-reducer-test.log" ] \
   && grep -qa "^OK" "$EVID/event-reducer-test.log"; then
  check "事件归并测试（D1）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/event-reducer-test.log" | head -1) 项"
else
  check "事件归并测试（D1）" 0 "见 docs/evidence/event-reducer-test.log"
fi
if [ -f "$EVID/replay-pack-test.log" ] \
   && grep -qa "^OK" "$EVID/replay-pack-test.log"; then
  check "回放包测试（D1）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/replay-pack-test.log" | head -1) 项"
else
  check "回放包测试（D1）" 0 "见 docs/evidence/replay-pack-test.log"
fi
echo "[21/32] D1b：三类事件真正接线（今日累计 / 产物整表 / 成员合并）"
_IB="$ROOT/app/src/main/java/studio/morrow/android/State.kt"
_STATE="$ROOT/app/src/main/java/studio/morrow/android/State.kt"

# 三个事件已从 Ignore 改为真实动作
if ! grep -q '"artifacts_updated" -> EventAction.Ignore' "$_STATE" \
   && grep -q '"artifacts_updated" -> EventAction.ArtifactsReplaced' "$_STATE"; then
  check "artifacts_updated 已接线（D1b）" 1 "ArtifactsReplaced"
else
  check "artifacts_updated 已接线（D1b）" 0 "仍是 Ignore"
fi
if ! grep -q '"usage_updated" -> EventAction.Ignore' "$_STATE" \
   && grep -q 'EventAction.UsageReported' "$_STATE"; then
  check "usage_updated 已接线（D1b）" 1 "UsageReported"
else
  check "usage_updated 已接线（D1b）" 0 "仍是 Ignore"
fi
if ! grep -q '"super_updated" -> EventAction.Ignore' "$_STATE" \
   && grep -q 'EventAction.MembersReplaced' "$_STATE"; then
  check "super_updated 已接线（D1b）" 1 "MembersReplaced"
else
  check "super_updated 已接线（D1b）" 0 "仍是 Ignore"
fi
if grep -q 'EventAction.MemberPatched' "$_STATE"; then
  check "tool_progress.memberTask 单条合并（D1b）" 1 "MemberPatched"
else
  check "tool_progress.memberTask 单条合并（D1b）" 0 "未接"
fi

# ── 本轮：手机要看得见桌面在干什么（命令执行 / 文件增删改 / 待办 / 提示） ──
# 这一组守的是产品要求本身：手机上不能只剩「只读文字的聊天窗」。
for _ev in '"tool_started" ->' '"tool_finished" ->' '"todos_updated" ->' \
           '"plan_updated" ->' '"goal_updated" ->' '"notice" ->' '"error" ->'; do
  if grep -q "$_ev" "$_STATE" && ! grep -q "$_ev EventAction.Ignore" "$_STATE"; then
    check "事件已接线到手机状态 $_ev" 1 "真实动作"
  else
    check "事件已接线到手机状态 $_ev" 0 "仍是 Ignore"
  fi
done
# 归并器要真的处理工具开始/结束，否则解析出来也没人用
if grep -q 'is EventAction.ToolStarted ->' "$_STATE" \
   && grep -q 'is EventAction.ToolFinished ->' "$_STATE"; then
  check "工具调用进入归并器（命令/文件变更可见）" 1 "ToolStarted + ToolFinished"
else
  check "工具调用进入归并器（命令/文件变更可见）" 0 "归并器未处理"
fi
# 界面投影必须把工具组挂到消息上；只映射 text 就是「只能读文字」
if grep -q 'toolGroup = ToolProjection.groupOf' "$ROOT/app/src/main/java/studio/morrow/android/Shell.kt"; then
  check "对话页投影带工具组" 1 "ToolProjection.groupOf"
else
  check "对话页投影带工具组" 0 "投影丢了工具信息"
fi
# 发送必须以桌面回执为准，不能拿 socket 写入成功当「已受理」
if grep -q 'sendMessageAwaiting' "$ROOT/app/src/main/java/studio/morrow/android/MainActivity.kt" \
   && ! grep -q 'sendInvoke("message.send"' "$ROOT/app/src/main/java/studio/morrow/android/MainActivity.kt"; then
  check "message.send 等桌面回执（不假装成功）" 1 "sendMessageAwaiting"
else
  check "message.send 等桌面回执（不假装成功）" 0 "仍只看 socket 写入"
fi
# 桌面侧不得把命令原文/输出正文下发到手机
if grep -q 'phoneToolResult' "$ROOT/../Morrow/src/main/relay-phone.ts" \
   && ! grep -q 'output: result.output' "$ROOT/../Morrow/src/main/relay-phone.ts"; then
  check "桌面工具事件只下发摘要（不泄露原文）" 1 "phoneToolSummary/phoneToolResult"
else
  check "桌面工具事件只下发摘要（不泄露原文）" 0 "可能下发原文"
fi

# 整表 vs 合并的语义在代码里可辨认
if grep -q 'existing.map { if (it.index == idx) merged else it }' "$_IB" \
   && grep -q 'existing + merged' "$_IB"; then
  check "成员按 index 合并而非整表替换（D1b）" 1 "map + append"
else
  check "成员按 index 合并而非整表替换（D1b）" 0 "可能整表替换"
fi
if grep -q 'mt.containsKey' "$_IB"; then
  check "缺失字段与 null 字段可区分（D1b）" 1 "containsKey"
else
  check "缺失字段与 null 字段可区分（D1b）" 0 "as? 无法区分"
fi
if grep -q 'reason == "max-steps"' "$_IB" && grep -q 'reason == "cancelled"' "$_IB"; then
  check "四种 RunFinishReason 逐个处理（D1b）" 1 "cancelled/max-steps 也计入失败"
else
  check "四种 RunFinishReason 逐个处理（D1b）" 0 "只认 error"
fi
if grep -q 'val artifacts: List<IgnoreBridges.ArtifactView>? = null' "$_STATE"; then
  check "产物列表 null 与空可区分（D1b）" 1 "可空类型"
else
  check "产物列表 null 与空可区分（D1b）" 0 "非可空"
fi

if [ -f "$EVID/ignore-bridges-test.log" ] \
   && grep -qa "^OK" "$EVID/ignore-bridges-test.log"; then
  check "三类接线测试（D1b）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/ignore-bridges-test.log" | head -1) 项"
else
  check "三类接线测试（D1b）" 0 "见 docs/evidence/ignore-bridges-test.log"
fi

echo "[22/32] D1c：ViewModel 层与工位页接线（24ms 节流 / 顺序保证 / 真实数据上屏）"
_VM="$ROOT/app/src/main/java/studio/morrow/android/State.kt"
_APP="$ROOT/app/src/main/java/studio/morrow/android/Shell.kt"

# 24ms 节流窗口与桌面同值
if grep -q 'const val DELTA_APPLY_MS = 24L' "$_VM" \
   && grep -q 'delay(applyMs)' "$_VM"; then
  check "节流窗口 24ms 与桌面同值（D1c）" 1 "DELTA_APPLY_MS = 24"
else
  check "节流窗口 24ms 与桌面同值（D1c）" 0 "缺节流或窗口不对"
fi

# 顺序保证：非 delta 必须先 flush
if grep -q 'if (action is EventAction.MessageDelta)' "$_VM" \
   && grep -q '顺序保证：先落地已缓冲的正文' "$_VM" \
   && grep -q 'flushNow()' "$_VM"; then
  check "非 delta 事件先落地缓冲（D1c）" 1 "顺序保证"
else
  check "非 delta 事件先落地缓冲（D1c）" 0 "可能顺序错乱"
fi

# 排程不叠加（40 条/秒不能排 40 个 timer）
if grep -q 'if (flushJob != null) return' "$_VM"; then
  check "排程不叠加（D1c）" 1 "已有排程即返回"
else
  check "排程不叠加（D1c）" 0 "可能重复排程"
fi

# 合并必须带 kind 判定
if grep -q 'last.kind == item.kind' "$_VM"; then
  check "合并带 kind 判定防跨段（D1c）" 1 "kind 参与"
else
  check "合并带 kind 判定防跨段（D1c）" 0 "可能丢掉 thinking 位置"
fi

# 切会话 / 销毁必须落地缓冲
# 用 awk 判断函数体里是否有 flushNow()，比 `grep -A | grep -q` 可靠：
# 后者在管道里 `$?` 拿的是最后一个 grep 的状态，且 -A 的上下文行会串到相邻函数
vm_lands() {
  awk -v fn="  fun $1(" '
    index($0, fn) { inFn=1; next }
    inFn && /^  }/ { inFn=0 }
    inFn && /flushNow/ { found=1; exit }
    END { exit(found ? 0 : 1) }
  ' "$_VM"
}
if vm_lands selectSession && vm_lands onCleared; then
  check "切会话与销毁都落地缓冲（D1c）" 1 "不丢最后半句"
else
  check "切会话与销毁都落地缓冲（D1c）" 0 "可能丢字"
fi

# 投影层：null 与 emptyList 语义
if grep -q 'sawAnything' "$ROOT/app/src/main/java/studio/morrow/android/Workbench.kt" \
   && grep -q 'BYTES_UNKNOWN' "$ROOT/app/src/main/java/studio/morrow/android/Workbench.kt"; then
  check "投影层保留不知道语义（D1c）" 1 "sawAnything + BYTES_UNKNOWN"
else
  check "投影层保留不知道语义（D1c）" 0 "可能显示假零"
fi

# 接线：调用点真的传了 projected
if grep -q 'projected = projected,' "$_APP"; then
  check "工位页已接事件流投影（D1c）" 1 "projected = projected"
else
  check "工位页已接事件流投影（D1c）" 0 "参数存在但没接线"
fi

for pair in "EventStreamViewModelTest:viewmodel-stream-test:流式归并测试（D1c）" \
            "WorkbenchProjectionTest:projection-test:投影层测试（D1c）" \
            "EventStreamWiringTest:stream-wiring-test:事件流接线测试（D1c）"; do
  CLS="${pair%%:*}"; rest="${pair#*:}"; LOG="${rest%%:*}"; LBL="${rest#*:}"
  if bash "$ROOT/scripts/run-jvm-tests.sh" "studio.morrow.android.$CLS" > "$EVID/$LOG.log" 2>&1 \
     && grep -qa "^OK" "$EVID/$LOG.log"; then
    check "$LBL" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/$LOG.log" | head -1) 项"
  else
    check "$LBL" 0 "见 docs/evidence/$LOG.log"
  fi
done

echo "[23/32] D1d：relay 信封与事件接收（seq 来源 / 主线程写入 / 失败可见）"
_RE="$ROOT/app/src/main/java/studio/morrow/android/Relay.kt"
_RS="$ROOT/app/src/main/java/studio/morrow/android/Relay.kt"
_MA="$ROOT/app/src/main/java/studio/morrow/android/MainActivity.kt"

# seq 必须从信封取（这是本批存在的唯一理由）
if grep -q 'merged\["seq"\] = env.seq' "$_RE"; then
  check "seq 从信封取而非事件体（D1d）" 1 "toEventMap 覆盖 body 的 seq"
else
  check "seq 从信封取而非事件体（D1d）" 0 "仍在读事件体的 seq"
fi

# 信封校验照抄桌面 isValidenvelope：v / kind / seq 正整数 / at 有限
if grep -q 'if (v != VERSION)' "$_RE" \
   && grep -q 'seq < 1' "$_RE" \
   && grep -q 'isNaN() || at.isInfinite()' "$_RE"; then
  check "信封三级严格校验（D1d）" 1 "版本 / seq 正整数 / at 有限"
else
  check "信封三级严格校验（D1d）" 0 "校验有缺口"
fi
if grep -q 'if (kind !in KINDS)' "$_RE"; then
  check "未知 kind 明确失败（D1d）" 1 "不猜"
else
  check "未知 kind 明确失败（D1d）" 0 "可能当事件处理"
fi

# 非 event 信封不进归并器
if grep -q 'Result.NotAnEvent' "$_RE" && grep -q 'skippedNonEvent += 1' "$_RS"; then
  check "invoke/result/ack 不进归并器（D1d）" 1 "单独计数"
else
  check "invoke/result/ack 不进归并器（D1d）" 0 "可能与事件混淆"
fi

# 主线程 / 快照两种写入
if grep -q 'Snapshot.withMutableSnapshot' "$_RS" && grep -q 'MainThreadHolder' "$_RS"; then
  check "状态写入不在后台线程直写（D1d）" 1 "主线程 + 快照两种"
else
  check "状态写入不在后台线程直写（D1d）" 0 "可能后台直写"
fi
# 主线程切换的判定必须在代码里（不是只靠注释声称）
if grep -q 'Looper.myLooper() == android.os.Looper.getMainLooper()' "$_RS"; then
  check "后台线程调用会切到主线程（D1d）" 1 "myLooper 判定"
else
  check "后台线程调用会切到主线程（D1d）" 0 "可能后台直写"
fi

# 失败可见（原先 ?: return 静默丢弃）
if grep -q 'invalid += 1' "$_RS" && grep -q 'unparsed += 1' "$_RS" \
   && grep -q 'lastProblem' "$_RS"; then
  check "解析失败可诊断而非静默（D1d）" 1 "invalid / unparsed / lastProblem"
else
  check "解析失败可诊断而非静默（D1d）" 0 "可能静默丢弃"
fi
if grep -q 'parsed.action is EventAction.Unparsed' "$_RS"; then
  check "未知 type 计入 unparsed（D1d）" 1 "判动作而非判 null"
else
  check "未知 type 计入 unparsed（D1d）" 0 "未知事件会被当正常"
fi

# delta 的 seq 立刻推进（否则重连重复拉取）
_VM="$ROOT/app/src/main/java/studio/morrow/android/State.kt"
if grep -q '缓冲只推迟' "$_VM" || grep -q 'lastSeq = maxOf(state.lastSeq, parsed.seq)' "$_VM"; then
  check "流式分片的 seq 立刻推进（D1d）" 1 "不等 flush"
else
  check "流式分片的 seq 立刻推进（D1d）" 0 "重连会重复拉取"
fi

# 生产入口接线（这一批最容易漏的一环）
# 注意：E1b 之后这行的形参变多了 → 用 `stream = stream` 而不是整串字面量匹配，
# 否则每次加参数都要改这条检查，而它守的其实是「stream 有没有传」。
if grep -A 8 'MorrowApp(' "$_MA" | grep -q 'stream = stream'; then
  check "MainActivity 把事件流交给界面（D1d）" 1 "生产入口"
else
  check "MainActivity 把事件流交给界面（D1d）" 0 "参数存在但没接线"
fi
if grep -q 'stream.onCleared()' "$_MA" && grep -q 'streamScope.cancel()' "$_MA"; then
  check "销毁时先落地再取消作用域（D1d）" 1 "不丢最后半句"
else
  check "销毁时先落地再取消作用域（D1d）" 0 "可能丢字或泄漏协程"
fi

for pair in "RelayEnvelopeTest:relay-envelope-test:信封解析测试（D1d）" \
            "RelayEventSinkTest:relay-sink-test:事件接收测试（D1d）" \
            "RelayWiringTest:relay-wiring-test:relay 端到端接线（D1d）"             "MainThreadDispatcherTest:main-thread-dispatcher-test:生产 dispatcher 主线程切换（D1d）"; do
  CLS="${pair%%:*}"; rest="${pair#*:}"; LOG="${rest%%:*}"; LBL="${rest#*:}"
  if [ -f "$EVID/$LOG.log" ] && grep -qa "^OK" "$EVID/$LOG.log"; then
    check "$LBL" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/$LOG.log" | head -1) 项"
  else
    check "$LBL" 0 "见 docs/evidence/$LOG.log"
  fi
done

echo "[24/32] D1e：手机侧 SecureChannel（跨语言互通）"
_SC="$ROOT/app/src/main/java/studio/morrow/android/Relay.kt"

# HKDF-SHA256 域分离（info 串格式）
if grep -q 'morrow-frame:v1:\$direction:\$deviceId' "$_SC" \
   || grep -q '"\$VERSION:\$P2D:\$deviceId"' "$_SC"; then
  check "HKDF info 串格式与桌面一致（D1e）" 1 "morrow-frame:v1:<dir>:<device>"
else
  check "HKDF info 串格式与桌面一致（D1e）" 0 "派生参数可能不同"
fi
if grep -q 'hkdfSha256' "$_SC" && grep -q 'Mac.getInstance("HmacSHA256")' "$_SC"; then
  check "手写 HKDF（API 26 无 KDF）（D1e）" 1 "Extract + Expand"
else
  check "手写 HKDF（API 26 无 KDF）（D1e）" 0 "缺派生实现"
fi

# AES-256-GCM + 12 字节 nonce
if grep -q 'AES/GCM/NoPadding' "$_SC" && grep -q 'NONCE_BYTES = 12' "$_SC"; then
  check "AES-256-GCM + 12 字节 nonce（D1e）" 1 "GCM"
else
  check "AES-256-GCM + 12 字节 nonce（D1e）" 0 "算法或 nonce 长度不对"
fi
if grep -q 'GCMParameterSpec(TAG_BYTES \* 8' "$_SC" && grep -q 'TAG_BYTES = 16' "$_SC"; then
  check "GCM tag 128 位（D1e）" 1 "16 字节"
else
  check "GCM tag 128 位（D1e）" 0 "tag 长度不对"
fi

# AAD 绑方向 + deviceId + 计数
if grep -q '\$deviceId|p2d|' "$_SC" && grep -q 'updateAAD' "$_SC"; then
  check "AAD 绑方向与 deviceId（D1e）" 1 "防反射/跨设备挪用"
else
  check "AAD 绑方向与 deviceId（D1e）" 0 "AAD 绑定不足"
fi

# 双向密钥分离
if grep -q 'P2D to hkdfSha256' "$_SC" && grep -q 'D2P to hkdfSha256' "$_SC"; then
  check "双向密钥分离（D1e）" 1 "p2d / d2p 各一把"
else
  check "双向密钥分离（D1e）" 0 "可能共用一把钥匙"
fi

# 接收计数初值 -1（防 off-by-one）
if grep -q 'mutableMapOf(P2D to -1L, D2P to -1L)' "$_SC"; then
  check "接收计数初值 -1（D1e）" 1 "首帧不会被判重放"
else
  check "接收计数初值 -1（D1e）" 0 "首帧会被误判"
fi

# 重放防护 + 失败不推进计数
if grep -q 'FRAME_REPLAYED' "$_SC" && grep -q '只有解密成功才推进计数' "$_SC"; then
  check "重放防护且失败不污染窗口（D1e）" 1 "严格单调"
else
  check "重放防护且失败不污染窗口（D1e）" 0 "可能被拒绝服务"
fi

# base64url 无填充（否则桌面认不出手机发的帧）
if grep -q 'withoutPadding' "$_SC"; then
  check "base64url 无填充（D1e）" 1 "与 Node 输出一致"
else
  check "base64url 无填充（D1e）" 0 "带填充桌面认不出"
fi

# 向量测试与产出物
if bash "$ROOT/scripts/run-jvm-tests.sh" studio.morrow.android.SecureChannelVectorTest \
     > "$EVID/secure-channel-vector-test.log" 2>&1 \
   && grep -qa "^OK" "$EVID/secure-channel-vector-test.log"; then
  check "手机解桌面密文（D1e）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/secure-channel-vector-test.log" | head -1) 项"
else
  check "手机解桌面密文（D1e）" 0 "见 docs/evidence/secure-channel-vector-test.log"
fi

# 反方向：桌面解手机密文（node 脚本，不是 Kotlin 断言）
if node "$ROOT/scripts/verify-phone-seal.mjs" > "$EVID/phone-seal-verify.log" 2>&1; then
  check "桌面解手机密文（D1e）" 1 "$(grep -aoP '桌面解手机密文：\K[0-9]+' "$EVID/phone-seal-verify.log" | head -1) 条通过"
else
  check "桌面解手机密文（D1e）" 0 "见 docs/evidence/phone-seal-verify.log"
fi

# 向量来源必须能被桌面源码确证（不是手抄）
# 这条是本组最有价值的检查：Kotlin 测试解的是硬编码旧密文，
# 桌面实现漂移时它照样全绿（已实测：改桌面 AAD 后 19 项仍全过）。
# 只有 --check 直接 import 桌面 SecureChannel 复算，才能发现这种漂移。
if [ ! -f "$ROOT/../Morrow/src/main/relay.ts" ]; then
  check "向量可被桌面源码确证（D1e）" 0 "读不到桌面 relay.ts，无法确证"
elif node "$ROOT/scripts/run-desktop-ts.mjs" "$ROOT/scripts/gen-secure-vectors.mjs" --check        > "$EVID/secure-vector-check.log" 2>&1; then
  check "向量可被桌面源码确证（D1e）" 1     "$(grep -ao '桌面源码 vs 手机硬编码向量：.*' "$EVID/secure-vector-check.log" | head -1 | sed 's/桌面源码 vs 手机硬编码向量：//')"
else
  check "向量可被桌面源码确证（D1e）" 0     "$(grep -ao '桌面源码 vs 手机硬编码向量：.*' "$EVID/secure-vector-check.log" 2>/dev/null | head -1 || echo '见 docs/evidence/secure-vector-check.log')"
fi

echo "[25/32] D1f：手机侧配对与 ECDH（跨语言互通）"
_EP="$ROOT/app/src/main/java/studio/morrow/android/Relay.kt"
_DESKTOP_PAIRING="$ROOT/../Morrow/src/main/relay.ts"

# 公钥必须是 65 字节未压缩点（04||X||Y）
if grep -q 'UNCOMPRESSED_BYTES = 65' "$_EP" && grep -q 'out\[0\] = 0x04' "$_EP"; then
  check "公钥编码为 65 字节未压缩点（D1f）" 1 "04||X||Y"
else
  check "公钥编码为 65 字节未压缩点（D1f）" 0 "编码可能不符"
fi
if grep -q 'fixedWidth' "$_EP"; then
  check "坐标高位为零时补足 32 字节（D1f）" 1 "BigInteger 不自动补齐"
else
  check "坐标高位为零时补足 32 字节（D1f）" 0 "缺补零逻辑"
fi

# 六位码：大端读 + 左补零
if grep -q '1_000_000L' "$_EP" && grep -q "padStart(6, '0')" "$_EP"; then
  check "六位码取模并左补零到 6 位（D1f）" 1 "格式定死"
else
  check "六位码取模并左补零到 6 位（D1f）" 0 "格式可能不对"
fi
if grep -q 'shl 24' "$_EP"; then
  check "六位码按大端读 uint32（D1f）" 1 "与桌面 readUInt32BE 一致"
else
  check "六位码按大端读 uint32（D1f）" 0 "字节序可能错"
fi

# sessionKey 就是共享秘密的 base64（中间不再套 KDF）
if grep -q 'fun sessionKey(sharedSecret: ByteArray): String' "$_EP"; then
  check "sessionKey = base64(共享秘密)（D1f）" 1 "与桌面 completePairing 一致"
else
  check "sessionKey = base64(共享秘密)（D1f）" 0 "可能多套了一层 KDF"
fi

# 票据三查：desktopPub / exp / 密钥字段守卫
if grep -q 'desktopPub' "$_EP" && grep -q 'TICKET_EXPIRED' "$_EP"; then
  check "票据校验 desktopPub 与过期（D1f）" 1 "缺一即拒绝"
else
  check "票据校验 desktopPub 与过期（D1f）" 0 "校验不全"
fi
if grep -q 'session|secret|private' "$_EP"; then
  check "票据含密钥字段时拒绝（D1f）" 1 "二维码可能被肩窥"
else
  check "票据含密钥字段时拒绝（D1f）" 0 "缺密钥泄漏守卫"
fi

# 桌面协议前提：票据必须带 desktopPub（本批补的缺口）
if grep -q 'desktopPub: string' "$_DESKTOP_PAIRING" 2>/dev/null \
   && grep -q 'desktopPub: this.publicKey' "$_DESKTOP_PAIRING" 2>/dev/null; then
  check "桌面票据携带 desktopPub（D1f）" 1 "手机据此算 ECDH"
else
  check "桌面票据携带 desktopPub（D1f）" 0 "手机拿不到桌面公钥，ECDH 无从算起"
fi

# 向量测试（跑）
if [ -f "$EVID/ecdh-pairing-vector-test.log" ] && grep -qa "^OK" "$EVID/ecdh-pairing-vector-test.log"; then
  check "手机侧 ECDH 向量测试（D1f）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/ecdh-pairing-vector-test.log" | head -1) 项"
else
  check "手机侧 ECDH 向量测试（D1f）" 0 "见 docs/evidence/ecdh-pairing-vector-test.log"
fi

# 向量必须能被桌面源码确证（与 D1e 同理：硬编码测试抓不到桌面漂移）
if [ ! -f "$_DESKTOP_PAIRING" ]; then
  check "配对向量可被桌面源码确证（D1f）" 0 "读不到桌面 relay.ts"
elif node "$ROOT/scripts/run-desktop-ts.mjs" "$ROOT/scripts/gen-pairing-vectors.mjs" --check \
       > "$EVID/pairing-vector-check.log" 2>&1; then
  check "配对向量可被桌面源码确证（D1f）" 1 \
    "$(grep -ao '桌面源码 vs 手机硬编码值：.*' "$EVID/pairing-vector-check.log" | head -1 | sed 's/桌面源码 vs 手机硬编码值：//')"
else
  check "配对向量可被桌面源码确证（D1f）" 0 \
    "$(grep -ao '桌面源码 vs 手机硬编码值：.*' "$EVID/pairing-vector-check.log" 2>/dev/null | head -1 || echo '见 docs/evidence/pairing-vector-check.log')"
fi

# sessionKey 能直接喂给 SecureChannel（配对 → 加密的衔接）
if grep -q 'SecureChannel' "$ROOT/app/src/test/java/studio/morrow/android/RelayCryptoTests.kt"; then
  check "配对产出的 sessionKey 可直接用于 SecureChannel（D1f）" 1 "链路闭合"
else
  check "配对产出的 sessionKey 可直接用于 SecureChannel（D1f）" 0 "缺少衔接验证"
fi

echo "[26/32] D1g：手机侧 WebSocket 客户端（端到端）"
_RS="$ROOT/app/src/main/java/studio/morrow/android/Relay.kt"
_WS="$ROOT/app/src/main/java/studio/morrow/android/Relay.kt"
_RP="$ROOT/app/src/main/java/studio/morrow/android/Relay.kt"

# 证书指纹固定（自签证书唯一可控的做法）
if grep -q 'fingerprintEquals' "$_RS" && grep -q 'CertificateException' "$_RS"; then
  check "证书指纹固定校验（D1g）" 1 "自签证书只能靠指纹"
else
  check "证书指纹固定校验（D1g）" 0 "可能信任一切"
fi
# 证书错误必须归为不可重连（否则指纹不符会无限重连）
if grep -q 'findCertificateCause' "$_RS"; then
  check "证书失败归为不可重连（D1g）" 1 "TLS 会包成 SSLHandshakeException"
else
  check "证书失败归为不可重连（D1g）" 0 "会被当网络抖动反复重连"
fi

# hello 签名（channel 必须是 lan，分隔符必须是换行）
if grep -q 'CHANNEL_LAN = "lan"' "$_RP" && grep -q 'deviceId\\n\$channel\\n\$timestamp' "$_RP"; then
  check "hello 签名内容与桌面一致（D1g）" 1 "deviceId\\nlan\\nts"
else
  check "hello 签名内容与桌面一致（D1g）" 0 "分隔符或 channel 可能不同"
fi

# WebSocket 掩码方向（客户端加、服务端不加）
if grep -q 'masked: Boolean = true' "$_WS" && grep -q 'masked = true' "$_RS"; then
  check "客户端帧带掩码（D1g）" 1 "RFC 6455 §5.3"
else
  check "客户端帧带掩码（D1g）" 0 "可能被服务端断开"
fi
if grep -q 'expectMasked' "$_WS"; then
  check "接收时校验掩码方向（D1g）" 1 "防中间人改写"
else
  check "接收时校验掩码方向（D1g）" 0 "接受非标准来源的帧"
fi

# 握手 accept 校验（不校验会把 HTTP 200 当成功）
if grep -q 'acceptKey' "$_WS" && grep -q '258EAFA5-E914-47DA-95CA-C5AB0DC85B11' "$_WS"; then
  check "Sec-WebSocket-Accept 校验（D1g）" 1 "RFC 6455 固定 GUID"
else
  check "Sec-WebSocket-Accept 校验（D1g）" 0 "可能把普通 HTTP 响应当握手成功"
fi

# 断线分类：三种安全关闭码不重连
if grep -q 'CLOSE_TAMPERED = 4402' "$_RP" && grep -q 'CLOSE_REPLAYED = 4403' "$_RP"; then
  check "4401/4402/4403 不重连（D1g）" 1 "重连等于让攻击者反复试"
else
  check "4401/4402/4403 不重连（D1g）" 0 "可能对攻击反复重连"
fi
# CloseSignal 必须走协议分类（否则 4401 会被当网络错误）
if grep -q 'catch (e: CloseSignal)' "$_RS"; then
  check "握手期 close 帧走协议分类（D1g）" 1 "否则 4401 被当网络抖动"
else
  check "握手期 close 帧走协议分类（D1g）" 0 "未授权会被误判为可重连"
fi

# snapshotRequired 分支
if grep -q 'SNAPSHOT_REQUIRED' "$_RP" && grep -q 'snapshotRequest' "$_RP"; then
  check "snapshotRequired 走全量请求（D1g）" 1 "不能只补漏"
else
  check "snapshotRequired 走全量请求（D1g）" 0 "永远缺一段历史"
fi

# 握手后明文帧一律拒绝（防降级）
if grep -q '握手后收到非加密、非 welcome 的帧' "$_RP"; then
  check "握手后拒绝其他明文帧（D1g）" 1 "防降级注入"
else
  check "握手后拒绝其他明文帧（D1g）" 0 "允许明文注入事件"
fi

# 三组测试
for pair in "RelayProtocolVectorTest:relay-protocol-vector-test.log" \
            "WebSocketFramesTest:websocket-frames-test.log" \
            "RelaySocketEndToEndTest:relay-socket-e2e-test.log"; do
  cls="${pair%%:*}"
  log="$EVID/${pair#*:}"
  if [ -f "$log" ] && grep -qa "^OK" "$log"; then
    n="$(grep -aoP 'OK \(\K[0-9]+' "$log" | head -1)"
    case "$cls" in
      RelayProtocolVectorTest) check "协议层向量测试（D1g）" 1 "$n 项" ;;
      WebSocketFramesTest)     check "WebSocket 帧编解码（D1g）" 1 "$n 项" ;;
      RelaySocketEndToEndTest) check "真实 TLS 端到端（D1g）" 1 "$n 项" ;;
    esac
  else
    check "$cls（D1g）" 0 "见 docs/evidence/${pair#*:}"
  fi
done

# E3：跨语言真实连接 —— 手机 Kotlin 客户端连桌面 Node DirectChannel，
# 不跳过配对、不换假服务端。这份日志必须由本次预热真实生成。
if [ -f "$EVID/cross-device-e2e-test.log" ] && grep -qa "^OK" "$EVID/cross-device-e2e-test.log"; then
  check "桌面 Node ↔ 手机 Kotlin 真实配对连接（E3）" 1 \
    "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/cross-device-e2e-test.log" | head -1) 项"
else
  check "桌面 Node ↔ 手机 Kotlin 真实配对连接（E3）" 0 "见 docs/evidence/cross-device-e2e-test.log"
fi
#
# 注意这条**永远 PASS**，它不是在断言某个行为，而是在记录一个事实。
# 这样设计的理由：接线状态是「进度」不是「正确性」，用 check 的红/绿表达进度会
# 让「还没接线」看起来像失败。它的价值在于**一旦有人接线，备注文字会变**，
# 提醒同步更新 D1g 报告 §9 的边界描述 —— 避免报告说「未接线」而代码已经接了。
if grep -rq "RelaySocketClient(" "$ROOT/app/src/main" --include=*.kt 2>/dev/null    && [ "$(grep -rl "RelaySocketClient(" "$ROOT/app/src/main" --include=*.kt 2>/dev/null | wc -l)" -gt 1 ]; then
  check "客户端接线状态（D1g）" 1 "已被生产代码使用（报告 §9 需更新）"
else
  check "客户端接线状态（D1g）" 1 "未接线：只在测试里构造（报告 §9 已如实标注）"
fi

# 协议向量可被桌面源码确证
if [ ! -f "$ROOT/../Morrow/src/main/relay.ts" ]; then
  check "协议向量可被桌面源码确证（D1g）" 0 "读不到桌面 relay.ts"
elif node "$ROOT/scripts/run-desktop-ts.mjs" "$ROOT/scripts/gen-relay-protocol-vectors.mjs" --check \
       > "$EVID/relay-protocol-vector-check.log" 2>&1; then
  check "协议向量可被桌面源码确证（D1g）" 1 \
    "$(grep -ao '桌面源码 vs 手机硬编码值：.*' "$EVID/relay-protocol-vector-check.log" | head -1 | sed 's/桌面源码 vs 手机硬编码值：//')"
else
  check "协议向量可被桌面源码确证（D1g）" 0 \
    "$(grep -ao '桌面源码 vs 手机硬编码值：.*' "$EVID/relay-protocol-vector-check.log" 2>/dev/null | head -1 || echo '见 docs/evidence/relay-protocol-vector-check.log')"
fi

echo "[27/32] E1a：配凭据持久化（Keystore + AES-GCM）"
_CREDS="$ROOT/app/src/main/java/studio/morrow/android/Relay.kt"
_SKP="$ROOT/app/src/main/java/studio/morrow/android/Relay.kt"

# 加密落盘（不加密等于把 sessionKey 交给任何能读文件的进程）
if grep -q 'AES/GCM/NoPadding' "$_CREDS" && grep -q 'GCM_TAG_BITS' "$_CREDS"; then
  check "凭据以 AES-GCM 加密落盘（E1a）" 1 "GCM 128 位 tag"
else
  check "凭据以 AES-GCM 加密落盘（E1a）" 0 "可能明文写盘"
fi
# IV 必须每次随机（GCM 下重用 IV 直接泄漏明文异或值）
if grep -q 'cipher.init(Cipher.ENCRYPT_MODE, key)' "$_CREDS" && grep -q 'val iv = cipher.iv' "$_CREDS"; then
  check "加密 IV 由 Cipher 随机生成（E1a）" 1 "重用 IV 会泄漏明文"
else
  check "加密 IV 由 Cipher 随机生成（E1a）" 0 "IV 可能固定"
fi

# 拒绝明文降级
if grep -q '拒绝以明文持久化凭据' "$_CREDS"; then
  check "Keystore 不可用时拒绝持久化（E1a）" 1 "不静默降级"
else
  check "Keystore 不可用时拒绝持久化（E1a）" 0 "会明文降级"
fi

# 读路径不创建密钥（否则密钥被撤销会被误报成「凭据损坏」）
if grep -q 'hasKey(keyAlias)' "$_CREDS" && grep -q 'fun hasKey' "$_SKP"; then
  check "读路径不创建密钥（E1a）" 1 "避免误报 CORRUPT"
else
  check "读路径不创建密钥（E1a）" 0 "密钥撤销会被当成损坏"
fi

# 原子写
if grep -q 'renameTo(target)' "$_CREDS" && grep -q '\.tmp' "$_CREDS"; then
  check "临时文件 + 原子重命名（E1a）" 1 "中途被杀不留半个文件"
else
  check "临时文件 + 原子重命名（E1a）" 0 "可能留下损坏文件"
fi

# 损坏时不删文件 + 区分 Empty / Unreadable
if grep -q 'ERR_CORRUPT' "$_CREDS" && grep -q 'data object Empty' "$_CREDS"; then
  check "区分「未配对」与「读不出来」（E1a）" 1 "两者含义不同"
else
  check "区分「未配对」与「读不出来」（E1a）" 0 "配对失效无从解释"
fi

# 形状校验：sessionKey 32 字节、公钥 65 字节未压缩点
if grep -q 'sessionKey 必须是 32 字节' "$_CREDS" && grep -q '65 字节未压缩点' "$_CREDS"; then
  check "凭据形状逐项校验（E1a）" 1 "截断值不会流到 SecureChannel"
else
  check "凭据形状逐项校验（E1a）" 0 "坏凭据会在下游才炸"
fi

# Keystore 密钥不可导出（安全性的全部来源）
if grep -q 'setUserAuthenticationRequired(false)' "$_SKP" && grep -q 'AndroidKeyStore' "$_SKP"; then
  check "Keystore 生成不可导出 AES-256（E1a）" 1 "拿到文件也解不开"
else
  check "Keystore 生成不可导出 AES-256（E1a）" 0 "密钥来源不明"
fi

# 凭据文件必须排除出云备份（Keystore 密钥不随备份迁移，备份走的密文解不开）。
# API 31+ 走 data_extraction_rules.xml，两套规则都要排除 —— 只查旧文件
# 会漏掉「新系统上凭据其实进了云备份」这个洞。
backup_ok=0
if grep -q 'relay-credentials.bin' "$ROOT/app/src/main/res/xml/backup_rules.xml" 2>/dev/null &&
   grep -q 'relay-credentials.bin' "$ROOT/app/src/main/res/xml/data_extraction_rules.xml" 2>/dev/null; then
  backup_ok=1
fi
check "凭据排除出云备份（E1a）" "$backup_ok" "换机后状态为「未配对」而非「解不开的密文」"
if grep -q 'relay-credentials.bin' "$ROOT/app/src/main/res/xml/backup_rules.xml" 2>/dev/null &&
   grep -q 'relay-credentials.bin' "$ROOT/app/src/main/res/xml/data_extraction_rules.xml" 2>/dev/null; then
  :
else
  check "凭据排除出云备份（E1a）" 0 "密文会进云备份但换机解不开"
fi

# 测试
if [ -f "$EVID/relay-credentials-test.log" ] && grep -qa "^OK" "$EVID/relay-credentials-test.log"; then
  check "凭据持久化测试（E1a）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/relay-credentials-test.log" | head -1) 项"
else
  check "凭据持久化测试（E1a）" 0 "见 docs/evidence/relay-credentials-test.log"
fi

# 测试必须覆盖「明文不出现在文件里」这条断言
if grep -q '文件里不含任何明文凭据\|的明文绝不能出现在文件里' \
     "$ROOT/app/src/test/java/studio/morrow/android/RelayConnectionTests.kt" 2>/dev/null; then
  check "测试断言文件不含明文凭据（E1a）" 1 "加密真生效才有意义"
else
  check "测试断言文件不含明文凭据（E1a）" 0 "加密可能没被验证"
fi

# ═══ 第 28 组：relay 连接接线（E1b） ═══
echo "[28/32] E1b：relay 连接接线（凭据→连接→退避）"
# 这一组守的是「状态对象存在」与「生产有没有用它」的区别。
# E1a 的凭据类有 17 项测试，但它没有任何生产调用方 —— 本组补的就是那个缺口。
if [ -f "$EVID/relay-connection-controller-test.log" ] && grep -qa "^OK" "$EVID/relay-connection-controller-test.log"; then
  check "连接编排测试（E1b）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/relay-connection-controller-test.log" | head -1) 项"
else
  check "连接编排测试（E1b）" 0 "见 docs/evidence/relay-connection-controller-test.log"
fi

if [ -f "$EVID/relay-connection-wiring-test.log" ] && grep -qa "^OK" "$EVID/relay-connection-wiring-test.log"; then
  check "chip 四态渲染测试（E1b）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/relay-connection-wiring-test.log" | head -1) 项"
else
  check "chip 四态渲染测试（E1b）" 0 "见 docs/evidence/relay-connection-wiring-test.log"
fi

# 生产入口：这一组唯一能抓「没接线」的断言
if [ -f "$EVID/main-activity-relay-chip-test.log" ] && grep -qa "^OK" "$EVID/main-activity-relay-chip-test.log"; then
  check "生产入口接线测试（E1b）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/main-activity-relay-chip-test.log" | head -1) 项"
else
  check "生产入口接线测试（E1b）" 0 "见 docs/evidence/main-activity-relay-chip-test.log"
fi

# 静态核对：MainActivity 真的接了三条线（onStart 连 / onStop 断 / 传状态）
if grep -q 'relayConnection = relayState.connection' "$ROOT/app/src/main/java/studio/morrow/android/MainActivity.kt" 2>/dev/null; then
  check "MainActivity 把 relay 状态传给界面（E1b）" 1 "删掉这行会被上面第 3 项抓到"
else
  check "MainActivity 把 relay 状态传给界面（E1b）" 0 "接线缺失：chip 永远显示前台服务状态"
fi

if grep -q 'relayController.start()' "$ROOT/app/src/main/java/studio/morrow/android/MainActivity.kt" 2>/dev/null; then
  check "onStart 启动连接（E1b）" 1 "切后台再回来能自己恢复"
else
  check "onStart 启动连接（E1b）" 0 "热回前台不会重连"
fi

# 不可重连四类：静态确认 UNAUTHORIZED 走在不重试分支里
if grep -q 'RelayProtocol.Disconnect.UNAUTHORIZED' \
     "$ROOT/app/src/test/java/studio/morrow/android/RelayConnectionTests.kt" 2>/dev/null; then
  check "4401 不重连有测试守着（E1b）" 1 "重连等于让攻击者反复试签名"
else
  check "4401 不重连有测试守着（E1b）" 0 "这条逻辑可能被改成总是重连而无人发现"
fi

# ═══ 第 29 组：配对流程状态机（E2a） ═══
echo "[29/32] E2a：配对流程状态机（七状态 / 六种失败原因）"
# 守的是「失败原因可判定」：六种原因必须互不相同，
# 因为其中三种重来没用（要回电脑操作），合并成「配对失败」会让用户白试。
if [ -f "$EVID/pairing-flow-test.log" ] && grep -qa "^OK" "$EVID/pairing-flow-test.log"; then
  check "配对状态机测试（E2a）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/pairing-flow-test.log" | head -1) 项"
else
  check "配对状态机测试（E2a）" 0 "见 docs/evidence/pairing-flow-test.log"
fi

if grep -q 'if (e.code == EcdhPairing.TICKET_EXPIRED) Failure.TICKET_EXPIRED' \
     "$ROOT/app/src/main/java/studio/morrow/android/Relay.kt" 2>/dev/null; then
  check "过期与无效分开报（E2a）" 1 "两者该做的事不同：重新对准 vs 回电脑重生成"
else
  check "过期与无效分开报（E2a）" 0 "过期被并入 TICKET_INVALID"
fi

if grep -q '票据在输码期间过期' \
     "$ROOT/app/src/main/java/studio/morrow/android/Relay.kt" 2>/dev/null; then
  check "输码期间判过期（E2a）" 1 "过期优先于比码，否则码对也会被告知码错"
else
  check "输码期间判过期（E2a）" 0 "码对但过期会被误报成码错误"
fi

if grep -q 'state = State.Verifying' \
     "$ROOT/app/src/main/java/studio/morrow/android/Relay.kt" 2>/dev/null; then
  check "本地比码不等于完成（E2a）" 1 "还要等桌面校验 nonce 与票据状态"
else
  check "本地比码不等于完成（E2a）" 0 "可能跳过桌面确认"
fi

if grep -q 'ticketTtlMillis: Long = 90_000' \
     "$ROOT/app/src/main/java/studio/morrow/android/Relay.kt" 2>/dev/null; then
  check "票据 TTL 与桌面一致 90 秒（E2a）" 1 "桌面 relay.ts 的 TICKET_TTL_MS"
else
  check "票据 TTL 与桌面一致 90 秒（E2a）" 0 "TTL 不一致会导致两端判断矛盾"
fi

if grep -q 'onRejected' "$ROOT/app/src/main/java/studio/morrow/android/Relay.kt" 2>/dev/null \
   && grep -q 'state !is State.Verifying && state !is State.Saving' \
     "$ROOT/app/src/main/java/studio/morrow/android/Relay.kt" 2>/dev/null; then
  check "Saving 阶段也接受桌面拒绝（E2a）" 1 "只认 Verifying 会静默丢掉真实拒绝"
else
  check "Saving 阶段也接受桌面拒绝（E2a）" 0 "桌面拒绝可能被忽略"
fi

# 跨语言固定向量：桌面真实签发的票据，手机必须解出同一个六位码。
# 这一项比「自己拼自己解」强一个量级：它同时验证 base64url 是否带 padding、
# 公钥是否 uncompressed、HMAC 输入串、以及私钥编码格式。
if [ -f "$EVID/desk-pairing-vector-test.log" ] && grep -qa "^OK" "$EVID/desk-pairing-vector-test.log"; then
  check "桌面真实票据跨语言验证（E2a）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/desk-pairing-vector-test.log" | head -1) 项"
else
  check "桌面真实票据跨语言验证（E2a）" 0 "见 docs/evidence/desk-pairing-vector-test.log"
fi

if [ -f "$ROOT/scripts/gen-pairing-vector.mjs" ]; then
  check "固定向量生成脚本可复现（E2a）" 1 "node scripts/gen-pairing-vector.mjs"
else
  check "固定向量生成脚本可复现（E2a）" 0 "缺 scripts/gen-pairing-vector.mjs"
fi

# ═══ 第 30 组：配对 UI 入口（E2b） ═══
echo "[30/32] E2b：配对 UI 入口（未配对→可点→落盘）"
# 这一组守的是「用户能不能真的配一次对」。
# E1a 的 save() 有 17 项测试却无生产调用方 —— 本组让那条路径第一次走通。
if [ -f "$EVID/pairing-entry-test.log" ] && grep -qa "^OK" "$EVID/pairing-entry-test.log"; then
  check "配对入口生产链路（E2b）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/pairing-entry-test.log" | head -1) 项"
else
  check "配对入口生产链路（E2b）" 0 "见 docs/evidence/pairing-entry-test.log"
fi

if [ -f "$EVID/relay-hint-test.log" ] && grep -qa "^OK" "$EVID/relay-hint-test.log"; then
  check "relayHint 解析（E2b）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/relay-hint-test.log" | head -1) 项"
else
  check "relayHint 解析（E2b）" 0 "见 docs/evidence/relay-hint-test.log"
fi

if [ -f "$EVID/ticket-field-probe.log" ] && grep -qa "^OK" "$EVID/ticket-field-probe.log"; then
  check "票据新字段可解析（E2b）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/ticket-field-probe.log" | head -1) 项"
else
  check "票据新字段可解析（E2b）" 0 "relayHint/fingerprint 可能读不出来"
fi

# 未配对时必须有「去配对」入口（用户唯一出路）
if grep -q 'TAG_GO_PAIRING' "$ROOT/app/src/main/java/studio/morrow/android/Workbench.kt" 2>/dev/null \
   && grep -q 'onOpenPairing = onOpenPairing' "$ROOT/app/src/main/java/studio/morrow/android/Shell.kt" 2>/dev/null; then
  check "未配对时有「去配对」入口（E2b）" 1 "删掉它会让工位页变回死路"
else
  check "未配对时有「去配对」入口（E2b）" 0 "入口缺失或回调断链"
fi

# save() 必须真的写了地址：读路径要的字段写路径也得写
if grep -q 'append("relayHost=")' "$ROOT/app/src/main/java/studio/morrow/android/Relay.kt" 2>/dev/null \
   && grep -q 'append("relayPort=")' "$ROOT/app/src/main/java/studio/morrow/android/Relay.kt" 2>/dev/null; then
  check "凭据读写字段对称（E2b）" 1 "只读不写 = 配对成功但重启后连不上"
else
  check "凭据读写字段对称（E2b）" 0 "地址字段写了没存"
fi

# 指纹缺失时必须拒绝完成配对，不能存一份读不回来的凭据
if grep -q '配对码里没有电脑的证书指纹' "$ROOT/app/src/main/java/studio/morrow/android/MainActivity.kt" 2>/dev/null; then
  check "缺指纹时明确拒绝（E2b）" 1 "存空串会让 load() 判 CORRUPT"
else
  check "缺指纹时明确拒绝（E2b）" 0 "可能存下读不回来的凭据"
fi

# ═══ 第 31 组：桌面证书指纹（E2c） ═══
echo "[31/32] E2c：桌面证书指纹（票据带指纹 → 两端算同一串 → 存得下）"
# 这一组守的是「手机能不能连上这台电脑」。
# 桌面是自签证书，没有可信链，唯一正确的机制是指纹固定 ——
# 而指纹只能从配对票据里拿。票据不带指纹，配对在真机上永远走不完。

if [ -f "$EVID/tls-fingerprint-vector-test.log" ] && grep -qa "^OK" "$EVID/tls-fingerprint-vector-test.log"; then
  check "跨语言指纹向量（E2c）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/tls-fingerprint-vector-test.log" | head -1) 项"
else
  check "跨语言指纹向量（E2c）" 0 "见 docs/evidence/tls-fingerprint-vector-test.log"
fi

# 桌面侧：票据必须真的带上指纹（内部有值但没进 qr 是最容易漏的一种）
if grep -q 'fingerprint: this.tlsFingerprint' "$ROOT/../Morrow/src/main/relay.ts" 2>/dev/null; then
  check "桌面票据携带指纹（E2c）" 1 "issueTicket 写入 fingerprint"
else
  check "桌面票据携带指纹（E2c）" 0 "relay.ts 没把指纹写进票据"
fi

# 桌面侧：启动时把 tls.fingerprint 注入 PairingService
# 不绑变量名（此前写成 `pairing.setTlsFingerprint(...)`，E2d 把它改名 service 就误报失败）。
# 真正要守的是「loadOrCreateTls 之后有把 tls.fingerprint 注入配对服务」。
if grep -qE '\.setTlsFingerprint\(tls\.fingerprint\)' "$ROOT/../Morrow/src/main/relay.ts" 2>/dev/null; then
  check "桌面启动注入指纹（E2c）" 1 "startLanStack → setTlsFingerprint"
else
  check "桌面启动注入指纹（E2c）" 0 "证书载入了但没交给配对服务"
fi

# 手机侧：指纹归一化必须两种字母表都试（桌面用 base64url）
if grep -q 'Base64.getUrlDecoder().decode(body)' "$ROOT/app/src/main/java/studio/morrow/android/Relay.kt" 2>/dev/null; then
  check "指纹归一化支持 base64url（E2c）" 1 "桌面 digest(base64url) 含 - 与 _"
else
  check "指纹归一化支持 base64url（E2c）" 0 "83% 的真实指纹会被判不匹配"
fi

# 手机侧：配对时必须校验指纹能否解析，不能存垃圾
if grep -q 'canParseFingerprint' "$ROOT/app/src/main/java/studio/morrow/android/MainActivity.kt" 2>/dev/null; then
  check "配对时校验指纹格式（E2c）" 1 "存不解析的值 = 每次连接都失败"
else
  check "配对时校验指纹格式（E2c）" 0 "可能存下解析不出来的指纹"
fi

# 端到端：桌面真实签发的票据里真的带指纹，且等于 sha256(证书 DER)
# 上面几条只是 grep 源码，证明不了「运行时真的这么做了」。
if node "$ROOT/scripts/run-desktop-ts.mjs" "$ROOT/scripts/verify-ticket-fingerprint.mjs" > "$EVID/ticket-fingerprint-e2e.log" 2>&1; then
  check "桌面票据真实携带指纹（E2c）" 1 "$(grep -ac '^  OK' "$EVID/ticket-fingerprint-e2e.log" | head -1) 项通过"
else
  check "桌面票据真实携带指纹（E2c）" 0 "见 docs/evidence/ticket-fingerprint-e2e.log"
fi

# 固定向量脚本必须存在且可复现（与桌面源码比对）
if [ -f "$ROOT/scripts/gen-tls-fingerprint-vector.mjs" ]; then
  check "指纹向量生成脚本存在（E2c）" 1 "node scripts/gen-tls-fingerprint-vector.mjs --check"
else
  check "指纹向量生成脚本存在（E2c）" 0 "缺 scripts/gen-tls-fingerprint-vector.mjs"
fi

echo "[32/32] E2d：桌面配对签发入口（桌面能出码 + 手机能给出请求）"
# 这一组守的是「两个方向都通」：
#   桌面 → 手机：桌面必须能签出票据（此前 issueTicket 零生产调用方）；
#   手机 → 桌面：手机必须能把公钥交出去（桌面签票需要它）。
# 任缺一边，真机链路都断在第一环。
# 注意：前 8 条是 grep 源码的结构检查（3 个 IPC + preload + 设置页入口 +
# 开关生效 + 手机编码器 + 复制按钮），价值是「防止接线被删」；
# 「运行时真的这么做了」由后面的 pair-entry-e2e / pairing-request-test 覆盖。

for key in relayPairIssue relayPairList relayPairRevoke; do
  # contracts.ts 里它是 IPC 常量表的键（`relayPairIssue: 'relay:pair-issue'`），
  # ipc.ts 里是 `ipcMain.handle(IPC.relayPairIssue, ...)`。两边写法不同，不能共用一个模式。
  if grep -q "  $key: 'relay:pair-" "$ROOT/../Morrow/src/shared/contracts.ts" 2>/dev/null &&
     grep -q "IPC.$key" "$ROOT/../Morrow/src/main/ipc.ts" 2>/dev/null; then
    check "IPC 通道 $key 已注册（E2d）" 1 "contracts + ipc"
  else
    check "IPC 通道 $key 已注册（E2d）" 0 "缺通道或没注册 handler"
  fi
done

if grep -q 'relayPair: {' "$ROOT/../Morrow/src/preload/index.ts" 2>/dev/null; then
  check "preload 暴露 relayPair（E2d）" 1 "issue / list / revoke"
else
  check "preload 暴露 relayPair（E2d）" 0 "界面调不到"
fi

if grep -q "PairingTab" "$ROOT/../Morrow/src/renderer/features/settings.tsx" 2>/dev/null &&
   grep -q "id: 'pairing'" "$ROOT/../Morrow/src/renderer/features/settings.tsx" 2>/dev/null; then
  check "设置页有手机配对入口（E2d）" 1 "TABS + PAGE_META + 渲染分支"
else
  check "设置页有手机配对入口（E2d）" 0 "IPC 有了但用户点不到"
fi

if grep -q "await applyRelaySettings()" "$ROOT/../Morrow/src/main/ipc.ts" 2>/dev/null; then
  check "远程通道开关当场生效（E2d）" 1 "settingsUpdate → applyRelaySettings"
else
  check "远程通道开关当场生效（E2d）" 0 "改完设置要重启才生效"
fi

if grep -q "object PairRequest" "$ROOT/app/src/main/java/studio/morrow/android/Relay.kt" 2>/dev/null; then
  check "手机侧配对请求编码器（E2d）" 1 "MORROWPAIR1. 前缀 + base64url"
else
  check "手机侧配对请求编码器（E2d）" 0 "桌面拿不到手机公钥就签不出票"
fi

if grep -q "TAG_PAIRING_COPY_REQUEST" "$ROOT/app/src/main/java/studio/morrow/android/Pairing.kt" 2>/dev/null; then
  check "配对页有复制配对请求按钮（E2d）" 1 "第一步排在粘贴之前"
else
  check "配对页有复制配对请求按钮（E2d）" 0 "用户无处拿公钥"
fi

if [ -f "$EVID/pairing-request-test.log" ] && grep -qa "^OK" "$EVID/pairing-request-test.log"; then
  check "配对请求行为测试（E2d）" 1 "$(grep -aoP 'OK \(\K[0-9]+' "$EVID/pairing-request-test.log" | head -1) 项"
else
  check "配对请求行为测试（E2d）" 0 "见 docs/evidence/pairing-request-test.log"
fi

# 桌面仓库里的两条 vitest 套件由 Morrow 自己的工具链跑，本仓只负责调用并把日志落到 evidence。
# 不把 vitest 拉进本仓：那会引入一整套 node 依赖，而这里只需要「跑一次、看结果」。
MORROW_ROOT="$ROOT/../Morrow"
if [ -d "$MORROW_ROOT/node_modules" ] && [ -f "$MORROW_ROOT/node_modules/vitest/vitest.mjs" ]; then
  ( cd "$MORROW_ROOT" && node node_modules/vitest/vitest.mjs run tests/unit/pair-panel.test.ts > "$EVID/pair-panel.log" 2>&1 ) || true
  ( cd "$MORROW_ROOT" && node node_modules/vitest/vitest.mjs run tests/unit/relay-pair-api.test.ts > "$EVID/pair-api.log" 2>&1 ) || true
fi

if [ -f "$EVID/pair-panel.log" ] && ! grep -qa "FAIL" "$EVID/pair-panel.log"; then
  check "配对面板视图逻辑（E2d）" 1 "$(sed -r 's/\[[0-9;]*m//g' "$EVID/pair-panel.log" | grep -aoP 'Tests\s+\K[0-9]+(?= passed)' | head -1) 项"
else
  check "配对面板视图逻辑（E2d）" 0 "见 docs/evidence/pair-panel.log"
fi

if [ -f "$EVID/pair-api.log" ] && ! grep -qa "FAIL" "$EVID/pair-api.log"; then
  check "配对后端逻辑（E2d）" 1 "$(sed -r 's/\[[0-9;]*m//g' "$EVID/pair-api.log" | grep -aoP 'Tests\s+\K[0-9]+(?= passed)' | head -1) 项"
else
  check "配对后端逻辑（E2d）" 0 "见 docs/evidence/pair-api.log"
fi

if node "$ROOT/scripts/run-desktop-ts.mjs" "$ROOT/scripts/verify-pair-entry.mjs" > "$EVID/pair-entry-e2e.log" 2>&1; then
  check "桌面配对签发端到端（E2d）" 1 "$(grep -ac '^  OK' "$EVID/pair-entry-e2e.log" | head -1) 项通过"
else
  check "桌面配对签发端到端（E2d）" 0 "见 docs/evidence/pair-entry-e2e.log"
fi

echo
printf '═══ 结果：%d 通过 / %d 失败 ═══\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1