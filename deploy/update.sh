#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
# Upload updated files or git pull --ff-only before running this script.
"${project_dir}/.venv/bin/python" -m pip install -r "${project_dir}/requirements.txt"
"${project_dir}/.venv/bin/python" -m unittest discover -s "${project_dir}/tests" -v
systemctl --user restart brugmonitor.service
systemctl --user --no-pager status brugmonitor.service
