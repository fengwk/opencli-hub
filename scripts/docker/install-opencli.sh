#!/usr/bin/env bash
#
# install-opencli.sh
#
# 在 Dockerfile 构建阶段以固定版本全局安装 OpenCLI。
#
# 用法：
#   install-opencli.sh <package-name> <version>
#
# 例如：
#   install-opencli.sh "@jackwener/opencli" "1.8.6"
#
# 关闭 OpenCLI 的 postinstall 行为（fetch-adapters），避免运行时联网拉取 adapter。
# npm 的 `preuninstall` 触发 fetch('http://127.0.0.1:19825/shutdown') 在构建环境不响应，
# 由 `--ignore-scripts` 屏蔽；这是 npm 自身提供的官方逃生口。

set -euo pipefail

if [[ "$#" -ne 2 ]]; then
    echo "usage: $(basename "$0") <package-name> <version>" >&2
    exit 2
fi

PKG="$1"
VERSION="$2"

echo "[install-opencli] installing ${PKG}@${VERSION} (ignore-scripts)"
npm install -g --no-fund --no-audit --ignore-scripts --prefer-offline \
    --fetch-retries=10 --fetch-retry-factor=2 \
    --fetch-retry-mintimeout=2000 --fetch-retry-maxtimeout=120000 \
    "${PKG}@${VERSION}"

# 安装完成后单独执行 OpenCLI 的 build 步骤（prepare 脚本会做），让 dist/ 目录就绪。
# prepare 脚本只检查 src 是否存在，存在则 build；不存在则什么也不做；镜像里没有源码则跳过。
# 这一步放在 install 之后通过 npm rebuild 触发，避免双跑 prepare/postinstall。
echo "[install-opencli] running npm rebuild for prepared dist"
npm rebuild -g "${PKG}" --no-fund --no-audit || true

CLI_PATH="$(command -v opencli || true)"
if [[ -z "${CLI_PATH}" ]]; then
    echo "[install-opencli] opencli not found in PATH after install" >&2
    exit 1
fi

REPORTED_VERSION="$("${CLI_PATH}" --version 2>/dev/null || true)"
echo "[install-opencli] installed opencli: ${REPORTED_VERSION} at ${CLI_PATH}"
