"""Single-process server: one Selenium monitor and one FCM worker."""
import logging
import os
import signal
import threading
from pathlib import Path
from dotenv import load_dotenv


def run(monitor):
    from waitress import create_server
    from cloud import FirebaseRelay
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    if os.environ.get("FIREBASE_ENABLED", "0") == "1":
        data_dir = Path(os.environ.get("BRUGMONITOR_DATA_DIR", ".data"))
        if not data_dir.is_absolute():
            data_dir = monitor.BASE_DIR / data_dir
        monitor.relay = FirebaseRelay(data_dir, os.environ.get("FIRESTORE_HEARTBEAT_SECONDS", "30"))
        monitor.relay.start()
    thread = threading.Thread(target=monitor.monitor_loop, name="bridge-monitor", daemon=True)
    host = os.environ.get("BRUGMONITOR_HOST", "127.0.0.1")
    port = int(os.environ.get("BRUGMONITOR_PORT", "8080"))
    http = create_server(monitor.app, host=host, port=port, threads=4)
    def shutdown(signum, frame):
        monitor.stop_event.set()
        http.close()
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    thread.start()
    logging.info("Monitor actief; lokale diagnosepagina http://%s:%s; Firebase %s", host, port, bool(monitor.relay))
    try:
        http.run()
    finally:
        monitor.stop_event.set()
        thread.join(timeout=30)
        if monitor.relay:
            monitor.relay.close()
        if monitor.driver:
            try:
                monitor.driver.quit()
            except Exception:
                pass
        http.close()


def main():
    load_dotenv(Path(__file__).resolve().parent / ".env")
    import app
    run(app)


if __name__ == "__main__":
    main()
