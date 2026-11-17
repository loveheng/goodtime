#!/usr/bin/env python3
# toolbox-script
# format: v1
# name: walkthrough-collab
# summary: 协作九页真机走查驱动（app-debug-bridge 通道）：可达性/门禁文案/空态/半盲态/响应浮条→双维拒绝面板链+截图留档（scripts/agent-tools 唯一走查入口）
# trigger: manual
# platform: unix
# cat: test
#
# 用法: python3 scripts/agent-tools/walkthrough-collab.py [--serial <设备号>] [--keep]
# 前置: debug 变体已装真机（toolbox run build-apk）；设备在线；桥面=NativeDebugBridgeWiring
#       （nav 参数路由 collab_decision_matrix/collab_audit_timeline/collab_canvas/collab_safety_number +
#        collab_seed/collab_clean 播种面，T4.9）。
# 挂账: 登录态/真实密文事件=B 档播种器（relay 登录态+双账号）——本脚本覆盖无登录态全页 +
#       明文骨架事件面；表单输入（account/便签/搜索）=SDK set_text 边界手工项。
# 产物: /tmp/collab-walkthrough-<ts>/ 步骤截图 + final.json 走查报告。

import argparse
import base64
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

PACKAGE = "app.shiguang.shiguang"
ACTIVITY = "app.shiguang.shiguang/.DebugNativeMainActivity"
BUNDLE = "walk-b1"


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
            pid = self._sh("shell", "pidof", "-s", PACKAGE, check=False).strip()
            if pid:
                break
            time.sleep(1)
        if not pid:
            raise RuntimeError("拾光进程未起（debug 变体?）")
        time.sleep(3)
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
        # 端点双形态：裸 PNG / JSON 包裹（ok/format/bytes/data=base64）——批 14 双兼容口径
        if data[:1] == b"{":
            wrapped = json.loads(data.decode())
            data = base64.b64decode(wrapped["data"])
        path.write_bytes(data)
        return path


