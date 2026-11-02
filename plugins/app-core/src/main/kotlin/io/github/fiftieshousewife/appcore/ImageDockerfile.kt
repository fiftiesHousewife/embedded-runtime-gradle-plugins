package io.github.fiftieshousewife.appcore

/** One [ImageSpec], resolved with what the build knows of the app. */
internal data class ImageModel(
    val name: String,
    val identity: ImageIdentity,
    val java: Boolean,
    val base: String,
    val buildImage: String,
    val packages: List<String>,
    val environment: Map<String, String>,
    val labels: Map<String, String>,
    val ports: List<Int>,
    val user: String,
    val app: Boolean,
)

/**
 * Writes an [ImageModel] as a Dockerfile of two stages. A files stage on the build machine's own CPU installs the
 * locked RPMs into a copy of the base's root with `rpm` (signatures checked, scriptlets refused, the context's
 * fixed time as the install time) and makes `/var/lib/app` and `/licenses`; the final stage copies that, the
 * runtime, the libraries and the app's own files onto the base, from what changes least to what changes most, and
 * runs nothing, so no stage needs emulation or a repository.
 */
internal object ImageDockerfile {
    fun render(image: ImageModel): String =
        (header(image) + files(image) + final(image) + command(image)).joinToString("\n", postfix = "\n")

    private fun header(image: ImageModel) = listOf(
        "# Written by the plugin from images { register(\"${image.name}\") { … } }: change it there.",
        "# BuildKit sets BUILDPLATFORM; other builders take it as --build-arg BUILDPLATFORM=linux/<cpu>.",
        "ARG BASE_IMAGE=${image.base}",
        "ARG BUILD_IMAGE=${image.buildImage}",
        "ARG BUILDPLATFORM",
        "FROM \${BASE_IMAGE} AS base",
        "",
        "FROM --platform=\${BUILDPLATFORM} \${BUILD_IMAGE} AS files",
        "ARG TARGETARCH",
    )

