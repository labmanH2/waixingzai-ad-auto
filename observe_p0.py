#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
外星仔加速器 · 看广告得时长 · P0 观察者
======================================

P0 阶段只做三件事：看清、记录、不点击。

    probe    一次性体检：设备信息 / 分辨率 / 目标包名 / uiautomator 能否看见 Flutter 控件
    record   连续采样：截图 + 控件树 + 前台包名 + logcat，按状态变化落盘

设计约束（很重要）：
  * 全程只读，不执行任何 input tap / swipe，不会替你操作手机。
  * 默认只在前台是外星仔时才截图，避免把微信等其它 App 的画面录进磁盘。
  * 纯标准库实现，不需要 pip install 任何东西。

用法：
    python observe_p0.py probe
    python observe_p0.py record --duration 600

产物落在 out/<时间戳>/ 下：
    index.jsonl   每一帧的结构化记录（前台包、文本、可点击节点、是否截图）
    shots/0001.png
    dumps/0001.xml
    logcat.txt    目标进程的日志（若拿到 pid）
    session.json  设备与参数快照
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

REMOTE_SHOT = "/sdcard/p0_shot.png"
REMOTE_DUMP = "/sdcard/p0_dump.xml"

# 包名线索：外星的域名是 et001.com / etalien，包名基本跑不出这几个关键词
PKG_HINTS = ("etalien", "et001", "waixing", "alien")

# 判定为「有信息量」的界面文本
SKIP_TEXTS = {"", " ", "\n"}


# --------------------------------------------------------------------------- #
# 基础设施
# --------------------------------------------------------------------------- #
def log(msg: str = "") -> None:
    print(msg, flush=True)


def run(cmd: list[str], timeout: int = 40) -> tuple[int, str]:
    """跑一条命令，合并 stdout/stderr，永不抛异常。"""
    try:
        p = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, f"TIMEOUT({timeout}s): {' '.join(cmd)}"
    except FileNotFoundError:
        return 127, f"NOT FOUND: {cmd[0]}"
    except Exception as exc:  # noqa: BLE001
        return 1, f"EXC: {exc}"


class Device:
    def __init__(self, adb: str, serial: str) -> None:
        self.adb = adb
        self.serial = serial

    def sh(self, args: list[str], timeout: int = 40) -> tuple[int, str]:
        return run([self.adb, "-s", self.serial, *args], timeout=timeout)

    def pull(self, remote: str, local: Path, timeout: int = 60) -> bool:
        local.parent.mkdir(parents=True, exist_ok=True)
        rc, out = run([self.adb, "-s", self.serial, "pull", remote, str(local)], timeout=timeout)
        if rc != 0 or not local.exists():
            log(f"    ! pull 失败 {remote}: {out.strip()[:160]}")
            return False
        return True


def find_adb(explicit: str | None) -> str | None:
    if explicit:
        return explicit if Path(explicit).exists() else shutil.which(explicit)
    # 环境里已装 platform-tools，优先用它，其次 PATH
    for cand in (r"C:\platform-tools\adb.exe", "adb"):
        p = Path(cand)
        if p.exists():
            return str(p)
        w = shutil.which(cand)
        if w:
            return w
    return None


def list_devices(adb: str) -> list[tuple[str, str]]:
    """返回 [(serial, state), ...]"""
    rc, out = run([adb, "devices"])
    if rc != 0:
        return []
    rows = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            rows.append((parts[0], parts[1]))
    return rows


def choose_device(adb: str, want: str | None) -> str | None:
    rows = list_devices(adb)
    ready = [s for s, st in rows if st == "device"]
    if want:
        if want in ready:
            return want
        log(f"[x] 指定设备 {want} 不在就绪列表中：{rows}")
        return None
    if not ready:
        log("[x] 没有处于 device 状态的设备。")
        if rows:
            log(f"    当前列表：{rows}（unauthorized=手机未点『允许调试』，offline=授权过期/线材问题）")
        log("    检查清单：① USB 调试已开 ② 手机弹窗点『允许』并勾选始终允许 ③ 数据线支持传输 ④ 解锁屏幕")
        return None
    if len(ready) > 1:
        log(f"[!] 检测到多台设备 {ready}，请用 --serial 指定")
        return None
    return ready[0]


