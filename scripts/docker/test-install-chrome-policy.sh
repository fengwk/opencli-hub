#!/usr/bin/env bash
#
# test-install-chrome-policy.sh
#
# Self-contained check for install-chrome-policy.sh using a temporary policy
# directory. Verifies valid JSON, the managed URL policy, and that the Bridge
# force-install policy remains intact.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly POLICY_SCRIPT="${SCRIPT_DIR}/install-chrome-policy.sh"
readonly EXTENSION_ID='abcdefghijklmnopabcdefghijklmnop'
readonly UPDATE_BASE_URL='http://127.0.0.1:18181'
readonly EXPECTED_ALLOWLIST='[
  "http://*",
  "https://*",
  "about:*",
  "data:*",
  "blob:*",
  "filesystem:*",
  "ws://*",
  "wss://*"
]'

fail() {
    printf '[test-install-chrome-policy] %s\n' "$*" >&2
    exit 1
}

[[ -f "${POLICY_SCRIPT}" ]] || fail "missing policy script: ${POLICY_SCRIPT}"
command -v jq >/dev/null 2>&1 || fail "jq is required"
bash -n "${POLICY_SCRIPT}"

WORK_ROOT="$(mktemp -d -t opencli-chrome-policy-test-XXXXXX)"
cleanup() {
    rm -rf "${WORK_ROOT}"
}
trap cleanup EXIT

POLICY_DIR="${WORK_ROOT}/policies"
POLICY_FILE="${POLICY_DIR}/opencli-hub-extension.json"
bash "${POLICY_SCRIPT}" "${EXTENSION_ID}" "${UPDATE_BASE_URL}" "${POLICY_DIR}"

[[ -f "${POLICY_FILE}" ]] || fail "policy file missing: ${POLICY_FILE}"
jq -e . "${POLICY_FILE}" >/dev/null || fail "generated policy is not valid JSON"

# Default-deny navigation protocols; only browser automation protocols are
# allowed. The wildcard does not cover internal chrome:// / chrome-extension://
# pages, so Bridge extension pages remain usable.
jq -e '.URLBlocklist == ["*"]' "${POLICY_FILE}" >/dev/null \
    || fail 'URLBlocklist must be exactly ["*"]'
jq -e --argjson expected "${EXPECTED_ALLOWLIST}" \
    '(.URLAllowlist | sort) == ($expected | sort)' "${POLICY_FILE}" >/dev/null \
    || fail "URLAllowlist does not match the expected browser protocols"

# Bridge force-install policy must remain exactly the fixed loopback update.
jq -e --arg id "${EXTENSION_ID}" --arg url "${UPDATE_BASE_URL}/updates.xml" '
    .ExtensionInstallForcelist == [$id + ";" + $url]
    and .ExtensionSettings[$id].installation_mode == "force_installed"
    and .ExtensionSettings[$id].update_url == $url
    and .ExtensionSettings[$id].override_update_url == true
' "${POLICY_FILE}" >/dev/null || fail "Bridge force-install policy changed"

echo "[test-install-chrome-policy] all checks passed"
