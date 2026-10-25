# Performance tuning

Measure before changing anything. Java Flight Recorder (JFR) records what the JVM and the app spend
their time on, at an overhead of a few percent, and the trimmed runtime can carry it.

## Recording

JFR needs the `jdk.jfr` module in the runtime. jdeps finds it when a library uses it (Spring Boot
does); otherwise add it:

```kotlin
javaApp { additionalModules.addAll("jdk.jfr") }
```

Start the app with a recording. In `open` runtime options mode (the default) this goes in
`APP_JAVA_OPTS`, or in `conf/jvm.options` on a server:

```bash
APP_JAVA_OPTS="-XX:StartFlightRecording=duration=120s,filename=$APP_LOG_DIR/app.jfr,settings=profile" \
  bin/orders-service run
```

`settings=profile` samples more often than the default and suits a short investigation. For an
always-on recording that keeps the last hour and writes it out when the app stops:

```text
-XX:StartFlightRecording=maxage=1h,maxsize=200m,dumponexit=true,filename=/var/log/orders-service/app.jfr
```

With `runtimeOptions("locked")` the operator can't add these; put the recording in `jvmArgs` for a
build meant for profiling instead.

Recordings can contain environment variables and system properties: treat them like a heap dump.

## Reading a recording

The `jfr` tool is not in the trimmed runtime; use the one in a full JDK on your own machine. From JDK
21 it has ready-made views:

```bash
jfr summary app.jfr                 # what was recorded
jfr view hot-methods app.jfr        # where CPU time goes
jfr view gc-pauses app.jfr          # how long the collector stops the app
jfr view allocation-by-class app.jfr
jfr view contention-by-site app.jfr # lock contention
```

`jfr view` lists every view. JDK Mission Control opens the same file for flame graphs and
timelines.

## What usually helps

| Symptom in the recording | Change |
|---|---|
| Frequent or long GC pauses, heap near its limit | Raise the heap share: `runtimeDefaults { maxRamPercentage = 85 }` (75% by default; the JVM's own default is 25%) |
| Pauses from the Serial collector | The JVM picks Serial with fewer than 2 CPUs or under about 1792 MB. Give the container 2 CPUs, or choose a collector: `runtimeDefaults { garbageCollector = "G1" }` (the usual default), `"Parallel"` for batch throughput, `"Z"` for low latency on large heaps |
| Slow startup, class loading at the top | A class-data sharing archive built on the target: `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=/var/lib/orders-service/app.jsa` (JDK 19+; a full path, because `jvmArgs` are not expanded). On JDK 25, an ahead-of-time cache with `-XX:AOTCacheOutput` goes further. Both are built by running on the target itself |
| Allocation dominated by a few classes | Fix the code; no flag helps |

Change one thing at a time and record again. `./gradlew build` still runs the smoke test, so a flag
that stops the app starting fails the build.
