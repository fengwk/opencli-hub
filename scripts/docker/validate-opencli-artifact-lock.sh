#!/usr/bin/env bash
#
# validate-opencli-artifact-lock.sh
#
# Validate the tracked OpenCLI artifact lock consumed by Dockerfile / CI.
#
# usage:
#   validate-opencli-artifact-lock.sh [lock-file]
#
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly LOCK_FILE="${1:-${SCRIPT_DIR}/opencli-artifact.lock.env}"
readonly SHA256_RE='^[0-9a-f]{64}$'
readonly NONEMPTY_RE='^.+$'

fail() {
    printf '[validate-opencli-artifact-lock] %s\n' "$*" >&2
    exit 1
}

require_match() {
    local name="$1"
    local value="$2"
    local pattern="$3"
    if [[ ! "${value}" =~ ${pattern} ]]; then
        fail "invalid ${name}: ${value}"
    fi
}

[[ -f "${LOCK_FILE}" && -r "${LOCK_FILE}" ]] || fail "lock file missing or unreadable: ${LOCK_FILE}"

# shellcheck disable=SC1090
source "${LOCK_FILE}"

: "${OPENCLI_PACKAGE:?OPENCLI_PACKAGE is required}"
: "${OPENCLI_VERSION:?OPENCLI_VERSION is required}"
: "${OPENCLI_SOURCE_REVISION:?OPENCLI_SOURCE_REVISION is required}"
: "${EXTENSION_VERSION:?EXTENSION_VERSION is required}"
: "${OPENCLI_EXTENSION_URL:?OPENCLI_EXTENSION_URL is required}"
: "${OPENCLI_EXTENSION_SHA256:?OPENCLI_EXTENSION_SHA256 is required}"

# Artifact URLs must be https:// or file://; plain http:// is rejected.
readonly ARTIFACT_URL_RE='^(https|file)://'

require_match OPENCLI_PACKAGE "${OPENCLI_PACKAGE}" "${NONEMPTY_RE}"
require_match OPENCLI_VERSION "${OPENCLI_VERSION}" "${NONEMPTY_RE}"
require_match OPENCLI_SOURCE_REVISION "${OPENCLI_SOURCE_REVISION}" "${NONEMPTY_RE}"
require_match EXTENSION_VERSION "${EXTENSION_VERSION}" "${NONEMPTY_RE}"
require_match OPENCLI_EXTENSION_URL "${OPENCLI_EXTENSION_URL}" "${ARTIFACT_URL_RE}"
require_match OPENCLI_EXTENSION_SHA256 "${OPENCLI_EXTENSION_SHA256}" "${SHA256_RE}"

# CLI URL is optional (registry mode). When present, SHA256 is mandatory.
if [[ -n "${OPENCLI_CLI_URL:-}" ]]; then
    require_match OPENCLI_CLI_URL "${OPENCLI_CLI_URL}" "${ARTIFACT_URL_RE}"
    : "${OPENCLI_CLI_SHA256:?OPENCLI_CLI_SHA256 is required when OPENCLI_CLI_URL is set}"
    require_match OPENCLI_CLI_SHA256 "${OPENCLI_CLI_SHA256}" "${SHA256_RE}"
elif [[ -n "${OPENCLI_CLI_SHA256:-}" ]]; then
    fail "OPENCLI_CLI_SHA256 is set without OPENCLI_CLI_URL"
fi

printf '[validate-opencli-artifact-lock] ok package=%s version=%s extension=%s\n' \
    "${OPENCLI_PACKAGE}" "${OPENCLI_VERSION}" "${EXTENSION_VERSION}"
