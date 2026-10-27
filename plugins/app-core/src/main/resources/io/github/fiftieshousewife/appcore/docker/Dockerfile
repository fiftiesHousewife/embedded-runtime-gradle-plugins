# @APP_NAME@, from the archive servers install: the runtime is inside, so the base needs only glibc and a shell.
ARG BASE_IMAGE=@BASE_IMAGE@
FROM ${BASE_IMAGE}

ARG TARGETARCH
ARG BASE_IMAGE
ARG APP_VERSION
ARG GIT_COMMIT
ARG CREATED

LABEL @LABELS@

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
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s CMD ["/app/bin/@APP_NAME@", "healthcheck", "ready"]
STOPSIGNAL SIGTERM
ENTRYPOINT ["/app/bin/@APP_NAME@", "run"]
