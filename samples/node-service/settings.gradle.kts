pluginManagement {
    // A real project pins a published version instead of this source build.
    includeBuild("../../plugins")
}

plugins {
    id("io.github.fiftieshousewife.node-app.settings")
}

rootProject.name = "node-service"
