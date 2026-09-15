#!/usr/bin/env bash
#
# Thursday leadership demo driver.
#
# Two acts, one command:
#
#   Act 1  simulate  US + EU all-green run  -> "Green = Go"
#   Act 2  device    real PST3 terminal run -> "Red = No-Go", with the real reason
#
# Each act snapshots its dashboard into reports/demo/<act>/ so the green result survives the
# device run that follows it, and both are available side by side when you present.
#
# Usage: scripts/demo.sh [all|simulate|device] [--serial SERIAL] [--no-open]

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

ACT="all"
SERIAL=""
OPEN=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    all|simulate|device) ACT="$1"; shift ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --no-open) OPEN=0; shift ;;
    -h|--help) sed -n '2,15p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

banner() {
  printf '\n\033[1;36m%s\033[0m\n' "=============================================================="
  printf '\033[1;36m %s\033[0m\n' "$*"
  printf '\033[1;36m%s\033[0m\n\n' "=============================================================="
}
say()  { printf '\033[1;36m[demo]\033[0m %s\n' "$*"; }
cue()  { printf '\033[1;33m[say ]\033[0m %s\n' "$*"; }
pause() { [[ -t 0 ]] && { printf '\n\033[0;90m[demo] press Enter to continue\033[0m'; read -r _; echo; }; }

snapshot() {
  local dest="reports/demo/$1"
  mkdir -p "${dest}"
  for f in reports/phmp-release-gate.html \
           reports/phmp-execution-report-us.html \
           reports/phmp-execution-report-eu.html; do
    [[ -f "${f}" ]] && cp "${f}" "${dest}/"
  done
  say "Snapshot: ${dest}/"
}

# ------------------------------------------------------------------ Act 1
run_simulate() {
  banner "ACT 1 — Simulated nightly build (US + EU): the Go case"
  cue "This is the nightly gate running the full PCM lifecycle end to end, with no hardware."
  # local.properties is configured for the physical terminal (env=dev, cloud inject off), so the
  # simulate act overrides both: the reference run has to exercise every gate, including cloud.
  PHMP_ENV=local PHMP_CLOUD_INJECT_ENABLED=true \
    ./scripts/run-local.sh simulate both 2>&1 \
    | grep -E "PASS \[|FAIL \[|SKIP \[|Passed=|BUILD (SUCCESS|FAILURE)"
  snapshot simulate
  cue "Eleven gates, both regions, one verdict: green means the build can ship."
  [[ "${OPEN}" == "1" && "$(uname)" == "Darwin" ]] && open reports/demo/simulate/phmp-release-gate.html 2>/dev/null || true
}

# ------------------------------------------------------------------ Act 2
run_device() {
  banner "ACT 2 — Real PST3 terminal: the No-Go case"
  # The device stream stays on: the terminal's own PCM log lines next to each verdict are what make
  # the run credible to an audience, and they are kept in logs/live-*.log as the evidence trail.
  local args=(US --force)
  [[ -n "${SERIAL}" ]] && args+=(--serial "${SERIAL}")
  cue "Same engine, same gates. The only change is the target: a physical terminal over ADB."
  say "This takes about five minutes and reads the terminal's own PCM logs."
  ./scripts/run-live.sh "${args[@]}"
  snapshot device
  cue "Red is not a broken test. The report names the upstream cause: the terminal is not"
  cue "activated in this environment, so PCM never gets a token. One reason, six gates."
}

case "${ACT}" in
  simulate) run_simulate ;;
  device)   run_device ;;
  all)      run_simulate; pause; run_device ;;
esac

banner "Artifacts"
say "Go case:    ${ROOT}/reports/demo/simulate/phmp-release-gate.html"
say "No-Go case: ${ROOT}/reports/demo/device/phmp-execution-report-us.html"
say "Trend:      ${ROOT}/reports/history/summary.jsonl"
