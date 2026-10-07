"""Send a clearly-labelled test to all opted-in Brugmonitor devices."""
import argparse
import os
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dotenv import load_dotenv
from cloud import FirebaseRelay

parser = argparse.ArgumentParser()
parser.add_argument("--dry-run", action="store_true", help="Validate with Firebase without delivery")
args = parser.parse_args()
base = Path(__file__).resolve().parents[1]
load_dotenv(base / ".env")
relay = FirebaseRelay(base / os.environ.get("BRUGMONITOR_DATA_DIR", ".data"))
payload = {"event_id": __import__("uuid").uuid4().hex, "status": "TEST", "detail": "De verbinding tussen je Linux-server en Brugmonitor werkt."}
message_id = relay.sender(payload, 120, args.dry_run)
print(("Dry-run geslaagd: " if args.dry_run else "Testmelding verstuurd: ") + message_id)
