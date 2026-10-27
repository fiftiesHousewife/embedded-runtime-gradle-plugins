pluginManagement {
    // A real project pins a published version instead of this source build.
    includeBuild("../../plugins")
}

plugins {
    id("io.github.fiftieshousewife.java-app.settings")
}

rootProject.name = "docker-service"
