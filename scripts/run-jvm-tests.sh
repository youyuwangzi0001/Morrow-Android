#!/usr/bin/env bash
# 在 JVM 内直接跑 Robolectric 测试，绕开 Gradle test worker。
# 原因：本机工作区路径含中文，Gradle 的 GradleWorkerMain 在该路径下无法启动
# （ClassNotFoundException + GBK 编码），属环境限制，不是工程缺陷。
#
# 另一个坑：test runtime classpath 里混有 .aar（Android 库的真实产物），
# JVM 无法从 .aar 读类，必须先解出里面的 classes.jar 再拼进 classpath。
#
# ══════════════════════════════════════════════════════════════════
# 提速结构（本轮重构，这是关键）
# ══════════════════════════════════════════════════════════════════
#
# 单个测试类耗时约 75 秒，其中 **Robolectric 沙盒初始化占 45–60 秒**。
# 我试过「一次 JVM 跑多个类」，**行不通**：各测试类用了大量不同的
# `@Config(qualifiers = "w411dp-h891dp-xhdpi")`，而 Robolectric 按
# qualifiers + sdk 分别建沙盒 —— 32 个类有 14 种配置，等于 14 个沙盒，
# 串行跑完还是那个时间。实测 3 个类串行 4 分 55 秒，与逐个跑持平。
#
# 真正有效的是**并行**：本机 20 核，每个类的 JVM 互不依赖。
# 8 路并行时 32 类约 6 分钟（vs 40 分钟），项数与结果逐类一致。
#
# 所以脚本分成两段：
#   run-jvm-tests.sh --prepare      # gradle 编译 + 拼 classpath（只做一次）
#   run-jvm-tests.sh <类名> …       # 跑测试；检测到已 prepare 就跳过 gradle
#
# verify.sh 用 `--prepare` 一次，再给多个类名（默认 8 路并行）。
# 单独调试某个类时直接 `run-jvm-tests.sh <类名>`，行为与以前一致。
# ══════════════════════════════════════════════════════════════════
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE_BIN="${GRADLE_BIN:-}"
if [ -z "$GRADLE_BIN" ]; then
  for candidate in "$ROOT/../.build/gradle-8.14.3/bin/gradle" "$ROOT/gradle-8.14.3/bin/gradle"; do
    if [ -x "$candidate" ]; then GRADLE_BIN="$candidate"; break; fi
  done
fi
GRADLE_BIN="${GRADLE_BIN:-gradle}"
if [ -z "${GRADLE_USER_HOME:-}" ] && [ -d "$ROOT/../.build/gradle-home" ]; then
  export GRADLE_USER_HOME="$ROOT/../.build/gradle-home"
fi

# Kotlin 编译器把 `.alive` 锁文件写到 `java.io.tmpdir`。这台机器上该值
# 被解析成宿主应用的运行时目录（`…/Morrow/deliver/.morrow-runtime-*/…/.morrow-temp`），
# 而那目录只读，于是编译报 `AccessDeniedException: …kotlin-compiler-in-…alive` ——
# 报错里完全看不出与临时目录有关，症状是「有时能编译、有时不能」。
# 显式指到工作区内一个可写目录。
export TMPDIR="${TMPDIR_OVERRIDE:-$ROOT/.build/tmp}"
export TEMP="$TMPDIR"
export TMP="$TMPDIR"
mkdir -p "$TMPDIR" 2>/dev/null || true
# 只给 gradle 的 JVM 传，不用 JAVA_TOOL_OPTIONS（那会让 Gradle 自身的
# 服务初始化失败：Could not create service of type BuildLifecycleAwareVirtualFileSystem）。
# 优先用工作区内的 SDK（可复现的那一份）；用户目录 SDK 在本机只读
if [ -d "$ROOT/../.sdk/platforms" ]; then
  export ANDROID_HOME="$(cd "$ROOT/../.sdk" && pwd)"
else
  export ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
fi
BUILD_DIR="$ROOT/../.build"
CP_FILE="$BUILD_DIR/cp.txt"
AAR_DIR="$BUILD_DIR/aar-classes"
STAMP_FILE="$BUILD_DIR/.jvm-tests.stamp"
LOCK_DIR="$BUILD_DIR/run-jvm-tests.lock"
ROBO_HOME="$BUILD_DIR/robo-home"

