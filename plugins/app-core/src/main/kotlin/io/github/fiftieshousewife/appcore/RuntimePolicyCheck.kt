package io.github.fiftieshousewife.appcore

import org.cyclonedx.model.Component
import org.cyclonedx.parsers.JsonParser
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.HexFormat
import java.util.Properties

/**
 * Checks an unpacked app against a [RuntimePolicy] by reading what is really there — a Java
 * runtime's effective `java.security`, its `cacerts` and module list, a Node.js app's CA bundle, and
 * the launcher — rather than trusting any record of what was done to it. Returns one line per
 * violation; empty means compliant.
 */
object RuntimePolicyCheck {
    private const val CACERTS_PASSWORD = "changeit"

    /** How every launcher starts the embedded JVM: the lines whose options are checked. */
    private val JAVA_STARTS = listOf("\"\$APP_HOME/runtime/bin/java\"", "\"%APP_HOME%\\runtime\\bin\\java.exe\"")

    /** How every launcher starts the embedded Node.js. */
    private val NODE_STARTS = listOf("\"\$APP_HOME/runtime/bin/node\"", "\"%APP_HOME%\\runtime\\node.exe\"")

    fun violations(policy: RuntimePolicy, app: File): List<String> {
        val runtime = app.resolve("runtime")
        val security = runtime.resolve("conf/security/java.security")
        val launchers = app.resolve("bin").listFiles().orEmpty().filter(File::isFile).map(File::readText)
        val ecosystem = when {
            security.isFile -> {
                val effective = Properties().apply { Files.newInputStream(security.toPath()).use(::load) }
                properties(policy, effective) + modules(policy, runtime) + trust(policy, runtime) +
                    forbiddenArgs(policy.forbiddenJvmArgs, starts(launchers, JAVA_STARTS), "JVM", "javaApp.jvmArgs")
            }

            runtime.resolve("bin/node").isFile || runtime.resolve("node.exe").isFile ->
                NodePolicyCheck.trust(policy, app, launchers) +
                    NodePolicyCheck.tls(policy, starts(launchers, NODE_STARTS)) +
                    forbiddenArgs(policy.forbiddenNodeArgs, starts(launchers, NODE_STARTS), "Node", "nodeApp.nodeArgs")

            else -> return listOf("${app.name} has no Java or Node.js runtime to check under runtime/")
        }
        return ecosystem + lockedOptions(policy, launchers) + libraries(policy, app)
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
            Files.newInputStream(file.toPath()).use(factory::generateCertificates).filterIsInstance<X509Certificate>()
                .filterNot { fingerprint(it) in trusted }
                .map { "cacerts does not trust ${it.subjectX500Principal.name} (${file.name})" }
        }
    }

    private fun lockedOptions(policy: RuntimePolicy, launchers: List<String>) =
        if (policy.lockedOptions && launchers.any { "runtimeOptions = locked" !in it }) {
            listOf("the launcher does not lock runtime options; set <javaApp|nodeApp>.runtimeOptions=locked")
        } else {
            emptyList()
        }

    private fun starts(launchers: List<String>, starts: List<String>) =
        launchers.flatMap(String::lines).filter { line -> starts.any { it in line } }

    private fun forbiddenArgs(forbidden: List<String>, starts: List<String>, runtime: String, setting: String) =
        forbidden.filter { arg -> starts.any { line -> arg in line } }
            .map { "the launcher passes a forbidden $runtime option starting $it ($setting)" }

    private fun libraries(policy: RuntimePolicy, app: File): List<String> {
        if (policy.forbiddenLibraries.isEmpty()) return emptyList()
        val sbom =
            app.resolve("META-INF/sbom.cdx.json").takeIf(File::isFile) ?: return listOf("the app has no SBOM to check")
        val purls = purls(JsonParser().parse(sbom).components.orEmpty())
        return policy.forbiddenLibraries.flatMap { prefix ->
            purls.filter { it.startsWith(prefix) }.map { "the app ships forbidden library $it" }
        }
    }

    private fun purls(components: List<Component>): List<String> =
        components.flatMap { listOfNotNull(it.purl) + purls(it.components.orEmpty()) }
}

/** The checks for a Node.js app: its CA bundle and the TLS floor its launcher sets. */
private object NodePolicyCheck {
    private const val CA_BUNDLE = "security/trusted-cas.pem"
    private val TLS_MIN = Regex("""--tls-min-v(1\.[0-3])""")

    /** The policy's certificates are in the CA bundle, and every launcher points Node at it. */
    fun trust(policy: RuntimePolicy, app: File, launchers: List<String>): List<String> {
        if (policy.certificates.isEmpty()) return emptyList()
        val bundle = app.resolve(CA_BUNDLE)
        if (!bundle.isFile) return listOf("the app has no $CA_BUNDLE holding the policy's certificates")
        val factory = CertificateFactory.getInstance("X.509")
        val trusted = Files.newInputStream(
            bundle.toPath(),
        ).use(factory::generateCertificates).map(::fingerprint).toSet()
        val missing = policy.certificates.flatMap { file ->
            Files.newInputStream(file.toPath()).use(factory::generateCertificates).filterIsInstance<X509Certificate>()
                .filterNot { fingerprint(it) in trusted }
                .map { "$CA_BUNDLE does not hold ${it.subjectX500Principal.name} (${file.name})" }
        }
        val unused = if (launchers.any { "NODE_EXTRA_CA_CERTS" !in it }) {
            listOf("the launcher does not set NODE_EXTRA_CA_CERTS to $CA_BUNDLE")
        } else {
            emptyList()
        }
        return missing + unused
    }

    fun tls(policy: RuntimePolicy, starts: List<String>): List<String> {
        val required = policy.tlsMinVersion ?: return emptyList()
        return starts.filter { line -> TLS_MIN.find(line)?.groupValues?.get(1).orEmpty() < required }
            .map { "the launcher lets Node accept TLS below $required; set nodeApp.security.tlsMinVersion" }
            .distinct()
    }
}

private fun fingerprint(certificate: java.security.cert.Certificate): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
