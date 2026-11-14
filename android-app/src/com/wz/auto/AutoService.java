package com.wz.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
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
    private volatile long lastKick = 0;

    // 广告素材最爱跳的几家；检测到就秒退，别等下一轮轮询
    private static final String[] JUMPERS = {
            "com.jingdong.app.mall", "com.taobao.taobao", "com.tmall.wireless",
            "com.xunmeng.pinduoduo", "com.tencent.mm", "com.eg.android.AlipayGphone",
            "com.huawei.browser", "com.android.browser", "com.sina.weibo",
            "com.ss.android.ugc.aweme", "com.qiyi.video", "tv.danmaku.bili"};

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
        boolean wasRunning = running;
        INST = this;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "wzauto:run");
            wake.setReferenceCounted(false);
        }
        note(wasRunning ? "无障碍服务重连，任务继续" : "无障碍服务已连接，可以开始");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        INST = null;
        // 注意：这里不要置 running=false。华为会周期性重建无障碍服务，
        // 一旦清掉标志，跑到一半的任务会被误判成「已结束」。
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent e) {
        if (e.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence c = e.getClassName();
            if (c != null) lastClass = c.toString();
            CharSequence p = e.getPackageName();
            if (p != null) {
                lastPkg = p.toString();
                // 广告跳走的一瞬间就退回来，比 400ms 轮询快得多
                if (running && SystemClock.uptimeMillis() - lastKick > 1200) {
                    for (String j : JUMPERS) {
                        if (j.equals(lastPkg)) {
                            lastKick = SystemClock.uptimeMillis();
                            note("秒退外部应用 " + lastPkg);
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            break;
                        }
                    }
                }
            }
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
        // 按首页状态决策：加载中就等，加载失败就点重试，就绪才点领取
        boolean started = false;
        int noFill = 0;
        for (int attempt = 0; attempt < 4 && running && !started; attempt++) {
            Home cur = (attempt == 0) ? h : readHome();
            if (cur == null) {
                sleep(1500);
                continue;
            }
            if (cur.doneToday) {
                note("首页提示今日已看完：" + cur.label);
                stopReason = "今日已看完";
                running = false;
                return false;
            }
            if ("loading".equals(cur.state)) {
                note("首页状态：广告加载中，等待");
                setStatus("第 " + round + " 轮 · 等待广告加载");
                sleep(6000);
                continue;
            }
            if ("failed".equals(cur.state)) {
                note("首页状态：广告加载失败，点重试");
            } else if ("ready".equals(cur.state)) {
                note("首页状态：就绪，点领取");
            } else {
                note("首页状态：" + cur.state + " 标签=" + cur.label);
            }
            if (cur.button == null) {
                sleep(2500);
                continue;
            }
            clickNode(cur.button);
            sleep(900);
            started = waitAdStart(40000);
            if (!started) {
                noFill++;
                note("没拉到广告（第 " + noFill + " 次）");
                sleep(1500);
            }
        }
        if (!started) {
            // 连续失败通常是广告 SDK 状态坏了：重新拉起 App 再试一次
            note("连续拉不到广告，重新拉起 App");
            goHomeScreen();
            launchApp();
            sleep(6000);
            Home again = ensureHome();
            if (again != null && again.button != null) {
                clickNode(again.button);
                sleep(900);
                started = waitAdStart(40000);
            }
        }
        if (!started) {
            note("多次重试仍无广告，先停手");
            stopReason = "广告拉不到";
            Push.send(this, "外星仔：广告拉不到",
                    "连续多次没有填充，可能需要手动重启外星仔。已完成 " + done + " 轮。");
            running = false;
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
        for (int k = 0; k < 5 && !atHome() && running; k++) {
            back();
            sleep(1000);
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
        String state = "unknown"; // ready / loading / failed / done / none
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
            if (i == 2 && !atHome()) {
                // 两轮都回不去，多半是广告 Activity 卡住了，直接强停重开
                if (forceStopViaSettings()) {
                    Home h = readHome();
                    if (h != null && h.duration >= 0) return h;
                }
            }
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

    /**
     * App 没有 force-stop 权限，但「设置 → 应用信息」里有「强行停止」按钮。
     * 广告 Activity 赖在最上层时，这是唯一能真正把外星仔杀干净的办法。
     */
    private boolean forceStopViaSettings() {
        note("借系统设置强停外星仔");
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + PKG));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            note("打开应用信息页失败：" + t);
            return false;
        }
        sleep(2200);
        boolean clicked = false;
        for (int k = 0; k < 8 && !clicked; k++) {
            Snap s = snap();
            if (s.root == null) {
                sleep(700);
                continue;
            }
            for (String t : new String[]{"强行停止", "强制停止"}) {
                if (clickText(s, t)) {
                    clicked = true;
                    break;
                }
            }
            if (!clicked) sleep(700);
        }
        if (!clicked) {
            note("没找到「强行停止」按钮，放弃");
            goHomeScreen();
            return false;
        }
        sleep(1400);
        // 确认弹窗
        Snap s2 = snap();
        if (!clickText(s2, "确定")) clickText(s2, "强行停止");
        sleep(1200);
        note("已强行停止外星仔");
        goHomeScreen();
        sleep(800);
        launchApp();
        sleep(3000);
        return true;
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
        String lb = h.label;
        if (h.doneToday || lb.contains("已看完") || lb.contains("上限") || lb.contains("已领")) {
            h.state = "done";
        } else if (lb.contains("加载中") || lb.contains("稍等")) {
            h.state = "loading";
        } else if (lb.contains("加载失败") || lb.contains("重试")) {
            h.state = "failed";
        } else if (lb.contains("领时长") && s.submit != null) {
            h.state = "ready";
        } else {
            h.state = "none";
        }
        return h;
    }

    private boolean verify(Home before) {
        setStatus("第 " + round + " 轮 · 等到账");
        long t0 = SystemClock.uptimeMillis();
        int baseProgress = progressOf(before.stages);
        int relaunch = 0;
        while (SystemClock.uptimeMillis() - t0 < 90000 && running) {
            Home now = readHome();
            if (now == null || now.duration < 0) {
                if (SystemClock.uptimeMillis() - t0 > 20000 && relaunch < 2) {
                    relaunch++;
                    note("首页读不出数据，重启 App 重新拉（" + relaunch + "/2）");
                    goHomeScreen();
                    launchApp();
                    sleep(3000);
                } else if (relaunch >= 2) {
                    note("首页始终读不出，放弃本轮校验");
                    return false;
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

    enum AdKind {VIDEO, BAIDU, H5, CJ, OTHER}

    private AdKind classify(String cls) {
        if (cls == null) return AdKind.OTHER;
        String c = cls.toLowerCase(Locale.ROOT);
        // 真正的 H5 落地页：百青藤 Stub_Standard（对无障碍完全不可见）
        if (c.contains("byazt") || c.contains("stub_standard")) return AdKind.H5;
        // 百度 MobRewardVideoActivity 是视频，但页面热点多，点了会跳京东，所以不点 X
        if (c.contains("mobad") || c.contains("mobreward")) return AdKind.BAIDU;
        if (c.contains("cj.mobile")) return AdKind.CJ;
        if (c.contains("kwad") || c.contains("qq.e") || c.contains("ptg") || c.contains("reward")) return AdKind.VIDEO;
        return AdKind.OTHER;
    }

    private boolean waitAdStart(long ms) {
        long t0 = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - t0 < ms && running) {
            // 广告 Activity 起来后 lastClass 就不再是 MainActivity
            if (!lastClass.endsWith("MainActivity")) return true;
            Snap s = snap();
            if (s.root != null && !s.pkg.isEmpty() && !PKG.equals(s.pkg)) return true;
            sleep(400);
        }
        return false;
    }

    private String handleAd(AdKind kind) {
        long t0 = SystemClock.uptimeMillis();
        int taps = 0, dialogs = 0;
        long lastBeat = -20000;
        int lastCloseX = -1, lastCloseY = -1, sameClose = 0;
        while (running) {
            long el = SystemClock.uptimeMillis() - t0;
            setStatus("第 " + round + " 轮 · 广告中 " + (el / 1000) + "s · " + kind);
            if (el - lastBeat > 20000) {
                lastBeat = el;
                note("心跳 " + (el / 1000) + "s · " + kind);
            }
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
                    if (c.x == lastCloseX && c.y == lastCloseY) {
                        sameClose++;
                    } else {
                        sameClose = 0;
                        lastCloseX = c.x;
                        lastCloseY = c.y;
                    }
                    // 同一个坐标点两次没反应就是假关闭，别再连点
                    if (sameClose < 2) {
                        note("点关闭控件 " + c.x + "," + c.y);
                        tapXY(c.x, c.y);
                        sleep(1400);
                        continue;
                    }
                }
            }
            if (kind == AdKind.H5 && el > 22000) {
                note("H5 看不见，放弃");
                back();
                sleep(1500);
                return "timeout";
            }
            if (kind == AdKind.BAIDU && el > 45000) {
                note("百度视频广告 45 秒收场");
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
