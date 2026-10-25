// Convention plugins shared by the app-core, java-app and node-app modules: one place for the
// Kotlin, quality and Maven Central settings every published artifact needs.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
