"""Public status via Firestore, bridge transitions via FCM. No inbound ports."""
import json
import logging
import os
import sqlite3
import threading
import time
import uuid
from datetime import timedelta
from contextlib import contextmanager
from pathlib import Path

TOPIC = "brugmonitor-sas-van-gent-v1"
DOCUMENT = "bridges/sas-van-gent"
CHANNEL = "bridge_status"
TTL_SECONDS = 120
log = logging.getLogger(__name__)


class PushOutbox:
    """Keep only the latest transition; never replay an obsolete OPEN alert."""
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS pending (slot INTEGER PRIMARY KEY CHECK(slot=1), id TEXT, created REAL, payload TEXT, attempts INTEGER DEFAULT 0, retry_at REAL DEFAULT 0)")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        try:
            with db:
                yield db
        finally:
            db.close()

    def enqueue(self, status, detail, now=None, update=False):
        event = {"event_id": str(uuid.uuid4()), "status": status, "detail": detail, "update": update}
        created = time.time() if now is None else now
        with self.connect() as db:
            pending = db.execute("SELECT payload FROM pending WHERE slot=1").fetchone()
            if update and pending:
                previous = json.loads(pending[0])
                if previous["status"] == status and not previous.get("update", False):
                    event["update"] = False  # Keep the first opening alert audible.
            db.execute("INSERT OR REPLACE INTO pending(slot,id,created,payload,attempts,retry_at) VALUES(1,?,?,?,?,?)", (event["event_id"], created, json.dumps(event), 0, 0))
        return event["event_id"]

    def due(self, now=None):
        now = time.time() if now is None else now
        with self.connect() as db:
            db.execute("DELETE FROM pending WHERE created <= ?", (now - TTL_SECONDS,))
            row = db.execute("SELECT id,created,payload,attempts FROM pending WHERE retry_at <= ?", (now,)).fetchone()
        return row

    def complete(self, event_id):
        with self.connect() as db:
            db.execute("DELETE FROM pending WHERE id=?", (event_id,))

    def retry(self, event_id, attempts, now=None):
        now = time.time() if now is None else now
        delay = min(30, 5 * 2 ** min(attempts, 3))
        with self.connect() as db:
            db.execute("UPDATE pending SET attempts=?,retry_at=? WHERE id=?", (attempts + 1, now + delay, event_id))


class FirebaseRelay:
    def __init__(self, data_dir, heartbeat=30, app=None, document=None, sender=None):
        # Dependencies can be injected for offline tests.
        if document is None or sender is None:
            import firebase_admin
            from firebase_admin import credentials, firestore, messaging
            credential_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
            if not credential_path or not Path(credential_path).is_file():
                raise RuntimeError("FIREBASE_ENABLED=1 maar GOOGLE_APPLICATION_CREDENTIALS verwijst niet naar een leesbare service-account-JSON.")
            app = app or firebase_admin.initialize_app(credentials.Certificate(credential_path), options={"httpTimeout": 10}, name="brugmonitor")
            document = firestore.client(app).document(DOCUMENT)
            sender = lambda payload, ttl, dry_run=False: messaging.send(self.message(payload, ttl), app=app, dry_run=dry_run)
        self.document = document
        self.sender = sender
        self.outbox = PushOutbox(Path(data_dir) / "push.sqlite3")
        self.heartbeat = max(15, int(heartbeat))
        self.last_fingerprint = None
        self.last_publish = 0
        self.last_publish_at = None
        self.publish_ok = False
        self.wake = threading.Event()
        self.stop = threading.Event()
        self.thread = None
        self.current_status = None
        self.current_detail = None

    @staticmethod
    def message(payload, ttl):
        from firebase_admin import messaging
        status = payload["status"]
        title = "Brugmonitor testmelding" if status == "TEST" else "Sas van Gent brug is " + ("open" if status == "OPEN" else "dicht")
        return messaging.Message(
            topic=TOPIC,
            data={"status": status, "event_id": payload["event_id"],
                  "title": title, "body": payload.get("detail", ""),
                  "update": str(payload.get("update", False)).lower()},
            android=messaging.AndroidConfig(
                priority="high", ttl=timedelta(seconds=max(1, ttl)), collapse_key="bridge-status",
            ),
        )

    def publish(self, state, now=None):
        now = time.time() if now is None else now
        public = {k: v for k, v in state.items() if k != "error"}
        public["source"] = "https://www.brug-open.nl/brug/sas%20van%20gent/sas%20van%20gent%20brug"
        public["heartbeat_seconds"] = self.heartbeat
        stable = {k: public.get(k) for k in ("status", "live_text", "detail", "history", "stale")}
        fingerprint = json.dumps(stable, sort_keys=True)
        if fingerprint == self.last_fingerprint and now - self.last_publish < self.heartbeat:
            return False
        try:
            self.document.set(public, retry=None, timeout=10)
        except Exception:
            self.publish_ok = False
            log.exception("Firestore-status bijwerken mislukt; volgende controle probeert opnieuw")
            return False
        self.last_fingerprint = fingerprint
        self.last_publish = now
        self.last_publish_at = now
        self.publish_ok = True
        return True

    def transition(self, previous, current, detail):
        old_detail = self.current_detail
        if current in ("OPEN", "DICHT"):
            self.current_detail = detail
            self.current_status = current
            self.wake.set()
        if previous in ("OPEN", "DICHT") and current in ("OPEN", "DICHT") and previous != current:
            self.outbox.enqueue(current, detail)
            self.wake.set()
            return True
        if previous == current == "OPEN" and old_detail is not None and detail != old_detail:
            self.outbox.enqueue(current, detail, update=True)
            self.wake.set()
        return False

    def start(self):
        self.thread = threading.Thread(target=self.work, name="firebase-push", daemon=True)
        self.thread.start()

    def process_pending(self, now=None):
        now = time.time() if now is None else now
        row = self.outbox.due(now)
        if not row:
            return False
        event_id, created, raw, attempts = row
        if self.current_status is None:
            return False  # establish a fresh baseline after restart before replaying anything
        payload = json.loads(raw)
        if payload["status"] != self.current_status:
            self.outbox.complete(event_id)
            return False
        try:
            self.sender(payload, int(TTL_SECONDS - (now - created)))
        except Exception:
            self.outbox.retry(event_id, attempts, now)
            log.exception("FCM-melding niet verstuurd; tijdelijk opnieuw proberen")
            return False
        self.outbox.complete(event_id)
        log.info("FCM-statusmelding verstuurd: %s", payload["status"])
        return True

    def work(self):
        while not self.stop.is_set():
            try:
                self.process_pending()
            except Exception:
                log.exception("FCM-wachtrij tijdelijk niet beschikbaar")
            self.wake.wait(1)
            self.wake.clear()

    def close(self):
        self.stop.set()
        self.wake.set()
        if self.thread:
            self.thread.join(timeout=12)
