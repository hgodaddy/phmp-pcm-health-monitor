#!/usr/bin/env bash
# PHMP device qualification (Priority 2, Step 1).
#
# Captures ground truth from a real terminal BEFORE the suite is run, and reports whether the log
# markers each gate depends on are actually present. A gate whose markers are missing cannot produce a
# trustworthy result, so this must be clean before a demo.
#
# Usage:
#   ./scripts/qualify-device.sh [SERIAL]
#   ./scripts/qualify-device.sh ST3SL512NY000341 --exercise-disconnect
#
# Serial resolution order: argument, PHMP_US_SERIAL, phmp.us.serial in local.properties.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PKG="${PHMP_PCM_PACKAGE:-co.poynt.services.pcm}"
EXERCISE_DISCONNECT=false
SERIAL=""

for arg in "$@"; do
  case "$arg" in
    --exercise-disconnect) EXERCISE_DISCONNECT=true ;;
    -*) echo "Unknown option: $arg"; exit 2 ;;
    *) SERIAL="$arg" ;;
  esac
done

if [[ -z "$SERIAL" ]]; then
  SERIAL="${PHMP_US_SERIAL:-}"
fi
if [[ -z "$SERIAL" && -f local.properties ]]; then
  SERIAL="$(awk -F= '/^[[:space:]]*phmp\.us\.serial[[:space:]]*=/ {gsub(/[[:space:]]/,"",$2); print $2}' local.properties | tail -1)"
fi
if [[ -z "$SERIAL" ]]; then
  echo "ERROR: no serial supplied. Pass it as an argument or set PHMP_US_SERIAL."
  exit 2
fi

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found on PATH."; exit 1; }
adb start-server >/dev/null 2>&1 || true

if ! adb devices | awk 'NR>1 && $2=="device" {print $1}' | grep -Fxq "$SERIAL"; then
  echo "ERROR: $SERIAL is not in 'device' state."
  adb devices -l
  exit 1
fi

OUT_DIR="reports/qualification"
mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
CAPTURE="$OUT_DIR/logcat-$SERIAL-$STAMP.txt"
REPORT="$OUT_DIR/qualification-$SERIAL-$STAMP.txt"

dsh() { adb -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }

echo "==> PHMP device qualification"
echo "    serial=$SERIAL package=$PKG"
echo "    capture=$CAPTURE"

# Grow the buffer so a later run does not lose boot evidence to rotation.
dsh "logcat -G 16M" >/dev/null 2>&1 || true

# Reconnect markers only exist once a disconnect has actually happened, so they are only demanded
# when we deliberately caused one.
RECONNECT_SEVERITY="soft"
if $EXERCISE_DISCONNECT; then
  echo "==> Exercising force-disconnect broadcast (socket will drop and should recover)"
  dsh "am broadcast -a co.poynt.pcm.ACTION_FORCE_DISCONNECT" >/dev/null 2>&1 || true
  sleep 45
  RECONNECT_SEVERITY="critical"
fi

echo "==> Capturing logcat"
adb -s "$SERIAL" logcat -d -v threadtime > "$CAPTURE" 2>/dev/null
CAPTURE_LINES="$(wc -l < "$CAPTURE" | tr -d ' ')"

MISSING_CRITICAL=0
MISSING_SOFT=0

# marker_check <gate> <severity: critical|soft> <label> <regex>
# Counters are updated in the current shell, so this must not run inside a pipeline.
marker_check() {
  local gate="$1" severity="$2" label="$3" regex="$4"
  local count status
  count="$(grep -acEi -- "$regex" "$CAPTURE" 2>/dev/null || true)"
  count="${count:-0}"
  if [[ "$count" -gt 0 ]]; then
    status="OK  "
  elif [[ "$severity" == "critical" ]]; then
    status="MISS"
    MISSING_CRITICAL=$((MISSING_CRITICAL + 1))
  else
    status="WARN"
    MISSING_SOFT=$((MISSING_SOFT + 1))
  fi
  printf '  [ %s ] %-15s %-38s hits=%s\n' "$status" "$gate" "$label" "$count" | tee -a "$REPORT"
}

