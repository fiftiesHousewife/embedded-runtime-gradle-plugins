pluginManagement {
    // Inside this repository the sample builds against the plugin source.
    // A real project instead pins a published version: id("io.github.fiftieshousewife.java-app.settings") version "0.0.2"
    includeBuild("../../build-src")
}

plugins {
    id("io.github.fiftieshousewife.java-app.settings")
}

rootProject.name = "docker-service"
