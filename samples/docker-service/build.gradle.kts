import io.github.fiftieshousewife.appcore.Target
import java.io.OutputStream
import javax.inject.Inject

plugins {
    id("io.github.fiftieshousewife.java-app")
}

group = "io.github.fiftieshousewife.samples"
version = "1.0.0"

application {
    mainClass = "io.github.fiftieshousewife.samples.docker.HelloService"
}

javaApp {
    javaVersion = 21
    // This machine (for the smoke test) and both Linux CPUs: the image uses the one matching this machine.
    targets(Target.host().id, "linux-x64", "linux-aarch64")
}

// Builds the container image from appDockerContext and proves it with Testcontainers: dockerTest
// runs it on the Dockerfile's own base image (UBI minimal), dockerTestMicro on UBI micro (the
// micro.BASE_IMAGE property), passed as --build-arg BASE_IMAGE. Each checks the container starts,
// answers its probes, is labelled and stops on SIGTERM. Skipped, not failed, unless a Docker daemon
// running Linux containers is reachable (GitHub's Windows runners run Windows containers), or
// Colima can start one. -PdockerRequired=true runs them regardless, so a missing daemon fails the
// build: CI's Linux job uses it to prove the image runs.
val dockerRequired = providers.gradleProperty("dockerRequired").map(String::toBoolean).orElse(false)

// On a Mac with Colima and no DOCKER_HOST, the images run in a Colima profile of their own,
// started on demand and native to this CPU: an emulated x86_64 VM can't run UBI 9, whose glibc
// needs x86-64-v2. -PdockerTest.colima=false uses whatever engine docker is set up for instead.
val colimaProfile = "docker-test"
val useColima =
    providers.gradleProperty("dockerTest.colima").map(String::toBoolean).orElse(true).get() &&
        System.getProperty("os.name").startsWith("Mac") &&
        providers.environmentVariable("DOCKER_HOST").orNull == null &&
        providers.environmentVariable("PATH").get().split(File.pathSeparator)
            .any { File(it, "colima").canExecute() }
val colimaHost = "unix://${System.getProperty("user.home")}/.colima/$colimaProfile/docker.sock"

/** Starts [profile] unless it is running: natively for this CPU, with Apple's virtualisation. */
abstract class StartColima @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val profile: Property<String>

    @TaskAction
    fun start() {
        val running = exec.exec {
            commandLine("colima", "status", profile.get())
            isIgnoreExitValue = true
            standardOutput = OutputStream.nullOutputStream()
            errorOutput = OutputStream.nullOutputStream()
        }.exitValue == 0
        if (running) return
        val arch = if (System.getProperty("os.arch") == "aarch64") "aarch64" else "x86_64"
        exec.exec { commandLine("colima", "start", profile.get(), "--arch", arch, "--vm-type", "vz") }
    }
}

val dockerEngine =
    tasks.register<StartColima>("dockerEngine") {
        group = "docker"
        description = "Starts the $colimaProfile Colima profile the images run in, unless it is running"
        profile = colimaProfile
        val colima = useColima // a local: the configuration cache can't hold the build script
        onlyIf("Colima runs the images here") { colima }
    }

/** DOCKER_HOST for the docker CLI and Testcontainers, and the socket Testcontainers mounts inside Colima. */
val engineEnvironment =
    if (useColima) {
        mapOf("DOCKER_HOST" to colimaHost, "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE" to "/var/run/docker.sock")
    } else {
        emptyMap()
    }
val dockerOsType =
    run {
        val environment = engineEnvironment // a local: the configuration cache can't hold the build script
        providers.exec {
            commandLine("docker", "info", "--format", "{{.OSType}}")
            environment(environment)
            isIgnoreExitValue = true
        }.standardOutput.asText // fails to start without a docker command (GitHub's macOS runners): no daemon
    }

/** Whether to build and run the images: required, or Colima can start an engine, or one is running. */
val dockerAvailable =
    run {
        val colima = useColima // locals: the configuration cache can't hold the build script
        val osType = dockerOsType
        dockerRequired.map { required ->
            required || colima || kotlin.runCatching { osType.get().trim() == "linux" }.getOrDefault(false)
        }
    }

/** One image per base: the test target that runs it, its tag, and the BASE_IMAGE it is built on. */
data class ImageVariant(val testTarget: String, val tag: String, val baseImage: String?)

val variants =
    listOf(
        ImageVariant("dockerTest", "docker-service:$version", null),
        ImageVariant(
            "dockerTestMicro",
            "docker-service:$version-micro",
            providers.gradleProperty("micro.BASE_IMAGE").get(),
        ),
    )

val dockerImages =
    variants.associate { variant ->
        variant.testTarget to
            tasks.register<Exec>(variant.testTarget.replace("dockerTest", "dockerImage")) {
                group = "docker"
                description = "Builds ${variant.tag} from the appDockerContext build context" +
                    (variant.baseImage?.let { " on $it" } ?: "")
                val context = tasks.named<Sync>("appDockerContext").map { it.destinationDir }
                inputs.dir(context)
                val buildArgs = variant.baseImage?.let { listOf("--build-arg", "BASE_IMAGE=$it") } ?: emptyList()
                commandLine(listOf("docker", "build", "--tag", variant.tag) + buildArgs + context.get().path)
                dependsOn(dockerEngine)
                environment(engineEnvironment)
                val available = dockerAvailable // a local: the configuration cache can't hold the build script
                onlyIf("a Docker daemon running Linux containers is reachable, or dockerRequired is set") {
                    available.get()
                }
            }
    }

// Each image run through Testcontainers: started, probed, its labels read and stopped with SIGTERM.
testing {
    suites {
        register<JvmTestSuite>("dockerTest") {
            useJUnitJupiter("6.1.3")
            dependencies {
                implementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
            }
            targets {
                register("dockerTestMicro")
                all {
                    val variant = variants.single { it.testTarget == name }
                    testTask.configure {
                        description = "Runs ${variant.tag} and checks its probes, labels and SIGTERM handling"
                        dependsOn(dockerImages.getValue(variant.testTarget))
                        systemProperty("image", variant.tag)
                        environment(engineEnvironment)
                        val available = dockerAvailable // a local: the configuration cache can't hold the build script
                        onlyIf("a Docker daemon running Linux containers is reachable, or dockerRequired is set") {
                            available.get()
                        }
                    }
                }
            }
        }
    }
}

tasks.named("check") { dependsOn(testing.suites.named<JvmTestSuite>("dockerTest").map { it.targets.map { t -> t.testTask } }) }