# NATIVE 需要把原生库解压到纯 ASCII 路径；本机除工作区外均不可写，故默认 LEGACY。
# LEGACY 下 Compose 仍会真实组合 + 测量 + 布局，可验节点/尺寸/截断，只是不出像素图。
GRAPHICS_MODE="${MORROW_GFX:-LEGACY}"

# ─────────────────── 参数解析 ───────────────────
MODE="run"          # run | prepare
PARALLEL="${MORROW_PARALLEL:-8}"
CLASSES=()

while [ $# -gt 0 ]; do
  case "$1" in
    --prepare) MODE="prepare"; shift ;;
    --parallel) PARALLEL="${2:?--parallel 需要数字}"; shift 2 ;;
    --list)
      LIST_FILE="${2:?--list 需要文件路径}"
      while IFS= read -r line; do
        # 去掉 Windows CR 与首尾空白，跳过空行与注释
        line="${line%$'\r'}"
        line="$(echo "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
        [ -z "$line" ] && continue
        case "$line" in \#*) continue ;; esac
        CLASSES+=("$line")
      done < "$LIST_FILE"
      shift 2 ;;
    *) CLASSES+=("$1"); shift ;;
  esac
done

# ─────────────────── classpath 拼装（两段共用） ───────────────────

build_cpparts() {
  echo "▸ 展开 AAR 内的 classes.jar"
  mkdir -p "$AAR_DIR"
  local aar_count=0 entry name out
  while IFS= read -r entry; do
    case "$entry" in
      *.aar)
        aar_count=$((aar_count+1))
        name="$(basename "$entry" .aar)"
        out="$AAR_DIR/$name-classes.jar"
        if [ ! -f "$out" ] || [ "$entry" -nt "$out" ]; then
          unzip -p "$entry" classes.jar > "$out" 2>/dev/null
        fi
        ;;
    esac
  done < <(tr ';' '\n' < "$CP_FILE")
  echo "  已展开 $aar_count 个 AAR"

  echo "▸ 拼装 JVM classpath"
  CPPARTS=""
  add() { [ -n "$1" ] && CPPARTS="$CPPARTS;$1"; }

  add "$(cygpath -w "$ROOT/app/build/tmp/kotlin-classes/debugUnitTest" 2>/dev/null || echo "$ROOT/app/build/tmp/kotlin-classes/debugUnitTest")"
  # 主代码类必须用 kotlin-classes/debug（本轮最新编译产物）。
  # 注意：依赖清单里还有一个 bundleDebugClassesToRuntimeJar 打出的 classes.jar，
  # 那是上一次构建的快照；它若排在前面会遮住新类，出现 NoSuchMethodError 这种
  # 「代码明明改了但运行时报方法不存在」的假故障。下面按路径排除它。
  add "$(cygpath -w "$ROOT/app/build/tmp/kotlin-classes/debug")"
  add "$(cygpath -w "$ROOT/app/build/intermediates/classes/debug" 2>/dev/null || echo "$ROOT/app/build/intermediates/classes/debug")"
  add "$(cygpath -w "$ANDROID_HOME/platforms/android-36/android.jar")"
  add "$(cygpath -w "$ROOT/app/build/intermediates/runtime_symbol_list/debug/processDebugResources")"
  # 依赖库的 R$id 等资源 ID 类在这里（AAR 里的 classes.jar 不含 R 类，
  # 它们由 AGP 在构建时生成）。缺了会在 PoolingContainer 之类的地方报
  # NoClassDefFoundError: androidx/.../R$id。
  add "$(cygpath -w "$ROOT/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar")"
  # AGP 生成的单元测试资源/清单指引：Robolectric 靠它找到 mergeDebugAssets、
  # 合并后的 AndroidManifest 和 apk-for-local-test，缺了会解析不到 Activity
  add "$(cygpath -w "$ROOT/app/build/intermediates/unit_test_config_directory/debugUnitTest/generateDebugUnitTestConfig/out")"

  while IFS= read -r entry; do
    case "$entry" in
      *.aar)
        name="$(basename "$entry" .aar)"
        add "$(cygpath -w "$AAR_DIR/$name-classes.jar")"
        ;;
      *.jar)
        # 排除上一次构建生成的类快照，防止旧类遮住 kotlin-classes/debug 的新类
        case "$entry" in
          *runtime_app_classes_jar*) ;;
          *) add "$(cygpath -w "$entry")" ;;
        esac
        ;;
    esac
  done < <(tr ';' '\n' < "$CP_FILE")
}

