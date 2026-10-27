package io.github.fiftieshousewife.appcore

import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.gradle.api.GradleException
import java.io.BufferedInputStream
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/** One package in a YUM repository's primary metadata: what dnf shows, and where the file is. */
data class YumPackage(
    val name: String,
    val version: String,
    val release: String,
    val arch: String,
    /** Relative to the repository root: `Packages/j/jdk-21_21.0.5_linux-x64_bin.rpm`. */
    val location: String,
    /** `sha256` (or, from old repositories, `sha` / `sha1`), and the file's digest in hex. */
    val checksumType: String,
    val checksum: String,
) {
    val fileName: String get() = location.substringAfterLast('/')
    override fun toString() = "$name-$version-$release.$arch"
}

/** The package a build wants: by name, version (without the release) and RPM CPU name. */
data class YumWanted(val name: String, val version: String, val arch: String) {
    override fun toString() = "$name $version for $arch"
}

/**
 * Reads a YUM repository's metadata, as Artifactory, Nexus and `createrepo` write it: `repodata/
 * repomd.xml` names the primary index, which lists every package with its version, CPU, location
 * and checksum. The primary index is streamed, so a repository of thousands of packages is never
 * held in memory.
 */
object YumMetadata {
    const val REPOMD = "repodata/repomd.xml"

    /** The primary index's location, from `repomd.xml`. */
    fun primaryLocation(repomd: InputStream): String {
        val xml = reader(repomd)
        var inPrimary = false
        while (xml.hasNext()) {
            if (xml.next() != XMLStreamConstants.START_ELEMENT) continue
            when (xml.localName) {
                "data" -> inPrimary = xml.getAttributeValue(null, TYPE) == "primary"
                LOCATION -> if (inPrimary) return xml.getAttributeValue(null, HREF)
            }
        }
        throw GradleException("$REPOMD lists no primary index; is this a YUM repository?")
    }

    /** Every package named [name] in the primary index, decompressed by its [fileName]'s extension. */
    fun packages(primary: InputStream, fileName: String, name: String): List<YumPackage> {
        val xml = reader(decompress(primary, fileName))
        val found = mutableListOf<YumPackage>()
        val fields = mutableMapOf<String, String>()
        while (xml.hasNext()) {
            val event = xml.next()
            if (event == XMLStreamConstants.START_ELEMENT) readField(xml, fields)
            if (event == XMLStreamConstants.END_ELEMENT && xml.localName == PACKAGE) {
                if (fields[NAME] == name) found += packageOf(fields)
                fields.clear()
            }
        }
        return found
    }

    /**
     * The package [wanted], its newest release when there are several. Fails naming what the
     * repository does hold, so a wrong name, version or CPU is plain.
     */
    fun select(packages: List<YumPackage>, wanted: YumWanted, repository: String): YumPackage {
        val matching = packages.filter { it.version == wanted.version && it.arch == wanted.arch }
        return matching.maxWithOrNull { a, b -> if (RuntimeSupport.isNewer(a.release, b.release)) 1 else -1 }
            ?: throw GradleException(
                "$repository has no $wanted. " +
                    if (packages.isEmpty()) {
                        "It has no package named ${wanted.name} at all."
                    } else {
                        "It has: ${packages.joinToString { "${it.version}-${it.release}.${it.arch}" }}"
                    },
            )
    }

    /** One of the primary index's fields for the current package, if [xml] is at one. */
    private fun readField(xml: XMLStreamReader, fields: MutableMap<String, String>) {
        if (xml.namespaceURI != COMMON) return
        when (xml.localName) {
            NAME, ARCH -> fields[xml.localName] = xml.elementText.trim()

            "version" -> {
                fields[VER] = xml.getAttributeValue(null, VER)
                fields[REL] = xml.getAttributeValue(null, REL)
            }

            CHECKSUM -> {
                fields[CHECKSUM_TYPE] = xml.getAttributeValue(null, TYPE)
                fields[CHECKSUM] = xml.elementText.trim()
            }

            LOCATION -> fields[HREF] = xml.getAttributeValue(null, HREF)
        }
    }

    private fun packageOf(fields: Map<String, String>) = YumPackage(
        name = fields.getValue(NAME),
        version = fields.getValue(VER),
        release = fields[REL].orEmpty(),
        arch = fields.getValue(ARCH),
        location = fields.getValue(HREF),
        checksumType = fields[CHECKSUM_TYPE].orEmpty(),
        checksum = fields[CHECKSUM].orEmpty().lowercase(),
    )

    /** gzip (Artifactory, Nexus), xz, bzip2 or zstd (createrepo_c on RHEL 9), or plain XML. */
    private fun decompress(input: InputStream, fileName: String): InputStream {
        val buffered = BufferedInputStream(input)
        return if (fileName.endsWith(
                ".xml",
            )
        ) {
            buffered
        } else {
            CompressorStreamFactory().createCompressorInputStream(buffered)
        }
    }

    private fun reader(input: InputStream): XMLStreamReader = XMLInputFactory.newFactory().apply {
        // Repository metadata is data: no DTDs, no external entities.
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }.createXMLStreamReader(input)

    private const val PACKAGE = "package"
    private const val NAME = "name"
    private const val ARCH = "arch"
    private const val LOCATION = "location"
    private const val HREF = "href"
    private const val TYPE = "type"
    private const val CHECKSUM_TYPE = "checksumType"
    private const val CHECKSUM = "checksum"
    private const val VER = "ver"
    private const val REL = "rel"

    /** The namespace of the primary index's package fields (not `rpm:`'s, which reuse some names). */
    private const val COMMON = "http://linux.duke.edu/metadata/common"
}
