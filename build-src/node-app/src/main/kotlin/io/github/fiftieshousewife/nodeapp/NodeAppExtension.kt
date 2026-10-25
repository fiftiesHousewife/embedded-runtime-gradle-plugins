package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.AppExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** `nodeApp {}`: the Node.js application archive and the Node runtime inside it. */
abstract class NodeAppExtension : AppExtension() {
    /** Exact Node.js version bundled and used for `npm` at build time. Defaults to the catalogue pin. */
    abstract val nodeVersion: Property<String>

    /** Files copied into `app/`. Defaults to `src/` except `src/deploy/` (or `dist/` when [buildScript] is set). */
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
