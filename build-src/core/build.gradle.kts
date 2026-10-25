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

// The AI assistant files in the repository's ai/ folder, shipped in the jar for `aiSkills` to write
// into a project, with an index because a jar's resources cannot be listed.
val aiSource = layout.projectDirectory.dir("../../ai")
val aiResources =
    tasks.register<Sync>("aiResources") {
        val root = "io/github/fiftieshousewife/appcore/ai"
        val parts = mapOf(
            "skills" to "skills",
            "hooks" to "hooks",
            "copilot/hooks" to "copilot-hooks",
            "devin" to "devin",
        )
        parts.forEach { (dir, into) -> from(aiSource.dir(dir)) { into("$root/$into") } }
        into(layout.buildDirectory.dir("generated/ai"))
        val sources = parts.mapValues { aiSource.dir(it.key).asFile }
        sources.values.forEach { inputs.dir(it) }
        doLast {
            val listed = sources.flatMap { (dir, file) ->
                file.walkTopDown().filter(File::isFile).map {
                    parts.getValue(dir) + "/" +
                        it.relativeTo(file).invariantSeparatorsPath
                }
            }
            destinationDir.resolve("$root/index.txt").writeText(listed.sorted().joinToString("\n", postfix = "\n"))
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
    resources.srcDir(aiResources.map { layout.buildDirectory.dir("generated/ai").get() })
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
    // Reading RPM-packaged runtimes: Redline parses the headers, Commons Compress the cpio payload;
    // zstd-jni backs Commons Compress for zstd payloads, the default of RHEL 9 and later.
    implementation(libs.commons.compress)
    implementation(libs.zstd.jni)
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
