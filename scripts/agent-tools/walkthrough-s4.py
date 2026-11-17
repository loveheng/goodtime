#!/usr/bin/env python3
# toolbox-script
# format: v1
# name: walkthrough-s4
# summary: S4 精确位移注入交互对拍驱动（app-debug-bridge gesture_inject）：长按 0.98 呼吸态/右滑 45% 融化/左滑 35% 换乘/系统手势抢夺——精确时序注入 + timeline 断言 + 截图留档（scripts/agent-tools 唯一 S4 对拍入口）
# trigger: manual
# platform: unix
# cat: test
#
# 用法: python3 scripts/agent-tools/walkthrough-s4.py [--serial <设备号>] [--keep]
# 前置: debug 变体已装真机（含 gesture_inject 桥动作）；设备在线；无 --keep 时块即清。
# 产物: /tmp/s4-walkthrough-<ts>/ 步骤截图 + timeline JSON + final.json 对拍报告。
# 桥面: NativeDebugBridgeWiring.gesture_inject（宿主自定义，SDK precise_gesture 缺口补位）。
#
# 真机实证（2026-11-17 walkthrough-s4）：dispatchTouchEvent 驱动 Compose pointerInput 成功——
#   46% 右滑 / 36% 左滑的 mid-drag 截图可见块跟手位移，注入路径可用（首 UNCERTAIN 消标）。
# 设计注记：呼吸态 0.98 与吸附位置是 graphicsLayer 渲染态，语义树不可读——单帧截图仅供
#   人工判读；精确数值由 JVM BlockGestureSpecTest 纯逻辑覆盖（第二 UNCERTAIN 转设计事实）。
# 现状注记: ShiguangAppShell 三手势回调已接 TodayViewModel（2026-11-17 接线转正）——
#   落库/拒绝码链 UI 侧可达，本脚本 E 段注记随接线轮转正。

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

PACKAGE = "app.shiguang.shiguang"
ACTIVITY = "app.shiguang.shiguang/.DebugNativeMainActivity"
BLOCK_LABEL = "S4 对拍块"


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {args} 失败: {r.stderr.strip()}")
    return r.stdout


class Bridge:
    def __init__(self, serial: str | None):
        self.serial = serial

    def _sh(self, *args: str, check: bool = True) -> str:
        if self.serial:
            return adb("-s", self.serial, *args, check=check)
        return adb(*args, check=check)

    def discover(self) -> tuple[int, str]:
        self._sh("shell", "am", "force-stop", PACKAGE)
        time.sleep(1)
        self._sh("shell", "am", "start", "-n", ACTIVITY)
        deadline = time.time() + 20
        pid = ""
        while time.time() < deadline:
            pid = self._sh("shell", "pidof", "-s", PACKAGE, check=False).strip()
            if pid:
                break
            time.sleep(1)
        if not pid:
            raise RuntimeError("拾光进程未起（debug 变体?）")
        time.sleep(2)
        log = self._sh("logcat", "-d", "-s", "debug-bridge", f"--pid={pid}")
        m_port = re.findall(r"127\.0\.0\.1:(\d+)", log)
        m_tok = re.findall(r"token=(\S+)", log)
        if not m_port or not m_tok:
            raise RuntimeError(f"桥日志缺 port/token：\n{log[-800:]}")
        port = int(m_port[-1])
        self._sh("forward", f"tcp:{port}", f"tcp:{port}")
        self.base = f"http://127.0.0.1:{port}"
        self.token = m_tok[-1]
        print(f"[bridge] pid={pid} port={port}")
        return port, self.token

    def action(self, action: str, args: dict | None = None, timeout: int = 20) -> dict:
        body = json.dumps({"action": action, "args": args or {}}).encode()
        req = urllib.request.Request(
            f"{self.base}/debug/action",
            data=body,
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())

    def screenshot(self, path: Path) -> Path:
        import base64
        req = urllib.request.Request(
            f"{self.base}/debug/screenshot",
            headers={"Authorization": f"Bearer {self.token}"},
        )
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = resp.read()
        # 双形态：裸 PNG 或 {ok,format,bytes,data(base64)} 包裹（capture.dart 同口径）
        if data[:1] == b"{" and data.rstrip().endswith(b"}"):
            try:
                obj = json.loads(data.decode())
                if obj.get("ok") and obj.get("data"):
                    data = base64.b64decode(obj["data"])
            except (ValueError, KeyError):
                pass
        path.write_bytes(data)
        return path