# --------------------------------------------------------------------------- #
# 采集原语
# --------------------------------------------------------------------------- #
def read_prop(dev: Device, key: str) -> str:
    _, out = dev.sh(["shell", "getprop", key])
    return out.strip()


def wm_size(dev: Device) -> str:
    _, out = dev.sh(["shell", "wm", "size"])
    m = re.findall(r"(\d+x\d+)", out)
    return m[-1] if m else "unknown"


def wm_density(dev: Device) -> str:
    _, out = dev.sh(["shell", "wm", "density"])
    m = re.findall(r"(\d+)", out)
    return m[-1] if m else "unknown"


def foreground(dev: Device) -> tuple[str, str]:
    """当前前台 (包名, Activity)；取不到返回空串。"""
    rc, out = dev.sh(["shell", "sh", "-c", "dumpsys window | grep -m3 -E 'mCurrentFocus|mFocusedApp'"])
    if rc != 0 or not out.strip():
        rc, out = dev.sh(["shell", "dumpsys", "window"])
    for pat in (
        r"mCurrentFocus=Window\{[0-9a-f]+\s+u\d+\s+([A-Za-z0-9_.]+)/([^\s}]+)",
        r"mFocusedApp=.*?u\d+\s+([A-Za-z0-9_.]+)/([^\s}]+)",
        r"mCurrentFocus=.*?u\d+\s+([A-Za-z0-9_.]+)/([^\s}]+)",
    ):
        m = re.search(pat, out)
        if m:
            return m.group(1), m.group(2)
    rc, out = dev.sh(["shell", "sh", "-c", "dumpsys activity activities | grep -m3 -E 'topResumedActivity|mResumedActivity'"])
    m = re.search(r"u\d+\s+([A-Za-z0-9_.]+)/([^\s}]+)", out)
    return (m.group(1), m.group(2)) if m else ("", "")


def foreground_pkg(dev: Device) -> str:
    return foreground(dev)[0]


def take_screenshot(dev: Device, local: Path) -> bool:
    rc, out = dev.sh(["shell", "screencap", "-p", REMOTE_SHOT], timeout=60)
    if rc != 0:
        log(f"    ! screencap 失败: {out.strip()[:160]}")
        return False
    ok = dev.pull(REMOTE_SHOT, local)
    dev.sh(["shell", "rm", "-f", REMOTE_SHOT])
    return ok


def dump_ui(dev: Device, local: Path, timeout: int = 60, attempts: int = 2) -> tuple[bool, str]:
    """uiautomator dump。返回 (是否成功, 原始提示)。

    广告页（视频/持续重绘的 WebView）经常等不到 idle，一调用就是十几秒才失败；
    发广告的循环里要用短超时，否则一次 dump 就把预算吃光。
    """
    note = ""
    for _ in range(max(1, attempts)):
        rc, out = dev.sh(["shell", "uiautomator", "dump", "--compressed", REMOTE_DUMP], timeout=timeout)
        note = out.strip()
        if rc == 0 and dev.pull(REMOTE_DUMP, local):
            dev.sh(["shell", "rm", "-f", REMOTE_DUMP])
            return True, note
        time.sleep(0.6)
    return False, note


def summarize_xml(path: Path | None) -> dict:
    """从控件树里抽要点：文本、可点击节点、以及 Flutter 语义树是否可见。"""
    out = {
        "nodes": 0,
        "with_text": 0,
        "with_desc": 0,
        "clickable": 0,
        "texts": [],
        "clickables": [],
        "package": "",
    }
    if path is None:
        return out
    try:
        root = ET.parse(path).getroot()
    except Exception:  # noqa: BLE001
        return out

    texts: list[str] = []
    seen: set[str] = set()
    for node in root.iter("node"):
        out["nodes"] += 1
        pkg = node.get("package") or ""
        if pkg and not out["package"]:
            out["package"] = pkg
        t = (node.get("text") or "").strip()
        d = (node.get("content-desc") or "").strip()
        if t and t not in SKIP_TEXTS:
            out["with_text"] += 1
        if d and d not in SKIP_TEXTS:
            out["with_desc"] += 1
        for v in (t, d):
            if v and v not in seen and len(v) <= 60:
                seen.add(v)
                texts.append(v)
        if node.get("clickable") == "true":
            out["clickable"] += 1
            if len(out["clickables"]) < 60:
                out["clickables"].append(
                    {
                        "t": t[:40],
                        "d": d[:40],
                        "id": (node.get("resource-id") or "")[-60:],
                        "b": node.get("bounds") or "",
                    }
                )
    out["texts"] = texts[:80]
    return out


