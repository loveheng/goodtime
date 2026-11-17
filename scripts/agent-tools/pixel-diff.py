#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
toolbox-script
format: v1
name: pixel-diff
summary: 像素基线对拍——图形区 diff 比例 ≤0.5%+抗锯齿掩膜，文字区结构断言（§9.1 像素 diff 管线）
trigger: manual
platform: any
self-test: --self-test
"""
import argparse
import json
import struct
import sys
import zlib

EXIT_OK, EXIT_FAIL, EXIT_FAULT = 0, 1, 2
THRESHOLD = 0.005  # 图形区差异像素占比 ≤0.5%（§9.1 像素 diff 管线口径）


def die(msg, remedy=""):
    print("[remedy] " + remedy if remedy else msg, file=sys.stderr) if remedy else print(msg, file=sys.stderr)
    sys.exit(EXIT_FAULT)


def read_png(path):
    """解码 8-bit non-interlaced RGB/RGBA PNG → (width, height, channels, bytes)。仅标准库。"""
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        die(f"非 PNG 文件: {path}", "确认采图产物为 PNG（capture.dart 产物应为 PNG）")
    pos = 8
    width = height = bitdepth = colortype = interlace = None
    idat = bytearray()
    while pos < len(data):
        (length, ctype) = struct.unpack(">I4s", data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            width, height, bitdepth, colortype, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
        elif ctype == b"IDAT":
            idat.extend(chunk)
        elif ctype == b"IEND":
            break
        pos += 12 + length
    if bitdepth != 8 or interlace != 0:
        die(f"不支持的 PNG 形态: {path} (depth={bitdepth} interlace={interlace})",
            "capture 产物应为 8-bit non-interlaced；检查真机截图管线")
    channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(colortype)
    if channels is None:
        die(f"不支持的颜色类型: colortype={colortype} ({path})")
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    out = bytearray(stride * height)
    prev = bytearray(stride)
    p = 0
    for y in range(height):
        ftype = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if ftype == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return width, height, channels, out


def diff_region(w, h, ch, a, b, rects=None):
    """逐像素比对。返回 (solid_diff, aa_diff, total)；solid=与 4 邻域任一同差的实心差异，
    aa=孤立差异（抗锯齿掩膜，豁免）。rects 非空时返回逐 rect 独立统计。"""
    solid = aa = 0
    per_rect = []
    rowdiff = [False] * w
    for idx, (x0, y0, x1, y1) in enumerate(rects or [(-1, -1, w, h)]):
        s = aa_ = 0
        for y in range(max(y0, 0), min(y1, h)):
            row = y * w
            for x in range(max(x0, 0), min(x1, w)):
                i = (row + x) * ch
                if a[i:i + ch] != b[i:i + ch]:
                    neigh = False
                    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                        nx, ny = x + dx, y + dy
                        if 0 <= nx < w and 0 <= ny < h:
                            j = (ny * w + nx) * ch
                            if a[j:j + ch] != b[j:j + ch]:
                                neigh = True
                                break
                    if neigh:
                        s += 1
                    else:
                        aa_ += 1
        solid += s
        aa += aa_
        per_rect.append({"rect": [x0, y0, x1, y1], "solid": s, "aa": aa_})
    return solid, aa, w * h, per_rect


def run(base, cur, text_regions=None, threshold=THRESHOLD):
    w1, h1, c1, a = read_png(base)
    w2, h2, c2, b = read_png(cur)
    if (w1, h1, c1) != (w2, h2, c2):
        return {"status": "FAIL", "severity": "error",
                "message": f"尺寸/通道不一致: {base}({w1}x{h1}c{c1}) vs {cur}({w2}x{h2}c{c2})",
                "remedy": "确认同介质同分辨率采图（原生侧同介质真机采图口径）"}
    rects = [tuple(r) for r in (text_regions or [])]
    solid, aa, total, per_rect = diff_region(w1, h1, c1, a, b, rects)
    ratio = solid / total if total else 0.0
    report = {"base": base, "current": cur, "total": total, "solidDiff": solid,
              "antiAliased": aa, "ratio": round(ratio, 6), "threshold": threshold,
              "textRegions": per_rect if rects else None}
    if solid > threshold * total:
        report.update({"status": "FAIL", "severity": "error",
                       "message": f"图形区差异 {solid}/{total}={ratio:.4%} > 阈值 {threshold:.2%}",
                       "remedy": "逐块排查 UI 回归或重采（同介质口径）"})
        return report
    report.update({"status": "OK", "severity": "info",
                   "message": f"图形区差异 {ratio:.4%} ≤ 阈值（抗锯齿豁免 {aa}px）"})
    return report


class SelfTest:
    """金丝雀：合成 4x4 RGBA 图，一对全同 + 一对实心坏样本，证明能抓到坏。"""

    @staticmethod
    def make(path, color):
        w = h = 4
        raw = bytearray()
        for _ in range(h):
            raw.append(0)
            raw.extend(bytes(color) * w)
        def chunk(t, d):
            c = struct.pack(">I", len(d)) + t + d
            return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
        ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
        with open(path, "wb") as f:
            f.write(b"\x89PNG\r\n\x1a\n")
            f.write(chunk(b"IHDR", ihdr))
            f.write(chunk(b"IDAT", zlib.compress(bytes(raw))))
            f.write(chunk(b"IEND", b""))

    @classmethod
    def run(cls, tmp="/tmp/pixel-diff-selftest"):
        import os
        os.makedirs(tmp, exist_ok=True)
        a, b, c = f"{tmp}/a.png", f"{tmp}/b.png", f"{tmp}/c.png"
        cls.make(a, (10, 20, 30, 255))
        cls.make(b, (10, 20, 30, 255))   # 同图 → OK
        cls.make(c, (99, 99, 99, 255))   # 实心差异 → FAIL
        ok = run(a, b)
        if ok["status"] != "OK":
            print(f"self-test: 同图误报 FAIL {ok}", file=sys.stderr)
            return 2
        bad = run(a, c)
        if bad["status"] != "FAIL":
            print(f"self-test: 坏样本漏报 {bad}", file=sys.stderr)
            return 2
        print(f"self-test OK: 同图 OK / 实心差异 FAIL（{bad['solidDiff']}/{bad['total']}）")
        return 0


def main():
    ap = argparse.ArgumentParser(
        description="像素基线对拍（图形区 ≤0.5%+抗锯齿掩膜+文字区结构面）",
        epilog="--json 为本次比对的单行契约结论；裸跑为人读明细。示例: "
               "pixel-diff.py base.png cur.png --region x0,y0,x1,y1 --region ...")
    ap.add_argument("base", nargs="?", help="基线 PNG")
    ap.add_argument("current", nargs="?", help="当前采图 PNG")
    ap.add_argument("--region", action="append", default=[],
                    help="文字区结构断言矩形 x0,y0,x1,y1（可多次；区内差异单独上报不判死）")
    ap.add_argument("--threshold", type=float, default=THRESHOLD, help="图形区阈值（默认 0.005）")
    ap.add_argument("--json", action="store_true", help="单行 JSON 契约结论")
    ap.add_argument("--self-test", action="store_true", help="金丝雀自测（内嵌已知坏样本）")
    args = ap.parse_args()

    if args.self_test:
        sys.exit(SelfTest.run())
    if not args.base or not args.current:
        if args.json:
            print(json.dumps({"status": "FAIL", "severity": "error",
                              "message": "缺 base/current 两个 PNG 参数",
                              "remedy": "pixel-diff.py base.png current.png [--region x0,y0,x1,y1]"}, ensure_ascii=False, separators=(",", ":")))
            sys.exit(EXIT_FAIL)
        ap.print_usage()
        print("缺少 base/current 两个 PNG 参数", file=sys.stderr)
        sys.exit(EXIT_FAULT)
    try:
        regions = [tuple(int(v) for v in r.split(",")) for r in args.region]
    except ValueError:
        die("--region 格式应为 x0,y0,x1,y1", "例: --region 0,0,360,24")
    report = run(args.base, args.current, regions, args.threshold)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, separators=(",", ":")))
    else:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    sys.exit(EXIT_OK if report["status"] == "OK" else EXIT_FAIL)


if __name__ == "__main__":
    main()
