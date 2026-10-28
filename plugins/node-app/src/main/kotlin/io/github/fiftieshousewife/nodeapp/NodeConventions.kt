package io.github.fiftieshousewife.nodeapp

import org.gradle.api.Project

/** The defaults behind `nodeApp {}`. */
internal object NodeConventions {
    fun apply(project: Project, extension: NodeAppExtension) {
        val projectDir = project.layout.projectDirectory
        // src/deploy/ holds conf.defaults and the Dockerfile override, so it is not application code.
        extension.appFiles.convention(
            extension.buildScript
                .map { projectDir.dir("dist").asFileTree }
                .orElse(projectDir.dir("src").asFileTree.matching { exclude("deploy/**") }),
        )
        extension.entryPoint.convention("main.js")
        // Node.js has no classpath for defaults, so they ship beside the app.
        extension.configDefaults.convention(projectDir.dir("src/deploy/conf"))
        extension.testScript.convention("test")
        extension.nodeArgs.convention(emptyList())
        extension.installScripts.convention(emptyList())
    }
}
