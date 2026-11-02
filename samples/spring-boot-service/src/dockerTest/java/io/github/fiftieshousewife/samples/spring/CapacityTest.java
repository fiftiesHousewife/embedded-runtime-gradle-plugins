package io.github.fiftieshousewife.samples.spring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;

/**
 * What a chart's resources rest on, measured from the container's cgroup: once the JIT has settled, a fixed number
 * of requests costs the same CPU time each round on a quiet engine, and the JVM's heap and peak memory fit the pod's
 * limit. The client is curl in a container of its own on the app's network: a client in this JVM, through the
 * engine's port forward, gave rounds a third apart. A busy engine charges less per request, so the rounds agreeing
 * doesn't show the number is the quiet one.
 */
class CapacityTest {
    private static final long MEMORY = 512L * 1024 * 1024;
    private static final long NANOS_PER_CPU = 1_000_000_000L;
    private static final int CPUS = 2;
    private static final int WARM_UP = 70_000;
    private static final int ROUND = 20_000;
    private static final int ROUNDS = 3;
    private static final double SPREAD = 0.10;
    private static final double HEAP_SHARE = 0.75;

    @Test
    void cpuPerRequestRepeatsAndMemoryFitsTheLimit() throws Exception {
        try (GenericContainer<?> container = new GenericContainer<>(ContainerTest.image(UbiMicroTest.DOCKERFILE))
            .withExposedPorts(8080)
            .withEnv("APP_JAVA_OPTS", "-XX:ActiveProcessorCount=" + CPUS + " -XX:+UseSerialGC -XX:+PrintFlagsFinal")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(MEMORY)
                .withMemorySwap(MEMORY).withNanoCPUs(CPUS * NANOS_PER_CPU))) {
            container.start();
            String client = clientImage();
            send(container, client, WARM_UP);
            List<Double> microsPerRequest = IntStream.range(0, ROUNDS)
                .mapToObj(round -> round(container, client)).toList();
            double low = microsPerRequest.stream().min(Double::compare).orElseThrow();
            double high = microsPerRequest.stream().max(Double::compare).orElseThrow();
            long peak = Long.parseLong(read(container, "/sys/fs/cgroup/memory.peak"));
            long heap = maxHeap(container.getLogs());
            assertAll(
                () -> assertTrue((high - low) / low < SPREAD, "CPU µs per request " + microsPerRequest),
                () -> assertTrue(peak < MEMORY, "peak " + peak),
                () -> assertEquals(HEAP_SHARE, (double) heap / MEMORY, 0.01, "heap " + heap));
        }
    }

    private static double round(GenericContainer<?> container, String client) {
        long before = cpuMicros(container);
        send(container, client, ROUND);
        return (double) (cpuMicros(container) - before) / ROUND;
    }

    /** One curl over one connection, sharing the app's network and charged to a cgroup of its own. */
    private static void send(GenericContainer<?> container, String client, int requests) {
        try (GenericContainer<?> curl = new GenericContainer<>(client)
            .withNetworkMode("container:" + container.getContainerId())
            .withCommand("curl", "-sS", "-o", "/dev/null", "http://localhost:8080/?n=[1-" + requests + "]")
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(5)))) {
            curl.start();
        }
    }

    /** The image the Dockerfile's own build stage names, which has curl and is already pulled. */
    private static String clientImage() throws IOException {
        return Files.readAllLines(ContainerTest.CONTEXT.resolve(UbiMicroTest.DOCKERFILE)).stream()
            .filter(line -> line.startsWith("ARG BUILD_IMAGE="))
            .map(line -> line.substring("ARG BUILD_IMAGE=".length())).findFirst().orElseThrow();
    }

    private static long cpuMicros(GenericContainer<?> container) {
        return read(container, "/sys/fs/cgroup/cpu.stat").lines().filter(line -> line.startsWith("usage_usec"))
            .map(line -> Long.parseLong(line.split(" ")[1])).findFirst().orElseThrow();
    }

    private static String read(GenericContainer<?> container, String path) {
        try {
            return container.execInContainer("cat", path).getStdout().strip();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long maxHeap(String flags) {
        return flags.lines().filter(line -> line.contains(" MaxHeapSize ")).map(line -> line.trim().split("\\s+")[3])
            .map(Long::parseLong).findFirst().orElseThrow();
    }
}