    private fun files(image: ImageModel): List<String> {
        val install = if (image.packages.isEmpty()) emptyList() else INSTALL
        val copies = if (install.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "COPY --from=base / /mnt/rootfs/",
                "COPY packages/\${TARGETARCH}/ /tmp/packages/",
            )
        }
        val run = install + listOf(
            "mkdir -p /out/var/lib/app/data /out/var/lib/app/logs",
            "chown -R 1001:0 /out/var/lib/app && chmod -R g=u /out/var/lib/app",
        )
        val licences = if (image.java) {
            "COPY runtime/\${TARGETARCH}/legal/ /out/licenses/java-runtime/"
        } else {
            "COPY runtime/\${TARGETARCH}/LICENSE /out/licenses/nodejs/LICENSE"
        }
        return copies + ("RUN " + run.joinToString(" && \\\n    ")) + licences + ""
    }

    private fun final(image: ImageModel): List<String> {
        val layers = listOf("COPY --from=files /out/ /", "COPY --chown=1001:0 layers/\${TARGETARCH}/runtime/ /") +
            if (image.app) {
                listOf(
                    "COPY --chown=1001:0 layers/\${TARGETARCH}/libs/ /",
                    "COPY --chown=1001:0 layers/\${TARGETARCH}/code/ /",
                )
            } else {
                emptyList()
            }
        return listOf("FROM base", "") + ARGS + "" + labels(image) + "" + layers + "" + env(image) +
            listOfNotNull(image.user.ifEmpty { null }?.let { "USER $it" }, "WORKDIR /var/lib/app")
    }

    private fun labels(image: ImageModel): List<String> {
        val all = ImageLabels.of(image) + image.labels.mapValues { (_, value) -> quoted(value) }
        return listOf("LABEL " + all.entries.joinToString(" \\\n      ") { (key, value) -> "$key=\"$value\"" })
    }

    private fun env(image: ImageModel): List<String> {
        val all = defaultEnvironment(image) + image.environment
        return listOf("ENV " + all.entries.joinToString(" \\\n    ") { (key, value) -> "$key=\"${quoted(value)}\"" })
    }

    private fun defaultEnvironment(image: ImageModel): Map<String, String> {
        val home = mapOf("HOME" to "/var/lib/app", "LANG" to "C.UTF-8")
        if (!image.app) {
            return mapOf("PATH" to "/app/runtime/bin:/usr/bin:/bin") +
                (if (image.java) mapOf("JAVA_HOME" to "/app/runtime") else emptyMap()) + home
        }
        return mapOf(
            "APP_ROOT" to "/var/lib/app",
            "APP_CONF_DIR" to "/config",
            "APP_DATA_DIR" to "/var/lib/app/data",
            "APP_LOG_DIR" to "/var/lib/app/logs",
            "APP_LOG_TARGET" to "stdout",
            "APP_PORT" to (image.ports.firstOrNull() ?: DEFAULT_PORT).toString(),
        ) + home
    }

    private fun command(image: ImageModel): List<String> {
        if (!image.app) {
            return if (image.java) {
                listOf("ENTRYPOINT [\"java\"]", "CMD [\"-version\"]")
            } else {
                listOf("ENTRYPOINT [\"node\"]", "CMD [\"--version\"]")
            }
        }
        val launcher = "/app/bin/${image.identity.name}"
        // Each probe is a second process inside the container's limits: a small JVM, without the server's options.
        val probe = if (image.java) "\"env\", \"APP_JAVA_OPTS=$PROBE_JAVA_OPTS\", " else ""
        return listOf(
            image.ports.ifEmpty { listOf(DEFAULT_PORT) }.joinToString(" ", prefix = "EXPOSE "),
            "HEALTHCHECK --interval=30s --timeout=10s --start-period=30s \\",
            "    CMD [$probe\"$launcher\", \"healthcheck\", \"ready\"]",
            "STOPSIGNAL SIGTERM",
            "ENTRYPOINT [\"$launcher\", \"run\"]",
        )
    }

    private fun quoted(value: String) = DockerfileTemplate.escape(value)

    /** Rpm installs into the copy of the base's root; then the packages' files and the database are staged. */
    private val INSTALL = listOf(
        "rpm --checksig /tmp/packages/*.rpm",
        "for rpm in /tmp/packages/*.rpm; do \\\n" +
            "      if [ -n \"\$(rpm -qp --scripts --triggers \"\$rpm\")\" ]; then " +
            "echo \"\$rpm has scriptlets\" >&2; exit 1; fi; \\\n    done",
        "set -- /tmp/packages/*.rpm && SOURCE_DATE_EPOCH=\$(stat -c %Y \"\$1\") rpm --root /mnt/rootfs --install " +
            "--ignorearch \\\n      --noscripts --notriggers --excludedocs /tmp/packages/*.rpm",
        "rpm -qlp /tmp/packages/*.rpm | while read -r path; do \\\n" +
            "      if [ -f \"/mnt/rootfs\$path\" ] || [ -L \"/mnt/rootfs\$path\" ]; then \\\n" +
            "        mkdir -p \"/out\${path%/*}\" && cp -a \"/mnt/rootfs\$path\" \"/out\$path\"; \\\n" +
            "      fi; \\\n    done",
        "mkdir -p /out/var/lib/rpm && cp -a /mnt/rootfs/var/lib/rpm/. /out/var/lib/rpm/",
    )

    private val ARGS = listOf(
        "ARG TARGETARCH",
        "ARG BASE_IMAGE",
        "ARG APP_VERSION",
        "ARG GIT_COMMIT",
        "ARG CREATED",
        "ARG RELEASE=1",
    )

    private const val DEFAULT_PORT = 8080
    private const val PROBE_JAVA_OPTS = "-Xmx32m -Xss512k -XX:TieredStopAtLevel=1 -XX:+UseSerialGC"
}
