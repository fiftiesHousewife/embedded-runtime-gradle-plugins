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
// reachable (GitHub's Windows runners run Windows containers). -PdockerRequired=true runs them
// regardless, so a missing daemon fails the build: CI's Linux job uses it to prove the image runs.
val image = "docker-service:$version"
val dockerRequired = providers.gradleProperty("dockerRequired").map(String::toBoolean).orElse(false)
val dockerOsType =
    providers.exec {
        commandLine("docker", "info", "--format", "{{.OSType}}")
        isIgnoreExitValue = true
    }.standardOutput.asText // fails to start without a docker command (GitHub's macOS runners): no daemon

val dockerImage =
    tasks.register<Exec>("dockerImage") {
        group = "docker"
        description = "Builds $image from the appDockerContext build context"
        val context = tasks.named<Sync>("appDockerContext").map { it.destinationDir }
        inputs.dir(context)
        commandLine("docker", "build", "--tag", image, context.get().path)
        val required = dockerRequired // locals: the configuration cache can't hold the build script
        val osType = dockerOsType
        onlyIf("a Docker daemon running Linux containers is reachable, or dockerRequired is set") {
            required.get() || runCatching { osType.get().trim() == "linux" }.getOrDefault(false)
        }
    }

val dockerSmokeTest =
    tasks.register<Exec>("dockerSmokeTest") {
        group = "verification"
        description = "Starts $image and waits for the container's own healthcheck"
        dependsOn(dockerImage)
        commandLine("sh", "src/docker/smoke-test.sh", image)
        val required = dockerRequired // locals: the configuration cache can't hold the build script
        val osType = dockerOsType
        onlyIf("a Docker daemon running Linux containers is reachable, or dockerRequired is set") {
            required.get() || runCatching { osType.get().trim() == "linux" }.getOrDefault(false)
        }
    }

tasks.named("check") { dependsOn(dockerSmokeTest) }
