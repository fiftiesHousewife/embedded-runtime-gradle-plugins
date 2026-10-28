// This build applies the plugins it publishes, as a user's build would: a change that breaks users breaks it.
pluginManagement {
    includeBuild("plugins")
    includeBuild("build-logic")
}

plugins {
    id("io.github.fiftieshousewife.java-app.settings")
    id("io.github.fiftieshousewife.node-app.settings")
}

rootProject.name = "embedded-runtime-gradle-plugins"

listOf("java-service", "spring-boot-service", "node-service", "deployment-pipeline")
    .forEach { includeBuild("samples/$it") }

// Node.js for validateRenovatePresets, here because the settings plugins prefer settings repositories.
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist/") {
                    name = "Node.js"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
    }
}
