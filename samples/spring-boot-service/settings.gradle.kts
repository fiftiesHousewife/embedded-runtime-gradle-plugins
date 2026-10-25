pluginManagement {
    // Inside this repository the sample builds against the plugin source.
    includeBuild("../../build-src")
}

plugins {
    id("io.github.fiftieshousewife.java-app.settings")
}

dependencyResolutionManagement {
    // The repository's one version catalogue, so the Spring Boot version is pinned in one place.
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}

rootProject.name = "spring-boot-service"
