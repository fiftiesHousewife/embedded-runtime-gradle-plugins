pluginManagement {
    // Inside this repository the sample builds against the plugin source.
    // A real project instead pins a published version: id("io.github.fiftieshousewife.java-app.settings") version "0.0.4"
    includeBuild("../../plugins")
}

plugins {
    id("io.github.fiftieshousewife.java-app.settings")
}

rootProject.name = "java-service"
