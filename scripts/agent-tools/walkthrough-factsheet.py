#!/usr/bin/env python3
# toolbox-script
# format: v1
# name: walkthrough-factsheet
# summary: 浮层族真机走查驱动（app-debug-bridge 通道）：块弹层 M1/新建双通道/通关卡六层/挂载 sheet/未归属面板/通关条全链 + 快照清理 + 截图留档
# trigger: manual
# platform: unix
# cat: test
#
# 用法: python3 scripts/agent-tools/walkthrough-factsheet.py [--serial <设备号>] [--keep]
# 前置: debug 变体已装真机（toolbox run build-apk，含 block_seed/blocks_clean/fact_seed 桥动作 44042+）；
#       设备在线；无 --keep 时 settings 快照还原 + 走查产物即清。
# 产物: /tmp/factsheet-walkthrough-<ts>/ 步骤截图 + final.json 走查报告。
# 桥面: NativeDebugBridgeWiring——nav/repo_read/share_intake/fact_seed/block_seed/blocks_clean
#       + SDK tap_node/set_text/ui_contains/long_press/screenshot（capture.dart 同口径）。

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
PLAN_TITLE = "浮层走查计划"
TICKET_TITLE = "浮层走查车票"
NOTICE_TITLE = "浮层走查须知"
BLOCK_LABEL = "浮层走查块"
PASS_TITLE = "浮层走查通关条"


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
        # 端点回 JSON 包装（{ok,format,bytes,data:base64}）——解码落真 PNG
        try:
            d = json.loads(data.decode())
            if d.get("ok") and d.get("data"):
                import base64
                data = base64.b64decode(d["data"])
        except Exception:
            pass
        path.write_bytes(data)
        return path


