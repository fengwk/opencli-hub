#!/usr/bin/env bash
#
# poc-vnc-check.sh
#
# 检查 x11vnc 是否按预期仅监听 127.0.0.1:<VNC_PORT>。
# 用法：
#   poc-vnc-check.sh [port]
# 退出码：
#   0  VNC 监听正确
#   1  VNC 未监听或监听地址非预期
#   2  调用错误

set -euo pipefail

PORT="${1:-${OPENCLI_VNC_PORT:-5900}}"
HOST="${OPENCLI_VNC_DISPLAY_HOST:-127.0.0.1}"

# 1. 端口必须能连接。
if ! (echo >"/dev/tcp/${HOST}/${PORT}") >/dev/null 2>&1; then
    echo "VNC not reachable at ${HOST}:${PORT}" >&2
    exit 1
fi
echo "VNC reachable at ${HOST}:${PORT}"

# 2. 端口必须不在 0.0.0.0 上接受连接（防止 x11vnc 漏掉 -localhost）。
#    用 ss/netstat 拿监听 socket 的 bind address。
BIND_ADDR=""
if command -v ss >/dev/null 2>&1; then
    # -l 仅监听，-t tcp，-n 不解析，-H 不带表头；抓 IPv4 与 IPv6 的 0.0.0.0 / [::] 行
    BIND_ADDR="$(ss -ltnH 2>/dev/null \
        | awk -v port=":${PORT}" '$4 ~ port"$" {print $4}' \
        | sort -u)"
elif command -v netstat >/dev/null 2>&1; then
    BIND_ADDR="$(netstat -ltn 2>/dev/null \
        | awk -v port=":${PORT}$" '$4 ~ port {print $4}' \
        | sort -u)"
else
    echo "warn: neither ss nor netstat available; skip bind-address check" >&2
    exit 0
fi

if [[ -z "${BIND_ADDR}" ]]; then
    echo "VNC port ${PORT} not present in ss/netstat output" >&2
    exit 1
fi

echo "VNC bound to: ${BIND_ADDR}"

BAD=0
for addr in ${BIND_ADDR}; do
    case "${addr}" in
        127.0.0.1:*|\[::1\]:*) ;;
        0.0.0.0:*|[::]:*) BAD=1 ;;
        *) BAD=1 ;;
    esac
done

if (( BAD != 0 )); then
    echo "VNC is bound to a non-loopback address; refusing" >&2
    exit 1
fi

echo "VNC is correctly loopback-only"
exit 0
