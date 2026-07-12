#!/usr/bin/env bash
#
# poc-entrypoint.sh
#
# F1 PoC 入口脚本：在已构建镜像内顺序启动
#   opencli daemon -> Xvfb -> openbox -> x11vnc -> google-chrome-stable
# 以验证 OpenCLI daemon、Browser Bridge extension 与 VNC 链路。
#
# 使用：
#   docker run --rm -it --shm-size=2g \
#       -v opencli_hub_data:/var/lib/opencli \
#       opencli-hub:latest poc-entrypoint.sh
#
# 环境变量（全部可选，使用默认值即可走通默认链路）：
#   OPENCLI_DISPLAY        :99        虚拟 display 编号
#   OPENCLI_PROFILE_DIR    /var/lib/opencli/data/profile
#                                临时 Chrome Profile 根目录；本 PoC 内每次启动在
#                                此目录下创建唯一 profile 子目录
#   OPENCLI_PROFILE_NAME   auto      指定固定子目录名（用于"重启保持 contextId"验证）；
#                                auto 表示每次启动生成新 UUID 子目录（用于"新 Profile 唯一"验证）
#   OPENCLI_CHROME_URL     about:blank  Chrome 启动时打开的 URL
#   OPENCLI_CHROME_FLAGS   ""        追加到 Chrome 命令行末尾的额外 flag
#   OPENCLI_DAEMON_PORT    19825     OpenCLI daemon 端口，与 extension 默认一致
#   OPENCLI_VNC_PORT       5900      x11vnc 监听端口
#   OPENCLI_VNC_DISPLAY_HOST 127.0.0.1  x11vnc bind host
#   OPENCLI_SHUTDOWN_TIMEOUT 20     关闭所有子进程的最大等待秒数
#
# 行为契约：
#   - 启动顺序：daemon -> Xvfb -> openbox -> x11vnc -> Chrome
#     （daemon 必须先就绪，否则 Chrome 一启动 extension 立即尝试连接 19825）
#   - 退出顺序：反向，并通过 TERM/INT 触发 trap 后再做一次 KILL
#   - tini 已是 PID 1，本脚本只需要保证自己的子进程被正确清理

set -euo pipefail

# ───────── 常量与默认值 ─────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DISPLAY_NUM="${OPENCLI_DISPLAY:-99}"
DISPLAY_VAR=":${DISPLAY_NUM}"
XVFB_AUTH_FILE="/tmp/.opencli-xvfb-auth.$$"

DAEMON_PORT="${OPENCLI_DAEMON_PORT:-19825}"
DAEMON_HOST="127.0.0.1"
DAEMON_BASE_URL="http://${DAEMON_HOST}:${DAEMON_PORT}"

VNC_PORT="${OPENCLI_VNC_PORT:-5900}"
VNC_DISPLAY_HOST="${OPENCLI_VNC_DISPLAY_HOST:-127.0.0.1}"
VNC_PASSWORD_FILE="/tmp/.opencli-vnc-passwd"

PROFILE_ROOT="${OPENCLI_PROFILE_DIR:-/var/lib/opencli/data/profile}"
PROFILE_NAME="${OPENCLI_PROFILE_NAME:-auto}"
if [[ "${PROFILE_NAME}" == "auto" ]]; then
    PROFILE_NAME="profile-$(cat /proc/sys/kernel/random/uuid)"
fi
CHROME_URL="${OPENCLI_CHROME_URL:-about:blank}"
CHROME_EXTRA_FLAGS="${OPENCLI_CHROME_FLAGS:-}"

EXTENSION_DIR="${OPENCLI_EXTENSION_DIR:-/opt/opencli/extension}"
CRX_DIR="${OPENCLI_CRX_DIR:-/opt/opencli/crx}"
DATA_DIR="${OPENCLI_DATA:-/var/lib/opencli/data}"
LOG_DIR="${OPENCLI_LOG:-/var/log/opencli}"

SHUTDOWN_TIMEOUT="${OPENCLI_SHUTDOWN_TIMEOUT:-20}"

