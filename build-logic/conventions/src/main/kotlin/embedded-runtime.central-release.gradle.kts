import io.github.fiftieshousewife.buildlogic.PublishCentralBundle
import io.github.fiftieshousewife.buildlogic.VerifyCentralBundle

// The Publisher API names the deployment, which the Vanniktech plugin's upload can't.
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
    val projectName = rootProject.name
    deploymentName.set(providers.gradleProperty("releaseVersion").map { "$projectName-$it" })
    automatic.set(true)
    username.set(providers.gradleProperty("mavenCentralUsername"))
    password.set(providers.gradleProperty("mavenCentralPassword"))
}
