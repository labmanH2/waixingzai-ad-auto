package com.wz.auto;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status;
    private TextView logView;
    private ScrollView scroller;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#12121a"));

        TextView title = new TextView(this);
        title.setText("外星仔看广告自动化");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9be7ff"));
        status.setTextSize(13);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        Button openSet = new Button(this);
        openSet.setText("① 打开无障碍设置");
        openSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(openSet);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));

        final EditText rounds = new EditText(this);
        rounds.setText("21");
        rounds.setTextColor(Color.WHITE);
        rounds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(rounds, lp);

        Button start = new Button(this);
        start.setText("② 开始");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int n = 21;
                try {
                    n = Integer.parseInt(rounds.getText().toString().trim());
                } catch (Exception ignored) {
                }
                AutoService.startRun(n);
            }
        });
        row.addView(start);

        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoService.stopRun();
            }
        });
        row.addView(stop);
        root.addView(row);

        final EditText scKey = new EditText(this);
        scKey.setHint("Server\u9171 SendKey\uff08\u4e0d\u586b\u5219\u4e0d\u63a8\u9001\uff09");
        scKey.setSingleLine(true);
        scKey.setTextColor(Color.WHITE);
        scKey.setHintTextColor(Color.parseColor("#777788"));
        scKey.setTextSize(12);
        scKey.setText(Push.sendKey(this));
        scKey.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                Push.setSendKey(MainActivity.this, s.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
            }
        });
        root.addView(scKey);

        Button overlayBtn = new Button(this);
        overlayBtn.setText("\u2462 \u60ac\u6d6e\u7a97\u6743\u9650");
        overlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                }
            }
        });
        root.addView(overlayBtn);

        scroller = new ScrollView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#c8c8d0"));
        logView.setTextSize(11);
        logView.setGravity(Gravity.TOP);
        scroller.addView(logView);
        root.addView(scroller, slp);

        setContentView(root);
        ui.post(ticker);
    }

    private void refresh() {
        boolean on = AutoService.INST != null;
        status.setText((on ? "无障碍：已连接" : "无障碍：未开启（先点第①步）")
                + "\n运行中：" + (AutoService.running ? "是" : "否")
                + "   完成 " + AutoService.done + " 轮 / 成功 " + AutoService.ok + " 轮");
        String log = AutoService.dumpLog();
        if (!log.equals(logView.getText().toString())) {
            logView.setText(log);
            scroller.post(new Runnable() {
                @Override
                public void run() {
                    scroller.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
