import com.github.gradle.node.npm.task.NpxTask

// The Renovate presets in renovate/, checked by Renovate's own validator on the Node.js the
// catalogue pins. Renovate's version and lockfile are in build-logic/renovate-validator.
plugins {
    base
    id("com.github.node-gradle.node")
}

val libs = versionCatalogs.named("libs")
val presetDir = layout.projectDirectory.dir("renovate")

node {
    version = libs.findVersion("node").get().requiredVersion
    download = true
    // The repository is declared in settings: the settings plugins prefer settings repositories.
    distBaseUrl = null
    nodeProjectDir = layout.projectDirectory.dir("build-logic/renovate-validator")
    npmInstallCommand = "ci"
}

val validateRenovatePresets =
    tasks.register<NpxTask>("validateRenovatePresets") {
        group = "verification"
        description = "Checks the Renovate presets in renovate/ with Renovate's config validator"
        dependsOn(tasks.named("npmInstall"))
        val presets = presetDir.asFileTree.matching { include("*.json") }
        inputs.files(presets)
        command = "renovate-config-validator"
        args = presets.elements.map { files -> files.map { it.asFile.absolutePath }.sorted() }
    }

tasks.named("check") { dependsOn(validateRenovatePresets) }
