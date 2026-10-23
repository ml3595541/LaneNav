#!/usr/bin/env python3
"""生成 LaneNav 两个 App 的 launcher 图标。

设计取自参考图：白色圆环 + 淡色圆盘 + 饱和色 "GPS" 字。
手机端 = 红色系，盒子端 = 蓝色系。

自适应图标（API26+，本项目 minSdk 28 全覆盖）：
  前景 = 108dp 画布，内容收在中心 72dp 安全区内，系统可按 OEM 形状裁切。
同时输出传统方形 PNG 作为兜底。
"""
import os
from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
SS = 8  # 超采样倍数（抗锯齿）

PALETTES = {
    "phone": {
        "bg": (250, 226, 223),      # 自适应底：淡红
        "legacy_bg": (255, 255, 255),  # 传统图标：白底
        "ring": (255, 255, 255),
        "disc": (250, 222, 220),    # 淡红圆盘
        "text": (217, 58, 43),      # 强红字
    },
    "box": {
        "bg": (219, 231, 244),      # 自适应底：淡蓝
        "legacy_bg": (255, 255, 255),
        "ring": (255, 255, 255),
        "disc": (219, 231, 244),
        "text": (56, 167, 213),
    },
}

# 传统图标（方形，内容占比较大）
LEGACY = {  # density: px
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}
# 自适应前景（108dp 画布）
ADAPTIVE = {
    "mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432,
}


def render(size_px, pal, content_ratio, bg="none", rounded=False, disc=True):
    """content_ratio: 圆环外径 / 画布尺寸。
    bg: "none" 透明 | "legacy" 白底 | "adaptive" 淡色底
    """
    W = size_px * SS
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    if bg == "legacy":
        color = pal["legacy_bg"]
        if rounded:
            d.ellipse([0, 0, W - 1, W - 1], fill=color + (255,))
        else:
            d.rectangle([0, 0, W, W], fill=color + (255,))
    elif bg == "adaptive":
        d.rectangle([0, 0, W, W], fill=pal["bg"] + (255,))

    cx = cy = W / 2
    r_ring = W * content_ratio / 2          # 圆环外径
    thickness = r_ring * 0.115              # 环厚
    r_disc = r_ring - thickness

    # 圆盘（淡色）；自适应图标由 background 层负责底色，不再画盘
    if disc:
        d.ellipse([cx - r_disc, cy - r_disc, cx + r_disc, cy + r_disc],
                  fill=pal["disc"] + (255,))

    # 文字：按实际墨迹包围盒居中
    target_h = r_disc * 0.92                # 文字墨迹高度
    font = ImageFont.truetype(FONT, int(target_h * 1.9))
    text = "GPS"
    bbox = d.textbbox((0, 0), text, font=font)
    ink_w = bbox[2] - bbox[0]
    ink_h = bbox[3] - bbox[1]

    # 缩放到目标墨迹高度
    scale = target_h / ink_h
    font = ImageFont.truetype(FONT, int(font.size * scale))
    bbox = d.textbbox((0, 0), text, font=font)
    ink_w = bbox[2] - bbox[0]
    ink_h = bbox[3] - bbox[1]

    # 收进圆盘（留 8% 边距）
    max_w = r_disc * 1.72 * 0.92
    if ink_w > max_w:
        font = ImageFont.truetype(FONT, int(font.size * max_w / ink_w))
        bbox = d.textbbox((0, 0), text, font=font)
        ink_w = bbox[2] - bbox[0]
        ink_h = bbox[3] - bbox[1]

    x = cx - ink_w / 2 - bbox[0]
    y = cy - ink_h / 2 - bbox[1]
    d.text((x, y), text, font=font, fill=pal["text"] + (255,))

    return img.resize((size_px, size_px), Image.LANCZOS)


def write(res_dir, name, img):
    os.makedirs(res_dir, exist_ok=True)
    img.save(os.path.join(res_dir, name), "PNG", optimize=True)


def gen(side, root):
    pal = PALETTES[side]
    for density, px in LEGACY.items():
        out = os.path.join(root, f"mipmap-{density}")
        # 方形（自适应设备不用，仅兜底）：白底 + 圆盘
        write(out, "ic_launcher.png", render(px, pal, 0.90, bg="legacy"))
        # 圆形：整圆填充淡色
        write(out, "ic_launcher_round.png",
              render(px, pal, 1.0, bg="legacy", rounded=True))

    for density, px in ADAPTIVE.items():
        out = os.path.join(root, f"mipmap-{density}")
        # 前景：透明底 + 文字收在 72/108 安全区
        write(out, "ic_launcher_foreground.png",
              render(px, pal, 70 / 108, bg="none", disc=False))

    # 自适应图标描述
    anydpi = os.path.join(root, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_bg" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'''
    for f in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, f), "w") as fh:
            fh.write(xml)

    # 背景色资源
    values = os.path.join(root, "values")
    os.makedirs(values, exist_ok=True)
    colors = os.path.join(values, "ic_launcher_colors.xml")
    rgb = pal["bg"]
    with open(colors, "w") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
        fh.write(f'    <color name="ic_launcher_bg">#{rgb[0]:02X}{rgb[1]:02X}{rgb[2]:02X}</color>\n')
        fh.write('</resources>\n')

    print(f"{side}: 生成完成 → {root}")


if __name__ == "__main__":
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    gen("phone", os.path.join(base, "phone/src/main/res"))
    gen("box", os.path.join(base, "box/src/main/res"))
