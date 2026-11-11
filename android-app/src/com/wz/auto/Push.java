package com.wz.auto;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Server 酱推送（https://sct.ftqq.com）。SendKey 在主界面配置。 */
public class Push {

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("wz", Context.MODE_PRIVATE);
    }

    static String sendKey(Context c) {
        return prefs(c).getString("sc_key", "");
    }

    static void setSendKey(Context c, String k) {
        prefs(c).edit().putString("sc_key", k.trim()).apply();
    }

    static void send(final Context c, final String title, final String body) {
        final String key = sendKey(c);
        if (key == null || key.isEmpty()) {
            AutoService.note("没配置 SendKey，跳过推送");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL u = new URL("https://sctapi.ftqq.com/" + key + ".send");
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded; charset=UTF-8");
                    String data = "title=" + URLEncoder.encode(title, "UTF-8")
                            + "&desp=" + URLEncoder.encode(body, "UTF-8");
                    OutputStream os = conn.getOutputStream();
                    os.write(data.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    int code = conn.getResponseCode();
                    AutoService.note("推送完成 HTTP " + code);
                } catch (Throwable t) {
                    AutoService.note("推送失败：" + t);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "wz-push").start();
    }
}
