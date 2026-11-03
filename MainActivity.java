package com.dawai.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
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
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Reminders.createChannel(this);
        wakeIfAlarm(getIntent());

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage لحفظ الأدوية والسجل
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { pageReady = true; }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        // طلب إذن الإشعارات من أول تشغيل (أندرويد ١٣+)
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifPermission()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        Reminders.scheduleAll(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        wakeIfAlarm(intent);
        js("window.onNativeResume && window.onNativeResume()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) js("window.onNativeResume && window.onNativeResume()");
    }

    /** عند فتح التطبيق من المنبّه: أظهره فوق شاشة القفل وأضئ الشاشة */
    private void wakeIfAlarm(Intent it) {
        if (it == null || !it.getBooleanExtra("fromAlarm", false)) return;
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void js(final String code) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override public void run() { web.evaluateJavascript(code, null); }
        });
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) js("window.onNativePerm && window.onNativePerm()");
    }

    boolean hasNotifPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void openSafe(Intent it) {
        try {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
        }
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
            return Reminders.notificationsOk(MainActivity.this) && hasNotifPermission();
        }

        @JavascriptInterface
        public void requestPermission() {
            if (Build.VERSION.SDK_INT >= 33 && !hasNotifPermission()) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                    }
                });
            } else {
                fix("notif");
            }
        }

        /** حالة كل شروط التنبيه بصيغة JSON */
        @JavascriptInterface
        public String status() {
            return Reminders.status(MainActivity.this, hasNotifPermission());
        }

        /** تنبيه تجريبي بعد عدد من الثواني */
        @JavascriptInterface
        public void test(int seconds) {
            Reminders.scheduleTest(MainActivity.this, seconds);
        }

        /** فتح شاشة الإعداد المناسبة لإصلاح شرط معيّن */
        @JavascriptInterface
        public void fix(String what) {
            String pkg = getPackageName();
            Intent it;
            switch (what) {
                case "notif":
                    it = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                    break;
                case "channel":
                    it = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, Reminders.CHANNEL);
                    break;
                case "exact":
                    it = Build.VERSION.SDK_INT >= 31
                            ? new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + pkg))
                            : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                    break;
                case "battery":
                    it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg));
                    break;
                case "fullscreen":
                    it = Build.VERSION.SDK_INT >= 34
                            ? new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + pkg))
                            : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                    break;
                default:
                    it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            }
            openSafe(it);
        }

        /** الإجراءات التي ضغطها المستخدم من الإشعار (تناولته / تأجيل) ولم تُسجَّل بعد */
        @JavascriptInterface
        public String takePending() {
            return Reminders.takePending(MainActivity.this);
        }
    }
}
