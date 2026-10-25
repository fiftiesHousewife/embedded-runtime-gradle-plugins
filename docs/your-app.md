# What your app needs to do

The launcher, `bin/<app>`, takes three commands:

| Command | Does |
|---|---|
| `run [args]` | Starts the app |
| `healthcheck` | Starts your app with `--healthcheck`; exit 0 means healthy |
| `version` | Prints `META-INF/app.json` |

So your app should read its port from `APP_PORT`, and exit 0 when started with `--healthcheck`
if the running instance is healthy. The apps in [`samples/`](../samples) do this in a few lines.

The launcher also sets `APP_HOME`, `APP_ROOT`, `APP_CONF_DIR`, `APP_DATA_DIR` and `APP_LOG_DIR`,
and adds `APP_JAVA_OPTS` / `APP_NODE_OPTS` to the runtime's options.

Default configuration files go in `src/deploy/conf/` and ship as `conf.defaults/`. Read `conf/`
first and fall back to `conf.defaults/`, so an upgrade never overwrites a deployed file.

## Inside the archive

```
orders-service-1.4.2/
  bin/orders-service      # .bat on Windows targets
  runtime/                # the embedded JDK or Node.js
  lib/                    # Java: your jar and its dependencies
  app/                    # Node: your code, package.json and production node_modules
  conf.defaults/
  META-INF/app.json       # name, version, target, runtime, git commit
  META-INF/sbom.cdx.json  # CycloneDX SBOM, including the runtime
```

Archives are reproducible: rebuilding the same commit gives a byte-identical file.

The SBOM lists the embedded runtime and every library, with their hashes, in the CycloneDX format
that vulnerability scanners such as Dependency-Track, Grype and Snyk read. It is also published next
to each archive. To use an SBOM tool of your own instead (the CycloneDX Gradle plugin, Syft, a corporate scanner),
point `sbom` at its CycloneDX JSON output. Every archive and publication then carries that file and
the built-in generator does not run:

```kotlin
javaApp {                                                    // or nodeApp
    sbom = tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom").flatMap { it.jsonOutput }
}
```

Listing the runtime matters: a JDK or Node.js inside an archive is invisible to a
scanner that looks only at the operating system's packages.
