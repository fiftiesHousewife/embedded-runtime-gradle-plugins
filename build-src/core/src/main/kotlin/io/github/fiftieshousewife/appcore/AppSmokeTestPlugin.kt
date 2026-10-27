package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import io.github.fiftieshousewife.appcore.tasks.SignerIdentity
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.time.Duration

/**
 * `io.github.fiftieshousewife.app-smoke-test` — smoke-tests published app archives from any build,
 * for example a deployment pipeline checking a release before rolling it out:
 *
 * ```
 * dependencies { smokeTest("com.example:orders-service:1.4.2") }
 * ```
 *
 * `appSmokeTest` resolves the archive built for this machine's OS and CPU, using the attributes the
 * java-app and node-app publications carry in their Gradle Module Metadata, then starts it exactly
 * as the producing build's own smoke test does. With `-PappSmokeTest.securityPolicy` (or
 * `…securityPolicyArtifact`) it first checks the archive against the organisation's runtime policy.
 *
 * With `appSmokeTest { signerIdentity = … }` ([AppSmokeTestExtension]) or `-PappSmokeTest.signerIdentity`
 * it first verifies each archive's Sigstore bundle, published
 * beside it as `<archive>.sigstore.json`: only an archive signed by that identity (a regex, such as
 * one release workflow of one repository) is started. See [SigstoreBundles].
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
            RuntimePolicySource.files(
                project,
                property(project, "securityPolicy").orElse(extension.securityPolicy),
                property(project, "securityPolicyArtifact").orElse(extension.securityPolicyArtifact),
            ),
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
