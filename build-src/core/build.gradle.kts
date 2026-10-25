plugins {
    id("embedded-runtime.gradle-plugin")
    `java-test-fixtures`
}

description = "Shared machinery of the java-app and node-app plugins, and the app-smoke-test plugin for consuming builds"

gradlePlugin {
    plugins {
        register("appSmokeTest") {
            id = "io.github.fiftieshousewife.app-smoke-test"
            implementationClass = "io.github.fiftieshousewife.appcore.AppSmokeTestPlugin"
            displayName = "Smoke-test published app archives"
            description =
                "Resolves the java-app or node-app archive built for this machine's OS and CPU, starts it and " +
                "waits for its health check"
            tags = listOf("smoke-test", "deployment", "verification")
        }
    }
}

// The settings plugins align project-plugin versions with their own, read from this resource.
val versionResource =
    tasks.register<WriteProperties>("versionResource") {
        destinationFile = layout.buildDirectory.file("generated/version/io/github/fiftieshousewife/appcore/version.properties")
        property("version", project.version.toString())
    }
sourceSets.main {
    resources.srcDir(versionResource.map { layout.buildDirectory.dir("generated/version").get() })
}

dependencies {
    // TestProject: the TestKit harness both plugins' functional tests build fixture projects with.
    testFixturesApi(gradleTestKit())
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter.api)
}

// The test fixtures are for this repository's functional tests, not for consumers of app-core.
listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { name ->
    (components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(configurations[name]) { skip() }
}
