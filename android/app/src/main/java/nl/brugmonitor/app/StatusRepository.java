package nl.brugmonitor.app;

import android.content.Context;
import com.google.firebase.firestore.*;
import org.json.JSONObject;
import java.time.OffsetDateTime;

final class StatusRepository {
    private final Context context;
    private volatile String latest = "";
    private volatile boolean fromCache = true;
    private volatile boolean failed = false;
    private ListenerRegistration listener;

    StatusRepository(Context context) { this.context = context.getApplicationContext(); }

    void start() {
        if (listener != null || !PushSettings.configured(context)) return;
        listener = FirebaseFirestore.getInstance().document("bridges/sas-van-gent")
            .addSnapshotListener(MetadataChanges.INCLUDE, (snapshot, error) -> {
                if (error != null) {
                    failed = true;
                    android.util.Log.w("Brugmonitor", "Firebase-status lezen mislukt", error);
                    return;
                }
                if (snapshot == null || !snapshot.exists() || snapshot.getData() == null) {
                    failed = true;
                    return;
                }
                latest = new JSONObject(snapshot.getData()).toString();
                fromCache = snapshot.getMetadata().isFromCache();
                failed = false;
            });
    }

    void stop() { if (listener != null) { listener.remove(); listener = null; } }

    String json() {
        if (latest.isEmpty()) return "";
        try {
            JSONObject data = new JSONObject(latest);
            long checked = OffsetDateTime.parse(data.optString("last_success")).toInstant().toEpochMilli();
            long maxAge = Math.max(90, data.optLong("heartbeat_seconds", 30) * 3) * 1000;
            boolean old = System.currentTimeMillis() - checked > maxAge;
            data.put("stale", data.optBoolean("stale") || old || fromCache || failed);
            return data.toString();
        } catch (Exception e) { return ""; }
    }
}
