plugins {
    id("embedded-runtime.gradle-plugin")
}

description = "Packages a Java application with a pinned, jlink-trimmed embedded runtime"

dependencies {
    api(project(":app-core"))
    // The functional tests package the test JDK as an RPM, as an organisation's binary repository holds one.
    "functionalTestImplementation"(libs.redline)
}

// The JDK pins are versions in gradle/libs.versions.toml (`jdk-<distribution>-<feature>`, which the
// catalogue spells `jdk.<distribution>.<feature>`): shipped in the jar as jdks.properties for JdkCatalog.
val jdkPins =
    tasks.register<WriteProperties>("jdkPins") {
        destinationFile =
            layout.buildDirectory.file("generated/pins/io/github/fiftieshousewife/javaapp/jdks.properties")
        val catalog = versionCatalogs.named("libs")
        catalog.versionAliases.filter { it.startsWith("jdk.") }.forEach {
            property(it, catalog.findVersion(it).get().requiredVersion)
        }
    }
sourceSets.main {
    resources.srcDir(jdkPins.map { layout.buildDirectory.dir("generated/pins").get() })
}

gradlePlugin {
    plugins {
        register("javaAppSettings") {
            id = "io.github.fiftieshousewife.java-app.settings"
            implementationClass = "io.github.fiftieshousewife.javaapp.JavaAppSettingsPlugin"
            displayName = "Java app repositories and JDK toolchains"
            description =
                "Routes Maven, plugin and JDK resolution to the public internet or a corporate " +
                "binary repository, and provisions compile JDKs at the exact version that gets embedded"
            tags = listOf("toolchains", "jdk", "binaryRepo")
        }
        register("javaApp") {
            id = "io.github.fiftieshousewife.java-app"
            implementationClass = "io.github.fiftieshousewife.javaapp.JavaAppPlugin"
            displayName = "Java app with embedded runtime"
            description =
                "Packages a Java 11 to 27 application with a pinned, jlink-trimmed runtime from " +
                "Temurin, Oracle, Corretto, Zulu, Liberica, Microsoft or SapMachine"
            tags = listOf("jlink", "packaging", "deployment")
        }
    }
}

// A build holding both Java and Node apps applies both settings plugins; the functional test that
// proves this needs node-app on the TestKit plugin classpath too.
val nodeAppPlugin = configurations.dependencyScope("nodeAppPlugin")
val nodeAppPluginClasspath =
    configurations.resolvable("nodeAppPluginClasspath") {
        extendsFrom(nodeAppPlugin.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        }
    }
dependencies {
    nodeAppPlugin(project(":node-app"))
}
tasks.pluginUnderTestMetadata {
    pluginClasspath.from(nodeAppPluginClasspath)
}
