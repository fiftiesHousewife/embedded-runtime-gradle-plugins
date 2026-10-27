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
     * The project's Dockerfile, which `appDockerfile` creates if missing and whose embedded-runtime
     * block it keeps up to date; one without that block is used as written. Defaults to the
     * project's root `Dockerfile` (or an existing `src/deploy/docker/Dockerfile`).
     */
    abstract val dockerfile: RegularFileProperty

    /**
     * The archive every target is packed into: `tar.gz`, `tar` or `zip`. Unset (and without
     * `-PjavaApp.archiveFormat` / `-PnodeApp.archiveFormat`), each target gets the one native to it:
     * `zip` for Windows, `tar.gz` for Linux and macOS. The extension is part of the published
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

    /**
     * The organisation's runtime policy ([RuntimePolicySource]): a directory or zip, every archive
     * is checked against before it is built. `-P<javaApp|nodeApp>.securityPolicy`, set per machine,
     * wins over this, so a project can't loosen the policy a machine requires.
     */
    abstract val securityPolicy: Property<String>

    /** The policy as the `group:name:version` of a zip; `-P…securityPolicyArtifact` wins over this. */
    abstract val securityPolicyArtifact: Property<String>

    /**
     * The commit recorded in `META-INF/app.json` and the container image's labels. Defaults to
     * `-P<javaApp|nodeApp>.gitCommit`, else `git rev-parse HEAD`, else `unknown` outside a checkout.
     */
    abstract val gitCommit: Property<String>

    /** How `checkRuntimeSupport` judges the embedded runtime. */
    @get:Nested
    abstract val runtimeSupport: RuntimeSupportSettings

    fun runtimeSupport(action: Action<RuntimeSupportSettings>) = action.execute(runtimeSupport)

    /** Configuration shipped as `conf.defaults/` and an RPM's `/etc/<name>/`. node-app: `src/deploy/conf`. */
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
