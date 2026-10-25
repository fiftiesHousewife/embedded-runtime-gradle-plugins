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
  security/               # Node: trusted-cas.pem, when nodeApp.security or the runtime policy names CAs
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

## Node.js: trusted CAs and TLS

An embedded Node.js trusts only the CAs compiled into it, not the server's certificate store, so
calls to services behind a corporate CA fail. `security {}` fixes that in the archive itself, so it
holds wherever the archive runs (Java's equivalent is in [java.md](java.md#hardening-the-runtime)):

```kotlin
nodeApp {
    security {
        trustedCertificates.from("certs")   // PEM or DER files, or directories of them
        tlsMinVersion = "1.3"               // refuse TLS 1.2 and older
        tlsCipherList = "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256"
    }
}
```

The build checks every certificate and writes them to one bundle, `security/trusted-cas.pem`. The
launcher points `NODE_EXTRA_CA_CERTS` at it, so these CAs are trusted as well as Node's own. The TLS
settings become `--tls-min-v1.3` and `--tls-cipher-list=…`, placed before `nodeArgs` and
`APP_NODE_OPTS`. The smoke test starts the app with all of them in place.

`runtimeOptions("locked")` makes the launcher refuse to start while `NODE_OPTIONS`, `APP_NODE_OPTS`
or `NODE_TLS_REJECT_UNAUTHORIZED` is set, rather than let them load a script or turn off
certificate checks.

### The organisation's runtime policy

The same runtime policy as Java's ([java.md](java.md#an-organisation-wide-runtime-policy)) covers
Node.js apps, set per machine with `nodeApp.securityPolicy` or `nodeApp.securityPolicyArtifact`.
One policy can hold both ecosystems' keys; these are the ones that apply to Node.js:

```properties
id=acme-node-runtime
version=3
tlsMinVersion=1.3                                    # the launcher must pass --tls-min-v1.3
forbiddenNodeArgs=--inspect,--require,--import,--loader,--experimental-loader
runtimeOptions=locked
forbiddenLibraries=pkg:npm/request                   # package URL prefixes, matched against the SBOM
```

The policy's `certificates/` are added to `security/trusted-cas.pem` after the project's own. Every
archive is checked first (`appVerifyPolicy<Target>`): a launcher that lets Node accept older TLS,
passes a forbidden option or doesn't lock its options, a bundle missing a policy certificate, or a
forbidden package in the SBOM, and the build fails before any archive exists. A deployment
pipeline's `app-smoke-test` checks it again with `appSmokeTest.securityPolicy`.