def app_pid(dev: Device, pkg: str) -> str:
    if not pkg:
        return ""
    _, out = dev.sh(["shell", "pidof", pkg])
    out = out.strip()
    return out.split()[0] if out and out.isdigit() else ""


def detect_packages(dev: Device) -> list[str]:
    _, out = dev.sh(["shell", "pm", "list", "packages"], timeout=60)
    pkgs = []
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            pkgs.append(line.split(":", 1)[1].strip())
    return [p for p in pkgs if any(h in p.lower() for h in PKG_HINTS)]


# --------------------------------------------------------------------------- #
# probe
# --------------------------------------------------------------------------- #
def cmd_probe(dev: Device, outdir: Path, args: argparse.Namespace) -> int:
    log("=" * 68)
    log("外星仔加速器 · P0 体检")
    log("=" * 68)

    info = {
        "serial": dev.serial,
        "model": read_prop(dev, "ro.product.model"),
        "brand": read_prop(dev, "ro.product.brand"),
        "android": read_prop(dev, "ro.build.version.release"),
        "sdk": read_prop(dev, "ro.build.version.sdk"),
        "size": wm_size(dev),
        "density": wm_density(dev),
        "ts": datetime.now().isoformat(timespec="seconds"),
    }
    for k, v in info.items():
        log(f"  {k:9s} {v}")

    log("\n[1] 查找目标应用")
    cands = detect_packages(dev)
    if cands:
        for p in cands:
            log(f"  * {p}")
        info["packages"] = cands
    else:
        log("  ! 没找到包含 etalien/et001/waixing/alien 的包")
        log("    请确认手机已装好外星仔加速器；或用 --package 手动指定")

    pkg = cands[0] if cands else ""
    if pkg:
        if args.launch:
            log(f"  启动 {pkg} …")
            dev.sh(["shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"])
            time.sleep(4)
        pid = app_pid(dev, pkg)
        log(f"  进程 pid: {pid or '(未运行)'}")
        info["pid"] = pid
        fg = foreground(dev)
        log(f"  当前前台: {fg[0] or '(未知)'} / {fg[1] or '?'}")
        info["foreground"] = f"{fg[0]}/{fg[1]}"

    log("\n[2] 截屏 + 控件树（判断 Flutter 语义树是否可见）")
    outdir.mkdir(parents=True, exist_ok=True)
    shot = outdir / "probe.png"
    ok_shot = take_screenshot(dev, shot)
    log(f"  截图: {'OK -> ' + str(shot) if ok_shot else 'FAILED'}")

    dum = outdir / "probe.xml"
    ok_dump, note = dump_ui(dev, dum)
    log(f"  dump: {'OK' if ok_dump else 'FAILED'}  {note[:120]}")
    info["dump_ok"] = ok_dump

    if ok_dump:
        s = summarize_xml(dum)
        info["summary"] = s
        log(f"  节点数={s['nodes']} 带文本={s['with_text']} 带描述={s['with_desc']} 可点击={s['clickable']}")
        log(f"  包名={s['package'] or '?'}")
        if s["texts"]:
            log("  可见文本(前 20):")
            for t in s["texts"][:20]:
                log(f"    - {t}")
        if pkg and s["package"] and s["package"] != pkg:
            verdict = f"dump 到的是 {s['package']}，不是目标应用 -> 结论无效，请把外星仔切到前台后重跑"
        elif s["with_text"] + s["with_desc"] >= 5:
            verdict = "uiautomator 能看到 Flutter 控件 -> 走『控件树选点』，最稳"
        else:
            verdict = "控件树几乎是空的 -> Flutter 没开语义树，改走『图像模板匹配 + OCR』"
        log(f"\n  >>> 结论：{verdict}")
        info["verdict"] = verdict

    (outdir / "probe.json").write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"\n体检结果已存: {outdir / 'probe.json'}")
    return 0 if (ok_shot and ok_dump) else 1


