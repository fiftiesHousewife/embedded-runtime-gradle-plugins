plugins {
    id("io.github.fiftieshousewife.java-app")
    id("dev.sigstore.sign") version "2.3.0" apply false
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
    // Linux targets also get an RPM: /opt/java-service, /etc/java-service and a systemd unit.
    rpm {
        enabled = true
        summary = "Sample HTTP service with its own Java runtime"
    }
}

// Published to build/repo, where samples/release-check finds it. With -PsigstoreSign=true every
// published file is also signed with Sigstore, keyless, as CI's release job does with the workflow's
// own identity; anywhere else that would open a browser to sign in, so it is off by default.
publishing { repositories { maven { name = "sample"; url = uri(layout.buildDirectory.dir("repo")) } } }
if (providers.gradleProperty("sigstoreSign").map(String::toBoolean).getOrElse(false)) {
    apply(plugin = "dev.sigstore.sign")
}
