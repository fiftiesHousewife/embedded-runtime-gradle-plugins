package io.github.fiftieshousewife.samples.spring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A Spring Boot web service packaged by the java-app plugin.
 *
 * <p>{@code bin/spring-boot-service run} serves HTTP on {@code APP_PORT};
 * {@code bin/spring-boot-service healthcheck [live|ready]} asks the running instance's Actuator
 * liveness or readiness group (readiness when no mode is given) and exits 0 when it is up.
 */
@SpringBootApplication
@RestController
public class Application {
    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("APP_PORT", "8080");
        if (args.length > 0 && args[0].equals("--healthcheck")) {
            String mode = args.length > 1 ? args[1] : "ready";
            System.exit(healthcheck(port, mode.equals("live") ? "liveness" : "readiness"));
        }
        SpringApplication app = new SpringApplication(Application.class);
        // Probes: /actuator/health/liveness and /readiness, which Boot enables on its own only on Kubernetes.
        app.setDefaultProperties(Map.of("server.port", port, "management.endpoint.health.probes.enabled", "true"));
        app.run(args);
    }

    @GetMapping("/")
    public String hello() {
        return "hello from Spring Boot\n";
    }

    private static int healthcheck(String port, String group) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/" + group))
                .timeout(Duration.ofSeconds(5)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200 ? 0 : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
