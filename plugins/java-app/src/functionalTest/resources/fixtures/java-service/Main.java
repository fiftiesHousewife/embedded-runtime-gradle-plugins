package test;

import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("APP_PORT", "8080"));
        if (args.length > 0 && args[0].equals("--healthcheck")) {
            System.exit(healthy(port) ? 0 : 1);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    private static boolean healthy(int port) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
