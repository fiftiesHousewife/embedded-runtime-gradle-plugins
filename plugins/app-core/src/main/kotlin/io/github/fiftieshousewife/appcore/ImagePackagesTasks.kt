package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.DownloadImagePackages
import io.github.fiftieshousewife.appcore.tasks.LockImagePackages
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/** `appImagePackagesLock`, and for each Linux CPU the locked RPMs, for `appDockerContext`'s `packages/<cpu>/`. */
internal class ImagePackagesTasks(private val project: Project, private val wiring: AppWiring) {
    private val image = wiring.extension.image

    /** `image { packages(…) }`, for the project's own Dockerfiles, and every generated image's `install(…)`. */
    private val packages: Provider<List<String>> =
        image.packages.zip(wiring.generatedImages.packages()) { own, images -> (own + images).distinct() }

    fun applyConventions() {
        val ubi = wiring.binaryRepo?.ubiUrl?.toString() ?: PUBLIC_UBI
        image.packages.convention(emptyList())
        image.repositories.convention(
            mapOf(
                "ubi-9-baseos-rpms" to "$ubi[arch]/baseos/os/",
                "ubi-9-appstream-rpms" to "$ubi[arch]/appstream/os/",
            ),
        )
        image.lockFile.convention(project.layout.projectDirectory.file(LOCK_FILE))
    }

    fun registerLock(targets: List<Target>) = project.tasks.register(LOCK_TASK, LockImagePackages::class.java) {
        group = wiring.taskGroup
        description = "Pins the image's RPMs (image { packages(…) }) for each Linux CPU in ${LOCK_FILE}"
        packages.set(this@ImagePackagesTasks.packages)
        arches.set(targets.map { it.arch.rpm })
        repositories.set(image.repositories)
        login(this.login, this.loginFor)
        lockFile.set(image.lockFile)
    }

    /** This CPU's locked RPMs, or none when the image adds no packages. */
    fun download(target: Target): Provider<Directory> {
        val task = project.tasks.register(
            "appImagePackages${target.taskSuffix}",
            DownloadImagePackages::class.java,
        ) {
            group = wiring.taskGroup
            description = "Fetches the RPMs ${LOCK_FILE} pins for ${target.id}, checked against it"
            onlyIf("some image installs packages") { (it as DownloadImagePackages).packages.get().isNotEmpty() }
            packages.set(this@ImagePackagesTasks.packages)
            arch.set(target.arch.rpm)
            repositories.set(image.repositories)
            lockFile.set(image.lockFile)
            login(this.login, this.loginFor)
            cacheDir.set(project.gradle.gradleUserHomeDir.resolve("caches/embedded-runtime/yum"))
            lockTask.set(LOCK_TASK)
            outputDir.set(wiring.workDir.map { it.dir("image-packages/${target.id}") })
        }
        return task.flatMap { it.outputDir }
    }

    private fun login(login: Property<RepositoryLogin>, loginFor: Property<String>) {
        wiring.binaryRepo?.let {
            login.set(it.login(project.providers))
            loginFor.set(it.baseUrl)
        }
    }

    companion object {
        const val LOCK_TASK = "appImagePackagesLock"
        const val LOCK_FILE = "rpms.lock.yaml"
        private const val PUBLIC_UBI = "https://cdn-ubi.redhat.com/content/public/ubi/dist/ubi9/9/"
    }
}
