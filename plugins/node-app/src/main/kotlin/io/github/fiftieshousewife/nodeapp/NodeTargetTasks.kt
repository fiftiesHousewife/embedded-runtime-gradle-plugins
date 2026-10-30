package io.github.fiftieshousewife.nodeapp

import io.github.fiftieshousewife.appcore.AppWiring
import io.github.fiftieshousewife.appcore.EcosystemSpec
import io.github.fiftieshousewife.appcore.Launchers
import io.github.fiftieshousewife.appcore.Target
import io.github.fiftieshousewife.nodeapp.tasks.BundleTrustedCertificates
import io.github.fiftieshousewife.nodeapp.tasks.NpmProductionInstall
import io.github.fiftieshousewife.nodeapp.tasks.VerifyNodeModules
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

/** What a Node app puts in each target's archive: the target's node binary and its production `node_modules`. */
internal class NodeTargetTasks(
    private val project: Project,
    private val extension: NodeAppExtension,
    private val wiring: AppWiring,
    private val node: PinnedNode,
) {
    private val distributions = NodeDistributions(project, node.version, wiring)
    private val group = NodeAppPlugin.ECOSYSTEM.taskGroup

    /** The contribution for [target]: this machine's distribution is reused for its own target. */
    fun spec(target: Target, npmBuild: TaskProvider<*>): EcosystemSpec {
        val distribution = if (target == wiring.host) node.extracted else distributions.forTarget(target)
        val prodModules = productionInstall(target).flatMap { it.outputDir }.map { it.dir("node_modules") }
        val verified = verify(target, prodModules)
        val runtime = runtime(target, distribution)
        val launch = launch()
        return EcosystemSpec(
            runtimeName = "nodejs",
            runtimeVersion = node.version,
            launchers = Launchers.Generated(
                launch.map(NodeLaunchCommand::posix),
                launch.map(NodeLaunchCommand::windows),
            ),
            runtime = runtime,
            contents = {
                // A distribution's contents can't depend on tasks directly; an empty collection built by them can.
                from(project.files().builtBy(npmBuild, verified))
                into("security") { from(trustedCertificates) }
                into("app") {
                    from(extension.appFiles)
                    from(project.layout.projectDirectory.file("package.json"))
                }
                into("app/node_modules") { from(prodModules) { exclude(".bin/**", ".package-lock.json") } }
            },
            sbom = { nodeModules.set(prodModules) },
        )
    }

    /** The launcher's command: the security options first, then `nodeArgs`. */
    private fun launch(): Provider<NodeLaunch> {
        val security = extension.security
        val noValue = project.provider { listOf<String>() }
        val securityOptions =
            security.tlsMinVersion.map { listOf(it) }.orElse(noValue)
                .zip(security.tlsCipherList.map { listOf(it) }.orElse(noValue)) { min, ciphers ->
                    NodeRuntimeSecurity.options(min.firstOrNull(), ciphers.firstOrNull())
                }
        val args = securityOptions.zip(extension.nodeArgs) { options, nodeArgs -> options + nodeArgs }
        return extension.entryPoint
            .zip(args, ::NodeLaunch)
    }

    /** One bundle for every target: certificates don't depend on the OS. */
    private val trustedCertificates by lazy {
        project.tasks.register("nodeTrustedCertificates", BundleTrustedCertificates::class.java) {
            group = this@NodeTargetTasks.group
            description =
                "Bundles nodeApp.security.trustedCertificates and the runtime policy's into security/trusted-cas.pem"
            certificates.from(extension.security.trustedCertificates)
            policy.from(wiring.runtimePolicy)
            bundle.set(project.layout.buildDirectory.file("app-work/node-security/trusted-cas.pem"))
        }
    }

    private fun productionInstall(target: Target) =
        project.tasks.register("npmInstallProduction${target.taskSuffix}", NpmProductionInstall::class.java) {
            group = this@NodeTargetTasks.group
            description = "npm ci --omit=dev for ${target.id}, isolated from the working tree"
            dependsOn(node.extracted, node.npmrc)
            nodeHome.set(node.home)
            nodeVersion.set(node.version)
            npmrc.set(node.npmrc.flatMap { it.outputFile })
            packageJson.set(project.layout.projectDirectory.file("package.json"))
            packageLock.set(project.layout.projectDirectory.file("package-lock.json"))
            val host = wiring.host
            npmArgs.set(extension.installScripts.map { ProductionInstallArgs.of(it.isEmpty(), target, host) })
            outputDir.set(wiring.workDir.map { it.dir("npm-production/${target.id}") })
        }

    private fun verify(target: Target, prodModules: Provider<Directory>) =
        project.tasks.register("verifyNodeModules${target.taskSuffix}", VerifyNodeModules::class.java) {
            group = AppWiring.VERIFICATION_GROUP
            description = "Checks the ${target.id} production node_modules for host-specific install-script output"
            nodeModules.set(prodModules)
            crossBuild.set(target != wiring.host)
            ignoredScripts.set(extension.installScripts.map(List<String>::isEmpty))
            portable.set(extension.installScripts)
            reportFile.set(wiring.workDir.map { it.file("node-modules-report/${target.id}.txt") })
        }

    private fun runtime(target: Target, distribution: TaskProvider<Sync>) =
        project.tasks.register("nodeRuntime${target.taskSuffix}", Sync::class.java) {
            group = this@NodeTargetTasks.group
            description = "Selects the node binary and licence from the ${target.id} distribution"
            from(distribution) { include("bin/node", "node.exe", "LICENSE") }
            into(wiring.workDir.map { it.dir("node-runtime/${target.id}") })
        }
}
