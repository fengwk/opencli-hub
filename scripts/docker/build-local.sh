#!/usr/bin/env bash
# Fast local image build: reuse host npm and Maven caches, then inject only the
# packaged JAR into the same final Docker stage used by the reproducible build.
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly IMAGE_TAG="${1:-opencli-hub:local}"
readonly JAVA_HOME_17_VALUE="${JAVA_HOME_17:?JAVA_HOME_17 is required}"
readonly configured_signing_key="${OPENCLI_HUB_EXTENSION_SIGNING_KEY_FILE:-.secrets/opencli-extension-signing-key.pem}"
if [[ "${configured_signing_key}" == /* ]]; then
    readonly signing_key_file="${configured_signing_key}"
else
    readonly signing_key_file="${PROJECT_DIR}/${configured_signing_key}"
fi
artifact_dir=""

cleanup() {
    [[ -n "${artifact_dir}" ]] && rm -rf "${artifact_dir}"
}
trap cleanup EXIT

[[ -r "${signing_key_file}" && -s "${signing_key_file}" ]] || {
    printf '[build-local] signing key must be readable and nonempty: %s\n' "${signing_key_file}" >&2
    exit 1
}

printf '[build-local] building frontend with host npm cache\n'
(
    cd "${PROJECT_DIR}/frontend"
    npm ci --prefer-offline --fetch-retries=10 --fetch-retry-factor=2 \
        --fetch-retry-mintimeout=2000 --fetch-retry-maxtimeout=120000
    npm run build
)

printf '[build-local] packaging Hub with host Maven repository %s\n' "${HOME}/.m2/repository"
env JAVA_HOME="${JAVA_HOME_17_VALUE}" \
    mvn --batch-mode --no-transfer-progress -DskipTests \
        -Dmaven.wagon.http.connectionTimeout=5000 \
        -Dmaven.wagon.http.readTimeout=60000 \
        -Dmaven.wagon.http.retryHandler.count=5 \
        clean package -f "${PROJECT_DIR}/pom.xml"

readonly jar_path="${PROJECT_DIR}/web/target/opencli-hub-web-1.0.0.jar"
[[ -s "${jar_path}" ]] || {
    printf '[build-local] packaged JAR missing: %s\n' "${jar_path}" >&2
    exit 1
}

artifact_dir="$(mktemp -d "${TMPDIR:-/tmp}/opencli-hub-artifact.XXXXXX")"
mkdir -p "${artifact_dir}/artifact"
cp "${jar_path}" "${artifact_dir}/artifact/opencli-hub.jar"

printf '[build-local] assembling %s from the host-built JAR\n' "${IMAGE_TAG}"
docker build \
    --platform linux/amd64 \
    --secret "id=opencli_extension_signing_key,src=${signing_key_file}" \
    --build-context "prebuilt-artifact=${artifact_dir}" \
    --build-arg HUB_ARTIFACT_SOURCE=prebuilt-artifact \
    --tag "${IMAGE_TAG}" \
    "${PROJECT_DIR}"
