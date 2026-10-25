package io.github.fiftieshousewife.javaapp

import io.github.fiftieshousewife.appcore.TestProject
import java.io.File

/** The modules the fixture app needs: it serves HTTP with the JDK's own server. */
const val FIXTURE_MODULES = """jlinkModules = listOf("java.base", "jdk.httpserver")"""

/**
 * A small HTTP service applying java-app, with the test JDK published on a fake download site for
 * this machine and for `linux-aarch64`. [javaApp] goes inside `javaApp {}`; [build] at the end of
 * the build script; [publishJdk] false leaves the fake site empty, for tests that supply JDKs
 * another way.
 */
fun javaService(
    dir: File,
    javaApp: String = FIXTURE_MODULES,
    build: String = "",
    publishJdk: Boolean = true,
    vararg properties: Pair<String, String>,
): TestProject =
    TestProject(dir).apply {
        if (publishJdk) JdkFixture.publish(this, TestProject.hostTarget(), "linux-aarch64")
        gradleProperties(*JdkFixture.properties(this), *properties)
        settings(JavaAppSettingsPlugin.ID, "svc")
        file(
            "build.gradle.kts",
            """
            plugins { id("io.github.fiftieshousewife.java-app") }
            group = "com.example.test"
            version = providers.gradleProperty("appVersion").getOrElse("2.0.0")
            application { mainClass = "test.Main" }
            javaApp {
                javaVersion = ${JdkFixture.javaFeature}
                $javaApp
            }
            // The fixture JDK is whatever runs the tests, not necessarily Temurin.
            java { toolchain { vendor = JvmVendorSpec.matching("") } }
            $build
            """,
        )
        file(
            "src/main/java/test/Main.java",
            """
            package test;
            import com.sun.net.httpserver.HttpServer;
            import java.net.*;
            public class Main {
                public static void main(String[] args) throws Exception {
                    int port = Integer.parseInt(System.getenv().getOrDefault("APP_PORT", "8080"));
                    if (args.length > 0 && args[0].equals("--healthcheck")) {
                        try { HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
                              System.exit(c.getResponseCode() == 200 ? 0 : 1); } catch (Exception e) { System.exit(1); }
                    }
                    HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
                    s.createContext("/", x -> { x.sendResponseHeaders(200, -1); x.close(); });
                    s.start();
                }
            }
            """,
        )
        file("src/deploy/conf/app.properties", "key=value")
    }
