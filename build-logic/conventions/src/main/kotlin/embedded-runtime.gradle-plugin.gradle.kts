import com.vanniktech.maven.publish.GradlePublishPlugin

// A published Gradle plugin module: the shared Kotlin conventions, Maven Central and the Plugin
// Portal, and a functional test suite that runs real Gradle builds through TestKit.
plugins {
    id("embedded-runtime.kotlin")
    id("com.gradle.plugin-publish")
    id("com.vanniktech.maven.publish")
}

val libs = versionCatalogs.named("libs")
val projectUrl = "https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins"

testing {
    suites {
        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter(libs.findVersion("junit").get().requiredVersion)
            dependencies {
                implementation(project())
                implementation(gradleTestKit())
                implementation(testFixtures(project(":app-core")))
            }
            targets.configureEach {
                testTask.configure {
                    // Each test runs whole Gradle builds; separate forks run them side by side. Capped,
                    // because every fork starts TestKit daemons of its own.
                    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
                    // Windows can't delete files a TestKit daemon still holds open, which would fail
                    // the test; CI's Windows runners are thrown away after the job anyway.
                    if (System.getProperty("os.name").startsWith("Windows")) {
                        systemProperty("junit.jupiter.tempdir.cleanup.mode.default", "never")
                    }
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(testing.suites.named("functionalTest")) }

// Maven Central: the module, its plugin markers (so builds that list mavenCentral() in
// pluginManagement resolve the plugin ids), sources and the Dokka javadoc, each signed when a key
// is configured (signingInMemoryKey, signing.keyId or signing.gnupg.keyName in
// ~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_* variables; nothing is baked in). Every module
// publishes into one staging repository, which the plugin build's root checks and uploads as a single
// deployment (embedded-runtime.central-release).
mavenPublishing {
    configure(GradlePublishPlugin())
    val signingConfigured = listOf("signingInMemoryKey", "signing.keyId", "signing.gnupg.keyName")
        .any { providers.gradleProperty(it).isPresent }
    if (signingConfigured) signAllPublications()
}
publishing {
    repositories {
        maven {
            name = "centralStaging"
            url = uri(rootDir.resolve("build/central-staging"))
        }
    }
}
tasks.withType<PublishToMavenRepository>().matching { it.name.endsWith("ToCentralStagingRepository") }.configureEach {
    mustRunAfter(":cleanMavenCentralStaging")
}

// What Maven Central and search show for each POM, under the project's own name: the module by what
// it is, each plugin marker by its plugin's display name and description.
val moduleTitle =
    mapOf(
        "app-core" to "shared core and app-smoke-test plugin",
        "java-app" to "Java app plugin",
        "node-app" to "Node.js app plugin",
    ).getValue(project.name)
publishing.publications.withType<MavenPublication>().configureEach {
    val pluginName = name.removeSuffix("PluginMarkerMaven").takeIf { it != name }
    val plugin = providers.provider { pluginName?.let { gradlePlugin.plugins.findByName(it) } }
    pom {
        name = plugin.map { "Embedded Runtime: ${it.displayName} (${it.id})" }
            .orElse("Embedded Runtime: $moduleTitle")
        description = plugin.map { it.description!! }.orElse(project.description!!)
    }
}

gradlePlugin {
    website = projectUrl
    vcsUrl = "$projectUrl.git"
    testSourceSets.add(sourceSets["functionalTest"])
}
