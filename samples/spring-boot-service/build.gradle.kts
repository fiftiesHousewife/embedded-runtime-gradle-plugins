plugins {
    id("io.github.fiftieshousewife.java-app")
}

group = "io.github.fiftieshousewife.samples"
version = "1.0.0"

application {
    mainClass = "io.github.fiftieshousewife.samples.spring.Application"
}

dependencies {
    // Spring Boot's dependency management without its Gradle plugin: the java-app plugin builds the
    // launchers and the archive, so a fat jar is not needed.
    implementation(platform(libs.spring.boot.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}

javaApp {
    javaVersion = 21
    jvmArgs = listOf("-XX:MaxRAMPercentage=75")
}
