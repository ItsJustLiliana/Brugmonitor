import copy
import json
import tempfile
import unittest
from unittest.mock import Mock, patch
from pathlib import Path
from cloud import FirebaseRelay, PushOutbox, TOPIC, CHANNEL, TTL_SECONDS


class CloudTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.document = Mock()
        self.sender = Mock(return_value="projects/test/messages/1")
        self.relay = FirebaseRelay(self.temp.name, document=self.document, sender=self.sender)
        self.payload = {"status": "DICHT", "live_text": "Onlangs gesloten", "detail": "", "history": [], "stale": False, "last_success": "2026-10-07T12:00:00+02:00", "error": "private diagnostics"}

    def test_first_observation_same_status_and_invalid_status_send_nothing(self):
        for previous, current in [(None, "OPEN"), ("OPEN", "OPEN"), ("DICHT", None)]:
            self.assertFalse(self.relay.transition(previous, current, ""))
        self.assertIsNone(self.relay.outbox.due())
        self.sender.assert_not_called()

    def test_transition_is_sent_once_and_removed(self):
        self.assertTrue(self.relay.transition("DICHT", "OPEN", "Nog ongeveer 5 minuten"))
        self.assertTrue(self.relay.process_pending())
        self.assertFalse(self.relay.process_pending())
        self.sender.assert_called_once()
        self.assertEqual(self.sender.call_args.args[0]["status"], "OPEN")

    def test_failed_send_retries_after_backoff(self):
        self.relay.outbox.enqueue("OPEN", "", now=100)
        self.relay.current_status = "OPEN"
        self.sender.side_effect = RuntimeError("temporary network failure")
        with self.assertLogs("cloud", level="ERROR"):
            self.assertFalse(self.relay.process_pending(now=100))
        self.sender.side_effect = None
        self.assertFalse(self.relay.process_pending(now=104))
        self.assertTrue(self.relay.process_pending(now=105))
        self.assertEqual(self.sender.call_count, 2)

    def test_expired_and_superseded_events_are_not_replayed(self):
        old = self.relay.outbox.enqueue("OPEN", "", now=100)
        new = self.relay.outbox.enqueue("DICHT", "", now=101)
        self.relay.outbox.complete(old)  # in-flight old completion cannot remove new event
        self.assertEqual(self.relay.outbox.due(now=102)[0], new)
        self.assertIsNone(self.relay.outbox.due(now=101 + TTL_SECONDS))

    def test_queue_survives_restart(self):
        event = self.relay.outbox.enqueue("OPEN", "", now=100)
        restored = PushOutbox(Path(self.temp.name) / "push.sqlite3")
        self.assertEqual(restored.due(now=101)[0], event)

    def test_heartbeat_limits_writes_and_changes_are_immediate(self):
        self.assertTrue(self.relay.publish(self.payload, now=100))
        changed_time = dict(self.payload, last_success="2026-10-07T12:00:05+02:00")
        self.assertFalse(self.relay.publish(changed_time, now=105))
        self.assertTrue(self.relay.publish(changed_time, now=130))
        self.assertTrue(self.relay.publish(dict(changed_time, status="OPEN"), now=131))
        self.assertEqual(self.document.set.call_count, 3)
        self.assertNotIn("error", self.document.set.call_args.args[0])

    def test_firestore_failure_is_retried_without_marking_publish_success(self):
        self.document.set.side_effect = RuntimeError("unavailable")
        with self.assertLogs("cloud", level="ERROR"):
            self.assertFalse(self.relay.publish(self.payload, now=100))
        self.assertFalse(self.relay.publish_ok)
        self.document.set.side_effect = None
        self.assertTrue(self.relay.publish(self.payload, now=101))
        self.assertTrue(self.relay.publish_ok)

    def test_fcm_message_channel_topic_and_short_lifetime(self):
        message = self.relay.message({"event_id": "event-1", "status": "OPEN", "detail": "Nog 5 minuten"}, 60)
        self.assertEqual(message.topic, TOPIC)
        self.assertIsNone(message.notification)
        self.assertIsNone(message.android.notification)
        self.assertEqual(message.android.collapse_key, "bridge-status")
        self.assertEqual(message.android.priority, "high")
        self.assertEqual(message.data["body"], "Nog 5 minuten")
        self.assertEqual(message.data["update"], "false")
        self.assertEqual(message.android.ttl.total_seconds(), 60)
        self.assertEqual(message.data["event_id"], "event-1")

    def test_restart_waits_for_fresh_baseline_and_discards_wrong_old_status(self):
        self.relay.outbox.enqueue("OPEN", "", now=100)
        self.assertFalse(self.relay.process_pending(now=101))
        self.sender.assert_not_called()
        self.relay.transition(None, "DICHT", "")
        self.assertFalse(self.relay.process_pending(now=102))
        self.assertIsNone(self.relay.outbox.due(now=103))
        self.sender.assert_not_called()

    def test_remaining_minutes_update_the_open_notification_silently(self):
        self.relay.transition("DICHT", "OPEN", "Nog 5 minuten")
        self.relay.process_pending()
        self.relay.transition("OPEN", "OPEN", "Nog 4 minuten")
        self.relay.process_pending()
        payload = self.sender.call_args.args[0]
        self.assertTrue(payload["update"])
        self.assertEqual(payload["detail"], "Nog 4 minuten")
        self.relay.transition("OPEN", "OPEN", "Nog 4 minuten")
        self.assertFalse(self.relay.process_pending())

    def test_minute_update_preserves_pending_initial_alert(self):
        self.relay.transition("DICHT", "OPEN", "Nog 5 minuten")
        self.relay.transition("OPEN", "OPEN", "Nog 4 minuten")
        self.relay.process_pending()
        self.assertFalse(self.sender.call_args.args[0]["update"])

    def test_closed_notification_is_a_separate_alert(self):
        self.relay.transition("DICHT", "OPEN", "Nog 5 minuten")
        self.relay.process_pending()
        self.relay.transition("OPEN", "DICHT", "De brug is dicht")
        self.relay.process_pending()
        self.assertEqual(self.sender.call_args.args[0]["status"], "DICHT")
        self.assertFalse(self.sender.call_args.args[0]["update"])
