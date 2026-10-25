import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Every published module: compiled for the JVM Gradle runs on, checked by ktlint and detekt, and
// published with everything Maven Central requires.
plugins {
    id("org.gradle.kotlin.kotlin-dsl")
    `maven-publish`
    signing
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
    // No vendor constraint: any JDK 17 compiles this, unlike the runtimes that get embedded, which
    // are pinned to one exact build of one distribution.
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

// A failing test prints its whole exception, so a CI log alone explains it.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// Quality gates, following the Clean Code plugin's standards with standard tools: ktlint through
// Spotless for formatting (120 columns, set below to match .editorconfig), detekt for size, complexity, naming
// and safety rules (config/detekt/detekt.yml), Forbidden APIs for unsafe JDK calls in the compiled
// bytecode, JaCoCo for coverage, and ben-manes for dependency drift.
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

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    source.setFrom(
        files("src/main/kotlin", "src/test/kotlin", "src/testFixtures/kotlin", "src/functionalTest/kotlin")
            .filter { it.isDirectory },
    )
}
// detekt 2 runs the rules that need type resolution (LongParameterList, ForbiddenMethodCall, …) only
// in its per-source-set tasks (detektMain, detektTest, …), so check runs those, not just `detekt`.
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

// Only report upgrades within the current major, so a report never suggests a jump that needs real
// migration work.
tasks.withType<DependencyUpdatesTask>().configureEach {
    outputFormatter = "json"
    rejectVersionIf {
        candidate.version.substringBefore(".") != currentVersion.substringBefore(".")
    }
}

// The API documentation is the KDoc: Dokka renders it as HTML (dokkaGenerate) and as the javadoc
// jar every publication carries, which `javadoc` alone would leave empty for Kotlin sources.
dokka {
    // In the build's own JVM, isolated by class loader, rather than a worker process of its own.
    dokkaGeneratorIsolation = ClassLoaderIsolation()
}
tasks.matching { it.name == "javadocJar" }.withType<Jar>().configureEach {
    from(tasks.named("dokkaGeneratePublicationJavadoc"))
}

// Maven Central requires sources and javadoc jars, a POM carrying name, description, URL, licence,
// developer and SCM, and a detached PGP signature per artifact.
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
        // ./gradlew publish -PreleaseVersion=1.2.0 publishes to the corporate Gradle plugin repository.
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
