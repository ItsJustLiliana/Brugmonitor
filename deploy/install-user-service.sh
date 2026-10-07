#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${project_dir}" =~ [[:space:]] ]]; then
  echo "Gebruik een projectpad zonder spaties, bijvoorbeeld /projects/Brugmonitor." >&2
  exit 1
fi
if [[ ! -f "${project_dir}/.env" ]]; then
  cp "${project_dir}/.env.example" "${project_dir}/.env"
  chmod 600 "${project_dir}/.env"
  echo "Vul eerst GOOGLE_APPLICATION_CREDENTIALS in ${project_dir}/.env in en voer dit script opnieuw uit." >&2
  exit 1
fi
for executable in python chromium chromedriver; do
  if ! command -v "${executable}" >/dev/null; then
    echo "${executable} ontbreekt. Installeer op Arch: sudo pacman -Syu --needed python python-pip chromium" >&2
    exit 1
  fi
done
python -m venv "${project_dir}/.venv"
"${project_dir}/.venv/bin/python" -m pip install -r "${project_dir}/requirements.txt"
"${project_dir}/.venv/bin/python" -m unittest discover -s "${project_dir}/tests" -v
unit_dir="${HOME}/.config/systemd/user"
mkdir -p "${unit_dir}"
python - "${project_dir}" "${unit_dir}/brugmonitor.service" <<'PY'
import pathlib, sys
project = pathlib.Path(sys.argv[1])
text = (project / "deploy/brugmonitor.service").read_text()
pathlib.Path(sys.argv[2]).write_text(text.replace("@PROJECT_DIR@", str(project)))
PY
systemctl --user daemon-reload
systemctl --user enable brugmonitor.service
systemctl --user restart brugmonitor.service
sleep 2
systemctl --user --no-pager status brugmonitor.service
