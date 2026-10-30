package io.github.fiftieshousewife.appcore

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyScopeConfiguration
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily

/**
 * Only the OS and CPU attributes are requested, so a module published without Gradle Module Metadata still
 * resolves when its dependency names the classifier and extension instead. [transitive] brings an app's
 * runtime module along with it, whose variant for the same machine the same attributes select.
 */
internal object MachineArchives {
    fun resolvable(
        project: Project,
        name: String,
        declared: NamedDomainObjectProvider<DependencyScopeConfiguration>,
        transitive: Boolean = false,
    ) = project.configurations.resolvable(name) {
        extendsFrom(declared.get())
        isTransitive = transitive
        val host = Target.host()
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
}
