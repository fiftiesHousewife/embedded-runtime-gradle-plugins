import com.vanniktech.maven.publish.GradlePublishPlugin

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
                    // Capped: every fork starts TestKit daemons of its own.
                    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
                    // Windows can't delete files a TestKit daemon still holds open.
                    if (System.getProperty("os.name").startsWith("Windows")) {
                        systemProperty("junit.jupiter.tempdir.cleanup.mode.default", "never")
                    }
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(testing.suites.named("functionalTest")) }

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
