package nl.brugmonitor.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

final class AppUpdater {
    private final MainActivity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile String state = "{}";
    private volatile boolean busy;
    private volatile JSONObject release;
    private long lastCheck;
    private volatile File downloaded;
    private boolean waitingForPermission;
    private volatile boolean closed;

    AppUpdater(MainActivity activity) { this.activity = activity; update("", false); }
    String json() { return state; }
    private synchronized void update(String message, boolean working) {
        try {
            JSONObject data = new JSONObject();
            data.put("currentVersion", BuildConfig.VERSION_NAME);
            data.put("available", release != null);
            data.put("version", release == null ? "" : release.optString("version"));
            data.put("busy", working);
            data.put("message", message);
            state = data.toString();
        } catch (Exception ignored) { }
    }
    synchronized void check(boolean force) {
        if (closed || busy || (!force && System.currentTimeMillis() - lastCheck < 300000)) return;
        busy = true; lastCheck = System.currentTimeMillis();
        if (force) update("Updates controleren...", true);
        worker.execute(() -> {
            try {
                JSONObject payload = readFeed(BuildConfig.UPDATE_FEED_URL);
                JSONObject candidate = payload.optJSONObject("data");
                if (candidate != null && candidate.getLong("buildNumber") > BuildConfig.VERSION_CODE) {
                    validate(candidate);
                    if (release == null || !release.optString("sha256").equals(candidate.getString("sha256"))) downloaded = null;
                    release = candidate;
                    update("Nieuwe versie beschikbaar", false);
                    prompt(candidate);
                } else { release = null; downloaded = null; update(force ? (candidate == null ? "Nog geen apprelease gepubliceerd" : "Je hebt de nieuwste versie") : "", false); }
            } catch (Exception error) { update(force ? "Updates controleren lukt nu niet. Probeer later opnieuw." : "", false); }
            finally { busy = false; }
        });
    }
    private void prompt(JSONObject candidate) {
        activity.runOnUiThread(() -> {
            if (closed || activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return;
            long build = candidate.optLong("buildNumber");
            android.content.SharedPreferences prefs = activity.getSharedPreferences("updates", 0);
            if (prefs.getLong("promptedBuild", 0) == build) return;
            prefs.edit().putLong("promptedBuild", build).apply();
            new AlertDialog.Builder(activity).setTitle("Brugmonitor " + candidate.optString("version"))
                .setMessage("Er is een nieuwe versie. Wil je die downloaden en installeren?")
                .setNegativeButton("Later", null)
                .setPositiveButton("Downloaden", (dialog, which) -> download()).show();
        });
    }
    private static void validate(JSONObject candidate) throws Exception {
        if (!BuildConfig.APPLICATION_ID.equals(candidate.getString("packageName"))
            || !candidate.getString("sha256").matches("(?i)[a-f0-9]{64}")
            || !candidate.getString("version").matches("[0-9]+[.][0-9]+[.][0-9]+")
            || candidate.getLong("buildNumber") <= 0) throw new IOException("Ongeldige releasegegevens");
        trustedUrl(candidate.getString("downloadUrl"));
        long size = candidate.optLong("sizeBytes", 0);
        if (size < 0 || size > 150L * 1024 * 1024) throw new IOException("Ongeldige bestandsgrootte");
    }
    private static URL trustedUrl(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
            || (url.getPort() != -1 && url.getPort() != 443)
            || !("liliananuzohra.com".equalsIgnoreCase(url.getHost()) || "www.liliananuzohra.com".equalsIgnoreCase(url.getHost())))
            throw new IOException("Ongeldige downloadlocatie");
        return url;
    }
    private static HttpURLConnection connect(String address) throws Exception {
        URL url = trustedUrl(address);
        for (int i = 0; i < 6; i++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "Brugmonitor/" + BuildConfig.VERSION_NAME);
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new IOException("Downloadlocatie ontbreekt");
                url = trustedUrl(new URL(url, location).toString()); continue;
            }
            if (code != 200) { connection.disconnect(); throw new IOException("Updateserver niet beschikbaar"); }
            return connection;
        }
        throw new IOException("Te veel omleidingen");
    }
    private static JSONObject readFeed(String url) throws Exception {
        HttpURLConnection connection = connect(url);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 262144) throw new IOException("Releasegegevens te groot");
                output.write(buffer, 0, count);
            }
            return new JSONObject(output.toString("UTF-8"));
        } finally { connection.disconnect(); }
    }
    synchronized void download() {
        if (closed || busy || release == null) return;
        if (downloaded != null && downloaded.isFile()) { activity.runOnUiThread(this::install); return; }
        busy = true;
        JSONObject candidate = release;
        update("Download starten...", true);
        worker.execute(() -> {
            File partial = null;
            HttpURLConnection connection = null;
            try {
                validate(candidate);
                File directory = new File(activity.getCacheDir(), "updates");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Downloadmap niet beschikbaar");
                partial = new File(directory, "brugmonitor.part");
                connection = connect(candidate.getString("downloadUrl"));
                long expected = candidate.optLong("sizeBytes", 0);
                long total = expected > 0 ? expected : connection.getContentLengthLong();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long received = 0, shownAt = 0;
                try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Download afgebroken");
                        received += count;
                        if (received > 150L * 1024 * 1024) throw new IOException("Download te groot");
                        digest.update(buffer, 0, count); output.write(buffer, 0, count);
                        if (System.currentTimeMillis() - shownAt > 200) {
                            update(total > 0 ? "Downloaden... " + Math.min(100, received * 100 / total) + "%" : "Downloaden...", true);
                            shownAt = System.currentTimeMillis();
                        }
                    }
                }
                if (received == 0 || (total > 0 && received != total)
                    || !hex(digest.digest()).equalsIgnoreCase(candidate.getString("sha256")))
                    throw new IOException("De downloadcontrole is mislukt. Probeer opnieuw.");
                validateApk(partial, candidate);
                File destination = new File(directory, "brugmonitor.apk");
                if (destination.exists() && !destination.delete()) throw new IOException("Oude download verwijderen mislukt");
                if (!partial.renameTo(destination)) throw new IOException("Download opslaan mislukt");
                downloaded = destination;
                update("Download gereed. Installeren...", false);
                activity.runOnUiThread(this::install);
            } catch (Exception error) { update("Update downloaden of controleren mislukt. Probeer opnieuw.", false); }
            finally {
                if (connection != null) connection.disconnect();
                if (partial != null && partial.exists()) partial.delete();
                busy = false;
            }
        });
    }
    private void validateApk(File apk, JSONObject candidate) throws Exception {
        PackageManager manager = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo update = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(activity.getPackageName(), flags);
        if (update == null || !activity.getPackageName().equals(update.packageName)
            || !candidate.getString("version").equals(update.versionName)
            || (Build.VERSION.SDK_INT >= 28 ? update.getLongVersionCode() : update.versionCode) != candidate.getLong("buildNumber"))
            throw new IOException("APK hoort niet bij deze release");
        android.content.pm.Signature[] newSignatures = Build.VERSION.SDK_INT >= 28 ? update.signingInfo.getApkContentsSigners() : update.signatures;
        android.content.pm.Signature[] oldSignatures = Build.VERSION.SDK_INT >= 28 ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
        if (newSignatures == null || oldSignatures == null || newSignatures.length != oldSignatures.length
            || !new HashSet<>(Arrays.asList(newSignatures)).equals(new HashSet<>(Arrays.asList(oldSignatures))))
            throw new IOException("Ondertekening komt niet overeen");
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private void install() {
        if (closed || downloaded == null || !downloaded.isFile() || activity.isFinishing()) return;
        try {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                waitingForPermission = true;
                update("Sta installeren vanuit Brugmonitor toe en keer terug naar de app.", false);
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", downloaded);
            activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            update("Bevestig de installatie in Android.", false);
        } catch (Exception error) { update("De installer kon niet worden geopend. Probeer opnieuw.", false); }
    }
    void resume() {
        if (waitingForPermission) {
            waitingForPermission = false;
            if (activity.getPackageManager().canRequestPackageInstalls()) install();
            else update("Installeren niet toegestaan. Tik opnieuw om toestemming te geven.", false);
        }
        check(false);
    }
    void close() { closed = true; worker.shutdownNow(); }
}
