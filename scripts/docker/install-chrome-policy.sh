#!/usr/bin/env bash
#
# install-chrome-policy.sh
#
# 运行时（容器内）：把 OpenCLI Browser Bridge extension 通过
# ExtensionInstallForcelist/ExtensionSettings 强制安装到 google-chrome-stable。
#
# Chrome enterprise policy 路径：
#   /etc/opt/chrome/policies/managed/*.json
#
# 由 Chrome 在每次启动时读取；managed policy 必须以 root 写入（openclihub 用户
# 无法写到 /etc/opt/chrome/policies/managed/，因此该脚本必须以 root 运行，
# 通常由容器构建阶段已经写好；这里提供一个运行时入口供 PoC 调试）。
#
# 用法（root）：
#   install-chrome-policy.sh <extension-id> <update-base-url> [policy-out-dir]
#
# 例：
#   install-chrome-policy.sh 5f16320f80bf2c69c263eafaf213634c http://127.0.0.1:18181
#
# 输出 policy 文件为 JSON，Chrome 读取后会：
#   1. 解析 update_url；
#   2. GET <update-url>/updates.xml；
#   3. 按 appid 匹配 extension；
#   4. 从 codebase 下载 .crx；
#   5. 校验 CRX3 header 内 RSA 公钥哈希是否等于该 extension ID；
#   6. 验证通过则静默安装。
#
# 验证是否成功需看容器日志里的 "ExtensionSettingsManager::OnUpdateExtension" 类日志，
# 或用 chrome://extensions 页面、poc-status.sh 的 /status 接口观察。

set -euo pipefail

if [[ "$#" -lt 2 ]]; then
    echo "usage: $(basename "$0") <extension-id> <update-base-url> [policy-out-dir]" >&2
    exit 2
fi

EXT_ID="$1"
UPDATE_BASE_URL="$2"
POLICY_DIR="${3:-/etc/opt/chrome/policies/managed}"

if [[ ! "${EXT_ID}" =~ ^[a-p]{32}$ ]]; then
    echo "extension id must be 32 chars from [a-p]: got '${EXT_ID}'" >&2
    exit 2
fi

mkdir -p "${POLICY_DIR}"
POLICY_FILE="${POLICY_DIR}/opencli-hub-extension.json"

# ExtensionSettings 是更新策略；ExtensionInstallForcelist 是初次安装 + 强制；二者并用
# 可以保证：当前未安装 → 立刻拉取安装；之后该 extension 保持 force-installed，
# 不允许用户禁用/卸载。update_url 保留供 ExtensionSettings 决定后续更新路径。
cat > "${POLICY_FILE}.tmp" <<EOF
{
  "ExtensionInstallForcelist": [
    "${EXT_ID};${UPDATE_BASE_URL}/updates.xml"
  ],
  "ExtensionSettings": {
    "${EXT_ID}": {
      "installation_mode": "force_installed",
      "update_url": "${UPDATE_BASE_URL}/updates.xml",
      "override_update_url": true
    }
  }
}
EOF

chmod 0644 "${POLICY_FILE}.tmp"
mv "${POLICY_FILE}.tmp" "${POLICY_FILE}"

echo "[install-chrome-policy] wrote ${POLICY_FILE}"
echo "[install-chrome-policy] extension_id=${EXT_ID}"
echo "[install-chrome-policy] update_url=${UPDATE_BASE_URL}/updates.xml"
