package nl.brugmonitor.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.*;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView web;
    private StatusRepository status;
    private AppUpdater updater;

    // Only trusted bundled HTML runs in the WebView. Links always open externally.
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        NotificationSupport.createChannel(this);
        NotificationSupport.clearLegacy(this);
        status = new StatusRepository(this);
        updater = new AppUpdater(this);
        web = new WebView(this);
        // WebView content ignores its own padding on some devices; inset the container instead.
        android.widget.FrameLayout content = new android.widget.FrameLayout(this);
        content.setBackgroundColor(android.graphics.Color.WHITE);
        content.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    android.view.DisplayCutout cutout = insets.getDisplayCutout();
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        content.requestApplyInsets();
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (android.content.ActivityNotFoundException e) { Toast.makeText(MainActivity.this, "Geen browser beschikbaar", Toast.LENGTH_SHORT).show(); }
                }
                return true;
            }
        });
        try (java.io.InputStream input = getAssets().open("index.html")) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            web.loadDataWithBaseURL("https://brugmonitor.local/", output.toString("UTF-8"), "text/html", null, null);
        } catch (java.io.IOException e) {
            Toast.makeText(this, "De apppagina kan niet worden geladen", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleNotifications() {
        if (!PushSettings.configured(this)) return;
        if (PushSettings.wanted(this)) {
            PushSettings.disable(this);
        } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 33);
        } else {
            PushSettings.enable(this);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 33) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) PushSettings.enable(this);
            else PushSettings.error = "Sta meldingen toe in de Android-instellingen van Brugmonitor.";
        }
    }

    public class Bridge {
        @JavascriptInterface public String getUpdateState() { return updater.json(); }
        @JavascriptInterface public void checkForUpdate() { updater.check(true); }
        @JavascriptInterface public void downloadUpdate() { updater.download(); }
        @JavascriptInterface public boolean isFirebaseConfigured() { return PushSettings.configured(MainActivity.this); }
        @JavascriptInterface public String getStatusJSON() { return status.json(); }
        @JavascriptInterface public String getPushState() { return PushSettings.json(MainActivity.this); }
        @JavascriptInterface public void toggleNotifications() { runOnUiThread(() -> MainActivity.this.toggleNotifications()); }
        @JavascriptInterface public void openNotificationSettings() {
            runOnUiThread(() -> {
                Intent settings = new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, NotificationSupport.CHANNEL);
                startActivity(settings);
            });
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (status != null) status.start();
        PushSettings.sync(this);
        if (updater != null) updater.resume();
    }
    @Override protected void onPause() {
        if (status != null) status.stop();
        if (web != null) web.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (status != null) status.stop();
        if (updater != null) updater.close();
        if (web != null) { web.removeJavascriptInterface("Android"); web.destroy(); }
        super.onDestroy();
    }
}
