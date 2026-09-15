#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

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

MODE="${1:-simulate}"
REGION="${2:-both}"

# Avoid Allure DNS lookup noise in restricted environments
export ALLURE_HOST_NAME="${ALLURE_HOST_NAME:-phmp-local}"
export ALLURE_THREAD_NAME="${ALLURE_THREAD_NAME:-phmp-runner}"
export BUILD_ID="${BUILD_ID:-local-poc-$(date +%Y%m%d-%H%M%S)}"

# Optional pass-through overrides (set by run-device.sh or exported by hand).
EXTRA_ARGS=()
if [[ -n "${PHMP_ENV:-}" ]]; then
  EXTRA_ARGS+=("-Dphmp.env=${PHMP_ENV}")
fi
if [[ -n "${PHMP_CLOUD_INJECT_ENABLED:-}" ]]; then
  EXTRA_ARGS+=("-Dphmp.cloud.injectEnabled=${PHMP_CLOUD_INJECT_ENABLED}")
fi
# Safe expansion of a possibly-empty array under `set -u` on bash 3.2 (macOS default).
EXPAND=(${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"})

echo "==> PHMP local run"
echo "    JAVA_HOME=${JAVA_HOME:-<unset>}"
echo "    mode=${MODE}"
echo "    region=${REGION}"
echo "    env=${PHMP_ENV:-<from application.yaml>}"
echo "    BUILD_ID=${BUILD_ID}"

case "$REGION" in
  both)
    mvn -B clean test -P"${MODE}" -Dphmp.mode="${MODE}" -Dphmp.region=both "${EXPAND[@]+${EXPAND[@]}}"
    ;;
  US|us)
    mvn -B clean test -P"${MODE},us" -Dphmp.mode="${MODE}" -Dphmp.region=US -Dgroups=us "${EXPAND[@]+${EXPAND[@]}}"
    ;;
  EU|eu)
    mvn -B clean test -P"${MODE},eu" -Dphmp.mode="${MODE}" -Dphmp.region=EU -Dgroups=eu "${EXPAND[@]+${EXPAND[@]}}"
    ;;
  *)
    echo "Unsupported region: $REGION (use both|US|EU)"
    exit 1
    ;;
esac

echo ""
echo "==> Reports"
echo "Combined gate: $ROOT_DIR/reports/phmp-release-gate.html"
echo "US report:     $ROOT_DIR/reports/phmp-execution-report-us.html"
echo "EU report:     $ROOT_DIR/reports/phmp-execution-report-eu.html"
echo "History:       $ROOT_DIR/reports/history/summary.jsonl"
echo "Surefire:      $ROOT_DIR/target/surefire-reports"
echo "Allure data:   $ROOT_DIR/target/allure-results"
echo ""
echo "Generate Allure HTML (optional): ./scripts/allure-report.sh"
if [[ -f reports/history/summary.jsonl ]]; then
  echo ""
  echo "==> Latest history"
  tail -n 4 reports/history/summary.jsonl || true
fi
