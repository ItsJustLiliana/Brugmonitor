import copy
import unittest
from unittest.mock import Mock, patch
from datetime import datetime
import app


class AppTests(unittest.TestCase):
    def setUp(self):
        self.original_state = copy.deepcopy(app.state)
        self.original_relay = app.relay
        self.addCleanup(self.restore)
        app.state.update(status=None, last_change=None, stale=False)
        app.relay = Mock(publish_ok=False, last_publish_at=None)

    def restore(self):
        app.state.clear()
        app.state.update(self.original_state)
        app.relay = self.original_relay

    def result(self, status):
        return {"status": status, "live_text": "Onlangs gesloten" if status == "DICHT" else "nog +/- 5 minuten", "message": "", "detail": "Details", "history": [], "checked": datetime.now(app.TIMEZONE)}

    def test_valid_transition_updates_timestamp_and_queues_push(self):
        app.apply_result(self.result("DICHT"))
        self.assertIsNone(app.state["last_change"])
        app.apply_result(self.result("OPEN"))
        self.assertIsNotNone(app.state["last_change"])
        self.assertEqual(app.state["status"], "OPEN")
        app.relay.transition.assert_called_with("DICHT", "OPEN", "Details")
        self.assertTrue(app.state["last_success"].endswith(("+02:00", "+01:00")))

    def test_api_serves_no_source_files(self):
        client = app.app.test_client()
        with client.get("/") as response:
            self.assertEqual(response.status_code, 200)
        self.assertEqual(client.get("/Brugmonitor/app.py").status_code, 404)
        self.assertEqual(client.get("/api/health").status_code, 200)

    def test_linux_browser_paths_are_used(self):
        with patch.dict(app.os.environ, {"CHROME_BINARY": "/usr/bin/chromium", "CHROMEDRIVER_PATH": "/usr/bin/chromedriver", "CHROME_NO_SANDBOX": "1"}):
            with patch.object(app.webdriver, "Chrome") as chrome:
                app.make_driver()
                options = chrome.call_args.kwargs["options"]
                service = chrome.call_args.kwargs["service"]
                self.assertEqual(options.binary_location, "/usr/bin/chromium")
                self.assertEqual(service.path, "/usr/bin/chromedriver")
                self.assertIn("--no-sandbox", options.arguments)

    def test_live_text_parser(self):
        for text, status in [("nog +/- 5 minuten", "OPEN"), ("langer open dan verwacht", "OPEN"), ("10 minuten geleden", "DICHT"), ("Onlangs gesloten", "DICHT")]:
            self.assertEqual(app.parse_live_indicator(text)["status"], status)
        self.assertIsNone(app.parse_live_indicator("geen geldige data"))

    def test_source_failure_retains_status_and_does_not_queue_push(self):
        app.state["status"] = "DICHT"
        original_driver = app.driver
        app.driver = Mock()
        def fail():
            app.stop_event.set()
            raise RuntimeError("source unavailable")
        try:
            with patch.object(app, "scrape_once", side_effect=fail):
                app.monitor_loop()
            self.assertEqual(app.state["status"], "DICHT")
            self.assertTrue(app.state["stale"])
            app.relay.transition.assert_not_called()
            self.assertTrue(app.relay.publish.call_args.args[0]["stale"])
        finally:
            app.driver = original_driver
            app.stop_event.clear()

    def test_push_queue_failure_does_not_mark_fresh_source_as_stale(self):
        app.relay.transition.side_effect = RuntimeError("queue unavailable")
        with self.assertLogs("app", level="ERROR"):
            app.apply_result(self.result("OPEN"))
        self.assertEqual(app.state["status"], "OPEN")
        self.assertFalse(app.state["stale"])

    def test_remaining_text_used_by_app_and_push(self):
        for raw, expected in [("nog +/- 5 minuten", "Nog ongeveer 5 minuten open"), ("nog +/- 1 minuten", "Nog ongeveer 1 minuut open"), ("langer open dan verwacht", "Sluitingstijd onbekend")]:
            with self.subTest(raw=raw), patch.object(app, "driver") as driver, patch.object(app.time, "sleep"):
                driver.execute_script.return_value = {"topTexts": [{"text": raw, "y": 1}], "tables": []}
                result = app.scrape_once()
                self.assertEqual(result["status"], "OPEN")
                self.assertEqual(result["detail"], expected)
