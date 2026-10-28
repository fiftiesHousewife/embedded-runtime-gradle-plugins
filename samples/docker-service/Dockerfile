# >>> embedded-runtime: written by the io.github.fiftieshousewife java-app / node-app plugin, which keeps
#     everything up to the closing line. Add your own instructions after it.
# docker-service, from the archive servers install: the runtime is inside, so the base needs only glibc and a shell.
ARG BASE_IMAGE=registry.access.redhat.com/ubi9/ubi-minimal:9.8-1793050346@sha256:6f3a78179e0b30b930a186e7f68a4af52e51bb82406c9653be10bddd624e40f5
FROM ${BASE_IMAGE}

ARG TARGETARCH
ARG BASE_IMAGE
ARG APP_VERSION
ARG GIT_COMMIT
ARG CREATED

LABEL org.opencontainers.image.title="docker-service" \
      org.opencontainers.image.source="https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins.git" \
      org.opencontainers.image.licenses="LicenseRef-Proprietary" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.base.name="${BASE_IMAGE}"

# The runtime first, in a layer of its own: a new release of the app leaves it alone.
COPY --chown=1001:0 runtime/${TARGETARCH}/ /app/runtime/
COPY --chown=1001:0 app/${TARGETARCH}/ /app/

ENV APP_ROOT=/var/lib/app \
    APP_CONF_DIR=/config \
    APP_DATA_DIR=/var/lib/app/data \
    APP_LOG_DIR=/var/lib/app/logs \
    APP_LOG_TARGET=stdout \
    APP_PORT=8080

USER 1001
EXPOSE 8080
# Docker only: OCI images (Podman's default) drop HEALTHCHECK and Kubernetes ignores it; use exec probes there.
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s CMD ["/app/bin/docker-service", "healthcheck", "ready"]
STOPSIGNAL SIGTERM
ENTRYPOINT ["/app/bin/docker-service", "run"]
# <<< embedded-runtime
