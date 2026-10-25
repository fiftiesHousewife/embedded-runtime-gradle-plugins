package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.AppExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** `javaApp {}`: the Java application archive and the jlink-trimmed runtime inside it. */
abstract class JavaAppExtension : AppExtension() {
    /** Java feature release used for both compilation and the bundled runtime. Defaults to the catalogue default. */
    abstract val javaVersion: Property<Int>

    /** Exact JDK version (e.g. `21.0.12.1+1`) linked into the archive. Defaults to the catalogue pin. */
    abstract val jdkVersion: Property<String>

    /**
     * Modules linked into the runtime. Defaults to what `jdeps` finds in the app and its
     * dependencies, plus [additionalModules]. Set it to take full control of the list.
     */
    abstract val jlinkModules: ListProperty<String>

    /**
     * Modules `jdeps` cannot see because they are loaded by name at runtime, such as
     * `jdk.crypto.ec` (TLS), `java.naming` (JNDI, LDAP) or `jdk.localedata`. `jlinkFindModules`
     * reports which ones an app needs.
     */
    abstract val additionalModules: ListProperty<String>

    /** Extra `jlink` options; defaults strip debug info, man pages and headers. */
    abstract val jlinkOptions: ListProperty<String>

    /** Fixed JVM options baked into the launcher, before `$APP_JAVA_OPTS`. */
    abstract val jvmArgs: ListProperty<String>
}
