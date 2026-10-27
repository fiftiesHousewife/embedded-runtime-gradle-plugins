// The build's own tooling: the convention plugins the published modules apply, and the detekt
// rules their detekt runs load.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
include("conventions", "detekt-rules")
