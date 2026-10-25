package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * The JDK running the tests, repackaged in Adoptium's layout under [TestProject.sources]. It is the
 * only Java release the fake site holds, so a build under test must ask for [javaFeature].
 */
object JdkFixture {
    val jdkVersion: String = Runtime.version().let { v -> v.version().joinToString(".") + "+" + v.build().orElse(1) }
    val javaFeature: Int = Runtime.version().feature()

    /** Points the build at the fake site and pins [jdkVersion]. */
    fun properties(project: TestProject) =
        arrayOf(
            JdkSources.PUBLIC_URL to
                project.sources
                    .resolve("adoptium")
                    .toURI()
                    .toString(),
            JdkSources.VERSION_PREFIX + javaFeature to jdkVersion,
        )

    /** Publishes the test JDK as `OpenJDK<f>U-jdk_<arch>_<os>_hotspot_<v>.tar.gz` for each target id. */
    fun publish(
        project: TestProject,
        vararg targets: String = arrayOf(TestProject.hostTarget()),
    ) {
        val home = File(System.getProperty("java.home"))
        assumeTrue(home.resolve("jmods").isDirectory, "the test JDK must ship jmods/")
        val stage = project.dir.resolve("stage/jdk-$jdkVersion")
        if (!stage.exists()) {
            stage.mkdirs()
            // Dereference links (distro JDKs link into /etc); dangling ones are skipped, not fatal.
            TestProject.run("sh", "-c", "cd '${home.absolutePath}' && tar -chf - . 2>/dev/null | (cd '${stage.absolutePath}' && tar -xf -)")
        }
        targets.forEach { t ->
            val (os, arch) = t.split("-")
            val out =
                project.sources
                    .resolve("adoptium/temurin$javaFeature-binaries/releases/download/jdk-$jdkVersion")
                    .resolve("OpenJDK${javaFeature}U-jdk_${arch}_${os}_hotspot_${jdkVersion.replace('+', '_')}.tar.gz")
            out.parentFile.mkdirs()
            TestProject.run("tar", "-czf", out.absolutePath, "-C", stage.parentFile.absolutePath, stage.name)
        }
    }
}
