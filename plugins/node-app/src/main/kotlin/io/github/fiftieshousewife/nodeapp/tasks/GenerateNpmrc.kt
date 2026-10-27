package io.github.fiftieshousewife.nodeapp.tasks

import io.github.fiftieshousewife.appcore.ProxySettings
import io.github.fiftieshousewife.appcore.RepositoryLogin
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/** Owner-readable only and never cached, because it carries the binary repository's credentials. */
@UntrackedTask(because = "Contains credentials that must not be cached or fingerprinted")
abstract class GenerateNpmrc : DefaultTask() {
    @get:Input abstract val registry: Property<String>

    @get:Input @get:Optional
    abstract val caFile: Property<String>

    @get:Internal abstract val credentials: Property<RepositoryLogin>

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val user = credentials.orNull?.let { NpmUser(it.username, it.password) }
        val path = outputFile.get().asFile.toPath()
        Files.createDirectories(path.parent)
        val lines = Npmrc.lines(registry.get(), caFile.orNull, user) + ProxySettings.fromSystem().npmrcLines()
        Files.writeString(path, lines.joinToString("\n", postfix = "\n"))
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }
}

internal data class NpmUser(val name: String, val password: String)

internal object Npmrc {
    /** `replace-registry-host=always` fetches a lockfile's registry.npmjs.org tarballs from [registry] instead. */
    fun lines(registry: String, caFile: String?, user: NpmUser?): List<String> {
        val normalised = registry.trimEnd('/') + "/"
        return listOfNotNull("registry=$normalised", "replace-registry-host=always", caFile?.let { "cafile=$it" }) +
            listOfNotNull(user?.let { auth(normalised, it) })
    }

    /** npm scopes `_auth` to the registry's host and path, so it is sent there and nowhere else. */
    private fun auth(registry: String, user: NpmUser): String {
        val uri = URI.create(registry)
        return "//${uri.authority}${uri.path}:_auth=" +
            Base64.getEncoder().encodeToString("${user.name}:${user.password}".toByteArray())
    }
}
