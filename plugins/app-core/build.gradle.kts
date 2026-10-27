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

// With an index: a jar's resources can't be listed.
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

val versionResource =
    tasks.register<WriteProperties>("versionResource") {
        destinationFile =
            layout.buildDirectory.file("generated/version/io/github/fiftieshousewife/appcore/version.properties")
        property("version", project.version.toString())
        property(
            "dockerBaseImage",
            "registry.access.redhat.com/ubi9/ubi-minimal:${libs.versions.ubi.minimal.tag.get()}",
        )
        property("dockerBaseDigest", libs.versions.ubi.minimal.digest.get())
        property("sigstoreJava", libs.versions.sigstore.get())
        property("bouncycastle", libs.versions.bouncycastle.get())
    }
sourceSets.main {
    resources.srcDir(versionResource.map { layout.buildDirectory.dir("generated/version").get() })
    resources.srcDir(aiResources.map { layout.buildDirectory.dir("generated/ai").get() })
}

dependencies {
    implementation(libs.cyclonedx.core)
    implementation(libs.packageurl)

    // Redline (2021) without its Ant tasks, on current releases of its compression and OpenPGP libraries.
    implementation(libs.redline) {
        exclude(group = "org.apache.ant")
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.bouncycastle.pg)
    implementation(libs.commons.compress)
    implementation(libs.zstd.jni)
    // Resolved only when a signer identity is set, by an isolated worker.
    compileOnly(libs.sigstore.java)
    testImplementation(libs.sigstore.java)
    constraints {
        implementation(libs.commons.compress) { because("Redline 1.2.10 asks for 1.21, which has known CVEs") }
        implementation(libs.xz) { because("Redline 1.2.10 asks for 1.4") }
    }

    testFixturesApi(gradleTestKit())
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter.api)
    testFixturesImplementation(libs.redline) {
        exclude(group = "org.apache.ant")
        exclude(group = "org.bouncycastle")
    }
}

// For this repository's functional tests, not for consumers of app-core.
listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { name ->
    (components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(configurations[name]) { skip() }
}
