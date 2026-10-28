package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.redline_rpm.Builder
import org.redline_rpm.header.Architecture
import org.redline_rpm.header.Os
import java.io.File
import java.io.IOException
import java.nio.file.Files
import io.github.fiftieshousewife.appcore.Os as TargetOs

/**
 * The JDK running the tests, repackaged in Adoptium's layout under [TestProject.sources]. It is the
 * only Java release the fake site holds, so a build under test must ask for [javaFeature].
 */
object JdkFixture {
    val jdkVersion: String = Runtime.version().let { v -> v.version().joinToString(".") + "+" + v.build().orElse(1) }
    val javaFeature: Int = Runtime.version().feature()

    /** Points the build at the fake site and pins [jdkVersion]. */
    fun properties(project: TestProject) = arrayOf(
        JdkSources.SOURCE to
            project.sources
                .resolve("adoptium")
                .toURI()
                .toString(),
        JdkSources.VERSION_PREFIX + javaFeature to jdkVersion,
        // The fixture serves the test JDK's own bytes; tests of reusing the toolchain's say so themselves.
        JdkSources.REUSE_TOOLCHAIN to "false",
    )

    /** The JDK running the tests, which the fixtures repackage. */
    val home: File get() = File(System.getProperty("java.home"))

    private val host = Target.parse(TestProject.hostTarget())

    /** The target a test of RPM sources (Linux only) builds: this machine's on Linux, else Linux on this CPU. */
    val rpmTarget: Target = if (host.os == TargetOs.LINUX) host else Target(TargetOs.LINUX, host.arch)

    /** Whether [rpmTarget] is this machine's, so its archive is started by the smoke test. */
    val rpmTargetStarts: Boolean = rpmTarget == host

    /**
     * On a machine that isn't Linux, its own JDK by path, as a Windows desktop beside Linux servers
     * would have it: an RPM source serves Linux targets only.
     */
    fun hostJdkUnlessLinux(): List<Pair<String, String>> =
        if (rpmTargetStarts) emptyList() else listOf(JdkSources.pathProperty(javaFeature, host) to home.absolutePath)

    /** Publishes the test JDK as `OpenJDK<f>U-jdk_<arch>_<os>_hotspot_<v>.tar.gz` for each target id. */
    fun publish(project: TestProject, vararg targets: String = arrayOf(TestProject.hostTarget())) =
        targets.forEach { t ->
            val (os, arch) = t.split("-")
            archive(
                project.sources
                    .resolve("adoptium/temurin$javaFeature-binaries/releases/download/jdk-$jdkVersion")
                    .resolve("OpenJDK${javaFeature}U-jdk_${arch}_${os}_hotspot_${jdkVersion.replace('+', '_')}.tar.gz"),
            )
        }

    /** Lays the test JDK out under [root] the way a curated binary repository tree (or a shared directory) holds it. */
    fun publishCurated(root: File, target: String = TestProject.hostTarget()) {
        val version = jdkVersion.replace('+', '_')
        archive(root.resolve("temurin/jdk/$version/jdk-$version-$target.tar.gz"))
    }

    /**
     * Publishes the test JDK as an RPM under [root], named the way an organisation might:
     * `<root>/<group>/jdk<feature>_<update>-64_<target>.rpm`, installed under `/usr/lib/jvm/`.
     */
    fun publishRpm(root: File, group: String, target: String = TestProject.hostTarget()): File {
        val (feature, update) = jdkVersion.substringBefore('+').split('.').let { it[0] to it.getOrElse(2) { "0" } }
        val out = root.resolve("$group/jdk${feature}_$update-64_$target.rpm")
        out.parentFile.mkdirs()
        sharedRpm.copyTo(out, overwrite = true)
        return out
    }

    /** The test JDK as an RPM, built once per test JVM with Redline. */
    private val sharedRpm: File by lazy {
        val dir = Files.createTempDirectory("jdk-rpm").toFile()
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        val install = "/usr/lib/jvm/jdk-$jdkVersion"
        val builder = Builder().apply {
            setPackage("jdk", jdkVersion.substringBefore('+'), "1")
            setPlatform(Architecture.X86_64, Os.LINUX)
        }
        home.walkTopDown().filter { !it.isDirectory || Files.isSymbolicLink(it.toPath()) }.forEach { file ->
            val path = "$install/${file.relativeTo(home).invariantSeparatorsPath}"
            if (Files.isSymbolicLink(file.toPath())) {
                builder.addLink(path, Files.readSymbolicLink(file.toPath()).toString())
            } else {
                builder.addFile(path, file, if (file.canExecute()) OCTAL_755 else OCTAL_644)
            }
        }
        dir.resolve(builder.build(dir))
    }

    private const val OCTAL_644 = 420
    private const val OCTAL_755 = 493

    /** Every target's archive holds the same bytes, so each test links one shared archive under its name. */
    private fun archive(out: File) {
        out.parentFile.mkdirs()
        out.delete()
        try {
            Files.createLink(out.toPath(), sharedArchive.toPath())
        } catch (_: IOException) {
            sharedArchive.copyTo(out) // a filesystem without hard links
        }
    }

    /** The test JDK as a `jdk-<version>/` tarball, built once per test JVM; [publish] costs a link, not a gzip. */
    private val sharedArchive: File by lazy {
        assumeTrue(home.resolve("jmods").isDirectory, "the test JDK must ship jmods/")
        val dir = Files.createTempDirectory("jdk-fixture").toFile()
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        val stage = dir.resolve("jdk-$jdkVersion").apply { mkdirs() }
        // Dereference links (distro JDKs link into /etc); dangling ones are skipped, not fatal. Fastest
        // gzip level: the archive is unpacked once per build and never shipped.
        TestProject.run(
            "sh",
            "-c",
            "cd '${home.absolutePath}' && tar -chf - . 2>/dev/null | (cd '${stage.absolutePath}' && tar -xf -) && " +
                "cd '${dir.absolutePath}' && tar -cf - '${stage.name}' | gzip -1 > jdk.tar.gz",
        )
        stage.deleteRecursively()
        dir.resolve("jdk.tar.gz")
    }
}
