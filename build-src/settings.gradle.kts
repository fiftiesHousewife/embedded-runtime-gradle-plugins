// This build produces the plugins, so it is the one build that cannot apply them: it declares
// public repositories itself. Behind Artifactory, gradle/init.d/embedded-runtime-artifactory.init.gradle.kts
// redirects them.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        // Most build plugins publish to both; preferring Central means a corporate mirror of
        // Central covers nearly everything, and only genuinely portal-only plugins need
        // plugins.gradle.org (and its artifact CDN, plugins-artifacts.gradle.org) opened up.
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
    // One version catalogue for the whole repository, owned by the root build.
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "embedded-runtime-gradle-plugins"

// Published as io.github.fiftieshousewife:app-core / java-app / node-app.
include("app-core", "java-app", "node-app")
project(":app-core").projectDir = file("core")
