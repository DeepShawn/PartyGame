#!/usr/bin/env python3
"""同乐盒 PartyGame · 设计资源生成与校验。

一套原创几何数据（100 x 100 图形网格）同时产出四种渲染：
- design/tokens.json（UiStyle.kt 消费的 4 主题色，同式样复制到 app assets）
- design/icons/*.svg 与 web/public/icons/*.svg/.png（16 游戏图标 + 启动图标 trio）
- app/src/main/res/drawable/ic_game_*.xml（Android vector drawable）
- app/src/main/res/drawable/ic_launcher_* 与 mipmap 自适应图标（4 个桌面别名图标）
- artifacts/design-preview.png（主题色卡 + 图标网格拼图，评估用）

运行：python scripts/generate-assets.py
脚本会断言全部预期产物存在、SVG/Android XML 可解析、PNG 尺寸正确，并对
主题与图标的 WCAG 对比度（正文/次要 ≥4.5:1，关键图形 ≥3:1）做自动校验。
"""

from __future__ import annotations

import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree as ET

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
DESIGN_DIR = ROOT / "design"
DESIGN_ICONS = DESIGN_DIR / "icons"
WEB_ICONS = ROOT / "web" / "public" / "icons"
APP_RES = ROOT / "app" / "src" / "main" / "res"
DRAWABLE = APP_RES / "drawable"
MIPMAP_ANY = APP_RES / "mipmap-anydpi-v26"
MIPMAP_DPIS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ASSETS_DESIGN = ROOT / "app" / "src" / "main" / "assets" / "design"
ARTIFACTS = ROOT / "artifacts"
PREVIEW = ARTIFACTS / "design-preview.png"

WEB_PNG_SIZE = 256
SS = 4                 # PNG 超采样倍率
GLYPH_SCALE = 0.76     # 启动图标前景/预览在图标画布中的占比

GAMES = [
    ("reaction", "反应抢按"), ("color", "颜色陷阱"), ("math", "心算抢答"), ("memory", "记忆翻牌"),
    ("snake", "贪吃蛇竞技"), ("tank", "坦克乱斗"), ("bomber", "炸弹人"), ("dodge", "躲避竞技"),
    ("gomoku", "五子棋"), ("xiangqi", "中国象棋"), ("chess", "国际象棋"), ("go", "围棋"),
    ("reversi", "黑白棋"), ("chinese_checkers", "中国跳棋"), ("draughts", "国际跳棋"), ("doudizhu", "斗地主"),
]
LAUNCHERS = [("trio", "同乐三人"), ("dice", "幸运骰"), ("board", "棋盘"), ("mono", "极简")]

# ---------------------------------------------------------------- 主题色 ----------------------------------------------------------------
# 与 UiStyle.kt 内置兜底色一致；sea 的 accent 由 #117D78 加深为 #0F6F6A，
# 使次级按钮文字（accent on soft）达到 4.5:1。
THEMES = {
    "sea": {"name": "海盐青", "colors": {
        "background": "#F4F8F7", "surface": "#FFFFFF", "text": "#183634", "muted": "#57736F",
        "accent": "#0F6F6A", "onAccent": "#FFFFFF", "soft": "#E0F2ED", "border": "#D7E6E2",
        "boardLight": "#E7D8B8", "boardDark": "#77988B", "danger": "#B73545", "dark": False}},
    "midnight": {"name": "午夜蓝", "colors": {
        "background": "#121D2A", "surface": "#1C2A3A", "text": "#F0F5FA", "muted": "#B4C3D1",
        "accent": "#80D8C4", "onAccent": "#0C2926", "soft": "#273D49", "border": "#3A4A5B",
        "boardLight": "#A5BAC2", "boardDark": "#526B79", "danger": "#FFADAD", "dark": True}},
    "cherry": {"name": "樱桃红", "colors": {
        "background": "#FFF5F6", "surface": "#FFFFFF", "text": "#422A34", "muted": "#7C5969",
        "accent": "#A9325D", "onAccent": "#FFFFFF", "soft": "#FBE5EC", "border": "#EACDD7",
        "boardLight": "#F8DFE3", "boardDark": "#BC8093", "danger": "#AD2838", "dark": False}},
    "wood": {"name": "原木棋室", "colors": {
        "background": "#F6F0E6", "surface": "#FFFBF4", "text": "#3B3026", "muted": "#75634F",
        "accent": "#75603B", "onAccent": "#FFFFFF", "soft": "#EDE2D0", "border": "#DCCDAD",
        "boardLight": "#EDD2A1", "boardDark": "#AC8053", "danger": "#AA382E", "dark": False}},
}
COLOR_KEYS = ["background", "surface", "text", "muted", "accent", "onAccent", "soft", "border",
              "boardLight", "boardDark", "danger"]

# ---------------------------------------------------------------- 图标调色板 ----------------------------------------------------------------
P = {
    "tile": "#EAF4EF", "edge": "#C6DDD4", "ink": "#153D3A", "teal": "#0E8175",
    "amber": "#B87A1E", "coral": "#C2483D", "grid": "#7FA69A", "paper": "#FFFFFF",
}
XIANGQI_PAPER = "#FBF6EA"
DRAUGHTS_FILL = "#F3EBD7"
DRAUGHTS_DARK = "#2E5B53"

LAUNCHER_BG = {"trio": "#0F6F6A", "dice": "#C2483D", "board": "#123A36", "mono": "#F2F6F3"}
LAUNCHER_FG = {
    "trio": {"white": "#FFFFFF", "amber": "#FFC96B", "coral": "#FFA89B"},
    "dice": {"paper": "#FFFFFF", "ink": "#153D3A", "teal": "#0E8175"},
    "board": {"paper": "#EFE5D2", "dark": "#2F6E63", "amber": "#FFC96B", "white": "#FFFFFF"},
    "mono": {"ink": "#153D3A", "teal": "#0E8175"},
}

# ---------------------------------------------------------------- 形状原语 ----------------------------------------------------------------


@dataclass
class Rect:
    x: float; y: float; w: float; h: float; r: float = 0.0
    fill: str | None = None; stroke: str | None = None; sw: float = 0.0


@dataclass
class Disc:
    cx: float; cy: float; r: float
    fill: str | None = None; stroke: str | None = None; sw: float = 0.0


@dataclass
class Poly:
    pts: list; close: bool = False
    fill: str | None = None; stroke: str | None = None; sw: float = 0.0


@dataclass
class Spline:
    pts: list; close: bool = False
    fill: str | None = None; stroke: str | None = None; sw: float = 0.0


