// The plugins live in `plugins`, included here as a plugin build. Gradle builds it first, so this
// build can consume — and self-apply — the very plugins it publishes: both settings plugins below
// configure this build's repositories, JDK toolchain resolver and project-plugin versions exactly
// as they do for a consuming repository. Applying both also proves a build with Java and Node apps
// can. Dogfooding them here means a change that breaks consumers breaks this build too.
pluginManagement {
    includeBuild("plugins")
}

plugins {
    id("io.github.fiftieshousewife.java-app.settings")
    id("io.github.fiftieshousewife.node-app.settings")
}

rootProject.name = "embedded-runtime-gradle-plugins"

// The samples, as builds of their own the IDE and the root build both see; CI also builds each
// with -p samples/<name>.
listOf("java-service", "spring-boot-service", "node-service", "docker-service", "release-check")
    .forEach { includeBuild("samples/$it") }

// Node.js for the Node plugin (validateRenovatePresets). The settings plugins prefer settings
// repositories, so this one is declared here rather than by the Node plugin, for its one module.
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
