#!/usr/bin/env bash
# Fails on any internal API usage the Plugin Verifier reported, except the ones the Marketplace tolerates.
#
# The Marketplace refuses an upload over any internal API usage, whatever the "Compatible" verdict
# says: 1.18.0 was refused over FileSizeLimit while verifyPlugin was green. failureLevel cannot catch
# it without failing every run on the six ToolWindowFactory usages Kotlin emits on 2025.2, which the
# Marketplace tolerates (see failureLevel in build.gradle.kts). So the reports are read here: those
# six pass, any other internal API usage fails.
#
# Run after verifyPlugin, by the Plugin Compatibility workflow and by the release workflow before
# the upload:
#
#   scripts/check-internal-api.sh [reports-dir]    (default: build/reports/pluginVerifier)
#
# Exit codes: 0 no refused usage, 1 at least one, 2 no verifier report to read.
set -euo pipefail

reports=${1:-build/reports/pluginVerifier}

# getIcon(), getAnchor() and manage(): default bodies of ToolWindowFactory that Kotlin implements in
# I18nToolWindowFactory, each one both "overridden in class …" and "invoked in ….method()".
allowed='^Internal method com\.intellij\.openapi\.wm\.ToolWindowFactory\.(getIcon|getAnchor|manage)\(.* in (class )?com\.ibrahimdans\.i18n\.plugin\.ide\.toolwindow\.I18nToolWindowFactory[.(]'

error() {
  local title=$1 message=$2
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::error title=${title}::${message}"
  else
    echo "error: ${title}: ${message}" >&2
  fi
}

# A report is written per IDE only when it has usages: no file is the normal case. No verdict at all
# means verifyPlugin did not run, which must not pass for "no internal API usage".
if [ ! -d "$reports" ] || [ -z "$(find "$reports" -name verification-verdict.txt -print -quit)" ]; then
  error "No verifier report" "no verification-verdict.txt under ${reports}: run verifyPlugin first"
  exit 2
fi

status=0
while IFS= read -r -d '' report; do
  ide=${report#"$reports"/}
  ide=${ide%%/*}
  while IFS= read -r usage; do
    error "Internal API usage on ${ide}" "${usage%% This * is marked with*}"
    status=1
  done < <(grep -vE "$allowed" "$report" | grep -v '^$' || true)
done < <(find "$reports" -name internal-api-usages.txt -print0)

if [ "$status" = 0 ]; then
  echo "No internal API usage the Marketplace refuses."
fi
exit $status
