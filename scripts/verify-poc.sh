#!/usr/bin/env bash
# One-command Phase 1 POC verification for a local machine (simulate mode).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "=============================================="
echo " PHMP Phase 1 MVP — Local POC Verification"
echo "=============================================="
echo ""

"$ROOT_DIR/scripts/run-local.sh" simulate both

echo ""
echo "==> Gate checks"
fail=0

for f in \
  reports/phmp-execution-report-us.html \
  reports/phmp-execution-report-eu.html \
  reports/phmp-release-gate.html \
  reports/history/summary.jsonl \
  reports/latest/US.json \
  reports/latest/EU.json
do
  if [[ -f "$f" ]]; then
    echo "  OK  $f"
  else
    echo "  MISSING $f"
    fail=1
  fi
done

if grep -q "RELEASE READY" reports/phmp-execution-report-us.html \
  && grep -q "RELEASE READY" reports/phmp-execution-report-eu.html \
  && grep -q "PASS — nightly PCM build is release-ready" reports/phmp-release-gate.html; then
  echo "  OK  Release Ready YES (US + EU + combined)"
else
  echo "  FAIL Release Ready signal not found"
  fail=1
fi

echo ""
if [[ "$fail" -eq 0 ]]; then
  echo "POC VERIFIED — Phase 1 simulate gate is healthy."
  echo "Open: $ROOT_DIR/reports/phmp-release-gate.html"
  if command -v open >/dev/null 2>&1; then
    open "$ROOT_DIR/reports/phmp-release-gate.html" \
         "$ROOT_DIR/reports/phmp-execution-report-us.html" \
         "$ROOT_DIR/reports/phmp-execution-report-eu.html" || true
  fi
  exit 0
fi

echo "POC VERIFICATION FAILED"
exit 1