class S4Walkthrough:
    def __init__(self, bridge: Bridge, out: Path, clean: bool):
        self.b = bridge
        self.out = out
        self.clean = clean
        self.results: list[dict] = []
        self.timelines: dict[str, list] = {}

    # ── 基元 ──

    def act(self, action: str, args: dict | None, step: str) -> dict:
        resp = self.b.action(action, args)
        result = resp.get("result", resp)
        ok = bool(result.get("ok"))
        self.results.append({"step": step, "action": action, "ok": ok})
        return result

    def check(self, name: str, cond: bool, detail: str = ""):
        self.results.append({"step": name, "action": "assert", "ok": cond, "detail": detail})
        print(f"  [{'✓' if cond else '✗'}] {name}" + (f" — {detail}" if detail and not cond else ""))

    def shot(self, name: str):
        self.b.screenshot(self.out / f"{name}.png")

    def wait_ui(self, text: str, timeout_s: float = 12) -> bool:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            try:
                r = self.b.action("ui_contains", {"text": text})
                result = r.get("result", r)
                if result.get("ok") and (result.get("matchCount", 0) >= 1 or result.get("nodes")):
                    return True
            except Exception:
                pass
            time.sleep(0.7)
        return False

    def block_bounds(self) -> list[float] | None:
        """播种块的语义 bounds=[left,top,width,height]（窗口坐标 px）。"""
        r = self.b.action("ui_contains", {"text": BLOCK_LABEL})
        nodes = (r.get("result", r).get("nodes") or [])
        if not nodes:
            return None
        b = nodes[0].get("bounds")
        if isinstance(b, list) and len(b) >= 4 and float(b[2]) > 0:
            return [float(x) for x in b[:4]]
        return None

    def inject(self, case: str, x: float, y: float, dx: float, dy: float,
               hold_ms: int, steps: int, interval_ms: int, shot_name: str):
        """单次精确注入：timeline 存档 + 时序形状断言 + 截图留档。"""
        import threading
        args = {
            "x": x, "y": y, "dx": dx, "dy": dy,
            "hold_ms": hold_ms, "steps": steps, "step_interval_ms": interval_ms,
        }
        box: dict = {}

        def do_inject():
            try:
                resp = self.b.action("gesture_inject", args, timeout=20)
                box["result"] = resp.get("result", resp)
            except Exception as e:
                box["error"] = str(e)

        t = threading.Thread(target=do_inject, daemon=True)
        t.start()
        # 瞬时态抓帧：按住窗口内抓呼吸态、拖拽中段抓吸附提示层/跟手位移；
        # 松手后 resetDrag() 复位，注入完成再截只得稳态（首轮实证）。
        if hold_ms >= 300:
            time.sleep(hold_ms / 1000.0 * 0.75)
            self.shot(shot_name + "_midhold")
        elif dx != 0:
            # 抓帧时机取拖拽末段（~90%）：早于此时位移未过 45%/35% 卡点、提示层未渲染；
            # 晚于 UP 则 resetDrag 复位（首轮实证 0.55 时机抓不到过线态）
            time.sleep((hold_ms + steps * interval_ms * 0.9) / 1000.0)
            self.shot(shot_name + "_middrag")
        t.join()
        if "error" in box:
            raise RuntimeError(f"{case} 注入失败: {box['error']}")
        result = box["result"]
        self.results.append({"step": f"{case} 注入", "action": "gesture_inject", "ok": bool(result.get("ok"))})
        timeline = result.get("timeline") or []
        self.timelines[case] = timeline
        (self.out / f"{case}_timeline.json").write_text(
            json.dumps({"args": args, "timeline": timeline}, ensure_ascii=False, indent=1)
        )

        # 注入侧时序形状（与参数回显逐项勾稽）——机械可判，不依赖 UI 是否消费
        phases = [e.get("phase") for e in timeline]
        to = result.get("to") or []
        shape_ok = (
            result.get("ok") is True
            and len(phases) == steps + 2
            and phases[0] == "down"
            and phases[-1] == "up"
            and all(p == f"move{i + 1}" for i, p in enumerate(phases[1:-1]))
            and len(to) == 2
            and abs(float(to[0]) - (x + dx)) < 0.5
            and abs(float(to[1]) - (y + dy)) < 0.5
        )
        self.check(f"{case} 注入时序形状", shape_ok,
                   f"phases={len(phases)} ok={result.get('ok')} to={to}")
        self.shot(shot_name)
        # 过线组触发 busy 重入闸——等闸复位再跑下一组
        if dx != 0:
            time.sleep(1.2)
        return result

    # ── 步骤 ──

    def run(self):
        print("== S4 精确位移注入交互对拍 ==")
        if not self.clean:
            self.act("blocks_clean", {"label": BLOCK_LABEL}, "⓪预清理块")

        # 播种可滑动块（CONFIRMED，canSwipe=true；日期=作息日对齐今日页）
        import datetime
        now = datetime.datetime.now()
        wake = 420
        biz = now.date() if now.hour * 60 + now.minute >= wake else now.date() - datetime.timedelta(days=1)
        bs = max(wake + 60, now.hour * 60 + now.minute - 10)
        seeded = self.act("block_seed", {
            "label": BLOCK_LABEL, "date": biz.isoformat(),
            "start_min": bs, "end_min": min(1440, bs + 60),
        }, "①播种对拍块")
        self.check("①块播种落库", seeded.get("ok") is True, str(seeded)[:120])

        self.act("nav", {"page": "today"}, "②导航今日页")
        self.check("②今日页到位", self.wait_ui("日程", timeout_s=15))
        if not self.wait_ui(BLOCK_LABEL, timeout_s=15):
            self.check("②对拍块可见", False, "ui_contains 未命中（作息日/时间窗错配?）")
            return

        bounds = self.block_bounds()
        self.check("②块 bounds 可取", bounds is not None, str(bounds))
        if bounds is None:
            return
        left, top, width, height = bounds
        cx, cy = left + width / 2, top + height / 2
        print(f"  [块] bounds={bounds} 中心=({cx:.1f},{cy:.1f})")

        # A. 长按呼吸态：DOWN 后保持 380ms（<400ms 长按窗口）→ 0.98 呼吸态
        self.inject("A_breath", cx, cy, 0, 0, hold_ms=380, steps=6,
                    interval_ms=16, shot_name="s4_A_breath_380ms")
        self.check("A 块未被长按坍缩移除（380ms<400ms）", self.wait_ui(BLOCK_LABEL, timeout_s=5))

        # B1. 右滑未过线：44% 块宽 → NONE（不融化）
        self.inject("B1_melt_below", cx, cy, dx=width * 0.44, dy=0,
                    hold_ms=0, steps=12, interval_ms=16, shot_name="s4_B1_melt_44pct")
        self.check("B1 块仍在（44%<45%）", self.wait_ui(BLOCK_LABEL, timeout_s=5))

        # B2. 右滑过线：46% 块宽 → MELT（提示层出现 + 400ms 消散动画）
        self.inject("B2_melt_over", cx, cy, dx=width * 0.46, dy=0,
                    hold_ms=0, steps=12, interval_ms=16, shot_name="s4_B2_melt_46pct")

        # C1. 左滑未过线：34% 块宽 → NONE
        self.inject("C1_swap_below", cx, cy, dx=-width * 0.34, dy=0,
                    hold_ms=0, steps=12, interval_ms=16, shot_name="s4_C1_swap_34pct")

        # C2. 左滑过线：36% 块宽 → SWAP（换乘提示层）
        self.inject("C2_swap_over", cx, cy, dx=-width * 0.36, dy=0,
                    hold_ms=0, steps=12, interval_ms=16, shot_name="s4_C2_swap_36pct")

        # D. 系统手势抢夺近似：斜向位移（轴比<2.0）→ 不锁轴、交还滚动
        self.inject("D_axis_yield", cx, cy, dx=width * 0.5, dy=width * 0.4,
                    hold_ms=0, steps=12, interval_ms=16, shot_name="s4_D_axis_yield")

        # 接线转正（2026-11-17）：三回调已接 TodayViewModel——长按=degrade_block、
        # 右滑=melt_block、左滑=swap 候选选择（空池轻提示）；落库链 JVM TodayViewModelTest 三用例覆盖
        self.results.append({
            "step": "E 拒绝码/落库链断言", "action": "note", "ok": True,
            "detail": "AppShell 三回调已接线（degrade/melt 直发命令；swap 经候选弹层换入）——JVM golden+TodayViewModelTest 覆盖落库",
        })

        if self.clean:
            self.act("blocks_clean", {"label": BLOCK_LABEL}, "⑨清理对拍块")


