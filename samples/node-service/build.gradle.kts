plugins {
    id("io.github.fiftieshousewife.node-app")
}

group = "io.github.fiftieshousewife.samples"
version = "1.0.0"

nodeApp {
    entryPoint = "main.js"
    nodeArgs = listOf("--enable-source-maps")
}
