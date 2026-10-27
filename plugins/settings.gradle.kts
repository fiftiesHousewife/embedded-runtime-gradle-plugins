// The published plugins. This build produces them, so it is the one build that can't apply them.
pluginManagement {
    includeBuild("../build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Also a regular included build, so detektPlugins can depend on its detekt-rules project.
includeBuild("../build-logic")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "embedded-runtime-gradle-plugins"

// Published as io.github.fiftieshousewife:app-core / java-app / node-app.
include("app-core", "java-app", "node-app")
