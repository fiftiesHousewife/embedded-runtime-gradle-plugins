import org.gradle.kotlin.dsl.support.expectedKotlinDslPluginsVersion

plugins {
    `kotlin-dsl`
}

dependencies {
    // The modules are themselves built with kotlin-dsl, so plugin code gets the same SAM-with-receiver
    // and Gradle API wiring as a build script; this is the matching plugin artefact for that.
    implementation("org.gradle.kotlin:gradle-kotlin-dsl-plugins:$expectedKotlinDslPluginsVersion")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$embeddedKotlinVersion")
    implementation(libs.gradle.plugin.publish)
    implementation(libs.gradle.maven.publish)
    implementation(libs.gradle.versions)
    implementation(libs.gradle.spotless)
    implementation(libs.gradle.detekt)
    implementation(libs.gradle.forbiddenapis)
    implementation(libs.gradle.dokka)
    implementation(libs.gradle.node)
}

// The release logic (CentralBundle, CentralPortal) is tested like any other code.
testing {
    suites {
        named<JvmTestSuite>("test") { useJUnitJupiter(libs.versions.junit.get()) }
    }
}
