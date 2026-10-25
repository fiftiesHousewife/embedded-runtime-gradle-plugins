---
name: embedded-runtime-performance
description: Measure and tune the performance of a Java app packaged with the java-app plugin, using Java Flight Recorder on its embedded runtime - record, read the recording with jfr view, then change heap, GC or startup settings one at a time. Use when asked why the app is slow, uses too much memory, pauses, starts slowly, or to profile or tune it.
---

# Tune an embedded-runtime Java app with Java Flight Recorder

Measure first; change one thing; measure again.

## 1. Make sure the runtime can record

Read `build/app-work/jdeps/modules.txt` (after `./gradlew appArchive`). If `jdk.jfr` is missing, add
`javaApp { additionalModules.addAll("jdk.jfr") }` to `build.gradle.kts` and rebuild.

## 2. Record a representative run

Install this machine's app and start it with a recording, then drive it with the traffic that shows
the problem (a load test, the slow request, or just startup):

```bash
./gradlew installDist
APP_ROOT=/tmp/app APP_PORT=8080 APP_LOG_TARGET=stdout \
APP_JAVA_OPTS="-XX:StartFlightRecording=duration=120s,filename=/tmp/app/app.jfr,settings=profile" \
  build/install/<app>/bin/<app> run
```

If the build sets `runtimeOptions("locked")`, put the `-XX:StartFlightRecording` option in
`javaApp { jvmArgs }` temporarily instead, and remove it afterwards.

## 3. Read it

Use `jfr` from a full JDK 21 or later on this machine (not the trimmed runtime, which has no tools):

```bash
jfr summary /tmp/app/app.jfr
jfr view hot-methods /tmp/app/app.jfr
jfr view gc-pauses /tmp/app/app.jfr
jfr view allocation-by-class /tmp/app/app.jfr
jfr view contention-by-site /tmp/app/app.jfr
```

Report what dominates, with the numbers.

## 4. Change one thing

| Finding | Change in `javaApp { jvmArgs }` |
|---|---|
| Heap near its limit, frequent GC | `-XX:MaxRAMPercentage=75` |
| Serial GC in use (under 2 CPUs or ~1.8 GB) | more CPUs for the container, or `-XX:+UseG1GC` / `-XX:+UseParallelGC` (throughput) / `-XX:+UseZGC` (latency) |
| Startup dominated by class loading | `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=$APP_DATA_DIR/app.jsa` |
| A few classes dominate allocation, or one lock dominates contention | a code change, not a flag |

## 5. Prove it

`./gradlew build` (the smoke test must still pass), record again with the same traffic, and compare
the same `jfr view`. Keep the change only if the numbers moved. See `docs/performance.md` in the
plugin repository for the background.