def star4(cx, cy, rl, rs):
    return [(cx, cy - rl), (cx + rs, cy - rs), (cx + rl, cy), (cx + rs, cy + rs),
            (cx, cy + rl), (cx - rs, cy + rs), (cx - rl, cy), (cx - rs, cy - rs)]


def diamond(cx, cy, r):
    return [(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)]


def pie_pts(cx, cy, r, a0, a1, steps=48):
    pts = []
    for i in range(steps + 1):
        a = math.radians(a0 + (a1 - a0) * i / steps)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def catmull_beziers(pts, closed):
    """Catmull-Rom 样条转三次贝塞尔段，供 SVG / vector drawable / 采样共用。"""
    n = len(pts)

    def at(i):
        return pts[i % n] if closed else pts[max(0, min(n - 1, i))]

    segs = []
    for i in range(n if closed else n - 1):
        p0, p1, p2, p3 = at(i - 1), at(i), at(i + 1), at(i + 2)
        c1 = (p1[0] + (p2[0] - p0[0]) / 6.0, p1[1] + (p2[1] - p0[1]) / 6.0)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6.0, p2[1] - (p3[1] - p1[1]) / 6.0)
        segs.append((p1, c1, c2, p2))
    return segs


def sample_bezier(p0, c1, c2, p1, per=12):
    out = []
    for t in range(1, per + 1):
        u = t / per
        a, b, c, d = (1 - u) ** 3, 3 * (1 - u) ** 2 * u, 3 * (1 - u) * u ** 2, u ** 3
        out.append((a * p0[0] + b * c1[0] + c * c2[0] + d * p1[0],
                    a * p0[1] + b * c1[1] + c * c2[1] + d * p1[1]))
    return out


def spline_points(sh: Spline, per=12):
    segs = catmull_beziers(sh.pts, sh.close)
    pts = [segs[0][0]]
    for seg in segs:
        pts.extend(sample_bezier(*seg, per=per))
    return pts


def xf(shapes, s, cx=50.0, cy=50.0, tx=0.0, ty=0.0):
    """整体缩放（围绕 cx,cy）并平移，用于启动图标的前景/预览排布。"""
    def p(pt):
        return (cx + (pt[0] - 50.0) * s + tx, cy + (pt[1] - 50.0) * s + ty)

    out = []
    for sh in shapes:
        if isinstance(sh, Rect):
            x, y = p((sh.x, sh.y))
            out.append(Rect(x, y, sh.w * s, sh.h * s, sh.r * s, sh.fill, sh.stroke, sh.sw * s))
        elif isinstance(sh, Disc):
            x, y = p((sh.cx, sh.cy))
            out.append(Disc(x, y, sh.r * s, sh.fill, sh.stroke, sh.sw * s))
        elif isinstance(sh, Poly):
            out.append(Poly([p(q) for q in sh.pts], sh.close, sh.fill, sh.stroke, sh.sw * s))
        elif isinstance(sh, Spline):
            out.append(Spline([p(q) for q in sh.pts], sh.close, sh.fill, sh.stroke, sh.sw * s))
    return out


# ---------------------------------------------------------------- 渲染器 1：SVG ----------------------------------------------------------------


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def svg_paint(sh):
    parts = [f'fill="{sh.fill or "none"}"']
    if sh.stroke and sh.sw > 0:
        parts += [f'stroke="{sh.stroke}"', f'stroke-width="{fmt(sh.sw)}"',
                  'stroke-linecap="round" stroke-linejoin="round"']
    return " ".join(parts)


def svg_element(sh):
    if isinstance(sh, Rect):
        rx = f' rx="{fmt(sh.r)}" ry="{fmt(sh.r)}"' if sh.r else ""
        return f'<rect x="{fmt(sh.x)}" y="{fmt(sh.y)}" width="{fmt(sh.w)}" height="{fmt(sh.h)}"{rx} {svg_paint(sh)}/>'
    if isinstance(sh, Disc):
        return f'<circle cx="{fmt(sh.cx)}" cy="{fmt(sh.cy)}" r="{fmt(sh.r)}" {svg_paint(sh)}/>'
    if isinstance(sh, Poly):
        pts = " ".join(f"{fmt(x)},{fmt(y)}" for x, y in sh.pts)
        tag = "polygon" if sh.close else "polyline"
        return f'<{tag} points="{pts}" {svg_paint(sh)}/>'
    if isinstance(sh, Spline):
        segs = catmull_beziers(sh.pts, sh.close)
        d = "M" + ",".join(fmt(v) for v in segs[0][0])
        for _, c1, c2, p1 in segs:
            d += f" C{fmt(c1[0])},{fmt(c1[1])} {fmt(c2[0])},{fmt(c2[1])} {fmt(p1[0])},{fmt(p1[1])}"
        if sh.close:
            d += " Z"
        return f'<path d="{d}" {svg_paint(sh)}/>'
    raise TypeError(sh)


def build_svg(title, shapes):
    body = "\n  ".join(svg_element(sh) for sh in shapes)
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100" role="img">\n'
            f'  <title>{title}</title>\n  {body}\n</svg>\n')


# ---------------------------------------------------------------- 渲染器 2：Android vector drawable ----------------------------------------------------------------


def vd_path(sh):
    if isinstance(sh, Rect):
        x, y, w, h, r = sh.x, sh.y, sh.w, sh.h, sh.r
        if r <= 0:
            return f"M{fmt(x)},{fmt(y)} H{fmt(x + w)} V{fmt(y + h)} H{fmt(x)} Z"
        return (f"M{fmt(x + r)},{fmt(y)} H{fmt(x + w - r)} A{fmt(r)},{fmt(r)} 0 0 1 {fmt(x + w)},{fmt(y + r)} "
                f"V{fmt(y + h - r)} A{fmt(r)},{fmt(r)} 0 0 1 {fmt(x + w - r)},{fmt(y + h)} "
                f"H{fmt(x + r)} A{fmt(r)},{fmt(r)} 0 0 1 {fmt(x)},{fmt(y + h - r)} "
                f"V{fmt(y + r)} A{fmt(r)},{fmt(r)} 0 0 1 {fmt(x + r)},{fmt(y)} Z")
    if isinstance(sh, Disc):
        r = sh.r
        return (f"M{fmt(sh.cx - r)},{fmt(sh.cy)} a{fmt(r)},{fmt(r)} 0 1 1 {fmt(2 * r)},0 "
                f"a{fmt(r)},{fmt(r)} 0 1 1 {fmt(-2 * r)},0 Z")
    if isinstance(sh, Poly):
        d = "M" + ",".join(fmt(v) for v in sh.pts[0]) + "".join(
            f" L{fmt(q[0])},{fmt(q[1])}" for q in sh.pts[1:])
        return d + (" Z" if sh.close else "")
    if isinstance(sh, Spline):
        segs = catmull_beziers(sh.pts, sh.close)
        d = "M" + ",".join(fmt(v) for v in segs[0][0])
        for _, c1, c2, p1 in segs:
            d += f" C{fmt(c1[0])},{fmt(c1[1])} {fmt(c2[0])},{fmt(c2[1])} {fmt(p1[0])},{fmt(p1[1])}"
        return d + (" Z" if sh.close else "")
    raise TypeError(sh)