# CRX HTTP server / Chrome managed policy：仅当 openclihub 已能加载 /opt/opencli/crx 时才启用；
# 默认开启。设置 OPENCLI_SKIP_CRX_INSTALL=1 跳过（退回纯 --load-extension 试探，方便对比）。
CRX_HTTP_PORT="${OPENCLI_CRX_HTTP_PORT:-18181}"
CRX_HTTP_BIND="127.0.0.1"
CRX_HTTP_BASE_URL="${OPENCLI_CRX_HTTP_BASE_URL:-http://127.0.0.1:${CRX_HTTP_PORT}}"
SKIP_CRX_INSTALL="${OPENCLI_SKIP_CRX_INSTALL:-0}"
CHROME_POLICY_DIR="${OPENCLI_CHROME_POLICY_DIR:-/etc/opt/chrome/policies/managed}"
CHROME_USER_DATA_RELATIVE=".config/google-chrome"

# ───────── 进程状态 ─────────
DAEMON_PID=""
XVFB_PID=""
OPENBOX_PID=""
X11VNC_PID=""
CRX_HTTP_PID=""
CHROME_PID=""

EXTENSION_ID=""
POLICY_INSTALLED=0
USE_CRX_INSTALL=0

LOG_FILES=()

mkdir -p "${PROFILE_ROOT}" "${LOG_DIR}"

# ───────── 日志辅助 ─────────
log() {
    local level="$1"; shift
    printf '[%s] %s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "${level}" "$*"
}

info() { log "INFO " "$*"; }
warn() { log "WARN " "$*" >&2; }
err()  { log "ERROR" "$*" >&2; }

cleanup_file() {
    local f="$1"
    [[ -f "${f}" ]] && rm -f "${f}" || true
}

# 依据子进程 PID 信号优雅终止；超时后 KILL。
stop_pid() {
    local name="$1" pid="$2" signal="${3:-TERM}"
    if [[ -z "${pid}" ]] || ! kill -0 "${pid}" 2>/dev/null; then
        return 0
    fi
    info "stopping ${name} (pid=${pid}, signal=${signal})"
    kill "-${signal}" "${pid}" 2>/dev/null || true
    local waited=0
    while kill -0 "${pid}" 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
        if (( waited >= SHUTDOWN_TIMEOUT )); then
            warn "${name} did not exit after ${SHUTDOWN_TIMEOUT}s, sending KILL"
            kill -KILL "${pid}" 2>/dev/null || true
            break
        fi
    done
    wait "${pid}" 2>/dev/null || true
}

shutdown_all() {
    info "shutting down PoC stack"
    # 反向关闭：Chrome -> CRX HTTP server -> x11vnc -> openbox -> Xvfb -> daemon
    [[ -n "${CHROME_PID}"   ]] && stop_pid "chrome"          "${CHROME_PID}"   TERM
    [[ -n "${CRX_HTTP_PID}" ]] && stop_pid "crx-http-server" "${CRX_HTTP_PID}" TERM
    [[ -n "${X11VNC_PID}"   ]] && stop_pid "x11vnc"          "${X11VNC_PID}"   TERM
    [[ -n "${OPENBOX_PID}"  ]] && stop_pid "openbox"         "${OPENBOX_PID}"  TERM
    [[ -n "${XVFB_PID}"     ]] && stop_pid "xvfb"            "${XVFB_PID}"     TERM
    # daemon 提供 graceful shutdown：调用 /shutdown
    if [[ -n "${DAEMON_PID}" ]] && kill -0 "${DAEMON_PID}" 2>/dev/null; then
        info "requesting daemon shutdown via ${DAEMON_BASE_URL}/shutdown"
        curl -fsS --max-time 5 -X POST -H 'X-OpenCLI: 1' \
            "${DAEMON_BASE_URL}/shutdown" >/dev/null 2>&1 || true
        stop_pid "daemon" "${DAEMON_PID}" TERM
    fi
    cleanup_file "${XVFB_AUTH_FILE}"
    cleanup_file "${VNC_PASSWORD_FILE}"
}

trap shutdown_all EXIT INT TERM HUP

# ───────── 1. 启动 OpenCLI daemon ─────────
info "starting opencli daemon on port ${DAEMON_PORT}"
DAEMON_LOG="${LOG_DIR}/daemon.log"
LOG_FILES+=("${DAEMON_LOG}")
# `opencli daemon restart` 会拉起 detached daemon，并在 daemon /status 就绪后返回。
# 不能把 CLI 包装进程的 PID 当作 daemon PID；真实 PID 从 /status 响应读取。
: > "${DAEMON_LOG}"
if ! opencli daemon restart >>"${DAEMON_LOG}" 2>&1; then
    err "opencli daemon restart failed; tail of log:"
    tail -n 50 "${DAEMON_LOG}" >&2 || true
    exit 1
