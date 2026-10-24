plugins {
    id("io.github.fiftieshousewife.platform.java-bundle")
}

group = "io.github.fiftieshousewife.samples"
version = "1.0.0"

application {
    mainClass = "io.github.fiftieshousewife.samples.HelloService"
}

bundle {
    javaVersion = 21
    // Reviewed explicitly: jdeps would suggest these, but misses anything loaded reflectively.
    jlinkModules = listOf("java.base", "jdk.httpserver")
    jvmArgs = listOf("-XX:+UseSerialGC", "-Xss512k")
}
