package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import java.io.File

/** The module the fixture app serves HTTP with, which jdeps also finds: named so the tests don't depend on it. */
const val FIXTURE_MODULES = """additionalModules.add("jdk.httpserver")"""

/** How a test's fixture differs from the default: the `javaApp {}` body, extra build script, JDK sources. */
data class JavaServiceOptions(
    val javaApp: String = FIXTURE_MODULES,
    val build: String = "",
    val publishJdk: Boolean = true,
    val properties: List<Pair<String, String>> = emptyList(),
    /** Only with [publishJdk] false: publishing uses the system `tar`. */
    val runsOnWindows: Boolean = false,
)

/** A fixture file from `src/functionalTest/resources/fixtures`. */
fun fixture(path: String): String = JavaServiceOptions::class.java.getResource("/fixtures/$path")!!.readText()

/**
 * A small HTTP service applying java-app, with the test JDK published on a fake download site for
 * this machine and for `linux-aarch64` unless [JavaServiceOptions.publishJdk] is false.
 */
fun javaService(dir: File, options: JavaServiceOptions = JavaServiceOptions()): TestProject =
    TestProject(dir, options.runsOnWindows).apply {
        if (options.publishJdk) JdkFixture.publish(this, TestProject.hostTarget(), "linux-aarch64")
        gradleProperties(*JdkFixture.properties(this), *options.properties.toTypedArray())
        settings(JavaAppSettingsPlugin.ID, "svc")
        file(
            "build.gradle.kts",
            fixture("java-service/build.gradle.kts.template")
                .replace("@FEATURE@", JdkFixture.javaFeature.toString())
                .replace("@JAVA_APP@", options.javaApp)
                .replace("@BUILD@", options.build),
        )
        file("src/main/java/test/Main.java", fixture("java-service/Main.java"))
        file("src/deploy/conf/app.properties", "key=value")
    }
