package io.github.fiftieshousewife.appcore

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import java.time.Duration

/**
 * Settings every app archive has, whatever its ecosystem: the base of `javaApp {}` and `nodeApp {}`.
 * Every property has a convention, so the minimal configuration is applying the plugin.
 */
abstract class AppExtension {
    /** Launcher name (`bin/<appName>`) and archive base name. Defaults to the project name. */
    abstract val appName: Property<String>

    /**
     * The OS and CPU combinations to build, one archive each, all published together. Defaults to
     * `-PjavaApp.targets` / `-PnodeApp.targets` (comma-separated), else this machine. Only this
     * machine's archive can be smoke-tested here.
     */
    abstract val targets: SetProperty<Target>

    /** Replaces [targets]: `targets("linux-x64", "linux-aarch64", "windows-x64")`. */
    fun targets(vararg ids: String) = targets.set(ids.map(Target::parse))

    /**
     * The Dockerfile `appDockerContext` copies. Defaults to the project's own `Dockerfile`, else
     * `src/deploy/docker/Dockerfile`; with neither, a generated one is used.
     */
    abstract val dockerfile: RegularFileProperty

    /** Default configuration shipped as `conf.defaults/`. Defaults to `src/deploy/conf`. */
    abstract val configDefaults: DirectoryProperty

    /** Start the extracted archive and wait for `bin/<app> healthcheck` to succeed. Default true. */
    abstract val smokeTestEnabled: Property<Boolean>
    abstract val smokeTestTimeout: Property<Duration>

    /**
     * Publishing an archive that has not passed its smoke test on this machine fails unless this is
     * true (or `-PjavaApp.allowUntestedPublish=true` / `-PnodeApp.allowUntestedPublish=true`).
     * Archives for other targets are always untested.
     */
    abstract val allowUntestedPublish: Property<Boolean>
}
