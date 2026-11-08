#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
外星仔 · 看广告得时长 · 自动化控制器

    python auto.py run  --act --rounds 3      # 真跑
    python auto.py run                        # dry-run：只打印计划
    python auto.py probe                       # 读一次首页状态

安全边界（P0 实测得出）：
  * 首页只点 com.etalien.booster:id/UIADSubmit
  * 广告内只点 内容描述/资源 id 含 close 的关闭按钮；H5 页只点文本含"领奖/领取"的按钮
  * 其余一律不点：跳过、立即查看、立享优惠、了解详情、免费获取、摇一摇区域
  * 退出兜底用 KEYCODE_BACK
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import observe_p0 as P  # noqa: E402

PKG = "com.etalien.booster"
HOME_ACT = "com.etalien.booster.ui.MainActivity"
BTN_ID = "com.etalien.booster:id/UIADSubmit"

# 所有写死的像素坐标都以 1080x2340 为参照，运行时按真机分辨率等比缩放
REF_W, REF_H = 1080, 2340
ENDCARD_X = (985, 173)      # 广告结束页右上角 X

AD_KINDS = [
    ("h5", ("com.byazt.tj.Stub_Standard_Portrait_Activity",)),
    ("jump", ("com.byazt.tj.Stub_Standard_Activity",)),
    ("video", ("com.kwad.", "com.baidu.mobads", "com.qq.e.", "com.ptg.ptgapi")),
]

# 绝对禁止点击的文本
FORBIDDEN = ("立即查看", "立享优惠", "了解详情", "免费获取", "立即获取", "下载",
             "打开淘宝", "去看看", "跳过", "晃动", "任意滑动")

REWARD_WORDS = ("领奖", "领取奖励", "领取", "开宝箱")


def log(msg: str = "") -> None:
    print(msg, flush=True)


def classify(act: str) -> str:
    for name, keys in AD_KINDS:
        if any(k in act for k in keys):
            return name
    if act and not act.startswith(PKG):
        return "external"
    return "other"


def center(b: str) -> tuple[int, int] | None:
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def area(b: str) -> int:
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
    if not m:
        return 0
    x1, y1, x2, y2 = map(int, m.groups())
    return max(0, x2 - x1) * max(0, y2 - y1)


def parse_duration(texts: list[str]) -> int | None:
    unit = {"时": 3600, "分": 60, "秒": 1}
    total, found = 0, False
    for i, t in enumerate(texts):
        if t in unit and i > 0 and texts[i - 1].isdigit():
            total += int(texts[i - 1]) * unit[t]
            found = True
    return total if found else None


