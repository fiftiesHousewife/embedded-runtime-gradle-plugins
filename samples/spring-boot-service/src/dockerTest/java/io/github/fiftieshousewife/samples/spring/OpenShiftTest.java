package io.github.fiftieshousewife.samples.spring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ExposedPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * {@code Dockerfile.openshift}'s image started as OpenShift's restricted-v2 security context constraint
 * starts it: a random user id with no passwd entry, group 0, every capability dropped and no privilege
 * escalation. Being the files' owner (1001) would hide what only group 0's permissions allow.
 */
class OpenShiftTest {
    private static final int SIGKILL_EXIT = 137;
    private static final List<String> REQUIRED_LABELS = List.of("name", "vendor", "version", "release", "summary",
        "description", "maintainer", "io.k8s.display-name", "io.k8s.description", "io.openshift.tags",
        "io.openshift.expose-services");
    /** Every file and directory under /app the running user can't read, or can't enter. */
    private static final String UNREADABLE = """
        shopt -s globstar dotglob
        for f in /app/**; do
          [ -L "$f" ] && continue
          [ -r "$f" ] || echo "unreadable $f"
          if [ -d "$f" ] && [ ! -x "$f" ]; then echo "unenterable $f"; fi
        done""";
    private static final String WRITES = "touch \"$APP_DATA_DIR/probe\" \"$APP_LOG_DIR/probe\" \"$HOME/probe\"";
    /** Oracle's terms in /licenses as a regular file, not through a link, which certification skips. */
    private static final String LICENCE = """
        f=/licenses/java-runtime/java.base/LICENSE
        [ ! -L /licenses/java-runtime ] && [ -f "$f" ] && [ ! -L "$f" ] && [[ $(< "$f") == *'No-Fee Terms'* ]]""";

    @Test
    void runsAsAnArbitraryUserInGroupZeroWithNoCapabilities() throws Exception {
        String uid = String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000));
        try (GenericContainer<?> container = new GenericContainer<>(ContainerTest.image("Dockerfile.openshift"))
            .withExposedPorts(8080)
            .withCreateContainerCmdModifier(command -> command.withUser(uid + ":0").getHostConfig()
                .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges")))) {
            container.start();
            ContainerConfig config = container.getContainerInfo().getConfig();
            Map<String, String> labels = config.getLabels();
            String hello = get("http://" + container.getHost() + ":" + container.getMappedPort(8080) + "/");
            String id = bash(container, "echo $(id -u):$(id -g)").getStdout().strip();
            String unreadable = bash(container, UNREADABLE).getStdout();
            int writes = bash(container, WRITES).getExitCode();
            int appWrite = bash(container, "touch /app/probe || touch /app/bin/spring-boot-service").getExitCode();
            int live = container.execInContainer(ContainerTest.LAUNCHER, "healthcheck", "live").getExitCode();
            int ready = container.execInContainer(ContainerTest.LAUNCHER, "healthcheck", "ready").getExitCode();
            String java = container.execInContainer("/app/runtime/bin/java", "-version").getStderr();
            String os = bash(container, "cat /etc/redhat-release").getStdout();
            int licence = bash(container, LICENCE).getExitCode();
            String logs = container.getLogs();
            var docker = container.getDockerClient();
            String imageUser = docker.inspectImageCmd(container.getDockerImageName()).exec().getConfig().getUser();
            docker.stopContainerCmd(container.getContainerId()).withTimeout(20).exec();
            long exit = docker.inspectContainerCmd(container.getContainerId()).exec().getState().getExitCodeLong();
            assertAll(
                () -> assertEquals("hello from Spring Boot\n", hello),
                () -> assertEquals(uid + ":0", id),
                () -> assertEquals("", unreadable),
                () -> assertEquals(0, writes, "write to the data, log and home directories"),
                () -> assertNotEquals(0, appWrite, "/app is writable"),
                () -> assertEquals(0, live, "healthcheck live"),
                () -> assertEquals(0, ready, "healthcheck ready"),
                () -> assertTrue(java.contains("java version \"25") && java.contains("Java(TM) SE"), java),
                () -> assertTrue(os.startsWith("Red Hat Enterprise Linux release 9"), os),
                () -> assertEquals(0, licence, "Oracle's No-Fee Terms in /licenses"),
                () -> assertTrue(logs.contains("Started Application"), "logs on stdout: " + logs),
                () -> assertNotEquals(SIGKILL_EXIT, (int) exit, "ignored SIGTERM and was killed"),
                () -> assertTrue(imageUser == null || imageUser.isEmpty(), "USER " + imageUser),
                () -> assertTrue(Arrays.asList(config.getExposedPorts()).contains(ExposedPort.tcp(8080))),
                () -> assertEquals("spring-boot-service", labels.get("name")),
                () -> assertNotEquals("Red Hat, Inc.", labels.get("vendor")),
                () -> assertEquals(labels.get("org.opencontainers.image.version"), labels.get("version")),
                () -> assertEquals(labels.get("org.opencontainers.image.revision"), labels.get("vcs-ref")),
                () -> assertAll(REQUIRED_LABELS.stream().map(label ->
                    () -> assertFalse(labels.getOrDefault(label, "").isBlank(), label + " in " + labels))));
        }
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
