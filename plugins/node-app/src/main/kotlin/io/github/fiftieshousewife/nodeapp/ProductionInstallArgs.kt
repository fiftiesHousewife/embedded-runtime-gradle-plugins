package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.Os
import io.github.fiftieshousewife.appcore.Target

/** npm's extra arguments for a production install of one target's dependencies. */
internal object ProductionInstallArgs {
    /** The arguments for installing [target]'s dependencies on [host]. */
    fun of(ignoreScripts: Boolean, target: Target, host: Target): List<String> =
        listOfNotNull("--ignore-scripts".takeIf { ignoreScripts }) + crossTarget(target, host)

    /** Another target's OS- and CPU-specific optional dependencies (npm 10+); glibc for Linux. */
    private fun crossTarget(target: Target, host: Target): List<String> = if (target == host) {
        emptyList()
    } else {
        listOfNotNull(
            "--os=${NodeSources.npmOs(target)}",
            "--cpu=${NodeSources.npmCpu(target)}",
            "--libc=glibc".takeIf { target.os == Os.LINUX },
        )
    }
}
