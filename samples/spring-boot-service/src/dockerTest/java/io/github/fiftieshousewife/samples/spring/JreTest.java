package io.github.fiftieshousewife.samples.spring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * {@code Dockerfile.jre}'s image, the runtime alone on UBI micro, started as a hardened pod with
 * OpenShift's arbitrary user: Java runs within the memory limit, finds zlib (recorded in micro's RPM
 * database), names files in UTF-8 and has a home it can write, and the runtime's layer is the one the
 * app's own micro image carries.
 */
class JreTest {
    static final String DOCKERFILE = "Dockerfile.jre";
    private static final long MEMORY = 256L * 1024 * 1024;
    private static final String OPENSHIFT_USER = "1000770000:0";
    private static final String RPMDB = "[[ $(< /var/lib/rpm/rpmdb.sqlite) == *zlib-1.2.11* ]] 2>/dev/null";
    private static final String LICENCE = "[ -f /licenses/java-runtime/java.base/LICENSE ]"
        + " && [ ! -L /licenses/java-runtime/java.base/LICENSE ]";

    @Test
    void runsJavaHardenedAsAnArbitraryUserAndSharesTheAppImagesRuntimeLayer() throws Exception {
        String image = image();
        try (GenericContainer<?> container = new GenericContainer<>(image)
            .withCreateContainerCmdModifier(command -> command.withEntrypoint("sleep").withCmd("120")
                .withUser(OPENSHIFT_USER)
                .getHostConfig().withReadonlyRootfs(true).withMemory(MEMORY).withMemorySwap(MEMORY)
                .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges")))
            .withTmpFs(Map.of("/tmp", "rw,size=16m", "/var/lib/app", "rw,mode=1777,size=16m"))) {
            container.start();
            ContainerConfig config = container.getDockerClient().inspectImageCmd(image).exec().getConfig();
            ExecResult java = container.execInContainer("java", "-XshowSettings:all", "-version");
            String settings = java.getStdout() + java.getStderr();
            int rpmdb = bash(container, RPMDB).getExitCode();
            int licence = bash(container, LICENCE).getExitCode();
            int home = bash(container, "touch \"$HOME/written\"").getExitCode();
            List<String> jre = ContainerTest.layers(image);
            List<String> app = ContainerTest.layers(ContainerTest.image("Dockerfile.micro"));
            assertAll(
                () -> assertEquals(0, java.getExitCode(), settings),
                () -> assertTrue(settings.contains("java version \"25"), settings),
                () -> assertTrue(settings.contains("Memory Limit: 256.00M"), settings),
                () -> assertTrue(settings.contains("sun.jnu.encoding = UTF-8"), settings),
                () -> assertTrue(settings.contains("user.home = /var/lib/app"), settings),
                () -> assertEquals(0, rpmdb, "zlib in micro's RPM database"),
                () -> assertEquals(0, licence, "the runtime's terms in /licenses as regular files"),
                () -> assertEquals(0, home, "HOME writable by an arbitrary user in group 0"),
                () -> assertEquals("1001", config.getUser()),
                () -> assertEquals(List.of("java"), List.of(config.getEntrypoint())),
                () -> assertEquals("java-runtime", config.getLabels().get("name")),
                () -> assertTrue(config.getLabels().get("version").startsWith("25"), config.getLabels().toString()),
                () -> assertEquals(app.get(app.size() - 2), jre.getLast(), "runtime layer " + jre + " vs " + app));
        }
    }

    private static ExecResult bash(GenericContainer<?> container, String script) throws Exception {
        return container.execInContainer("bash", "-c", script);
    }

    /** JAVA_VERSION from the runtime's own {@code release} file, as the docs build it. */
    private static String image() throws IOException {
        String cpu = ContainerTest.cpu();
        String version = Files.readAllLines(ContainerTest.CONTEXT.resolve("runtime/" + cpu + "/release")).stream()
            .filter(line -> line.startsWith("JAVA_VERSION="))
            .map(line -> line.substring("JAVA_VERSION=".length()).replace("\"", ""))
            .findFirst().orElseThrow();
        return new ImageFromDockerfile("spring-boot-service-jre", false)
            .withFileFromPath(".", ContainerTest.CONTEXT)
            .withDockerfilePath(DOCKERFILE)
            .withBuildArg("TARGETARCH", cpu)
            .withBuildArg("BUILDPLATFORM", "linux/" + cpu)
            .withBuildArg("JAVA_VERSION", version)
            .get();
    }
}
