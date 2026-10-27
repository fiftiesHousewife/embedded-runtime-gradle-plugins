package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppExtension
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/** `javaApp {}`: the Java application archive and the jlink-trimmed runtime inside it. */
abstract class JavaAppExtension : AppExtension() {
    /** Java feature release used for both compilation and the bundled runtime. Defaults to the catalogue default. */
    abstract val javaVersion: Property<Int>

    /** Exact JDK version (e.g. `21.0.12.1+1`) linked into the archive. Defaults to the catalogue pin. */
    abstract val jdkVersion: Property<String>

    /**
     * Modules the runtime must hold. Defaults to what `jdeps` finds in the app and its
     * dependencies, plus [additionalModules]. Set it to take full control of the list; with
     * [sharedModuleSets] the runtime links the smallest shared set that holds them.
     */
    abstract val jlinkModules: ListProperty<String>

    /**
     * Modules `jdeps` cannot see because they are loaded by name at runtime, such as
     * `jdk.crypto.ec` (TLS), `java.naming` (JNDI, LDAP) or `jdk.localedata`. `jlinkFindModules`
     * reports which ones an app needs.
     */
    abstract val additionalModules: ListProperty<String>

    /**
     * Whether the runtime is the smallest of [moduleSets] holding every module in [jlinkModules],
     * rather than exactly those: apps on the same set ship identical runtimes, so a container
     * registry or node stores that layer once. An app no set covers gets exactly its own modules.
     * Default `true`, or the `javaApp.sharedModuleSets` property.
     */
    abstract val sharedModuleSets: Property<Boolean>

    /**
     * The shared module sets, by name. Defaults to the plugin's `base`, `service`, `framework` and
     * `server`; `put` adds or replaces one, `set` replaces them all.
     */
    abstract val moduleSets: MapProperty<String, List<String>>

    /** Extra `jlink` options; defaults strip debug info, man pages and headers. */
    abstract val jlinkOptions: ListProperty<String>

    /** Fixed JVM options baked into the launcher, after [runtimeDefaults] and before `$APP_JAVA_OPTS`. */
    abstract val jvmArgs: ListProperty<String>

    /** Production JVM options: heap share, exit and dump on out-of-memory, collector; see [JavaRuntimeDefaults]. */
    @get:Nested
    abstract val runtimeDefaults: JavaRuntimeDefaults

    fun runtimeDefaults(action: Action<JavaRuntimeDefaults>) = action.execute(runtimeDefaults)

    /**
     * Files copied over every runtime after [security] is applied, by path: anything the plugin does
     * not model, such as a provider's jar or a replacement `conf/logging.properties`. Default
     * `src/deploy/runtime`.
     */
    abstract val runtimeOverlay: DirectoryProperty

    /** Certificates, security policy and hardening applied to every runtime; see [JavaRuntimeSecurity]. */
    @get:Nested
    abstract val security: JavaRuntimeSecurity

    fun security(action: Action<JavaRuntimeSecurity>) = action.execute(security)
}