{
  echo "PHMP device qualification report"
  echo "serial=$SERIAL"
  echo "package=$PKG"
  echo "captured=$STAMP lines=$CAPTURE_LINES"
  echo
  echo "== Device facts (copy the endpoint/discovery values verbatim into application.yaml) =="
  printf '%-38s %s\n' "persist.poynt.srvc.url.pcm" "$(dsh 'getprop persist.poynt.srvc.url.pcm')"
  printf '%-38s %s\n' "persist.poynt.pcm.endpoint" "$(dsh 'getprop persist.poynt.pcm.endpoint')"
  printf '%-38s %s\n' "persist.poynt.pcm.discovery" "$(dsh 'getprop persist.poynt.pcm.discovery')"
  printf '%-38s %s\n' "sys.boot_completed" "$(dsh 'getprop sys.boot_completed')"
  printf '%-38s %s\n' "ro.build.fingerprint" "$(dsh 'getprop ro.build.fingerprint')"
  printf '%-38s %s\n' "ro.build.flavor" "$(dsh 'getprop ro.build.flavor')"
  printf '%-38s %s\n' "persist.sys.ota.status" "$(dsh 'getprop persist.sys.ota.status')"
  printf '%-38s %s\n' "ro.product.cpu.abilist" "$(dsh 'getprop ro.product.cpu.abilist')"
  printf '%-38s %s\n' "device clock (MM-DD HH:MM:SS)" "$(dsh 'date "+%m-%d %H:%M:%S"')"
  printf '%-38s %s\n' "timezone" "$(dsh 'getprop persist.sys.timezone')"
  echo
  echo "== Package state =="
  printf '%-38s %s\n' "pm path" "$(dsh "pm path $PKG")"
  dsh "dumpsys package $PKG | grep -iE 'versionName|versionCode|sharedUser|privApp|pkgFlags'" | sed 's/^[[:space:]]*/  /'
  echo
  echo "== Marker coverage per gate =="
} | tee "$REPORT"

marker_check "Startup"      critical "Start proc for cloudmessaging"      "start proc.*co\.poynt\.cloudmessaging"
marker_check "Startup"      critical "PcmService onCreate"                "pcmservice: oncreate\(\)"
marker_check "Startup"      critical "WebSocket connect task"             "start web socket connect task"
marker_check "Startup"      critical "WebSocket connect attempt"          "attempting to connect websocket:"
marker_check "Startup"      soft     "Startup marker (boot/provider/net)" "pcm-startup|boot completed|network available|starting pcm service"
marker_check "Auth"         critical "Access token retrieved"             "successfully retrieved access token!"
marker_check "Auth"         soft     "Token failure markers"              "failed to obtain token!|maximum retries reached"
marker_check "WebSocket"    critical "WebSocket connected:"               "websocket connected:"
marker_check "SocketHealth" soft     "WebSocket disconnected:"            "websocket disconnected:"
marker_check "Reconnect"    "$RECONNECT_SEVERITY" "Schedule reconnect attempt in:" "schedule reconnect attempt in:"
marker_check "Reconnect"    "$RECONNECT_SEVERITY" "Reconnect intent fired"         "poynt\.intent\.action\.websocket_reconnect"
marker_check "Ping/Pong"    critical "Ping / Pong cadence events"         "ping:|pong:|action_websocket_ping"
marker_check "Environment"  critical "Resolved wss host"                  "wss://"
marker_check "Environment"  soft     "sysprop PCM url log"                "persist\.poynt\.srvc\.url\.pcm"
marker_check "Cloud"        soft     "Raw socket (WEBSOCKET got:)"        "websocket got:"
marker_check "Cloud"        soft     "Cloud Message received"             "cloud message received"
marker_check "Cloud"        soft     "POYNT_CLOUD_MESSAGE_RECEIVED"       "poynt_cloud_message_received"

{
  echo
  echo "== Verdict =="
  echo "critical markers missing: $MISSING_CRITICAL"
  echo "soft markers missing:     $MISSING_SOFT"
  echo
  if [[ "$CAPTURE_LINES" -lt 200 ]]; then
    echo "WARNING: only $CAPTURE_LINES log lines captured. Reboot the device, let PCM settle for ~10"
    echo "         minutes, then re-run so the gates have real evidence to score."
  fi
  if [[ "$MISSING_CRITICAL" -gt 0 ]]; then
    echo "NOT READY: gates above marked MISS will fail for reasons unrelated to PCM health."
    echo "           Either the PCM build logs different strings, or the scenario has not happened yet"
    echo "           on this device (reboot + settle covers most startup/auth/websocket markers)."
  else
    echo "READY: every gate-critical marker is present. Proceed with ./scripts/run-device.sh US"
  fi
  echo
  echo "Cloud markers are expected to be absent until the suite injects a message; they are soft here."
} | tee -a "$REPORT"

echo
echo "Report:  $ROOT_DIR/$REPORT"
echo "Capture: $ROOT_DIR/$CAPTURE"

[[ "$MISSING_CRITICAL" -eq 0 ]]
