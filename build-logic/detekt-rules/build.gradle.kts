import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version embeddedKotlinVersion
}

group = "io.github.fiftieshousewife.build"
description = "The repository's own detekt rules: file length and comment density"

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
java { targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    compileOnly("dev.detekt:detekt-api:${libs.versions.detekt.get()}")
    testImplementation("dev.detekt:detekt-api:${libs.versions.detekt.get()}")
}

testing {
    suites {
        named<JvmTestSuite>("test") { useJUnitJupiter(libs.versions.junit.get()) }
    }
}
