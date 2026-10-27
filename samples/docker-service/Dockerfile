# >>> embedded-runtime: written by the io.github.fiftieshousewife java-app / node-app plugin, which keeps
#     everything up to the closing line. Add your own instructions after it.
# Container image for docker-service, built from the same archive that is installed on servers.
#
# The base image needs only glibc and /bin/sh — the Java or Node runtime is inside the archive,
# so there is exactly one runtime version to track. It is pinned by digest, so every build of this
# release starts from the same bytes. To use a registry mirror or another base image, pass the whole
# reference: --build-arg BASE_IMAGE=mirror.example.com/ubi9/ubi-minimal:9.8@sha256:… (a mirror keeps
# the digest). Nothing is run while building, so one context builds every CPU without emulation:
# docker buildx build --platform linux/amd64,linux/arm64 build/docker-context
ARG BASE_IMAGE=registry.access.redhat.com/ubi9/ubi-minimal:9.8@sha256:2d4c4551af6831dcd26bbf86108c4385de5183ec8fe6b5868f6f9640fe47f4ed
FROM ${BASE_IMAGE}

# Set by BuildKit, Buildah and Podman to the CPU being built: amd64 or arm64.
ARG TARGETARCH
ARG BASE_IMAGE
# The release, its commit and that commit's time, filled in by appDockerContext for each build.
ARG APP_VERSION
ARG GIT_COMMIT
ARG CREATED

# OCI image annotations: which app, release and commit this image holds, and what it was built on.
LABEL org.opencontainers.image.title="docker-service" \
      org.opencontainers.image.licenses="LicenseRef-Proprietary" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.base.name="${BASE_IMAGE}"

COPY --chown=1001:0 app/${TARGETARCH}/ /app/

ENV APP_ROOT=/var/lib/app \
    APP_CONF_DIR=/config \
    APP_DATA_DIR=/var/lib/app/data \
    APP_LOG_DIR=/var/lib/app/logs \
    APP_LOG_TARGET=stdout \
    APP_PORT=8080

USER 1001
EXPOSE 8080
# Docker only: OCI-format builds (Podman's default) drop HEALTHCHECK and Kubernetes ignores it, so
# give Kubernetes exec probes running `healthcheck live` and `healthcheck ready` instead.
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s CMD ["/app/bin/docker-service", "healthcheck", "ready"]
STOPSIGNAL SIGTERM
ENTRYPOINT ["/app/bin/docker-service", "run"]
# <<< embedded-runtime
