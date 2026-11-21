"""Morrow Android 仓库封面与示意图生成。

规则（沿用桌面仓库 docs 的视觉规范）：
- 封面 2:1，只用真实界面截图，不画假 UI；
- 示意图只解释结构，不冒充截图；
- 中文字体用 msyh，纯 ASCII 的协议串才用 consola。
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")
SCREENS = os.path.join(DOCS, "screens")
DIAGRAMS = os.path.join(DOCS, "diagrams")
os.makedirs(DIAGRAMS, exist_ok=True)

FONTS = r"C:\Windows\Fonts"
SERIF = os.path.join(FONTS, "georgia.ttf")
SANS = os.path.join(FONTS, "msyh.ttc")
SANS_BOLD = os.path.join(FONTS, "msyhbd.ttc")
MONO = os.path.join(FONTS, "consola.ttf")

PAPER = (242, 237, 226)
MUTED = (152, 143, 130)
GOLD = (185, 154, 91)
GREEN = (61, 220, 132)
LINE = (54, 57, 63)
CARD = (26, 30, 37)
CARD_2 = (34, 39, 47)
SOFT = (196, 187, 172)


def font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size)


def gradient(size, top, bottom):
    image = Image.new("RGB", size, top)
    draw = ImageDraw.Draw(image)
    for y in range(size[1]):
        t = y / max(1, size[1] - 1)
        draw.line(
            [(0, y), (size[0], y)],
            fill=(
                int(top[0] + (bottom[0] - top[0]) * t),
                int(top[1] + (bottom[1] - top[1]) * t),
                int(top[2] + (bottom[2] - top[2]) * t),
            ),
        )
    return image


def soft_glow(size, center, radius, color, strength=70):
    layer = Image.new("L", size, 0)
    d = ImageDraw.Draw(layer)
    steps = 26
    for i in range(steps, 0, -1):
        r = radius * i / steps
        d.ellipse(
            [center[0] - r, center[1] - r * 0.62, center[0] + r, center[1] + r * 0.62],
            fill=int(strength * (1 - i / steps) ** 2),
        )
    return Image.new("RGB", size, color), layer


def phone(shot_path: str, width: int) -> Image.Image:
    """把一张真实截图包成手机外框；只加边框与圆角，不篡改界面。"""
    shot = Image.open(shot_path).convert("RGB")
    ratio = width / shot.width
    height = int(shot.height * ratio)
    shot = shot.resize((width, height), Image.LANCZOS)
    pad = max(6, width // 90)
    frame = Image.new("RGB", (width + pad * 2, height + pad * 2), (38, 42, 50))
    frame.paste(shot, (pad, pad))
    mask = Image.new("L", frame.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, frame.width - 1, frame.height - 1), radius=width // 14, fill=255
    )
    out = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    out.paste(frame, (0, 0), mask)
    return out


def paste_with_shadow(base, card, xy, blur=22, alpha=140):
    shadow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        (xy[0] - 4, xy[1] + 14, xy[0] + card.width + 4, xy[1] + card.height + 22),
        radius=card.width // 14,
        fill=(0, 0, 0, alpha),
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    base.paste(shadow, (0, 0), shadow)
    base.paste(card, xy, card)


def build_cover() -> None:
    width, height = 2560, 1280
    image = gradient((width, height), (13, 16, 21), (7, 9, 12)).convert("RGBA")
    tint, mask = soft_glow((width, height), (1720, 600), 880, (44, 92, 78), 66)
    image.paste(tint, (0, 0), mask)
    draw = ImageDraw.Draw(image)

    draw.text((146, 352), "Morrow", font=font(SERIF, 196), fill=PAPER)
    draw.rectangle([148, 634, 268, 638], fill=GREEN)
    draw.text((150, 690), "安卓伴侣端", font=font(SANS, 40), fill=(206, 198, 184))
    draw.text((150, 766), "MORROW · ANDROID COMPANION", font=font(SANS, 22), fill=(128, 120, 108))
    draw.text((150, 840), "看进度 · 批权限 · 翻成品", font=font(SANS, 30), fill=SOFT)
    draw.text((150, 1148), "v0.2.0", font=font(MONO, 26), fill=(112, 105, 94))

    cards = [
        (os.path.join(SCREENS, "workbench-online.png"), 470, 900, 250),
        (os.path.join(SCREENS, "approvals.png"), 470, 1180, 236),
        (os.path.join(SCREENS, "swarm.png"), 470, 1460, 214),
    ]
    for path, w, x, y in cards:
        card = phone(path, w)
        paste_with_shadow(image, card, (x, y))

    image.convert("RGB").save(os.path.join(DOCS, "cover.png"), optimize=True)
    print("cover:", image.size)


def build_architecture() -> None:
    width, height = 2400, 1320
    image = gradient((width, height), (13, 16, 21), (9, 11, 15))
    draw = ImageDraw.Draw(image)

    draw.text((110, 58), "Morrow 安卓端的五个域", font=font(SANS_BOLD, 44), fill=PAPER)
    draw.text(
        (112, 122),
        "示意图：一个域一到两个文件，没有子包；界面只投影状态，工具永远在桌面执行",
        font=font(SANS, 24),
        fill=MUTED,
    )

    rows = [
        ("应用入口", "MainActivity.kt", "Activity、通知跳转、relay 生命周期接线"),
        ("远程通道", "RelayProtocol / RelayEnvelope / WebSocketFrames / EcdhPairing / SecureChannel / RelayCredentials / SecretKeyProvider / PairConfirmClient / RelaySocketClient / RelayConnection* / Pairing", "协议常量、ECDH 配对、SecureChannel、WebSocket 客户端"),
        ("事件归并", "EventParser / EventReducer / EventStreamViewModel / MorrowUiState / ReplayPack / IgnoreBridges", "快照解析、28 类事件、工位/对话/协作/审批/作品投影"),
        ("界面外壳", "Shell.kt · DesignTokens.kt", "路由、适配层、insets、设计令牌"),
        ("页面", "Workbench / Thread / Swarm / Approvals / Works / Settings / Preview", "七页一屏一页，手写路由"),
        ("通知与后台", "Notifications.kt", "四个渠道、免打扰、深链、前台服务保活"),
    ]

    y = 220
    label_font = font(SANS_BOLD, 27)
    file_font = font(MONO, 21)
    body_font = font(SANS, 22)
    for name, files, detail in rows:
        draw.rounded_rectangle((110, y, 2290, y + 150), radius=16, fill=CARD, outline=LINE, width=2)
        draw.text((146, y + 30), name, font=label_font, fill=PAPER)
        f = file_font if all(ord(c) < 128 for c in files) else body_font
        draw.text((146, y + 80), files, font=f, fill=GREEN)
        draw.text((1120, y + 44), detail, font=body_font, fill=SOFT)
        draw.text((1120, y + 92), "同一域需要拆分时，最多拆成「模型」与「界面」两个文件", font=body_font, fill=MUTED)
        y += 172

    draw.text(
        (110, 1240),
        "手机不执行任何工具：审批、停止、发消息、预览都走上行白名单，执行端始终在桌面。",
        font=font(SANS, 24),
        fill=MUTED,
    )
    image.save(os.path.join(DIAGRAMS, "architecture.png"), optimize=True)
    print("architecture:", image.size)


def build_pairing() -> None:
    width, height = 2400, 1240
    image = gradient((width, height), (13, 16, 21), (9, 11, 15))
    draw = ImageDraw.Draw(image)

    draw.text((110, 58), "配对与首连", font=font(SANS_BOLD, 44), fill=PAPER)
    draw.text(
        (112, 122),
        "示意图：一张 90 秒票据 + 一次 ECDH + 一次指纹固定；之后每一帧都加密",
        font=font(SANS, 24),
        fill=MUTED,
    )

    steps = [
        ("1", "手机生成配对请求", "MORROWPAIR1.<base64url>（含本机公钥）"),
        ("2", "桌面签发票据", "90 秒一次性：desktopPub · nonce · exp · relayHint · fingerprint"),
        ("3", "手机校验归属并算共享秘密", "ECDH P-256 → 六位码本地比对，最多 3 次"),
        ("4", "手机连 relayHint", "WSS，自签证书的 SHA-256 指纹必须等于票据里的值"),
        ("5", "hello 鉴权", "HMAC-SHA256(sessionKey, deviceId + 通道 + 时间戳)"),
        ("6", "welcome（明文只此一帧）", "salt · newestSeq · snapshotRequired"),
        ("7", "双向密钥与加密帧", "HKDF-SHA256 → AES-256-GCM，AAD 含方向与计数"),
        ("8", "首连补快照", "会话、审批、成员、作品一次对齐；之后只走增量"),
    ]

    y = 230
    for index, (num, title, detail) in enumerate(steps):
        draw.rounded_rectangle((110, y, 2290, y + 112), radius=14, fill=CARD, outline=LINE, width=2)
        draw.ellipse((146, y + 34, 190, y + 78), outline=GREEN, width=3)
        draw.text((160, y + 42), num, font=font(MONO, 24), fill=GREEN)
        draw.text((220, y + 26), title, font=font(SANS_BOLD, 26), fill=PAPER)
        d_font = font(MONO, 19) if all(ord(c) < 128 for c in detail) else font(SANS, 21)
        draw.text((220, y + 68), detail, font=d_font, fill=SOFT)
        y += 124

    draw.text(
        (110, 1200),
        "只在同一局域网内可用：公网中继尚未实现。撤销配对等于作废会话密钥，下次必须重新配对。",
        font=font(SANS, 23),
        fill=MUTED,
    )
    image.save(os.path.join(DIAGRAMS, "pairing-flow.png"), optimize=True)
    print("pairing-flow:", image.size)


if __name__ == "__main__":
    build_cover()
    build_architecture()
    build_pairing()
