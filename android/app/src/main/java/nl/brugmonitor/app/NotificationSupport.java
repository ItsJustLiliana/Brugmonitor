package nl.brugmonitor.app;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class NotificationSupport {
    static final String CHANNEL = "bridge_status";
    static void createChannel(Context c) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Brugstatus", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Melding wanneer de Sas van Gent brug opent of sluit");
        c.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    static void cancel(Context c) { c.getSystemService(NotificationManager.class).cancel("bridge-status", 1); }
    @android.annotation.SuppressLint("MissingPermission")
    static void show(Context c, String title, String body, String eventId) {
        if (!allowed(c) || !PushSettings.wanted(c)) return;
        if (!eventId.isEmpty() && eventId.equals(PushSettings.prefs(c).getString("last_push_event", ""))) return;
        createChannel(c);
        Intent intent = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bridge).setContentTitle(title).setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(open)
            .setAutoCancel(true).build();
        c.getSystemService(NotificationManager.class).notify("bridge-status", 1, notification);
        if (!eventId.isEmpty()) PushSettings.prefs(c).edit().putString("last_push_event", eventId).apply();
    }
}
