---
name: jlink-modules
description: Find every JDK module a Java app needs so its jlink-trimmed runtime works, using the java-app plugin's jdeps and jlinkFindModules tasks. Use when appSmokeTest fails with NoClassDefFoundError or ClassNotFoundException for a java.* / javax.* / jdk.* / sun.* class, when setting up javaApp for a new app, or when asked which modules the runtime should contain.
---

# Find the JDK modules for the embedded runtime

The java-app plugin links a runtime holding only the modules the app uses. By default these come
from `jdeps`. jdeps sees only static references, so modules reached by name at runtime are missing
until something runs the app.

## Steps

1. **See what jdeps finds.** Run `./gradlew jdeps` and read `build/app-work/jdeps/modules.txt`. If the build
   sets `javaApp { jlinkModules = listOf(...) }`, that fixed list replaces jdeps entirely; prefer
   removing it and using `additionalModules` for the extras.
2. **Run the smoke test.** `./gradlew appSmokeTest` starts the packaged archive and waits for
   `bin/<app> healthcheck`. If it passes, the startup and health-check paths are covered.
3. **If it fails on a missing JDK class,** run `./gradlew jlinkFindModules`. It links, starts the app,
   maps each JDK class that failed to load to its module (via `jimage list` on the exact JDK), adds
   it, and repeats until healthy. It prints the line to add, for example:
   ```kotlin
   javaApp { additionalModules.addAll("jdk.crypto.ec") }
   ```
   Add that line to `build.gradle.kts` and run `./gradlew build` again.
4. **Cover code paths the health check never reaches.** jlinkFindModules finds only what fails at
   startup or on the health check. Review the app for these runtime-loaded modules and add the ones
   it uses, or make the health check or a test exercise them:

   | Module | Needed for |
   |---|---|
   | `jdk.crypto.ec` | TLS with elliptic-curve certificates or key exchange (merged into `java.base` from JDK 22) |
   | `jdk.crypto.cryptoki` | PKCS#11 hardware security modules |
   | `java.naming` | JNDI, LDAP, some DNS lookups |
   | `jdk.naming.dns` | `dns:` JNDI lookups |
   | `jdk.localedata` | Locales other than English (keep it small with `--include-locales=en,de` in `jlinkOptions`) |
   | `jdk.charsets` | Non-standard charsets such as EBCDIC or Shift_JIS |
   | `jdk.zipfs` | `FileSystems.newFileSystem` on a zip or jar |
   | `java.sql`, `java.sql.rowset` | JDBC drivers |
   | `java.management`, `jdk.management` | JMX, Micrometer JVM metrics |
   | `jdk.unsupported` | `sun.misc.Unsafe`, used by Netty, Kryo and many others |
   | `java.instrument` | `-javaagent` agents, such as OpenTelemetry |
   | `jdk.jfr` | Java Flight Recorder |
   | `java.desktop` | Anything touching AWT, ImageIO or fonts (POI, PDF libraries); also needs fontconfig on the host |
   | `jdk.httpserver` | `com.sun.net.httpserver` |

5. **Never add** `jdk.jdwp.agent` (remote debugging) to a production runtime. The plugin's
   `security { forbiddenModules }` fails the build if it appears.
6. **Check the result.** `./gradlew build` passes, and the archive in `build/distributions/` is still
   small; a runtime over about 60 MB usually means a broad module like `java.se` was added.

## Notes

- Modules must exist in every target JDK. Cross-target builds link against each target JDK's
  `jmods/`; the module list is shared by all targets.
- Third-party libraries that are themselves modular can need JDK modules jdeps reports only with
  `--multi-release`; the plugin already passes the app's Java release.
- See `docs/java.md` in the plugin repository for the full explanation.