fi

STATUS_JSON="$(curl -fsS --max-time 3 -H 'X-OpenCLI: 1' "${DAEMON_BASE_URL}/status")"
DAEMON_PID="$(jq -r '.pid // empty' <<<"${STATUS_JSON}")"
if [[ -z "${DAEMON_PID}" ]] || ! kill -0 "${DAEMON_PID}" 2>/dev/null; then
    err "daemon status did not return a live pid"
    printf '%s\n' "${STATUS_JSON}" >&2
    exit 1
fi
info "daemon is responding at ${DAEMON_BASE_URL}/status (pid=${DAEMON_PID}, log=${DAEMON_LOG})"

# ───────── 2. 启动 Xvfb ─────────
info "starting Xvfb on ${DISPLAY_VAR}"
XVFB_LOG="${LOG_DIR}/xvfb.log"
LOG_FILES+=("${XVFB_LOG}")
: > "${XVFB_LOG}"
Xvfb "${DISPLAY_VAR}" -screen 0 1280x720x24 -ac -nolisten tcp -auth "${XVFB_AUTH_FILE}" \
    >>"${XVFB_LOG}" 2>&1 &
XVFB_PID=$!
export DISPLAY="${DISPLAY_VAR}"
info "Xvfb launched (pid=${XVFB_PID})"

# 等待 Xvfb 接受连接（通过 xdpyinfo 或简单轮询）
for _ in $(seq 1 30); do
    if [[ -S "/tmp/.X11-unix/X${DISPLAY_NUM}" ]]; then
        info "Xvfb is accepting clients on ${DISPLAY_VAR}"
        break
    fi
    if ! kill -0 "${XVFB_PID}" 2>/dev/null; then
        err "Xvfb exited unexpectedly"
        tail -n 20 "${XVFB_LOG}" >&2 || true
        exit 1
    fi
    sleep 0.2
done
if [[ ! -S "/tmp/.X11-unix/X${DISPLAY_NUM}" ]]; then
    err "Xvfb did not become ready"
    tail -n 20 "${XVFB_LOG}" >&2 || true
    exit 1
fi

# ───────── 3. 启动 openbox（窗口管理器） ─────────
info "starting openbox window manager on ${DISPLAY_VAR}"
OPENBOX_LOG="${LOG_DIR}/openbox.log"
LOG_FILES+=("${OPENBOX_LOG}")
: > "${OPENBOX_LOG}"
openbox >>"${OPENBOX_LOG}" 2>&1 &
OPENBOX_PID=$!
info "openbox launched (pid=${OPENBOX_PID})"

# openbox 没有直接的 readiness 信号；sleep 0.3 后假定就绪。
sleep 0.3
if ! kill -0 "${OPENBOX_PID}" 2>/dev/null; then
    err "openbox exited unexpectedly"
    tail -n 20 "${OPENBOX_LOG}" >&2 || true
    exit 1
fi

# ───────── 4. 启动 x11vnc（仅 localhost） ─────────
info "starting x11vnc on ${VNC_DISPLAY_HOST}:${VNC_PORT}"
X11VNC_LOG="${LOG_DIR}/x11vnc.log"
LOG_FILES+=("${X11VNC_LOG}")
: > "${X11VNC_LOG}"

# -localhost 仅允许 loopback；-nopw 不需要密码（生产环境应在 SCG Gateway 层做鉴权）；
# -forever 让 x11vnc 在客户端断开后继续监听。
# 注意：x11vnc 不接受 -port，只接受 -rfbport；下面同时显式给 listen port。
x11vnc \
    -display "${DISPLAY_VAR}" \
    -listen "${VNC_DISPLAY_HOST}" \
    -rfbport "${VNC_PORT}" \
    -localhost \
    -nopw \
    -forever \
    -shared \
    >>"${X11VNC_LOG}" 2>&1 &
X11VNC_PID=$!
info "x11vnc launched (pid=${X11VNC_PID})"

