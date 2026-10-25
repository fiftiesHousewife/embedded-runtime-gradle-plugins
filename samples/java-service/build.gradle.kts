plugins {
    id("io.github.fiftieshousewife.java-app")
}

group = "io.github.fiftieshousewife.samples"
version = "1.0.0"

application {
    mainClass = "io.github.fiftieshousewife.samples.HelloService"
}

javaApp {
    javaVersion = 21
    // No jlinkModules: jdeps finds java.base and jdk.httpserver from the code.
    jvmArgs = listOf("-XX:+UseSerialGC", "-Xss512k")
}