def vd_element(sh):
    attrs = [f'android:fillColor="{sh.fill or "#00000000"}"']
    if sh.stroke and sh.sw > 0:
        attrs += [f'android:strokeColor="{sh.stroke}"', f'android:strokeWidth="{fmt(sh.sw)}"',
                  'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
    return f'    <path android:pathData="{vd_path(sh)}" {" ".join(attrs)}/>'


def vd_file(shapes, dp, viewport):
    return "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{dp}dp"',
        f'    android:height="{dp}dp"',
        f'    android:viewportWidth="{fmt(viewport)}"',
        f'    android:viewportHeight="{fmt(viewport)}">',
        *[vd_element(sh) for sh in shapes],
        '</vector>',
    ]) + "\n"


def adaptive_icon_file(lid):
    return "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">',
        f'    <background android:drawable="@drawable/ic_launcher_{lid}_background" />',
        f'    <foreground android:drawable="@drawable/ic_launcher_{lid}_foreground" />',
        '</adaptive-icon>',
    ]) + "\n"


# ---------------------------------------------------------------- 渲染器 3：Pillow ----------------------------------------------------------------


def pil_draw(d: ImageDraw.ImageDraw, sh, k):
    if isinstance(sh, Rect):
        box = [sh.x * k, sh.y * k, (sh.x + sh.w) * k, (sh.y + sh.h) * k]
        if sh.fill:
            d.rounded_rectangle([round(v) for v in box], radius=round(sh.r * k), fill=sh.fill)
        if sh.stroke and sh.sw > 0:
            e = sh.sw * k / 2.0
            d.rounded_rectangle([round(box[0] - e), round(box[1] - e), round(box[2] + e), round(box[3] + e)],
                                radius=round((sh.r + sh.sw / 2.0) * k), outline=sh.stroke,
                                width=max(1, round(sh.sw * k)))
        return
    if isinstance(sh, Disc):
        box = [(sh.cx - sh.r) * k, (sh.cy - sh.r) * k, (sh.cx + sh.r) * k, (sh.cy + sh.r) * k]
        if sh.fill:
            d.ellipse([round(v) for v in box], fill=sh.fill)
        if sh.stroke and sh.sw > 0:
            e = sh.sw * k / 2.0
            d.ellipse([round(box[0] - e), round(box[1] - e), round(box[2] + e), round(box[3] + e)],
                      outline=sh.stroke, width=max(1, round(sh.sw * k)))
        return
    if isinstance(sh, (Poly, Spline)):
        pts = sh.pts if isinstance(sh, Poly) else spline_points(sh)
        pix = [(round(x * k), round(y * k)) for x, y in pts]
        if sh.fill:
            d.polygon(pix, fill=sh.fill)
        if sh.stroke and sh.sw > 0:
            w = max(1, round(sh.sw * k))
            seq = pix + [pix[0]] if sh.close else pix
            if len(seq) >= 2:
                d.line(seq, fill=sh.stroke, width=w, joint="curve")
            rp = w / 2.0
            for px, py in pix:
                d.ellipse([px - rp, py - rp, px + rp, py + rp], fill=sh.stroke)
        return
    raise TypeError(sh)


def render(shapes, size, ss=SS):
    img = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = size * ss / 100.0
    for sh in shapes:
        pil_draw(d, sh, k)
    return img.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------- 图标几何定义 ----------------------------------------------------------------


def tile():
    return Rect(3, 3, 94, 94, r=24, fill=P["tile"], stroke=P["edge"], sw=1.6)


def icon_reaction():
    return [
        Poly(star4(50, 27, 11, 3.4), close=True, fill=P["amber"]),
        Disc(50, 64, 11, stroke=P["teal"], sw=7),
    ]


def icon_color():
    return [
        Disc(38, 40, 12.5, fill=P["teal"], stroke=P["tile"], sw=3.5),
        Disc(62, 40, 12.5, fill=P["amber"], stroke=P["tile"], sw=3.5),
        Disc(50, 61, 12.5, fill=P["coral"], stroke=P["tile"], sw=3.5),
    ]


def icon_math():
    out = [Rect(27, 25, 46, 50, r=9, stroke=P["ink"], sw=4.5)]
    for y in (36.5, 50, 63.5):
        out.append(Poly([(33, y), (67, y)], stroke=P["grid"], sw=2.6))
    for x in (40.5, 52.5):
        out.append(Disc(x, 36.5, 5.2, fill=P["teal"]))
    for x in (47.5, 59.5):
        out.append(Disc(x, 50, 5.2, fill=P["amber"]))
    for x in (40.5, 52.5):
        out.append(Disc(x, 63.5, 5.2, fill=P["coral"]))
    return out


def icon_memory():
    return [
        Rect(46, 26, 24, 34, r=5, fill=P["teal"], stroke=P["ink"], sw=3),
        Poly(diamond(58, 43, 7.5), close=True, stroke=P["paper"], sw=2.4),
        Disc(58, 43, 2.2, fill=P["paper"]),
        Rect(26, 40, 24, 34, r=5, fill=P["paper"], stroke=P["ink"], sw=3.5),
        Poly(diamond(38, 57, 7), close=True, fill=P["amber"]),
    ]


def icon_snake():
    return [
        Poly([(24, 69), (36, 69), (36, 55), (48, 55), (48, 69), (60, 69), (60, 45), (68, 45)],
             stroke=P["teal"], sw=7.5),
        Disc(68, 45, 8, fill=P["teal"]),
        Disc(70.8, 42.6, 2.0, fill=P["paper"]),
        Disc(83, 45, 5.5, fill=P["amber"]),
        Poly([(83, 39.5), (85.5, 36.2)], stroke=P["amber"], sw=2.8),
    ]


def icon_tank():
    return [
        Rect(25, 45, 9, 31, r=4.5, fill=P["ink"]),
        Rect(66, 45, 9, 31, r=4.5, fill=P["ink"]),
        Rect(30, 47, 40, 27, r=8, fill=P["teal"]),
        Poly([(50, 60), (50, 27)], stroke=P["ink"], sw=6.5),
        Disc(50, 60.5, 9, fill=P["ink"]),
        Disc(47.2, 57.8, 2.2, fill=P["paper"]),
    ]


