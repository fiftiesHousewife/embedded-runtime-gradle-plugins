package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.LockedRpm
import io.github.fiftieshousewife.appcore.RepositoryLogin
import io.github.fiftieshousewife.appcore.RpmLock
import io.github.fiftieshousewife.appcore.RpmVersions
import io.github.fiftieshousewife.appcore.YumDownloads
import io.github.fiftieshousewife.appcore.YumPackage
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.nio.file.Files

/**
 * Pins each of [packages] for each of [arches] to the newest build its repositories hold (or a `noarch` one),
 * and writes [lockFile]. Run it to take updates, as a lockfile is meant to be; builds only read the lock.
 */
@DisableCachingByDefault(because = "Reads what the repositories hold now")
abstract class LockImagePackages : DefaultTask() {
    @get:Input abstract val packages: ListProperty<String>

    /** As RPM names CPUs: `x86_64`, `aarch64`. */
    @get:Input abstract val arches: ListProperty<String>

    @get:Input abstract val repositories: MapProperty<String, String>

    @get:Internal abstract val login: Property<RepositoryLogin>

    /** The binary repository's URL: only requests under it carry [login]. */
    @get:Input
    @get:Optional
    abstract val loginFor: Property<String>

    @get:OutputFile abstract val lockFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun lock() {
        val downloads = YumDownloads(login.orNull, loginFor.orNull)
        val lock = RpmLock(arches.get().associateWith { arch -> packages.get().map { newest(downloads, it, arch) } })
        Files.writeString(lockFile.get().asFile.toPath(), lock.render())
        lock.arches.forEach { (arch, rpms) ->
            logger.lifecycle("$arch: ${rpms.joinToString { "${it.name}-${it.evr}" }}")
        }
    }

    private fun newest(downloads: YumDownloads, name: String, arch: String): LockedRpm {
        val found = repositories.get().flatMap { (id, url) ->
            val root = URI.create(url.replace(ARCH, arch).trimEnd('/') + "/")
            downloads.packages(root, name).filter { it.arch == arch || it.arch == NOARCH }
                .map { Triple(id, root, it) }
        }
        val (id, root, rpm) = found.maxWithOrNull { a, b -> RpmVersions.compare(a.third.evr, b.third.evr) }
            ?: throw GradleException("No $name for $arch in ${repositories.get().keys.joinToString()}")
        return locked(id, root, rpm)
    }

    private fun locked(id: String, root: URI, rpm: YumPackage): LockedRpm {
        if (!rpm.checksumType.equals("sha256", ignoreCase = true)) {
            throw GradleException("$rpm is listed with a ${rpm.checksumType} checksum; the lock records SHA-256")
        }
        return LockedRpm(
            rpm.name,
            rpm.evr,
            root.resolve(rpm.location).toString(),
            rpm.checksum,
            rpm.size,
            id,
            rpm.sourceRpm,
        )
    }

    private companion object {
        const val ARCH = "[arch]"
        const val NOARCH = "noarch"
    }
}
