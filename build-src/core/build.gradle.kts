plugins {
    id("embedded-runtime.kotlin")
    `java-test-fixtures`
}

description = "Shared machinery of the java-app and node-app plugins; not a public API"

// Published to Maven Central for the plugins to depend on: a plain library, not a plugin, so it
// needs its own sources and javadoc jars (plugin-publish adds them only to plugin modules).
java {
    withSourcesJar()
    withJavadocJar()
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
