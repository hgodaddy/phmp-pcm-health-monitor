#!/usr/bin/env bash
#
# One command for a live, observable run against a real terminal.
#
# PHMP observes rather than drives, so the terminal's screen shows nothing during a run. This script
# makes the run watchable instead: it streams the terminal's PCM log lines into your console, prefixed
# and interleaved with each gate's verdict, and traces every adb command PHMP issues.
#
#   [device]  lines PCM itself printed on the terminal
#   [phmp]    what the harness is doing and deciding
#
# Usage: scripts/run-live.sh [US|EU] [--serial SERIAL] [--env dev|ote|prod]
#                            [--no-token] [--quiet-device] [--force]
#
# It refuses to start when the terminal cannot authenticate in the target environment, since every
# socket gate would then fail for that one upstream reason. --force runs anyway.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

REGION="US"
SERIAL=""
TARGET_ENV=""
FETCH_TOKEN=1
STREAM_DEVICE=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    US|us|EU|eu) REGION="$(echo "$1" | tr '[:lower:]' '[:upper:]')"; shift ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --env) TARGET_ENV="$2"; shift 2 ;;
    --no-token) FETCH_TOKEN=0; shift ;;
    --quiet-device) STREAM_DEVICE=0; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

REGION_LOWER="$(echo "${REGION}" | tr '[:upper:]' '[:lower:]')"

prop() { [[ -f local.properties ]] && grep -E "^$1=" local.properties | tail -1 | cut -d= -f2- || true; }

[[ -z "${SERIAL}" ]] && SERIAL="$(prop "phmp.${REGION_LOWER}.serial")"
[[ -z "${TARGET_ENV}" ]] && TARGET_ENV="$(prop phmp.env)"
[[ -z "${TARGET_ENV}" ]] && TARGET_ENV="dev"

