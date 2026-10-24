// The plugins live in `build-src`, included here as a plugin build. Gradle builds it first, so this
// build can consume — and self-apply — the very plugins it publishes: `io.github.fiftieshousewife.platform.settings`
// below configures this build's repositories, JDK toolchain resolver and project-plugin versions
// exactly as it does for a consuming repository. Dogfooding the settings plugin here means a change
// that breaks consumers breaks this build too.
pluginManagement {
    includeBuild("build-src")
}

plugins {
    id("io.github.fiftieshousewife.platform.settings")
}

rootProject.name = "platform-bundle"
