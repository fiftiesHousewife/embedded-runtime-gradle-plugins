package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import io.github.fiftieshousewife.appcore.YumRepositoryFixture
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** `image { packages(…) }`: locked per CPU from YUM repositories, then put in the container context from the lock. */
class ImagePackagesTest {
    @TempDir
    lateinit var tmp: File

    private val host = TestProject.hostTarget()

    /** [salt] makes the files differ from other tests', which the shared download cache would otherwise serve. */
    private fun project(
        packages: String = "\"zlib\"",
        salt: String = "",
        images: String = "",
        build: String = "",
    ): TestProject {
        val repositories = tmp.resolve("rpms")
        listOf("x86_64", "aarch64").forEach { arch ->
            val entries = listOf("39", "40").map { release ->
                val rpm = tmp.resolve("files/zlib-1.2.11-$release.el9.$arch.rpm").apply {
                    parentFile.mkdirs()
                    writeText("zlib $release for $arch$salt")
                }
                YumRepositoryFixture.Entry(rpm, "zlib", "1.2.11", "$release.el9", arch)
            }
            YumRepositoryFixture.write(repositories.resolve("$arch/baseos/os"), entries)
        }
        val image = """image {
            packages($packages)
            repositories.set(mapOf("ubi-9-baseos-rpms" to "${repositories.toURI()}[arch]/baseos/os/"))
        }"""
        val targets = "targets(\"$host\", \"linux-x64\", \"linux-aarch64\")"
        val options = JavaServiceOptions(javaApp = "$FIXTURE_MODULES\n$targets\n$image\n$images", build = build)
        return javaService(tmp, options).also {
            JdkFixture.publish(it, "linux-x64", "linux-aarch64")
        }
    }

    @Test
    fun `locks the newest build for each CPU and puts exactly those files in the context`() {
        val project = project()
        project.build("appImagePackagesLock")
        val lock = project.project.resolve("rpms.lock.yaml").readText()
        project.build("appDockerContext")
        val context = project.project.resolve("build/docker-context/packages")
        assertAll(
            { assertTrue(lock.contains("    evr: 1.2.11-40.el9\n"), lock) },
            { assertTrue(!lock.contains("-39."), lock) },
            { assertEquals("zlib 40 for x86_64", context.resolve("amd64/zlib-1.2.11-40.el9.x86_64.rpm").readText()) },
            { assertEquals(listOf("zlib-1.2.11-40.el9.aarch64.rpm"), context.resolve("arm64").list()!!.toList()) },
        )
    }

    @Test
    fun `a lock made elsewhere serves this build's repositories, and a package it lacks stops the build`() {
        val project = project()
        project.build("appImagePackagesLock")
        val lockFile = project.project.resolve("rpms.lock.yaml")
        lockFile.writeText(
            lockFile.readText().replace(Regex("url: \\S+/Packages/"), "url: https://cdn.invalid/Packages/"),
        )
        project.build("appDockerContext", "--offline")
        val built = project.project.resolve("build/docker-context/packages/amd64/zlib-1.2.11-40.el9.x86_64.rpm")
        val lacking = project(packages = "\"zlib\", \"libstdc++\"").buildAndFail("appDockerContext")
        assertAll(
            { assertTrue(built.isFile) },
            { assertTrue(lacking.output.contains("pins no libstdc++ for"), lacking.output) },
            { assertTrue(lacking.output.contains("run appImagePackagesLock"), lacking.output) },
        )
    }

    @Test
    fun `a file that no longer matches the lock's SHA-256 is refused`() {
        val project = project(salt = " (${System.nanoTime()})")
        project.build("appImagePackagesLock")
        tmp.resolve("rpms/x86_64/baseos/os/Packages/z/zlib-1.2.11-40.el9.x86_64.rpm").writeText("replaced")
        val refused = project.buildAndFail("appDockerContext")
        assertTrue(refused.output.contains("the download is corrupt or the package was replaced"), refused.output)
    }

    @Test
    fun `an image on micro needs no packages named, and a new release changes only its code layer`() {
        val project = project(
            packages = "",
            images = "images { register(\"ubi-micro\") { from(ubiMicro) } }",
            build = fixture("java-service/library.gradle.kts.template"),
        )
        project.build("appImagePackagesLock")
        project.build("appDockerContext")
        val context = project.project.resolve("build/docker-context")
        val dockerfile = context.resolve("Dockerfile.ubi-micro").readText()
        val libs = context.resolve("layers/amd64/libs")
        val first = LAYERS.associateWith { tree(context.resolve("layers/amd64/$it")) }
        project.build("appDockerContext", "-PappVersion=2.0.1")
        val second = LAYERS.associateWith { tree(context.resolve("layers/amd64/$it")) }
        assertAll(
            { assertTrue(project.project.resolve("rpms.lock.yaml").readText().contains("name: zlib")) },
            { assertTrue(context.resolve("packages/amd64/zlib-1.2.11-40.el9.x86_64.rpm").isFile) },
            { assertTrue(dockerfile.contains("ubi-micro:"), dockerfile) },
            { assertTrue(!dockerfile.contains("dnf"), dockerfile) },
            { assertTrue(libs.resolve("app/lib/library-1.jar").isFile, first.toString()) },
            { assertEquals(first["runtime"], second["runtime"]) },
            { assertEquals(first["libs"], second["libs"]) },
            { assertTrue(first["code"] != second["code"], "the app's own files name its version") },
        )
    }

    /** Each file's path and SHA-256, and its time: `COPY` records times, so they decide the layer's digest too. */
    private fun tree(root: File) = root.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(root).path to (YumRepositoryFixture.sha256(it) to it.lastModified()) }

    private companion object {
        val LAYERS = listOf("runtime", "libs", "code")
    }
}
