package io.github.fiftieshousewife.samples.spring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Each Dockerfile in the build context {@code appDockerContext} wrote, built and run as Docker,
 * Kubernetes and OpenShift run it: both probe modes of its launcher pass, it carries the OCI labels,
 * and it stops on SIGTERM before the grace period runs out. The build passes the context's path as
 * the {@code context} system property.
 */
class ContainerTest {
    private static final Path CONTEXT = Path.of(System.getProperty("context"));
    private static final String LAUNCHER = "/app/bin/spring-boot-service";
    private static final String OPENSHIFT_USER = "1000770000:0";
    private static final int SIGKILL_EXIT = 137;
    private static final List<String> LABELS = List.of("title", "version", "revision", "created", "licenses", "base.name");

    static Stream<String> dockerfiles() throws IOException {
        try (Stream<Path> files = Files.list(CONTEXT)) {
            return files.map(file -> file.getFileName().toString()).filter(name -> name.startsWith("Dockerfile"))
                .sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("dockerfiles")
    void runsAsADeploymentExpects(String dockerfile) throws Exception {
        try (GenericContainer<?> container = new GenericContainer<>(image(dockerfile)).withExposedPorts(8080)) {
            if (dockerfile.endsWith(".openshift")) {
                container.withCreateContainerCmdModifier(command -> command.withUser(OPENSHIFT_USER));
            }
            container.start();
            Map<String, String> labels = container.getContainerInfo().getConfig().getLabels();
            int live = container.execInContainer(LAUNCHER, "healthcheck", "live").getExitCode();
            int ready = container.execInContainer(LAUNCHER, "healthcheck", "ready").getExitCode();
            var docker = container.getDockerClient();
            docker.stopContainerCmd(container.getContainerId()).withTimeout(20).exec();
            long exit = docker.inspectContainerCmd(container.getContainerId()).exec().getState().getExitCodeLong();
            assertAll(
                () -> assertEquals(0, live, "healthcheck live"),
                () -> assertEquals(0, ready, "healthcheck ready"),
                () -> assertNotEquals(SIGKILL_EXIT, (int) exit, "ignored SIGTERM and was killed"),
                () -> assertAll(LABELS.stream().map(key -> "org.opencontainers.image." + key).map(label ->
                    () -> assertFalse(labels.getOrDefault(label, "").isBlank(), label + " in " + labels))));
        }
    }

    /** Docker's own builder sets TARGETARCH only with BuildKit, which Testcontainers doesn't use: give it. */
    private static String image(String dockerfile) {
        String cpu = DockerClientFactory.instance().getInfo().getArchitecture().equals("aarch64") ? "arm64" : "amd64";
        return new ImageFromDockerfile("spring-boot-service-" + dockerfile.toLowerCase().replace('.', '-'), false)
            .withFileFromPath(".", CONTEXT)
            .withDockerfilePath(dockerfile)
            .withBuildArg("TARGETARCH", cpu)
            .get();
    }
}
