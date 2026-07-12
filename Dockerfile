# opencli-hub 基础镜像 (F1 PoC + 后续 I0 集成共用)
#
# 目标：
#   1. 提供正式 google-chrome-stable、OpenCLI CLI 和固定版本 Browser Bridge extension，
#      构建阶段下载并校验，运行时不再访问外网；
#   2. 提供 Xvfb / openbox / x11vnc / tini / 非 root 用户等运行时基础设施，
#      F1 PoC 用它们验证 chrome + extension + daemon + VNC 链路；
#   3. 同时包含 Java 17 + Node.js 20+，为后续 I0 集成 Hub 应用预留运行时。
#
# 固定基线版本通过 ARG 暴露，构建时可在保证镜像可重复的前提下做版本升级。

ARG UBUNTU_VERSION=22.04
ARG NODE_MAJOR=20
ARG OPENCLI_VERSION=1.8.6
ARG OPENCLI_PACKAGE=@jackwener/opencli
ARG OPENCLI_RELEASE_TAG=v1.8.6
ARG EXTENSION_VERSION=1.0.22
ARG CHROME_MAJOR=150
ARG CHROME_FULL=150.0.7871.114-1

# ──────────────────────────────────────────────────────────────────────────────
# Stage 1: node-deps
#   - 安装 Node.js 20.x（来自 NodeSource 官方仓库）
# ──────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS node-deps

ARG NODE_MAJOR

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        gnupg \
        xz-utils \
 && mkdir -p /etc/apt/keyrings \
 && curl -fsSL "https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key" \
        | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
        nodejs=${NODE_MAJOR}.* \
 && npm install -g npm@10.9.0 \
 && node --version \
 && npm --version \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*


# ──────────────────────────────────────────────────────────────────────────────
# Stage 2: opencli-runtime
#   - 安装 google-chrome-stable 固定版本（从 dl.google.com 直接下载 .deb 并校验）
#   - 安装 Xvfb / openbox / x11vnc / tini / 必需字体和运行时库
#   - 创建非 root 用户 openclihub
#   - 全局安装 OpenCLI 固定版本
#   - 下载并校验 Browser Bridge extension ZIP，解压到 /opt/opencli/extension
# ──────────────────────────────────────────────────────────────────────────────
FROM node-deps AS opencli-runtime

ARG CHROME_MAJOR
ARG CHROME_FULL
ARG OPENCLI_VERSION
ARG OPENCLI_PACKAGE
ARG OPENCLI_RELEASE_TAG
ARG EXTENSION_VERSION

ENV DEBIAN_FRONTEND=noninteractive

# extension ZIP 在构建期固定下载并做 SHA256 校验；URL 与 hash 同步记录在 docs/poc-chrome-extension.md。
ENV OPENCLI_EXTENSION_URL="https://github.com/jackwener/OpenCLI/releases/download/${OPENCLI_RELEASE_TAG}/opencli-extension-v${EXTENSION_VERSION}.zip"
ENV OPENCLI_EXTENSION_SHA256="9d2e3d053948beab5d97124aa79b1532d2122e33e461eca56cac113afd33207a"
ENV OPENCLI_INSTALL_DIR=/opt/opencli
ENV OPENCLI_HOME=/var/lib/opencli
ENV OPENCLI_DATA=/var/lib/opencli/data
ENV OPENCLI_LOG=/var/log/opencli

# 1. 安装 google-chrome-stable 固定版本（直接从 dl.google.com 拉 .deb，避开仓库漂移）。
#    URL 模式：https://dl.google.com/linux/chrome/deb/pool/main/g/google-chrome-stable/google-chrome-stable_<version>_amd64.deb
RUN set -eux \
 && CHROME_DEB_URL="https://dl.google.com/linux/chrome/deb/pool/main/g/google-chrome-stable/google-chrome-stable_${CHROME_FULL}_amd64.deb" \
 && curl -fsSL -o /tmp/google-chrome-stable.deb "${CHROME_DEB_URL}" \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
        /tmp/google-chrome-stable.deb \
 && rm -f /tmp/google-chrome-stable.deb \
 && google-chrome-stable --version \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# 2. 安装 Xvfb / openbox / x11vnc / tini / 字体与基础运行时库；
