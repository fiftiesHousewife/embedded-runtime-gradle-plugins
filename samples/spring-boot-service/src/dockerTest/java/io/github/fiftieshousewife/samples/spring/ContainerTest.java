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
import org.junit.jupiter.api.Test;
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
    static final Path CONTEXT = Path.of(System.getProperty("context"));
    static final String LAUNCHER = "/app/bin/spring-boot-service";
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

    /**
     * The runtime's and the app's layers, each image's last two, are byte-identical in every image and
     * in a rebuild without Docker's cache, so a registry stores them once and nodes pull them once.
     */
    @Test
    void everyImageAndARebuildShareTheRuntimeAndAppLayers() throws IOException {
        List<List<String>> shared = dockerfiles().map(dockerfile -> lastTwoLayers(image(dockerfile))).toList();
        List<String> rebuilt = lastTwoLayers(image("Dockerfile", "rebuilt", true));
        assertAll(
            () -> assertEquals(1, shared.stream().distinct().count(), "runtime and app layers " + shared),
            () -> assertEquals(shared.getFirst(), rebuilt, "rebuilt without the cache"));
    }

    private static List<String> lastTwoLayers(String image) {
        List<String> layers = DockerClientFactory.instance().client().inspectImageCmd(image).exec()
            .getRootFS().getLayers();
        return layers.subList(layers.size() - 2, layers.size());
    }

    static String image(String dockerfile) {
        return image(dockerfile, dockerfile.toLowerCase().replace('.', '-'), false);
    }

    /** Docker's own builder sets TARGETARCH only with BuildKit, which Testcontainers doesn't use: give it. */
    private static String image(String dockerfile, String name, boolean noCache) {
        String cpu = DockerClientFactory.instance().getInfo().getArchitecture().equals("aarch64") ? "arm64" : "amd64";
        return new ImageFromDockerfile("spring-boot-service-" + name, false)
            .withFileFromPath(".", CONTEXT)
            .withDockerfilePath(dockerfile)
            .withBuildArg("TARGETARCH", cpu)
            .withBuildImageCmdModifier(command -> command.withNoCache(noCache))
            .get();
    }
}
