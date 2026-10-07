package nl.brugmonitor.app;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class BridgeMessagingService extends FirebaseMessagingService {
    @Override public void onMessageReceived(RemoteMessage message) {
        RemoteMessage.Notification notification = message.getNotification();
        if (notification != null) {
            NotificationSupport.show(this, notification.getTitle(), notification.getBody(),
                message.getData().getOrDefault("event_id", ""));
        }
    }
    @Override public void onNewToken(String token) { PushSettings.sync(this); }
    // Newer FCM versions also report installation-ID rotation through this callback.
    public void onRegistered(String installationId) { PushSettings.sync(this); }
}