# 等待 VNC 端口起来
for _ in $(seq 1 30); do
    if (echo >"/dev/tcp/${VNC_DISPLAY_HOST}/${VNC_PORT}") >/dev/null 2>&1; then
        info "x11vnc is listening on ${VNC_DISPLAY_HOST}:${VNC_PORT}"
        break
    fi
    if ! kill -0 "${X11VNC_PID}" 2>/dev/null; then
        err "x11vnc exited unexpectedly"
        tail -n 20 "${X11VNC_LOG}" >&2 || true
        exit 1
    fi
    sleep 0.2
done
if ! (echo >"/dev/tcp/${VNC_DISPLAY_HOST}/${VNC_PORT}") >/dev/null 2>&1; then
    err "x11vnc did not bind ${VNC_DISPLAY_HOST}:${VNC_PORT} within timeout"
    tail -n 20 "${X11VNC_LOG}" >&2 || true
    exit 1
fi

# ───────── 4b. （可选）启动本地 CRX HTTP server + 写入 Chrome managed policy ─────────
#
# 触发条件：默认开启；除非 OPENCLI_SKIP_CRX_INSTALL=1 或 CRX_DIR 不可读。
# 必须先 Xvfb 起来但不依赖 Chrome 是否已启动；managed policy 由 Chrome 自身读取。
#
# 流程：
#   1. 用 build-info.json 取 extension ID；
#   2. 后台启动本地 HTTP server（仅 127.0.0.1）；
#   3. 通过 install-chrome-policy.sh 把 ExtensionInstallForcelist + ExtensionSettings
#      写到 /etc/opt/chrome/policies/managed/opencli-hub-extension.json。
#   4. Chrome 启动后会：拉 /updates.xml -> 下载 /extension.crx -> 校验 CRX3 签名 ->
#      公钥 SHA256 == extension ID -> 静默安装。
#
# 注：managed policy 必须 root 写入，因此 install-chrome-policy.sh 在镜像构建阶段已可写，
# 运行时本 entrypoint 已经在 USER openclihub 下；为避免运行时写 /etc 失败，
# 本 PoC 在镜像构建时由 Dockerfile 的 USER root RUN 块预先生成 policy，
# 运行时入口只负责读取 build-info 中的 ID 并记录日志，不在此处再写 policy。
# （runtime policy override 是允许的，但需要 setcap/sudo，违反 PoC 简化原则。）

CRX_INFO_FILE="${CRX_DIR}/build-info.json"
CRX_FILE="${CRX_DIR}/extension.crx"
CRX_UPDATES="${CRX_DIR}/updates.xml"
CRX_HTTP_LOG="${LOG_DIR}/crx-http.log"
LOG_FILES+=("${CRX_HTTP_LOG}")

