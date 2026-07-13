# Production image: build the React SPA and Spring Boot JAR outside the runtime image.
ARG UBUNTU_VERSION=22.04
ARG NODE_MAJOR=20
ARG OPENCLI_VERSION=1.8.6
ARG OPENCLI_PACKAGE=@jackwener/opencli
ARG OPENCLI_RELEASE_TAG=v1.8.6
ARG EXTENSION_VERSION=1.0.22
ARG CHROME_FULL=150.0.7871.114-1
ARG HUB_ARTIFACT_SOURCE=java-build

FROM node:20-bookworm-slim AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,id=opencli-hub-npm,target=/root/.npm,sharing=locked \
    npm ci --prefer-offline --fetch-retries=10 --fetch-retry-factor=2 \
        --fetch-retry-mintimeout=2000 --fetch-retry-maxtimeout=120000
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-17 AS java-build
WORKDIR /workspace
COPY pom.xml lombok.config ./
COPY share/ share/
COPY core/ core/
COPY web/ web/
COPY --from=frontend-build /workspace/frontend/dist frontend/dist
RUN --mount=type=cache,id=opencli-hub-maven,target=/root/.m2/repository,sharing=locked \
    find /root/.m2/repository -name '*.lastUpdated' -delete \
 && mvn --batch-mode --update-snapshots -Dmaven.test.skip=true \
        -Dmaven.wagon.http.connectionTimeout=5000 \
        -Dmaven.wagon.http.readTimeout=60000 \
        -Dmaven.wagon.http.retryHandler.count=5 package \
 && test -s web/target/opencli-hub-web-1.0.0.jar \
 && mkdir -p /artifact \
 && cp web/target/opencli-hub-web-1.0.0.jar /artifact/opencli-hub.jar

# The default source is the reproducible in-container Maven stage. Local smoke
# builds may pass a named BuildKit context containing /artifact/opencli-hub.jar
# so host ~/.m2 and ~/.npm can be reused without changing the final image.
FROM ${HUB_ARTIFACT_SOURCE} AS hub-artifact

