package nl.brugmonitor.app;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

final class NotificationSupport {
    static final String CHANNEL = "bridge_status";
    // Legacy FCM notifications and test messages use this tag.
    static final String TAG = "bridge-status";
    static final int ID = 0;
    static void createChannel(Context c) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Brugstatus", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Melding op het scherm wanneer de Sas van Gent brug opent of sluit");
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        channel.enableVibration(true);
        // Recreating a channel preserves the user's existing Android settings.
        c.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    static void clearLegacy(Context c) { c.getSystemService(NotificationManager.class).cancel(TAG, 1); }
    static void cancel(Context c) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        manager.cancel(TAG, ID);
        manager.cancel("bridge-open", ID);
        manager.cancel("bridge-closed", ID);
        clearLegacy(c);
    }
    @android.annotation.SuppressLint("MissingPermission")
    static void show(Context c, String title, String body, String eventId) {
        showStatus(c, title, body, eventId, "TEST", false);
    }
    @android.annotation.SuppressLint("MissingPermission")
    static void showStatus(Context c, String title, String body, String eventId, String status, boolean update) {
        if (!allowed(c) || !PushSettings.wanted(c)) return;
        if (!eventId.isEmpty() && eventId.equals(PushSettings.prefs(c).getString("last_push_event", ""))) return;
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        String tag = "OPEN".equals(status) ? "bridge-open" : "DICHT".equals(status) ? "bridge-closed" : TAG;
        if (update) {
            boolean active = false;
            for (android.service.notification.StatusBarNotification posted : manager.getActiveNotifications()) {
                if (tag.equals(posted.getTag()) && posted.getId() == ID) active = true;
            }
            if (!active) return; // Do not restore an opening notification the user dismissed.
        }
        if ("OPEN".equals(status) || "DICHT".equals(status)) {
            manager.cancel("OPEN".equals(status) ? "bridge-closed" : "bridge-open", ID);
            manager.cancel(TAG, ID);
        }
        createChannel(c);
        Intent intent = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bridge).setContentTitle(title).setContentText(body)
            .setCategory(Notification.CATEGORY_STATUS)
            .setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(open)
            .setOnlyAlertOnce(update).setAutoCancel(true).build();
        clearLegacy(c);
        c.getSystemService(NotificationManager.class).notify(tag, ID, notification);
        if (!eventId.isEmpty()) PushSettings.prefs(c).edit().putString("last_push_event", eventId).apply();
    }
}
