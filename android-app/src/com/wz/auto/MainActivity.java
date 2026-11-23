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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.Spinner;
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

        Button pushBtn = new Button(this);
        pushBtn.setText("\u63a8\u9001\u8bbe\u7f6e");
        pushBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPushSettings();
            }
        });
        root.addView(pushBtn);

        // 轮间休息下拉
        LinearLayout gapRow = new LinearLayout(this);
        gapRow.setOrientation(LinearLayout.HORIZONTAL);
        gapRow.setGravity(Gravity.CENTER_VERTICAL);
        gapRow.setPadding(0, dp(4), 0, dp(4));

        TextView gapLabel = new TextView(this);
        gapLabel.setText("\u8f6e\u95f4\u4f11\u606f");
        gapLabel.setTextColor(Color.parseColor("#9be7ff"));
        gapLabel.setTextSize(13);
        gapRow.addView(gapLabel);

        final Spinner gapSpin = new Spinner(this);
        final int[] gaps = {15, 20, 30, 40, 50};
        String[] labels = new String[gaps.length];
        for (int i = 0; i < gaps.length; i++) labels[i] = gaps[i] + " \u79d2";
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        gapSpin.setAdapter(ad);
        int savedGap = getSharedPreferences("wz", MODE_PRIVATE).getInt("gap", 15);
        for (int i = 0; i < gaps.length; i++) {
            if (gaps[i] == savedGap) {
                gapSpin.setSelection(i);
                break;
            }
        }
        gapSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                getSharedPreferences("wz", MODE_PRIVATE).edit().putInt("gap", gaps[pos]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gapRow.addView(gapSpin, glp);
        root.addView(gapRow);

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

    /** 推送设置：SendKey + 是否推送 + 测试。 */
    private void openPushSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, pad);

        TextView tip = new TextView(this);
        tip.setText("SendKey \u5728 sct.ftqq.com \u83b7\u53d6\uff0c\u5f62\u5982 SCT \u5f00\u5934\u7684\u4e32");
        tip.setTextSize(11);
        box.addView(tip);

        final EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint("SCT...");
        key.setText(Push.sendKey(this));
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(key);

        final android.widget.CheckBox show = new android.widget.CheckBox(this);
        show.setText("\u663e\u793a\u5bc6\u94a5");
        show.setTextSize(12);
        show.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                key.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | (checked ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD));
                key.setSelection(key.getText().length());
            }
        });
        box.addView(show);

        final android.widget.CheckBox enabled = new android.widget.CheckBox(this);
        enabled.setText("\u4efb\u52a1\u7ed3\u675f\u65f6\u63a8\u9001");
        enabled.setTextSize(12);
        enabled.setChecked(getSharedPreferences("wz", MODE_PRIVATE).getBoolean("push_enabled", true));
        box.addView(enabled);

        new android.app.AlertDialog.Builder(this)
                .setTitle("\u63a8\u9001\u8bbe\u7f6e")
                .setView(box)
                .setPositiveButton("\u4fdd\u5b58", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Push.setSendKey(MainActivity.this, key.getText().toString());
                        getSharedPreferences("wz", MODE_PRIVATE).edit()
                                .putBoolean("push_enabled", enabled.isChecked()).apply();
                        AutoService.note("推送设置已保存"
                                + (enabled.isChecked() ? "（开启推送）" : "（关闭推送）"));
                    }
                })
                .setNeutralButton("\u6d4b\u8bd5", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Push.setSendKey(MainActivity.this, key.getText().toString());
                        Push.sendTest(MainActivity.this);
                    }
                })
                .setNegativeButton("\u53d6\u6d88", null)
                .show();
    }
}
