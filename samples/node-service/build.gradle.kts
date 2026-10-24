plugins {
    id("io.github.fiftieshousewife.platform.node-bundle")
}

group = "io.github.fiftieshousewife.samples"
version = "1.0.0"

bundle {
    entryPoint = "main.js"
    nodeArgs = listOf("--enable-source-maps")
}
