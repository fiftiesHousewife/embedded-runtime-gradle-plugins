plugins {
    id("io.github.fiftieshousewife.java-app")
    id("dev.sigstore.sign") version "2.3.0" apply false
}

group = "io.github.fiftieshousewife.samples"
version = providers.gradleProperty("appVersion").getOrElse("1.0.0")
description = "Sample HTTP service with its own Java runtime"

application {
    mainClass = "io.github.fiftieshousewife.samples.HelloService"
}

javaApp {
    javaVersion = 21
    jvmArgs = listOf("-XX:+UseSerialGC", "-Xss512k")
    rpm { enabled = true }
}

// -PsigstoreSign=true signs with Sigstore, keyless, as CI does; elsewhere that opens a browser.
publishing { repositories { maven { name = "sample"; url = uri(layout.buildDirectory.dir("repo")) } } }
if (providers.gradleProperty("sigstoreSign").map(String::toBoolean).getOrElse(false)) {
    apply(plugin = "dev.sigstore.sign")
}
