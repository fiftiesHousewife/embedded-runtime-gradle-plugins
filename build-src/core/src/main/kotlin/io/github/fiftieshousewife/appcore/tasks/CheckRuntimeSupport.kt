package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.Ecosystem
import io.github.fiftieshousewife.appcore.EmbeddedRuntime
import io.github.fiftieshousewife.appcore.ProxySettings
import io.github.fiftieshousewife.appcore.RuntimeSupport
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `checkRuntimeSupport`: fails when the embedded runtime's release line has reached end of life,
 * and warns when it will within [warningDays] or when a newer patch is out. Reads endoflife.date,
 * or the mirror or copy of it at [apiUrl]. Not part of `check`: the answer changes with the date, so run
 * it on a schedule.
 */
@UntrackedTask(because = "the answer depends on today's date and on endoflife.date")
abstract class CheckRuntimeSupport : DefaultTask() {
    @get:Input abstract val runtime: Property<EmbeddedRuntime>

    /** The API's products URL, `https:` or `file:`; `<apiUrl><product>` is read. */
    @get:Input abstract val apiUrl: Property<String>

    @get:Input abstract val warningDays: Property<Int>

    /** Report an end-of-life runtime as a warning instead of failing. */
    @get:Input abstract val allowEndOfLife: Property<Boolean>

    @TaskAction
    fun check() {
        val runtime = runtime.get()
        val url = URI.create(apiUrl.get().trimEnd('/') + "/" + runtime.product)
        val releases = RuntimeSupport.parse(if (url.scheme == "file") Files.readString(Path.of(url)) else fetch(url))
        // The date where endoflife.date's dates are meant: the same everywhere a build runs.
        val assessment = RuntimeSupport.assess(runtime, releases, LocalDate.now(ZoneOffset.UTC), warningDays.get())
        logger.lifecycle(assessment.summary)
        assessment.warnings.forEach { logger.warn("Warning: $it") }
        if (assessment.problems.isNotEmpty()) {
            val message = assessment.problems.joinToString("\n")
            if (allowEndOfLife.get()) logger.warn("Warning: $message") else throw GradleException(message)
        }
    }

    private fun fetch(url: URI): String {
        val client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .authenticator(ProxySettings.authenticator(null))
            .build()
        val request = HttpRequest.newBuilder(url).timeout(TIMEOUT).header("Accept", "application/json").build()
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                throw GradleException("Could not read $url (${e.message}); set $MIRROR_HINT", e)
            }
        if (response.statusCode() != HTTP_OK) {
            throw GradleException("$url answered HTTP ${response.statusCode()}; set $MIRROR_HINT")
        }
        return response.body()
    }

    companion object {
        /** Registers `checkRuntimeSupport` for the runtime [ecosystem] embeds. */
        fun register(project: Project, ecosystem: Ecosystem, embedded: Provider<EmbeddedRuntime>) {
            val providers = project.providers
            fun setting(name: String) = providers.gradleProperty(ecosystem.property(name))
            project.tasks.register("checkRuntimeSupport", CheckRuntimeSupport::class.java) {
                group = "verification"
                description =
                    "Fails if the embedded runtime has reached end of life; warns if it soon will or is behind"
                runtime.set(embedded)
                apiUrl.set(setting("runtimeSupportUrl").orElse(RuntimeSupport.DEFAULT_URL))
                warningDays.set(
                    setting("endOfLifeWarningDays").map(String::toInt).orElse(RuntimeSupport.DEFAULT_WARNING_DAYS),
                )
                allowEndOfLife.set(setting("allowEndOfLife").map(String::toBoolean).orElse(false))
            }
        }

        private val TIMEOUT: Duration = Duration.ofSeconds(30)
        private const val HTTP_OK = 200
        private const val MIRROR_HINT =
            "<javaApp|nodeApp>.runtimeSupportUrl to a mirror of https://endoflife.date/api/v1/products/"
    }
}
