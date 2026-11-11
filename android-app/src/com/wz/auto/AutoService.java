package com.wz.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 用无障碍服务复刻 PC 版 auto.py 的策略：
 * 首页只点「看广告领时长」，广告页只点关闭/放弃，绝不点「跳过」和页面热点。
 */
public class AutoService extends AccessibilityService {

    static final String PKG = "com.etalien.booster";
    static final String TAG = "WZAuto";

    // ---- 全局状态（给界面看）----
    public static volatile boolean running = false;
    static volatile AutoService INST = null;
    static final ArrayDeque<String> LOG = new ArrayDeque<>();
    static volatile int done = 0, ok = 0;

    private volatile Thread worker;
    private PowerManager.WakeLock wake;
    private volatile String lastClass = "";
    private volatile String lastPkg = "";

    static void note(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date()) + "  " + s;
        synchronized (LOG) {
            LOG.addLast(line);
            while (LOG.size() > 300) LOG.removeFirst();
        }
        android.util.Log.i(TAG, line);
    }

    static String dumpLogTail(int n) {
        StringBuilder sb = new StringBuilder();
        synchronized (LOG) {
            int skip = Math.max(0, LOG.size() - n);
            int i = 0;
            for (String s : LOG) {
                if (i++ >= skip) sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    static String dumpLog() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOG) {
            for (String s : LOG) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static volatile int round = 0;
    public static volatile String STATUS = "空闲";
    static volatile String stopReason = "正常结束";

    static void setStatus(String s) {
        STATUS = s;
        Overlay.set("外星仔自动化\n" + s);
    }

    static void startRun(int rounds) {
        AutoService s = INST;
        if (s == null) {
            note("无障碍服务没在运行，请先在系统设置里开启");
            return;
        }
        s.begin(rounds);
    }

    static void stopRun() {
        running = false;
        note("收到停止指令");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INST = this;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "wzauto:run");
            wake.setReferenceCounted(false);
        }
        note("无障碍服务已连接，可以开始");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        INST = null;
        running = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent e) {
        if (e.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence c = e.getClassName();
            if (c != null) lastClass = c.toString();
            CharSequence p = e.getPackageName();
            if (p != null) lastPkg = p.toString();
        }
    }

    @Override
    public void onInterrupt() {
    }

    // ---------------- 主循环 ----------------

    private void begin(final int rounds) {
        if (worker != null && worker.isAlive()) {
            note("已经在跑了");
            return;
        }
        running = true;
        done = 0;
        ok = 0;
        round = 0;
        stopReason = "正常结束";
        Overlay.show(this);
        setStatus("已启动，共 " + rounds + " 轮");
        if (wake != null && !wake.isHeld()) wake.acquire();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 1; i <= rounds && running; i++) {
                        round = i;
                        setStatus("第 " + i + "/" + rounds + " 轮 · 开始");
                        note("—— 第 " + i + "/" + rounds + " 轮 ——");
                        boolean good;
                        try {
                            good = doRound();
                        } catch (Throwable t) {
                            note("本轮异常：" + t);
                            good = false;
                        }
                        done = i;
                        if (good) ok++;
                        if (!running) break;
                        if (i < rounds) sleep(good ? 25000 + rnd(30000) : 15000);
                    }
                } finally {
                    running = false;
                    if (wake != null && wake.isHeld()) wake.release();
                    setStatus("已结束：共 " + done + " 轮");
                    String summary = "原因：" + stopReason + "\n跑了 " + done + " 轮，成功 "
                            + ok + " 轮。\n\n" + AutoService.dumpLogTail(12);
                    note("任务结束：跑了 " + done + " 轮，成功 " + ok + " 轮");
                    Push.send(AutoService.this, "外星仔自动化结束（" + stopReason + "）", summary);
                    sleep(1500);
                    Overlay.hide();
                }
            }
        }, "wz-worker");
        worker.start();
    }

    private boolean doRound() {
        Home h = ensureHome();
        if (h == null) {
            note("回不到首页，放弃本轮");
            return false;
        }
        note("起始 时长=" + h.durationText() + " 阶段=" + h.stages);
        setStatus("第 " + round + " 轮 · 首页 " + h.durationText());
        if (h.doneToday) {
            note("首页提示今日已看完：" + h.label);
            stopReason = "今日已看完";
            running = false;
            return false;
        }
        if (!h.hasTask()) {
            note("三档都做完了，收工");
            stopReason = "三档已完成";
            running = false;
            return false;
        }
        if (h.button == null) {
            note("首页按钮不可点，等一会儿");
            sleep(4000);
            h = ensureHome();
            if (h == null || h.button == null) return false;
        }
        clickNode(h.button);
        sleep(800);

        if (!waitAdStart(30000)) {
            note("没拉到广告");
            return false;
        }
        AdKind kind = classify(lastClass);
        note("广告开始 " + lastClass + " → " + kind);
        setStatus("第 " + round + " 轮 · 广告中 " + kind);

        String reason = handleAd(kind);
        note("广告结束：" + reason);
        if ("external".equals(reason)) {
            back();
            sleep(1600);
        }
        // 没有 force-stop 权限，只能先连按返回把广告页退掉，再重新拉起
        for (int k = 0; k < 3 && !atHome() && running; k++) {
            back();
            sleep(900);
        }
        if (!atHome()) {
            goHomeScreen();
            launchApp();
            sleep(2600);
        }
        if (!atHome()) {
            launchApp();
            sleep(2800);
        }
        return verify(h);
    }

    // ---------------- 首页 ----------------

    static class Home {
        int duration = -1;
        List<String> stages = new ArrayList<>();
        AccessibilityNodeInfo button;
        String label = "";
        boolean doneToday = false;

        String durationText() {
            return duration < 0 ? "?" : (duration / 60) + " 分";
        }

        boolean hasTask() {
            for (String s : stages) if (s.matches("\\d+ / \\d+")) return true;
            return false;
        }
    }

    private Home ensureHome() {
        for (int i = 0; i < 5 && running; i++) {
            if (!atHome()) {
                goHomeScreen();
                launchApp();
            }
            long t0 = SystemClock.uptimeMillis();
            while (SystemClock.uptimeMillis() - t0 < 9000 && running) {
                Home h = readHome();
                if (h != null && h.duration >= 0) return h;
                sleep(600);
            }
        }
        return null;
    }

    private boolean atHome() {
        // 无障碍服务收不到「自己 App」的窗口事件，所以 lastPkg/lastClass 会停在
        // 上一次外星仔的界面；必须直接读当前窗口的包名，否则会把本 App 当成首页
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        if (root == null) return false;
        CharSequence p = root.getPackageName();
        if (p == null || !PKG.contentEquals(p)) return false;
        return lastClass.endsWith("MainActivity");
    }

    private void launchApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(PKG);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable t) {
            note("拉起失败：" + t);
        }
    }

    private void goHomeScreen() {
        performGlobalAction(GLOBAL_ACTION_HOME);
        sleep(600);
    }

    private Home readHome() {
        Snap s = snap();
        if (s.root == null) return null;
        Home h = new Home();
        h.duration = parseDuration(s.texts);
        for (String t : s.texts) {
            if (t.matches("\\d+ / \\d+") || t.equals("已完成") || t.equals("待解锁")) h.stages.add(t);
            if (t.contains("广告") && (t.contains("领时长") || t.contains("稍等")
                    || t.contains("已领") || t.contains("上限") || t.contains("重试"))) h.label = t;
            if (t.contains("明日再来") || t.contains("明天再来")
                    || (t.contains("今日") && (t.contains("看完") || t.contains("领完")
                    || t.contains("已达上限") || t.contains("已上限")))) {
                h.doneToday = true;
                h.label = "命中:" + t;
            }
        }
        h.button = s.submit;
        return h;
    }

    private boolean verify(Home before) {
        setStatus("第 " + round + " 轮 · 等到账");
        long t0 = SystemClock.uptimeMillis();
        int baseProgress = progressOf(before.stages);
        while (SystemClock.uptimeMillis() - t0 < 90000 && running) {
            Home now = readHome();
            if (now == null || now.duration < 0) {
                if (SystemClock.uptimeMillis() - t0 > 20000) {
                    note("首页读不出数据，重启 App 重新拉");
                    goHomeScreen();
                    launchApp();
                    sleep(3000);
                }
                sleep(2000);
                continue;
            }
            boolean byDuration = before.duration >= 0 && now.duration - before.duration >= 540;
            int p = progressOf(now.stages);
            boolean byProgress = baseProgress >= 0 && p > baseProgress;
            if (byDuration || byProgress) {
                note("到账确认：时长 " + before.durationText() + " → " + now.durationText());
                return true;
            }
            sleep(2500);
        }
        note("没等到到账（可能延迟，下一轮会体现）");
        return false;
    }

    private int progressOf(List<String> stages) {
        int sum = 0;
        for (String s : stages) {
            if (s.equals("已完成")) sum += 100;
            else if (s.matches("\\d+ / \\d+")) {
                String[] p = s.split("/");
                sum += Integer.parseInt(p[0].trim());
            }
        }
        return sum;
    }

    // ---------------- 广告 ----------------

    enum AdKind {VIDEO, H5, CJ, OTHER}

    private AdKind classify(String cls) {
        if (cls == null) return AdKind.OTHER;
        String c = cls.toLowerCase(Locale.ROOT);
        if (c.contains("byazt") || c.contains("mobad") || c.contains("stub_standard")) return AdKind.H5;
        if (c.contains("cj.mobile")) return AdKind.CJ;
        if (c.contains("kwad") || c.contains("qq.e") || c.contains("ptg") || c.contains("reward")) return AdKind.VIDEO;
        return AdKind.OTHER;
    }

    private boolean waitAdStart(long ms) {
        long t0 = SystemClock.uptimeMillis();
        int gone = 0;
        while (SystemClock.uptimeMillis() - t0 < ms && running) {
            if (!atHome()) return true;
            Snap s = snap();
            if (s.submit == null) {
                gone++;
                if (gone >= 2) return true;
            } else {
                gone = 0;
            }
            sleep(400);
        }
        return false;
    }

    private String handleAd(AdKind kind) {
        long t0 = SystemClock.uptimeMillis();
        int taps = 0, dialogs = 0;
        while (running) {
            long el = SystemClock.uptimeMillis() - t0;
            setStatus("第 " + round + " 轮 · 广告中 " + (el / 1000) + "s · " + kind);
            if (el > 110000) break;
            Snap s = snap();
            if (s.root == null) {
                sleep(400);
                continue;
            }
            String pkg = s.pkg;
            if (!TextUtils.isEmpty(pkg) && !pkg.equals(PKG)) {
                note("跳到了 " + pkg);
                return "external";
            }
            String joined = TextUtils.join(" ", s.texts);

            if (joined.contains("确认要离开吗")) {
                dialogs++;
                note("离开弹窗 x" + dialogs);
                if (dialogs > 2) {
                    back();
                    sleep(1500);
                    return "timeout";
                }
                if (!clickText(s, "放弃奖励离开")) tapXY(cx(324), cy(1328));
                sleep(1800);
                continue;
            }
            if (joined.contains("奖励将于")) {
                if (el > 12000) {
                    tapX();
                    sleep(1800);
                }
                continue;
            }
            if (kind != AdKind.CJ) {
                Pt c = findClose(s);
                if (c != null) {
                    note("点关闭控件 " + c.x + "," + c.y);
                    tapXY(c.x, c.y);
                    sleep(1400);
                    continue;
                }
            }
            if (kind == AdKind.H5 && el > 22000) {
                note("H5 看不见，放弃");
                back();
                sleep(1500);
                return "timeout";
            }
            if (kind == AdKind.VIDEO) {
                if (el > 65000 && taps < 2) {
                    taps++;
                    note("盲点右上角 X (" + taps + ")");
                    tapX();
                    sleep(2200);
                    continue;
                }
                if (taps >= 2 && el > 90000) {
                    note("结束页救不回来，退出");
                    back();
                    sleep(1500);
                    return "timeout";
                }
            }
            sleep(400);
        }
        back();
        sleep(1500);
        return "timeout";
    }

    static class Pt {
        int x, y;

        Pt(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private Pt findClose(Snap s) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        for (int i = 0; i < s.nodes.size(); i++) {
            AccessibilityNodeInfo n = s.nodes.get(i);
            String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase(Locale.ROOT);
            String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            String t = n.getText() == null ? "" : n.getText().toString();
            if (t.contains("跳过") || id.contains("close_tip") || id.contains("skip")) continue;
            boolean hit = t.contains("关闭") || d.contains("关闭") || id.contains("close_button")
                    || id.contains("btn_close") || id.contains("icon_close") || id.contains("closebtn");
            if (!hit) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long area = (long) r.width() * r.height();
            if (area <= 0 || area > 320 * 320) continue;
            int cxp = r.centerX(), cyp = r.centerY();
            if (!(cxp > w * 0.55f && cyp < h * 0.22f)) continue; // 只认右上角
            return new Pt(cxp, cyp);
        }
        return null;
    }

    private void tapX() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        tapXY(Math.round(w * 985f / 1080f), Math.round(h * 173f / 2340f));
    }

    private int cx(int ref) {
        return Math.round(getResources().getDisplayMetrics().widthPixels * ref / 1080f);
    }

    private int cy(int ref) {
        return Math.round(getResources().getDisplayMetrics().heightPixels * ref / 2340f);
    }

    // ---------------- 基础操作 ----------------

    private void back() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private void tapXY(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, 60);
        GestureDescription g = new GestureDescription.Builder().addStroke(st).build();
        dispatchGesture(g, null, null);
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            cur = cur.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() > 0) {
            tapXY(r.centerX(), r.centerY());
            return true;
        }
        return false;
    }

    private boolean clickText(Snap s, String text) {
        for (AccessibilityNodeInfo n : s.nodes) {
            CharSequence t = n.getText();
            if (t != null && t.toString().equals(text)) {
                if (clickNode(n)) return true;
            }
        }
        return false;
    }

    static class Snap {
        AccessibilityNodeInfo root;
        String pkg = "";
        final List<String> texts = new ArrayList<>();
        final List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        AccessibilityNodeInfo submit;
    }

    private Snap snap() {
        Snap s = new Snap();
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            return s;
        }
        if (root == null) return s;
        s.root = root;
        CharSequence p = root.getPackageName();
        if (p != null) s.pkg = p.toString();
        walk(root, s, 0);
        return s;
    }

    private void walk(AccessibilityNodeInfo n, Snap s, int depth) {
        if (n == null || depth > 40) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) s.texts.add(t.toString());
        String id = n.getViewIdResourceName();
        if (id != null && id.endsWith("UIADSubmit")) s.submit = n;
        s.nodes.add(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) walk(c, s, depth + 1);
        }
    }

    private int parseDuration(List<String> texts) {
        int total = 0;
        boolean found = false;
        for (int i = 1; i < texts.size(); i++) {
            String t = texts.get(i);
            String prev = texts.get(i - 1);
            if (!prev.matches("\\d+")) continue;
            int mul = 0;
            if (t.equals("时")) mul = 3600;
            else if (t.equals("分")) mul = 60;
            else if (t.equals("秒")) mul = 1;
            if (mul > 0) {
                total += Integer.parseInt(prev) * mul;
                found = true;
            }
        }
        return found ? total : -1;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private int rnd(int max) {
        return (int) (Math.random() * max);
    }
}