class Bot:
    def __init__(self, adb: str, serial: str, outdir: Path, act: bool) -> None:
        self.dev = P.Device(adb, serial)
        self.out = outdir
        self.act = act
        self.shots = outdir / "shots"
        self.shots.mkdir(parents=True, exist_ok=True)
        self.timeline: list[dict] = []
        self._snap = 0
        self.w, self.h = self._screen()

    def _screen(self) -> tuple[int, int]:
        _, out = self.dev.sh(["shell", "wm", "size"])
        m = re.findall(r"(\d+)x(\d+)", out)
        if m:
            return int(m[-1][0]), int(m[-1][1])
        return REF_W, REF_H

    def pt(self, x: int, y: int) -> tuple[int, int]:
        """把参照系坐标换算到本机分辨率。"""
        return round(x * self.w / REF_W), round(y * self.h / REF_H)

    # ---------------- 基础 ---------------- #
    def stamp(self) -> str:
        return datetime.now().strftime("%H:%M:%S.%f")[:-3]

    def note(self, event: str, **kw) -> None:
        self.timeline.append({"t": self.stamp(), "event": event, **kw})
        log(f"  [{self.stamp()}] {event} " + " ".join(f"{k}={v}" for k, v in kw.items()))

    def fg(self) -> tuple[str, str]:
        return P.foreground(self.dev)

    def shot(self, tag: str = "") -> None:
        self._snap += 1
        p = self.shots / f"{self._snap:04d}_{tag}.png" if tag else self.shots / f"{self._snap:04d}.png"
        if not P.take_screenshot(self.dev, p):
            p.unlink(missing_ok=True)

    def dump_nodes(self) -> tuple[list[dict], list[str]]:
        p = self.out / "_last.xml"
        ok, _ = P.dump_ui(self.dev, p)
        if not ok:
            return [], []
        nodes, texts = [], []
        try:
            root = ET.parse(p).getroot()
        except Exception:  # noqa: BLE001
            return [], []
        for n in root.iter("node"):
            t = (n.get("text") or "").strip()
            d = (n.get("content-desc") or "").strip()
            nodes.append({
                "id": n.get("resource-id") or "",
                "t": t,
                "d": d,
                "b": n.get("bounds") or "",
                "click": n.get("clickable") == "true",
            })
            if t:
                texts.append(t)
        return nodes, texts

    def tap(self, x: int, y: int, why: str) -> None:
        if not self.act:
            log(f"  [dry] tap({x},{y}) {why}")
            self.note("dry_tap", x=x, y=y, why=why)
            return
        self.dev.sh(["shell", "input", "tap", str(x), str(y)])
        self.note("tap", x=x, y=y, why=why)

    def swipe_up(self, why: str) -> None:
        if not self.act:
            log(f"  [dry] swipe_up {why}")
            return
        x1, y1 = self.pt(540, 1650)
        x2, y2 = self.pt(540, 950)
        self.dev.sh(["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "320"])
        self.note("swipe", why=why)

    def back(self, why: str) -> None:
        if not self.act:
            log(f"  [dry] back {why}")
            return
        self.dev.sh(["shell", "input", "keyevent", "KEYCODE_BACK"])
        self.note("back", why=why)

    def goto_home(self) -> None:
        self.dev.sh(["shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"])
        self.note("launch")
        time.sleep(2.2)

    def hard_reset(self) -> None:
        """广告 Activity 赖在最上层、monkey 拉不回来时，直接杀掉进程重开。"""
        self.note("hard_reset")
        self.dev.sh(["shell", "am", "force-stop", PKG])
        time.sleep(1.8)
        self.goto_home()
        time.sleep(2.0)

    def at_home(self) -> bool:
        fg, act = self.fg()
        return fg == PKG and act.endswith("MainActivity")

    # ---------------- 首页 ---------------- #
    def read_home(self) -> dict:
        nodes, texts = self.dump_nodes()
        if parse_duration(texts) is None:          # 偶发 dump 到半渲染帧，补一次
            time.sleep(1.5)
            nodes, texts = self.dump_nodes()
        btn = next((n for n in nodes if n["id"] == BTN_ID), None)
        label = ""
        for t in texts:
            if "广告" in t and any(k in t for k in ("领时长", "稍等", "已领", "上限", "刷新", "重试")):
                label = t
        duration = parse_duration(texts)
        return {
            "texts": texts,
            "duration": duration,
            "button": btn,
            "label": label,
            "stages": [t for t in texts if re.fullmatch(r"\d+ / \d+|已完成|待解锁", t)],
            "valid": bool(duration is not None or btn is not None),
        }

    def wait_home_idle(self, timeout: float = 30) -> dict | None:
        """等首页从『广告加载中』恢复成可点状态。"""
        t0 = time.time()
        home = None
        while time.time() - t0 < timeout:
            home = self.read_home()
            if home["valid"] and "稍等" not in home["label"] and home["button"]:
                return home
            time.sleep(2.0)
        return home

    # ---------------- 广告处理 ---------------- #
    def _find_close(self, nodes: list[dict]) -> tuple[int, int] | None:
        """只认真正的关闭控件。

        「跳过」(ksad_top_toolbar_close_tip) 会提前结束广告导致不发奖，必须排除。
        """
        for n in nodes:
            key = (n["id"] + " " + n["d"]).lower()
            txt = n["t"]
            if "跳过" in txt or "close_tip" in key or "skip" in key:
                continue
            hit = ("关闭" in txt or "关闭" in n["d"] or txt in ("X", "×")
                   or "close_button" in key or "btn_close" in key
                   or "icon_close" in key or "closebtn" in key)
            a = area(n["b"])
            if hit and 0 < a < 320 * 320:
                c = center(n["b"])
                if c:
                    return c
        return None

    def _progress(self, texts: list[str]) -> int | None:
        """最后一段 'N / M' 就是当前在做的那一档的进度。"""
        val = None
        for t in texts:
            m = re.fullmatch(r"(\d+) / (\d+)", t)
            if m:
                val = int(m.group(1))
        return val

    def _find_reward(self, nodes: list[dict]) -> tuple[int, int, str] | None:
        """H5 领奖按钮：文本命中且面积不大。绝不选整屏容器。"""
        cands = []
        for n in nodes:
            t = n["t"]
            if not t or any(f in t for f in FORBIDDEN):
                continue
            if not any(w in t for w in REWARD_WORDS):
                continue
            a = area(n["b"])
            if a <= 0 or a > 1080 * 2340 * 0.25:
                continue
            c = center(n["b"])
            if c:
                cands.append((a, c, t))
        if not cands:
            return None
        a, c, t = min(cands)
        return c[0], c[1], t

    def tap_text(self, nodes: list[dict], text: str) -> bool:
        for n in nodes:
            if n["t"] and text in n["t"]:
                c = center(n["b"])
                if c:
                    self.tap(c[0], c[1], f"text:{text}")
                    return True
        return False

    def handle_ad(self, kind: str, budget: float = 110, record: bool = True) -> str:
        """跟随一次广告直到结束。返回 closed/external/timeout。

        优量汇的坑：结束页会显示「奖励将于 N 秒后发放」，但倒计时可能永远不动；
        此时按返回键会弹「确认要离开吗？」，之前没处理这个弹窗，广告卡了 7 分钟。
        """
        t0 = time.time()
        i = 0
        swiped_at = -99.0
        dialog_hits = 0
        endcard_taps = 0
        nodes_log = self.out / "ad_nodes.jsonl"
        while time.time() - t0 < budget:
            i += 1
            fg, act = self.fg()
            if fg == PKG and act.endswith("MainActivity") and time.time() - t0 > 2.5:
                return "closed"
            if fg and not fg.startswith(PKG):
                self.note("external", fg=fg)
                return "external"

            nodes, texts = self.dump_nodes()
            if nodes:
                with nodes_log.open("a", encoding="utf-8") as fh:
                    fh.write(json.dumps({"t": self.stamp(), "act": act, "kind": kind,
                                         "nodes": nodes}, ensure_ascii=False) + "\n")
            joined = " ".join(texts)

            # 1) 「确认要离开吗？」弹窗：明确选「放弃奖励离开」，否则会一直挂着
            if "确认要离开吗" in joined:
                dialog_hits += 1
                self.note("leave_dialog", n=dialog_hits)
                if dialog_hits > 2:
                    self.back("leave_dialog_loops")
                    time.sleep(1.5)
                    return "timeout"
                if self.tap_text(nodes, "放弃奖励离开"):
                    time.sleep(1.8)
                continue

            # 2) 结束页「奖励将于N秒后发放」：等一会儿再按右上角 X 收摊
            if "奖励将于" in joined:
                if time.time() - t0 > 12:
                    self.note("endcard_close")
                    self.tap(*self.pt(*ENDCARD_X), "endcard_x")
                    time.sleep(1.8)
                continue

            close = self._find_close(nodes)
            if close:
                self.note("close_button", x=close[0], y=close[1])
                self.tap(close[0], close[1], "close_button")
                time.sleep(1.5)
                continue

            # 快手「详情页」版结束页没有 close_button，只能按坐标点右上角 X；
            # 65 秒后才做，避免提前点到「跳过」导致不发奖。
            if kind == "video" and time.time() - t0 > 65 and endcard_taps < 2:
                endcard_taps += 1
                self.note("endcard_x", n=endcard_taps)
                self.tap(*self.pt(*ENDCARD_X), "endcard_x")
                time.sleep(2.2)
                continue

            if kind == "h5":
                m = re.search(r"再逛\s*(\d+)\s*秒", joined)
                if "下滑浏览" in joined and time.time() - swiped_at > 6:
                    self.swipe_up("需要下滑浏览")
                    swiped_at = time.time()
                    continue
                if m:
                    self.note("countdown", left=int(m.group(1)))
                rw = self._find_reward(nodes)
                if rw and (not m or int(m.group(1)) <= 1) and time.time() - t0 > 8:
                    self.note("reward_btn", t=rw[2])
                    self.tap(rw[0], rw[1], f"reward:{rw[2]}")
                    time.sleep(2.5)
                    continue

            if record and i % 3 == 0:
                self.shot(kind)
            time.sleep(0.4)

        self.note("ad_timeout", budget=budget)
        self.back("ad_timeout")
        time.sleep(1.6)
        if not self.at_home():
            self.hard_reset()
        return "timeout"

    # ---------------- 一轮 ---------------- #
    def ensure_home(self) -> dict | None:
        """回到并确认「读得出的首页」。读不出就杀进程重开，别误判成『没任务』。"""
        for _ in range(3):
            if not self.at_home():
                self.goto_home()
            home = self.wait_home_idle(20)
            if home and home.get("valid"):
                return home
            self.hard_reset()
        home = self.read_home()
        return home if home.get("valid") else None

    def run_round(self, record: bool = True) -> dict:
        home0 = self.ensure_home()
        if home0 is None:
            self.note("unreachable")
            return {"ok": False, "reason": "unreachable"}
        log(f"  起始：时长={home0['duration']}s 标签={home0['label']!r} 阶段={home0['stages']}")
        has_task = any(re.fullmatch(r"\d+ / \d+", s) for s in home0["stages"])
        if not has_task:
            # 刚跑完一档时，下一档可能要几秒才解锁，别急着判「没任务」
            for _ in range(3):
                time.sleep(4)
                home0 = self.read_home()
                if any(re.fullmatch(r"\d+ / \d+", s) for s in home0["stages"]):
                    has_task = True
                    break
        if not has_task or "上限" in home0["label"] or "已领" in home0["label"]:
            return {"ok": False, "reason": "limit", "label": home0["label"],
                    "stages": home0["stages"]}
        btn = home0["button"]
        if not btn:
            return {"ok": False, "reason": "no_button"}
        c = center(btn["b"])
        if not c:
            return {"ok": False, "reason": "bad_bounds"}
        self.note("home", duration=home0["duration"], label=home0["label"], bounds=btn["b"])
        self.tap(c[0], c[1], "UIADSubmit")
        time.sleep(0.6)

        kind, t0 = None, time.time()
        while time.time() - t0 < 30:
            fg, act = self.fg()
            if act and not act.endswith("MainActivity"):
                kind = classify(act)
                self.note("ad_start", act=act, kind=kind)
                break
            time.sleep(0.35)
        if kind is None:
            self.note("no_fill")
            return {"ok": False, "reason": "no_ad"}

        reason = self.handle_ad(kind, record=record)
        self.note("ad_end", reason=reason)
        if reason == "external":
            self.back("从外部应用返回")
            time.sleep(1.6)
        if not self.at_home():
            self.goto_home()

        # 奖励是延迟到账的：最多等 90s；时长与进度任一变好即算成功
        base_p = self._progress(home0["stages"])
        t1 = time.time()
        got, home1 = False, None
        while time.time() - t1 < 90:
            if not self.at_home():
                self.goto_home()
                time.sleep(1.5)
            home1 = self.read_home()
            cur_p = self._progress(home1["stages"])
            d = None
            if home0["duration"] is not None and home1["duration"] is not None:
                d = home1["duration"] - home0["duration"]
            if (d is not None and d >= 540) or (base_p is not None and cur_p is not None and cur_p > base_p):
                got = True
                break
            time.sleep(4)
        home1 = home1 or self.read_home()
        delta = None
        if home0["duration"] is not None and home1["duration"] is not None:
            delta = home1["duration"] - home0["duration"]
        self.note("verify", ok=got, duration=home1["duration"], delta=delta, stages=home1["stages"])
        log(f"  结果：{home0['duration']} -> {home1['duration']}  Δ={delta}s  {'成功' if got else '未确认'}")
        return {"ok": got, "reason": reason, "kind": kind, "delta": delta,
                "home0": home0["duration"], "home1": home1["duration"]}


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    ap = argparse.ArgumentParser(description="外星仔看广告自动化（默认 dry-run）")
    ap.add_argument("mode", nargs="?", default="run", choices=["run", "probe"])
    ap.add_argument("--adb", default=None)
    ap.add_argument("--serial", default=None)
    ap.add_argument("--out", default="out")
    ap.add_argument("--package", default=None, help="目标应用包名，默认 com.etalien.booster")
    ap.add_argument("--act", action="store_true", help="真的点击")
    ap.add_argument("--rounds", type=int, default=1)
    ap.add_argument("--gap", type=float, nargs=2, default=(20.0, 45.0), help="轮间隔秒")
    ap.add_argument("--max-minutes", type=float, default=40, help="总时长上限")
    args = ap.parse_args()

    if args.package:
        global PKG, BTN_ID
        PKG = args.package
        BTN_ID = f"{PKG}:id/UIADSubmit"

    adb = P.find_adb(args.adb)
    if not adb:
        log("[x] 找不到 adb")
        return 2
    serial = P.choose_device(adb, args.serial)
    if not serial:
        return 2

    outdir = Path(args.out) / f"auto-{datetime.now():%Y%m%d-%H%M%S}"
    bot = Bot(adb, serial, outdir, args.act)
    log("=" * 66)
    log(f"模式={args.mode} 实际点击={'是' if args.act else '否(dry)'} 输出={outdir}")
    log("=" * 66)

    if args.mode == "probe":
        h = bot.read_home()
        log(f"时长={h['duration']}s 标签={h['label']!r} 阶段={h['stages']}")
        return 0

    results, t_start = [], time.time()
    try:
        for r in range(args.rounds):
            log(f"\n--- 第 {r + 1}/{args.rounds} 轮 ---")
            res = bot.run_round()
            results.append(res)
            if res.get("reason") in ("limit", "no_button"):
                log("  结束：没有可领的任务")
                break
            if len(results) >= 2 and not any(r.get("ok") for r in results[-2:]):
                log("  结束：连续两轮没拿到奖励，先停手")
                break
            if time.time() - t_start > args.max_minutes * 60:
                log("  达到总时长上限")
                break
            if r < args.rounds - 1:
                g = random.uniform(*args.gap)
                log(f"  休息 {g:.1f}s …")
                time.sleep(g)
    except KeyboardInterrupt:
        log("\n中止")
    finally:
        (outdir / "timeline.json").write_text(
            json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        ok = sum(1 for r in results if r.get("ok"))
        log(f"\n汇总：{len(results)} 轮，成功 {ok}，时间线 {outdir / 'timeline.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
