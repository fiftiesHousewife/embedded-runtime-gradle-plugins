package io.github.fiftieshousewife.appcore

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty

/**
 * `javaApp { image { packages("zlib") } }`: RPMs a container image adds to its base, UBI micro's above all,
 * which has no package manager. `appImagePackagesLock` pins each one per CPU in [lockFile], from [repositories];
 * `appDockerContext` puts the locked files, checked against the lock, in `packages/<cpu>/`, and the Dockerfile
 * installs them with `rpm`. The image build itself then reaches no repository, and runs nothing for another CPU.
 */
abstract class ImagePackages {
    /** Package names, as dnf takes them. Their own dependencies must be in the base image or listed too. */
    abstract val packages: ListProperty<String>

    fun packages(vararg names: String) = packages.addAll(*names)

    /**
     * YUM repositories by id, searched in order, `[arch]` standing for the CPU (`x86_64`, `aarch64`). Default:
     * BaseOS and AppStream from the binary repository's `binaryRepo.repo.ubi` remote, laid out as Red Hat's UBI 9
     * repositories are; with no binary repository, Red Hat's own.
     */
    abstract val repositories: MapProperty<String, String>

    /** Default `rpms.lock.yaml` at the project's root, in Konflux's layout; commit it. */
    abstract val lockFile: RegularFileProperty
}
