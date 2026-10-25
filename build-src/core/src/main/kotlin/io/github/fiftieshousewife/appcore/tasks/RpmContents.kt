package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.AppCopySpecs
import io.github.fiftieshousewife.appcore.RpmLayout
import io.github.fiftieshousewife.appcore.Target
import org.redline_rpm.Builder
import org.redline_rpm.payload.Directive
import java.io.File

/**
 * The files of an RPM laid out by [RpmLayout]: the app directory under `/opt/<name>` owned by root,
 * its `conf.defaults/` again under `/etc/<name>` as `%config(noreplace)` readable by the service
 * account, and the account's own data and log directories.
 *
 * Every directory added is one this package owns; parents such as `/opt` belong to the system.
 */
internal class RpmContents(private val builder: Builder, private val layout: RpmLayout) {
    private val posixHost = !Target.host().isWindows

    fun addAll(appDirectory: File) {
        tree(appDirectory, Area(layout.home, ROOT, PUBLIC_DIR)) { file, rel ->
            file(file, "${layout.home}/$rel", modeOf(file, rel))
        }
        val conf = Area(layout.confDir, layout.user, PRIVATE_DIR)
        directory(conf.path, conf.dirMode, group = conf.group)
        appDirectory.resolve(CONF_DEFAULTS).takeIf(File::isDirectory)?.let { defaults ->
            tree(defaults, conf) { file, rel ->
                builder.addFile(
                    "${layout.confDir}/$rel",
                    file,
                    CONFIG_FILE,
                    PRIVATE_DIR,
                    Directive(CONFIG_NOREPLACE),
                    ROOT,
                    layout.user,
                    false,
                )
            }
        }
        directory(layout.dataDir, PRIVATE_DIR, owner = layout.user, group = layout.user)
        directory(layout.logDir, PRIVATE_DIR, owner = layout.user, group = layout.user)
    }

    /** Where a tree of files goes, and who owns its directories. */
    private class Area(val path: String, val group: String, val dirMode: Int)

    /** Adds [root] as [area], every directory below it, and each file through [addFile], in a stable order. */
    private fun tree(root: File, area: Area, addFile: (File, String) -> Unit) {
        directory(area.path, area.dirMode, group = area.group)
        root.walkTopDown().drop(1).sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach {
            val rel = it.relativeTo(root).invariantSeparatorsPath
            if (it.isDirectory) directory("${area.path}/$rel", area.dirMode, group = area.group) else addFile(it, rel)
        }
    }

    private fun directory(path: String, mode: Int, owner: String = ROOT, group: String = ROOT) =
        builder.addDirectory(path, mode, Directive.NONE, owner, group, false)

    private fun file(file: File, path: String, mode: Int) =
        builder.addFile(path, file, mode, PUBLIC_DIR, Directive.NONE, ROOT, ROOT, false)

    /** The exec bit as the assembled directory has it; a Windows build machine has none, so by path. */
    private fun modeOf(file: File, rel: String): Int {
        val executable = if (posixHost) file.canExecute() else AppCopySpecs.isExecutable(rel)
        return if (executable) PUBLIC_DIR else PUBLIC_FILE
    }

    private companion object {
        const val ROOT = "root"
        const val CONF_DEFAULTS = "conf.defaults"
        const val PUBLIC_DIR = 0b111_101_101 // 0755
        const val PUBLIC_FILE = 0b110_100_100 // 0644
        const val PRIVATE_DIR = 0b111_101_000 // 0750
        const val CONFIG_FILE = 0b110_100_000 // 0640
        const val CONFIG_NOREPLACE = Directive.RPMFILE_CONFIG or Directive.RPMFILE_NOREPLACE
    }
}
