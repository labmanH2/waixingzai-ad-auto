package com.wz.auto;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/** 运行时的小悬浮窗，显示当前在做什么。需要「显示在其他应用上层」权限。 */
public class Overlay {

    private static WindowManager wm;
    private static TextView tv;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    static boolean canShow(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static void show(final Context c) {
        if (!canShow(c)) {
            AutoService.note("系统报告无悬浮窗权限，仍尝试添加");
        }
        UI.post(new Runnable() {
            @Override
            public void run() {
                if (tv != null) return;
                try {
                    wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                    tv = new TextView(c);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(12);
                    tv.setPadding(30, 18, 30, 18);
                    tv.setBackgroundColor(0xCC111119);
                    tv.setText("外星仔自动化：准备中");
                    int type = Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.x = 16;
                    lp.y = 150;
                    wm.addView(tv, lp);
                } catch (Throwable t) {
                    AutoService.note("悬浮窗失败：" + t);
                    tv = null;
                    wm = null;
                }
            }
        });
    }

    static void set(final String text) {
        UI.post(new Runnable() {
            @Override
            public void run() {
                if (tv != null) tv.setText(text);
            }
        });
    }

    static void hide() {
        UI.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (wm != null && tv != null) wm.removeView(tv);
                } catch (Throwable ignored) {
                }
                tv = null;
                wm = null;
            }
        });
    }
}
