package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.AppExtension
import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/** `nodeApp {}`: the Node.js application archive and the Node runtime inside it. */
abstract class NodeAppExtension : AppExtension() {
    /** Files copied into `app/`. Defaults to `src/` except `src/deploy/` (or `dist/` when [buildScript] is set). */
    abstract val appFiles: ConfigurableFileCollection

    /** Script under `app/` started by `bin/<app> run`, e.g. `main.js` or `server.mjs`. */
    abstract val entryPoint: Property<String>

    /** Optional `package.json` script run before packaging, e.g. `build`. */
    abstract val buildScript: Property<String>

    /** `package.json` script run by `check` when present. Defaults to `test`. */
    abstract val testScript: Property<String>

    /** Fixed Node options baked into the launcher, before `$APP_NODE_OPTS`. */
    abstract val nodeArgs: ListProperty<String>

    /**
     * Packages whose install scripts have been vetted, such as esbuild, whose postinstall only checks
     * the platform package npm already chose. Empty (the default), the production install passes
     * `--ignore-scripts`. Naming any runs install scripts, since npm can't run some and skip others,
     * and the named packages no longer block cross builds; any other package with an install
     * script still does, as its output is usually built for the build machine.
     */
    abstract val installScripts: ListProperty<String>

    /** Trusted CAs and TLS settings for the embedded Node.js; see [NodeRuntimeSecurity]. */
    @get:Nested
    abstract val security: NodeRuntimeSecurity

    fun security(action: Action<NodeRuntimeSecurity>) = action.execute(security)
}
