package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

/**
 * Reads YUM repositories and fetches their packages, for the JDK RPMs and an image's RPMs alike: through the JVM's
 * proxy, with a login sent only where [login] is meant for, and each file kept once, by checksum, in a cache shared
 * by every build on the machine.
 */
internal class YumDownloads(private val login: RepositoryLogin?, private val loginFor: String?) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(ProxySelector.getDefault())
        .authenticator(ProxySettings.authenticator(null))
        .build()

    fun open(uri: URI): InputStream {
        if (uri.scheme == "file") return Files.newInputStream(Path.of(uri))
        val request = HttpRequest.newBuilder(uri).timeout(DOWNLOAD_TIMEOUT).apply {
            // Sent up front: a repository that lets anyone list it may answer 404, not 401, without it.
            login?.takeIf { loginFor == null || uri.toString().startsWith(loginFor) }
                ?.let { header("Authorization", "Basic " + base64("${it.username}:${it.password}")) }
        }.build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in SUCCESS) {
            response.body().close()
            throw GradleException("$uri answered HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    /** Every package named [name] in the repository at [root]. */
    fun packages(root: URI, name: String): List<YumPackage> {
        val primary = open(root.resolve(YumMetadata.REPOMD)).use(YumMetadata::primaryLocation)
        return open(root.resolve(primary)).use { YumMetadata.packages(it, primary, name) }
    }

    /** [wanted] in [cacheDir] under its checksum, downloaded and checked first if it isn't there. */
    fun cached(cacheDir: File, wanted: ChecksummedFile): File {
        val fileName = wanted.fileName
        val checksumType = wanted.checksumType
        val checksum = wanted.checksum
        val algorithm = ALGORITHMS[checksumType.lowercase()]
            ?: throw GradleException("$fileName has no usable checksum in the repository metadata ($checksumType)")
        val file = cacheDir.resolve("$checksum/$fileName")
        if (file.isFile && digest(file.toPath(), algorithm) == checksum) return file
        file.parentFile.mkdirs()
        val partial = File(file.parentFile, "${file.name}.part")
        open(wanted.uri).use { Files.copy(it, partial.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        val actual = digest(partial.toPath(), algorithm)
        if (actual != checksum) {
            partial.delete()
            throw GradleException(
                "$fileName has $checksumType $actual, but $checksum was expected: the download is corrupt or the " +
                    "package was replaced",
            )
        }
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return file
    }

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(30)
        private val SUCCESS = 200..299
        private val ALGORITHMS =
            mapOf("sha256" to "SHA-256", "sha512" to "SHA-512", "sha" to "SHA-1", "sha1" to "SHA-1")

        /** A hard link to the cached file where the file system allows one, else a copy. */
        fun place(cached: File, out: Path) {
            Files.createDirectories(out.parent)
            Files.deleteIfExists(out)
            try {
                Files.createLink(out, cached.toPath())
            } catch (_: IOException) {
                Files.copy(cached.toPath(), out)
            }
        }

        private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

        private fun digest(path: Path, algorithm: String): String {
            val md = MessageDigest.getInstance(algorithm)
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(1 shl 16)
                generateSequence { input.read(buffer).takeIf { it >= 0 } }.forEach { md.update(buffer, 0, it) }
            }
            return HexFormat.of().formatHex(md.digest())
        }
    }
}

/** A file in a YUM repository, and the checksum its metadata or a lock gives for it. */
internal data class ChecksummedFile(val uri: URI, val fileName: String, val checksumType: String, val checksum: String)
