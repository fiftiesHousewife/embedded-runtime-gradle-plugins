package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException

/**
 * Whether whoever runs the app may add runtime options to the ones the build fixed. Environment
 * variables the runtime itself reads (`JAVA_TOOL_OPTIONS`, `NODE_OPTIONS`, …) can load an agent or
 * a script, re-enable the attach API, or loosen TLS, undoing the build's hardening.
 */
enum class RuntimeOptions(val id: String) {
    /** The default: `APP_JAVA_OPTS` / `APP_NODE_OPTS`, and for Java `$APP_CONF_DIR/jvm.options`, are added. */
    OPEN("open"),

    /**
     * The runtime starts with the build's options only. The launcher refuses to start while
     * [VARIABLES] or `$APP_CONF_DIR/jvm.options` would change them, rather than silently ignoring them.
     */
    LOCKED("locked"),
    ;

    override fun toString(): String = id

    companion object {
        /**
         * Every variable through which the launcher's caller could add runtime options, or loosen
         * Node's TLS: `NODE_TLS_REJECT_UNAUTHORIZED=0` turns off certificate checks. Not
         * `NODE_EXTRA_CA_CERTS`: hosts often set it machine-wide, and Java launchers share this list.
         */
        val VARIABLES =
            listOf(
                "APP_JAVA_OPTS",
                "APP_NODE_OPTS",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
                "NODE_OPTIONS",
                "NODE_TLS_REJECT_UNAUTHORIZED",
            )

        fun parse(id: String): RuntimeOptions = entries.firstOrNull { it.id == id.trim().lowercase() }
            ?: throw GradleException("Unsupported runtimeOptions '$id'. Supported: ${entries.joinToString()}")
    }
}
