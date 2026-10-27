// A pipeline's check of a published release: its signature, then its health check.
plugins {
    id("io.github.fiftieshousewife.app-smoke-test")
}

repositories {
    maven { url = uri("../java-service/build/repo") }
    mavenCentral() // sigstore-java, which verifies signatures; your binary repository in a real pipeline
}

dependencies {
    smokeTest("io.github.fiftieshousewife.samples:java-service:1.0.0")
}
