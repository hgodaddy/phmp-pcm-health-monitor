#!/usr/bin/env bash
#
# Recovers the access token PCM is currently presenting from the terminal's logcat and writes it to
# local.properties for the PHMP run.
#
# PCM logs its WebSocket URL at debug level and the token rides on that URL as a query parameter:
#   Attempting to connect websocket: wss://<host>/streams/<biz>/<store>/<deviceId>?token=<jwt>
#
# The token is a live credential. It is written only to local.properties, which is gitignored, and this
# script prints a fingerprint and the JWT claims rather than the token itself.
#
# Usage: scripts/fetch-device-token.sh [serial] [--env dev|ote|prod] [--print-claims]

set -euo pipefail

SERIAL="${1:-${PHMP_SERIAL:-}}"
if [[ "${SERIAL}" == --* ]]; then
  SERIAL=""
else
  [[ $# -gt 0 ]] && shift || true
fi

TARGET_ENV="${PHMP_ENV:-dev}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) TARGET_ENV="$2"; shift 2 ;;
    --print-claims) PRINT_CLAIMS=1; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${SERIAL}" ]]; then
  SERIAL="$(adb devices | awk '/\tdevice$/ {print $1; exit}')"
fi
if [[ -z "${SERIAL}" ]]; then
  echo "No device found. Connect the terminal and confirm 'adb devices' lists it." >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="${ROOT}/local.properties"

echo "Terminal : ${SERIAL}"
echo "Target   : ${TARGET_ENV}"

# Newest connect line wins: PCM re-logs the URL on every attempt and only the latest token is in force.
LINE="$(adb -s "${SERIAL}" shell 'logcat -d -v threadtime' 2>/dev/null \
  | grep -a 'Attempting to connect websocket:' \
  | tail -1 || true)"

if [[ -z "${LINE}" ]]; then
  echo
  echo "No token-bearing connect attempt in the buffer." >&2
  echo "PCM logs the URL only when it tries to connect. Force an attempt and retry:" >&2
  echo "  adb -s ${SERIAL} shell am startservice -n co.poynt.cloudmessaging/.PcmService" >&2
  exit 1
fi

URL="$(echo "${LINE}" | grep -ao 'wss://[^[:space:]]*')"
TOKEN="${URL#*\?token=}"
if [[ "${TOKEN}" == "${URL}" || -z "${TOKEN}" ]]; then
  echo "Connect URL carried no token parameter; nothing to extract." >&2
  exit 1
fi

FINGERPRINT="sha256:$(printf '%s' "${TOKEN}" | shasum -a 256 | cut -c1-12)"
CLAIMS="$(python3 - "${TOKEN}" <<'PY'
import base64, json, sys
payload = sys.argv[1].split('.')[1]
payload += '=' * (-len(payload) % 4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(payload)), indent=2, sort_keys=True))
PY
)"

get_claim() { echo "${CLAIMS}" | python3 -c "import json,sys;print(json.load(sys.stdin).get('$1',''))"; }

ISSUER="$(get_claim iss)"
AUDIENCE="$(get_claim aud)"
EXP="$(get_claim exp)"
NOW="$(adb -s "${SERIAL}" shell date +%s | tr -d '\r')"

echo
echo "Token    : ${FINGERPRINT}"
echo "Host     : ${URL%%/streams/*}"
echo "Issuer   : ${ISSUER}"
echo "Audience : ${AUDIENCE}"
if [[ -n "${EXP}" ]]; then
  REMAINING=$(( EXP - NOW ))
  if (( REMAINING <= 0 )); then
    echo "Expiry   : EXPIRED $(( -REMAINING / 60 ))m ago -- the terminal must re-authenticate"
  else
    echo "Expiry   : valid for a further $(( REMAINING / 60 ))m"
  fi
fi
[[ -n "${PRINT_CLAIMS:-}" ]] && { echo; echo "${CLAIMS}"; }

# An issuer from another environment parses fine and is unexpired, yet the cloud answers the handshake
# with 401. Flag it here so it is caught before the run rather than in the middle of it.
ISSUER_TAG="$(echo "${ISSUER}" | sed -E 's#https?://([^./]*-)?([a-z]+)\..*#\2#')"
case "${TARGET_ENV}" in
  dev) EXPECTED_TAG=ci ;;
  ote) EXPECTED_TAG=ote ;;
  *)   EXPECTED_TAG="" ;;
esac
if [[ -n "${EXPECTED_TAG}" && -n "${ISSUER_TAG}" && "${ISSUER_TAG}" != "${EXPECTED_TAG}" ]]; then
  echo
  echo "WARNING: token issuer is '${ISSUER_TAG}' but target env '${TARGET_ENV}' expects '${EXPECTED_TAG}'."
  echo "         The handshake will be rejected with 401. Re-activate the terminal in ${TARGET_ENV}."
fi

touch "${PROPS}"
TMP="$(mktemp)"
grep -v '^phmp\.cloud\.token=' "${PROPS}" > "${TMP}" || true
echo "phmp.cloud.token=${TOKEN}" >> "${TMP}"
mv "${TMP}" "${PROPS}"
chmod 600 "${PROPS}"

echo
echo "Wrote phmp.cloud.token to local.properties (gitignored, mode 600)."
echo "Tokens are short lived, so re-run this immediately before a PHMP run."
