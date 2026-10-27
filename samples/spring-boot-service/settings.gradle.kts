pluginManagement {
    includeBuild("../../plugins")
}

plugins {
    id("io.github.fiftieshousewife.java-app.settings")
}

dependencyResolutionManagement {
    // The repository's version catalogue, for the Spring Boot version.
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}

rootProject.name = "spring-boot-service"
