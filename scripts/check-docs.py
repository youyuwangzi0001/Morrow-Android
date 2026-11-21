"""文档自检：本地链接、图片存在性、锚点、必备章节。

沿用桌面仓库 docs:check 的思路，但只做安卓仓库需要的那几条：
- Markdown 里的相对链接必须指向存在的文件；
- 图片路径必须存在，且尺寸固定（截图 1080x1920，封面 2560x1280）；
- 带锚点的链接必须能在目标文件里找到对应标题；
- 每张 docs/screens/*.png 至少被一篇文档引用。
"""
from __future__ import annotations

import os
import re
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")

LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
IMG_RE = re.compile(r"!\[[^\]]*\]\(([^)]+)\)")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$", re.MULTILINE)

errors: list[str] = []
warnings: list[str] = []


def slug(title: str) -> str:
    """GitHub 风格的锚点：小写、去标点、空格转连字符。"""
    out = []
    for ch in title.strip().lower():
        if ch.isalnum() or ch in "-_" or "\u4e00" <= ch <= "\u9fff":
            out.append(ch)
        elif ch == " ":
            out.append("-")
    return "".join(out)


def headings(path: str) -> set[str]:
    text = open(path, encoding="utf-8").read()
    return {slug(m.group(2)) for m in HEADING_RE.finditer(text)}


def markdown_files() -> list[str]:
    files = []
    for base in (ROOT, DOCS):
        for name in os.listdir(base):
            p = os.path.join(base, name)
            if os.path.isfile(p) and name.endswith(".md"):
                files.append(p)
    for name in sorted(os.listdir(DOCS)):
        p = os.path.join(DOCS, name)
        if os.path.isdir(p):
            for sub in os.listdir(p):
                if sub.endswith(".md"):
                    files.append(os.path.join(p, sub))
    return files


used_images: set[str] = set()

for md in markdown_files():
    text = open(md, encoding="utf-8").read()
    rel_md = os.path.relpath(md, ROOT)

    for target in LINK_RE.findall(text):
        if target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        path_part, _, anchor = target.partition("#")
        full = os.path.normpath(os.path.join(os.path.dirname(md), path_part)) if path_part else md
        if not os.path.exists(full):
            errors.append(f"{rel_md}: 链接目标不存在 -> {target}")
            continue
        if anchor and full.endswith(".md"):
            if anchor not in headings(full):
                errors.append(f"{rel_md}: 锚点不存在 -> {target}")

    for target in IMG_RE.findall(text):
        if target.startswith(("http://", "https://")):
            continue
        full = os.path.normpath(os.path.join(os.path.dirname(md), target))
        if not os.path.exists(full):
            errors.append(f"{rel_md}: 图片不存在 -> {target}")
            continue
        used_images.add(full.replace("\\", "/"))
        try:
            with Image.open(full) as im:
                w, h = im.size
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{rel_md}: 图片读不出来 {target}: {exc}")
            continue
        rel = os.path.relpath(full, MARK := ROOT).replace("\\", "/")
        if "/screens/" in rel and (w, h) != (1080, 1920):
            errors.append(f"{rel_md}: 截图尺寸应为 1080x1920，实际 {w}x{h} -> {target}")
        if rel.endswith("docs/cover.png") and (w, h) != (2560, 1280):
            errors.append(f"{rel_md}: 封面应为 2560x1280，实际 {w}x{h}")

# 每张截图都要被引用
for name in sorted(os.listdir(os.path.join(DOCS, "screens"))):
    p = os.path.join(DOCS, "screens", name).replace("\\", "/")
    if p not in used_images:
        errors.append(f"截图未被任何文档引用 -> docs/screens/{name}")

# 每张示意图都要被引用
for name in sorted(os.listdir(os.path.join(DOCS, "diagrams"))):
    p = os.path.join(DOCS, "diagrams", name).replace("\\", "/")
    if p not in used_images:
        errors.append(f"示意图未被任何文档引用 -> docs/diagrams/{name}")

# 双语首页互链
zh = open(os.path.join(ROOT, "README.zh.md"), encoding="utf-8").read()
en = open(os.path.join(ROOT, "README.md"), encoding="utf-8").read()
if "README.md" not in zh:
    errors.append("README.zh.md 没有链回 README.md")
if "README.zh.md" not in en:
    errors.append("README.md 没有链到 README.zh.md")

print(f"checked files: {len(markdown_files())}")
for w in warnings:
    print("WARN", w)
if errors:
    for e in errors:
        print("FAIL", e)
    sys.exit(1)
print("OK: 所有本地链接、锚点与图片均有效")