# --------------------------------------------------------------------------- #
# record
# --------------------------------------------------------------------------- #
def cmd_record(dev: Device, outdir: Path, args: argparse.Namespace) -> int:
    pkg = args.package
    if not pkg:
        cands = detect_packages(dev)
        pkg = cands[0] if cands else ""
    if not pkg:
        log("[x] 无法确定目标包名，请用 --package com.xxx 指定")
        return 2

    shots = outdir / "shots"
    dumps = outdir / "dumps"
    shots.mkdir(parents=True, exist_ok=True)
    dumps.mkdir(parents=True, exist_ok=True)

    meta = {
        "ts": datetime.now().isoformat(timespec="seconds"),
        "serial": dev.serial,
        "model": read_prop(dev, "ro.product.model"),
        "android": read_prop(dev, "ro.build.version.release"),
        "size": wm_size(dev),
        "density": wm_density(dev),
        "package": pkg,
        "interval": args.interval,
        "duration": args.duration,
        "gate": not args.no_gate,
    }
    (outdir / "session.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    log("=" * 68)
    log(f"开始采样  设备={meta['model']}  {meta['size']}  包={pkg}")
    log(f"间隔={args.interval}s  时长={args.duration or '∞'}s  输出={outdir}")
    log("现在请在手机上手动走一遍完整流程（进入任务页 -> 点看广告 -> 等播完 -> 领奖）")
    log("Ctrl+C 结束")
    log("=" * 68)

    # logcat 后台收集（拿不到 pid 就跳过）
    logcat_proc = None
    logcat_path = outdir / "logcat.txt"
    if not args.no_logcat:
        pid = app_pid(dev, pkg)
        logcat_cmd = [dev.adb, "-s", dev.serial, "logcat", "-v", "time"]
        if pid:
            logcat_cmd.append(f"--pid={pid}")
        try:
            logcat_proc = subprocess.Popen(
                logcat_cmd,
                stdout=open(logcat_path, "wb"),
                stderr=subprocess.DEVNULL,
            )
            log(f"logcat 收集中 -> {logcat_path.name}（pid={pid or '未运行'}）")
        except Exception as exc:  # noqa: BLE001
            log(f"! logcat 启动失败: {exc}")

    idx_path = outdir / "index.jsonl"
    t0 = time.time()
    seq = 0
    last_fp = None
    last_png_seq = -99
    saved_png = 0
    clusters: dict[str, dict] = {}

    try:
        while True:
            seq += 1
            now = datetime.now()
            fg, act = foreground(dev)
            in_scope = (not args.no_gate) and (fg == pkg)

            rec: dict = {
                "seq": seq,
                "ts": now.isoformat(timespec="milliseconds"),
                "t": round(time.time() - t0, 2),
                "fg": fg,
                "act": act,
                "in_scope": in_scope,
            }

            if not in_scope:
                rec["skip"] = True
                with idx_path.open("a", encoding="utf-8") as fh:
                    fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
                if seq % 10 == 1:
                    log(f"[{seq:04d}] 前台={fg or '?'}（非目标应用，跳过截图）")
                time.sleep(args.interval)
                continue

            xml_path = dumps / f"{seq:04d}.xml"
            ok_dump, note = dump_ui(dev, xml_path)
            summary = summarize_xml(xml_path if ok_dump else None)
            rec.update(
                {
                    "nodes": summary["nodes"],
                    "clickable": summary["clickable"],
                    "texts": summary["texts"],
                    "clickables": summary["clickables"],
                }
            )

            fp = "|".join(sorted(summary["texts"]))[:4000]
            changed = fp != last_fp
            want_png = changed or (seq - last_png_seq >= args.png_every)
            if want_png:
                png_path = shots / f"{seq:04d}.png"
                if take_screenshot(dev, png_path):
                    rec["png"] = f"shots/{seq:04d}.png"
                    saved_png += 1
                    last_png_seq = seq
                else:
                    png_path.unlink(missing_ok=True)
            if ok_dump:
                rec["xml"] = f"dumps/{seq:04d}.xml"
            else:
                xml_path.unlink(missing_ok=True)
                rec["dump_error"] = note[:200]

            with idx_path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(rec, ensure_ascii=False) + "\n")

            key = fp if fp else "(空界面)"
            c = clusters.setdefault(key, {"count": 0, "texts": summary["texts"][:8], "first": seq})
            c["count"] += 1

            head = " / ".join(summary["texts"][:3]) or "(无文本)"
            flag = "STATE*" if changed else "      "
            log(f"[{seq:04d}] {now:%H:%M:%S} {flag} 节点={summary['nodes']:3d} 点击={summary['clickable']:2d} | {head[:90]}")
            last_fp = fp

            if args.duration and (time.time() - t0) >= args.duration:
                break
            time.sleep(args.interval)
    except KeyboardInterrupt:
        log("\n收到 Ctrl+C，收尾中…")
    finally:
        if logcat_proc is not None:
            logcat_proc.terminate()
            try:
                logcat_proc.wait(timeout=5)
            except Exception:  # noqa: BLE001
                logcat_proc.kill()
        dev.sh(["shell", "rm", "-f", REMOTE_SHOT, REMOTE_DUMP])

    # 收尾统计
    log("\n" + "=" * 68)
    log(f"采样结束：{seq} 帧，截图 {saved_png} 张，界面状态 {len(clusters)} 种")
    top = sorted(clusters.items(), key=lambda kv: -kv[1]["count"])[:10]
    for i, (_, c) in enumerate(top, 1):
        log(f"  #{i} x{c['count']:<3d} 首现帧 {c['first']:<4d} | {' / '.join(c['texts'])[:100]}")
    total = sum(p.stat().st_size for p in outdir.rglob("*") if p.is_file())
    log(f"占用磁盘：{total / 1024 / 1024:.1f} MB  ->  {outdir}")
    log("=" * 68)
    return 0


