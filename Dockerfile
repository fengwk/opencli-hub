# syntax=docker/dockerfile:1.7

# Production image: build the React SPA and Spring Boot JAR outside the runtime image.
ARG NODE_MAJOR=20
ARG CHROME_FULL=150.0.7871.114-1
ARG HUB_ARTIFACT_SOURCE=java-build

# Optional OpenCLI artifact overrides. Empty values mean "use
# scripts/docker/opencli-artifact.lock.env" so docker build, Compose, local
# build and CI share one tracked pin by default.
ARG OPENCLI_PACKAGE=
ARG OPENCLI_VERSION=
ARG OPENCLI_CLI_URL=
ARG OPENCLI_CLI_SHA256=
ARG OPENCLI_SOURCE_REVISION=
ARG EXTENSION_VERSION=
ARG OPENCLI_EXTENSION_URL=
ARG OPENCLI_EXTENSION_SHA256=

FROM node:${NODE_MAJOR}-bookworm-slim AS frontend-build
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
 && mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true \
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
ARG TARGETARCH
ARG NODE_MAJOR
ARG CHROME_FULL
ENV DEBIAN_FRONTEND=noninteractive
RUN set -eux; \
    test "${TARGETARCH}" = "amd64"; \
    apt-get -o Acquire::Retries=5 update; \
    apt-get -o Acquire::Retries=5 install -y --no-install-recommends ca-certificates curl gnupg xz-utils; \
    mkdir -p /etc/apt/keyrings; \
    curl --retry 5 --retry-all-errors --connect-timeout 15 -fsSL \
        https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
        | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg; \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" > /etc/apt/sources.list.d/nodesource.list; \
    curl --retry 5 --retry-all-errors --connect-timeout 15 -fsSL \
        -o /tmp/google-chrome-stable.deb \
        "https://dl.google.com/linux/chrome/deb/pool/main/g/google-chrome-stable/google-chrome-stable_${CHROME_FULL}_amd64.deb"; \
    apt-get -o Acquire::Retries=5 update; \
    apt-get -o Acquire::Retries=5 install -y --no-install-recommends \
        /tmp/google-chrome-stable.deb \
        nodejs=${NODE_MAJOR}.* \
        tini xvfb x11vnc openbox iproute2 jq git \
        fonts-liberation fonts-noto-cjk \
        libasound2 libatk-bridge2.0-0 libatk1.0-0 libatspi2.0-0 libcairo2 libcups2 \
        libdbus-1-3 libgbm1 libglib2.0-0 libgtk-3-0 libnss3 libnspr4 libpango-1.0-0 \
        libx11-xcb1 libxcomposite1 libxdamage1 libxext6 libxfixes3 libxkbcommon0 libxrandr2 procps; \
    rm -f /tmp/google-chrome-stable.deb /etc/apt/sources.list.d/nodesource.list /etc/apt/keyrings/nodesource.gpg; \
    apt-get purge -y --auto-remove gnupg xz-utils; \
    git config --system http.version HTTP/1.1; \
    npm --version; node --version; git --version; \
    test "$(git config --system --get http.version)" = "HTTP/1.1"; \
    google-chrome-stable --version; \
    apt-get clean; rm -rf /var/lib/apt/lists/*

FROM runtime-base AS opencli-assets
ARG OPENCLI_PACKAGE
ARG OPENCLI_VERSION
ARG OPENCLI_CLI_URL
ARG OPENCLI_CLI_SHA256
ARG OPENCLI_SOURCE_REVISION
ARG EXTENSION_VERSION
ARG OPENCLI_EXTENSION_URL
ARG OPENCLI_EXTENSION_SHA256
ENV OPENCLI_INSTALL_DIR=/opt/opencli \
    NPM_CONFIG_PREFIX=/opt/opencli/npm \
    PATH=/opt/opencli/npm/bin:${PATH}
RUN apt-get -o Acquire::Retries=5 update \
 && apt-get -o Acquire::Retries=5 install -y --no-install-recommends unzip rsync \
 && rm -rf /var/lib/apt/lists/*
COPY --chmod=0755 \
    scripts/docker/install-opencli.sh \
    scripts/docker/install-extension.sh \
    scripts/docker/validate-opencli-artifact-lock.sh \
    /usr/local/bin/
# Lock is build input only; resolved truth is artifact-build-info.json.
COPY scripts/docker/opencli-artifact.lock.env /tmp/opencli-artifact.lock.env
RUN set -eux; \
    validate-opencli-artifact-lock.sh /tmp/opencli-artifact.lock.env; \
    arg_package="${OPENCLI_PACKAGE}"; \
    arg_version="${OPENCLI_VERSION}"; \
    arg_cli_url="${OPENCLI_CLI_URL}"; \
    arg_cli_sha256="${OPENCLI_CLI_SHA256}"; \
    arg_source_revision="${OPENCLI_SOURCE_REVISION}"; \
    arg_extension_version="${EXTENSION_VERSION}"; \
    arg_extension_url="${OPENCLI_EXTENSION_URL}"; \
    arg_extension_sha256="${OPENCLI_EXTENSION_SHA256}"; \
    if [ -n "${arg_cli_url}" ] || [ -n "${arg_cli_sha256}" ]; then \
        if [ -z "${arg_cli_url}" ] || [ -z "${arg_cli_sha256}" ]; then \
            echo "OPENCLI_CLI_URL and OPENCLI_CLI_SHA256 must be overridden together" >&2; \
            exit 1; \
        fi; \
    fi; \
    if [ -n "${arg_extension_url}" ] || [ -n "${arg_extension_sha256}" ]; then \
        if [ -z "${arg_extension_url}" ] || [ -z "${arg_extension_sha256}" ]; then \
            echo "OPENCLI_EXTENSION_URL and OPENCLI_EXTENSION_SHA256 must be overridden together" >&2; \
            exit 1; \
        fi; \
    fi; \
    set -a; \
    # shellcheck disable=SC1091
    . /tmp/opencli-artifact.lock.env; \
    set +a; \
    if [ -n "${arg_package}" ]; then OPENCLI_PACKAGE="${arg_package}"; fi; \
    if [ -n "${arg_version}" ]; then OPENCLI_VERSION="${arg_version}"; fi; \
    if [ -n "${arg_cli_url}" ]; then OPENCLI_CLI_URL="${arg_cli_url}"; fi; \
    if [ -n "${arg_cli_sha256}" ]; then OPENCLI_CLI_SHA256="${arg_cli_sha256}"; fi; \
    if [ -n "${arg_source_revision}" ]; then OPENCLI_SOURCE_REVISION="${arg_source_revision}"; fi; \
    if [ -n "${arg_extension_version}" ]; then EXTENSION_VERSION="${arg_extension_version}"; fi; \
    if [ -n "${arg_extension_url}" ]; then OPENCLI_EXTENSION_URL="${arg_extension_url}"; fi; \
    if [ -n "${arg_extension_sha256}" ]; then OPENCLI_EXTENSION_SHA256="${arg_extension_sha256}"; fi; \
    if [ -n "${OPENCLI_CLI_URL}" ]; then \
        install_mode=tarball; \
        install-opencli.sh "${OPENCLI_PACKAGE}" "${OPENCLI_VERSION}" \
            "${OPENCLI_CLI_URL}" "${OPENCLI_CLI_SHA256}"; \
    else \
        install_mode=registry; \
        install-opencli.sh "${OPENCLI_PACKAGE}" "${OPENCLI_VERSION}"; \
    fi; \
    opencli --version; \
    install-extension.sh "${OPENCLI_EXTENSION_URL}" "${OPENCLI_EXTENSION_SHA256}" \
        "${EXTENSION_VERSION}" "${OPENCLI_INSTALL_DIR}/extension"; \
    jq -e --arg version "${EXTENSION_VERSION}" '.version == $version' \
        "${OPENCLI_INSTALL_DIR}/extension/manifest.json"; \
    jq -n \
        --argjson schemaVersion 1 \
        --arg sourceRevision "${OPENCLI_SOURCE_REVISION}" \
        --arg installMode "${install_mode}" \
        --arg package "${OPENCLI_PACKAGE}" \
        --arg version "${OPENCLI_VERSION}" \
        --arg cliUrl "${OPENCLI_CLI_URL:-}" \
        --arg cliSha256 "${OPENCLI_CLI_SHA256:-}" \
        --arg extensionVersion "${EXTENSION_VERSION}" \
        --arg extensionUrl "${OPENCLI_EXTENSION_URL}" \
        --arg extensionSha256 "${OPENCLI_EXTENSION_SHA256}" \
        '{schemaVersion:$schemaVersion,sourceRevision:$sourceRevision,cli:{installMode:$installMode,package:$package,version:$version,url:$cliUrl,sha256:$cliSha256},extension:{version:$extensionVersion,url:$extensionUrl,sha256:$extensionSha256}}' \
        > "${OPENCLI_INSTALL_DIR}/artifact-build-info.json"; \
    jq -e '.schemaVersion == 1 and .cli.version and .extension.version' \
        "${OPENCLI_INSTALL_DIR}/artifact-build-info.json"; \
    rm -f /tmp/opencli-artifact.lock.env; \
    npm cache clean --force; \
    rm -f /usr/local/bin/install-opencli.sh \
        /usr/local/bin/install-extension.sh \
        /usr/local/bin/validate-opencli-artifact-lock.sh

# Pack the extension and generate its managed-install policy during the build.
# Chrome needs --no-sandbox only for this root-owned build-time pack operation;
# neither Hub nor its Java-owned Chrome instances receive that flag at runtime.
COPY --chmod=0755 scripts/docker/build-extension-crx.mjs scripts/docker/install-chrome-policy.sh /usr/local/bin/
RUN --mount=type=secret,id=opencli_extension_signing_key,required=true \
    set -eux; \
    signing_key=/run/secrets/opencli_extension_signing_key; \
    test -r "${signing_key}" && test -s "${signing_key}"; \
    extension_version="$(jq -er '.extension.version' "${OPENCLI_INSTALL_DIR}/artifact-build-info.json")"; \
    mkdir -p "${OPENCLI_INSTALL_DIR}/crx" /tmp/opencli-extension-pack; \
    cp -a "${OPENCLI_INSTALL_DIR}/extension" /tmp/opencli-extension-pack/extension; \
    HOME=/tmp/opencli-extension-pack google-chrome-stable --no-sandbox --headless=new --disable-gpu \
        --pack-extension=/tmp/opencli-extension-pack/extension \
        --pack-extension-key="${signing_key}"; \
    mv /tmp/opencli-extension-pack/extension.crx "${OPENCLI_INSTALL_DIR}/crx/extension.crx"; \
    build-extension-crx.mjs "${OPENCLI_INSTALL_DIR}/extension" "${signing_key}" \
        "${OPENCLI_INSTALL_DIR}/crx/extension.crx" "${OPENCLI_INSTALL_DIR}/crx/updates.xml" \
        "${OPENCLI_INSTALL_DIR}/crx/build-info.json" "${extension_version}"; \
    extension_id="$(jq -er '.extensionId | select(test("^[a-p]{32}$"))' "${OPENCLI_INSTALL_DIR}/crx/build-info.json")"; \
    install-chrome-policy.sh "${extension_id}" http://127.0.0.1:18181 /etc/opt/chrome/policies/managed; \
    rm -rf /tmp/opencli-extension-pack /usr/local/bin/build-extension-crx.mjs /usr/local/bin/install-chrome-policy.sh; \
    chmod -R a+rX "${OPENCLI_INSTALL_DIR}/extension" "${OPENCLI_INSTALL_DIR}/crx" "${OPENCLI_INSTALL_DIR}/npm" \
        "${OPENCLI_INSTALL_DIR}/artifact-build-info.json"; \
    test ! -e /tmp/opencli-artifact.lock.env; \
    test ! -e "${OPENCLI_INSTALL_DIR}/opencli-artifact.lock.env"

FROM runtime-base AS final
ENV HOME=/var/lib/opencli \
    OPENCLI_HOME=/var/lib/opencli \
    OPENCLI_DATA=/var/lib/opencli/data \
    OPENCLI_LOG=/data/opencli-hub/logs \
    OPENCLI_HUB_DATA_DIR=/data/opencli-hub \
    OPENCLI_HUB_SCRIPTS=/opt/opencli/scripts \
    NPM_CONFIG_PREFIX=/opt/opencli/npm \
    PATH=/opt/opencli/scripts:/opt/opencli/npm/bin:${PATH} \
    SPRING_PROFILES_ACTIVE=docker-h2 \
    OPENCLI_DISABLE_UPDATE_CHECK=1
RUN set -eux; \
    groupadd --system --gid 1000 openclihub; \
    useradd --system --uid 1000 --gid openclihub --home-dir "${HOME}" --shell /bin/bash openclihub; \
    mkdir -p "${HOME}/data" /data/opencli-hub; \
    chown -R openclihub:openclihub "${HOME}" /data/opencli-hub
COPY --from=opencli-assets --chown=root:root /opt/opencli /opt/opencli
COPY --from=opencli-assets --chown=root:root /etc/opt/chrome/policies/managed/opencli-hub-extension.json /etc/opt/chrome/policies/managed/opencli-hub-extension.json
COPY --from=hub-artifact --chown=openclihub:openclihub /artifact/opencli-hub.jar /opt/opencli-hub/opencli-hub.jar
COPY --chown=root:root --chmod=0755 scripts/docker/hub-entrypoint.sh scripts/docker/crx-http-server.mjs /opt/opencli/scripts/
RUN test -s /opt/opencli-hub/opencli-hub.jar \
 && git --version \
 && test -r /opt/opencli/crx/extension.crx \
 && test -r /opt/opencli/artifact-build-info.json \
 && test -r /etc/opt/chrome/policies/managed/opencli-hub-extension.json \
 && test "$(find /opt/opencli/scripts -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | sort | paste -sd ' ' -)" = "crx-http-server.mjs hub-entrypoint.sh"
VOLUME ["/data/opencli-hub", "/var/lib/opencli"]
USER 1000:1000
WORKDIR /var/lib/opencli
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent "http://127.0.0.1:${OPENCLI_HUB_PORT:-8080}/actuator/health" || exit 1
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["hub-entrypoint.sh"]
