# @APP_NAME@, from the archive servers install: the runtime is inside, so the base needs only glibc and a shell.
ARG BASE_IMAGE=@BASE_IMAGE@
FROM ${BASE_IMAGE}

ARG TARGETARCH
ARG BASE_IMAGE
ARG APP_VERSION
ARG GIT_COMMIT
ARG CREATED

LABEL @LABELS@

# Where the app writes when nothing is mounted there; group 0 as well, for OpenShift's arbitrary user.
RUN mkdir -p /var/lib/app/data /var/lib/app/logs && chown -R 1001:0 /var/lib/app && chmod -R g=u /var/lib/app

# The runtime first, in a layer of its own: a new release of the app leaves it alone. Each tree in
# layers/ holds the paths it takes in the image, with fixed times, so its layer is the same in every
# build and every image of the app.
COPY --chown=1001:0 layers/${TARGETARCH}/runtime/ /
COPY --chown=1001:0 layers/${TARGETARCH}/app/ /

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
