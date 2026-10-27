package io.github.fiftieshousewife.appcore.tasks

import org.cyclonedx.model.Component
import org.cyclonedx.model.Hash
import java.util.Base64
import java.util.HexFormat

/**
 * The packages npm's hidden lockfile (`node_modules/.package-lock.json`) says were installed, as
 * SBOM components.
 */
internal object NpmLockfile {
    private const val NODE_MODULES = "node_modules/"

    /** Every installed package in npm's hidden lockfile, skipping workspace links. */
    fun npmPackages(lockfile: Map<*, *>): List<Component> = (lockfile["packages"] as? Map<*, *>)
        .orEmpty()
        .filterKeys { it.toString().contains(NODE_MODULES) }
        .mapNotNull { (path, meta) -> npmPackage(path.toString(), meta as Map<*, *>) }
        .distinctBy { it.bomRef }

    /** A Subresource Integrity value (`sha512-<base64>`) as a CycloneDX hash, or null for an unknown algorithm. */
    fun integrityHash(sri: String): Hash? {
        val parts = sri.substringBefore(' ').split("-", limit = 2)
        val algorithm = SRI_ALGORITHMS[parts.first()]
        if (parts.size != 2 || algorithm == null) return null
        return Hash(algorithm, HexFormat.of().formatHex(Base64.getDecoder().decode(parts[1])))
    }

    private fun npmPackage(path: String, meta: Map<*, *>): Component? {
        val version = meta["version"] as? String
        if (meta["link"] == true || version == null) return null
        val fullName = meta["name"] as? String ?: path.substringAfterLast(NODE_MODULES)
        val scope = fullName.takeIf { it.startsWith("@") }?.substringBefore('/')
        val purl = CycloneDx.purl("npm", CycloneDx.Coordinates(scope, fullName.substringAfter('/', fullName), version))
        return CycloneDx.component(Component.Type.LIBRARY, purl, CycloneDx.Coordinates(null, fullName, version)).apply {
            (meta["integrity"] as? String)?.let(::integrityHash)?.let { hashes = listOf(it) }
        }
    }

    private val SRI_ALGORITHMS =
        mapOf(
            "sha512" to Hash.Algorithm.SHA_512,
            "sha384" to Hash.Algorithm.SHA_384,
            "sha256" to Hash.Algorithm.SHA_256,
            "sha1" to Hash.Algorithm.SHA1,
        )
}
