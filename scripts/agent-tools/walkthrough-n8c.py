#!/usr/bin/env python3
# toolbox-script
# format: v1
# name: walkthrough-n8c
# summary: N8c 真机走查驱动（app-debug-bridge 通道）：闸门引导/分享转投/update/mcp 回环服务全链+快照还原+截图留档（ scripts/agent-tools 唯一走查入口）
# trigger: manual
# platform: unix
# cat: test
#
# 用法: python3 scripts/agent-tools/walkthrough-n8c.py [--serial <设备号>] [--keep]
# 前置: debug 变体已装真机（toolbox run build-apk）；设备在线；无 --keep 时数据快照自动还原。
# 产物: /tmp/n8c-walkthrough-<ts>/ 步骤截图 + final.json 走查报告。
# 桥面: NativeDebugBridgeWiring（原生 Compose 宿主）——动作走 POST /debug/action，
#       截图 GET /debug/screenshot；port/token 自 logcat 发现（capture.dart 同口径）。

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
SHARE_TITLE = "N8c 走查分享文本"


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {args} 失败: {r.stderr.strip()}")
    return r.stdout


class Bridge:
    def __init__(self, serial: str | None):
        self.serial = serial
        self._forwarded = False

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
            # 进程未起时 pidof 返回码 1——属正常轮询态，不视为错误
            pid = self._sh("shell", "pidof", "-s", PACKAGE, check=False).strip()
            if pid:
                break
            time.sleep(1)
        if not pid:
            raise RuntimeError("拾光进程未起（debug 变体?）")
        time.sleep(2)  # 等 Compose 首帧 + 桥启动
        log = self._sh("logcat", "-d", "-s", "debug-bridge", f"--pid={pid}")
        m_port = re.findall(r"127\.0\.0\.1:(\d+)", log)
        m_tok = re.findall(r"token=(\S+)", log)
        if not m_port or not m_tok:
            raise RuntimeError(f"桥日志缺 port/token：\n{log[-800:]}")
        port = int(m_port[-1])
        token = m_tok[-1]
        self._sh("forward", f"tcp:{port}", f"tcp:{port}")
        self.base = f"http://127.0.0.1:{port}"
        self.token = token
        print(f"[bridge] pid={pid} port={port}")
        return port, token

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
        req = urllib.request.Request(
            f"{self.base}/debug/screenshot",
            headers={"Authorization": f"Bearer {self.token}"},
        )
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = resp.read()
        path.write_bytes(data)
        return path


