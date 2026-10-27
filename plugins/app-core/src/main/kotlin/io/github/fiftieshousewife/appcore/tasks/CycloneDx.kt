package io.github.fiftieshousewife.appcore.tasks

import com.github.packageurl.PackageURLBuilder
import org.cyclonedx.Version
import org.cyclonedx.generators.BomGeneratorFactory
import org.cyclonedx.model.Bom
import org.cyclonedx.model.Component
import org.cyclonedx.model.Hash
import org.cyclonedx.model.Metadata
import org.cyclonedx.model.metadata.ToolInformation
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * The components [GenerateSbom] lists, built with the CycloneDX reference model and package-url's
 * purl builder, and the CycloneDX 1.5 JSON they make.
 */
internal object CycloneDx {
    private const val TOOL = "io.github.fiftieshousewife.appcore"

    /** What names one component; [group] is the Maven group or npm scope, where there is one. */
    data class Coordinates(val group: String?, val name: String, val version: String)

    /** The app the SBOM describes; its purl names the published archive by its target classifier. */
    data class App(val coordinates: Coordinates, val target: String)

    /** The whole document as JSON, components sorted so a rebuild is byte-identical. */
    fun json(app: App, components: List<Component>): String {
        val subject = appComponent(app)
        val bom =
            Bom().apply {
                serialNumber = "urn:uuid:" + UUID.nameUUIDFromBytes(subject.purl.toByteArray())
                metadata = metadata(subject)
                this.components = components.sortedBy { it.bomRef }
            }
        return BomGeneratorFactory.createJson(Version.VERSION_15, bom).toJsonString()
    }

    /** The embedded runtime, as a `platform` component: scanners cannot see it through an OS package database. */
    fun runtime(name: String, version: String): Component {
        val coordinates = Coordinates(null, name, version)
        return component(Component.Type.PLATFORM, purl("generic", coordinates), coordinates)
    }

    /** One resolved Maven library from `group:module:version:fileName`, hashed when its file is known. */
    fun mavenLibrary(coordinates: String, filesByName: Map<String, File>): Component {
        val parts = coordinates.split(":", limit = 4)
        val maven = Coordinates(parts[0], parts[1], parts[2])
        return component(Component.Type.LIBRARY, purl("maven", maven), maven).apply {
            filesByName[parts.last()]?.let { hashes = listOf(Hash(Hash.Algorithm.SHA_256, sha256(it))) }
        }
    }

    /**
     * The archive's own purl: a Maven one with the target as its classifier, or a generic one for an
     * app with no Maven group, which package-url (rightly) refuses as a Maven coordinate.
     */
    private fun appComponent(app: App): Component {
        val group = app.coordinates.group?.takeIf(String::isNotBlank)
        val purl =
            PackageURLBuilder
                .aPackageURL()
                .withType(if (group == null) "generic" else "maven")
                .withNamespace(group)
                .withName(app.coordinates.name)
                .withVersion(app.coordinates.version)
                .withQualifier("classifier", app.target)
                .build()
                .canonicalize()
        return component(Component.Type.APPLICATION, purl, app.coordinates.copy(group = group))
    }

    /** No timestamp (the library stamps the current time by default): a rebuild must be byte-identical. */
    private fun metadata(subject: Component) = Metadata().apply {
        timestamp = null
        component = subject
        toolChoice = ToolInformation().apply { components = listOf(tool()) }
    }

    private fun tool() = Component().apply {
        type = Component.Type.APPLICATION
        name = TOOL
    }

    fun component(type: Component.Type, purl: String, coordinates: Coordinates) = Component().apply {
        this.type = type
        this.bomRef = purl
        this.purl = purl
        this.group = coordinates.group
        this.name = coordinates.name
        this.version = coordinates.version
    }

    fun purl(type: String, coordinates: Coordinates): String = PackageURLBuilder
        .aPackageURL()
        .withType(type)
        .withNamespace(coordinates.group)
        .withName(coordinates.name)
        .withVersion(coordinates.version)
        .build()
        .canonicalize()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(file.toPath()), digest).use {
            it.transferTo(OutputStream.nullOutputStream())
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