#    unzip / rsync 用于构建期 extension 安装；iproute2 提供 ss（VNC 监听检查）。
RUN set -eux \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
        tini \
        xvfb \
        x11vnc \
        openbox \
        unzip \
        rsync \
        iproute2 \
        jq \
        fonts-liberation \
        fonts-noto-cjk \
        libasound2 \
        libatk-bridge2.0-0 \
        libatk1.0-0 \
        libatspi2.0-0 \
        libcairo2 \
        libcups2 \
        libdbus-1-3 \
        libgbm1 \
        libglib2.0-0 \
        libgtk-3-0 \
        libnss3 \
        libnspr4 \
        libpango-1.0-0 \
        libx11-xcb1 \
        libxcomposite1 \
        libxdamage1 \
        libxext6 \
        libxfixes3 \
        libxkbcommon0 \
        libxrandr2 \
        procps \
        ca-certificates \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/* \
 && which tini Xvfb x11vnc openbox google-chrome-stable unzip rsync

# 3. 创建非 root 用户和数据目录；本镜像内所有运行时进程都应以 openclihub 启动。
RUN set -eux \
 && groupadd --system --gid 1000 openclihub \
 && useradd  --system --uid 1000 --gid openclihub --home-dir "${OPENCLI_HOME}" --shell /bin/bash openclihub \
 && mkdir -p \
        "${OPENCLI_INSTALL_DIR}" \
        "${OPENCLI_INSTALL_DIR}/extension" \
        "${OPENCLI_DATA}" \
        "${OPENCLI_LOG}" \
        "${OPENCLI_HOME}/.opencli-hub" \
 && chown -R openclihub:openclihub "${OPENCLI_HOME}" "${OPENCLI_LOG}" \
 && chmod 0755 "${OPENCLI_DATA}"

# 4. 全局安装固定版本 OpenCLI（绑定 package-lock 行为：npm ci 不可用，改用 --no-fund --no-audit）。
COPY --chmod=0755 scripts/docker/install-opencli.sh /usr/local/bin/install-opencli.sh
RUN set -eux \
 && install-opencli.sh "${OPENCLI_PACKAGE}" "${OPENCLI_VERSION}" \
 && opencli --version \
 && rm -f /usr/local/bin/install-opencli.sh

# 5. 下载并校验固定版本 extension ZIP，解压到 /opt/opencli/extension。
COPY --chmod=0755 scripts/docker/install-extension.sh /usr/local/bin/install-extension.sh
RUN set -eux \
 && install-extension.sh \
        "${OPENCLI_EXTENSION_URL}" \
        "${OPENCLI_EXTENSION_SHA256}" \
        "${EXTENSION_VERSION}" \
        "${OPENCLI_INSTALL_DIR}/extension" \
 && test -f "${OPENCLI_INSTALL_DIR}/extension/manifest.json" \
 && jq -e --arg v "${EXTENSION_VERSION}" '.version == $v' "${OPENCLI_INSTALL_DIR}/extension/manifest.json" \
 && rm -f /usr/local/bin/install-extension.sh \
 && chown -R root:root "${OPENCLI_INSTALL_DIR}/extension" \
 && chmod -R a+rX "${OPENCLI_INSTALL_DIR}/extension"

# 5b. 把固定 extension 1.0.22 打包为带固定 extension ID 的 CRX3 + Chrome update manifest。
#
#   google-chrome-stable 拒绝 --load-extension（"is not allowed in Google Chrome, ignoring"），
#   因此不能用 --load-extension 在运行时注入。改为：构建期用 google-chrome-stable
#   自带的 --pack-extension + 固定的 PEM signing key 产生 CRX3，再用 managed policy
#   ExtensionInstallForcelist/ExtensionSettings 在运行时通过本地 HTTP 拉取安装。
#
#   - PEM 在 scripts/docker/build-assets/extension-signing-key.pem 提交；SHA-256：
#     1c00afbec6b198b7de2cd2b5643c9dc1dbf519d4322ea09adf853353535f41ce
#   - 推导出的固定 extension ID：lieajjjjjggpnhebbjmmlfofjojallpe
#   - 该 ID 在 PEM 不变的前提下跨构建稳定。
#   - PEM 仅决定公开 extension ID；密钥泄露意味着攻击者能签相同 ID 的更新，但
#     不会泄露业务秘密。生产环境应使用独立安全构建机上的临时 key，并在 release
#     流程中固化 ID。
COPY --chmod=0755 scripts/docker/build-extension-crx.mjs /usr/local/bin/build-extension-crx.mjs
COPY --chmod=0600 scripts/docker/build-assets/extension-signing-key.pem /tmp/extension-signing-key.pem
RUN set -eux \
 && mkdir -p "${OPENCLI_INSTALL_DIR}/crx" "${OPENCLI_INSTALL_DIR}/certs" /tmp/opencli-extension-pack \
 && mv /tmp/extension-signing-key.pem "${OPENCLI_INSTALL_DIR}/certs/extension-signing-key.pem" \
 && cp -a "${OPENCLI_INSTALL_DIR}/extension" /tmp/opencli-extension-pack/extension \
 && HOME=/tmp/opencli-extension-pack google-chrome-stable \
        --no-sandbox \
        --headless=new \
        --disable-gpu \
        --pack-extension=/tmp/opencli-extension-pack/extension \
        --pack-extension-key="${OPENCLI_INSTALL_DIR}/certs/extension-signing-key.pem" \
 && mv /tmp/opencli-extension-pack/extension.crx "${OPENCLI_INSTALL_DIR}/crx/extension.crx" \
 && build-extension-crx.mjs \
        "${OPENCLI_INSTALL_DIR}/extension" \
        "${OPENCLI_INSTALL_DIR}/certs/extension-signing-key.pem" \
        "${OPENCLI_INSTALL_DIR}/crx/extension.crx" \
        "${OPENCLI_INSTALL_DIR}/crx/updates.xml" \
        "${OPENCLI_INSTALL_DIR}/crx/build-info.json" \
        "${EXTENSION_VERSION}" \
 && jq -e '.extensionId | test("^[a-p]{32}$")' "${OPENCLI_INSTALL_DIR}/crx/build-info.json" \
 && rm -rf /tmp/opencli-extension-pack /usr/local/bin/build-extension-crx.mjs \
 && chmod 0600 "${OPENCLI_INSTALL_DIR}/certs/extension-signing-key.pem" \
 && chmod 0644 "${OPENCLI_INSTALL_DIR}/crx/extension.crx" \
               "${OPENCLI_INSTALL_DIR}/crx/updates.xml" \
               "${OPENCLI_INSTALL_DIR}/crx/build-info.json"

# 5c. 生成 Chrome enterprise managed policy（必须在 USER openclihub 切换前由 root 完成）。
#    policy 用 ExtensionInstallForcelist + ExtensionSettings 指向 build-time 决定的固定 ID；
#    update_url 在这里写为占位符（构建期无法知道运行时端口），运行时由
#    install-chrome-policy.sh 替换；考虑到镜像单端口默认 18181，这里直接写死并要求
#    CRX HTTP server 必须监听 127.0.0.1:18181。
COPY --chmod=0755 scripts/docker/install-chrome-policy.sh /usr/local/bin/install-chrome-policy.sh
RUN set -eux \
 && EXT_ID="$(jq -r '.extensionId' "${OPENCLI_INSTALL_DIR}/crx/build-info.json")" \
 && test -n "${EXT_ID}" \
 && test "${#EXT_ID}" = "32" \
 && install-chrome-policy.sh "${EXT_ID}" "http://127.0.0.1:18181" /etc/opt/chrome/policies/managed \
 && test -s /etc/opt/chrome/policies/managed/opencli-hub-extension.json \
 && jq -e '.ExtensionInstallForcelist[0] and .ExtensionSettings' /etc/opt/chrome/policies/managed/opencli-hub-extension.json \
 && rm -f /usr/local/bin/install-chrome-policy.sh

# 6. 复制运行时脚本（chmod 0755 由 --chmod 保证，源仓库内不必额外维护可执行位）。
COPY --chown=root:root --chmod=0755 scripts/docker/ /opt/opencli/scripts/

USER openclihub
WORKDIR ${OPENCLI_HOME}

# 默认入口：tini 作为 PID 1，转发 SIGTERM/SIGINT 给子进程。
# 启动模式由第一个参数选择（F1 PoC 用 `poc`；后续 Hub 启动将引入 `hub` 模式）。
ENV OPENCLI_HUB_SCRIPTS=/opt/opencli/scripts
ENV PATH="/opt/opencli/scripts:${PATH}"

# F1 PoC 默认暴露 VNC 端口（容器内仅 127.0.0.1），Hub Web/REST 端口预留后续 I0。
# 当前阶段不通过 EXPOSE 暴露 5900；保留为文档约定。
# EXPOSE 5900

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["poc-entrypoint.sh"]
