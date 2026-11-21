"""提取 verify.sh 里的完整测试类清单，并 8 路并行跑全仓 JVM 测试。

为什么单独写成脚本：中文内容与正则经过 shell heredoc 会被破坏
（本机 shell 走 GBK，`>` 与 `\n` 都出过问题），落成 .py 文件再跑最稳。
"""
import pathlib
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

root = pathlib.Path(__file__).resolve().parent.parent
verify = (root / "scripts" / "verify.sh").read_bytes().decode("utf-8")

m = re.search(r'bash "\$ROOT/scripts/run-jvm-tests\.sh"((?:\s+studio\.[\w.]+)+)\s*>', verify)
if not m:
    print("FATAL 没找到测试类清单")
    sys.exit(1)
classes = m.group(1).split()
print(f"测试类 {len(classes)} 个")

script = root / "scripts" / "run-jvm-tests.sh"


def run(cls):
    short = cls.rsplit(".", 1)[-1]
    p = subprocess.run(
        ["bash", str(script), cls],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    out = p.stdout + p.stderr
    ok = re.search(r"^OK \((\d+) tests?\)", out, re.M)
    fail = re.search(r"Tests run: (\d+),\s+Failures: (\d+)", out)
    if ok:
        return short, int(ok.group(1)), 0, ""
    if fail:
        return short, int(fail.group(1)), int(fail.group(2)), out[-600:]
    return short, 0, -1, out[-600:]


total = bad_classes = 0
failures = 0
with ThreadPoolExecutor(max_workers=8) as ex:
    for short, ran, failed, detail in ex.map(run, classes):
        if failed == 0:
            print(f"  OK   {short:38s} {ran:3d} 项")
            total += ran
        else:
            print(f"  FAIL {short:38s} {ran:3d} 项 / {failed} 失败")
            print("       " + detail.replace("\n", "\n       ")[:500])
            bad_classes += 1
            failures += max(failed, 1)

print()
print(f"全仓：{len(classes)} 类 {total} 项，{bad_classes} 个类失败")
sys.exit(0 if bad_classes == 0 else 1)
