// The plugin build's root: no code of its own. It releases the modules to Maven Central as one
// deployment, and its `check` runs the build logic's own tests.
plugins {
    base
    id("embedded-runtime.central-release")
}

tasks.named("check") {
    val buildLogic = gradle.includedBuild("build-logic")
    dependsOn(buildLogic.task(":conventions:test"), buildLogic.task(":detekt-rules:test"))
}
