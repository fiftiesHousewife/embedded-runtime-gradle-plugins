// A published Gradle plugin module: the shared Kotlin conventions, the Plugin Portal, and a
// functional test suite that runs real Gradle builds through TestKit.
plugins {
    id("embedded-runtime.kotlin")
    id("com.gradle.plugin-publish")
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
        }
    }
}
tasks.named("check") { dependsOn(testing.suites.named("functionalTest")) }

gradlePlugin {
    website = projectUrl
    vcsUrl = "$projectUrl.git"
    testSourceSets.add(sourceSets["functionalTest"])
}
