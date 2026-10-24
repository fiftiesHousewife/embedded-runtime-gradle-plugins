package io.github.fiftieshousewife.platform.bundle

import io.github.fiftieshousewife.platform.runtime.Platform
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.time.Duration

/**
 * Settings shared by Java and Node bundles. Every property has a convention, so the minimal
 * configuration is applying the plugin (plus `application.mainClass` for Java).
 */
abstract class BundleExtension {
    /** Launcher name (`bin/<appName>`) and archive base name. Defaults to the project name. */
    abstract val appName: Property<String>

    /**
     * The platform to build for. Defaults to `-Pbundle.targetPlatform`, else the build host.
     * A non-host target is a cross build: it is assembled but cannot be smoke-tested here.
     */
    abstract val targetPlatform: Property<Platform>

    /** Default configuration shipped as `conf.defaults/`. Defaults to `src/bundle/conf`. */
    abstract val configDefaults: DirectoryProperty

    /** Start the extracted archive and wait for `bin/<app> healthcheck` to succeed. Default true. */
    abstract val smokeTestEnabled: Property<Boolean>
    abstract val smokeTestTimeout: Property<Duration>

    /**
     * Publishing a bundle that has not passed its smoke test on this machine fails unless this is
     * true (or `-Pbundle.allowUntestedPublish=true`). Cross builds are always untested.
     */
    abstract val allowUntestedPublish: Property<Boolean>

    fun platform(id: String): Platform = Platform.parse(id)
}

abstract class JavaBundleExtension : BundleExtension() {
    /** Java feature release used for both compilation and the bundled runtime. Defaults to the catalogue default. */
    abstract val javaVersion: Property<Int>

    /** Exact JDK version (e.g. `21.0.12.1+1`) linked into the bundle. Defaults to the catalogue pin. */
    abstract val jdkVersion: Property<String>

    /**
     * Modules linked into the runtime. Keep this explicit and reviewed: `jdeps` misses reflectively
     * loaded modules such as `jdk.crypto.ec`, `java.naming` and `jdk.localedata`.
     */
    abstract val jlinkModules: ListProperty<String>

    /** Extra `jlink` options; defaults strip debug info, man pages and headers. */
    abstract val jlinkOptions: ListProperty<String>

    /** Fixed JVM options baked into the launcher, before `$APP_JAVA_OPTS`. */
    abstract val jvmArgs: ListProperty<String>
}

abstract class NodeBundleExtension : BundleExtension() {
    /** Exact Node.js version bundled and used for `npm` at build time. Defaults to the catalogue pin. */
    abstract val nodeVersion: Property<String>

    /** Files copied into `app/`. Defaults to `src/` except `src/bundle/` (or `dist/` when [buildScript] is set). */
    abstract val appFiles: ConfigurableFileCollection

    /** Script under `app/` started by `bin/<app> run`, e.g. `main.js` or `server.mjs`. */
    abstract val entryPoint: Property<String>

    /** Optional `package.json` script run before packaging, e.g. `build`. */
    abstract val buildScript: Property<String>

    /** `package.json` script run by `check` when present. Defaults to `test`. */
    abstract val testScript: Property<String>

    /** Pass `--ignore-scripts` to the production install. Default true; disable only for vetted install scripts. */
    abstract val ignoreInstallScripts: Property<Boolean>

    /** Fixed Node options baked into the launcher, before `$APP_NODE_OPTS`. */
    abstract val nodeArgs: ListProperty<String>

    /**
     * Packages whose install scripts are known not to produce host-specific output (for example
     * esbuild, whose postinstall only validates the platform package npm already selected), so they
     * do not block cross builds. Review before adding: the check exists because most do.
     */
    abstract val portableInstallScripts: ListProperty<String>
}
