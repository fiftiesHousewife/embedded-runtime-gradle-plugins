package io.github.fiftieshousewife.appcore

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Nested

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

    /** An RPM per Linux target, for installing with dnf; see [RpmPackaging]. Off by default. */
    @get:Nested
    abstract val rpm: RpmPackaging

    fun rpm(action: Action<RpmPackaging>) = action.execute(rpm)

    /**
     * A CycloneDX JSON SBOM of the project's own making, used in every target's archive (as
     * `META-INF/sbom.cdx.json`) and publication instead of the built-in one, which then does not run.
     * Point it at another tool's output so the task dependency comes with it:
     * `sbom = tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom").flatMap { it.jsonOutput }`.
     * The built-in SBOM lists the embedded runtime; a replacement should too, or scanners won't see it.
     */
    abstract val sbom: RegularFileProperty

    /**
     * The organisation's runtime policy ([RuntimePolicySource]) every archive is checked against before
     * it is built: a directory or zip, or the `group:name:version` of a zip in a repository.
     * `-P<javaApp|nodeApp>.securityPolicy`, set per machine, wins over this, so a project can't loosen
     * the policy a machine requires.
     */
    abstract val securityPolicy: Property<String>

    /** Configuration shipped as `conf.defaults/` and an RPM's `/etc/<name>/`. node-app: `src/deploy/conf`. */
    abstract val configDefaults: DirectoryProperty

    /** Start the extracted archive and wait for `bin/<app> healthcheck` to succeed. Default true. */
    abstract val smokeTestEnabled: Property<Boolean>
}