def icon_bomber():
    return [
        Spline([(51, 43), (56.5, 36.5), (63, 33), (67, 29.5)], stroke=P["ink"], sw=4.5),
        Poly(star4(70.5, 25, 8, 2.8), close=True, fill=P["amber"]),
        Disc(47, 58, 16.5, fill=P["ink"]),
        Disc(39.5, 50, 3.6, fill=P["paper"]),
    ]


def icon_dodge():
    return [
        Poly(diamond(35, 30, 9.5), close=True, fill=P["coral"]),
        Poly(diamond(64, 35, 7.5), close=True, fill=P["coral"]),
        Poly(diamond(48, 50.5, 5.5), close=True, fill=P["coral"]),
        Poly([(20, 62), (27, 70), (20, 78)], stroke=P["ink"], sw=3.8),
        Poly([(29, 64), (34, 70), (29, 76)], stroke=P["ink"], sw=3.8),
        Disc(50, 70, 7.5, fill=P["teal"]),
        Disc(47.7, 67.5, 2.0, fill=P["paper"]),
    ]


def icon_gomoku():
    out = []
    for x in (22, 36, 50, 64, 78):
        out.append(Poly([(x, 20), (x, 80)], stroke=P["grid"], sw=2.2))
    for y in (22, 36, 50, 64, 78):
        out.append(Poly([(20, y), (80, y)], stroke=P["grid"], sw=2.2))
    for x, y in [(22, 22), (36, 36), (50, 50), (64, 64), (78, 78)]:
        out.append(Disc(x, y, 5.6, fill=P["ink"]))
    return out


def icon_xiangqi():
    out = [Rect(21, 21, 58, 58, r=7, fill=XIANGQI_PAPER, stroke=P["ink"], sw=4)]
    for x in (36, 50, 64):
        out.append(Poly([(x, 27), (x, 46)], stroke=P["grid"], sw=2.2))
        out.append(Poly([(x, 54), (x, 73)], stroke=P["grid"], sw=2.2))
    for y in (36, 64):
        out.append(Poly([(27, y), (73, y)], stroke=P["grid"], sw=2.2))
    # 红方棋子
    out.append(Disc(50, 36, 9.5, fill=P["coral"]))
    out.append(Disc(50, 36, 6.2, stroke=XIANGQI_PAPER, sw=2.2))
    out.append(Disc(50, 36, 1.7, fill=XIANGQI_PAPER))
    # 黑方棋子
    out.append(Disc(64, 64, 7.5, fill=P["ink"]))
    out.append(Disc(64, 64, 4.6, stroke=XIANGQI_PAPER, sw=1.8))
    out.append(Disc(64, 64, 1.4, fill=XIANGQI_PAPER))
    return out


def icon_chess():
    pts = [(30, 54), (33, 44), (39, 37), (41, 27), (46, 34), (50, 26), (55, 33), (63, 37),
           (70, 46), (73, 58), (72, 70), (66, 76), (57, 77), (55, 68), (49, 63), (43, 62),
           (37, 65), (33, 60)]
    return [
        Spline(pts, close=True, fill=P["ink"]),
        Disc(44, 45, 2.4, fill=P["paper"]),
    ]


def icon_go():
    out = []
    for x in (34, 50, 66):
        out.append(Poly([(x, 22), (x, 78)], stroke=P["grid"], sw=2.2))
    for y in (34, 50, 66):
        out.append(Poly([(22, y), (78, y)], stroke=P["grid"], sw=2.2))
    out.append(Disc(66, 66, 2.4, fill=P["ink"]))
    out.append(Disc(34, 34, 9.5, fill=P["ink"]))
    out.append(Disc(30.8, 30.6, 2.4, fill=P["paper"]))
    out.append(Disc(50, 50, 9.5, fill=P["paper"], stroke=P["ink"], sw=3))
    return out


def icon_reversi():
    return [
        Disc(44, 46, 16.5, fill=P["paper"]),
        Poly(pie_pts(44, 46, 16.5, 90, 270), close=True, fill=P["ink"]),
        Disc(44, 46, 16.5, stroke=P["ink"], sw=3.5),
        Disc(71.5, 68, 8.5, fill=P["paper"]),
        Poly(pie_pts(71.5, 68, 8.5, -90, 90), close=True, fill=P["ink"]),
        Disc(71.5, 68, 8.5, stroke=P["ink"], sw=3),
    ]


def icon_chinese_checkers():
    return [
        Poly([(50, 20), (25, 63), (75, 63)], close=True, stroke=P["ink"], sw=4),
        Poly([(50, 80), (25, 37), (75, 37)], close=True, stroke=P["ink"], sw=4),
        Disc(50, 27, 5, fill=P["teal"]),
        Disc(28.7, 61, 5, fill=P["coral"]),
        Disc(71.3, 61, 5, fill=P["amber"]),
        Disc(50, 50, 2.6, fill=P["ink"]),
    ]


def icon_draughts():
    out = [Rect(26, 26, 48, 48, r=6, fill=DRAUGHTS_FILL, stroke=P["ink"], sw=3.5)]
    for i in range(4):
        for j in range(4):
            if (i + j) % 2 == 1:
                out.append(Rect(26 + 12 * i, 26 + 12 * j, 12, 12, r=1.8, fill=DRAUGHTS_DARK))
    out.append(Disc(32, 44, 6.2, fill=P["paper"], stroke=P["ink"], sw=2.6))
    out.append(Poly([(27.6, 46.4), (29.2, 41.4), (32, 44.2), (34.8, 41.4), (36.4, 46.4)],
                    stroke=P["amber"], sw=2.2))
    out.append(Poly([(27.6, 48.4), (36.4, 48.4)], stroke=P["amber"], sw=2.2))
    out.append(Disc(56, 44, 6.2, fill=P["ink"]))
    out.append(Disc(56, 44, 5.2, stroke=P["paper"], sw=1.5))
    return out


def icon_doudizhu():
    return [
        Rect(52, 41, 23, 35, r=5, fill=P["paper"], stroke=P["ink"], sw=3.2),
        Rect(25, 41, 23, 35, r=5, fill=P["paper"], stroke=P["ink"], sw=3.2),
        Rect(38.5, 30, 23, 35, r=5, fill=P["paper"], stroke=P["ink"], sw=3.2),
        Poly(diamond(50, 41, 6.5), close=True, fill=P["coral"]),
        Poly(diamond(43.5, 53.5, 3), close=True, fill=P["coral"]),
        Poly(diamond(56.5, 53.5, 3), close=True, fill=P["coral"]),
    ]


