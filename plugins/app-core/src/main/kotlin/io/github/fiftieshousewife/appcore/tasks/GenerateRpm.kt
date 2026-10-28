package io.github.fiftieshousewife.appcore.tasks

import io.github.fiftieshousewife.appcore.RpmLayout
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.redline_rpm.Builder
import org.redline_rpm.header.Architecture
import org.redline_rpm.header.Flags
import org.redline_rpm.header.Header
import org.redline_rpm.header.RpmType
import org.redline_rpm.payload.Directive
import java.io.RandomAccessFile
import org.redline_rpm.header.Os as RpmOs

/**
 * `appRpm<Target>`: packs an assembled app directory into an RPM laid out by [RpmLayout], built in
 * Java by Redline so any build machine (macOS and Windows included) can produce it, with no
 * `rpmbuild` on the path. The scriptlets are the shell equivalents of the `%systemd_*` macros a
 * Fedora or RHEL spec file would use.
 */
@CacheableTask
abstract class GenerateRpm : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appDirectory: DirectoryProperty

    @get:Input abstract val layout: Property<RpmLayout>

    @get:Input abstract val license: Property<String>

    @get:Input @get:Optional
    abstract val url: Property<String>

    @get:Input abstract val systemd: Property<Boolean>

    /** Seconds since the epoch recorded as the build time: `SOURCE_DATE_EPOCH` when set, else now. */
    @get:Input @get:Optional
    abstract val buildTime: Property<Long>

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun build() {
        val layout = layout.get()
        val builder = describe(layout)
        scripts(builder, layout)
        RpmContents(builder, layout).addAll(appDirectory.get().asFile)
        if (systemd.get()) {
            val unit = temporaryDir.resolve("${layout.name}.service").apply {
                writeText(layout.render(RpmLayout.template("app.service")))
            }
            // Without its parents: /usr/lib/systemd/system belongs to systemd, not to this package.
            builder.addFile(layout.unitFile, unit, FILE_MODE, DIR_MODE, Directive.NONE, "root", "root", false)
        }
        val out = outputFile.get().asFile.apply { delete() }
        RandomAccessFile(out, "rw").use { builder.build(it.channel) }
        logger.lifecycle("Built ${out.name}")
    }

    private fun describe(layout: RpmLayout) = Builder().apply {
        setPackage(layout.name, layout.rpmVersion, layout.release)
        setType(RpmType.BINARY)
        setPlatform(Architecture.valueOf(layout.arch.uppercase()), RpmOs.LINUX)
        setSummary(layout.summary)
        setDescription(layout.summary)
        setLicense(license.get())
        setGroup("Applications/System")
        // Not the build machine's name, which would make every build differ.
        setBuildHost("localhost")
        url.orNull?.let(::setUrl)
        buildTime.orNull?.let { addHeaderEntry(Header.HeaderTag.BUILDTIME, it.toInt()) }
    }

    private fun scripts(builder: Builder, layout: RpmLayout) {
        builder.addDependency("shadow-utils", Flags.SCRIPT_PRE, "")
        builder.setPreInstallScript(layout.render(RpmLayout.template("pre.sh")))
        if (systemd.get()) {
            builder.setPostInstallScript(layout.render(RpmLayout.template("post.sh")))
            builder.setPreUninstallScript(layout.render(RpmLayout.template("preun.sh")))
            builder.setPostUninstallScript(layout.render(RpmLayout.template("postun.sh")))
        }
    }

    private companion object {
        const val FILE_MODE = 0b110_100_100 // 0644
        const val DIR_MODE = 0b111_101_101 // 0755
    }
}
