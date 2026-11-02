package io.github.fiftieshousewife.samples.spring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * The image {@code images { register("ubi-micro") { from(ubiMicro) } }} writes: Oracle's Java 25, linked
 * by jlink, under Spring Boot on UBI micro, with nobody having written a Dockerfile or named an RPM.
 */
class UbiMicroTest {
    static final String DOCKERFILE = "Dockerfile.ubi-micro";
    private static final int OWN_LAYERS = 4;
    private static final int REUSED_LAYERS = 3;
    private static final String RPMDB = "[[ $(< /var/lib/rpm/rpmdb.sqlite) == *zlib-1.2.11* ]] 2>/dev/null";

    @Test
    void servesOnOraclesJava25AndUbiMicroWithNoPackageManager() throws Exception {
        try (GenericContainer<?> container = new GenericContainer<>(ContainerTest.image(DOCKERFILE))
            .withExposedPorts(8080)) {
            container.start();
            String java = container.execInContainer("/app/runtime/bin/java", "-XshowSettings:properties", "-version")
                .getStderr();
            String modules = container.execInContainer("/app/runtime/bin/java", "--list-modules").getStdout();
            String os = container.execInContainer("cat", "/etc/redhat-release").getStdout();
            String version = container.getContainerInfo().getConfig().getLabels().get("version");
            int rpmdb = container.execInContainer("bash", "-c", RPMDB).getExitCode();
            int managers = container.execInContainer("bash", "-c",
                "command -v microdnf || command -v dnf || command -v yum || command -v rpm").getExitCode();
            assertAll(
                () -> assertTrue(java.contains("java.version = 25"), java),
                () -> assertTrue(java.contains("java.vendor = Oracle Corporation"), java),
                () -> assertFalse(modules.contains("jdk.compiler"), "a linked runtime, no compiler: " + modules),
                () -> assertTrue(os.startsWith("Red Hat Enterprise Linux release 9"), os),
                () -> assertEquals("1.0.0", version, "the build's version in the label"),
                () -> assertEquals(0, rpmdb, "zlib in micro's RPM database, for scanners"),
                () -> assertEquals(1, managers, "no package manager in the image"));
        }
    }

    /** Micro's own layers, then four more; a rebuild with no cache gives the same digests for what is reused. */
    @Test
    void layersAreMicrosThenFilesRuntimeLibrariesAndApp() throws IOException {
        List<String> layers = ContainerTest.layers(ContainerTest.image(DOCKERFILE));
        List<String> rebuilt = ContainerTest.layers(ContainerTest.image(DOCKERFILE, "ubi-micro-rebuilt", true));
        List<String> byHand = ContainerTest.layers(ContainerTest.image("Dockerfile.micro"));
        String runtime = layers.get(layers.size() - REUSED_LAYERS);
        List<String> base = layers.subList(0, layers.size() - OWN_LAYERS);
        assertAll(
            () -> assertEquals(tail(layers), tail(rebuilt), "rebuilt without the cache"),
            () -> assertEquals(byHand.get(byHand.size() - 2), runtime, "the runtime layer of the micro image by hand"),
            () -> assertEquals(base, byHand.subList(0, base.size()), "micro's own layers, untouched"),
            () -> assertTrue(Files.isDirectory(ContainerTest.CONTEXT.resolve("layers/" + ContainerTest.cpu() + "/libs"))));
    }

    private static List<String> tail(List<String> layers) {
        return layers.subList(layers.size() - REUSED_LAYERS, layers.size());
    }
}
