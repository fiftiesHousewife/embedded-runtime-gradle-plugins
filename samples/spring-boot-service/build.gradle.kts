import io.github.fiftieshousewife.appcore.Target

plugins {
    id("io.github.fiftieshousewife.java-app")
}

group = "io.github.fiftieshousewife.samples"
version = "1.0.0"
description = "Order service on Spring Boot with its own Oracle Java runtime"

application {
    mainClass = "io.github.fiftieshousewife.samples.spring.Application"
}

dependencies {
    // Spring Boot's dependency management without its plugin: no fat jar is needed.
    implementation(platform(libs.spring.boot.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}

javaApp {
    javaVersion = 25
    targets(Target.host().id, "linux-x64", "linux-aarch64")
}

val colima = tasks.register<StartColima>("colima")

/**
 * On a Mac with Colima and no DOCKER_HOST, starts a native Colima VM for Testcontainers (an emulated
 * x86_64 one can't run UBI 9, whose glibc needs x86-64-v2); elsewhere, the engine docker is set up for.
 */
abstract class StartColima @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @Internal val profile = "docker-test"

    @Internal val wanted = System.getProperty("os.name").startsWith("Mac") && System.getenv("DOCKER_HOST") == null &&
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "colima").canExecute() }

    @Internal val environment = if (!wanted) emptyMap() else mapOf(
        "DOCKER_HOST" to "unix://${System.getProperty("user.home")}/.colima/$profile/docker.sock",
        "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE" to "/var/run/docker.sock",
    )

    init { onlyIf("Colima runs the containers here") { wanted } }

    @TaskAction
    fun start() {
        val running = exec.exec { commandLine("colima", "status", profile); isIgnoreExitValue = true }.exitValue == 0
        val arch = if (System.getProperty("os.arch") == "aarch64") "aarch64" else "x86_64"
        if (!running) exec.exec { commandLine("colima", "start", profile, "--arch", arch, "--vm-type", "vz") }
    }
}

// ./gradlew dockerTest builds each Dockerfile's image with Testcontainers and checks it; build and check don't.
testing {
    suites {
        register<JvmTestSuite>("dockerTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies { implementation(libs.testcontainers.junit.jupiter) }
            targets.all {
                testTask.configure {
                    val context = tasks.named<Sync>("appDockerContext").map { it.destinationDir }
                    inputs.dir(context)
                    systemProperty("context", context.get().path)
                    dependsOn(colima)
                    environment(colima.get().environment)
                }
            }
        }
    }
}
