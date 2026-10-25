package io.github.fiftieshousewife.appcore

import io.github.fiftieshousewife.appcore.tasks.AppSmokeTest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
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
 * as the producing build's own smoke test does.
 */
abstract class AppSmokeTestPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("base")
        val host = Target.host()
        val declared =
            project.configurations.dependencyScope("smokeTest") {
                description = "Published app archives to smoke-test on this machine"
            }
        val resolved =
            project.configurations.resolvable("smokeTestArchives") {
                extendsFrom(declared.get())
                isTransitive = false
                // Only the target is requested, so a module published without Gradle Module Metadata
                // still resolves when its dependency names the classifier and extension instead.
                attributes {
                    attribute(
                        OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                        project.objects.named(OperatingSystemFamily::class.java, AppPublishing.osFamilyOf(host)),
                    )
                    attribute(
                        MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
                        project.objects.named(MachineArchitecture::class.java, AppPublishing.architectureOf(host)),
                    )
                }
            }
        val smoke =
            project.tasks.register("appSmokeTest", AppSmokeTest::class.java) {
                group = AppWiring.VERIFICATION_GROUP
                description = "Resolves each smokeTest archive for this machine, starts it and waits for its healthcheck"
                archiveFiles.from(resolved)
                healthTimeout.convention(DEFAULT_TIMEOUT)
                resultFile.set(project.layout.buildDirectory.file("app-smoke-test/result.txt"))
            }
        project.tasks.named("check") { dependsOn(smoke) }
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
