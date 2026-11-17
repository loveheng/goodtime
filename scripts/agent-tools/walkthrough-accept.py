#!/usr/bin/env python3
# toolbox-script
# format: v1
# name: walkthrough-accept
# summary: 验收补三项真机走查（todos 拍板 2026-11-11）：①逐层返回链（浮层 BackHandler LIFO——BACK 消费不透传）②「不保留活动」全页面回滚 ③进程杀后 SavedStateHandle 编辑暂存恢复 + 勾稽动效三处代码实证
# trigger: manual
# platform: unix
# cat: test
#
# 用法: python3 scripts/agent-tools/walkthrough-accept.py [--serial <设备号>]
# 前置: debug 变体已装真机（含 plan_seed/block_seed 桥动作 + manifest
#       enableOnBackInvokedCallback=true）；设备在线亮屏；原生宿主走查（NativeMainActivity）。
# 产物: /tmp/accept-walkthrough-<ts>/final.json 三项报告。
# 口径: 验收三项=todos「验收补三项走查」（用户 2026-11-11 拍板）；编辑暂存=丢失点 #9
#       PlanDetail SavedStateHandle（进程重建回填）。

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

PACKAGE = "app.shiguang.shiguang"
NATIVE_ACT = "app.shiguang.shiguang/.DebugNativeMainActivity"
PLAN_TITLE = "验收走查计划"
BLOCK_LABEL = "验收走查块"
DRAFT = "走查草稿甲"


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {args} 失败: {r.stderr.strip()}")
    return r.stdout


class Bridge:
    def __init__(self, serial: str | None):
        self.serial = serial
        self.base = ""
        self.token = ""

    def _sh(self, *args: str, check: bool = True) -> str:
        if self.serial:
            return adb("-s", self.serial, *args, check=check)
        return adb(*args, check=check)

    def launch(self) -> None:
        self._sh("shell", "am", "force-stop", PACKAGE)
        time.sleep(1)
        self._sh("shell", "am", "start", "-n", NATIVE_ACT)
        deadline = time.time() + 20
        pid = ""
        while time.time() < deadline:
            pid = self._sh("shell", "pidof", "-s", PACKAGE, check=False).strip()
            if pid:
                break
            time.sleep(1)
        if not pid:
            raise RuntimeError("拾光进程未起（debug 变体?）")
        self.pid = pid
        time.sleep(2)
        log = self._sh("logcat", "-d", "-s", "debug-bridge", f"--pid={pid}")
        m_port = re.findall(r"127\.0\.0\.1:(\d+)", log)
        m_tok = re.findall(r"token=(\S+)", log)
        if not m_port or not m_tok:
            raise RuntimeError(f"桥日志缺 port/token：\n{log[-600:]}")
        self.port = int(m_port[-1])
        self.token = m_tok[-1]
        self._sh("forward", f"tcp:{self.port}", f"tcp:{self.port}")
        self.base = f"http://127.0.0.1:{self.port}"
        print(f"[bridge] pid={self.pid} port={self.port}")

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

    def back(self) -> None:
        self._sh("shell", "input", "keyevent", "4")

    def home(self) -> None:
        self._sh("shell", "input", "keyevent", "3")

    def alive(self) -> bool:
        return bool(self._sh("shell", "pidof", "-s", PACKAGE, check=False).strip())


