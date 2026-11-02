# Sizing pods from measurements

A chart's `resources` are usually a guess made once and copied ever after. They are measurements that
go stale: every release changes what the app needs. So measure them in CI with the image you are about
to ship, and let the numbers move the chart, as a pull request a person can read.

The plugins don't write charts or change them, and have no task for this yet. The sample's
[`CapacityTest`](../samples/spring-boot-service/src/dockerTest/java/io/github/fiftieshousewife/samples/spring/CapacityTest.java)
shows the measurements below in about a hundred lines of Testcontainers.

## Align memory with the heap

The JVM reads the container's memory limit, and the plugins' default is `-XX:MaxRAMPercentage=75`, so a
chart's limit already sets the heap: a 512 MiB limit gives a 384 MiB heap and leaves 128 MiB for
everything else. That remainder is what to size, because a pod is killed when the two together pass the
limit, and the JVM's own `OutOfMemoryError` won't warn you.

```
limit = heap + metaspace + code cache + threads × stack + direct buffers + headroom
```

| Do | Because |
|---|---|
| Set the memory request equal to the limit | A pod over its request is first in line for eviction when the node runs short |
| Read `memory.peak` from the container's cgroup after a load test, and fail when it nears the limit | It counts everything the kernel counts, which is what the OOM killer goes by; the JVM's own figures miss some |
| Print the real heap with `-XX:+PrintFlagsFinal` and check it against the limit | A flag or a chart change can move the percentage without anyone deciding to |
| Name the collector in `jvmArgs` | The JVM picks Serial under 2 CPUs or about 1792 MB, so resizing a pod can change the collector, and with it the numbers you just measured |
| Count exec probes in the budget | Each one starts a second JVM inside the pod's limit, or use an HTTP probe, which doesn't |

To change the share, set `jvmArgs.add("-XX:MaxRAMPercentage=85")` and measure again; the chart and the
build must agree, so keep the percentage in one place.

## Test CPU repeatably

Latency and throughput swing with how busy the machine is. **CPU time per request** is much steadier:
fix the work, and count what the kernel charged the container for doing it. These are the sample's
hello endpoint on a 2-CPU engine, with a client in its own container:

| Measured | CPU µs per request, by round |
|---|---|
| After 5,000 requests | 242, 269, 180, 146, 153, 143 |
| After 70,000 requests, three fresh containers | 48 47 47 48 · 48 48 48 48 · 47 47 47 48 |
| The same, with two busy containers beside it | 26 27 26 29 · 29 28 27 29 |

Two things to take from that. Warm-up matters far more than it looks: after 5,000 requests the JIT is still
compiling, and the numbers fall by half. And **the number isn't independent of the machine's load**: a busy
engine charged about 40% less per request, and each run still agreed with itself, so agreement alone won't
reveal it. The error is in the dangerous direction, as it under-sizes the pod.

1. **Fix what the JVM sees.** Set the CPU limit, `-XX:ActiveProcessorCount` and the collector, so the
   thread pools and GC threads are the same every run.
2. **Fix the work.** Send a set number of requests, one at a time, from outside the container, so the
   client's own CPU isn't counted. Keep the client the same too: a different rate of requests can change the figure.
3. **Warm up, and throw that away.** Long enough for the rounds to stop falling, which you find by measuring;
   tens of thousands of requests here. Report startup as its own number (about 2.4 CPU-seconds here).
4. **Read the cgroup, not a clock.** `usage_usec` in `/sys/fs/cgroup/cpu.stat`, before and after each round.
5. **Measure on a quiet machine of one type.** Run nothing else on the engine, and compare only numbers from
   the same type of agent. A shared agent's results aren't comparable with a quiet one's.
6. **Repeat, and assert the spread.** Fail the test when rounds disagree: that means the measurement can't be
   trusted, not that the app got slower. Repeatable within a few percent is what to expect, not exact.

```java
long before = cpuMicros(container);
send(client, uri, ROUND);
double microsPerRequest = (double) (cpuMicros(container) - before) / ROUND;
```

The CPU request then comes from arithmetic, using your own endpoints' cost, your peak rate and the utilisation
you want to run at. With made-up figures of 800 µs a request, 200 requests a second and 60% utilisation:

```
800 µs × 200/s ÷ 0.6 ≈ 0.27 cores → cpu: 270m
```

Leave a CPU limit out unless you need one: a limit throttles the app during JVM startup and GC even when
the node is idle, while the request alone still reserves its share.

## Keep the chart current

```yaml
# values.yaml, updated by the pipeline's pull request
resources:
  requests: { cpu: 270m, memory: 512Mi }
  limits: { memory: 512Mi }
```

On each release, or on a schedule:

1. Run the capacity test on the image about to ship, at the chart's current values.
2. **Fail** if the peak nears the limit, the run was throttled, or the container was killed for memory.
3. Work out new values from the measurements, with headroom, and round them so noise doesn't open a pull
   request every day.
4. Open a pull request with the old and new numbers when they move by more than a set share, 10% say.

The CI load is not production's, so treat this as a floor. It catches a release that needs more before
it ships, and starts the chart near the truth; production's own usage, through the
[Vertical Pod Autoscaler](https://github.com/kubernetes/autoscaler/tree/master/vertical-pod-autoscaler)
in `Off` mode say, tells you whether the test's mix of requests is the right one.
