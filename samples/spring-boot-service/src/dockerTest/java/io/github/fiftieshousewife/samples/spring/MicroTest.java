package io.github.fiftieshousewife.samples.spring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * {@code Dockerfile.micro}'s image started as a hardened Kubernetes pod runs it: the image's own
 * user, every capability dropped, no privilege escalation, a read-only root filesystem with
 * {@code /tmp} and {@code /var/lib/app} as empty volumes, and a 512 MiB memory limit.
 */
class MicroTest {
    private static final long MEMORY = 512L * 1024 * 1024;
    private static final int SIGTERM_EXIT = 143;
    private static final Duration STOP_WITHIN = Duration.ofSeconds(10);
    private static final List<String> REQUIRED_LABELS = List.of("name", "vendor", "version", "release", "summary",
        "description", "maintainer");
    private static final String LIBZ = "[ -L /usr/lib64/libz.so.1 ] && [ -f /usr/lib64/libz.so.1.2.11 ]";
    private static final String LICENCE = """
        f=/licenses/java-runtime/java.base/LICENSE
        [ -f "$f" ] && [ ! -L "$f" ] && [[ $(< "$f") == *'No-Fee Terms'* ]]""";

    @Test
    void runsHardenedOnItsOwnUserReadOnlyAndWithinAMemoryLimit() throws Exception {
        try (GenericContainer<?> container = new GenericContainer<>(ContainerTest.image("Dockerfile.micro"))
            .withExposedPorts(8080)
            .withTmpFs(Map.of("/tmp", "rw,size=64m", "/var/lib/app", "rw,mode=1777,size=64m"))
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withReadonlyRootfs(true)
                .withMemory(MEMORY).withMemorySwap(MEMORY)
                .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges")))) {
            container.start();
            var docker = container.getDockerClient();
            ContainerConfig image = docker.inspectImageCmd(container.getDockerImageName()).exec().getConfig();
            Map<String, String> labels = image.getLabels();
            List<String> probe = image.getHealthcheck().getTest();
            String hello = get("http://" + container.getHost() + ":" + container.getMappedPort(8080) + "/");
            String id = bash(container, "echo $(id -u):$(id -g)").getStdout().strip();
            String settings = showSettings(container, "properties") + showSettings(container, "system");
            int libz = bash(container, LIBZ).getExitCode();
            int licence = bash(container, LICENCE).getExitCode();
            int healthcheck = container.execInContainer(probe.subList(1, probe.size()).toArray(String[]::new))
                .getExitCode();
            String logs = container.getLogs();
            Instant stopping = Instant.now();
            docker.stopContainerCmd(container.getContainerId()).withTimeout(20).exec();
            Duration stopped = Duration.between(stopping, Instant.now());
            var state = docker.inspectContainerCmd(container.getContainerId()).exec().getState();
            assertAll(
                () -> assertEquals("hello from Spring Boot\n", hello),
                () -> assertEquals("1001:0", id),
                () -> assertEquals("1001", image.getUser()),
                () -> assertEquals(0, libz, "libz.so.1 a link to the zlib package's library"),
                () -> assertTrue(settings.contains("java version \"25"), settings),
                () -> assertTrue(settings.contains("sun.jnu.encoding = UTF-8"), settings),
                () -> assertTrue(settings.contains("user.home = /var/lib/app"), settings),
                () -> assertTrue(settings.contains("Memory Limit: 512.00M"), settings),
                () -> assertEquals(0, licence, "Oracle's No-Fee Terms in /licenses as a regular file"),
                () -> assertEquals("CMD", probe.getFirst()),
                () -> assertEquals(0, healthcheck, "the image's own HEALTHCHECK command " + probe),
                () -> assertTrue(logs.contains("Started Application"), "logs on stdout: " + logs),
                () -> assertEquals(SIGTERM_EXIT, state.getExitCodeLong().intValue(), "exit on SIGTERM"),
                () -> assertFalse(Boolean.TRUE.equals(state.getOOMKilled()), "killed for memory"),
                () -> assertTrue(stopped.compareTo(STOP_WITHIN) < 0, "stopped in " + stopped),
                () -> assertEquals("spring-boot-service", labels.get("name")),
                () -> assertFalse(labels.get("vendor").contains("Red Hat"), labels.get("vendor")),
                () -> assertFalse(labels.get("maintainer").contains("Red Hat"), labels.get("maintainer")),
                () -> assertEquals(labels.get("org.opencontainers.image.version"), labels.get("version")),
                () -> assertAll(REQUIRED_LABELS.stream().map(label ->
                    () -> assertFalse(labels.getOrDefault(label, "").isBlank(), label + " in " + labels))));
        }
    }

    /** One group per JVM: given twice, -XshowSettings prints only the last. */
    private static String showSettings(GenericContainer<?> container, String group) throws Exception {
        return container.execInContainer("/app/runtime/bin/java", "-XshowSettings:" + group, "-version").getStderr();
    }

    private static ExecResult bash(GenericContainer<?> container, String script) throws Exception {
        return container.execInContainer("bash", "-c", script);
    }

    private static String get(String url) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
                .body();
        }
    }
}
