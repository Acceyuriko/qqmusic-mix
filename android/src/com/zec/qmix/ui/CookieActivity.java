package com.zec.qmix.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import com.zec.qmix.L;
import com.zec.qmix.R;
import com.zec.qmix.net.Qq;
import com.zec.qmix.player.MixService;

/**
 * 内嵌 QQ 音乐网页登录（QQ 互联）。登录完成后从 CookieManager 读 cookie，
 * 验证通过才存进 SharedPreferences，然后重启 MixService。WebView 里的 cookie 读完就清。
 */
public class CookieActivity extends Activity {

    // QQ 音乐网页版的 QQ 登录（电脑版页面）：授权后跳 wx_redirect.html，它用 code 换 qm_keyst 等 cookie，再跳回 y.qq.com
    // 手机版页面只有「一键登录」（要拉起 QQ App，WebView 里用不了），所以用电脑版
    static final String LOGIN = "https://graph.qq.com/oauth2.0/authorize?response_type=code&client_id=100497308"
            + "&redirect_uri=https%3A%2F%2Fy.qq.com%2Fportal%2Fwx_redirect.html%3Flogin_type%3D1%26surl%3Dhttps%253A%252F%252Fy.qq.com%252F"
            + "&state=state&display=pc";

    WebView web;
    TextView msg;
    boolean checking;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);  // 系统可能单独恢复这个页面，这时 MainActivity / MixService 还没初始化 L
        setContentView(R.layout.activity_cookie);
        web = findViewById(R.id.web);
        msg = findViewById(R.id.msg);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36");
        // 电脑版页面在手机上太小，允许双指缩放
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                readCookie();
            }
        });
        web.loadUrl(LOGIN);
    }

    /** 页面加载完就看一眼：有 qqmusic_key 或 qm_keyst 才算登录完成，去验证。 */
    void readCookie() {
        if (checking) return;
        CookieManager cm = CookieManager.getInstance();
        String c = cm.getCookie("https://y.qq.com");
        if (c == null || !(c.contains("qqmusic_key=") || c.contains("qm_keyst="))) return;
        checking = true;
        cm.removeAllCookies(v -> cm.flush());
        L.i("login cookie has=true len=" + c.length());
        msg.setText("验证中...");
        new Thread(() -> {
            boolean ok = Qq.check(c);
            runOnUiThread(() -> {
                checking = false;
                if (!ok) {
                    msg.setText("登录无效");
                    web.loadUrl(LOGIN);
                    return;
                }
                getSharedPreferences("qmix", 0).edit().putString("cookie", c).apply();
                Toast.makeText(this, "登录成功", Toast.LENGTH_LONG).show();
                stopService(new Intent(this, MixService.class));
                startForegroundService(new Intent(this, MixService.class));
                finish();
            });
        }).start();
    }

    @Override
    protected void onDestroy() {
        // 没登录完就退出时，也不留下 QQ 登录态
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(v -> cm.flush());
        web.destroy();
        super.onDestroy();
    }
}
