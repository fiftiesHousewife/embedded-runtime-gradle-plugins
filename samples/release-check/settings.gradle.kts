pluginManagement {
    // Inside this repository the sample builds against the plugin source.
    // A real pipeline pins a published version: id("io.github.fiftieshousewife.app-smoke-test") version "0.0.4"
    includeBuild("../../build-src")
}

rootProject.name = "release-check"
