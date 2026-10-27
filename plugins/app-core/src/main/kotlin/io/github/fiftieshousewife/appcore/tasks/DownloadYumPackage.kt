package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.ProxySettings
import io.github.fiftieshousewife.appcore.RepositoryLogin
import io.github.fiftieshousewife.appcore.YumMetadata
import io.github.fiftieshousewife.appcore.YumPackage
import io.github.fiftieshousewife.appcore.YumWanted
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
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
import java.util.Locale

/**
 * Fetches one package from a YUM repository (Artifactory, Nexus, any `createrepo` tree): finds
 * [packageName] at [version] for [arch] in the repository's own metadata, downloads it with [login],
 * and checks it against the checksum the metadata records. Each package is kept once, by checksum,
 * in Gradle's user home ([cacheDir]), so every build and project on the machine shares it; the task
 * is up to date, and needs no network, once [rpm] is in place for the same inputs.
 */
@DisableCachingByDefault(because = "Downloads from a repository; the download itself is kept in cacheDir")
abstract class DownloadYumPackage : DefaultTask() {
    /** The repository root: the directory holding `repodata/`. `https:` or `file:`. */
    @get:Input abstract val repositoryUrl: Property<String>

    @get:Input abstract val packageName: Property<String>

    /** The package's version as the metadata gives it, without the release: `21.0.5`. */
    @get:Input abstract val version: Property<String>

    /** The CPU as RPM names it: `x86_64`, `aarch64`. */
    @get:Input abstract val arch: Property<String>

    @get:Internal abstract val login: Property<RepositoryLogin>

    @get:Internal abstract val cacheDir: DirectoryProperty

    @get:OutputFile abstract val rpm: RegularFileProperty

    @TaskAction
    fun download() {
        val root = URI.create(repositoryUrl.get().trimEnd('/') + "/")
        val client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .authenticator(ProxySettings.authenticator(null))
            .build()
        val open = { uri: URI -> open(client, uri) }
        val primary = open(root.resolve(YumMetadata.REPOMD)).use(YumMetadata::primaryLocation)
        val candidates = open(root.resolve(primary)).use { YumMetadata.packages(it, primary, packageName.get()) }
        val chosen = YumMetadata.select(
            candidates,
            YumWanted(packageName.get(), version.get(), arch.get()),
            root.toString(),
        )
        val cached = cached(chosen) { open(root.resolve(chosen.location)) }
        val out = rpm.get().asFile.toPath()
        Files.createDirectories(out.parent)
        Files.deleteIfExists(out)
        try {
            Files.createLink(out, cached.toPath())
        } catch (_: IOException) {
            Files.copy(cached.toPath(), out) // a filesystem without hard links, or another volume
        }
        logger.lifecycle("$chosen from $root")
    }

    /** The package in [cacheDir], downloaded and checked first if it isn't there yet. */
    private fun cached(chosen: YumPackage, download: () -> InputStream): File {
        val algorithm = when (chosen.checksumType.lowercase()) {
            "sha256" -> "SHA-256"

            "sha512" -> "SHA-512"

            "sha", "sha1" -> "SHA-1"

            else -> throw GradleException(
                "$chosen has no usable checksum in the repository metadata (${chosen.checksumType})",
            )
        }
        val file = cacheDir.get().asFile.resolve("${chosen.checksum}/${chosen.fileName}")
        if (file.isFile && digest(file.toPath(), algorithm) == chosen.checksum) return file
        file.parentFile.mkdirs()
        val partial = File(file.parentFile, "${file.name}.part")
        download().use { Files.copy(it, partial.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        val actual = digest(partial.toPath(), algorithm)
        if (actual != chosen.checksum) {
            partial.delete()
            throw GradleException(
                "${chosen.fileName} has ${chosen.checksumType} $actual, but the repository metadata says " +
                    "${chosen.checksum}: the download is corrupt or the package was replaced",
            )
        }
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return file
    }

    private fun open(client: HttpClient, uri: URI): InputStream {
        if (uri.scheme == "file") return Files.newInputStream(Path.of(uri))
        val request = HttpRequest.newBuilder(uri).timeout(DOWNLOAD_TIMEOUT).apply {
            // Sent up front: a repository that lets anyone list it may answer 404, not 401, without it.
            login.orNull?.let { header("Authorization", "Basic " + base64("${it.username}:${it.password}")) }
        }.build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in SUCCESS) {
            response.body().close()
            throw GradleException("$uri answered HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(30)
        val SUCCESS = 200..299

        fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

        fun digest(path: Path, algorithm: String): String {
            val md = MessageDigest.getInstance(algorithm)
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        }
    }
}
