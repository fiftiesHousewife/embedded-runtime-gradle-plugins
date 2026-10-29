package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.ImageVariant
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/**
 * The names of the `Dockerfile.<name>` files in a directory. Read this way, the configuration cache
 * records the names alone, not the directory's whole listing, which every `clean` changes.
 */
abstract class DockerfileVariants : ValueSource<List<String>, DockerfileVariants.Parameters> {
    interface Parameters : ValueSourceParameters {
        val directory: DirectoryProperty
    }

    override fun obtain(): List<String> =
        ImageVariant.find(parameters.directory.get().asFile).mapNotNull(ImageVariant::name)
}
