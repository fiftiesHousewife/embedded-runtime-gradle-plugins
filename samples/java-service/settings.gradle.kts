pluginManagement {
    // Inside this repository the sample builds against the plugin source.
    // A real project instead pins a published version: id("io.github.fiftieshousewife.platform.settings") version "1.0.0"
    includeBuild("../../build-src")
}

plugins {
    id("io.github.fiftieshousewife.platform.settings")
}

rootProject.name = "java-service"
