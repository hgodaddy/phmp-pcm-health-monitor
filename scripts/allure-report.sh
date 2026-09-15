#!/usr/bin/env bash
# Generates the Allure HTML report and normalizes its entry points.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SITE_DIR="target/site"
REPORT_DIR="$SITE_DIR/allure-maven-plugin"

mvn -B io.qameta.allure:allure-maven:report

if [[ ! -f "$REPORT_DIR/index.html" ]]; then
  echo "Allure report missing: $REPORT_DIR/index.html"
  exit 1
fi

# The Doxia wrapper page is emitted inside the report directory with a redirect
# relative to target/site, so it resolves to allure-maven-plugin/allure-maven-plugin/
# and 404s. Point both locations at the real report instead.
write_redirect() {
  local target="$1"
  local href="$2"
  cat > "$target" <<HTML
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <title>Allure Report – PHMP</title>
    <meta http-equiv="refresh" content="0;url=$href" />
  </head>
  <body>
    <a href="$href">Open Allure report</a>
  </body>
</html>
HTML
}

write_redirect "$SITE_DIR/allure-maven.html" "allure-maven-plugin/index.html"
write_redirect "$REPORT_DIR/allure-maven.html" "index.html"

echo ""
echo "==> Allure report"
echo "Report:     $ROOT_DIR/$REPORT_DIR/index.html"
echo "Entrypoint: $ROOT_DIR/$SITE_DIR/allure-maven.html"

if [[ "${1:-}" == "--open" ]] && command -v open >/dev/null 2>&1; then
  open "$ROOT_DIR/$REPORT_DIR/index.html" || true
fi
