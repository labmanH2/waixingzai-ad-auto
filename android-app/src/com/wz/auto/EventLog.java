package com.wz.auto;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 轻量运行事件记录。
 *
 * 只记「发生了什么」，不记界面细节：自愈重连、无填充等待、每轮结果、任务汇总。
 * 目的有三个：
 * 1. logcat 会被系统回收，落盘才能事后排查；
 * 2. 统计断连频率、每次损失时间，为后续优化提供依据；
 * 3. 其他用户反馈问题时可以直接把这段文本发过来。
 */
public class EventLog {

    private static final String FILE_NAME = "events.log";
    private static final int MAX_BYTES = 200 * 1024;
    private static final Object LOCK = new Object();

    private static File file(Context c) {
        File dir = new File(c.getExternalFilesDir(null), "diag");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE_NAME);
    }

    static void add(Context c, String line) {
        if (c == null) return;
        synchronized (LOCK) {
            try {
                File f = file(c);
                if (f.exists() && f.length() > MAX_BYTES) {
                    trim(f);
                }
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                        .format(new Date());
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write((ts + "  " + line + "\n").getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 文件太大时只保留最后一半，避免无限增长。 */
    private static void trim(File f) {
        try {
            List<String> all = readAll(f);
            int keep = Math.max(200, all.size() / 2);
            FileOutputStream fos = new FileOutputStream(f, false);
            for (int i = Math.max(0, all.size() - keep); i < all.size(); i++) {
                fos.write((all.get(i) + "\n").getBytes("UTF-8"));
            }
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private static List<String> readAll(File f) throws Exception {
        List<String> out = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), "UTF-8"));
        String s;
        while ((s = br.readLine()) != null) out.add(s);
        br.close();
        return out;
    }

    /** 取最后 n 行，供界面展示或复制。 */
    static String tail(Context c, int n) {
        if (c == null) return "";
        synchronized (LOCK) {
            try {
                File f = file(c);
                if (!f.exists()) return "（还没有运行记录）";
                List<String> all = readAll(f);
                StringBuilder sb = new StringBuilder();
                int start = Math.max(0, all.size() - n);
                for (int i = start; i < all.size(); i++) {
                    sb.append(all.get(i)).append('\n');
                }
                return sb.length() == 0 ? "（还没有运行记录）" : sb.toString();
            } catch (Throwable t) {
                return "读取失败：" + t;
            }
        }
    }

    static String path(Context c) {
        return file(c).getAbsolutePath();
    }
}
