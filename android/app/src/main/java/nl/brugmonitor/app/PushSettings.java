package nl.brugmonitor.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import org.json.JSONObject;

final class PushSettings {
    static final String TOPIC = "brugmonitor-sas-van-gent-v1";
    static volatile String error = "";
    private static volatile boolean busy = false;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("brugmonitor", Context.MODE_PRIVATE); }
    static boolean configured(Context c) { return BuildConfig.FIREBASE_CONFIGURED && !FirebaseApp.getApps(c).isEmpty(); }
    static boolean wanted(Context c) { return prefs(c).getBoolean("push_wanted", false); }

    static synchronized void enable(Context c) {
        Context app = c.getApplicationContext();
        if (!configured(app) || busy) return;
        if (!NotificationSupport.allowed(app)) {
            error = "Sta meldingen toe in de Android-instellingen van Brugmonitor.";
            return;
        }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
            error = "Google Play-services zijn niet beschikbaar op deze telefoon.";
            return;
        }
        error = ""; busy = true;
        prefs(app).edit().putBoolean("push_wanted", true).apply();
        FirebaseMessaging messaging = FirebaseMessaging.getInstance();
        messaging.setAutoInitEnabled(true);
        messaging.subscribeToTopic(TOPIC).addOnCompleteListener(task -> {
            busy = false;
            prefs(app).edit().putBoolean("push_subscribed", task.isSuccessful()).putBoolean("widget_topic_subscribed", task.isSuccessful()).apply();
            if (!task.isSuccessful()) error = "Meldingen activeren mislukt. Controleer internet en probeer opnieuw.";
        });
    }

    private static boolean widgetSyncBusy = false;
    static synchronized void syncWidgetSubscription(Context c) {
        Context app = c.getApplicationContext();
        if (!configured(app) || widgetSyncBusy || busy) return;
        boolean needed = BridgeWidgetProvider.hasWidgets(app) || wanted(app);
        if (needed && prefs(app).getBoolean("widget_topic_subscribed", false)) return;
        if (!needed && !prefs(app).getBoolean("widget_topic_subscribed", false)) return;
        widgetSyncBusy = true;
        FirebaseMessaging messaging = FirebaseMessaging.getInstance();
        if (needed) messaging.setAutoInitEnabled(true);
        com.google.android.gms.tasks.Task<Void> task = needed
            ? messaging.subscribeToTopic(TOPIC) : messaging.unsubscribeFromTopic(TOPIC);
        task.addOnCompleteListener(result -> {
            widgetSyncBusy = false;
            if (result.isSuccessful()) {
                prefs(app).edit().putBoolean("widget_topic_subscribed", needed).apply();
                if (!needed && !wanted(app) && !BridgeWidgetProvider.hasWidgets(app)) messaging.setAutoInitEnabled(false);
                if (needed != (BridgeWidgetProvider.hasWidgets(app) || wanted(app))) {
                    syncWidgetSubscription(app);
                }
            } else android.util.Log.w("Brugmonitor", "Widget-pushabonnement mislukt", result.getException());
        });
    }

    static synchronized void disable(Context c) {
        Context app = c.getApplicationContext();
        if (!configured(app) || busy) return;
        if (BridgeWidgetProvider.hasWidgets(app)) {
            prefs(app).edit().putBoolean("push_wanted", false).putBoolean("push_subscribed", false).apply();
            NotificationSupport.cancel(app);
            error = "";
            syncWidgetSubscription(app);
            return;
        }
        error = ""; busy = true;
        FirebaseMessaging messaging = FirebaseMessaging.getInstance();
        messaging.unsubscribeFromTopic(TOPIC).addOnCompleteListener(task -> {
            busy = false;
            if (task.isSuccessful()) {
                prefs(app).edit().putBoolean("push_wanted", false).putBoolean("push_subscribed", false).putBoolean("widget_topic_subscribed", false).apply();
                if (!BridgeWidgetProvider.hasWidgets(app)) messaging.setAutoInitEnabled(false);
                syncWidgetSubscription(app);
                NotificationSupport.cancel(app);
            } else error = "Uitschakelen mislukt. Controleer internet en probeer opnieuw.";
        });
    }

    static void sync(Context c) {
        Context app = c.getApplicationContext();
        syncWidgetSubscription(app);
        if (wanted(app) && configured(app) && NotificationSupport.allowed(app)
            && !prefs(app).getBoolean("push_subscribed", false)) enable(app);
    }

    static void tokenChanged(Context c) {
        Context app = c.getApplicationContext();
        prefs(app).edit().putBoolean("widget_topic_subscribed", false).putBoolean("push_subscribed", false).apply();
        sync(app);
    }

    static String json(Context c) {
        try {
            return new JSONObject()
                .put("configured", configured(c))
                .put("wanted", wanted(c))
                .put("enabled", wanted(c) && prefs(c).getBoolean("push_subscribed", false) && NotificationSupport.allowed(c))
                .put("busy", busy).put("error", error).toString();
        } catch (Exception e) { return "{}"; }
    }
}
