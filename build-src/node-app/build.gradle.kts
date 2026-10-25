plugins {
    id("embedded-runtime.gradle-plugin")
}

description = "Packages a Node.js application with the pinned official Node.js runtime embedded"

dependencies {
    api(project(":app-core"))
}

// The Node.js pin is the `node` version in gradle/libs.versions.toml, shipped as node.properties.
val nodePin =
    tasks.register<WriteProperties>("nodePin") {
        destinationFile =
            layout.buildDirectory.file("generated/pins/io/github/fiftieshousewife/nodeapp/node.properties")
        property("node.default", libs.versions.node.get())
    }
sourceSets.main {
    resources.srcDir(nodePin.map { layout.buildDirectory.dir("generated/pins").get() })
}

gradlePlugin {
    plugins {
        register("nodeAppSettings") {
            id = "io.github.fiftieshousewife.node-app.settings"
            implementationClass = "io.github.fiftieshousewife.nodeapp.NodeAppSettingsPlugin"
            displayName = "Node app repositories"
            description =
                "Routes Maven, plugin and Node.js resolution to the public internet or a corporate binary repository"
            tags = listOf("nodejs", "npm", "binaryRepo")
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
