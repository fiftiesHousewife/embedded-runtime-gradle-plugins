package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties

/**
 * Kept in one place instead of in a repackaged runtime: a directory (or a zip of one) holding
 * `runtime-policy.properties` and, optionally, `certificates/`. Applied after the project's own settings, so
 * it always wins; `appVerifyPolicy` and `appSmokeTest` check the actual files against it ([RuntimePolicyCheck]).
 */
data class RuntimePolicy(
    val id: String,
    val version: String,
    val set: Map<String, String>,
    val append: Map<String, List<String>>,
    val lockProperties: Boolean,
    val lockedOptions: Boolean,
    val forbiddenModules: List<String>,
    val forbiddenJvmArgs: List<String>,
    /** Package URL prefixes no component in the app's SBOM may match. */
    val forbiddenLibraries: List<String>,
    val tlsMinVersion: String? = null,
    val forbiddenNodeArgs: List<String> = emptyList(),
    val certificates: List<File>,
    /** SHA-256 over the policy file and every certificate, recorded in each runtime it is applied to. */
    val digest: String,
) {
    val label: String get() = "$id $version (sha256 ${digest.take(DIGEST_SHOWN)})"

    companion object {
        const val FILE = "runtime-policy.properties"
        private const val DIGEST_SHOWN = 12
        private val TLS_VERSIONS = setOf("1.2", "1.3")

        /** `lockProperties=true  # why` reads as a value that isn't `true`, quietly weakening the policy. */
        private fun requireNoTrailingComments(file: File, props: Properties) {
            val commented =
                props.stringPropertyNames().sorted().filter { TRAILING_COMMENT.containsMatchIn(props.getProperty(it)) }
            if (commented.isNotEmpty()) {
                val verb = if (commented.size == 1) "has" else "have"
                throw GradleException(
                    "$file: ${commented.joinToString()} $verb a comment after the value, which a " +
                        ".properties file reads as part of it; put the comment on its own line",
                )
            }
        }

        private val TRAILING_COMMENT = Regex("""\s#""")

        fun load(dir: File): RuntimePolicy {
            val file = dir.resolve(FILE)
            if (!file.isFile) throw GradleException("$dir is not a runtime policy: it has no $FILE")
            val props = Properties().apply { Files.newInputStream(file.toPath()).use(::load) }
            requireNoTrailingComments(file, props)
            fun list(key: String) =
                props.getProperty(key).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            fun prefixed(prefix: String) = props.stringPropertyNames().filter { it.startsWith(prefix) }.sorted()
                .associate { it.removePrefix(prefix) to props.getProperty(it).trim() }
            val certificates = dir.resolve("certificates").walkTopDown().filter(File::isFile).sortedBy {
                it.name
            }.toList()
            fun required(key: String) = props.getProperty(key) ?: throw GradleException("$file has no $key")
            return RuntimePolicy(
                id = required("id"),
                version = required("version"),
                set = prefixed("set."),
                append = prefixed("append.").mapValues { (k, _) -> list("append.$k") },
                // A .properties value keeps trailing spaces: `true ` must still lock.
                lockProperties = props.getProperty("lockProperties")?.trim().toBoolean(),
                lockedOptions = props.getProperty("runtimeOptions")?.trim() == RuntimeOptions.LOCKED.id,
                forbiddenModules = list("forbiddenModules"),
                forbiddenJvmArgs = list("forbiddenJvmArgs"),
                forbiddenLibraries = list("forbiddenLibraries"),
                tlsMinVersion = props.getProperty("tlsMinVersion")?.trim()?.also { v ->
                    if (v !in TLS_VERSIONS) throw GradleException("$file has tlsMinVersion=$v; use 1.2 or 1.3")
                },
                forbiddenNodeArgs = list("forbiddenNodeArgs"),
                certificates = certificates,
                digest = digest(listOf(file) + certificates),
            )
        }

        private fun digest(files: List<File>): String {
            val sha = MessageDigest.getInstance("SHA-256")
            files.forEach { sha.update(it.readBytes()) }
            return HexFormat.of().formatHex(sha.digest())
        }
    }
}
