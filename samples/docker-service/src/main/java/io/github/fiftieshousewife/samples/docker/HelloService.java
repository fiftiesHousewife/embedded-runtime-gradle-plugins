package io.github.fiftieshousewife.samples.docker;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Minimal service implementing the launcher contract:
 * {@code bin/java-service run} serves HTTP on {@code APP_PORT};
 * {@code bin/java-service healthcheck} (passed through as {@code --healthcheck}) exits 0 when healthy.
 */
public final class HelloService {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("APP_PORT", "8080"));
        if (args.length > 0 && args[0].equals("--healthcheck")) {
            System.exit(healthcheck(port));
        }

        PrintStream log = logStream();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "UP\n"));
        server.createContext("/", exchange -> respond(exchange, 200, "Hello from " + env("APP_NAME", "java-service") + "\n"));
        server.start();
        log.printf("java-service listening on %d (config: %s)%n", port, env("APP_CONF_DIR", "-"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
    }

    private static int healthcheck(int port) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/health").toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            return connection.getResponseCode() == 200 ? 0 : 1;
        } catch (IOException e) {
            return 1;
        }
    }

    private static PrintStream logStream() throws IOException {
        if (!"file".equals(env("APP_LOG_TARGET", "stdout"))) {
            return System.out;
        }
        Path dir = Path.of(env("APP_LOG_DIR", "logs"));
        Files.createDirectories(dir);
        return new PrintStream(Files.newOutputStream(dir.resolve("java-service.log"),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
