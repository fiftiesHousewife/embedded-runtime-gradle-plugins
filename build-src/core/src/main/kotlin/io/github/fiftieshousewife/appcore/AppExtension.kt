package io.github.fiftieshousewife.appcore

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import java.time.Duration

/**
 * Settings every app archive has, whatever its ecosystem: the base of `javaApp {}` and `nodeApp {}`.
 * Every property has a convention, so the minimal configuration is applying the plugin.
 */
abstract class AppExtension {
    /** Launcher name (`bin/<appName>`) and archive base name. Defaults to the project name. */
    abstract val appName: Property<String>

    /**
     * The OS and CPU to build for. Defaults to `-PjavaApp.target` / `-PnodeApp.target`, else the
     * build host. A non-host target is a cross build: it is assembled but cannot be smoke-tested here.
     */
    abstract val target: Property<Target>

    /** Default configuration shipped as `conf.defaults/`. Defaults to `src/deploy/conf`. */
    abstract val configDefaults: DirectoryProperty

    /** Start the extracted archive and wait for `bin/<app> healthcheck` to succeed. Default true. */
    abstract val smokeTestEnabled: Property<Boolean>
    abstract val smokeTestTimeout: Property<Duration>

    /**
     * Publishing an archive that has not passed its smoke test on this machine fails unless this is
     * true (or `-PjavaApp.allowUntestedPublish=true` / `-PnodeApp.allowUntestedPublish=true`). Cross
     * builds are always untested.
     */
    abstract val allowUntestedPublish: Property<Boolean>

    fun target(id: String): Target = Target.parse(id)
}
