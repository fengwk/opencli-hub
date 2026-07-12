#!/usr/bin/env bash
#
# install-extension.sh
#
# 在 Dockerfile 构建阶段下载并安装固定版本 OpenCLI Browser Bridge extension。
#
# 用法：
#   install-extension.sh <extension-url> <sha256> <version> <dest-dir>
#
# 行为：
#   1. 从 <extension-url> 下载 ZIP 到临时目录；
#   2. 校验 sha256；不通过则立即失败；
#   3. 解压到 <dest-dir>；
#   4. 校验 manifest.json 中的 version 与期望一致。
#
# 设计动机：把"从哪里下载、是否校验、放到哪里"集中在一个脚本里，
# 让 Dockerfile 主体保持简洁，也方便日后升级版本。

set -euo pipefail

if [[ "$#" -ne 4 ]]; then
    echo "usage: $(basename "$0") <extension-url> <sha256> <version> <dest-dir>" >&2
    exit 2
fi

EXT_URL="$1"
EXT_SHA256="$2"
EXT_VERSION="$3"
DEST_DIR="$4"

# 临时目录随脚本退出清理；解压失败时也保持原有 DEST_DIR 不变（操作前先落盘到临时目录）。
WORK_DIR="$(mktemp -d -t opencli-extension-XXXXXX)"
trap 'rm -rf "${WORK_DIR}"' EXIT

mkdir -p "${DEST_DIR}"

ZIP_PATH="${WORK_DIR}/extension.zip"
EXTRACT_DIR="${WORK_DIR}/unpack"

echo "[install-extension] downloading ${EXT_URL}"
curl -fsSL -o "${ZIP_PATH}" "${EXT_URL}"

echo "[install-extension] verifying sha256"
ACTUAL_SHA256="$(sha256sum "${ZIP_PATH}" | awk '{print $1}')"
if [[ "${ACTUAL_SHA256}" != "${EXT_SHA256}" ]]; then
    echo "[install-extension] sha256 mismatch: expected=${EXT_SHA256} actual=${ACTUAL_SHA256}" >&2
    exit 1
fi

echo "[install-extension] extracting to ${EXTRACT_DIR}"
mkdir -p "${EXTRACT_DIR}"
unzip -q "${ZIP_PATH}" -d "${EXTRACT_DIR}"

echo "[install-extension] verifying manifest.json"
MANIFEST="${EXTRACT_DIR}/manifest.json"
if [[ ! -f "${MANIFEST}" ]]; then
    echo "[install-extension] missing manifest.json in extension archive" >&2
    exit 1
fi

ACTUAL_VERSION="$(jq -r '.version' "${MANIFEST}")"
if [[ "${ACTUAL_VERSION}" != "${EXT_VERSION}" ]]; then
    echo "[install-extension] version mismatch: expected=${EXT_VERSION} actual=${ACTUAL_VERSION}" >&2
    exit 1
fi

# 关键字段必须存在（避免误下载了一个 manifest 不完整的旧版本）。
jq -e '.manifest_version and .name and .version' "${MANIFEST}" >/dev/null

# 以 rsync 形式合并到 DEST_DIR，避免 unzip -o 在目录非空时静默吞掉既有文件。
# 这里 DEST_DIR 由调用方在执行本脚本前清空/确保干净，因此允许覆盖写入。
rsync -a --delete "${EXTRACT_DIR}/" "${DEST_DIR}/"

echo "[install-extension] installed extension v${ACTUAL_VERSION} to ${DEST_DIR}"
