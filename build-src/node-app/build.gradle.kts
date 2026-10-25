plugins {
    id("embedded-runtime.gradle-plugin")
}

description = "Packages a Node.js application with the pinned official Node.js runtime embedded"

dependencies {
    api(project(":app-core"))
}

gradlePlugin {
    plugins {
        register("nodeAppSettings") {
            id = "io.github.fiftieshousewife.node-app.settings"
            implementationClass = "io.github.fiftieshousewife.nodeapp.NodeAppSettingsPlugin"
            displayName = "Node app repositories"
            description =
                "Routes Maven, plugin and Node.js resolution to the public internet or a corporate Artifactory"
            tags = listOf("nodejs", "npm", "artifactory")
        }
        register("nodeApp") {
            id = "io.github.fiftieshousewife.node-app"
            implementationClass = "io.github.fiftieshousewife.nodeapp.NodeAppPlugin"
            displayName = "Node.js app with embedded runtime"
            description = "Packages a Node.js application with the pinned official Node.js runtime"
            tags = listOf("nodejs", "packaging", "deployment")
        }
    }
}
