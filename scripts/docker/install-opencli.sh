#!/usr/bin/env bash
#
# install-opencli.sh
#
# Install a fixed OpenCLI version into the active npm prefix during image build
# or isolated local tests.
#
# usage:
#   # Registry mode (package + version from the npm registry)
#   install-opencli.sh <package-name> <version>
#
#   # Tarball mode (requires a matching SHA256)
#   install-opencli.sh <package-name> <version> <tarball-url> <sha256>
#
# examples:
#   install-opencli.sh "@jackwener/opencli" "1.8.6"
#   install-opencli.sh "@jackwener/opencli" "1.8.6" \
#     "https://registry.npmjs.org/@jackwener/opencli/-/opencli-1.8.6.tgz" \
#     "d271cf3ebab40dfd85c77d328592c8c34d6df20ff2aee9b641f984740d3c6670"
#
# Lifecycle scripts are always disabled. Packaged OpenCLI already ships dist/,
# so npm rebuild / prepare / postinstall must not run in the Hub image.
#

set -euo pipefail

usage() {
    echo "usage: $(basename "$0") <package-name> <version> [tarball-url sha256]" >&2
    exit 2
}

if [[ "$#" -ne 2 && "$#" -ne 4 ]]; then
    usage
fi

PKG="$1"
VERSION="$2"
CLI_URL="${3:-}"
CLI_SHA256="${4:-}"
SHA256_RE='^[0-9a-f]{64}$'

WORK_DIR=""
cleanup() {
    if [[ -n "${WORK_DIR}" ]]; then
        rm -rf "${WORK_DIR}"
    fi
}
trap cleanup EXIT

if [[ -z "${PKG}" || -z "${VERSION}" ]]; then
    echo "[install-opencli] package and version are required" >&2
    exit 2
fi

INSTALL_SPEC=""
if [[ -n "${CLI_URL}" ]]; then
    if [[ -z "${CLI_SHA256}" ]]; then
        echo "[install-opencli] tarball URL mode requires a SHA256 checksum" >&2
        exit 2
    fi
    if [[ ! "${CLI_SHA256}" =~ ${SHA256_RE} ]]; then
        echo "[install-opencli] invalid SHA256 (expect 64 lowercase hex): ${CLI_SHA256}" >&2
        exit 2
    fi

    WORK_DIR="$(mktemp -d -t opencli-cli-XXXXXX)"
    TGZ_PATH="${WORK_DIR}/opencli.tgz"

    echo "[install-opencli] downloading ${CLI_URL}"
    curl --retry 5 --retry-all-errors --connect-timeout 15 -fsSL \
        -o "${TGZ_PATH}" "${CLI_URL}"

    echo "[install-opencli] verifying sha256"
    ACTUAL_SHA256="$(sha256sum "${TGZ_PATH}" | awk '{print $1}')"
    if [[ "${ACTUAL_SHA256}" != "${CLI_SHA256}" ]]; then
        echo "[install-opencli] sha256 mismatch: expected=${CLI_SHA256} actual=${ACTUAL_SHA256}" >&2
        exit 1
    fi

    INSTALL_SPEC="${TGZ_PATH}"
    echo "[install-opencli] installing ${PKG}@${VERSION} from verified tarball (ignore-scripts)"
else
    INSTALL_SPEC="${PKG}@${VERSION}"
    echo "[install-opencli] installing ${INSTALL_SPEC} from registry (ignore-scripts)"
fi

npm install -g --no-fund --no-audit --ignore-scripts --prefer-offline \
    --fetch-retries=10 --fetch-retry-factor=2 \
    --fetch-retry-mintimeout=2000 --fetch-retry-maxtimeout=120000 \
    "${INSTALL_SPEC}"

CLI_PATH="$(command -v opencli || true)"
if [[ -z "${CLI_PATH}" ]]; then
    echo "[install-opencli] opencli not found in PATH after install" >&2
    exit 1
fi

REPORTED_VERSION="$("${CLI_PATH}" --version 2>/dev/null | head -n1 | tr -d '[:space:]' || true)"
if [[ -z "${REPORTED_VERSION}" ]]; then
    echo "[install-opencli] opencli --version produced no output" >&2
    exit 1
fi
if [[ "${REPORTED_VERSION}" != "${VERSION}" ]]; then
    echo "[install-opencli] version mismatch: expected=${VERSION} actual=${REPORTED_VERSION}" >&2
    exit 1
fi

echo "[install-opencli] installed opencli: ${REPORTED_VERSION} at ${CLI_PATH}"
