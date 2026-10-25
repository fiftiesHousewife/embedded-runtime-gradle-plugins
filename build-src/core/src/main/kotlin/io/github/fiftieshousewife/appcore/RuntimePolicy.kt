package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties

/**
 * An organisation's runtime policy: what every Java runtime it ships must contain, kept in one place
 * instead of in a repackaged JDK. A directory (or a zip of one) holding `runtime-policy.properties`
 * and, optionally, `certificates/` with CA certificates every runtime must trust:
 *
 * ```properties
 * id=acme-java-runtime
 * version=7
 * lockProperties=true                 # security.overridePropertiesFile=false
 * runtimeOptions=locked               # launchers refuse JAVA_TOOL_OPTIONS, APP_JAVA_OPTS, …
 * forbiddenModules=jdk.jdwp.agent,jdk.management.agent
 * forbiddenJvmArgs=-javaagent,-agentlib,-agentpath,-Djava.security.properties,-Djdk.tls.
 * forbiddenLibraries=pkg:maven/org.bouncycastle/bctls    # purl prefixes: no TLS stack of the app's own
 * set.networkaddress.cache.ttl=30     # a security property, exactly
 * append.jdk.tls.disabledAlgorithms=TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384
 * ```
 *
 * `customiseRuntime` applies it after the project's own settings, so it always wins;
 * `appVerifyPolicy` and `appInstall` check the actual files against it ([RuntimePolicyCheck]).
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
    val certificates: List<File>,
    /** SHA-256 over the policy file and every certificate, recorded in each runtime it is applied to. */
    val digest: String,
) {
    val label: String get() = "$id $version (sha256 ${digest.take(DIGEST_SHOWN)})"

    companion object {
        const val FILE = "runtime-policy.properties"
        private const val DIGEST_SHOWN = 12

        /** Loads the policy in [dir], which holds [FILE]. */
        fun load(dir: File): RuntimePolicy {
            val file = dir.resolve(FILE)
            if (!file.isFile) throw GradleException("$dir is not a runtime policy: it has no $FILE")
            val props = Properties().apply { file.inputStream().use(::load) }
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
                lockProperties = props.getProperty("lockProperties").toBoolean(),
                lockedOptions = props.getProperty("runtimeOptions") == RuntimeOptions.LOCKED.id,
                forbiddenModules = list("forbiddenModules"),
                forbiddenJvmArgs = list("forbiddenJvmArgs"),
                forbiddenLibraries = list("forbiddenLibraries"),
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