if [[ "${SKIP_CRX_INSTALL}" != "1" && -r "${CRX_INFO_FILE}" && -r "${CRX_FILE}" && -r "${CRX_UPDATES}" ]]; then
    USE_CRX_INSTALL=1
    EXTENSION_ID="$(jq -r '.extensionId // empty' "${CRX_INFO_FILE}")"
    if [[ -z "${EXTENSION_ID}" ]]; then
        err "extension ID missing in ${CRX_INFO_FILE}"
        exit 1
    fi
    info "starting local CRX HTTP server (extension id=${EXTENSION_ID})"
    : > "${CRX_HTTP_LOG}"
    OPENCLI_UPDATE_BASE_URL="${CRX_HTTP_BASE_URL}" \
    OPENCLI_CRX_HTTP_PORT="${CRX_HTTP_PORT}" \
        node /opt/opencli/scripts/crx-http-server.mjs \
            "${CRX_FILE}" \
            "${CRX_UPDATES}" \
            "${CRX_INFO_FILE}" \
        >>"${CRX_HTTP_LOG}" 2>&1 &
    CRX_HTTP_PID=$!
    info "crx-http-server launched (pid=${CRX_HTTP_PID}, log=${CRX_HTTP_LOG})"

    # 等待 server 端口可用
    for _ in $(seq 1 30); do
        if (echo >"/dev/tcp/${CRX_HTTP_BIND}/${CRX_HTTP_PORT}") >/dev/null 2>&1; then
            info "crx-http-server is listening on ${CRX_HTTP_BIND}:${CRX_HTTP_PORT}"
            break
        fi
        if ! kill -0 "${CRX_HTTP_PID}" 2>/dev/null; then
            err "crx-http-server exited unexpectedly"
            tail -n 30 "${CRX_HTTP_LOG}" >&2 || true
            exit 1
        fi
        sleep 0.2
    done
    if ! (echo >"/dev/tcp/${CRX_HTTP_BIND}/${CRX_HTTP_PORT}") >/dev/null 2>&1; then
        err "crx-http-server did not bind ${CRX_HTTP_BIND}:${CRX_HTTP_PORT}"
        tail -n 30 "${CRX_HTTP_LOG}" >&2 || true
        exit 1
    fi

    # 自检：CRX + update manifest 应可被本地 HTTP server 服务
    if ! curl -fsS --max-time 3 -o /dev/null "${CRX_HTTP_BASE_URL}/healthz"; then
        err "crx-http-server /healthz failed"
        tail -n 30 "${CRX_HTTP_LOG}" >&2 || true
        exit 1
    fi
    if ! curl -fsS --max-time 3 -o /dev/null "${CRX_HTTP_BASE_URL}/extension.crx"; then
        err "crx-http-server /extension.crx failed"
        tail -n 30 "${CRX_HTTP_LOG}" >&2 || true
        exit 1
    fi
    if ! curl -fsS --max-time 3 -o /dev/null "${CRX_HTTP_BASE_URL}/updates.xml"; then
        err "crx-http-server /updates.xml failed"
        tail -n 30 "${CRX_HTTP_LOG}" >&2 || true
        exit 1
    fi
    info "crx-http-server self-check OK (extension.crx=${CRX_FILE} sha256=$(jq -r '.crxSha256' "${CRX_INFO_FILE}"))"

    # 检查 managed policy 是否已在镜像构建阶段预置；本 entrypoint 进程是 openclihub，
    # 写不到 /etc/opt/chrome/policies/managed/。policy 由 Dockerfile 的 USER root 阶段生成。
    POLICY_FILE="${CHROME_POLICY_DIR}/opencli-hub-extension.json"
    if [[ -r "${POLICY_FILE}" ]]; then
        POLICY_INSTALLED=1
        info "chrome managed policy present: ${POLICY_FILE}"
        # 一致性自检：policy 中的 ID 必须等于 build-info 中的 ID
        POLICY_ID="$(jq -r --arg id "${EXTENSION_ID}" '.. | objects | select(has("ExtensionInstallForcelist")) | .ExtensionInstallForcelist[0] | split(";")[0] // empty' "${POLICY_FILE}" 2>/dev/null || true)"
        if [[ -n "${POLICY_ID}" && "${POLICY_ID}" != "${EXTENSION_ID}" ]]; then
            err "managed policy extension_id (${POLICY_ID}) != build-info extension_id (${EXTENSION_ID})"
            exit 1
        fi
    else
        err "managed policy NOT found at ${POLICY_FILE}; Chrome will not auto-install extension. Set OPENCLI_SKIP_CRX_INSTALL=1 to fall back, or rebuild image."
        exit 1
    fi
else
    info "skipping CRX managed install (OPENCLI_SKIP_CRX_INSTALL=${SKIP_CRX_INSTALL})"
fi

# ───────── 5. 启动 Chrome（PoC 主程序） ─────────
#
# 注意：使用 headed（无 --headless），因为 MV3 service worker 在 new-headless 模式下不可靠。
# 容器内由 Xvfb 提供虚拟 display，不影响扩展加载（参见
# OpenCLI e2e: tests/e2e/browser-ax-chrome.test.ts 的注释）。
#
# 关于 extension 加载：
#   默认通过 Chrome enterprise managed policy
#   (/etc/opt/chrome/policies/managed/opencli-hub-extension.json) 强制从
#   本地 127.0.0.1:18181 拉取 CRX3 安装，规避 google-chrome-stable 对
#   --load-extension 的拒绝（"is not allowed in Google Chrome, ignoring"）。
#   在 USE_CRX_INSTALL=1 模式下不再传 --load-extension / --disable-extensions-except。
#   在 SKIP_CRX_INSTALL=1 模式下保留这两个 flag，方便对比验证官方 Chrome 的拒绝行为。
#
# 关于 --no-sandbox：默认不开。Docker 默认 seccomp 缺 user_namespace 时 Chrome zygote
# 会 FATAL ("Failed to move to new namespace: ... Operation not permitted")。
# 这是宿主容器能力的真实缺陷，不是扩展问题；当确实无法更换 sandbox 配置时，
# 仅在显式 OPENCLI_REQUIRE_NO_SANDBOX=1 下加入 --no-sandbox，并在 NOTES 标注。
CHROME_FLAGS=(
    --user-data-dir="${PROFILE_ROOT}/${PROFILE_NAME}"
    --enable-unsafe-extension-debugging
    --no-first-run
    --no-default-browser-check
    --disable-sync
    --disable-popup-blocking
    --window-size=1280,720
)
if [[ "${USE_CRX_INSTALL}" != "1" ]]; then
    # 对比 / 调试模式：仍尝试 --load-extension，由 Chrome 自己决定是否拒绝。
    CHROME_FLAGS+=(
        --disable-extensions-except="${EXTENSION_DIR}"
        --load-extension="${EXTENSION_DIR}"
        --disable-features=DisableLoadExtensionCommandLineSwitch
    )
