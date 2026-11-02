package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.ChecksummedFile
import io.github.fiftieshousewife.appcore.RepositoryLogin
import io.github.fiftieshousewife.appcore.RpmLock
import io.github.fiftieshousewife.appcore.YumDownloads
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import javax.inject.Inject

/**
 * The RPMs [lockFile] pins for [arch], each checked against the lock's SHA-256. Fails, naming the task that
 * locks, when [packages] lists one the lock lacks: a build never resolves anything the lock doesn't name.
 */
@DisableCachingByDefault(because = "Downloads; each file is kept once in cacheDir")
abstract class DownloadImagePackages
@Inject
constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:Input abstract val packages: ListProperty<String>

    @get:Input abstract val arch: Property<String>

    /** The repositories by id, `[arch]` for the CPU: where each locked file is fetched from. */
    @get:Input abstract val repositories: MapProperty<String, String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    @get:Internal abstract val login: Property<RepositoryLogin>

    @get:Input
    @get:Optional
    abstract val loginFor: Property<String>

    @get:Internal abstract val cacheDir: DirectoryProperty

    /** The task that writes the lock, named in the failure. */
    @get:Internal abstract val lockTask: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val lock = RpmLock.parse(lockFile.get().asFile.readText())
        val missing = lock.missing(packages.get(), arch.get())
        if (missing.isNotEmpty()) {
            val lock = lockFile.get().asFile.name
            throw GradleException("$lock pins no ${missing.joinToString()} for ${arch.get()}: run ${lockTask.get()}")
        }
        val out = outputDir.get().asFile
        fs.delete { delete(out) }
        val downloads = YumDownloads(login.orNull, loginFor.orNull)
        val roots = repositories.get().mapValues { (_, url) -> url.replace("[arch]", arch.get()) }
        lock.packages(arch.get()).forEach { rpm ->
            val from = URI.create(rpm.urlIn(roots))
            val cached = downloads.cached(
                cacheDir.get().asFile,
                ChecksummedFile(from, rpm.fileName, "sha256", rpm.sha256),
            )
            YumDownloads.place(cached, out.resolve(rpm.fileName).toPath())
        }
    }
}
