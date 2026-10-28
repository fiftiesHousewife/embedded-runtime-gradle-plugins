package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import io.github.fiftieshousewife.appcore.tasks.SignerIdentity
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.time.Duration

/**
 * Smoke-tests published app archives from any build, such as a pipeline checking a release before rolling it
 * out, exactly as the producing build does. With a runtime policy it first checks each archive against it; with
 * a signer identity ([AppSmokeTestExtension]) it first verifies each archive's Sigstore bundle ([SigstoreBundles]).
 */
abstract class AppSmokeTestPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("base")
        val declared =
            project.configurations.dependencyScope("smokeTest") {
                description = "Published app archives to smoke-test on this machine"
            }
        val machineArchives = MachineArchives.resolvable(project, "smokeTestArchives", declared)
        val extension = extension(project)
        val smoke =
            project.tasks.register("appSmokeTest", AppSmokeTest::class.java) {
                configure(this, project, extension)
                archiveFiles.from(machineArchives)
            }
        // Whether to verify is known once the build script has set (or not set) a signer: a build that
        // doesn't verify never resolves the bundles or sigstore-java.
        project.afterEvaluate {
            if (extension.signerIdentity.isPresent) {
                smoke.configure {
                    SigstoreBundles.verify(project, machineArchives, this)
                }
            }
        }
        project.tasks.named("check") { dependsOn(smoke) }
    }

    private fun configure(task: AppSmokeTest, project: Project, extension: AppSmokeTestExtension) = task.apply {
        group = AppWiring.VERIFICATION_GROUP
        description = "Starts the smokeTest archives built for this machine and waits for their healthcheck"
        signer.set(extension.signerIdentity.zip(extension.signerIssuer, ::SignerIdentity))
        trustedRoot.set(extension.sigstoreTrustedRoot)
        healthTimeout.convention(extension.healthTimeout)
        requiredPolicy.from(
            RuntimePolicySource.files(project, property(project, "securityPolicy").orElse(extension.securityPolicy)),
        )
        resultFile.set(project.layout.buildDirectory.file("app-smoke-test/result.txt"))
    }

    private fun extension(project: Project): AppSmokeTestExtension {
        val extension = project.extensions.create(AppSmokeTestExtension.NAME, AppSmokeTestExtension::class.java)
        val projectDir = project.layout.projectDirectory.asFile
        extension.signerIdentity.convention(property(project, "signerIdentity"))
        extension.signerIssuer.convention(property(project, "signerIssuer").orElse(SigstoreBundles.GITHUB_ACTIONS))
        extension.sigstoreTrustedRoot.convention(
            project.layout.file(property(project, "sigstoreTrustedRoot").map(projectDir::resolve)),
        )
        extension.healthTimeout.convention(DEFAULT_TIMEOUT)
        return extension
    }

    private fun property(project: Project, name: String) =
        project.providers.gradleProperty("${AppSmokeTestExtension.NAME}.$name")

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
