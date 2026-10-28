package io.github.fiftieshousewife.javaapp

/**
 * `javaApp.runtimeDefaults`: in place of the JVM's own defaults (a quarter of the memory for the heap,
 * dumps in the working directory), what a production service wants. A collector, attach or a different
 * heap share are `jvmArgs`, which come after these and win.
 */
internal object JavaRuntimeDefaults {
    val OPTIONS = listOf("-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-XX:+HeapDumpOnOutOfMemoryError")
}