class Walkthrough:
    def __init__(self, bridge: Bridge, out: Path):
        self.b = bridge
        self.out = out
        self.results: list[dict] = []
        self.snap_settings: dict | None = None
        self.snap_slots: list | None = None
        self.snap_mcp: str | None = None

    # ── 基元 ──

    def act(self, action: str, args: dict | None = None, step: str = "", shot: str | None = None) -> dict:
        resp = self.b.action(action, args)
        result = resp.get("result", resp)
        ok = bool(result.get("ok"))
        self.results.append({"step": step or action, "action": action, "ok": ok, "resp_keys": list(result.keys())})
        if shot:
            self.b.screenshot(self.out / f"{shot}.png")
        return result

    def wait_ui(self, text: str, timeout_s: float = 15) -> bool:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            try:
                r = self.b.action("ui_contains", {"text": text})
                result = r.get("result", r)
                # 响应形状：{ok, matchCount, nodes:[...]} / miss={ok:false,error:node_not_found}
                if result.get("ok") and (result.get("matchCount", 0) >= 1 or result.get("nodes")):
                    return True
            except Exception:
                pass
            time.sleep(0.8)
        return False

    def wait_true(self, fn, timeout_s: float = 15) -> bool:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            if fn():
                return True
            time.sleep(0.8)
        return False

    def check(self, name: str, cond: bool, detail: str = ""):
        self.results.append({"step": name, "action": "assert", "ok": cond, "detail": detail})
        print(f"  [{'✓' if cond else '✗'}] {name}" + (f" — {detail}" if detail and not cond else ""))

    def shot(self, name: str):
        self.b.screenshot(self.out / f"{name}.png")

    # ── 步骤 ──

    def snapshot(self):
        r = self.act("repo_read", {"scope": "settings"}, step="快照 settings")
        data = r.get("data") or {}
        if isinstance(data, str):
            data = json.loads(data)
        self.snap_settings = data
        self.snap_mcp = data.get("mcp_enabled")
        e = self.act("repo_read", {"scope": "export_all"}, step="快照 fixed_slots")
        ed = e.get("data") or {}
        if isinstance(ed, str):
            ed = json.loads(ed)
        self.snap_slots = (ed.get("fixed_slots") or []) if isinstance(ed, dict) else []
        print(f"  快照：settings {len(data)} 键 / fixed_slots {len(self.snap_slots)} 行 / mcp_enabled={self.snap_mcp}")

    def restore(self):
        if self.snap_settings:
            self.act("repo_set", {"values": self.snap_settings}, step="还原 settings")
        if self.snap_slots is not None:
            self.act("slots_set", {"slots": self.snap_slots}, step="还原 fixed_slots")
        # MCP 开关键还原（服务本身不停——管家承载件常驻，Dart disable 同形）

    def run(self):
        print("== N8c 真机走查（app-debug-bridge） ==")
        self.shot("00_shell")

        # ① 闸门翻转：清作息 → 引导页
        self.act("gate_clear", step="①闸门翻转触发")
        ok = self.wait_ui("第 1 步")
        self.check("①闸门翻转→引导页", ok)
        self.shot("01_onboarding")

        # ② 引导四步：①预填→②上班族→③默认→④留空
        self.b.action("tap_node", {"text": "下一步"})
        ok = self.wait_ui("一周里哪些时间")
        self.check("②引导步①→步②", ok)
        self.b.action("tap_node", {"text": "上班族"})
        self.b.action("tap_node", {"text": "下一步"})
        ok = self.wait_ui("排程偏好")
        self.check("②引导步②→步③", ok)
        self.shot("02_prefs")
        self.b.action("tap_node", {"text": "下一步"})
        ok = self.wait_ui("关于你")
        self.check("②引导步③→步④", ok)
        self.shot("03_profile")
        self.b.action("tap_node", {"text": "开始使用"})
        ok = self.wait_ui("日程", timeout_s=20)
        self.check("②引导完成→主框架", ok)
        self.shot("04_shell")
        r = self.act("repo_read", {"scope": "settings"}, step="播种核")
        data = r.get("data") or {}
        if isinstance(data, str):
            data = json.loads(data)
        self.check("②播种 wake/sleep=420/1380", data.get("wake_time") == "420" or data.get("wake_time") == 420,
                   f"wake={data.get('wake_time')}")

        # ③ 分享转投：直投→入册断言→即清
        self.b.action("share_intake", {"text": f"{SHARE_TITLE}\n第二行"})
        found = self.wait_true(
            lambda: self.b.action("artifacts_query", {"title": SHARE_TITLE}).get("result", {})
            .get("count", 0) >= 1,
        )
        self.check("③分享入册（verbal/shared/raw）", found)
        rows = self.b.action("artifacts_query", {"title": SHARE_TITLE}).get("result", {}).get("rows", [])
        if rows:
            row0 = rows[0]
            self.check(
                "③入册字段逐字",
                row0.get("category") == "verbal" and row0.get("origin") == "shared" and row0.get("state") == "raw",
                str(row0),
            )
        self.act("artifacts_clean", {"title": SHARE_TITLE}, step="③产物即清")
        n = self.b.action("artifacts_query", {"title": SHARE_TITLE}).get("result", {}).get("count", 0)
        self.check("③清理后 0 行", n == 0, f"count={n}")

        # ④ update 页：无源检查→逐字错误串
        self.b.action("navigate", {"page": "update"})
        ok = self.wait_ui("远程更新")
        self.check("④update 页导航", ok)
        self.shot("05_update")
        self.b.action("tap_node", {"text": "检查更新"})
        ok = self.wait_ui("尚未配置更新源")
        self.check("④无源逐字错误串", ok)
        self.shot("05_update_nosource")

        # ⑤ mcp 页（真实路径：设置→长按关于→调试菜单）：回环服务启停+真听面
        self.b.action("navigate", {"page": "settings"})
        self.wait_ui("关于")
        self.b.action("long_press", {"text": "用户本地持有的日程台账。"})
        ok = self.wait_ui("调试菜单")
        self.check("⑤调试菜单唤出", ok)
        self.shot("06_debug_menu")
        self.b.action("tap_node", {"text": "MCP 调试服务"})
        ok = self.wait_ui("MCP 服务")
        self.check("⑤mcp 页导航", ok)
        # 服务态归零（用户先前开着先关；测完还原 mcp_enabled 键）
        self.b.action("tap_node", {"text": "已停止"})  # 若已运行则此步空拍
        r = self.b.action("mcp_ping", {})
        running_now = r.get("result", {}).get("ok") and "error" not in (r.get("result", {}).get("error") or "")
        if running_now and r.get("result", {}).get("status") != "200":
            pass
        # 明确拉起：若当前已运行，先关
        # （开关为 SwitchListTile 语义：tap 内容行标题触达开关行——SDK tap 落中心点）
        started = self.wait_true(lambda: self._toggle_and_check(on=True), timeout_s=25)
        self.check("⑤服务拉起→运行中", started)
        self.shot("07_mcp_running")
        ping = self.b.action("mcp_ping", {})
        pr = ping.get("result", {})
        body_ok = "result" in (pr.get("body") or "")
        self.check("⑤回环 ping 200+result", pr.get("status") == "200" and body_ok, str(pr)[:120])
        stopped = self.wait_true(lambda: self._toggle_and_check(on=False), timeout_s=25)
        self.check("⑤服务关停→已停止", stopped)
        self.shot("08_mcp_stopped")

        # ⑥ 数据还原
        self.restore()
        r = self.act("repo_read", {"scope": "settings"}, step="还原核")
        data = r.get("data") or {}
        if isinstance(data, str):
            data = json.loads(data)
        wake_ok = data.get("wake_time") == self.snap_settings.get("wake_time")
        theme_ok = data.get("theme_mode") == self.snap_settings.get("theme_mode")
        self.check(
            "⑥settings 还原一致",
            wake_ok and theme_ok,
            f"wake {self.snap_settings.get('wake_time')!r}->{data.get('wake_time')!r} / "
            f"theme {self.snap_settings.get('theme_mode')!r}->{data.get('theme_mode')!r}",
        )

    def _ping_state(self) -> str:
        """mcp_ping → "up" / "down" / "unknown"。"""
        try:
            pr = self.b.action("mcp_ping", {}).get("result", {})
            if pr.get("status") == "200":
                return "up"
            if pr.get("error") == "mcp_not_running" or pr.get("status") in ("error", "refused"):
                return "down"
            return "unknown"
        except Exception:
            return "unknown"

    def _toggle_and_check(self, on: bool) -> bool:
        """tap 开关（contentDescription=MCP 服务开关）→ 轮询 ping 态（enable 异步竞态容忍）。"""
        target = "up" if on else "down"
        deadline = time.time() + 20
        tapped = 0
        while time.time() < deadline:
            if self._ping_state() == target:
                return True
            if tapped < 4:
                try:
                    r = self.b.action("tap_node", {"label": "MCP 服务开关"})
                    if not r.get("result", r).get("ok"):
                        self.b.action("tap_node", {"text": "MCP 服务"})
                except Exception:
                    self.b.action("tap_node", {"text": "MCP 服务"})
                tapped += 1
            time.sleep(1.2)
        return self._ping_state() == target