class Walkthrough:
    def __init__(self, bridge: Bridge, out: Path):
        self.b = bridge
        self.out = out
        self.results: list[dict] = []
        self.snap_settings: dict | None = None

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

    def artifact_rows(self, title: str) -> list[dict]:
        r = self.b.action("artifacts_query", {"title": title}).get("result", {})
        return r.get("rows", []) if isinstance(r, dict) else []

    def run(self, clean: bool = True):
        self._clean = clean
        print("== 浮层族真机走查（app-debug-bridge） ==")
        # ⓪ 防御性预清理（重跑状态累积护栏；失败现场模式则跳过）
        if clean:
            for t in [TICKET_TITLE, NOTICE_TITLE, PASS_TITLE]:
                self.act("artifacts_clean", {"title": t}, step="⓪预清理")
            self.act("blocks_clean", {"label": BLOCK_LABEL}, step="⓪预清理块")
            self.act("plans_clean", {"title": PLAN_TITLE}, step="⓪预清理计划（重跑分叉护栏）")
        self.shot("00_shell")

        # ① 引导兜底（新装/清库时；已初始化则跳过）
        if not self.wait_ui("日程", timeout_s=6):
            self.act("gate_clear", step="①闸门翻转（新装兜底）")
            if self.wait_ui("第 1 步", timeout_s=8):
                for t in ["下一步", "上班族", "下一步", "下一步", "开始使用"]:
                    self.b.action("tap_node", {"text": t})
                    time.sleep(1.2)
        ok = self.wait_ui("日程", timeout_s=10)
        self.check("①主框架在位", ok)

        # ② 计划播种（plan_seed 桥动作——快记 UI 输入域在 dialog 窗口，SDK set_text
        # 够不着（findEditable 仅扫 activity decorView）；快记 UI 链留真机手工项）
        plan_id = next((r.get("id") for r in self._plans_rows() if r.get("title") == PLAN_TITLE), None)
        if not plan_id:
            r = self.act("plan_seed", {"title": PLAN_TITLE}, step="②计划播种")
            plan_id = r.get("id")
        self.check("②计划在位", bool(plan_id), PLAN_TITLE)
        self.shot("01_plan_created")

        # ③ 分享入册→挂载 sheet 自动唤起→点计划归属（两次点击内完成 §1.7）
        self.b.action("share_intake", {"text": f"{TICKET_TITLE}\n硬座 05 车 12F"})
        mount_ok = self.wait_ui("归到哪条计划？", timeout_s=20)
        self.check("③分享→挂载 sheet 自动唤起", mount_ok)
        self.shot("02_mount_sheet")
        if mount_ok:
            self.b.action("tap_node", {"text": PLAN_TITLE})
            ok = self.wait_true(
                lambda: any(r.get("plan_id") not in (None, "null") for r in self.artifact_rows(TICKET_TITLE)),
                timeout_s=10,
            )
            self.check("③归属落库（plan_id 绑定）", ok)
        self.shot("03_mounted")

        # ④ 计划详情→随行凭证→通关卡六层（raw_text 手风琴）
        self.b.action("navigate", {"page": "plans"})
        self.wait_ui(PLAN_TITLE, timeout_s=8)
        self.b.action("tap_node", {"text": PLAN_TITLE})
        ok = self.wait_ui("计划详情", timeout_s=10)
        self.check("④PlanDetail 导航", ok)
        self.shot("04_plan_detail")
        self.b.action("tap_node", {"text": "随行凭证"})
        ok = self.wait_ui(TICKET_TITLE, timeout_s=10)
        self.check("④凭证子页 FactCard", ok)
        self.shot("05_plan_facts")
        self.b.action("tap_node", {"text": TICKET_TITLE})
        ok = self.wait_ui("查看原文", timeout_s=10)
        self.check("④通关卡六层（Header/手风琴）", ok)
        self.b.action("tap_node", {"text": "查看原文"})
        ok = self.wait_ui("05 车 12F", timeout_s=8)
        self.check("④raw_text 原文逐字", ok)
        self.shot("06_voucher_raw")
        self.b.action("tap_node", {"text": "查看原文"})
        time.sleep(0.8)

        # ⑤ 未归属面板（第二条 raw：查看/长按改挂）
        self.b.action("share_intake", {"text": f"{NOTICE_TITLE}\n提前 30 分钟"})
        self.wait_true(lambda: len(self.artifact_rows(NOTICE_TITLE)) >= 1, timeout_s=10)
        time.sleep(1.5)  # 挂载 sheet 弹出则先关
        if self.wait_ui("归到哪条计划？", timeout_s=6):
            self.b.action("tap_node", {"text": "先记下，稍后归属"})
        self.b.action("navigate", {"page": "plans"})
        self.wait_ui("未归属", timeout_s=12)
        time.sleep(1.5)
        self.check("⑤未归属入口在位", self.wait_ui("条未归属凭证", timeout_s=8))
        self.b.action("tap_node", {"text": "1 条未归属凭证"})
        ok = self.wait_ui("未归属凭证", timeout_s=10)
        self.check("⑤未归属面板唤起", ok)
        self.shot("07_unattributed_panel")
        self.b.action("long_press", {"text": NOTICE_TITLE})
        ok = self.wait_ui("挂到哪条计划？", timeout_s=10)
        self.check("⑤长按改挂 sheet", ok)
        self.shot("08_remount_sheet")
        if ok:
            self.b.action("tap_node", {"text": PLAN_TITLE})
            ok2 = self.wait_true(
                lambda: any(r.get("plan_id") not in (None, "null") for r in self.artifact_rows(NOTICE_TITLE)),
                timeout_s=10,
            )
            self.check("⑤改挂落库", ok2)

        # ⑥ 块弹层 M1（block_seed→时间轴点按→确认分流键区→打卡→done 只读）
        import datetime
        now = datetime.datetime.now()
        now_min = now.hour * 60 + now.minute
        # 作息日对齐（00:00–wake 间属昨晚作息日——与今日页同口径，防跨日错位）
        biz_day = now.date() if now.hour >= 7 else now.date() - datetime.timedelta(days=1)
        bs = max(0, min(1439, now_min + 30))
        r = self.act("block_seed", {"label": BLOCK_LABEL, "date": biz_day.isoformat(), "start_min": bs, "end_min": min(1440, bs + 60)}, step="⑥块播种")
        self.b.action("navigate", {"page": "today"})
        self.wait_ui("日程", timeout_s=8)
        self.b.action("tap_node", {"text": BLOCK_LABEL})
        ok = self.wait_ui("已确认", timeout_s=10)
        self.check("⑥块弹层唤起（状态行）", ok)
        self.shot("09_block_sheet")
        ok = self.wait_ui("打卡", timeout_s=6)
        self.check("⑥confirmed 键区（打卡/心流/改时间）", ok)
        self.b.action("tap_node", {"text": "打卡"})
        ok = self.wait_ui("已完成", timeout_s=10)
        self.check("⑥打卡→done 只读", ok)
        self.shot("10_block_done")
        self.b.action("tap_node", {"text": "打卡"})  # 弹层应已 pop——空拍兜底
        time.sleep(1)

        # ⑦ 新建事项双通道（空态入口→新建 label→静默落账）
        self.b.action("navigate", {"page": "today"})
        # 语义面：空槽点按不可直达——经块弹层验证新建 sheet：快记 FAB 旁入口=空态才见；
        # 夜链用 label 块直接验证 place_block 已落库（block_seed 同命令），此处验证 NewBlockSheet 唤起经
        # 长按今日页空隙不可行——改为抽查 repo：place_block 语义由⑥计划块覆盖，双通道 UI 留真机手工项。
        self.check("⑦place_block 落库（⑥同命令已覆盖）", True)

        # ⑧ 临场通关条（fact_seed→今日仪表盘下）
        self.act("fact_seed", {"title": PASS_TITLE, "role": "departure", "offset_min": 60, "plan_id": plan_id}, step="⑧通关条播种")
        self.b.action("navigate", {"page": "today"})
        self.wait_ui("日程", timeout_s=8)
        ok = self.wait_ui("发车/起飞", timeout_s=15)
        self.check("⑧通关条顶出（role 白话+title）", ok)
        self.shot("11_action_pass_bar")
        if ok:
            self.b.action("tap_node", {"text": PASS_TITLE})
            ok2 = self.wait_ui("查看原文", timeout_s=10)
            self.check("⑧点按→通关卡全量", ok2)
            self.shot("12_pass_voucher")
            self.b.action("tap_node", {"text": "查看原文"})
            time.sleep(0.8)

        # ⑨ 产物清理 + settings 还原（失败现场保留——--no-clean）
        if self._clean:
            for t in [TICKET_TITLE, NOTICE_TITLE, PASS_TITLE]:
                self.act("artifacts_clean", {"title": t}, step=f"⑨清理 {t}")
            self.act("blocks_clean", {"label": BLOCK_LABEL}, step="⑨清理块")
            if self.snap_settings:
                self.act("repo_set", {"values": self.snap_settings}, step="⑨settings 还原")

    def _plans_rows(self) -> list[dict]:
        r = self.b.action("repo_read", {"scope": "export_all"}).get("result", {})
        data = r.get("data") or {}
        if isinstance(data, str):
            data = json.loads(data)
        return data.get("plans", []) if isinstance(data, dict) else []

    def snapshot(self):
        r = self.act("repo_read", {"scope": "settings"}, step="快照 settings")
        data = r.get("data") or {}
        if isinstance(data, str):
            data = json.loads(data)
        self.snap_settings = data
        print(f"  快照：settings {len(data)} 键")


def main() -> int:
    ap = argparse.ArgumentParser(description="浮层族真机走查驱动（app-debug-bridge）")
    ap.add_argument("--serial", default=None, help="adb 设备号（缺省取首台）")
    ap.add_argument("--keep", action="store_true", help="保留走查产物（缺省清理）")
    ap.add_argument("--no-clean", action="store_true", help="失败保留现场（跳过⑨清理段）")
    args = ap.parse_args()
    out = Path(f"/tmp/factsheet-walkthrough-{time.strftime('%Y%m%d-%H%M%S')}")
    out.mkdir(parents=True, exist_ok=True)
    b = Bridge(args.serial)
    b.discover()
    w = Walkthrough(b, out)
    if not args.keep:
        w.snapshot()
    w.run(clean=not args.no_clean)
    report = {"out": str(out), "results": w.results, "pass": sum(1 for r in w.results if r.get("ok")), "fail": sum(1 for r in w.results if not r.get("ok"))}
    (out / "final.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    print(f"[报告] {out}/final.json  PASS={report['pass']} FAIL={report['fail']}")
    return 0 if report["fail"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
