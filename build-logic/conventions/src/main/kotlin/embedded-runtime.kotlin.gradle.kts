import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.gradle.kotlin.kotlin-dsl")
    `maven-publish`
    id("com.diffplug.spotless")
    id("dev.detekt")
    id("io.github.ben-manes.versions")
    id("de.thetaphi.forbiddenapis")
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
    jacoco
}

val libs = versionCatalogs.named("libs")
val projectUrl = "https://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins"

jacoco { toolVersion = libs.findVersion("jacoco").get().requiredVersion }

group = "io.github.fiftieshousewife"
version = providers.gradleProperty("releaseVersion").getOrElse("1.0.0-SNAPSHOT")

/**
 * The jars are loaded into the Gradle daemon, so their bytecode must be readable by the oldest JVM
 * Gradle itself runs on, not by whatever JDK is on this developer's PATH. A toolchain pins the
 * compiler and the bytecode level together, so the jar is identical wherever it is built.
 *
 * Applications *packaged* by these plugins are a separate matter: they run on their own embedded
 * runtime, and Java apps are supported from Java 11 to 27 (see `JdkCatalog.SUPPORTED_FEATURES`).
 */
val gradleMinimumJvm = libs.findVersion("plugin-jvm").get().requiredVersion.toInt()

java {
    // Any JDK 17 compiles the plugins; only embedded runtimes are pinned to one build.
    toolchain { languageVersion = JavaLanguageVersion.of(gradleMinimumJvm) }
}
kotlin {
    compilerOptions { jvmTarget = JvmTarget.fromTarget(gradleMinimumJvm.toString()) }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.findVersion("junit").get().requiredVersion)
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
val ktlintRules = mapOf("max_line_length" to "120")
spotless {
    kotlin {
        ktlint(ktlintVersion).editorConfigOverride(ktlintRules)
        targetExclude("**/build/**")
    }
    kotlinGradle {
        ktlint(ktlintVersion).editorConfigOverride(ktlintRules)
    }
}

tasks.withType<de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis>().configureEach {
    bundledSignatures = setOf("jdk-unsafe", "jdk-deprecated", "jdk-non-portable", "jdk-system-out", "jdk-internal")
}

tasks.withType<JacocoReport>().configureEach {
    reports {
        xml.required = true
        html.required = true
    }
}
tasks.named("check") { dependsOn(tasks.withType<JacocoReport>()) }

dependencies { "detektPlugins"("io.github.fiftieshousewife.build:detekt-rules") }
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("../build-logic/detekt.yml"))
    source.setFrom(
        files("src/main/kotlin", "src/test/kotlin", "src/testFixtures/kotlin", "src/functionalTest/kotlin")
            .filter { it.isDirectory },
    )
}
// detekt 2 runs its type-resolution rules only in the per-source-set tasks.
tasks.named("check") {
    dependsOn(tasks.withType<Detekt>().matching { it.name != "detekt" && !it.name.endsWith("SourceSet") })
}
tasks.withType<Detekt>().configureEach {
    jvmTarget = gradleMinimumJvm.toString()
    reports {
        html.required = true
        sarif.required = true
    }
}

tasks.withType<DependencyUpdatesTask>().configureEach {
    outputFormatter = "json"
    rejectVersionIf {
        candidate.version.substringBefore(".") != currentVersion.substringBefore(".")
    }
}

// Dokka, since javadoc alone leaves the javadoc jar empty for Kotlin sources.
dokka {
    dokkaGeneratorIsolation = ClassLoaderIsolation()
}
tasks.matching { it.name == "javadocJar" }.withType<Jar>().configureEach {
    from(tasks.named("dokkaGeneratePublicationJavadoc"))
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        name = project.name
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
            connection = "scm:git:git://github.com/fiftiesHousewife/embedded-runtime-gradle-plugins.git"
            developerConnection = "scm:git:ssh://git@github.com/fiftiesHousewife/embedded-runtime-gradle-plugins.git"
        }
    }
}

publishing {
    repositories {
        providers.gradleProperty("binaryRepo.url").orNull?.let { base ->
            maven {
                name = "binaryRepo"
                url =
                    uri(
                        "${base.trimEnd('/')}/" +
                            providers.gradleProperty("binaryRepo.repo.pluginsLocal").getOrElse("gradle-plugins-local"),
                    )
                credentials(PasswordCredentials::class)
            }
        }
    }
}
