import io.github.fiftieshousewife.appcore.Target

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

// Builds the container image from appDockerContext and proves it: the container starts and its own
// healthcheck passes. Skipped, not failed, unless a Docker daemon running Linux containers is
// reachable (GitHub's Windows runners run Windows containers).
val image = "docker-service:$version"
val dockerRunning =
    providers.exec {
        commandLine("docker", "info", "--format", "{{.OSType}}")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() == "linux" }

val dockerImage =
    tasks.register<Exec>("dockerImage") {
        group = "docker"
        description = "Builds $image from the appDockerContext build context"
        val context = tasks.named<Sync>("appDockerContext").map { it.destinationDir }
        inputs.dir(context)
        commandLine("docker", "build", "--tag", image, context.get().path)
        val running = dockerRunning // a local: the configuration cache can't hold the build script
        onlyIf("a Docker daemon running Linux containers is reachable") { running.get() }
    }

val dockerSmokeTest =
    tasks.register<Exec>("dockerSmokeTest") {
        group = "verification"
        description = "Starts $image and waits for the container's own healthcheck"
        dependsOn(dockerImage)
        commandLine("sh", "src/docker/smoke-test.sh", image)
        val running = dockerRunning // a local: the configuration cache can't hold the build script
        onlyIf("a Docker daemon running Linux containers is reachable") { running.get() }
    }

tasks.named("check") { dependsOn(dockerSmokeTest) }
