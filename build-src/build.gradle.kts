import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    `maven-publish`
    signing
    alias(libs.plugins.plugin.publish)
    alias(libs.plugins.versions)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

val projectUrl = "https://github.com/fiftieshousewife/platform-bundle"

group = "io.github.fiftieshousewife"
version = providers.gradleProperty("releaseVersion").getOrElse("1.0.0-SNAPSHOT")
description = "Self-contained Java and Node.js bundles with pinned runtimes"

/**
 * The plugin jar is loaded into the Gradle daemon, so its bytecode must be readable by the oldest
 * JVM Gradle itself runs on — not by the JDK that happens to be on this developer's PATH. A
 * toolchain pins the compiler and the bytecode level together, so the jar is identical wherever it
 * is built; `sourceCompatibility`/`targetCompatibility` only pinned the latter and left the build
 * depending on the daemon's JDK.
 *
 * Applications *packaged* by these plugins are a separate matter: they run on their own bundled
 * runtime, and are supported from Java 11 to Java 25 (see `RuntimeCatalog.SUPPORTED_FEATURES`).
 */
val gradleMinimumJvm = 17

java {
    // No vendor constraint: any JDK 17 compiles this, unlike the runtimes that get bundled, which
    // are pinned to one exact build of one distribution.
    toolchain {
        languageVersion = JavaLanguageVersion.of(gradleMinimumJvm)
    }
}
kotlin {
    compilerOptions { jvmTarget = JvmTarget.fromTarget(gradleMinimumJvm.toString()) }
}

gradlePlugin {
    website = projectUrl
    vcsUrl = "$projectUrl.git"
    plugins {
        register("platformSettings") {
            id = "io.github.fiftieshousewife.platform.settings"
            implementationClass = "io.github.fiftieshousewife.platform.settings.PlatformSettingsPlugin"
            displayName = "Platform repositories and toolchains"
            description =
                "Routes Maven, plugin, JDK, Node and npm resolution to the public internet " +
                "or a corporate Artifactory"
            tags = listOf("artifactory", "toolchains", "repositories")
        }
        register("javaBundle") {
            id = "io.github.fiftieshousewife.platform.java-bundle"
            implementationClass = "io.github.fiftieshousewife.platform.bundle.JavaBundlePlugin"
            displayName = "Java application bundle"
            description =
                "Packages a Java 11 to 25 application with a pinned, jlink-trimmed Temurin " +
                "or Oracle runtime"
            tags = listOf("jlink", "packaging", "deployment")
        }
        register("nodeBundle") {
            id = "io.github.fiftieshousewife.platform.node-bundle"
            implementationClass = "io.github.fiftieshousewife.platform.bundle.NodeBundlePlugin"
            displayName = "Node.js application bundle"
            description = "Packages a Node.js application with the pinned official Node.js runtime"
            tags = listOf("nodejs", "packaging", "deployment")
        }
    }
}

// The settings plugin aligns project-plugin versions with its own, read from this resource.
val pluginVersionResource =
    tasks.register<WriteProperties>("pluginVersionResource") {
        destinationFile =
            layout.buildDirectory.file("generated/plugin-version/io/github/fiftieshousewife/platform/plugin.properties")
        property("version", project.version.toString())
    }
sourceSets.main {
    resources.srcDir(pluginVersionResource.map { layout.buildDirectory.dir("generated/plugin-version").get() })
}

testing {
    suites {
        // Unit tests: the download-URL maths, which no functional test can check without reaching
        // each vendor's real site.
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
        }
        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter(libs.versions.junit)
            // Node fixtures come from the `node` on PATH; the Node test is skipped without one.
            dependencies {
                implementation(gradleTestKit())
            }
        }
    }
}
gradlePlugin.testSourceSets.add(sourceSets["functionalTest"])
tasks.check { dependsOn(testing.suites.named("functionalTest")) }

// Quality gates, matching the conventions in the sibling Clean Code project: ktlint through
// Spotless for formatting, detekt in place of SpotBugs (which reads Java bytecode patterns and has
// little to say about Kotlin), and ben-manes for dependency drift.
spotless {
    kotlin {
        ktlint(libs.versions.ktlint.get())
        targetExclude("**/build/**")
    }
    kotlinGradle {
        ktlint(libs.versions.ktlint.get())
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(files("src/main/kotlin", "src/test/kotlin", "src/functionalTest/kotlin"))
}
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = gradleMinimumJvm.toString()
    reports {
        html.required = true
        sarif.required = true
    }
}

// Same policy as the sibling project: only report upgrades within the current major, so a report
// never suggests a jump that needs real migration work.
tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>().configureEach {
    outputFormatter = "json"
    rejectVersionIf {
        candidate.version.substringBefore(".") != currentVersion.substringBefore(".")
    }
}

// Everything Maven Central requires of a publication: sources and javadoc jars, a POM carrying
// name, description, URL, licence, developer and SCM, and a detached PGP signature per artifact.
// `kotlin-dsl` already adds the sources and javadoc jars; the rest is below.
publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        name = "Platform bundle plugins"
        description = project.description
        url = projectUrl
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "fiftiesHousewife"
                name = "Pippa Newbold"
                email = "pippa.newbold@gmail.com"
            }
        }
        scm {
            url = projectUrl
            connection = "scm:git:git://github.com/fiftieshousewife/platform-bundle.git"
            developerConnection = "scm:git:ssh://git@github.com/fiftieshousewife/platform-bundle.git"
        }
    }
}

// Signing is opt-in by key: a developer publishing to mavenLocal needs no signatory, while the
// release pipeline passes -PsigningKey / -PsigningPassword (or ORG_GRADLE_PROJECT_signingKey).
val signingKey = providers.gradleProperty("signingKey")
signing {
    isRequired = signingKey.isPresent
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), providers.gradleProperty("signingPassword").orNull)
    }
    sign(publishing.publications)
}

publishing {
    repositories {
        // ./gradlew publish -PreleaseVersion=1.2.0 publishes to the corporate Gradle plugin repository;
        // ./gradlew publishPlugins publishes to the Gradle Plugin Portal instead.
        providers.gradleProperty("platform.artifactory.url").orNull?.let { base ->
            maven {
                name = "artifactory"
                url =
                    uri(
                        "${base.trimEnd('/')}/" +
                            providers.gradleProperty("platform.artifactory.repo.pluginsLocal").getOrElse("gradle-plugins-local"),
                    )
                credentials(PasswordCredentials::class)
            }
        }
    }
}
