#!/usr/bin/env bash
# PHMP device-mode runner with preflight ADB / serial / token checks.
# Usage:
#   ./scripts/run-device.sh [both|US|EU]
#   ./scripts/run-device.sh both --skip-token-check
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REGION="${1:-both}"
SKIP_TOKEN_CHECK=false
if [[ "${2:-}" == "--skip-token-check" ]] || [[ "${1:-}" == "--skip-token-check" ]]; then
  SKIP_TOKEN_CHECK=true
fi
if [[ "${1:-}" == "--skip-token-check" ]]; then
  REGION="${2:-both}"
fi

# --- Java (same discovery as run-local.sh) ---
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  elif [[ -d "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home" ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
  elif [[ -d "/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home" ]]; then
    export JAVA_HOME="/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# --- Load local.properties into env if present (does not override existing env) ---
load_local_properties() {
  local file="$ROOT_DIR/local.properties"
  [[ -f "$file" ]] || return 0
  while IFS='=' read -r key value || [[ -n "${key:-}" ]]; do
    [[ -z "${key:-}" || "${key}" =~ ^[[:space:]]*# ]] && continue
    key="$(echo "$key" | xargs)"
    value="$(echo "${value:-}" | xargs)"
    case "$key" in
      phmp.us.serial)
        export PHMP_US_SERIAL="${PHMP_US_SERIAL:-$value}"
        ;;
      phmp.eu.serial)
        export PHMP_EU_SERIAL="${PHMP_EU_SERIAL:-$value}"
        ;;
      phmp.cloud.token)
        export PHMP_CLOUD_TOKEN="${PHMP_CLOUD_TOKEN:-$value}"
        ;;
      phmp.env)
        export PHMP_ENV="${PHMP_ENV:-$value}"
        ;;
      phmp.cloud.injectEnabled)
        export PHMP_CLOUD_INJECT_ENABLED="${PHMP_CLOUD_INJECT_ENABLED:-$value}"
        ;;
    esac
  done < "$file"
}

load_local_properties

need_us=false
need_eu=false
case "$REGION" in
  both) need_us=true; need_eu=true ;;
  US|us) need_us=true; REGION=US ;;
  EU|eu) need_eu=true; REGION=EU ;;
  *)
    echo "ERROR: Unsupported region '$REGION' (use both|US|EU)"
    exit 1
    ;;
esac

echo "==> PHMP device preflight"
echo "    JAVA_HOME=${JAVA_HOME:-<unset>}"
echo "    region=${REGION}"
echo "    env=${PHMP_ENV:-<from application.yaml>}"

# --- adb present ---
if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found on PATH. Install Android platform-tools and retry."
  exit 1
fi

echo "    adb=$(command -v adb)"
adb start-server >/dev/null 2>&1 || true

ADB_DEVICES_RAW="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
echo "    online devices:"
if [[ -z "$ADB_DEVICES_RAW" ]]; then
  echo "      <none>"
  echo "ERROR: No ADB devices in 'device' state. Check USB debugging / authorization."
  adb devices -l || true
  exit 1
fi
while IFS= read -r serial; do
  [[ -n "$serial" ]] && echo "      - $serial"
done <<< "$ADB_DEVICES_RAW"

device_online() {
  local want="$1"
  echo "$ADB_DEVICES_RAW" | grep -Fxq "$want"
}

FAIL=0

if $need_us; then
  if [[ -z "${PHMP_US_SERIAL:-}" ]]; then
    echo "ERROR: PHMP_US_SERIAL is not set (env or local.properties phmp.us.serial)."
    FAIL=1
  elif ! device_online "$PHMP_US_SERIAL"; then
    echo "ERROR: US serial '$PHMP_US_SERIAL' is not online via adb."
    FAIL=1
  else
    echo "    US serial OK: $PHMP_US_SERIAL"
  fi
fi

if $need_eu; then
  if [[ -z "${PHMP_EU_SERIAL:-}" ]]; then
    echo "ERROR: PHMP_EU_SERIAL is not set (env or local.properties phmp.eu.serial)."
    FAIL=1
  elif ! device_online "$PHMP_EU_SERIAL"; then
    echo "ERROR: EU serial '$PHMP_EU_SERIAL' is not online via adb."
    FAIL=1
  else
    echo "    EU serial OK: $PHMP_EU_SERIAL"
  fi
fi

# A disabled cloud inject needs no token; the cloud gate reports SKIPPED instead of calling out.
if [[ "${PHMP_CLOUD_INJECT_ENABLED:-true}" == "false" ]]; then
  SKIP_TOKEN_CHECK=true
  echo "    Cloud inject disabled (phmp.cloud.injectEnabled=false) — cloud gate will report SKIPPED"
fi

if [[ "$SKIP_TOKEN_CHECK" != "true" ]]; then
  if [[ -z "${PHMP_CLOUD_TOKEN:-}" ]]; then
    echo "ERROR: PHMP_CLOUD_TOKEN is not set (env or local.properties phmp.cloud.token)."
    echo "       Use --skip-token-check only if you intentionally test without inject auth."
    FAIL=1
  elif [[ "$PHMP_CLOUD_TOKEN" == "replace-me" || "$PHMP_CLOUD_TOKEN" == "YOUR_LAB_CLOUD_BEARER_TOKEN" ]]; then
    echo "ERROR: PHMP_CLOUD_TOKEN still looks like a placeholder. Replace with a real lab token."
    FAIL=1
  else
    echo "    Cloud token OK: (set, ${#PHMP_CLOUD_TOKEN} chars)"
  fi
else
  echo "    Cloud token check skipped"
fi

# Soft checks: package presence on required devices
soft_package_check() {
  local serial="$1"
  local label="$2"
  local pkg="${PHMP_PCM_PACKAGE:-co.poynt.services.pcm}"
  if adb -s "$serial" shell pm path "$pkg" >/dev/null 2>&1; then
    echo "    $label package OK: $pkg"
  else
    echo "WARN: $label serial $serial does not report package '$pkg' via pm path."
    echo "      Installation Validation will likely fail. Update packageName in application.yaml if needed."
  fi
}

if [[ "$FAIL" -eq 0 ]]; then
  $need_us && soft_package_check "$PHMP_US_SERIAL" "US"
  $need_eu && soft_package_check "$PHMP_EU_SERIAL" "EU"
fi

if [[ "$FAIL" -ne 0 ]]; then
  echo ""
  echo "Preflight FAILED. See docs/LAB_DEVICE_CHECKLIST.md"
  exit 1
fi

export BUILD_ID="${BUILD_ID:-device-$(date +%Y%m%d-%H%M%S)}"
echo "    BUILD_ID=$BUILD_ID"
echo "==> Preflight passed — starting Maven device run"

exec "$ROOT_DIR/scripts/run-local.sh" device "$REGION"