# ─────────────────── --prepare：编译 + classpath（一次） ───────────────────

do_prepare() {
  # 并发保护：两个 prepare 同时写 app/build 会读到半截 class。
  # 用 mkdir 做锁（原子操作，且不依赖 flock——Git Bash 的 flock 行为不稳）。
  # 注意：并行**跑测试**时不需要这把锁（JVM 只读 class），只有 gradle 要独占。
  local waited=0
  while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    if [ -f "$LOCK_DIR/pid" ] && ! kill -0 "$(cat "$LOCK_DIR/pid" 2>/dev/null)" 2>/dev/null; then
      rm -rf "$LOCK_DIR"; continue
    fi
    sleep 1
    waited=$((waited + 1))
    if [ "$waited" -ge 300 ]; then
      echo "  ✗ 等锁超过 5 分钟，可能有僵死进程持有 $LOCK_DIR" >&2
      return 1
    fi
  done
  echo $$ > "$LOCK_DIR/pid"
  local rc=0

  echo "▸ 编译主代码与测试代码"
  "$GRADLE_BIN" -p "$ROOT" :app:compileDebugUnitTestKotlin --no-daemon --console=plain -q || rc=1

  if [ "$rc" -eq 0 ]; then
    # Robolectric 读的是「打包后的单元测试清单 + apk-for-local-test」这两份**生成物**，
    # 不是 app/src/main/AndroidManifest.xml 原文。只跑 compileDebugUnitTestKotlin
    # 不会重建它们 —— 于是改了 Manifest（例如新增一个 <service>）之后，
    # 清单断言会在**旧快照**上运行：`getServiceInfo` 找不到刚加的服务，
    # 测试红得莫名其妙（实测踩过一次）。所以每次编译后强制刷新。
    echo "▸ 刷新单元测试清单与本地测试 apk"
    "$GRADLE_BIN" -p "$ROOT" :app:processDebugUnitTestManifest :app:packageDebugUnitTestForUnitTest \
      --no-daemon --console=plain -q || rc=1
  fi

  if [ "$rc" -eq 0 ]; then
    # 第三份生成物：test_config.properties（Robolectric 靠它定位合并后的清单）。
    # 没有任何任务会**无条件**重建它 —— 目录一旦被 clean 或增量构建清掉，
    # Robolectric 会退化成 org.robolectric.default 包，整组测试全红，
    # 看起来像代码坏了（实测踩过一次，18 项全红）。
    echo "▸ 刷新单元测试配置（Robolectric 定位清单用）"
    "$GRADLE_BIN" -p "$ROOT" :app:generateDebugUnitTestConfig \
      --no-daemon --console=plain -q || rc=1
    if [ ! -f "$ROOT/app/build/intermediates/unit_test_config_directory/debugUnitTest/generateDebugUnitTestConfig/out/com/android/tools/test_config.properties" ]; then
      echo "  ✗ test_config.properties 仍未生成，Robolectric 会找不到清单" >&2
      rc=1
    fi
  fi

  if [ "$rc" -eq 0 ]; then
    echo "▸ 刷新测试依赖 classpath"
    "$GRADLE_BIN" -p "$ROOT" :app:printTestClasspath -I "$ROOT/scripts/classpath.init.gradle" \
      --no-daemon --console=plain -q 2>/dev/null \
      | sed -n '/CLASSPATH_BEGIN/,/CLASSPATH_END/p' | sed -n '2p' > "$CP_FILE" || rc=1
    [ -s "$CP_FILE" ] || rc=1
  fi

  if [ "$rc" -eq 0 ]; then
    touch "$STAMP_FILE"
    echo "✓ 准备完成（编译 + classpath）"
  else
    echo "✗ 准备失败" >&2
  fi

  rm -rf "$LOCK_DIR"
  return "$rc"
}

# ─────────────────── 跑一个类 ───────────────────

