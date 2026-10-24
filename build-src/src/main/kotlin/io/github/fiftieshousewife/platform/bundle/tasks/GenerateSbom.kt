package io.github.fiftieshousewife.platform.bundle.tasks

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Writes a CycloneDX 1.5 SBOM to `META-INF/sbom.cdx.json` and alongside the published archive.
 *
 * The bundled runtime is listed as a `platform` component. Vulnerability scanners cannot see a
 * bundled JRE or Node binary through the OS package database, so without this entry a vulnerable
 * runtime is invisible.
 *
 * Java libraries come from the resolved `runtimeClasspath`; Node packages from npm's hidden lockfile
 * (`node_modules/.package-lock.json`) of the production install, which lists exactly what was
 * installed for the target platform.
 */
@CacheableTask
abstract class GenerateSbom : DefaultTask() {
    @get:Input abstract val bundleGroup: Property<String>

    @get:Input abstract val appName: Property<String>

    @get:Input abstract val version: Property<String>

    @get:Input abstract val platform: Property<String>

    @get:Input abstract val runtimeName: Property<String>

    @get:Input abstract val runtimeVersion: Property<String>

    @get:Input abstract val runtimePurl: Property<String>

    /** `group:module:version:fileName` for each resolved Maven artifact. */
    @get:Input abstract val mavenComponents: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val mavenFiles: ConfigurableFileCollection

    @get:InputDirectory @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nodeModules: DirectoryProperty

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val app = "pkg:maven/${bundleGroup.get()}/${appName.get()}@${version.get()}?classifier=${platform.get()}"
        val components = mutableListOf<Map<String, Any>>()
        components +=
            linkedMapOf(
                "type" to "platform",
                "bom-ref" to runtimePurl.get(),
                "name" to runtimeName.get(),
                "version" to runtimeVersion.get(),
                "purl" to runtimePurl.get(),
            )
        components += mavenLibraries()
        components += npmPackages()

        val bom =
            linkedMapOf(
                "bomFormat" to "CycloneDX",
                "specVersion" to "1.5",
                "serialNumber" to "urn:uuid:" + UUID.nameUUIDFromBytes(app.toByteArray()),
                "version" to 1,
                "metadata" to
                    linkedMapOf(
                        "tools" to
                            linkedMapOf(
                                "components" to
                                    listOf(
                                        linkedMapOf("type" to "application", "name" to "io.github.fiftieshousewife.platform"),
                                    ),
                            ),
                        "component" to
                            linkedMapOf(
                                "type" to "application",
                                "bom-ref" to app,
                                "group" to bundleGroup.get(),
                                "name" to appName.get(),
                                "version" to version.get(),
                                "purl" to app,
                            ),
                    ),
                "components" to components.sortedBy { it["bom-ref"] as String },
            )
        outputFile.get().asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(bom)) + "\n")
    }

    private fun mavenLibraries(): List<Map<String, Any>> {
        val byName = mavenFiles.files.associateBy { it.name }
        return mavenComponents
            .get()
            .map { entry ->
                val parts = entry.split(":", limit = 4)
                val (g, m, v) = parts
                val fileName = parts.last()
                val purl = "pkg:maven/$g/$m@$v"
                linkedMapOf<String, Any>(
                    "type" to "library",
                    "bom-ref" to purl,
                    "group" to g,
                    "name" to m,
                    "version" to v,
                    "purl" to purl,
                ).also { c -> byName[fileName]?.let { c["hashes"] = listOf(hash("SHA-256", sha256(it))) } }
            }.distinctBy { it["bom-ref"] }
    }

    @Suppress("UNCHECKED_CAST")
    private fun npmPackages(): List<Map<String, Any>> {
        val lock =
            nodeModules.orNull
                ?.asFile
                ?.resolve(".package-lock.json")
                ?.takeIf { it.isFile } ?: return emptyList()
        val packages =
            (JsonSlurper().parse(lock) as Map<String, Any?>)["packages"] as? Map<String, Map<String, Any?>>
                ?: return emptyList()
        return packages
            .filterKeys { it.contains("node_modules/") }
            .mapNotNull { (path, meta) ->
                if (meta["link"] == true) return@mapNotNull null
                val name = (meta["name"] as? String) ?: path.substringAfterLast("node_modules/")
                val version = meta["version"] as? String ?: return@mapNotNull null
                val purl = "pkg:npm/" + name.replace("@", "%40") + "@" + version
                linkedMapOf<String, Any>(
                    "type" to "library",
                    "bom-ref" to purl,
                    "name" to name,
                    "version" to version,
                    "purl" to purl,
                ).also { c ->
                    (meta["integrity"] as? String)?.let(::integrityHash)?.let { c["hashes"] = listOf(it) }
                }
            }.distinctBy { it["bom-ref"] }
    }

    private fun integrityHash(sri: String): Map<String, String>? {
        val (alg, b64) =
            sri
                .split(" ")
                .first()
                .split("-", limit = 2)
                .takeIf { it.size == 2 } ?: return null
        val name =
            when (alg) {
                "sha512" -> "SHA-512"
                "sha384" -> "SHA-384"
                "sha256" -> "SHA-256"
                "sha1" -> "SHA-1"
                else -> return null
            }
        return hash(name, Base64.getDecoder().decode(b64).toHex())
    }

    private fun hash(
        alg: String,
        content: String,
    ) = linkedMapOf("alg" to alg, "content" to content)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { it.copyTo(OutputStream.nullOutputStream()) }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
