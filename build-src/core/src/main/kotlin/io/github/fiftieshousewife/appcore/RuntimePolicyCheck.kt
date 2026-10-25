package io.github.fiftieshousewife.appcore

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.HexFormat
import java.util.Properties

/**
 * Checks an unpacked app against a [RuntimePolicy] by reading what is really there — the runtime's
 * effective `java.security`, its `cacerts`, its module list, and the launcher — rather than trusting
 * any record of what was done to it. Returns one line per violation; empty means compliant.
 */
object RuntimePolicyCheck {
    private const val CACERTS_PASSWORD = "changeit"

    fun violations(policy: RuntimePolicy, app: File): List<String> {
        val runtime = app.resolve("runtime")
        val security = runtime.resolve("conf/security/java.security")
        if (!security.isFile) {
            return listOf(
                "${app.name} has no Java runtime to check (runtime/conf/security/java.security)",
            )
        }
        val effective = Properties().apply { security.inputStream().use(::load) }
        return properties(policy, effective) + modules(policy, runtime) + trust(policy, runtime) +
            launcher(policy, app) + libraries(policy, app)
    }

    private fun properties(policy: RuntimePolicy, effective: Properties): List<String> {
        val set = policy.set.filter { (k, v) -> effective.getProperty(k)?.trim() != v }
            .map { (k, v) -> "security property $k is '${effective.getProperty(k)}', the policy requires '$v'" }
        val appended = policy.append.flatMap { (k, values) ->
            val present = effective.getProperty(k).orEmpty().split(',').map(String::trim).toSet()
            values.filterNot(present::contains).map { "$k does not include $it" }
        }
        val lock = if (policy.lockProperties &&
            effective.getProperty("security.overridePropertiesFile")?.trim() != "false"
        ) {
            listOf("security.overridePropertiesFile is not false: java.security can be overridden at runtime")
        } else {
            emptyList()
        }
        return set + appended + lock
    }

    private fun modules(policy: RuntimePolicy, runtime: File): List<String> {
        val release = runtime.resolve("release").takeIf(File::isFile)?.readText().orEmpty()
        val modules = Regex("""MODULES="([^"]*)"""").find(release)?.groupValues?.get(1)?.split(' ').orEmpty().toSet()
        return policy.forbiddenModules.filter(modules::contains).map { "the runtime contains forbidden module $it" }
    }

    private fun trust(policy: RuntimePolicy, runtime: File): List<String> {
        if (policy.certificates.isEmpty()) return emptyList()
        val cacerts = runtime.resolve("lib/security/cacerts")
        val trusted = KeyStore.getInstance(cacerts, CACERTS_PASSWORD.toCharArray()).let { store ->
            store.aliases().toList().mapNotNull { store.getCertificate(it) }.map(::fingerprint).toSet()
        }
        val factory = CertificateFactory.getInstance("X.509")
        return policy.certificates.flatMap { file ->
            file.inputStream().use(factory::generateCertificates).filterIsInstance<X509Certificate>()
                .filterNot { fingerprint(it) in trusted }
                .map { "cacerts does not trust ${it.subjectX500Principal.name} (${file.name})" }
        }
    }

    private fun launcher(policy: RuntimePolicy, app: File): List<String> {
        val launchers = app.resolve("bin").listFiles().orEmpty().filter(File::isFile).map(File::readText)
        val locked = if (policy.lockedOptions && launchers.any { "runtimeOptions = locked" !in it }) {
            listOf("the launcher does not lock runtime options; set runtimeOptions(\"locked\")")
        } else {
            emptyList()
        }
        val starts = launchers.flatMap(String::lines).filter { "runtime" in it && ("java" in it) && "-cp" in it }
        val forbidden = policy.forbiddenJvmArgs.filter { arg -> starts.any { line -> arg in line } }
            .map { "the launcher passes a forbidden JVM option starting $it (javaApp.jvmArgs)" }
        return locked + forbidden
    }

    private fun libraries(policy: RuntimePolicy, app: File): List<String> {
        if (policy.forbiddenLibraries.isEmpty()) return emptyList()
        val sbom = app.resolve("META-INF/sbom.cdx.json").takeIf(File::isFile)?.readText().orEmpty()
        val purls = Regex(""""purl"\s*:\s*"([^"]+)"""").findAll(sbom).map { it.groupValues[1] }.toList()
        return policy.forbiddenLibraries.flatMap { prefix ->
            purls.filter { it.startsWith(prefix) }.map { "the app ships forbidden library $it" }
        }
    }

    private fun fingerprint(certificate: java.security.cert.Certificate): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
}
