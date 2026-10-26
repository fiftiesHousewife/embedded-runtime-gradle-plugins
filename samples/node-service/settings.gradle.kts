pluginManagement {
    // Inside this repository the sample builds against the plugin source.
    // A real project instead pins a published version: id("io.github.fiftieshousewife.node-app.settings") version "0.0.1"
    includeBuild("../../build-src")
}

plugins {
    id("io.github.fiftieshousewife.node-app.settings")
}

rootProject.name = "node-service"
