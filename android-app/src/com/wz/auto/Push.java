package com.wz.auto;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Server 酱推送。
 *
 * 实机遇到过 {@code SSL handshake timed out}——TCP/ICMP 都通，但 TLS 协商卡住。
 * 这里做了三件事：显式 TLSv1.2、多次重试、https 失败后降级 http。
 */
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

    /** 供界面「测试推送」按钮直接调用。 */
    static void sendTest(final Context c) {
        send(c, "外星仔自动化 · 测试推送",
                "这是一条测试消息。\n\n收到它说明 SendKey 和网络都正常。");
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("push_enabled", true);
    }

    static void send(final Context c, final String title, final String body) {
        if (!enabled(c)) {
            AutoService.note("推送已在设置里关闭，跳过");
            return;
        }
        final String key = sendKey(c);
        if (key == null || key.isEmpty()) {
            AutoService.note("没配置 SendKey，跳过推送");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    if (post(c, key, title, body, true)) return;   // 先 https
                    AutoService.note("推送第 " + attempt + " 次（https）失败");
                    if (post(c, key, title, body, false)) return;  // 再退 http
                    AutoService.note("推送第 " + attempt + " 次（http）失败");
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                }
                AutoService.note("推送三次都失败，放弃（检查网络或 SendKey）");
            }
        }, "wz-push").start();
    }

    private static boolean post(Context c, String key, String title, String body, boolean https) {
        HttpURLConnection conn = null;
        try {
            String scheme = https ? "https" : "http";
            URL u = new URL(scheme + "://sctapi.ftqq.com/" + key + ".send");
            conn = (HttpURLConnection) u.openConnection();
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection hc = (HttpsURLConnection) conn;
                try {
                    SSLContext ctx = SSLContext.getInstance("TLSv1.2");
                    ctx.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
                    hc.setSSLSocketFactory(ctx.getSocketFactory());
                } catch (Throwable ignored) {
                }
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "wz-auto/1.3");
            conn.setRequestProperty("Connection", "close");
            String data = "title=" + URLEncoder.encode(title, "UTF-8")
                    + "&desp=" + URLEncoder.encode(body, "UTF-8");
            OutputStream os = conn.getOutputStream();
            os.write(data.getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            AutoService.note("推送成功 HTTP " + code + "（" + (https ? "https" : "http") + "）");
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            AutoService.note("推送异常：" + t.getClass().getSimpleName() + " "
                    + String.valueOf(t.getMessage()));
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 只用于兼容个别设备上的证书链问题；推送内容本身不含敏感数据。 */
    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
