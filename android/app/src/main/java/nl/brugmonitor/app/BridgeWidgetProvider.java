package nl.brugmonitor.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RemoteViews;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import org.json.JSONObject;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BridgeWidgetProvider extends AppWidgetProvider {
    private static final String CACHE = "widget_status";

    static boolean hasWidgets(Context c) {
        return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, BridgeWidgetProvider.class)).length > 0;
    }
    @Override public void onEnabled(Context c) { PushSettings.syncWidgetSubscription(c); }

    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) { refresh(c); }
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager manager, int id, Bundle options) {
        render(c, manager, id);
    }
    @Override public void onReceive(Context c, Intent intent) {
        if ("nl.brugmonitor.app.WIDGET_EXPIRE".equals(intent.getAction())) updateAll(c);
        else super.onReceive(c, intent);
    }

    private void refresh(Context c) {
        Context app = c.getApplicationContext();
        updateAll(app);
        PushSettings.syncWidgetSubscription(app);
        if (!PushSettings.configured(app)) return;
        PendingResult pending = goAsync();
        AtomicBoolean finished = new AtomicBoolean();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable finish = () -> { if (finished.compareAndSet(false, true)) pending.finish(); };
        handler.postDelayed(finish, 8000);
        FirebaseFirestore.getInstance().document("bridges/sas-van-gent").get(Source.SERVER)
            .addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null && task.getResult().getData() != null) {
                    save(app, new JSONObject(task.getResult().getData()).toString());
                } else {
                    PushSettings.prefs(app).edit().putBoolean("widget_read_failed", true).apply();
                    updateAll(app);
                }
                handler.removeCallbacks(finish);
                finish.run();
            });
    }

    static void save(Context c, String json) {
        try {
            JSONObject incoming = new JSONObject(json);
            String state = incoming.optString("status");
            if (!"OPEN".equals(state) && !"DICHT".equals(state)) return;
            JSONObject stored = new JSONObject(PushSettings.prefs(c).getString(CACHE, "{}"));
            long incomingTime = OffsetDateTime.parse(incoming.optString("last_success")).toInstant().toEpochMilli();
            try {
                long storedTime = OffsetDateTime.parse(stored.optString("last_success")).toInstant().toEpochMilli();
                if (incomingTime < storedTime) return;
            } catch (Exception ignored) { }
        } catch (Exception error) { return; }
        android.content.SharedPreferences prefs = PushSettings.prefs(c);
        String previous = prefs.getString(CACHE, "{}");
        boolean changed = false;
        try {
            JSONObject old = new JSONObject(previous), next = new JSONObject(json);
            changed = !old.optString("status").equals(next.optString("status")) || !statusTiming(old).equals(statusTiming(next));
        } catch (Exception ignored) { }
        android.content.SharedPreferences.Editor editor = prefs.edit().putString(CACHE, json).putBoolean("widget_read_failed", false);
        if (changed) editor.putString("widget_previous", previous)
            .putInt("widget_card_index", 1 - prefs.getInt("widget_card_index", 0));
        boolean recovering = prefs.getBoolean("widget_read_failed", false);
        editor.apply();
        if (changed || recovering) updateAll(c);
    }
    @Override public void onDisabled(Context c) {
        PushSettings.syncWidgetSubscription(c);
        android.app.AlarmManager alarms = (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent expiry = PendingIntent.getBroadcast(c, 0,
            new Intent(c, BridgeWidgetProvider.class).setAction("nl.brugmonitor.app.WIDGET_EXPIRE"),
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (expiry != null) { alarms.cancel(expiry); expiry.cancel(); }
    }
    // Render the push payload immediately: background fetches can be delayed or fail.
    static void receivePush(Context c, java.util.Map<String, String> payload, long sentTime) {
        String state = payload.get("status");
        if (!"OPEN".equals(state) && !"DICHT".equals(state)) return;
        try {
            JSONObject data = new JSONObject(PushSettings.prefs(c).getString(CACHE, "{}"));
            long previous = 0;
            try { previous = OffsetDateTime.parse(data.optString("last_success")).toInstant().toEpochMilli(); }
            catch (Exception ignored) { }
            long timestamp = sentTime > 0 ? sentTime : System.currentTimeMillis();
            if (timestamp < previous) return;
            data.put("status", state);
            data.put("live_text", payload.getOrDefault("live_text", ""));
            data.put("detail", payload.getOrDefault("detail", payload.getOrDefault("body", "")));
            data.put("last_success", java.time.Instant.ofEpochMilli(timestamp).atOffset(java.time.ZoneOffset.UTC).toString());
            data.put("stale", false);
            save(c, data.toString());
        } catch (Exception error) { android.util.Log.w("Brugmonitor", "Widget-push verwerken mislukt", error); }
    }
    static void updateAll(Context c) {
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        for (int id : manager.getAppWidgetIds(new ComponentName(c, BridgeWidgetProvider.class))) render(c, manager, id);
    }
    private static void render(Context c, AppWidgetManager manager, int id) {
        Bundle options = manager.getAppWidgetOptions(id);
        int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180);
        int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40);
        if (Build.VERSION.SDK_INT >= 31) {
            java.util.ArrayList<android.util.SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes != null && !sizes.isEmpty()) {
                java.util.Map<android.util.SizeF, RemoteViews> views = new java.util.LinkedHashMap<>();
                for (android.util.SizeF size : sizes) views.put(size, layout(c, id, (int) size.getWidth(), (int) size.getHeight()));
                manager.updateAppWidget(id, new RemoteViews(views));
                return;
            }
        }
        RemoteViews portrait = layout(c, id, width, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height));
        RemoteViews landscape = layout(c, id, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width), height);
        manager.updateAppWidget(id, new RemoteViews(landscape, portrait));
    }
    // Keep the timing text consistent with remainingText/openDetail in index.html.
    private static String statusTiming(JSONObject data) {
        String live = data.optString("live_text", "");
        String detail = data.optString("detail", "");
        if ("OPEN".equals(data.optString("status"))) {
            Matcher minutes = Pattern.compile("^nog\\s*\\+/-\\s*(\\d+)\\s*(?:minuut|minuten)$", Pattern.CASE_INSENSITIVE).matcher(live);
            if (!minutes.find()) {
                minutes = Pattern.compile("nog\\s+ongeveer\\s+(\\d+)\\s*(?:minuut|minuten)", Pattern.CASE_INSENSITIVE).matcher(detail);
                if (!minutes.find()) {
                    return Pattern.compile("langer\\s+open\\s+dan\\s+verwacht", Pattern.CASE_INSENSITIVE)
                        .matcher(live.isEmpty() ? detail : live).find() ? "Langer open dan verwacht" : "Sluitingstijd onbekend";
                }
            }
            int count = Integer.parseInt(minutes.group(1));
            return "Nog ongeveer " + count + (count == 1 ? " minuut" : " minuten") + " open";
        }
        if ("DICHT".equals(data.optString("status"))) {
            if (Pattern.compile("langer dicht dan verwacht", Pattern.CASE_INSENSITIVE).matcher(live).find()) return "";
            return !detail.isEmpty() ? detail : (!live.isEmpty() ? "Laatste opening: " + live : "");
        }
        return "";
    }

    private static RemoteViews layout(Context c, int id, int width, int height) {
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.bridge_widget);
        int active = PushSettings.prefs(c).getInt("widget_card_index", 0);
        fillCard(c, views, active, PushSettings.prefs(c).getString(CACHE, "{}"), width, height);
        fillCard(c, views, 1 - active, PushSettings.prefs(c).getString("widget_previous", "{}"), width, height);
        views.setInt(R.id.widget_transition, "setDisplayedChild", active);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 0,
            new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return views;
    }

    private static void fillCard(Context c, RemoteViews views, int index, String json, int width, int height) {
        int card = index == 0 ? R.id.widget_card_0 : R.id.widget_card_1;
        int titleView = index == 0 ? R.id.widget_title_0 : R.id.widget_title_1;
        int statusView = index == 0 ? R.id.widget_status_0 : R.id.widget_status_1;
        int detailView = index == 0 ? R.id.widget_detail_0 : R.id.widget_detail_1;
        String state = "LADEN", detail = "";
        int background = R.drawable.widget_background;
        boolean outdated = false;
        try {
            JSONObject data = new JSONObject(json);
            String status = data.optString("status");
            // Keep the last known status, just like the status box in the app.
            // A missed heartbeat or fetch must not erase valid bridge information.
            if ("OPEN".equals(status) || "DICHT".equals(status)) {
                state = status;
                background = "OPEN".equals(status) ? R.drawable.widget_open : R.drawable.widget_closed;
                detail = statusTiming(data);
            }
            try {
                long timestamp = OffsetDateTime.parse(data.optString("last_success")).toInstant().toEpochMilli();
                outdated = data.optBoolean("stale") || PushSettings.prefs(c).getBoolean("widget_read_failed", false)
                    || System.currentTimeMillis() - timestamp > Math.max(90L, data.optLong("heartbeat_seconds", 30) * 3L) * 1000L;
            } catch (Exception ignored) { outdated = true; }
        } catch (Exception ignored) { }
        boolean tall = height >= 75;
        boolean showDetail = !detail.isEmpty() && (tall || width >= 240);
        if (!tall) detail = detail.replace("Nog ongeveer ", "± ").replace(" minuten open", " min").replace(" minuut open", " min");
        views.setInt(card, "setBackgroundResource", background);
        views.setTextViewText(titleView, "Sas van Gent");
        views.setTextViewText(statusView, state);
        views.setTextViewText(detailView, detail);
        views.setViewVisibility(detailView, showDetail ? View.VISIBLE : View.GONE);
        views.setInt(detailView, "setMaxLines", tall && height >= 100 ? 2 : 1);
        views.setTextViewTextSize(titleView, android.util.TypedValue.COMPLEX_UNIT_SP, tall ? 11 : 9);
        views.setTextViewTextSize(statusView, android.util.TypedValue.COMPLEX_UNIT_SP, tall ? (width >= 320 ? 34 : 30) : (showDetail ? 16 : 24));
        views.setTextViewTextSize(detailView, android.util.TypedValue.COMPLEX_UNIT_SP, tall ? (width >= 320 ? 14 : 13) : 10);
        views.setContentDescription(card, state + (detail.isEmpty() ? "" : ", " + detail)
            + (outdated ? ", laatst bekende status" : ""));
    }
}
