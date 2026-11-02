package com.dawai.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * الشاشة الوحيدة: تعرض واجهة HTML من مجلد assets داخل WebView
 * وتربطها بمنبّه النظام عبر AndroidBridge.
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 101;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Reminders.createChannel(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage لحفظ الأدوية والسجل
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        // إعادة جدولة المنبّهات من آخر نسخة محفوظة
        Reminders.scheduleAll(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF && web != null) {
            web.evaluateJavascript("window.onNativePerm && window.onNativePerm()", null);
        }
    }

    boolean hasNotifPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** الواجهة التي تستدعيها صفحة HTML */
    class Bridge {
        @JavascriptInterface
        public void schedule(String medsJson) {
            Reminders.saveMeds(MainActivity.this, medsJson);
            Reminders.scheduleAll(MainActivity.this);
        }

        @JavascriptInterface
        public boolean hasPermission() {
            return hasNotifPermission();
        }

        @JavascriptInterface
        public void requestPermission() {
            if (Build.VERSION.SDK_INT >= 33 && !hasNotifPermission()) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                    }
                });
            }
        }

        /** الإجراءات التي ضغطها المستخدم من الإشعار (تناولته / تأجيل) ولم تُسجَّل بعد */
        @JavascriptInterface
        public String takePending() {
            return Reminders.takePending(MainActivity.this);
        }
    }
}
