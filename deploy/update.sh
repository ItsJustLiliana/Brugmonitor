#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${project_dir}"
# Upload updated files or git pull --ff-only before running this script.
"${project_dir}/.venv/bin/python" -m pip install -r "${project_dir}/requirements.txt"
"${project_dir}/.venv/bin/python" -m unittest discover -s "${project_dir}/tests" -v
unit_dir="${HOME}/.config/systemd/user"
mkdir -p "${unit_dir}"
"${project_dir}/.venv/bin/python" - "${project_dir}" "${unit_dir}/brugmonitor.service" <<'PYUNIT'
from pathlib import Path
import sys
project = Path(sys.argv[1])
Path(sys.argv[2]).write_text((project / "deploy/brugmonitor.service").read_text().replace("@PROJECT_DIR@", str(project)))
PYUNIT
systemctl --user daemon-reload
systemctl --user restart brugmonitor.service
"${project_dir}/.venv/bin/python" - "${project_dir}" <<'PYHEALTH'
from pathlib import Path
import sys, time, urllib.request
from dotenv import dotenv_values
config = dotenv_values(Path(sys.argv[1]) / ".env")
host = config.get("BRUGMONITOR_HOST") or "127.0.0.1"
if host == "0.0.0.0": host = "127.0.0.1"
if host == "::": host = "::1"
if ":" in host: host = "[" + host + "]"
port = int(config.get("BRUGMONITOR_PORT") or "8080")
url = f"http://{host}:{port}/api/health"
for attempt in range(15):
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            if response.status == 200:
                print("Brugmonitor-service is bereikbaar.")
                break
    except (OSError, ValueError):
        pass
    time.sleep(2)
else:
    raise SystemExit("Brugmonitor is na herstart niet bereikbaar; controleer journalctl --user -u brugmonitor.service.")
PYHEALTH
systemctl --user is-active --quiet brugmonitor.service
systemctl --user --no-pager status brugmonitor.service
