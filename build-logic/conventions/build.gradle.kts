import org.gradle.kotlin.dsl.support.expectedKotlinDslPluginsVersion

plugins {
    `kotlin-dsl`
}

dependencies {
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

testing {
    suites {
        named<JvmTestSuite>("test") { useJUnitJupiter(libs.versions.junit.get()) }
    }
}
