package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import java.io.File

internal data class ImageIdentity(val name: String, val licenses: String, val source: String? = null)

internal object DockerfileTemplate {
    private const val PREFIX = "org.opencontainers.image"

    fun render(template: String, identity: ImageIdentity): String {
        val labels =
            listOfNotNull(
                "title" to identity.name,
                identity.source?.let { "source" to it },
                "licenses" to identity.licenses,
            ).map { (key, value) -> "$PREFIX.$key=\"${escape(value)}\"" } +
                // Expanded by the builder: this build's release, commit and time, and the base image used.
                listOf(
                    "version" to "APP_VERSION",
                    "revision" to "GIT_COMMIT",
                    "created" to "CREATED",
                    "base.name" to "BASE_IMAGE",
                )
                    .map { (key, argument) -> "$PREFIX.$key=\"${'$'}{$argument}\"" }
        return template
            .replace("@LABELS@", labels.joinToString(" \\\n      "))
            .replace("@APP_NAME@", identity.name)
            .replace("@BASE_IMAGE@", "${PluginVersion.dockerBaseImage}@${PluginVersion.dockerBaseDigest}")
    }

    /** A Dockerfile double-quoted value: `\`, `"` and `$` are taken literally only when escaped. */
    fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}

/** Only the block between the markers is rewritten, so anything outside it stays; without them the file is left be. */
internal object ManagedDockerfile {
    private const val START = "# >>> embedded-runtime"
    private const val END = "# <<< embedded-runtime"

    fun block(content: String) =
        "$START: written by the io.github.fiftieshousewife java-app / node-app plugin, which keeps\n" +
            "#     everything up to the closing line. Add your own instructions after it.\n" +
            content.trimEnd() + "\n$END\n"

    /** Null when the file has no block, being the project's own. */
    fun update(existing: String?, content: String): String? {
        existing ?: return block(content)
        val lines = existing.lines()
        val start = lines.indexOfFirst { it.startsWith(START) }
        val end = lines.indexOfFirst { it.startsWith(END) }
        if (start < 0 || end < start) return null
        val before = if (start == 0) "" else lines.take(start).joinToString("\n") + "\n"
        val after = lines.drop(end + 1).joinToString("\n")
        return before + block(content) + after
    }
}

internal object ContextDockerfile {
    private val BARE_ARG = Regex("""^ARG\s+(\w+)\s*$""")

    fun render(dockerfile: String, values: Map<String, String>): String =
        dockerfile.split("\n").joinToString("\n") { line ->
            val name = BARE_ARG.matchEntire(line.trim())?.groupValues?.get(1)
            values[name]?.let { "ARG $name=\"${DockerfileTemplate.escape(it)}\"" } ?: line
        }
}

/**
 * Refuses a Dockerfile whose image would fail only when it starts: one copying all of `app/` (as 0.0.2 and
 * earlier wrote them) gets no `/app/bin`, and one copying `app/<cpu>/` without `runtime/<cpu>/` no runtime.
 */
internal object OwnDockerfile {
    private val copiesWholeApp = Regex("""(?m)^\s*(COPY|ADD)\b.*\sapp/?\s""")
    private val copiesApp = Regex("""(?m)^\s*(COPY|ADD)\b.*\sapp/\$\{TARGETARCH}""")
    private val copiesRuntime = Regex("""(?m)^\s*(COPY|ADD)\b.*\sruntime/\$\{TARGETARCH}""")
    private val copyBoth =
        "  ARG TARGETARCH\n  COPY runtime/${'$'}{TARGETARCH}/ /app/runtime/\n  COPY app/${'$'}{TARGETARCH}/ /app/"

    fun read(file: File): String = file.readText().also { text ->
        if (copiesWholeApp.containsMatchIn(text)) {
            throw GradleException(
                "$file copies app/, which holds one archive per CPU (app/amd64/, app/arm64/). Copy the one " +
                    "being built, and its runtime:\n$copyBoth",
            )
        }
        if (copiesApp.containsMatchIn(text) && !copiesRuntime.containsMatchIn(text)) {
            throw GradleException("$file copies app/ but not the runtime, which is a layer of its own:\n$copyBoth")
        }
    }
}

/** `Dockerfile` ([name] null) or `Dockerfile.<name>`: one image the context can build. */
internal data class ImageVariant(val name: String?) {
    val dockerfile: String get() = listOfNotNull(DOCKERFILE, name).joinToString(".")

    val taskSuffix: String
        get() = name?.split('-', '_', '.')?.joinToString("") { it.replaceFirstChar(Char::uppercase) }.orEmpty()

    companion object {
        const val DOCKERFILE = "Dockerfile"

        /** The default always, as `appDockerfile` creates it; a variant only where its file is. */
        fun find(projectDir: File): List<ImageVariant> = listOf(ImageVariant(null)) +
            projectDir.listFiles().orEmpty()
                .map(File::getName)
                .filter { it.startsWith("$DOCKERFILE.") && it != "$DOCKERFILE.dockerignore" }
                .map { ImageVariant(it.removePrefix("$DOCKERFILE.")) }
                .sortedBy { it.name }
    }
}