say() { printf '\033[1;36m[phmp]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[phmp]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- preflight
say "Region=${REGION}  Env=${TARGET_ENV}  Serial=${SERIAL:-<unset>}"
[[ -z "${SERIAL}" ]] && die "No serial. Set phmp.${REGION_LOWER}.serial in local.properties or pass --serial."

command -v adb >/dev/null || die "adb not on PATH."
if ! adb devices | grep -q "^${SERIAL}[[:space:]]*device$"; then
  echo; adb devices -l
  die "Terminal ${SERIAL} is not in 'device' state. Reseat USB, confirm the RSA prompt, then retry."
fi
say "Terminal online."

BOOT="$(adb -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
[[ "${BOOT}" == "1" ]] || die "Terminal has not finished booting (sys.boot_completed=${BOOT:-<empty>})."

# A run scores the socket over a ~5 minute window; a terminal that just booted has no history yet.
UPTIME_S="$(adb -s "${SERIAL}" shell cat /proc/uptime 2>/dev/null | tr -d '\r' | cut -d. -f1)"
if [[ -n "${UPTIME_S}" && "${UPTIME_S}" -lt 120 ]]; then
  say "Terminal booted ${UPTIME_S}s ago; waiting for PCM to settle."
  sleep $((120 - UPTIME_S))
fi

# A terminal that was pointed at a new environment without being activated there cannot obtain a token,
# so PCM never authenticates and six gates fail five minutes later for one upstream reason. Say so now.
READINESS="$(adb -s "${SERIAL}" shell 'logcat -d | grep -aoE "apiErrorCode=[A-Z_]*NOT_ACTIVATED|failed to obtain token|fallback to un secure mode" | sort -u' 2>/dev/null | tr -d '\r')"
if [[ -n "${READINESS}" ]]; then
  echo
  printf '\033[1;31m[phmp]\033[0m %s\n' "Terminal is not ready to authenticate in ${TARGET_ENV}:"
  echo "${READINESS}" | sed 's/^/          /'
  cat <<'EOF'

          PCM cannot obtain an access token, so it is dialling the stream URL unauthenticated.
          The Authentication, WebSocket, Socket Health, Ping/Pong and Reconnect gates will fail
          for this one upstream reason.

          To fix it, the terminal has to be activated in the target environment: sign in on the
          terminal (Poynt services shows the activation screen) with credentials for that
          environment's business, or ask the provisioning owner to activate this serial there.

          Re-run with --force to execute anyway and capture the evidence in a report.
EOF
  if [[ "${FORCE:-0}" != "1" ]]; then
    exit 3
  fi
  say "Continuing because --force was given."
fi

if [[ "${FETCH_TOKEN}" == "1" ]]; then
  say "Recovering the terminal's access token from logcat."
  ./scripts/fetch-device-token.sh "${SERIAL}" --env "${TARGET_ENV}" || \
    say "Token not recovered; the run continues and the Authentication gate will report why."
fi

mkdir -p logs reports
LIVE_LOG="logs/live-${SERIAL}-$(date +%Y%m%d-%H%M%S).log"

# ---------------------------------------------------------------- device stream
DEVICE_PID=""
if [[ "${STREAM_DEVICE}" == "1" ]]; then
  say "Streaming PCM activity from the terminal into this console."
  echo
  # Tokens are redacted on the way through: this stream is echoed to the console and kept on disk.
  ( adb -s "${SERIAL}" logcat -v time \
      | grep --line-buffered -aiE 'pcm-service|pcmservice|websocket|handshake|access token|un secure|cloud message' \
      | sed -l -E 's/(token=ey[A-Za-z0-9_-]{6})[A-Za-z0-9._-]+/\1REDACTED/' \
      | tee -a "${LIVE_LOG}" \
      | while IFS= read -r l; do printf '\033[0;90m[device]\033[0m %s\n' "$(echo "$l" | cut -c1-200)"; done ) &
  DEVICE_PID=$!
fi

cleanup() {
  for p in ${DEVICE_PID}; do kill "${p}" 2>/dev/null || true; done
  pkill -f "adb -s ${SERIAL} logcat" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------- the run
say "Starting the 11-gate lifecycle. This takes about five minutes."
say "Full device capture: ${LIVE_LOG}"
echo

mvn -B test \
  -Dphmp.mode=device \
  -Dphmp.env="${TARGET_ENV}" \
  -Dphmp.region="${REGION}" \
  -Dphmp.trace=true \
  -Dsurefire.printSummary=true 2>&1 \
  | while IFS= read -r line; do
      case "${line}" in
        *"PASS ["*)   printf '\033[0;32m[phmp]\033[0m %s\n' "${line#*INFO  }" ;;
        *"FAIL ["*)   printf '\033[0;31m[phmp]\033[0m %s\n' "${line#*ERROR }" ;;
        *"SKIP ["*)   printf '\033[0;33m[phmp]\033[0m %s\n' "${line#*INFO  }" ;;
        *"[device] "*) printf '\033[0;90m%s\033[0m\n' "${line#*INFO  }" ;;
        *EXECUTION\ SUMMARY*|*Passed=*|*Report=*|*Device=*)
                      printf '\033[1;36m[phmp]\033[0m %s\n' "${line#*INFO  }" ;;
        *ERROR*)      printf '\033[0;31m%s\033[0m\n' "${line}" ;;
      esac
    done

STATUS=${PIPESTATUS[0]}
cleanup

# ---------------------------------------------------------------- report
REPORT="$(ls -t reports/phmp-"${REGION_LOWER}"-*.html 2>/dev/null | head -1)"
echo
if [[ -n "${REPORT}" ]]; then
  say "Report: ${REPORT}"
  [[ "$(uname)" == "Darwin" ]] && open "${REPORT}" 2>/dev/null || true
fi
say "Device capture: ${LIVE_LOG}"
[[ "${STATUS}" -eq 0 ]] && say "Run clean: every evaluated gate passed." \
                        || say "Gates failed. The verdict lines above and the report give the reason."
exit "${STATUS}"
