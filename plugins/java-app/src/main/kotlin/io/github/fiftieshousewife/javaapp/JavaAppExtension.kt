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

    /**
     * Modules `jdeps` cannot see because they are loaded by name at runtime, such as
     * `jdk.crypto.ec` (TLS), `java.naming` (JNDI, LDAP) or `jdk.localedata`, added to what it finds.
     * `jlinkFindModules` reports which ones an app needs.
     */
    abstract val additionalModules: ListProperty<String>

    /**
     * Shared module sets, by name: the runtime is the smallest set holding every module the app
     * needs, rather than exactly those, so apps on the same set ship identical runtimes and a
     * container registry or node stores that layer once. An app no set covers gets exactly its own
     * modules. Defaults to the plugin's `base`, `service`, `framework` and `server`; `put` adds or
     * replaces one, `set` replaces them all, and an empty map links every app's modules alone.
     */
    abstract val moduleSets: MapProperty<String, List<String>>

    /** Fixed JVM options baked into the launcher, after [runtimeDefaults] and before `$APP_JAVA_OPTS`. */
    abstract val jvmArgs: ListProperty<String>

    /**
     * Production JVM options in place of the JVM's own defaults: three quarters of the memory for the
     * heap, and on `OutOfMemoryError` a heap dump in `$APP_LOG_DIR` and an exit, for a supervisor to
     * restart the app. They come first on the launcher's JVM line, so [jvmArgs], an operator's
     * `jvm.options` and `APP_JAVA_OPTS` still win. Default true.
     */
    abstract val runtimeDefaults: Property<Boolean>

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
