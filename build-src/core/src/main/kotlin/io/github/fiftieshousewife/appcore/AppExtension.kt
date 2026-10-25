package io.github.fiftieshousewife.appcore

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Nested
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

    /**
     * The archive each target is packed into: `tar.gz` (default), `tar` or `zip`. Defaults to
     * `-PjavaApp.archiveFormat` / `-PnodeApp.archiveFormat`. The extension is part of the published
     * artifact, so consumers resolving by Gradle Module Metadata are unaffected by a change.
     */
    abstract val archiveFormat: Property<ArchiveFormat>

    /** Replaces [archiveFormat]: `archiveFormat("zip")`. */
    fun archiveFormat(id: String) = archiveFormat.set(ArchiveFormat.parse(id))

    /** An RPM per Linux target, for installing with dnf; see [RpmPackaging]. Off by default. */
    @get:Nested
    abstract val rpm: RpmPackaging

    fun rpm(action: Action<RpmPackaging>) = action.execute(rpm)

    /** Where `appInstall` installs this machine's archive; see [InstallSettings]. */
    @get:Nested
    abstract val install: InstallSettings

    fun install(action: Action<InstallSettings>) = action.execute(install)

    /**
     * `open` (default) lets whoever runs the app add runtime options; `locked` refuses to start
     * while anything would. Defaults to `-PjavaApp.runtimeOptions` / `-PnodeApp.runtimeOptions`.
     */
    abstract val runtimeOptions: Property<RuntimeOptions>

    /** Replaces [runtimeOptions]: `runtimeOptions("locked")`. */
    fun runtimeOptions(id: String) = runtimeOptions.set(RuntimeOptions.parse(id))

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
