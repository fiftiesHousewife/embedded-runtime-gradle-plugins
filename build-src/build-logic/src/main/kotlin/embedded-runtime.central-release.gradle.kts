import io.github.fiftieshousewife.buildlogic.PublishCentralBundle
import io.github.fiftieshousewife.buildlogic.VerifyCentralBundle

// The plugin build's root: releases every module to Maven Central as one deployment named after
// the project. Each module publishes into a staging repository (embedded-runtime.gradle-plugin);
// the bundle is checked here and uploaded with the Central Portal's Publisher API, which, unlike
// the Vanniktech plugin's upload, lets the deployment be named.
//   ./gradlew -p build-src verifyMavenCentralBundle -PreleaseVersion=1.2.0   → stage and check only
//   ./gradlew -p build-src publishToMavenCentral   -PreleaseVersion=1.2.0   → and release
val stagingDir = layout.buildDirectory.dir("central-staging")

val cleanStaging = tasks.register<Delete>("cleanMavenCentralStaging") { delete(stagingDir) }
val stage =
    tasks.register("stageMavenCentral") {
        group = "publishing"
        description = "Publishes every module, signed when a key is configured, into build/central-staging"
        dependsOn(cleanStaging, subprojects.map { "${it.path}:publishAllPublicationsToCentralStagingRepository" })
    }
val verify =
    tasks.register<VerifyCentralBundle>("verifyMavenCentralBundle") {
        group = "verification"
        description = "Checks the staged release: POM names and metadata, checksums and signatures"
        dependsOn(stage)
        staging.set(stagingDir)
        namePrefix.set("Embedded Runtime: ")
        signed.set(
            listOf("signingInMemoryKey", "signing.keyId", "signing.gnupg.keyName")
                .any { providers.gradleProperty(it).isPresent },
        )
    }
tasks.register<PublishCentralBundle>("publishToMavenCentral") {
    group = "publishing"
    description = "Releases the checked bundle to Maven Central as one deployment named after the project"
    dependsOn(verify)
    staging.set(stagingDir)
    // A local copy: a lambda reading the script's own values would hold the script, which the
    // configuration cache can't store.
    val projectName = rootProject.name
    deploymentName.set(providers.gradleProperty("releaseVersion").map { "$projectName-$it" })
    automatic.set(true)
    username.set(providers.gradleProperty("mavenCentralUsername"))
    password.set(providers.gradleProperty("mavenCentralPassword"))
}
