plugins {
    id("embedded-runtime.gradle-plugin")
    `java-test-fixtures`
}

description =
    "Shared machinery of the java-app and node-app plugins, and the app-smoke-test plugin for consuming builds"

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
        destinationFile =
            layout.buildDirectory.file("generated/version/io/github/fiftieshousewife/appcore/version.properties")
        property("version", project.version.toString())
        property("dockerBaseImage", "registry.access.redhat.com/ubi9/ubi-minimal:${libs.versions.ubi.minimal.get()}")
    }
sourceSets.main {
    resources.srcDir(versionResource.map { layout.buildDirectory.dir("generated/version").get() })
}

dependencies {
    // The SBOM is built with the CycloneDX reference model and package-url's purl builder, so it is
    // valid by construction rather than by hand-written JSON.
    implementation(libs.cyclonedx.core)
    implementation(libs.packageurl)

    // RPMs are built by Redline, the library Nebula's ospackage plugin is built on, without the
    // plugin itself. Redline was last released in 2021: its Ant tasks are dropped, and its
    // compression and OpenPGP libraries are replaced by current releases of the same code.
    implementation(libs.redline) {
        exclude(group = "org.apache.ant")
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.bouncycastle.pg)
    constraints {
        implementation(libs.commons.compress) { because("Redline 1.2.10 asks for 1.21, which has known CVEs") }
        implementation(libs.xz) { because("Redline 1.2.10 asks for 1.4") }
    }

    // TestProject: the TestKit harness both plugins' functional tests build fixture projects with.
    testFixturesApi(gradleTestKit())
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter.api)
    // RpmFile: reads back the RPMs functional tests build.
    testFixturesImplementation(libs.redline) {
        exclude(group = "org.apache.ant")
        exclude(group = "org.bouncycastle")
    }
}

// The test fixtures are for this repository's functional tests, not for consumers of app-core.
listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { name ->
    (components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(configurations[name]) { skip() }
}