# --------------------------------------------------------------------------- #
def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    ap = argparse.ArgumentParser(description="外星仔看广告 P0 观察者（只读，不点击）")
    ap.add_argument("mode", nargs="?", default="probe", choices=["probe", "record"])
    ap.add_argument("--adb", default=None, help="adb 路径，默认自动查找")
    ap.add_argument("--serial", default=None, help="指定设备序列号")
    ap.add_argument("--out", default="out", help="输出根目录")
    ap.add_argument("--package", default=None, help="目标包名，默认按关键词自动识别")
    ap.add_argument("--interval", type=float, default=3.0, help="采样间隔秒，默认 3")
    ap.add_argument("--duration", type=int, default=600, help="采样时长秒，0=不限，默认 600")
    ap.add_argument("--png-every", type=int, default=5, help="界面无变化时最多隔多少帧存一张图")
    ap.add_argument("--no-gate", action="store_true", help="不对前台应用做过滤（会录到其它 App，慎用）")
    ap.add_argument("--no-logcat", action="store_true", help="不收集 logcat")
    ap.add_argument("--launch", action="store_true", help="probe 前先把目标应用拉到前台")
    args = ap.parse_args()

    adb = find_adb(args.adb)
    if not adb:
        log("[x] 找不到 adb，请装 platform-tools 或用 --adb 指定")
        return 2

    serial = choose_device(adb, args.serial)
    if not serial:
        return 2
    dev = Device(adb, serial)
    log(f"设备已连接: {serial}\n")

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    outdir = Path(args.out) / stamp

    if args.mode == "probe":
        return cmd_probe(dev, outdir, args)
    return cmd_record(dev, outdir, args)


if __name__ == "__main__":
    raise SystemExit(main())
