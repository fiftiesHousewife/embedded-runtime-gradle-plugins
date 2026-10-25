package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The JDK running the tests, repackaged in Adoptium's layout under [TestProject.sources]. It is the
 * only Java release the fake site holds, so a build under test must ask for [javaFeature].
 */
object JdkFixture {
    val jdkVersion: String = Runtime.version().let { v -> v.version().joinToString(".") + "+" + v.build().orElse(1) }
    val javaFeature: Int = Runtime.version().feature()

    /** Points the build at the fake site and pins [jdkVersion]. */
    fun properties(project: TestProject) = arrayOf(
        JdkSources.PUBLIC_URL to
            project.sources
                .resolve("adoptium")
                .toURI()
                .toString(),
        JdkSources.VERSION_PREFIX + javaFeature to jdkVersion,
    )

    /** The JDK running the tests, which the fixtures repackage. */
    val home: File get() = File(System.getProperty("java.home"))

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
