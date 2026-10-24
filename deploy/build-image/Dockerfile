# Linux build image for CI (and for local Linux builds where containers are available).
#
# Based on the OLDEST OS release you deploy to, because two things in a bundle are tied to the
# build machine's glibc: npm packages with native code, and anything compiled by install scripts.
# The JDK here only runs Gradle; the JDK that compiles and the runtime that is bundled are the
# pinned ones resolved by the platform plugins.
ARG BASE_IMAGE=registry.access.redhat.com/ubi8/ubi:8.10
FROM ${BASE_IMAGE}

RUN dnf install -y --setopt=install_weak_deps=False \
        java-21-openjdk-headless git tar gzip unzip findutils which \
        python3 make gcc-c++ \
    && dnf clean all

# Corporate wiring: the init script plus machine-level properties. Credentials are NOT baked in;
# CI injects ORG_GRADLE_PROJECT_artifactoryUsername / ORG_GRADLE_PROJECT_artifactoryPassword.
ARG ARTIFACTORY_URL
COPY gradle/init.d/platform-artifactory.init.gradle.kts /opt/gradle-home/init.d/
RUN printf 'platform.repositories=artifactory\nplatform.artifactory.url=%s\norg.gradle.java.installations.auto-detect=false\n' \
        "${ARTIFACTORY_URL}" > /opt/gradle-home/gradle.properties
ENV GRADLE_USER_HOME=/opt/gradle-home

RUN useradd --uid 1001 --create-home builder && chown -R builder /opt/gradle-home
USER builder
WORKDIR /workspace
