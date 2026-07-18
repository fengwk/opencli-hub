#!/usr/bin/env bash
#
# test-install-opencli.sh
#
# Self-contained smoke test for install-opencli.sh tarball mode.
# Builds a tiny fake @jackwener/opencli package in a temp directory, exercises
# file:// + correct SHA success, and rejects bad SHA / version mismatch without
# touching the real global npm prefix.
#
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly INSTALL_SCRIPT="${SCRIPT_DIR}/install-opencli.sh"
readonly FAKE_VERSION='9.9.9-test'
readonly FAKE_PACKAGE='@jackwener/opencli'

if [[ ! -x "${INSTALL_SCRIPT}" && ! -f "${INSTALL_SCRIPT}" ]]; then
    echo "[test-install-opencli] missing install script: ${INSTALL_SCRIPT}" >&2
    exit 1
fi
bash -n "${INSTALL_SCRIPT}"

WORK_ROOT="$(mktemp -d -t opencli-install-test-XXXXXX)"
cleanup() {
    rm -rf "${WORK_ROOT}"
}
trap cleanup EXIT

PKG_DIR="${WORK_ROOT}/package"
mkdir -p "${PKG_DIR}/dist/src"
cat >"${PKG_DIR}/package.json" <<EOF
{
  "name": "${FAKE_PACKAGE}",
  "version": "${FAKE_VERSION}",
  "bin": {
    "opencli": "dist/src/main.js"
  }
}
EOF
cat >"${PKG_DIR}/dist/src/main.js" <<EOF
#!/usr/bin/env node
if (process.argv[2] === '--version') {
  process.stdout.write('${FAKE_VERSION}\\n');
  process.exit(0);
}
process.stderr.write('unexpected invocation\\n');
process.exit(1);
EOF
chmod +x "${PKG_DIR}/dist/src/main.js"

TARBALL="${WORK_ROOT}/opencli-fake.tgz"
tar -czf "${TARBALL}" -C "${WORK_ROOT}" package
CORRECT_SHA="$(sha256sum "${TARBALL}" | awk '{print $1}')"
BAD_SHA='0000000000000000000000000000000000000000000000000000000000000000'
FILE_URL="file://${TARBALL}"

run_install() {
    local prefix="$1"
    shift
    mkdir -p "${prefix}"
    # Isolate npm global install from the real user/global prefix.
    env \
        npm_config_prefix="${prefix}" \
        NPM_CONFIG_PREFIX="${prefix}" \
        PATH="${prefix}/bin:${PATH}" \
        HOME="${WORK_ROOT}/home" \
        bash "${INSTALL_SCRIPT}" "$@"
}

expect_failure() {
    local label="$1"
    shift
    if "$@"; then
        echo "[test-install-opencli] expected failure (${label}) but command succeeded" >&2
        exit 1
    fi
    echo "[test-install-opencli] rejected as expected: ${label}"
}

# Success: correct SHA installs and reports the exact pinned version.
SUCCESS_PREFIX="${WORK_ROOT}/prefix-ok"
run_install "${SUCCESS_PREFIX}" "${FAKE_PACKAGE}" "${FAKE_VERSION}" "${FILE_URL}" "${CORRECT_SHA}"
SUCCESS_BIN="${SUCCESS_PREFIX}/bin/opencli"
[[ -x "${SUCCESS_BIN}" ]] || {
    echo "[test-install-opencli] opencli binary missing after success install" >&2
    exit 1
}
REPORTED="$("${SUCCESS_BIN}" --version | tr -d '[:space:]')"
[[ "${REPORTED}" == "${FAKE_VERSION}" ]] || {
    echo "[test-install-opencli] unexpected version after success install: ${REPORTED}" >&2
    exit 1
}
echo "[test-install-opencli] success path ok (${REPORTED})"

# Failure: bad SHA must not install.
expect_failure "bad sha256" \
    run_install "${WORK_ROOT}/prefix-bad-sha" \
    "${FAKE_PACKAGE}" "${FAKE_VERSION}" "${FILE_URL}" "${BAD_SHA}"

# Failure: version mismatch after install must fail the script.
expect_failure "version mismatch" \
    run_install "${WORK_ROOT}/prefix-bad-version" \
    "${FAKE_PACKAGE}" "1.0.0" "${FILE_URL}" "${CORRECT_SHA}"

# Failure: URL mode with an explicit empty SHA exercises the checksum-required
# path (four args, empty fourth), not merely argc/usage rejection.
expect_failure "missing sha256" \
    run_install "${WORK_ROOT}/prefix-no-sha" \
    "${FAKE_PACKAGE}" "${FAKE_VERSION}" "${FILE_URL}" ""

echo "[test-install-opencli] all checks passed"
