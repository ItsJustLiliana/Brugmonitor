#!/usr/bin/env bash
set -euo pipefail
project_dir="${BRUGMONITOR_DIR:-/projects/Brugmonitor}"
cd "${project_dir}"
exec 9>"${XDG_RUNTIME_DIR:-/tmp}/brugmonitor-deploy-${UID}.lock"
flock -n 9 || { echo "Een Brugmonitor-update draait al."; exit 1; }
[[ "$(git branch --show-current)" == main ]] || { echo "Brugmonitor moet op main staan." >&2; exit 1; }
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || { echo "Lokale wijzigingen: update afgebroken." >&2; exit 1; }
git fetch --no-tags origin main
target="$(git rev-parse origin/main)"
state_dir="${XDG_STATE_HOME:-${HOME}/.local/state}/brugmonitor"
mkdir -p "${state_dir}"
if [[ -f "${state_dir}/deployed-commit" && "$(cat "${state_dir}/deployed-commit")" == "${target}" && "$(git rev-parse HEAD)" == "${target}" ]]; then
  echo "Brugmonitor is al bijgewerkt."
  exit 0
fi
git merge --ff-only origin/main
# update.sh tests before restarting; a failed deployment is never marked successful.
bash deploy/update.sh
printf '%s\n' "${target}" >"${state_dir}/deployed-commit"
echo "Brugmonitor bijgewerkt naar ${target:0:7}."
