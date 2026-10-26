package io.github.fiftieshousewife.samples.docker;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The image built from {@code appDockerContext}, run as Docker and Kubernetes run it: it starts,
 * answers both probe modes of its own launcher, carries the OCI labels, and stops on SIGTERM.
 * The build passes the image's name as the {@code image} system property.
 */
class ContainerTest {
    private static final DockerImageName IMAGE = DockerImageName.parse(System.getProperty("image"));
    private static final int SIGKILL_EXIT = 137;

    private static GenericContainer<?> container;

    @BeforeAll
    static void start() {
        // Exposing the port makes start() wait until the app listens on it.
        container = new GenericContainer<>(IMAGE).withExposedPorts(8080);
        container.start();
    }

    @AfterAll
    static void remove() {
        container.close();
    }

    @Test
    void answersTheLivenessAndReadinessProbes() throws Exception {
        assertAll(
            () -> assertEquals(0, healthcheck(container, "live").getExitCode()),
            () -> assertEquals(0, healthcheck(container, "ready").getExitCode()));
    }

    @Test
    void isLabelledWithTheAppItsCommitAndItsBaseImage() {
        Map<String, String> labels = container.getContainerInfo().getConfig().getLabels();
        assertAll(
            java.util.stream.Stream.of("title", "version", "revision", "created", "licenses", "base.name")
                .map(key -> "org.opencontainers.image." + key)
                .map(label -> () -> assertFalse(labels.getOrDefault(label, "").isBlank(), label + " in " + labels)));
    }

    @Test
    void stopsOnSigtermBeforeTheGracePeriodRunsOut() {
        try (GenericContainer<?> stopping = new GenericContainer<>(IMAGE).withExposedPorts(8080)) {
            stopping.start();
            var docker = stopping.getDockerClient();
            Instant asked = Instant.now();
            docker.stopContainerCmd(stopping.getContainerId()).withTimeout(20).exec();
            Duration took = Duration.between(asked, Instant.now());
            Long exit = docker.inspectContainerCmd(stopping.getContainerId()).exec().getState().getExitCodeLong();
            // 137 is SIGKILL: the app ignored SIGTERM and was killed when the grace period ran out.
            assertNotEquals(SIGKILL_EXIT, exit.intValue(), "killed after " + took.toSeconds() + "s");
        }
    }

    private static ExecResult healthcheck(GenericContainer<?> running, String mode) throws Exception {
        String launcher = running.getContainerInfo().getConfig().getEntrypoint()[0];
        return running.execInContainer(launcher, "healthcheck", mode);
    }
}
