package com.zelvari.shiftdeck;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Hosts the ShiftDeck web UI (assets/www) and gives it a small native bridge. */
public class MainActivity extends Activity {

    private WebView web;
    private boolean askedNotify = false;
    private SharedPreferences ui;

    private boolean isTablet() {
        return getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    /** "auto" | "portrait" | "landscape". Tablets start in landscape, phones turn freely. */
    private String orientation() {
        return ui.getString("orient", isTablet() ? "landscape" : "auto");
    }

    private void applyOrientation(String mode) {
        int req;
        if ("portrait".equals(mode)) req = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        else if ("landscape".equals(mode)) req = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
        else req = ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
        setRequestedOrientation(req);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = getSharedPreferences("ui", MODE_PRIVATE);
        applyOrientation(orientation());
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#4D148C"));
        w.setNavigationBarColor(Color.parseColor("#2A0759"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#2A0759"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return openExternal(req.getUrl());
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");
    }

    private boolean openExternal(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if (scheme.equals("file")) return false;
        try {
            Intent i = scheme.equals("tel") ? new Intent(Intent.ACTION_DIAL, u) : new Intent(Intent.ACTION_VIEW, u);
            startActivity(i);
        } catch (Exception ignored) { }
        return true;
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    /** Methods the web page can call as window.Android.* */
    private class Bridge {

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                v.vibrate(VibrationEffect.createOneShot(Math.max(5, Math.min(ms, 200)), VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void syncReminders(String json) {
            Reminders.sync(MainActivity.this, json);
        }

        @JavascriptInterface
        public void ensureNotifyPermission() {
            if (Build.VERSION.SDK_INT < 33 || askedNotify) return;
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
            askedNotify = true;
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7));
        }

        @JavascriptInterface
        public String getOrientation() {
            return orientation();
        }

        @JavascriptInterface
        public void setOrientation(String mode) {
            ui.edit().putString("orient", mode).apply();
            runOnUiThread(() -> applyOrientation(mode));
        }

        @JavascriptInterface
        public void share(String title, String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, title);
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, title));
            });
        }

        @JavascriptInterface
        public void dial(String number) {
            runOnUiThread(() -> openExternal(Uri.parse("tel:" + Uri.encode(number))));
        }

        @JavascriptInterface
        public void setBars(String navColor, int dark) {
            runOnUiThread(() -> {
                try {
                    Window w = getWindow();
                    w.setNavigationBarColor(Color.parseColor(navColor));
                    View d = w.getDecorView();
                    int f = d.getSystemUiVisibility();
                    if (dark == 1) f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    else f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    d.setSystemUiVisibility(f);
                } catch (Exception ignored) { }
            });
        }
    }
}