GAME_BUILDERS = {
    "reaction": icon_reaction, "color": icon_color, "math": icon_math, "memory": icon_memory,
    "snake": icon_snake, "tank": icon_tank, "bomber": icon_bomber, "dodge": icon_dodge,
    "gomoku": icon_gomoku, "xiangqi": icon_xiangqi, "chess": icon_chess, "go": icon_go,
    "reversi": icon_reversi, "chinese_checkers": icon_chinese_checkers,
    "draughts": icon_draughts, "doudizhu": icon_doudizhu,
}


def game_icon_shapes(gid):
    return [tile()] + GAME_BUILDERS[gid]()


# ---------------------------------------------------------------- 启动图标几何 ----------------------------------------------------------------


def pawn(cx, top, s, color):
    hr = 6.6 * s
    body = Poly([(cx - 3.4 * s, top + 12 * s), (cx - 7.4 * s, top + 24 * s),
                 (cx + 7.4 * s, top + 24 * s), (cx + 3.4 * s, top + 12 * s)],
                close=True, fill=color, stroke=color, sw=2.6 * s)
    base = Rect(cx - 8.8 * s, top + 24 * s, 17.6 * s, 5 * s, r=2.4 * s, fill=color)
    head = Disc(cx, top + hr, hr, fill=color)
    return [body, head, base]


def launcher_glyph(lid):
    if lid == "trio":
        fg = LAUNCHER_FG["trio"]
        return pawn(50, 40.6, 1.15, fg["white"]) + pawn(28.5, 47.9, 0.9, fg["amber"]) \
            + pawn(71.5, 47.9, 0.9, fg["coral"])
    if lid == "dice":
        fg = LAUNCHER_FG["dice"]
        return [
            Rect(29, 29, 42, 42, r=11, fill=fg["paper"], stroke=fg["ink"], sw=4),
            Disc(41, 41, 4.4, fill=fg["ink"]), Disc(59, 41, 4.4, fill=fg["ink"]),
            Disc(41, 59, 4.4, fill=fg["ink"]), Disc(59, 59, 4.4, fill=fg["ink"]),
            Disc(50, 50, 4.9, fill=fg["teal"]),
        ]
    if lid == "board":
        fg = LAUNCHER_FG["board"]
        out = [Rect(28, 28, 44, 44, r=4, fill=fg["paper"])]
        for i in range(4):
            for j in range(4):
                if (i + j) % 2 == 0:
                    out.append(Rect(28 + 11 * i, 28 + 11 * j, 11, 11, r=1.6, fill=fg["dark"]))
        out.append(Disc(33.5, 33.5, 6.5, fill=fg["amber"]))
        out.append(Disc(55.5, 55.5, 6.5, fill=fg["white"]))
        return out
    if lid == "mono":
        fg = LAUNCHER_FG["mono"]
        return [
            Disc(50, 50, 20.5, stroke=fg["ink"], sw=6.5),
            Disc(38.5, 50, 4.6, fill=fg["teal"]),
            Disc(50, 50, 4.6, fill=fg["teal"]),
            Disc(61.5, 50, 4.6, fill=fg["teal"]),
        ]
    raise KeyError(lid)


def launcher_preview_shapes(lid):
    """应用内预览 drawable / 传统 PNG：圆角方块底 + 前景。"""
    return [Rect(0, 0, 100, 100, r=22, fill=LAUNCHER_BG[lid])] + xf(launcher_glyph(lid), GLYPH_SCALE)


def launcher_foreground_shapes(lid):
    """自适应图标前景：108 画布、围绕 (54,54) 缩放，收在 66dp 安全区内。"""
    return xf(launcher_glyph(lid), GLYPH_SCALE, tx=4, ty=4)


# ---------------------------------------------------------------- 对比度 ----------------------------------------------------------------


def luminance(hex_color):
    h = hex_color.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
    f = lambda c: c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = f(r), f(g), f(b)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    if la < lb:
        la, lb = lb, la
    return (la + 0.05) / (lb + 0.05)


def contrast_checks():
    checks = []
    for tid, t in THEMES.items():
        c = t["colors"]
        checks += [
            (f"{tid} 正文/背景", c["text"], c["background"], 4.5, "文字"),
            (f"{tid} 正文/卡片", c["text"], c["surface"], 4.5, "文字"),
            (f"{tid} 次要/背景", c["muted"], c["background"], 4.5, "文字"),
            (f"{tid} 次要/卡片", c["muted"], c["surface"], 4.5, "文字"),
            (f"{tid} 按钮字/强调底", c["onAccent"], c["accent"], 4.5, "文字"),
            (f"{tid} 次级按钮字/浅底", c["accent"], c["soft"], 4.5, "文字"),
            (f"{tid} 强调图形/背景", c["accent"], c["background"], 3.0, "图形"),
            (f"{tid} 强调图形/卡片", c["accent"], c["surface"], 3.0, "图形"),
            (f"{tid} 警示图形/背景", c["danger"], c["background"], 3.0, "图形"),
            (f"{tid} 警示图形/卡片", c["danger"], c["surface"], 3.0, "图形"),
        ]
    checks += [
        ("图标 墨青/图标底", P["ink"], P["tile"], 3.0, "图形"),
        ("图标 海青/图标底", P["teal"], P["tile"], 3.0, "图形"),
        ("图标 琥珀/图标底", P["amber"], P["tile"], 3.0, "图形"),
        ("图标 珊瑚/图标底", P["coral"], P["tile"], 3.0, "图形"),
        ("图标 网格/图标底", P["grid"], P["tile"], 2.0, "装饰"),
        ("图标 网格/棋纸", P["grid"], XIANGQI_PAPER, 2.0, "装饰"),
        ("图标 白/海青", "#FFFFFF", P["teal"], 3.0, "图形"),
        ("图标 琥珀/白纸", P["amber"], "#FFFFFF", 3.0, "图形"),
        ("图标 珊瑚/白纸", P["coral"], "#FFFFFF", 3.0, "图形"),
        ("图标 红子/棋盘纸", P["coral"], XIANGQI_PAPER, 3.0, "图形"),
        ("跳棋深格/浅格", DRAUGHTS_DARK, DRAUGHTS_FILL, 3.0, "图形"),
        ("跳棋白子/深格", "#FFFFFF", DRAUGHTS_DARK, 3.0, "图形"),
        ("跳棋黑子描边/黑子", "#FFFFFF", P["ink"], 3.0, "图形"),
        ("启动 白/同乐青", "#FFFFFF", LAUNCHER_BG["trio"], 3.0, "图形"),
        ("启动 琥珀/同乐青", LAUNCHER_FG["trio"]["amber"], LAUNCHER_BG["trio"], 3.0, "图形"),
        ("启动 珊瑚/同乐青", LAUNCHER_FG["trio"]["coral"], LAUNCHER_BG["trio"], 3.0, "图形"),
        ("启动 白/骰子红", "#FFFFFF", LAUNCHER_BG["dice"], 3.0, "图形"),
        ("启动 海青点/白面", LAUNCHER_FG["dice"]["teal"], "#FFFFFF", 3.0, "图形"),
        ("启动 棋纸/深青底", LAUNCHER_FG["board"]["paper"], LAUNCHER_BG["board"], 3.0, "图形"),
        ("启动 琥珀/深格", LAUNCHER_FG["board"]["amber"], LAUNCHER_FG["board"]["dark"], 3.0, "图形"),
        ("启动 白/深格", LAUNCHER_FG["board"]["white"], LAUNCHER_FG["board"]["dark"], 3.0, "图形"),
        ("启动 墨青/极简底", LAUNCHER_FG["mono"]["ink"], LAUNCHER_BG["mono"], 3.0, "图形"),
        ("启动 海青点/极简底", LAUNCHER_FG["mono"]["teal"], LAUNCHER_BG["mono"], 3.0, "图形"),
    ]
    return checks


