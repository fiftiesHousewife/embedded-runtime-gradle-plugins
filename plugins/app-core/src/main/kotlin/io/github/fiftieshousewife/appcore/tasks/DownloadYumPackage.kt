package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.ChecksummedFile
import io.github.fiftieshousewife.appcore.RepositoryLogin
import io.github.fiftieshousewife.appcore.YumDownloads
import io.github.fiftieshousewife.appcore.YumMetadata
import io.github.fiftieshousewife.appcore.YumWanted
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI

/**
 * Fetches [packageName] at [version] for [arch] from a YUM repository (Artifactory, Nexus, any `createrepo`
 * tree), checked against its metadata's checksum and kept once in [cacheDir] for every build on the machine.
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
        val downloads = YumDownloads(login.orNull, null)
        val chosen = YumMetadata.select(
            downloads.packages(root, packageName.get()),
            YumWanted(packageName.get(), version.get(), arch.get()),
            root.toString(),
        )
        val cached = downloads.cached(
            cacheDir.get().asFile,
            ChecksummedFile(root.resolve(chosen.location), chosen.fileName, chosen.checksumType, chosen.checksum),
        )
        YumDownloads.place(cached, rpm.get().asFile.toPath())
        logger.lifecycle("$chosen from $root")
    }
}