class Walkthrough:
    def __init__(self, bridge: Bridge, out: Path):
        self.b = bridge
        self.out = out
        self.results: list[dict] = []

    def act(self, action: str, args: dict | None = None, step: str = "", shot: str | None = None) -> dict:
        resp = self.b.action(action, args)
        result = resp.get("result", resp)
        ok = bool(result.get("ok"))
        self.results.append({"step": step or action, "action": action, "ok": ok, "resp_keys": list(result.keys())})
        if shot:
            self.b.screenshot(self.out / f"{shot}.png")
        return result

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
            time.sleep(0.8)
        return False

    def nav(self, page: str, args: dict | None = None) -> bool:
        r = self.act("nav", {"page": page, **(args or {})}, step=f"nav {page}")
        ok = bool(r.get("ok"))
        time.sleep(1.2)  # 等导航帧+页面首装配
        return ok

    def check(self, name: str, cond: bool, detail: str = ""):
        self.results.append({"step": name, "action": "assert", "ok": cond, "detail": detail})
        print(f"  [{'✓' if cond else '✗'}] {name}" + (f" — {detail}" if detail and not cond else ""))

    def shot(self, name: str):
        self.b.screenshot(self.out / f"{name}.png")

    def run(self) -> int:
        print("== 协作九页真机走查（app-debug-bridge） ==")
        self.act("collab_clean", step="预清理 walk- 播种残留")
        self.shot("00_shell")

        # ① member_calendar 空态（无协同包）
        assert_nav = self.nav("collab_member_calendar")
        self.check("①a nav collab_member_calendar", assert_nav)
        # 冷启动首入页加载偏慢——放宽轮询窗；FAB 文字=合并节点语义缺口（SDK 边界族，
        # 与浮层族 ④⑤⑥ 同款——渲染以截图留证，tap 面经 ③ 门禁文案间接覆盖）
        ok_empty = self.wait_ui("还没有协同日程", timeout_s=25)
        self.check("①b 空态文案（FAB 渲染=截图留证）", ok_empty)
        ok_entry = self.wait_ui("协同日程")
        self.check("①c AppBar 标题", ok_entry)
        self.shot("01_member_calendar_empty")

        # ② friends 门禁态（未登录/无身份）
        self.nav("collab_friends")
        ok_gate = self.wait_ui("完成登录与协同身份注册，才能管理好友")
        self.check("② friends 会话门禁两可文案", ok_gate)
        self.shot("02_friends_gate")

        # ③ compose_bundle 门禁兜底（kit null→引导）
        self.nav("collab_compose_bundle")
        ok_gate = self.wait_ui("先到「设置 → 协同账号」登录，才能发出邀约")
        self.check("③ compose 门禁兜底文案", ok_gate)
        self.shot("03_compose_gate")

        # ④ my_qr 签发失败态（无身份→错误+刷新钮）
        self.nav("collab_my_qr")
        ok_err = self.wait_ui("暂时签不出名片")
        ok_retry = self.wait_ui("刷新名片")
        self.check("④ my_qr 签发失败+刷新钮", ok_err and ok_retry)
        self.shot("04_my_qr_error")

        # ⑤ account 登录表单（LOGGED_OUT 三态门禁）
        self.nav("collab_account")
        ok_seg = self.wait_ui("注册并登录") or self.wait_ui("登录")
        ok_note = self.wait_ui("协同密钥在本机自动管理，无需记忆")
        self.check("⑤ account 表单+尾注", ok_seg and ok_note)
        self.shot("05_account_form")

        # ⑥ 播种 → member_calendar 数据态（三态预占卡片）
        seed_ok = self.act("collab_seed", {"bundle_id": BUNDLE, "proposal_type": "DIRECTIVE", "status": "PROPOSED"}, step="⑥ 播种 walk-b1")
        self.check("⑥a collab_seed", bool(seed_ok.get("ok")))
        self.nav("collab_member_calendar")
        ok_card = self.wait_ui("团队调度") and self.wait_ui("协商中")
        ok_steps = self.wait_ui("步骤 s1") and self.wait_ui("步骤 s2")
        # 徽标=包级 tier（Dart 同构：DIRECTIVE 包全步骤「待确认」，「候选」仅 CONSENSUS——B 档播种器）
        ok_badge = self.wait_ui("待确认")
        self.check("⑥b 包卡（类型/状态/双步骤/待确认徽标）", ok_card and ok_steps and ok_badge)
        self.shot("06_member_calendar_seeded")

        # ⑦ 响应浮条 → 同意链（outbox 落库反馈）
        self.b.action("tap_node", {"text": "步骤 s1"})
        ok_sheet = self.wait_ui("回应这一步")
        self.check("⑦a 响应浮条唤起", ok_sheet)
        self.shot("07_response_sheet")
        if ok_sheet:
            self.b.action("tap_node", {"text": "同意"})
            ok_toast = self.wait_ui("已记下，联网后自动发送", timeout_s=6)
            self.check("⑦b 同意→outbox 反馈文案", ok_toast)
        # 拒绝链：浮条→拒绝并说明→双维拒绝面板→提交
        self.b.action("tap_node", {"text": "步骤 s1"})
        ok_sheet2 = self.wait_ui("回应这一步")
        if ok_sheet2:
            self.b.action("tap_node", {"text": "拒绝并说明"})
            ok_panel = self.wait_ui("提出异议")
            self.check("⑦c 双维拒绝面板唤起", ok_panel)
            self.shot("07_reject_panel")
            ok_dims = self.wait_ui("时间冲突") and self.wait_ui("权责异议") and self.wait_ui("个人原因")
            self.check("⑦d 主维度+子类四面", ok_dims)
            ok_adv = self.wait_ui("详细说明与隐私（选填）")
            self.check("⑦e 展开态入口折叠在场", ok_adv)
            if ok_dims:
                self.b.action("tap_node", {"text": "时间冲突"})
                self.b.action("tap_node", {"text": "个人原因"})
                self.b.action("tap_node", {"text": "提交异议"})
                ok_toast2 = self.wait_ui("已记下，联网后自动发送", timeout_s=6)
                self.check("⑦f 提交异议→outbox 反馈", ok_toast2)
        else:
            self.check("⑦c 双维拒绝面板唤起", False, "响应浮条二唤失败")
        self.shot("07_after_reject")

        # ⑧ decision_matrix 半盲空态（哑元密文解不开——S4 半盲态即走查对象）
        self.nav("collab_decision_matrix", {"bundle_id": BUNDLE})
        ok_hdr = self.wait_ui("决策")
        ok_empty = self.wait_ui("暂无步骤数据") and self.wait_ui("该包密钥尚未取到")
        self.check("⑧ decision_matrix 半盲空态", ok_hdr and ok_empty)
        self.shot("08_decision_matrix_halfblind")

        # ⑨ audit_timeline 明文骨架事件流（无输入框时间树+便签抽屉）
        self.nav("collab_audit_timeline", {"bundle_id": BUNDLE})
        ok_e1 = self.wait_ui("发起了方案")
        ok_e2 = self.wait_ui("同意了这一步") and self.wait_ui("提出了异议")
        ok_hint = self.wait_ui("已私密反馈发起人，等待调整")
        ok_e4 = self.wait_ui("更新了备忘")
        self.check("⑨a 四事件气泡渲染", ok_e1 and ok_e2 and ok_e4)
        self.check("⑨b REJECT 非本人半盲骨架提示", ok_hint)
        self.shot("09_audit_timeline")
        # 便签抽屉唤起（note_add 图标 contentDescription=步骤便签）
        r_note = self.b.action("tap_node", {"text": "步骤便签"})
        result = r_note.get("result", r_note)
        note_open = self.wait_ui("覆盖式更新，经加密随事件流入库") or self.wait_ui("保存便签")
        self.check("⑨c 步骤便签抽屉唤起", note_open)
        self.shot("09_step_note_sheet")
        if note_open:
            self.b.action("tap_node", {"text": "取消"} if result else {"text": "保存便签"}) if False else None
            self.b.action("nav", {"page": "collab_member_calendar"})  # sheet 关闭走导航复位
            time.sleep(1)

        # ⑩ collab_canvas 双轨+共振条降级+步骤块
        self.nav("collab_canvas", {"bundle_id": BUNDLE, "window_start_ms": 0, "window_end_ms": 86400000})
        ok_track = self.wait_ui("团队大盘") and self.wait_ui("个人总览")
        ok_bar = self.wait_ui("全员共振条") and self.wait_ui("暂无成员空闲数据")
        ok_block = self.wait_ui("排期画板") and self.wait_ui("步骤 s2")
        self.check("⑩ collab_canvas 双轨+降级共振条+步骤块", ok_track and ok_bar and ok_block)
        self.shot("10_collab_canvas")

        # ⑪ safety_number 门禁错误态（会话缺位→取钥失败）
        self.nav("collab_safety_number", {"friend_user_id": "u_alice"})
        ok_err = self.wait_ui("暂时取不到对方公钥")
        ok_retry = self.wait_ui("重试")
        self.check("⑪ safety_number 取钥失败+重试", ok_err and ok_retry)
        self.shot("11_safety_number_error")

        # ⑫ 收尾清理
        self.act("collab_clean", step="⑫ 清理 walk- 播种")
        self.nav("collab_member_calendar")
        ok_clean = self.wait_ui("还没有协同日程")
        self.check("⑫ 清理还原空态", ok_clean)

        passed = sum(1 for r in self.results if r["action"] == "assert" and r["ok"])
        failed = sum(1 for r in self.results if r["action"] == "assert" and not r["ok"])
        report = {"passed": passed, "failed": failed, "results": self.results}
        (self.out / "final.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(f"== 走查完成 PASS={passed} FAIL={failed} → {self.out}/final.json ==")
        return failed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None)
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()
    out = Path(f"/tmp/collab-walkthrough-{int(time.time())}")
    out.mkdir(parents=True, exist_ok=True)
    bridge = Bridge(args.serial)
    bridge.discover()
    w = Walkthrough(bridge, out)
    failed = w.run()
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