def audit_contrast():
    failures = []
    print("---- 对比度校验（WCAG 相对亮度） ----")
    for label, a, b, minimum, kind in contrast_checks():
        ratio = contrast(a, b)
        ok = ratio >= minimum
        if not ok:
            failures.append(f"{label}: {ratio:.2f} < {minimum}")
        print(f"  [{'OK ' if ok else 'FAIL'}] {kind} {label:28s} {ratio:5.2f} (≥{minimum})")
    return failures


# ---------------------------------------------------------------- 生成 ----------------------------------------------------------------


def write_tokens():
    doc = {"themes": {}}
    for tid, t in THEMES.items():
        colors = {k: t["colors"][k] for k in COLOR_KEYS}
        colors["dark"] = t["colors"]["dark"]
        doc["themes"][tid] = {"name": t["name"], "colors": colors}
    text = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
    DESIGN_DIR.mkdir(parents=True, exist_ok=True)
    (DESIGN_DIR / "tokens.json").write_text(text, encoding="utf-8")
    ASSETS_DESIGN.mkdir(parents=True, exist_ok=True)
    (ASSETS_DESIGN / "tokens.json").write_text(text, encoding="utf-8")


def write_game_svgs():
    DESIGN_ICONS.mkdir(parents=True, exist_ok=True)
    WEB_ICONS.mkdir(parents=True, exist_ok=True)
    for gid, title in GAMES:
        svg = build_svg(title, game_icon_shapes(gid))
        (DESIGN_ICONS / f"{gid}.svg").write_text(svg, encoding="utf-8")
        (WEB_ICONS / f"{gid}.svg").write_text(svg, encoding="utf-8")


def write_web_pngs():
    for gid, _ in GAMES:
        render(game_icon_shapes(gid), WEB_PNG_SIZE).save(WEB_ICONS / f"{gid}.png")
    trio_svg = build_svg("同乐盒 PartyGame · 同乐三人", launcher_preview_shapes("trio"))
    (WEB_ICONS / "trio.svg").write_text(trio_svg, encoding="utf-8")
    render(launcher_preview_shapes("trio"), 192).save(WEB_ICONS / "trio-192.png")
    render(launcher_preview_shapes("trio"), 512).save(WEB_ICONS / "trio-512.png")


def write_android():
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    for gid, _ in GAMES:
        (DRAWABLE / f"ic_game_{gid}.xml").write_text(
            vd_file(game_icon_shapes(gid), 96, 100), encoding="utf-8")
    for lid, _ in LAUNCHERS:
        bg = Rect(0, 0, 108, 108, fill=LAUNCHER_BG[lid])
        (DRAWABLE / f"ic_launcher_{lid}.xml").write_text(
            vd_file(launcher_preview_shapes(lid), 96, 100), encoding="utf-8")
        (DRAWABLE / f"ic_launcher_{lid}_background.xml").write_text(
            vd_file([bg], 108, 108), encoding="utf-8")
        (DRAWABLE / f"ic_launcher_{lid}_foreground.xml").write_text(
            vd_file(launcher_foreground_shapes(lid), 108, 108), encoding="utf-8")
        MIPMAP_ANY.mkdir(parents=True, exist_ok=True)
        (MIPMAP_ANY / f"ic_launcher_{lid}.xml").write_text(adaptive_icon_file(lid), encoding="utf-8")
        for dpi, size in MIPMAP_DPIS.items():
            d = APP_RES / f"mipmap-{dpi}"
            d.mkdir(parents=True, exist_ok=True)
            render(launcher_preview_shapes(lid), size).save(d / f"ic_launcher_{lid}.png")


# ---------------------------------------------------------------- 预览拼图 ----------------------------------------------------------------

FONT_CJK = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
FONT_MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
_font_cache = {}
_cjk_index = None


def cjk_index():
    # 本机 Bold/Medium 的 ttc 面已被替换为非 CJK 字体，这里只认 Regular 中确实带 SC 的面，
    # 加粗在绘制时用同色描边合成。
    global _cjk_index
    if _cjk_index is None:
        _cjk_index = 0
        for i in range(12):
            try:
                f = ImageFont.truetype(FONT_CJK, 12, index=i)
                name = f.getname()[0]
                if "Noto Sans CJK" in name and "SC" in name:
                    _cjk_index = i
                    break
            except Exception:
                pass
    return _cjk_index


def font(size, mono=False):
    key = (size, mono)
    if key not in _font_cache:
        _font_cache[key] = (ImageFont.truetype(FONT_MONO, size) if mono
                            else ImageFont.truetype(FONT_CJK, size, index=cjk_index()))
    return _font_cache[key]


def faux_stroke(size, weight):
    if weight == "bold":
        return max(1, round(size * 0.05))
    if weight == "medium":
        return round(size * 0.028) if size >= 18 else 0
    return 0


class Sheet:
    def __init__(self, w, h, bg):
        self.w = w
        self.max_h = h
        self.img = Image.new("RGBA", (w, h), bg)
        self.d = ImageDraw.Draw(self.img)

    def box(self, x, y, w, h, r=0, fill=None, outline=None, width=1):
        self.d.rounded_rectangle([x, y, x + w, y + h], radius=r, fill=fill, outline=outline, width=width)

    def text(self, x, y, s, size, fill, weight="regular", mono=False, anchor="la"):
        self.d.text((x, y), s, font=font(size, mono), fill=fill, anchor=anchor,
                    stroke_width=faux_stroke(size, weight), stroke_fill=fill)

    def paste(self, im, x, y):
        self.img.alpha_composite(im, (x, y))

    def crop_to(self, bottom):
        self.img = self.img.crop((0, 0, self.w, min(bottom, self.max_h)))
        return self.img


