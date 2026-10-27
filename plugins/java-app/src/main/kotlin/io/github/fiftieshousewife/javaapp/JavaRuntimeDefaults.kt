package io.github.fiftieshousewife.javaapp

import org.gradle.api.GradleException
import org.gradle.api.provider.Property

/**
 * `javaApp { runtimeDefaults { … } }`: JVM options for a production service, in place of the JVM's
 * own defaults (a quarter of the memory for the heap, the Serial collector below 2 CPUs or about
 * 1.8 GB, dumps in the working directory). They come first on the launcher's JVM line, so the
 * project's `jvmArgs`, an operator's `jvm.options` and `APP_JAVA_OPTS` still win.
 */
abstract class JavaRuntimeDefaults {
    /** False leaves every JVM default as it is. Default true. */
    abstract val enabled: Property<Boolean>

    /** The share of the machine's or container's memory for the heap (`-XX:MaxRAMPercentage`). Default 75. */
    abstract val maxRamPercentage: Property<Int>

    /** Exit on the first `OutOfMemoryError`, for a supervisor to restart the app. Default true. */
    abstract val exitOnOutOfMemory: Property<Boolean>

    /**
     * Write a heap dump on `OutOfMemoryError`, and crash logs (`hs_err_pid<pid>.log`), into
     * `$APP_LOG_DIR`, which the launcher creates with mode 750; the JVM writes dumps readable by
     * the app's user only. Default true.
     */
    abstract val heapDumpOnOutOfMemory: Property<Boolean>

    /**
     * The collector: `G1`, `Parallel`, `Serial` or `Z`. Unset (the default) lets the JVM choose, so an
     * operator's own choice in `APP_JAVA_OPTS` doesn't conflict with it.
     */
    abstract val garbageCollector: Property<String>

    /** Refuse `jcmd`, `jstack` and agents attaching at runtime (`-XX:+DisableAttachMechanism`). Default false. */
    abstract val disableAttach: Property<Boolean>

    internal companion object {
        private val COLLECTORS = mapOf("G1" to "G1", "PARALLEL" to "Parallel", "SERIAL" to "Serial", "Z" to "Z")
        private val GC_FLAG = Regex("""-XX:\+Use\w+GC""")

        /** The options with fixed values, ahead of [jvmArgs]; empty when disabled. */
        fun fixedOptions(defaults: Settings, jvmArgs: List<String>): List<String> {
            if (!defaults.enabled) return emptyList()
            val gc = defaults.garbageCollector?.let { name ->
                val collector = COLLECTORS[name.uppercase()]
                    ?: throw GradleException("runtimeDefaults.garbageCollector is '$name'; use ${COLLECTORS.values}")
                jvmArgs.firstOrNull(GC_FLAG::matches)?.let {
                    throw GradleException("runtimeDefaults.garbageCollector is $name but jvmArgs already has $it")
                }
                "-XX:+Use${collector}GC"
            }
            return listOfNotNull(
                "-XX:MaxRAMPercentage=${defaults.maxRamPercentage}",
                "-XX:+ExitOnOutOfMemoryError".takeIf { defaults.exitOnOutOfMemory },
                "-XX:+HeapDumpOnOutOfMemoryError".takeIf { defaults.heapDumpOnOutOfMemory },
                gc,
                "-XX:+DisableAttachMechanism".takeIf { defaults.disableAttach },
            )
        }
    }

    /** The values, read once when the launcher is configured. */
    internal data class Settings(
        val enabled: Boolean = true,
        val maxRamPercentage: Int = 75,
        val exitOnOutOfMemory: Boolean = true,
        val heapDumpOnOutOfMemory: Boolean = true,
        val garbageCollector: String? = null,
        val disableAttach: Boolean = false,
    ) {
        /** Whether the launcher points dumps and crash logs at `$APP_LOG_DIR`. */
        val logDirFiles: Boolean get() = enabled && heapDumpOnOutOfMemory
    }
}
