package io.github.fiftieshousewife.appcore.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.inject.Inject

/**
 * `appPublishRpm`: uploads each RPM to a YUM repository with an HTTP PUT, the deploy API of
 * Artifactory and Nexus RPM repositories, which rebuild their `repodata/` themselves. Credentials
 * are the publication's own, `artifactoryUsername` and `artifactoryPassword`, read only when the
 * upload runs so they never reach the configuration cache.
 */
@DisableCachingByDefault(because = "Uploads to a remote repository")
abstract class PublishRpm
@Inject
constructor(private val providers: ProviderFactory) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val packages: ConfigurableFileCollection

    @get:Input abstract val repositoryUrl: Property<URI>

    @TaskAction
    fun publish() {
        val client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build()
        packages.files.sortedBy { it.name }.forEach { upload(client, it) }
    }

    private fun upload(client: HttpClient, rpm: File) {
        val target = URI.create("${repositoryUrl.get().toString().trimEnd('/')}/${rpm.name}")
        val request = HttpRequest.newBuilder(target).timeout(UPLOAD_TIMEOUT)
            .PUT(HttpRequest.BodyPublishers.ofFile(rpm.toPath()))
            .header("Content-Type", "application/x-rpm")
            .apply { authorization()?.let { header("Authorization", it) } }
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in SUCCESS) {
            val reason = "HTTP ${response.statusCode()} ${response.body()}"
            throw GradleException("Uploading ${rpm.name} to $target failed: $reason")
        }
        logger.lifecycle("Published ${rpm.name} to $target")
    }

    private fun authorization(): String? {
        val user = providers.gradleProperty("artifactoryUsername").orNull ?: return null
        val password = providers.gradleProperty("artifactoryPassword").orNull.orEmpty()
        return "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        val UPLOAD_TIMEOUT: Duration = Duration.ofMinutes(10)
        val SUCCESS = 200..299
    }
}
