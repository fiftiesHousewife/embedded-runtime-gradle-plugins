package io.github.fiftieshousewife.appcore

import org.gradle.api.GradleException

/** Caller's options (`JAVA_TOOL_OPTIONS`, `NODE_OPTIONS`, …) can load an agent or loosen TLS, undoing hardening. */
enum class RuntimeOptions(val id: String) {
    OPEN("open"),

    /** Refuses to start, rather than silently ignoring them, while [VARIABLES] or `jvm.options` would add any. */
    LOCKED("locked"),
    ;

    override fun toString(): String = id

    companion object {
        /** Not `NODE_EXTRA_CA_CERTS`: hosts often set it machine-wide, and Java launchers share this list. */
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
