package nl.brugmonitor.app;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class BridgeMessagingService extends FirebaseMessagingService {
    @Override public void onMessageReceived(RemoteMessage message) {
        RemoteMessage.Notification notification = message.getNotification();
        BridgeWidgetProvider.receivePush(this, message.getData(), message.getSentTime());
        sendBroadcast(new android.content.Intent(this, BridgeWidgetProvider.class)
            .setAction("nl.brugmonitor.app.WIDGET_REFRESH"));
        if (message.getData().containsKey("status")) {
            NotificationSupport.showStatus(this, message.getData().getOrDefault("title", notification != null ? notification.getTitle() : "Brugmonitor"),
                message.getData().getOrDefault("body", notification != null ? notification.getBody() : ""),
                message.getData().getOrDefault("event_id", ""),
                message.getData().get("status"),
                Boolean.parseBoolean(message.getData().getOrDefault("update", "false")));
        } else if (notification != null) {
            NotificationSupport.show(this, notification.getTitle(), notification.getBody(),
                message.getData().getOrDefault("event_id", ""));
        }
    }
    @Override public void onNewToken(String token) { PushSettings.tokenChanged(this); }
    // Newer FCM versions also report installation-ID rotation through this callback.
    public void onRegistered(String installationId) { PushSettings.tokenChanged(this); }
}
