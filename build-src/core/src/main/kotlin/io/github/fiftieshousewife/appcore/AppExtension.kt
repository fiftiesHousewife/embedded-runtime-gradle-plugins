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
     * The app's licence, as an SPDX expression (`Apache-2.0`, `LicenseRef-Proprietary`). Labels the
     * container image and is the RPM's licence. Default `LicenseRef-Proprietary`.
     */
    abstract val license: Property<String>

    /**
     * Where the app's source code lives, for the container image's `org.opencontainers.image.source`
     * label. Defaults to the `origin` git remote, as an https URL without any credentials in it.
     */
    abstract val sourceUrl: Property<String>

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

    /**
     * `open` (default) lets whoever runs the app add runtime options; `locked` refuses to start
     * while anything would. Defaults to `-PjavaApp.runtimeOptions` / `-PnodeApp.runtimeOptions`.
     */
    abstract val runtimeOptions: Property<RuntimeOptions>

    /** Replaces [runtimeOptions]: `runtimeOptions("locked")`. */
    fun runtimeOptions(id: String) = runtimeOptions.set(RuntimeOptions.parse(id))

    /**
     * A CycloneDX JSON SBOM of the project's own making, used in every target's archive (as
     * `META-INF/sbom.cdx.json`) and publication instead of the built-in one, which then does not run.
     * Point it at another tool's output so the task dependency comes with it:
     * `sbom = tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom").flatMap { it.jsonOutput }`.
     * The built-in SBOM lists the embedded runtime; a replacement should too, or scanners won't see it.
     */
    abstract val sbom: RegularFileProperty

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
