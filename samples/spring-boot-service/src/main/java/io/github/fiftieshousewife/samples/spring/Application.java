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
 * {@code bin/spring-boot-service healthcheck} asks the running instance's Actuator health endpoint
 * and exits 0 when it is up.
 */
@SpringBootApplication
@RestController
public class Application {
    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("APP_PORT", "8080");
        if (args.length > 0 && args[0].equals("--healthcheck")) {
            System.exit(healthcheck(port));
        }
        SpringApplication app = new SpringApplication(Application.class);
        app.setDefaultProperties(Map.of("server.port", port));
        app.run(args);
    }

    @GetMapping("/")
    public String hello() {
        return "hello from Spring Boot\n";
    }

    private static int healthcheck(String port) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(5)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200 ? 0 : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
