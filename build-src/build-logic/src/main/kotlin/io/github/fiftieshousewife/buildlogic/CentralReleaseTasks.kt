package io.github.fiftieshousewife.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.time.Duration
import java.time.Instant

/** `verifyMavenCentralBundle`: fails unless the staged release passes [CentralBundle.problems]. */
@UntrackedTask(because = "checks whatever is staged now")
abstract class VerifyCentralBundle : DefaultTask() {
    @get:Internal abstract val staging: DirectoryProperty

    @get:Input abstract val namePrefix: Property<String>

    /** Whether every artifact must carry a signature: true when a signing key is configured. */
    @get:Input abstract val signed: Property<Boolean>

    @TaskAction
    fun verify() {
        val dir = staging.get().asFile
        val problems = CentralBundle.problems(dir, namePrefix.get(), signed.get())
        if (problems.isNotEmpty()) {
            throw GradleException("The staged release is not ready for Maven Central:\n" + problems.joinToString("\n") { "  - $it" })
        }
        val poms = dir.walkTopDown().count { it.name.endsWith(".pom") }
        logger.lifecycle("Ready for Maven Central: $poms POMs, all named '${namePrefix.get()}…'" +
            if (signed.get()) ", every file signed" else ", unsigned (no signing key configured)")
    }
}

/**
 * `publishToMavenCentral`: uploads the staged release as one deployment called [deploymentName],
 * then waits until Central has published it (or, with [automatic] false, validated it for a manual
 * release on central.sonatype.com).
 */
@UntrackedTask(because = "publishes to Maven Central")
abstract class PublishCentralBundle : DefaultTask() {
    @get:Internal abstract val staging: DirectoryProperty

    @get:Input abstract val deploymentName: Property<String>

    @get:Input abstract val automatic: Property<Boolean>

    @get:Internal abstract val username: Property<String>

    @get:Internal abstract val password: Property<String>

    @TaskAction
    fun publish() {
        if (!username.isPresent || !password.isPresent) {
            throw GradleException("Set mavenCentralUsername and mavenCentralPassword (a Central Portal user token)")
        }
        val portal = CentralPortal(username.get(), password.get())
        val bundle = CentralBundle.zip(staging.get().asFile, temporaryDir.resolve("bundle.zip")).readBytes()
        val id = portal.upload(bundle, deploymentName.get(), automatic.get())
        logger.lifecycle("Uploaded deployment ${deploymentName.get()} ($id); waiting for Central to validate it")
        val done = if (automatic.get()) setOf("PUBLISHING", "PUBLISHED") else setOf("VALIDATED")
        val deadline = Instant.now() + Duration.ofMinutes(WAIT_MINUTES)
        while (Instant.now() < deadline) {
            val (state, errors) = portal.status(id)
            when (state) {
                in done -> return logger.lifecycle("Deployment ${deploymentName.get()} is $state")
                "FAILED" -> throw GradleException("Central rejected ${deploymentName.get()} ($id): $errors")
                else -> Thread.sleep(POLL_MILLIS)
            }
        }
        throw GradleException("${deploymentName.get()} ($id) was not validated in $WAIT_MINUTES minutes; see central.sonatype.com")
    }

    private companion object {
        const val WAIT_MINUTES = 20L
        const val POLL_MILLIS = 5_000L
    }
}
