import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The repository's own detekt rules: a plain Kotlin library the published modules' detekt runs load.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = versionCatalogs.named("libs")

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
java { targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    "compileOnly"("dev.detekt:detekt-api:${libs.findVersion("detekt").get().requiredVersion}")
    "testImplementation"("dev.detekt:detekt-api:${libs.findVersion("detekt").get().requiredVersion}")
}

testing {
    suites {
        named<JvmTestSuite>("test") { useJUnitJupiter(libs.findVersion("junit").get().requiredVersion) }
    }
}