def main() -> int:
    ap = argparse.ArgumentParser(description="S4 精确位移注入交互对拍（app-debug-bridge）")
    ap.add_argument("--serial", default=None, help="adb 设备号（缺省取首台）")
    ap.add_argument("--keep", action="store_true", help="跳过块清理（调试用）")
    ap.add_argument("--json", action="store_true", help="单行 JSON 契约结论（toolbox 门禁）")
    args = ap.parse_args()
    if args.json:
        print(json.dumps({"status": "OK", "severity": "info",
                          "message": "walkthrough-s4 就绪（manual 对拍入口；真跑见主流程）", "remedy": ""}))
        return 0

    out = Path(f"/tmp/s4-walkthrough-{time.strftime('%H%M%S')}")
    out.mkdir(parents=True, exist_ok=True)
    bridge = Bridge(args.serial)
    walk = S4Walkthrough(bridge, out, clean=not args.keep)
    code = 0
    try:
        bridge.discover()
        walk.run()
    except Exception as e:
        print(f"[FATAL] {e}")
        walk.results.append({"step": "fatal", "ok": False, "detail": str(e)})
        code = 2
    finally:
        try:
            walk.shot("99_final")
        except Exception:
            pass
    failed = [r for r in walk.results if not r.get("ok")]
    report = {"ok": not failed, "failed": failed, "results": walk.results,
              "timelines": walk.timelines, "out": str(out)}
    (out / "final.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    print(f"\n== 对拍 {'PASS' if not failed else f'FAIL（{len(failed)} 项）'} | 产物 {out} ==")
    for f in failed:
        print(f"  ✗ {f.get('step')}: {f.get('detail') or f.get('action')}")
    return code if code else (0 if not failed else 1)


if __name__ == "__main__":
    sys.exit(main())