# This stage contains only runtime dependencies. Build-only package tools are
# installed in opencli-assets and are not inherited by the final image.
FROM eclipse-temurin:17-jre-jammy AS runtime-base
ARG NODE_MAJOR
ARG CHROME_FULL
ENV DEBIAN_FRONTEND=noninteractive
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl gnupg xz-utils; \
    mkdir -p /etc/apt/keyrings; \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg; \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" > /etc/apt/sources.list.d/nodesource.list; \
    curl -fsSL -o /tmp/google-chrome-stable.deb "https://dl.google.com/linux/chrome/deb/pool/main/g/google-chrome-stable/google-chrome-stable_${CHROME_FULL}_amd64.deb"; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        /tmp/google-chrome-stable.deb \
        nodejs=${NODE_MAJOR}.* \
        tini xvfb x11vnc openbox iproute2 jq \
        fonts-liberation fonts-noto-cjk \
        libasound2 libatk-bridge2.0-0 libatk1.0-0 libatspi2.0-0 libcairo2 libcups2 \
        libdbus-1-3 libgbm1 libglib2.0-0 libgtk-3-0 libnss3 libnspr4 libpango-1.0-0 \
        libx11-xcb1 libxcomposite1 libxdamage1 libxext6 libxfixes3 libxkbcommon0 libxrandr2 procps; \
    rm -f /tmp/google-chrome-stable.deb /etc/apt/sources.list.d/nodesource.list /etc/apt/keyrings/nodesource.gpg; \
    apt-get purge -y --auto-remove gnupg xz-utils; \
    npm --version; node --version; google-chrome-stable --version; \
    apt-get clean; rm -rf /var/lib/apt/lists/*

FROM runtime-base AS opencli-assets
ARG OPENCLI_VERSION
ARG OPENCLI_PACKAGE
ARG OPENCLI_RELEASE_TAG
ARG EXTENSION_VERSION
ENV OPENCLI_INSTALL_DIR=/opt/opencli \
    NPM_CONFIG_PREFIX=/opt/opencli/npm \
    PATH=/opt/opencli/npm/bin:${PATH} \
    OPENCLI_EXTENSION_URL=https://github.com/jackwener/OpenCLI/releases/download/${OPENCLI_RELEASE_TAG}/opencli-extension-v${EXTENSION_VERSION}.zip \
    OPENCLI_EXTENSION_SHA256=9d2e3d053948beab5d97124aa79b1532d2122e33e461eca56cac113afd33207a
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip rsync \
 && rm -rf /var/lib/apt/lists/*
COPY --chmod=0755 scripts/docker/install-opencli.sh scripts/docker/install-extension.sh /usr/local/bin/
RUN set -eux; \
    install-opencli.sh "${OPENCLI_PACKAGE}" "${OPENCLI_VERSION}"; \
    opencli --version; \
    install-extension.sh "${OPENCLI_EXTENSION_URL}" "${OPENCLI_EXTENSION_SHA256}" "${EXTENSION_VERSION}" "${OPENCLI_INSTALL_DIR}/extension"; \
    jq -e --arg version "${EXTENSION_VERSION}" '.version == $version' "${OPENCLI_INSTALL_DIR}/extension/manifest.json"; \
    npm cache clean --force; \
    rm -f /usr/local/bin/install-opencli.sh /usr/local/bin/install-extension.sh

# Pack the fixed extension and generate its fixed-ID policy during the build.
# Chrome needs --no-sandbox only for this root-owned build-time pack operation;
# neither Hub nor its Java-owned Chrome instances receive that flag at runtime.
COPY --chmod=0755 scripts/docker/build-extension-crx.mjs scripts/docker/install-chrome-policy.sh /usr/local/bin/
COPY --chmod=0600 scripts/docker/build-assets/extension-signing-key.pem /tmp/extension-signing-key.pem
RUN set -eux; \
    mkdir -p "${OPENCLI_INSTALL_DIR}/crx" "${OPENCLI_INSTALL_DIR}/certs" /tmp/opencli-extension-pack; \
    mv /tmp/extension-signing-key.pem "${OPENCLI_INSTALL_DIR}/certs/extension-signing-key.pem"; \
    cp -a "${OPENCLI_INSTALL_DIR}/extension" /tmp/opencli-extension-pack/extension; \
    HOME=/tmp/opencli-extension-pack google-chrome-stable --no-sandbox --headless=new --disable-gpu \
        --pack-extension=/tmp/opencli-extension-pack/extension \
        --pack-extension-key="${OPENCLI_INSTALL_DIR}/certs/extension-signing-key.pem"; \
    mv /tmp/opencli-extension-pack/extension.crx "${OPENCLI_INSTALL_DIR}/crx/extension.crx"; \
    build-extension-crx.mjs "${OPENCLI_INSTALL_DIR}/extension" "${OPENCLI_INSTALL_DIR}/certs/extension-signing-key.pem" \
        "${OPENCLI_INSTALL_DIR}/crx/extension.crx" "${OPENCLI_INSTALL_DIR}/crx/updates.xml" \
        "${OPENCLI_INSTALL_DIR}/crx/build-info.json" "${EXTENSION_VERSION}"; \
    extension_id="$(jq -er '.extensionId | select(test("^[a-p]{32}$"))' "${OPENCLI_INSTALL_DIR}/crx/build-info.json")"; \
    install-chrome-policy.sh "${extension_id}" http://127.0.0.1:18181 /etc/opt/chrome/policies/managed; \
    rm -rf /tmp/opencli-extension-pack "${OPENCLI_INSTALL_DIR}/certs" /usr/local/bin/build-extension-crx.mjs /usr/local/bin/install-chrome-policy.sh; \
    chmod -R a+rX "${OPENCLI_INSTALL_DIR}/extension" "${OPENCLI_INSTALL_DIR}/crx" "${OPENCLI_INSTALL_DIR}/npm"
COPY --chown=root:root --chmod=0755 scripts/docker/ /opt/opencli/scripts/

FROM runtime-base AS final
ENV HOME=/var/lib/opencli \
    OPENCLI_HOME=/var/lib/opencli \
    OPENCLI_DATA=/var/lib/opencli/data \
    OPENCLI_LOG=/data/opencli-hub/logs \
    OPENCLI_HUB_DATA_DIR=/data/opencli-hub \
    OPENCLI_HUB_SCRIPTS=/opt/opencli/scripts \
    NPM_CONFIG_PREFIX=/opt/opencli/npm \
    PATH=/opt/opencli/scripts:/opt/opencli/npm/bin:${PATH} \
    SPRING_PROFILES_ACTIVE=docker-h2
RUN set -eux; \
    groupadd --system --gid 1000 openclihub; \
    useradd --system --uid 1000 --gid openclihub --home-dir "${HOME}" --shell /bin/bash openclihub; \
    mkdir -p "${HOME}/data" /data/opencli-hub; \
    chown -R openclihub:openclihub "${HOME}" /data/opencli-hub
COPY --from=opencli-assets --chown=root:root /opt/opencli /opt/opencli
COPY --from=opencli-assets --chown=root:root /etc/opt/chrome/policies/managed/opencli-hub-extension.json /etc/opt/chrome/policies/managed/opencli-hub-extension.json
COPY --from=hub-artifact --chown=openclihub:openclihub /artifact/opencli-hub.jar /opt/opencli-hub/opencli-hub.jar
RUN test -s /opt/opencli-hub/opencli-hub.jar \
 && test -r /opt/opencli/crx/extension.crx \
 && test -r /etc/opt/chrome/policies/managed/opencli-hub-extension.json
VOLUME ["/data/opencli-hub", "/var/lib/opencli"]
USER 1000:1000
WORKDIR /var/lib/opencli
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent "http://127.0.0.1:${OPENCLI_HUB_PORT:-8080}/actuator/health" || exit 1
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["hub-entrypoint.sh"]
