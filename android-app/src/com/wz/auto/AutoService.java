package com.wz.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
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
    private volatile int failStreak = 0;

    private volatile Thread worker;
    private HandlerThread bgThread;
    private volatile Handler bgHandler;
    private int beatSec = 0;
    private final Handler beatH = new Handler(Looper.getMainLooper());
    private final Runnable beatR = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            beatSec++;
            refreshOverlay("外星仔 #" + beatSec + "\n" + STATUS + "\n成功 " + ok + " / " + done);
            // 看门狗：服务实例丢了也要能重建，不能只在读页面时才自愈
            if (INST == null && SystemClock.uptimeMillis() - lastRebind > 60000) {
                lastRebind = SystemClock.uptimeMillis();
                note("悬浮窗看门狗：无障碍服务实例丢失，重建连接");
                toggleAccessibility();
            }
            beatH.postDelayed(this, 1000);
        }
    };
    private PowerManager.WakeLock wake;
    private volatile String lastClass = "";
    private volatile String lastPkg = "";
    private volatile long lastKick = 0;
    private volatile long lastScanLog = 0;
    private static volatile long lastRebind = 0;

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
    static volatile int TARGET = 21;
    static volatile int GAP_SEC = 15;
    private static volatile long RUN_START = 0;
    private static volatile boolean ACTIVE = false;
    static volatile android.content.Context APP_CTX = null;
    private static volatile long LAST_SAVE = 0;
    private volatile boolean workerExit = false;
    private android.content.SharedPreferences prefs;
    private int deadBeats = 0;
    private int noFillRounds = 0;
    private long restartGuardAt = 0;
    public static volatile String STATUS = "空闲";
    static volatile String stopReason = "正常结束";

    /** 刷新悬浮窗：掉线了自动补挂，再写文字。 */
    static void refreshOverlay(String text) {
        try {
            if (!Overlay.isShown()) {
                android.content.Context c = (INST != null) ? INST : APP_CTX;
                if (c != null) Overlay.show(c);
            }
        } catch (Throwable ignored) {
        }
        Overlay.set(text);
    }

    static void setStatus(String s) {
        STATUS = s;
        // 悬浮窗可能因为服务重建而丢失，这里顺手补挂回来
        refreshOverlay("外星仔自动化\n" + s);
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
        if (APP_CTX == null) APP_CTX = getApplicationContext();
        if (bgThread == null) {
            bgThread = new HandlerThread("wz-bg");
            bgThread.start();
            bgHandler = new Handler(bgThread.getLooper());
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "wzauto:run");
            wake.setReferenceCounted(false);
        }
        prefs = getSharedPreferences("run", MODE_PRIVATE);
        boolean savedRunning = prefs.getBoolean("running", false);
        note(wasRunning ? "无障碍服务重连，任务继续" : "无障碍服务已连接，可以开始");
        // 切开关重建连接后，进程还在、进度也在静态变量里，直接续跑
        if (wasRunning && (worker == null || !worker.isAlive())) {
            note("连接已恢复，" + 4 + " 秒后从第 " + (done + 1) + " 轮继续");
            final int t = TARGET, d = done, o2 = ok;
            beatH.postDelayed(new Runnable() {
                int retry = 0;
                @Override
                public void run() {
                    if (worker != null && worker.isAlive()) {
                        retry++;
                        if (retry % 5 == 0) note("旧线程还没退（" + retry + " 次），继续等");
                        if (retry < 20) {
                            beatH.postDelayed(this, 1500);
                        } else {
                            note("旧线程一直没退，强制接手");
                            resume(t, d, o2);
                        }
                        return;
                    }
                    resume(t, d, o2);
                }
            }, 4000);
            return;
        }
        long savedAt = prefs.getLong("savedAt", 0);
        boolean fresh = System.currentTimeMillis() - savedAt < 10 * 60 * 1000L;
        if (!wasRunning && savedRunning && !fresh) {
            note("发现很久以前的未完成任务，直接作废");
            prefs.edit().putBoolean("running", false).apply();
        }
        if (!wasRunning && savedRunning && fresh) {
            // 上一次进程因为连接失效被自己杀掉了，这里自动接着跑
            int target = prefs.getInt("target", 21);
            int savedDone = prefs.getInt("done", 0);
            int savedOk = prefs.getInt("ok", 0);
            note("检测到未完成的任务（已跑 " + savedDone + "/" + target + "），自动续跑");
            resume(target, savedDone, savedOk);
        }
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
                            // performGlobalAction 是同步跨进程调用，直接放在事件回调里
                            // 会把主线程（包括悬浮窗刷新）卡住，必须挪到后台线程
                            final Handler h = bgHandler;
                            if (h != null) {
                                h.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        performGlobalAction(GLOBAL_ACTION_BACK);
                                    }
                                });
                            }
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
        if (ACTIVE || (worker != null && worker.isAlive())) {
            note("已经有任务在跑，忽略这次点击");
            return;
        }
        ACTIVE = true;
        running = true;
        done = 0;
        ok = 0;
        round = 0;
        failStreak = 0;
        deadBeats = 0;
        workerExit = false;
        TARGET = rounds;
        GAP_SEC = getSharedPreferences("wz", MODE_PRIVATE).getInt("gap", 15);
        RUN_START = System.currentTimeMillis();
        if (prefs == null) prefs = getSharedPreferences("run", MODE_PRIVATE);
        prefs.edit().putBoolean("running", true).putInt("target", rounds)
                .putInt("done", 0).putInt("ok", 0).putLong("savedAt", System.currentTimeMillis()).apply();
        stopReason = "正常结束";
        Overlay.show(this);
        beatSec = 0;
        beatH.removeCallbacks(beatR);
        beatH.post(beatR);
        setStatus("已启动，共 " + rounds + " 轮");
        if (wake != null && !wake.isHeld()) wake.acquire();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 1; i <= rounds && running && !workerExit; i++) {
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
                        if (prefs != null) {
                            prefs.edit().putInt("done", done).putInt("ok", ok).putLong("savedAt", System.currentTimeMillis()).apply();
                        }
                        if (good) {
                            ok++;
                            failStreak = 0;
                        } else if (!workerExit) {
                            failStreak++;
                            if (failStreak >= 3) {
                                note("连续 " + failStreak + " 轮失败，先停手等你处理");
                                stopReason = "连续失败";
                                Push.send(AutoService.this, "外星仔：连续失败已暂停",
                                        "连续 " + failStreak + " 轮没跑通，可能是外星仔卡住了。\n"
                                                + AutoService.dumpLogTail(8));
                                running = false;
                                break;
                            }
                        }
                        if (!running) break;
                        if (i < rounds) sleep(good ? GAP_SEC * 1000L : 15000);
                    }
                } finally {
                    if (workerExit) {
                        ACTIVE = false;
                        note("等待无障碍重连后自动续跑（resume）");
                        return;
                    }
                    running = false;
                    if (prefs != null) prefs.edit().putBoolean("running", false).apply();
                    beatH.removeCallbacks(beatR);
                    if (wake != null && wake.isHeld()) wake.release();
                    setStatus("已结束：共 " + done + " 轮");
                    long cost = RUN_START > 0 ? (System.currentTimeMillis() - RUN_START) / 1000 : 0;
                    String costText = cost > 0
                            ? (cost / 60) + " 分 " + (cost % 60) + " 秒" : "未知";
                    String summary = "原因：" + stopReason + "\n跑了 " + done + " 轮，成功 "
                            + ok + " 轮\n用时 " + costText + "\n\n" + AutoService.dumpLogTail(12);
                    note("任务结束：跑了 " + done + " 轮，成功 " + ok + " 轮，用时 " + costText);
                    Push.send(AutoService.this, "外星仔自动化结束（" + stopReason + "）", summary);
                    Overlay.set("外星仔：已结束\n跑了 " + done + " 轮 · 成功 " + ok + " 轮 / " + stopReason);
                }
            }
        }, "wz-worker");
        worker.start();
    }

    private boolean doRound() {
        if (workerExit) return false;
        Snap pre = snap();
        if (pre.ok) {
            recoverUnknownPage(pre);
            if (pre.pkg.equals(getPackageName())) {
                note("当前停在本 App 界面，拉回外星仔");
                goHomeScreen();
                launchApp();
                sleep(3000);
            }
        }
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
            // 凌晨经常整段没有填充，等一会儿再试，别直接收工
            noFillRounds++;
            if (noFillRounds <= 3) {
                int waitMin = 3;
                note("暂无广告填充，等 " + waitMin + " 分钟后重试（第 " + noFillRounds + "/3 次）");
                setStatus("第 " + round + " 轮 · 无填充，等待 " + waitMin + " 分钟");
                sleep(waitMin * 60 * 1000L);
                ensureHome();
                return false;
            }
            note("多次重试仍无广告，先停手");
            stopReason = "广告拉不到";
            Push.send(this, "外星仔：广告拉不到",
                    "连续多次没有填充，可能需要手动重启外星仔。已完成 " + done + " 轮。");
            running = false;
            return false;
        }
        noFillRounds = 0;
        AdKind kind = classify(lastClass);
        note("广告开始 " + lastClass + " → " + kind);
        setStatus("第 " + round + " 轮 · 广告中 " + kind);

        String reason = handleAd(kind, 140000L);
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
        NodeV button;
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
        for (int i = 0; i < 5 && running && !workerExit; i++) {
            if (i == 2) {
                // 两轮都回不去，按用户的思路：退到后台杀进程再重开
                if (killBoosterBackground()) {
                    Home h = readHome();
                    if (h != null && h.duration >= 0) return h;
                }
            }
            if (!atHome()) {
                launchApp();
                sleep(2500);
            }
            long t0 = SystemClock.uptimeMillis();
            boolean refreshed = false;
            while (SystemClock.uptimeMillis() - t0 < 9000 && running) {
                Home h = readHome();
                if (h != null && h.duration >= 0) {
                    deadBeats = 0;
                    return h;
                }
                deadBeats++;
                if (deadBeats >= 6) {
                    deadBeats = 0;
                    resetAccessibilityConnection("连续 6 次读不到首页");
                    return null;
                }
                if (!refreshed) {
                    refreshed = true;
                    Snap ss = snap();
                    if (ss.ok) refreshHome(ss);
                    sleep(1500);
                    Home h2 = readHome();
                    if (h2 != null && h2.duration >= 0) return h2;
                    // 只在确认处于外星仔首页时才下拉刷新；
                    // 否则手势会落到桌面上，把「负一屏搜索」拉出来，反而彻底卡死
                    if (atHome()) {
                        Snap guard = snap();
                        String gj = TextUtils.join(" ", guard.texts);
                        if (guard.submit != null || gj.contains("可暂停时长") || gj.contains("看广告")) {
                            pullToRefresh();
                        } else {
                            note("页面还没有首页标记，跳过下拉刷新");
                            recoverUnknownPage(guard);
                        }
                        sleep(2500);
                    }
                    Home h3 = readHome();
                    if (h3 != null && h3.duration >= 0) return h3;
                    continue;
                }
                sleep(600);
            }
        }
        dumpDiag("stuck");
        return null;
    }

    /**
     * 「开启计时」确认框会挡住整个首页，还会消耗用户的加速时长。
     * 只点「取消」，永远不点「确定开启」。
     */
    private boolean dismissBlocker(Snap s) {
        String joined = TextUtils.join(" ", s.texts);
        boolean startTiming = joined.contains("确定要开启计时吗") || joined.contains("开启计时");
        boolean tip = joined.contains("温馨提示");
        if (startTiming || tip) {
            if (clickText(s, "取消")) {
                note("关掉遮挡弹窗「" + (startTiming ? "开启计时" : "温馨提示") + "」");
                return true;
            }
            note("发现弹窗但没找到取消按钮，按返回");
            back();
            return true;
        }
        return false;
    }

    /** 时长显示成「— 时 — 分 — 秒」时，点一下右上角「每日刷新」重新拉数据。 */
    /** 只有在确认是首页布局时才动手，避免手势落到充值中心之类的入口上。 */
    private boolean isHomeLike(Snap s) {
        if (s.submit != null) return true;
        String joined = TextUtils.join(" ", s.texts);
        return joined.contains("看广告") || joined.contains("可暂停时长");
    }

    private boolean refreshHome(Snap s) {
        if (clickText(s, "每日刷新")) {
            note("时长读不出，点「每日刷新」重拉");
            return true;
        }
        if (!isHomeLike(s)) {
            note("当前不在首页布局，放弃坐标兜底");
            return false;
        }
        // 按文本找不到才按坐标兜底（1080x2340 基准，右上角「每日刷新」）
        tapXY(cx(892), cy(692));
        note("时长读不出，按坐标点「每日刷新」");
        return true;
    }

    /** 误入其它页面（充值中心、详情页等）时退回来。 */
    private void recoverUnknownPage(Snap s) {
        String joined = TextUtils.join(" ", s.texts);
        boolean stray = (joined.contains("充值中心") || joined.contains("充值"))
                && s.submit == null;
        if (stray) {
            note("检测到非首页页面，按返回退出");
            back();
            sleep(1500);
        }
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
            // CLEAR_TASK 会把外星仔的任务栈清空重来，等效于把卡住的广告 Activity 扔掉
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable t) {
            note("拉起失败：" + t);
        }
    }

    /**
     * 把外星仔退到后台再杀它的进程，然后重新拉起。
     * 这是普通 App 能拿到的最接近 force-stop 的手段（广告 Activity 卡住时靠它救场）。
     */
    private boolean killBoosterBackground() {
        try {
            goHomeScreen();
            sleep(1800);
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            am.killBackgroundProcesses(PKG);
            note("已杀外星仔后台进程");
            sleep(2500);
            launchApp();
            sleep(4000);
            return true;
        } catch (Throwable t) {
            note("杀后台失败：" + t);
            return false;
        }
    }

    /** 应用内下拉刷新：强制外星仔重新拉一次数据，也会让语义树重绘。 */
    private void pullToRefresh() {
        int x = cx(540);
        int y0 = cy(700);
        int y1 = cy(1500);
        Path path = new Path();
        path.moveTo(x, y0);
        path.lineTo(x, y1);
        GestureDescription.StrokeDescription st =
                new GestureDescription.StrokeDescription(path, 0, 420);
        GestureDescription g = new GestureDescription.Builder().addStroke(st).build();
        dispatchGesture(g, null, null);
        note("下拉刷新");
    }

    /** 判定失败时把现场存成文本：窗口列表、活跃窗口文字、首页窗口是否能找到。 */
    private void dumpDiag(String tag) {
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "diag");
            if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                    java.util.Locale.CHINA).format(new java.util.Date());
            java.io.File f = new java.io.File(dir, ts + "_" + tag + ".txt");
            StringBuilder sb = new StringBuilder();
            sb.append("time=").append(ts).append('\n');
            sb.append("lastPkg=").append(lastPkg).append(" lastClass=").append(lastClass).append('\n');
            sb.append("atHome=").append(atHome()).append('\n');
            try {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
                for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = w.getRoot();
                    sb.append("win type=").append(w.getType())
                            .append(" pkg=").append(r == null ? "null" : r.getPackageName())
                            .append(" children=").append(r == null ? -1 : r.getChildCount()).append('\n');
                }
            } catch (Throwable t) {
                sb.append("windowsErr=").append(t).append('\n');
            }
            Snap a = snap();
            sb.append("activeOk=").append(a.ok).append(" activePkg=").append(a.pkg).append('\n');
            sb.append("activeTexts=").append(a.texts).append('\n');
            Snap hw = snapFromWindowWithSubmit();
            sb.append("homeWindowFound=").append(hw != null).append('\n');
            if (hw != null) sb.append("homeTexts=").append(hw.texts).append('\n');
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
            note("诊断已保存 " + f.getName());
        } catch (Throwable t) {
            note("诊断保存失败 " + t);
        }
    }

    /**
     * 无障碍连接彻底失效（窗口列表空、活跃窗口读不到）时重建连接。
     * 不用杀进程——直接把自己的无障碍开关关掉再打开，系统会重新绑定服务，
     * 进程和悬浮窗都还在。需要 WRITE_SECURE_SETTINGS（一次性 adb 授权）。
     */
    private static void toggleAccessibility() {
        android.content.Context c = (INST != null) ? INST : APP_CTX;
        if (c == null) return;
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            android.provider.Settings.Secure.putString(cr,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            try {
                Thread.sleep(900);
            } catch (InterruptedException ignored) {
            }
            android.provider.Settings.Secure.putString(cr,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    c.getPackageName() + "/" + AutoService.class.getName());
        } catch (Throwable t) {
            note("切换无障碍失败：" + t);
        }
    }

    private void resetAccessibilityConnection(String why) {
        note("无障碍连接失效（" + why + "）→ 切换无障碍开关重建连接");
        if (prefs != null) prefs.edit().putBoolean("running", true).putInt("target", TARGET).putLong("savedAt", System.currentTimeMillis()).apply();
        workerExit = true;   // 让当前 worker 退出
        ACTIVE = false;      // 让重建后的新实例能接手续跑
        try {
            android.content.ContentResolver cr = getContentResolver();
            android.provider.Settings.Secure.putString(cr,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            sleep(1000);
            android.provider.Settings.Secure.putString(cr,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    getPackageName() + "/" + AutoService.class.getName());
            note("开关已切换，等待系统重新绑定");
        } catch (Throwable t) {
            note("切换无障碍失败（缺 WRITE_SECURE_SETTINGS？）：" + t);
        }
    }

    /** 从持久化状态继续跑剩下的轮数。 */
    private void resume(final int target, final int savedDone, final int savedOk) {
        if (ACTIVE || (worker != null && worker.isAlive())) {
            note("续跑跳过：已有线程在跑");
            return;
        }
        ACTIVE = true;
        running = true;
        done = savedDone;
        ok = savedOk;
        failStreak = 0;
        deadBeats = 0;
        Overlay.show(this);
        beatSec = 0;
        beatH.removeCallbacks(beatR);
        beatH.post(beatR);
        setStatus("续跑，从第 " + (savedDone + 1) + " 轮开始");
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = savedDone + 1; i <= target && running && !workerExit; i++) {
                        round = i;
                        setStatus("第 " + i + "/" + target + " 轮 · 开始");
                        note("—— 第 " + i + "/" + target + " 轮（续跑）——");
                        boolean good;
                        try {
                            good = doRound();
                        } catch (Throwable t) {
                            note("本轮异常：" + t);
                            good = false;
                        }
                        done = i;
                        if (good) {
                            ok++;
                            failStreak = 0;
                        } else {
                            failStreak++;
                        }
                        if (prefs != null) prefs.edit().putInt("done", done).putInt("ok", ok).putLong("savedAt", System.currentTimeMillis()).apply();
                        if (!running) break;
                        if (i < target) sleep(good ? GAP_SEC * 1000L : 15000);
                    }
                } finally {
                    if (workerExit) {
                        ACTIVE = false;
                        note("等待无障碍重连后自动续跑");
                    }
                    if (!workerExit) {
                        running = false;
                    }
                    if (!workerExit && prefs != null) {
                        prefs.edit().putBoolean("running", false).apply();
                    }
                    if (workerExit) return;
                    beatH.removeCallbacks(beatR);
                    setStatus("已结束：共 " + done + " 轮");
                    note("任务结束：跑了 " + done + " 轮，成功 " + ok + " 轮");
                    Push.send(AutoService.this, "外星仔自动化结束（" + stopReason + "）",
                            "跑了 " + done + " 轮，成功 " + ok + " 轮。\n\n" + AutoService.dumpLogTail(10));
                    Overlay.set("外星仔：已结束\n跑了 " + done + " 轮 · 成功 " + ok + " 轮\n" + stopReason);
                }
            }
        }, "wz-resume");
        worker.start();
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
        boolean clicked = false;
        for (int k = 0; k < 14 && !clicked; k++) {
            sleep(800);
            Snap s = snap();
            if (!s.ok) continue;
            // 等设置页真的到前台，别在还停在外星仔时瞎找
            if (!s.pkg.contains("settings")) continue;
            for (String t : new String[]{"强行停止", "强制停止"}) {
                if (clickText(s, t)) {
                    clicked = true;
                    break;
                }
            }
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
        if (!s.ok) return null;
        if (dismissBlocker(s)) {
            sleep(1200);
            s = snap();
            if (!s.ok) return null;
        }
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
        setStatus("第 " + round + " 轮 · 等页面恢复");
        long t0 = SystemClock.uptimeMillis();
        int baseProgress = progressOf(before.stages);
        int relaunch = 0;
        // 奖励是延迟到账的，没必要死等 90 秒：页面能读就直接进下一轮，
        // 到没到账由下一轮起始读数对照体现
        while (SystemClock.uptimeMillis() - t0 < 7000 && running && !workerExit) {
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
            note("页面已恢复，暂无增量（延迟到账，下一轮起始会体现）");
            return false;
        }
        note("7 秒内页面没恢复，直接进下一轮");
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
            if (s.ok && !s.pkg.isEmpty() && !PKG.equals(s.pkg)) {
                if (s.pkg.equals(getPackageName())) continue;  // 我们自己的界面
                return true;
            }
            sleep(400);
        }
        return false;
    }

    private String handleAd(AdKind kind, long budget) {
        long t0 = SystemClock.uptimeMillis();
        int taps = 0, dialogs = 0;
        long lastBeat = -20000;
        int lastCloseX = -1, lastCloseY = -1, sameClose = 0;
        while (running && !workerExit) {
            long el = SystemClock.uptimeMillis() - t0;
            if (el > budget) break;
            setStatus("第 " + round + " 轮 · 广告中 " + (el / 1000) + "s · " + kind);
            if (el - lastBeat > 20000) {
                lastBeat = el;
                note("心跳 " + (el / 1000) + "s · " + kind);
            }
            if (el > 140000) break;
            Snap s = snap();
            if (!s.ok) {
                sleep(400);
                continue;
            }
            String pkg = s.pkg;
            if (!TextUtils.isEmpty(pkg) && !pkg.equals(PKG)
                    && !pkg.equals(getPackageName())) {
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
                if (el > 100000 && taps < 2) {
                    taps++;
                    note("盲点右上角 X (" + taps + ")");
                    tapX();
                    sleep(2200);
                    continue;
                }
                if (taps >= 2 && el > 130000) {
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
        for (NodeV n : s.nodes) {
            String id = n.id.toLowerCase(Locale.ROOT);
            String d = n.d;
            String t = n.t;
            if (t.contains("跳过") || id.contains("close_tip") || id.contains("skip")) continue;
            boolean hit = t.contains("关闭") || d.contains("关闭") || id.contains("close_button")
                    || id.contains("btn_close") || id.contains("icon_close") || id.contains("closebtn");
            if (!hit) continue;
            if (n.area <= 0 || n.area > 320 * 320) continue;
            if (!(n.x > w * 0.55f && n.y < h * 0.22f)) continue; // 只认右上角
            return new Pt(n.x, n.y);
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

    private boolean clickNode(NodeV n) {
        if (n == null || n.x < 0) return false;
        tapXY(n.x, n.y);
        return true;
    }

    private boolean clickText(Snap s, String text) {
        for (NodeV n : s.nodes) {
            if (n.t.equals(text) && clickNode(n)) return true;
        }
        return false;
    }

    /** 节点的值拷贝：只留文字、id、中心点和面积，不再持有 AccessibilityNodeInfo。 */
    static class NodeV {
        String t = "";
        String d = "";
        String id = "";
        int x = -1;
        int y = -1;
        int area = 0;
        boolean clickable = false;
    }

    static class Snap {
        boolean ok = false;
        String pkg = "";
        final List<String> texts = new ArrayList<>();
        final List<NodeV> nodes = new ArrayList<>();
        NodeV submit;
    }

    private Snap snap() {
        Snap s = new Snap();
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        // 有时活跃窗口是别的 App 或系统窗口（例如被设置页盖住），
        // 这时从窗口列表里找外星仔自己的窗口，别一头撞死
        boolean weak = root == null;
        if (root != null) {
            CharSequence p = root.getPackageName();
            if (p == null || root.getChildCount() == 0) weak = true;
        }
        if (weak) {
            try {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
                for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    CharSequence p = r.getPackageName();
                    if (p != null && PKG.contentEquals(p)) {
                        root = r;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (root == null) return s;
        s.ok = true;
        CharSequence p = root.getPackageName();
        if (p != null) s.pkg = p.toString();
        walk(root, s, 0);
        // 活跃窗口不是首页（例如广告 Activity 盖在上面）时，翻窗口列表把首页找出来。
        // 只读不点，点仍然走手势落在最上层。
        if (s.submit == null && PKG.equals(s.pkg) && lastClass.endsWith("MainActivity")) {
            Snap home = snapFromWindowWithSubmit();
            if (home != null) return home;
        }
        return s;
    }

    private Snap snapFromWindowWithSubmit() {
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            StringBuilder dbg = new StringBuilder();
            for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r0 = w.getRoot();
                String pk = r0 == null ? "null" : String.valueOf(r0.getPackageName());
                dbg.append(pk.replace("com.", "")).append("(t").append(w.getType()).append(") ");
            }
            if (SystemClock.uptimeMillis() - lastScanLog > 5000) {
                lastScanLog = SystemClock.uptimeMillis();
                note("窗口扫描: " + dbg);
            }
            for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                if (p == null || !PKG.contentEquals(p)) continue;
                Snap cand = new Snap();
                cand.ok = true;
                cand.pkg = p.toString();
                walk(r, cand, 0);
                if (cand.submit != null) return cand;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void walk(AccessibilityNodeInfo n, Snap s, int depth) {
        if (n == null || depth > 40) return;
        NodeV v = new NodeV();
        CharSequence t = n.getText();
        if (t != null) v.t = t.toString();
        CharSequence d = n.getContentDescription();
        if (d != null) v.d = d.toString();
        String id = n.getViewIdResourceName();
        if (id != null) v.id = id;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        v.x = r.centerX();
        v.y = r.centerY();
        v.area = r.width() * r.height();
        v.clickable = n.isClickable();
        if (!v.t.isEmpty()) s.texts.add(v.t);
        if (id != null && id.endsWith("UIADSubmit")) s.submit = v;
        s.nodes.add(v);
        int cnt = n.getChildCount();
        for (int i = 0; i < cnt; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            walk(c, s, depth + 1);
            try {
                c.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    private int parseDuration(List<String> texts) {
        // Flutter 有时把「54 时 20 分 32 秒」合并成一个节点，先按整串匹配
        java.util.regex.Pattern merged = java.util.regex.Pattern.compile(
                "(\\d+)\\s*时\\s*(\\d+)\\s*分\\s*(\\d+)\\s*秒");
        for (String t : texts) {
            java.util.regex.Matcher m = merged.matcher(t);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1)) * 3600
                            + Integer.parseInt(m.group(2)) * 60
                            + Integer.parseInt(m.group(3));
                } catch (NumberFormatException ignored) {
                }
            }
        }
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
