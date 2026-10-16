"""生成一个用于测试的 .tendies 海报包（iOS PosterBoard / CoreAnimation 格式）。

这既是导入器的测试素材，也是将来"导出器"的格式参照：
一个最小可用的包只需要
  descriptors/<UUID>/versions/1/contents/<名称>.wallpaper/
      ├ <名称>_Background.ca/{index.xml, main.caml, assets/*.png}
      ├ <名称>_Floating.ca/{index.xml, main.caml, assets/*.png}
      └ Wallpaper.plist                     ← wantsDeviceMotion: true

用法: python make_test_pack.py <输出 .tendies 路径>
"""
import io
import plistlib
import sys
import uuid
import zipfile

from PIL import Image, ImageDraw

DESIGN_W, DESIGN_H, SCALE = 390.0, 844.0, 3
CSS = 3  # 3x 素材


def caml(layers_xml: str, rules_xml: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<caml xmlns="http://www.apple.com/CoreAnimation/1.0">
<CALayer name="Root Layer" bounds="0 0 {DESIGN_W:g} {DESIGN_H:g}" position="{DESIGN_W / 2:g} {DESIGN_H / 2:g}" opacity="1">
  <sublayers>
{layers_xml}
  </sublayers>
</CALayer>
<style>
<wallpaperBackgroundAssetNames type="NSArray"/>
<wallpaperFloatingAssetNames type="NSArray"/>
<wallpaperParallaxGroups type="NSArray">
{rules_xml}
</wallpaperParallaxGroups>
</style>
</caml>
"""


def layer(name, x, y, w, h, asset=None, opacity=1.0) -> str:
    contents = f'<contents><CGImage name="{asset}"/></contents>' if asset else ""
    return (
        f'    <CALayer name="{name}" bounds="0 0 {w:g} {h:g}" position="{x:g} {y:g}" '
        f'opacity="{opacity:g}">\n      {contents}\n      <sublayers/>\n    </CALayer>'
    )


def rule(axis, name, lo, hi) -> str:
    return (
        f"  <NSDictionary>\n"
        f'    <axis type="string" value="{axis}"/>\n'
        f'    <keyPath type="string" value="transform.translation.{axis}"/>\n'
        f'    <layerName type="string" value="{name}"/>\n'
        f'    <mapMinTo type="integer" value="{lo}"/>\n'
        f'    <mapMaxTo type="integer" value="{hi}"/>\n'
        f'    <title type="string" value="test"/>\n'
        f'    <view type="string" value="Floating"/>\n'
        f"  </NSDictionary>"
    )


def png_bytes(im: Image.Image) -> bytes:
    b = io.BytesIO()
    im.save(b, "PNG")
    return b.getvalue()


def make_bg() -> Image.Image:
    w, h = int(DESIGN_W * SCALE), int(DESIGN_H * SCALE)
    im = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(im)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=(int(12 + 30 * t), int(30 + 55 * t), int(70 + 90 * t)))
    # 几条横向参照带：便于肉眼判断"哪一层在动"
    for i in range(1, 6):
        y = int(h * i / 6)
        d.line([(0, y), (w, y)], fill=(255, 255, 255), width=4)
    return im


def make_disc(color, r, mark=True) -> Image.Image:
    im = Image.new("RGBA", (r * 2, r * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse([0, 0, r * 2 - 1, r * 2 - 1], fill=color)
    if mark:
        d.line([(r * 0.4, r), (r * 1.6, r)], fill=(0, 0, 0, 140), width=max(2, r // 12))
        d.line([(r, r * 0.4), (r, r * 1.6)], fill=(0, 0, 0, 140), width=max(2, r // 12))
    return im


def make_rings(size=900) -> Image.Image:
    """同心环 + 中心十字：在窗口内平移时，位移一眼可见。"""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    cx = cy = size // 2
    colors = [(255, 214, 102, 255), (102, 204, 255, 255), (255, 122, 89, 255), (140, 255, 170, 255)]
    for i, r in enumerate(range(size // 2, 0, -size // 12)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=colors[i % len(colors)])
    d.line([(cx - size // 8, cy), (cx + size // 8, cy)], fill=(0, 0, 0, 200), width=size // 90)
    d.line([(cx, cy - size // 8), (cx, cy + size // 8)], fill=(0, 0, 0, 200), width=size // 90)
    return im


def window_layer() -> str:
    """一个"裁剪窗口"：150x150 的 masksToBounds 层，里面放一张 300x300 的大图。"""
    return (
        '    <CATransformLayer name="win" bounds="0 0 150 150" position="300 600" opacity="1">\n'
        '      <sublayers>\n'
        '        <CALayer name="winclip" masksToBounds="1" bounds="0 0 150 150" position="75 75" opacity="1">\n'
        '          <sublayers>\n'
        '            <CALayer name="winimg" bounds="0 0 300 300" position="150 150" opacity="1">\n'
        '              <contents><CGImage name="window.png"/></contents>\n'
        '              <sublayers/>\n'
        '            </CALayer>\n'
        '          </sublayers>\n'
        '        </CALayer>\n'
        '      </sublayers>\n'
        '    </CATransformLayer>'
    )


def main(out_path: str) -> None:
    name = "9999.Test"
    assets = {
        "bg.png": png_bytes(make_bg()),
        "moon.png": png_bytes(make_disc((217, 193, 115, 255), 140)),
        "sun.png": png_bytes(make_disc((255, 106, 61, 255), 150)),
        "fg.png": png_bytes(make_disc((61, 139, 255, 235), 190)),
        "window.png": png_bytes(make_rings()),
    }

    bg_layers = "\n".join([
        layer("bg", DESIGN_W / 2, DESIGN_H / 2, DESIGN_W, DESIGN_H, "bg.png"),
        layer("moon", 110, 190, 94, 94, "moon.png"),
        window_layer(),
    ])
    bg_rules = "\n".join([
        rule("x", "moon", -500, 500),
        rule("y", "moon", -500, 500),
        # 窗口自身小幅同向、内容大幅反向 —— 社区包里的"望远镜/纵深"正是这么做的
        rule("x", "win", -120, 120),
        rule("y", "win", -120, 120),
        rule("x", "winimg", 500, -500),
        rule("y", "winimg", 500, -500),
    ])

    fg_layers = "\n".join([
        layer("sun", 285, 690, 100, 100, "sun.png"),
        layer("fg", DESIGN_W / 2, 300, 127, 127, "fg.png"),
    ])
    fg_rules = "\n".join([
        # 反向：mapMin > mapMax —— "双透视"的负号就来自这里
        rule("x", "sun", 500, -500),
        rule("y", "sun", 500, -500),
        rule("x", "fg", -500, 500),
        rule("y", "fg", -500, 500),
    ])

    index_xml = ('<?xml version="1.0" encoding="UTF-8"?>\n'
                 '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
                 '<plist version="1.0">\n<dict>\n  <key>rootDocument</key>\n  <string>main.caml</string>\n</dict>\n</plist>\n')

    wallpaper_plist = {
        "preferredProminentColor": {"default": "#FFFFFF"},
        "contentVersion": 1.07,
        "assets": {"lockAndHome": {"default": {
            "assetFileName": name + ".ca",
            "identifier": 9999,
            "name": "Test",
            "type": "ParameterizedCA",
        }}},
        "version": 6,
        "preferredTitleColor": ["#FFFFFF02"],
        "logicalScreenClass": "any",
        "appearanceAware": False,
        "identifier": 9999,
        "wantsDeviceMotion": True,
        "name": "Test",
    }

    desc = uuid.uuid4().hex.upper()
    base = f"descriptors/{desc}/versions/1/contents/{name}-390w-844h@3x~iphone.wallpaper"

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        for bundle, layers_xml, rules_xml, wanted in (
            (f"{name}_Background-390w-844h@3x~iphone", bg_layers, bg_rules, ["bg.png", "moon.png", "window.png"]),
            (f"{name}_Floating-390w-844h@3x~iphone", fg_layers, fg_rules, ["sun.png", "fg.png"]),
        ):
            z.writestr(f"{base}/{bundle}.ca/index.xml", index_xml)
            z.writestr(f"{base}/{bundle}.ca/main.caml", caml(layers_xml, rules_xml))
            for a in wanted:
                z.writestr(f"{base}/{bundle}.ca/assets/{a}", assets[a])
        z.writestr(f"{base}/Wallpaper.plist", plistlib.dumps(wallpaper_plist, fmt=plistlib.FMT_XML))
    print("已生成:", out_path)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "test.tendies")
