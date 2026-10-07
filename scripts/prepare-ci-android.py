"""Restore CI inputs without logging their contents; fail rather than ship a broken app."""
import base64
import json
import os
from pathlib import Path

root = Path(__file__).resolve().parents[1]
config = os.environ.get("FIREBASE_CONFIG", "")
key = os.environ.get("SIGNING_KEY", "")
if not config or not key:
    raise SystemExit("Configure GOOGLE_SERVICES_JSON and ANDROID_DEBUG_KEYSTORE_BASE64 in repository Actions secrets. See AUTOMATION.md.")
try:
    data = json.loads(config)
    packages = [client["client_info"]["android_client_info"]["package_name"] for client in data["client"]]
    if "nl.brugmonitor.app" not in packages:
        raise ValueError("wrong package")
    signing_bytes = base64.b64decode(key, validate=True)
    if not signing_bytes:
        raise ValueError("empty key")
except (ValueError, KeyError, TypeError):
    raise SystemExit("Invalid Firebase configuration or signing key; check repository Actions secrets.") from None
(root / "android/app/google-services.json").write_text(config, encoding="utf-8")
key_path = Path.home() / ".android/debug.keystore"
key_path.parent.mkdir(parents=True, exist_ok=True)
key_path.write_bytes(signing_bytes)
key_path.chmod(0o600)
print("Firebase configuration and existing signing key restored.")