def main() -> int:
    ap = argparse.ArgumentParser(description="N8c 真机走查驱动（app-debug-bridge）")
    ap.add_argument("--serial", default=None, help="adb 设备号（缺省取首台）")
    ap.add_argument("--keep", action="store_true", help="跳过数据还原（调试用）")
    ap.add_argument("--json", action="store_true", help="单行 JSON 契约结论（toolbox 门禁）")
    args = ap.parse_args()
    if args.json:
        # 走查非钩子任务：--json 仅报告「登记就绪」契约（真跑走 manual 主流程）
        print(json.dumps({"status": "OK", "severity": "info", "message": "walkthrough-n8c 就绪（manual 走查入口；真跑见主流程输出）", "remedy": ""}))
        return 0

    out = Path(f"/tmp/n8c-walkthrough-{time.strftime('%H%M%S')}")
    out.mkdir(parents=True, exist_ok=True)
    bridge = Bridge(args.serial)
    walk = Walkthrough(bridge, out)
    code = 0
    try:
        bridge.discover()
        walk.snapshot()
        walk.run()
    except Exception as e:
        print(f"[FATAL] {e}")
        walk.results.append({"step": "fatal", "ok": False, "detail": str(e)})
        code = 2
    finally:
        try:
            walk.b.screenshot(out / "99_final.png")
        except Exception:
            pass
    failed = [r for r in walk.results if not r.get("ok")]
    report = {"ok": not failed, "failed": failed, "results": walk.results, "out": str(out)}
    (out / "final.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    print(f"\n== 走查 {'PASS' if not failed else f'FAIL（{len(failed)} 项）'} | 产物 {out} ==")
    for f in failed:
        print(f"  ✗ {f.get('step')}: {f.get('detail') or f.get('action')}")
    return code if code else (0 if not failed else 1)


if __name__ == "__main__":
    sys.exit(main())
