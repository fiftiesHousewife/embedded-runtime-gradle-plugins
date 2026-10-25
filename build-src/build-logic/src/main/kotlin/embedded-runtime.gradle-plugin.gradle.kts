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

// Maven Central, as the Clean Code plugin publishes: the module, its plugin markers (so builds that
// list mavenCentral() in pluginManagement resolve the plugin ids), sources and the Dokka javadoc,
// released without a manual step. `./gradlew publishToMavenCentral -PreleaseVersion=x.y.z` with
// mavenCentralUsername/mavenCentralPassword and a signing key in ~/.gradle/gradle.properties or
// ORG_GRADLE_PROJECT_* variables; nothing is baked in.
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    configure(GradlePublishPlugin())
    val signingConfigured = listOf("signingInMemoryKey", "signing.keyId", "signing.gnupg.keyName")
        .any { providers.gradleProperty(it).isPresent }
    if (signingConfigured) signAllPublications()
}

gradlePlugin {
    website = projectUrl
    vcsUrl = "$projectUrl.git"
    testSourceSets.add(sourceSets["functionalTest"])
}
