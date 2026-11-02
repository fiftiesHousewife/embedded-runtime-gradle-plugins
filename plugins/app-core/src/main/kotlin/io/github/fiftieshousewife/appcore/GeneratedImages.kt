package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.WriteTextFile
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/** What a base image lacks that the ecosystem's runtime can't start without, so nobody has to know to install it. */
internal object ImageNeeds {
    private const val MICRO = "ubi-micro"
    private const val ZLIB = "zlib"

    /** The java launcher's `libjli.so` loads `libz.so.1`, which micro leaves out; a mirror keeps the image's name. */
    fun packages(java: Boolean, base: String): List<String> = if (java && MICRO in base) listOf(ZLIB) else emptyList()
}

/** `images { register("<name>") { … } }`: each a `Dockerfile.<name>` in the context, written from its [ImageSpec]. */
internal class GeneratedImages(private val project: Project, private val wiring: AppWiring) {
    private val images = wiring.extension.images

    fun applyConventions() = images.configureEach {
        base.convention(ubiMinimal)
        packages.convention(emptyList())
        environment.convention(emptyMap())
        labels.convention(emptyMap())
        ports.convention(emptyList())
        user.convention(DEFAULT_USER)
        app.convention(true)
    }

    /** Every image's packages, which the lock pins with `image { packages(…) }`'s own. */
    fun packages(): Provider<List<String>> = project.provider { images.flatMap(::packagesOf) }

    private fun packagesOf(spec: ImageSpec) =
        (ImageNeeds.packages(wiring.ecosystem.id == JAVA, spec.base.get()) + spec.packages.get()).distinct()

    /** One task per image; [own] are the project's own `Dockerfile.<name>` variants, which an image may not shadow. */
    fun register(
        own: List<String>,
        identity: Provider<ImageIdentity>,
        arguments: Provider<Map<String, String>>,
    ): List<TaskProvider<WriteTextFile>> = images.map { spec ->
        val variant = ImageVariant(spec.name)
        if (spec.name in own) {
            throw GradleException(
                "images { register(\"${spec.name}\") } writes ${variant.dockerfile}, which the project has too: " +
                    "remove one",
            )
        }
        project.tasks.register("appImageDockerfile${variant.taskSuffix}", WriteTextFile::class.java) {
            description = "Writes ${variant.dockerfile} for the container build context from images { ${spec.name} }"
            // Resolved now: the configuration cache can't store a lambda holding this class, and so the Project.
            val image = model(spec)
            text.set(
                identity.map { image.copy(identity = it) }
                    .map(ImageDockerfile::render)
                    .zip(arguments, ContextDockerfile::render),
            )
            outputFile.set(wiring.workDir.map { it.file("docker/images/${variant.dockerfile}") })
        }
    }

    /** With a placeholder identity, which is only known once the build script has run. */
    private fun model(spec: ImageSpec) = ImageModel(
        name = spec.name,
        identity = ImageIdentity(spec.name, ""),
        java = wiring.ecosystem.id == JAVA,
        base = spec.base.get(),
        buildImage = spec.ubiMinimal,
        packages = packagesOf(spec),
        environment = spec.environment.get(),
        labels = spec.labels.get(),
        ports = spec.ports.get(),
        user = spec.user.get(),
        app = spec.app.get(),
    )

    private companion object {
        const val DEFAULT_USER = "1001"
        const val JAVA = "java"
    }
}