def build_preview():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    W = 1560
    M = 28
    ink = P["ink"]
    muted = "#5F736D"
    sheet = Sheet(W, 2800, "#EEF1EF")
    y = 28

    sheet.text(M, y, "同乐盒 PartyGame · 主题与图标设计预览", 42, ink, "bold")
    y += 56
    sheet.text(M, y, "海盐青 / 午夜蓝 / 樱桃红 / 原木棋室 · 16 游戏图标 + 4 启动图标 · "
                     "正文与次要文字 ≥4.5:1，关键图形 ≥3:1（脚本自动校验）", 20, muted)
    y += 46

    # ---- 四主题色卡 ----
    card_w = (W - 2 * M - 3 * 16) // 4
    card_h = 480
    core_rows = [("background", "底色"), ("surface", "卡片"), ("text", "正文"),
                 ("muted", "次要"), ("accent", "强调"), ("soft", "浅底")]
    for i, (tid, t) in enumerate(THEMES.items()):
        c = t["colors"]
        x = M + i * (card_w + 16)
        sheet.box(x, y, card_w, card_h, r=18, fill=c["surface"], outline=c["border"], width=2)
        px, py = x + 20, y + 16
        sheet.text(px, py, t["name"], 30, c["text"], "bold")
        sheet.text(x + card_w - 20, py + 10, tid, 16, c["muted"], mono=True, anchor="ra")
        sheet.text(px, py + 46, "正文示例 Aa · 三人同乐", 19, c["text"])
        sheet.text(px, py + 76, "次要说明 Aa · 房间口令 8421", 17, c["muted"])
        r1 = contrast(c["text"], c["background"])
        r2 = contrast(c["muted"], c["background"])
        sheet.text(px, py + 106, f"对比度：正文 {r1:.1f}:1 · 次要 {r2:.1f}:1", 14, c["muted"])
        sheet.text(px, py + 138, "核心色", 15, c["text"], "bold")
        chip_w, chip_h = 150, 36
        for k, (key, zh) in enumerate(core_rows):
            cxi = px + (k % 2) * (chip_w + 12)
            cyi = py + 164 + (k // 2) * 72
            sheet.box(cxi, cyi, chip_w, chip_h, r=9, fill=c[key], outline=c["border"])
            sheet.text(cxi + chip_w / 2, cyi + chip_h + 5, f"{zh} {c[key]}", 13,
                       c["muted"], anchor="ma")
        fy = py + 390
        sheet.box(px, fy, 52, 26, r=8, fill=c["border"])
        sheet.box(px + 62, fy, 28, 26, r=8, fill=c["boardLight"])
        sheet.box(px + 92, fy, 28, 26, r=8, fill=c["boardDark"])
        sheet.box(px + 130, fy, 52, 26, r=8, fill=c["danger"])
        sheet.text(px, fy + 36, f"border {c['border']} · danger {c['danger']}", 12, c["muted"], mono=True)
        sheet.text(px, fy + 54, f"board {c['boardLight']} / {c['boardDark']}", 12, c["muted"], mono=True)
    y += card_h + 26

    # ---- 16 游戏图标 ----
    panel_h = 22 + 40 + 22 + 2 * 196 + 20
    sheet.box(M, y, W - 2 * M, panel_h, r=20, fill="#FFFFFF")
    px, py = M + 24, y + 20
    sheet.text(px, py, "16 个游戏图标（Android vector / Web SVG / Web PNG 同源几何）", 26, ink, "bold")
    sheet.text(px, py + 40, "100 × 100 网格 · 主描边 7 · 圆角端点 · 固定调色板："
                            "墨青 #153D3A · 海青 #0E8175 · 琥珀 #B87A1E · 珊瑚 #C2483D", 16, muted)
    grid_y = py + 66
    cell_w = (W - 2 * M - 48) // 8
    for idx, (gid, title) in enumerate(GAMES):
        cx = px + (idx % 8) * cell_w
        cy = grid_y + (idx // 8) * 196
        icon = render(game_icon_shapes(gid), 116)
        sheet.paste(icon, int(cx + (cell_w - 116) / 2), int(cy))
        sheet.text(cx + cell_w / 2, cy + 128, title, 20, ink, "medium", anchor="ma")
        sheet.text(cx + cell_w / 2, cy + 156, gid, 13, "#7C8F88", mono=True, anchor="ma")
    y += panel_h + 26

    # ---- 启动图标（深色底） ----
    launcher_h = 22 + 36 + 24 + 156 + 22
    sheet.box(M, y, W - 2 * M, launcher_h, r=20, fill=THEMES["midnight"]["colors"]["background"])
    px, py = M + 24, y + 20
    sheet.text(px, py, "4 个桌面启动图标（自适应图标 · 前景收在 66dp 安全区）", 24,
               THEMES["midnight"]["colors"]["text"], "bold")
    sheet.text(px, py + 36, "ic_launcher_trio 同乐三人 · ic_launcher_dice 幸运骰 · "
                            "ic_launcher_board 棋盘 · ic_launcher_mono 极简 —— 深色底可见性检查", 15,
               THEMES["midnight"]["colors"]["muted"])
    lcell = (W - 2 * M - 48) // 4
    for idx, (lid, lname) in enumerate(LAUNCHERS):
        cx = px + idx * lcell
        cy = py + 66
        icon = render(launcher_preview_shapes(lid), 124)
        sheet.paste(icon, int(cx + (lcell - 124) / 2), int(cy))
        sheet.text(cx + lcell / 2, cy + 134, f"{lname} · ic_launcher_{lid}", 17,
                   THEMES["midnight"]["colors"]["text"], anchor="ma")
    y += launcher_h + 26

    # ---- 小尺寸检查 ----
    size_h = 22 + 36 + 24 + 104 + 40
    sheet.box(M, y, W - 2 * M, size_h, r=20, fill="#FFFFFF")
    px, py = M + 24, y + 20
    sheet.text(px, py, "小尺寸辨识度（96 / 64 / 48 / 32 px）", 24, ink, "bold")
    picks = ["reaction", "memory", "chess", "go", "doudizhu"]
    cell = (W - 2 * M - 48) // 5
    for idx, gid in enumerate(picks):
        cx = px + idx * cell
        cy = py + 58
        xoff = cx
        for size in (96, 64, 48, 32):
            icon = render(game_icon_shapes(gid), size)
            sheet.paste(icon, int(xoff), int(cy + (96 - size)))
            xoff += size + 14
        title = dict(GAMES)[gid]
        sheet.text(cx, cy + 104, f"{title} {gid}", 16, ink, anchor="la")
    y += size_h + 20

    sheet.text(M, y + 6, "源文件：design/tokens.json · design/icons/*.svg —— 产物："
                         "app/src/main/res/drawable/ic_game_*.xml · web/public/icons/*", 15, muted)
    y += 34

    img = sheet.crop_to(y + M)
    img.convert("RGB").save(PREVIEW)
    print(f"预览拼图: {PREVIEW.relative_to(ROOT)} ({img.width}x{img.height})")


# ---------------------------------------------------------------- 校验 ----------------------------------------------------------------


def expected_outputs():
    files = [
        (DESIGN_DIR / "tokens.json", None),
        (ASSETS_DESIGN / "tokens.json", None),
        (PREVIEW, None),
        (WEB_ICONS / "trio.svg", None),
        (WEB_ICONS / "trio-192.png", (192, 192)),
        (WEB_ICONS / "trio-512.png", (512, 512)),
    ]
    for gid, _ in GAMES:
        files += [
            (DESIGN_ICONS / f"{gid}.svg", None),
            (WEB_ICONS / f"{gid}.svg", None),
            (WEB_ICONS / f"{gid}.png", (WEB_PNG_SIZE, WEB_PNG_SIZE)),
            (DRAWABLE / f"ic_game_{gid}.xml", None),
        ]
    for lid, _ in LAUNCHERS:
        files += [
            (DRAWABLE / f"ic_launcher_{lid}.xml", None),
            (DRAWABLE / f"ic_launcher_{lid}_background.xml", None),
            (DRAWABLE / f"ic_launcher_{lid}_foreground.xml", None),
            (MIPMAP_ANY / f"ic_launcher_{lid}.xml", None),
        ]
        for dpi, size in MIPMAP_DPIS.items():
            files.append((APP_RES / f"mipmap-{dpi}" / f"ic_launcher_{lid}.png", (size, size)))
    return files


def verify():
    problems = []
    files = expected_outputs()
    for path, size in files:
        if not path.exists():
            problems.append(f"缺失文件: {path.relative_to(ROOT)}")
            continue
        if path.stat().st_size == 0:
            problems.append(f"空文件: {path.relative_to(ROOT)}")
            continue
        if path.suffix == ".png" and size is not None:
            with Image.open(path) as im:
                if im.size != size:
                    problems.append(f"PNG 尺寸错误: {path.relative_to(ROOT)} {im.size} != {size}")

    # tokens：结构必须匹配 UiStyle.kt 的读取方式
    tokens_path = DESIGN_DIR / "tokens.json"
    data = json.loads(tokens_path.read_text(encoding="utf-8"))
    themes = data.get("themes", {})
    if set(themes) != set(THEMES):
        problems.append(f"tokens.json 主题 id 不符: {sorted(themes)}")
    for tid, t in themes.items():
        colors = t.get("colors", {})
        for key in COLOR_KEYS:
            value = colors.get(key)
            if not isinstance(value, str) or len(value) != 7 or not value.startswith("#"):
                problems.append(f"tokens.json {tid}.{key} 不是 #RRGGBB: {value!r}")
        if not isinstance(colors.get("dark"), bool):
            problems.append(f"tokens.json {tid}.dark 不是布尔值")
    if (ASSETS_DESIGN / "tokens.json").read_text(encoding="utf-8") != tokens_path.read_text(encoding="utf-8"):
        problems.append("assets/design/tokens.json 与 design/tokens.json 不一致")

    # SVG 可被 XML 解析
    for gid, _ in GAMES:
        for path in (DESIGN_ICONS / f"{gid}.svg", WEB_ICONS / f"{gid}.svg"):
            try:
                root = ET.parse(path).getroot()
                if not root.tag.endswith("svg"):
                    problems.append(f"SVG 根节点异常: {path.relative_to(ROOT)}")
            except ET.ParseError as exc:
                problems.append(f"SVG 解析失败: {path.relative_to(ROOT)} {exc}")
    try:
        ET.parse(WEB_ICONS / "trio.svg")
    except ET.ParseError as exc:
        problems.append(f"SVG 解析失败: trio.svg {exc}")

    # Android XML：可解析且含路径数据
    android_xmls = [DRAWABLE / f"ic_game_{gid}.xml" for gid, _ in GAMES]
    android_xmls += [DRAWABLE / f"ic_launcher_{lid}{suffix}.xml"
                     for lid, _ in LAUNCHERS for suffix in ("", "_background", "_foreground")]
    android_xmls += [MIPMAP_ANY / f"ic_launcher_{lid}.xml" for lid, _ in LAUNCHERS]
    ns = "{http://schemas.android.com/apk/res/android}"
    for path in android_xmls:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            problems.append(f"Android XML 解析失败: {path.relative_to(ROOT)} {exc}")
            continue
        if root.tag.endswith("vector"):
            paths = [el for el in root.iter() if el.tag.endswith("path")]
            if not paths:
                problems.append(f"vector 无 path: {path.relative_to(ROOT)}")
            for el in paths:
                if not el.get(f"{ns}pathData"):
                    problems.append(f"path 缺 pathData: {path.relative_to(ROOT)}")
        elif root.tag.endswith("adaptive-icon"):
            tags = {el.tag.split("}")[-1] for el in root}
            if {"background", "foreground"} - tags:
                problems.append(f"adaptive-icon 缺层: {path.relative_to(ROOT)}")

    # 预览图尺寸
    with Image.open(PREVIEW) as im:
        if im.width != 1560:
            problems.append(f"预览图宽度异常: {im.size}")

    # 对比度
    failures = audit_contrast()
    problems += [f"对比度不足: {f}" for f in failures]

    print("---- 产物核对 ----")
    print(f"  预期文件 {len(files)} 个；缺失/异常 {len(problems)} 个")
    if problems:
        print("---- 校验未通过 ----")
        for p in problems:
            print(f"  ✗ {p}")
        sys.exit(1)
    print("  ✓ 全部文件存在、SVG/Android XML 可解析、PNG 尺寸正确、对比度达标")


def main():
    print("== 同乐盒 PartyGame · 设计资源生成 ==")
    write_tokens()
    write_game_svgs()
    write_web_pngs()
    write_android()
    build_preview()
    verify()
    print("完成。")


if __name__ == "__main__":
    main()
