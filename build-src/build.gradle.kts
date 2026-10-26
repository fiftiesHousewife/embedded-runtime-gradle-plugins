// The plugin build's root: no code of its own. It releases the modules to Maven Central as one
// deployment, and its `check` runs the build logic's own tests.
plugins {
    base
    id("embedded-runtime.central-release")
}

tasks.named("check") { dependsOn(gradle.includedBuild("build-logic").task(":test")) }