fi
if [[ "${OPENCLI_REQUIRE_NO_SANDBOX:-}" == "1" ]]; then
    CHROME_FLAGS+=(--no-sandbox)
fi
# 允许外部追加 flag，但只允许通过 OPENCLI_CHROME_FLAGS 追加安全字符串。
if [[ -n "${CHROME_EXTRA_FLAGS}" ]]; then
    # shellcheck disable=SC2206  # 故意按空白 split 为数组
    EXTRA=( ${CHROME_EXTRA_FLAGS} )
    CHROME_FLAGS+=( "${EXTRA[@]}" )
fi
CHROME_FLAGS+=( "${CHROME_URL}" )

info "starting google-chrome-stable"
CHROME_LOG="${LOG_DIR}/chrome.log"
LOG_FILES+=("${CHROME_LOG}")
: > "${CHROME_LOG}"

# 先确保 profile 目录存在（首次创建）；运行时由 Chrome 写入 Cookies / Local Storage / Extension Storage。
PROFILE_PATH="${PROFILE_ROOT}/${PROFILE_NAME}"
mkdir -p "${PROFILE_PATH}"
# Profile 由当前 Instance 独占。容器被 SIGKILL 后 Chrome 来不及移除 process singleton
# symlink，新容器会误判 Profile 正被另一台主机使用；启动前清理这些易失锁文件。
rm -f "${PROFILE_PATH}/SingletonLock" \
      "${PROFILE_PATH}/SingletonSocket" \
      "${PROFILE_PATH}/SingletonCookie"

# shellcheck disable=SC2086  # 我们显式维护数组，不做 word-split
google-chrome-stable "${CHROME_FLAGS[@]}" >>"${CHROME_LOG}" 2>&1 &
CHROME_PID=$!
info "chrome launched (pid=${CHROME_PID})"

# 暴露给运维检查用的环境摘要
echo "OPENCLI_DISPLAY=${DISPLAY_VAR}"
echo "OPENCLI_DAEMON_URL=${DAEMON_BASE_URL}"
echo "OPENCLI_DAEMON_PID=${DAEMON_PID}"
echo "OPENCLI_PROFILE_DIR=${PROFILE_ROOT}/${PROFILE_NAME}"
echo "OPENCLI_VNC_URL=vnc://${VNC_DISPLAY_HOST}:${VNC_PORT}"
echo "OPENCLI_CRX_INSTALL_MODE=$([[ "${USE_CRX_INSTALL}" == "1" ]] && echo managed || echo none)"
echo "OPENCLI_EXTENSION_ID=${EXTENSION_ID}"
echo "OPENCLI_CRX_UPDATE_URL=${CRX_HTTP_BASE_URL}/updates.xml"
echo "OPENCLI_LOG_DIR=${LOG_DIR}"
echo "OPENCLI_LOGS=${LOG_FILES[*]}"

info "PoC stack ready. Tail -f ${LOG_DIR}/*.log to follow."
info "Use: docker exec <cid> poc-status.sh | poc-context-id.sh | poc-vnc-check.sh"

# 阻塞直到任一关键子进程退出；之后由 trap 清理全部。
# 用 wait -n 等同"任意一个子进程退出"语义（Bash 4.3+）。
if (( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 3) )); then
    wait -n || true
else
    wait || true
fi

info "a child process exited; running shutdown_all"
exit 0