class Walkthrough:
    def __init__(self, bridge: Bridge, out: Path):
        self.b = bridge
        self.out = out
        self.results: list[dict] = []

    def check(self, name: str, cond: bool, detail: str = ""):
        self.results.append({"step": name, "ok": cond, "detail": detail})
        print(f"  [{'✓' if cond else '✗'}] {name}" + (f" — {detail}" if detail and not cond else ""))

    def ui(self, text: str) -> bool:
        try:
            r = self.b.action("ui_contains", {"text": text}).get("result", {})
            return bool(r.get("ok") and (r.get("matchCount", 0) >= 1 or r.get("nodes")))
        except Exception:
            return False

    def wait_ui(self, text: str, timeout_s: float = 12) -> bool:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            if self.ui(text):
                return True
            time.sleep(0.8)
        return False

    def run(self):
        print("== 验收补三项真机走查 ==")
        self.b.launch()
        self.b.action("plan_seed", {"title": PLAN_TITLE})
        self.b.action("block_seed", {"label": BLOCK_LABEL})

        # ── ① 逐层返回链（浮层 BackHandler LIFO——BACK 消费不透传） ────────────
        print("— ① 逐层返回链 —")
        self.b.action("navigate", {"page": "today"})
        self.wait_ui("日程", timeout_s=8)
        self.b.action("tap_node", {"text": BLOCK_LABEL})
        ok = self.wait_ui("已确认", timeout_s=10)
        self.check("①块弹层唤起", ok)
        pid_before = self.b.pid
        self.b.back()  # BACK → BackHandler 消费（弹层关，页面留）
        time.sleep(1.5)
        sheet_gone = not self.ui("已确认")
        page_kept = self.ui("日程") or self.ui("回到现在")
        same_pid = self.b.pid == pid_before and self.b.alive()
        self.check("①BACK 消费=弹层关页面留（不透传不退出）", sheet_gone and page_kept and same_pid,
                   f"sheet_gone={sheet_gone} page_kept={page_kept} alive={self.b.alive()}")
        # 编辑态返回（PlanDetail 编辑→BACK=回查看态）
        self.b.action("navigate", {"page": "plans"})
        self.wait_ui(PLAN_TITLE, timeout_s=8)
        self.b.action("tap_node", {"text": PLAN_TITLE})
        ok = self.wait_ui("计划详情", timeout_s=10)
        self.check("①PlanDetail 导航", ok)
        self.b.action("tap_node", {"text": "编辑"})
        ok = self.wait_ui("保存", timeout_s=8)
        self.check("①编辑态进入", ok)
        self.b.back()
        ok = self.wait_ui("随行凭证", timeout_s=8) and self.ui("编辑")
        self.check("①编辑态 BACK=回查看态（不保存不退出）", ok)
        # 二层浮层：picker（排期日期 picker BACK=取消）
        self.b.action("tap_node", {"text": "排期"})
        ok = self.wait_ui("确定", timeout_s=8)  # DatePickerDialog
        self.check("①排期 picker 唤起", ok)
        self.b.back()
        time.sleep(1.2)
        still_detail = self.ui("计划详情")
        self.check("①picker BACK=取消不透传（仍留详情页）", still_detail)

        # ── ② 「不保留活动」全页面回滚 ────────────────────────────────────────
        print("— ② 不保留活动回滚 —")
        self.b._sh("shell", "settings", "put", "global", "always_finish_activities", "1")
        self.b.home()  # onStop 即 finish
        time.sleep(2)
        self.b.launch()  # 重启（原 activity 已被系统 finish）
        ok = self.wait_ui("日程", timeout_s=15)
        self.check("②不保留活动→重启全页回滚渲染", ok)
        self.b._sh("shell", "settings", "put", "global", "always_finish_activities", "0")

        # ── ③ 进程杀后 SavedStateHandle 编辑暂存恢复 ──────────────────────────
        print("— ③ 进程杀暂存恢复 —")
        self.b.action("navigate", {"page": "plans"})
        self.wait_ui(PLAN_TITLE, timeout_s=8)
        self.b.action("tap_node", {"text": PLAN_TITLE})
        self.wait_ui("计划详情", timeout_s=10)
        self.b.action("tap_node", {"text": "编辑"})
        self.wait_ui("保存", timeout_s=8)
        self.b.action("tap_node", {"text": "标题"})  # 聚焦标题域
        time.sleep(0.5)
        self.b.action("set_text", {"text": DRAFT})
        time.sleep(0.8)
        landed = self.ui(DRAFT)
        self.check("③set_text 落点（标题域含草稿）", landed, "SDK set_text 对 Compose TextField 面核对")
        self.b.home()  # 背景化（系统存 saved state）
        time.sleep(2)
        # am kill 对前次仍缓存进程可能不动——am crash=真进程死亡（saved state 已在 onStop 落盘）
        self.b._sh("shell", "am", "crash", PACKAGE)
        time.sleep(2.5)
        killed = not self.b.alive()
        if not killed:
            self.b._sh("shell", "am", "force-stop", PACKAGE)
            time.sleep(1)
            killed = not self.b.alive()
        self.check("③进程已杀", killed)
        self.b.launch()
        self.b.action("navigate", {"page": "plans"})
        self.wait_ui(PLAN_TITLE, timeout_s=10)
        self.b.action("tap_node", {"text": PLAN_TITLE})
        self.wait_ui("计划详情", timeout_s=10)
        self.b.action("tap_node", {"text": "编辑"})
        self.wait_ui("保存", timeout_s=8)
        restored = self.ui(DRAFT)
        self.check("③编辑暂存恢复（SavedStateHandle 回填）", restored,
                   "未恢复→检查焦点/暂存写点；手工核对路径=todos 验收三项")
        if not restored:
            self._shot_debug()

    def _shot_debug(self):
        try:
            r = urllib.request.Request(
                f"{self.b.base}/debug/screenshot", headers={"Authorization": f"Bearer {self.b.token}"})
            d = json.loads(urllib.request.urlopen(r, timeout=20).read().decode())
            if d.get("data"):
                import base64
                (self.out / "debug_state.png").write_bytes(base64.b64decode(d["data"]))
        except Exception:
            pass


def main() -> int:
    ap = argparse.ArgumentParser(description="验收补三项真机走查")
    ap.add_argument("--serial", default=None)
    args = ap.parse_args()
    out = Path(f"/tmp/accept-walkthrough-{time.strftime('%Y%m%d-%H%M%S')}")
    out.mkdir(parents=True, exist_ok=True)
    b = Bridge(args.serial)
    w = Walkthrough(b, out)
    w.run()
    report = {"out": str(out), "results": w.results,
              "pass": sum(1 for r in w.results if r["ok"]),
              "fail": sum(1 for r in w.results if not r["ok"])}
    (out / "final.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    print(f"[报告] {out}/final.json  PASS={report['pass']} FAIL={report['fail']}")
    return 0 if report["fail"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