run_one() {
  local cls="$1"
  # Robolectric 会在 user.home 下建 `.robolectric-download-lock`。
  # 并行时多个 JVM 共用同一个 home 会抢这把锁，失败信息是
  # 「Couldn't create lock file … 另一个程序正在使用此文件」，
  # 整类 0 项通过、看起来像代码坏了（实测踩到过一次，FcmPayloadTest 红）。
  # 所以每个 worker 用**自己的** home；而 maven 缓存仍指向共享目录，
  # 避免每个 worker 各下载一份 android-all 运行时。
  local home="${2:-$ROBO_HOME}"
  mkdir -p "$home/.m2"
  (
    cd "$ROOT/app" || exit 1
    java -Drobolectric.graphicsMode="$GRAPHICS_MODE" \
         -Drobolectric.logging=stdout \
         -Duser.home="$(cygpath -w "$home")" \
         -Dmaven.repo.local="$(cygpath -w "$ROBO_HOME/.m2/repository")" \
         -Dfile.encoding=UTF-8 \
         -cp "$CPPARTS" org.junit.runner.JUnitCore "$cls" 2>&1
  # 失败信息里含中文断言消息时，grep 会把它判成「binary file」并只打印一句
  # "Binary file (standard input) matches"，真正的失败原因就丢了。
  # -a（--text）强制按文本处理，失败详情能完整看到。
  ) | grep -a -vE "^\s+at (java|org\.junit|org\.robolectric|jdk)"
}

# ─────────────────── 主流程 ───────────────────

if [ "$MODE" = "prepare" ]; then
  do_prepare
  exit $?
fi

# 是否已经 prepare 过（classpath 存在且源码没更新）
prepared=0
if [ -f "$CP_FILE" ] && [ -s "$CP_FILE" ] && [ -f "$STAMP_FILE" ]; then
  newer=$(find "$ROOT/app/src" "$ROOT/app/build.gradle.kts" \
            "$ROOT/scripts/classpath.init.gradle" \
            -newer "$STAMP_FILE" -print -quit 2>/dev/null)
  [ -z "$newer" ] && prepared=1
fi

if [ "$prepared" -eq 0 ]; then
  do_prepare || exit 1
else
  echo "▸ 已准备过（源码未变），跳过 gradle"
fi

build_cpparts
mkdir -p "$ROBO_HOME/.m2"

# 单类：直接跑，保留完整输出（调试用）
if [ ${#CLASSES[@]} -le 1 ]; then
  cls="${CLASSES[0]:-studio.morrow.android.MorrowAppRenderTest}"
  echo "▸ 运行 $cls"
  run_one "$cls"
  exit 0
fi

# 多类：并行跑。每个类的输出写独立文件，跑完按原顺序汇总，
# 避免交错输出无法 grep 断言。
echo "▸ 并行运行 ${#CLASSES[@]} 个测试类（${PARALLEL} 路）"
LOG_DIR="$BUILD_DIR/test-logs"
rm -rf "$LOG_DIR"; mkdir -p "$LOG_DIR"

idx=0
declare -a PIDS=()
declare -a TAGS=()
for cls in "${CLASSES[@]}"; do
  idx=$((idx + 1))
  tag="$(printf '%02d' "$idx")-${cls##*.}"
  TAGS+=("$tag")
  run_one "$cls" "$BUILD_DIR/robo-home-w$(( (idx - 1) % PARALLEL ))" > "$LOG_DIR/$tag.log" 2>&1 &
  PIDS+=($!)
  # 控制并发：达到上限就等最早的一个
  if [ ${#PIDS[@]} -ge "$PARALLEL" ]; then
    wait "${PIDS[0]}" 2>/dev/null
    PIDS=("${PIDS[@]:1}")
  fi
done
for pid in "${PIDS[@]}"; do wait "$pid" 2>/dev/null; done

# 汇总：保持传入顺序，逐类打印判定行
passed=0
failed_classes=0
for tag in "${TAGS[@]}"; do
  f="$LOG_DIR/$tag.log"
  short="${tag#*-}"
  if [ ! -f "$f" ]; then
    echo "  ✗ $short — 无日志"
    failed_classes=$((failed_classes + 1))
    continue
  fi
  if grep -qa "^OK (" "$f"; then
    n=$(grep -aoE "^OK \([0-9]+ test" "$f" | grep -oE "[0-9]+" | head -1)
    echo "  ✓ $short (${n} 项)"
    passed=$((passed + ${n:-0}))
  else
    n=$(grep -aoE "Tests run: [0-9]+,  Failures: [0-9]+" "$f" | head -1)
    echo "  ✗ $short — ${n:-未产出结果}"
    failed_classes=$((failed_classes + 1))
    # 失败详情直接打出来，不用再翻日志
    grep -a -A 4 "^[0-9]*) " "$f" | head -20
  fi
done

echo "────── 并行汇总：通过 ${passed} 项，失败类 ${failed_classes} 个 ──────"
[ "$failed_classes" -eq 0 ]