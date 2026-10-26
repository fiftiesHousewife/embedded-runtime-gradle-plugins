// A deployment pipeline's check of a published release: it downloads the archive built for this
// machine, verifies that it was signed by the expected release workflow (with
// -PappSmokeTest.signerIdentity), then starts it and waits for its health check. Publish
// samples/java-service first: ./gradlew -p samples/java-service publishAllPublicationsToSampleRepository
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
