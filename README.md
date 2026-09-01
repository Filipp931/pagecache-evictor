# pagecache-evictor

[![CI](https://github.com/Filipp931/pagecache-evictor/actions/workflows/ci.yml/badge.svg)](https://github.com/Filipp931/pagecache-evictor/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.filipp931/pagecache-evictor)](https://central.sonatype.com/artifact/io.github.filipp931/pagecache-evictor)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**`POSIX_FADV_DONTNEED` for the JVM — keep the page cache from eating your
tail latency.** The OS keeps every page you write until memory pressure — and
then reclaims them at the worst possible moment, on the cores you care about.
pagecache-evictor gives a JVM process surgical control over the Linux page
cache: `posix_fadvise` and `mincore` through the Java FFM API. No JNI, no JNA,
no helper binaries, zero dependencies.

- **Measure** — how much of a file is actually resident (`mincore`)
- **Evict** — drop cold files from the cache by age policy (`fadvise(DONTNEED)`)
- **Prefetch / hint** — `WILLNEED`, `SEQUENTIAL`, `RANDOM`, `NOREUSE`

Built for low-latency JVM systems (Aeron archives, WALs, tick-data logs), but
useful to anyone who wants the page cache to work *for* them instead of
against them.

## 60 seconds

Grab the standalone binary from
[Releases](https://github.com/Filipp931/pagecache-evictor/releases) — no JVM
needed:

```bash
curl -L -o pagecache https://github.com/Filipp931/pagecache-evictor/releases/latest/download/pagecache-linux-amd64
chmod +x pagecache
```

How resident are your archive segments right now?

```bash
./pagecache stat /var/lib/app/archive/seg-001*.rec
```

```
FILE                                     SIZE    RESIDENT    RATIO
/var/lib/app/archive/seg-0017.rec   128.0 MiB   128.0 MiB   100.0%
/var/lib/app/archive/seg-0018.rec   128.0 MiB   127.2 MiB    99.4%
/var/lib/app/archive/seg-0019.rec   128.0 MiB    54.9 MiB    42.9%
total: 3 files, 384.0 MiB, 310.1 MiB resident (80.7%)
```

What would an age-based sweep reclaim?

```bash
./pagecache evict --dir /var/lib/app/archive --suffix .rec --keep-recent 120s --dry-run
```

```
dry run: swept 1 directory: scanned 143, would evict 118 (14.2 GiB), kept 25 recent, 0 failed
```

Happy with that? Drop the `--dry-run` (and add a throttle — see below why):

```bash
./pagecache evict --dir /var/lib/app/archive --suffix .rec --keep-recent 120s --throttle 15ms
```

```
swept 1 directory: scanned 143, evicted 118 (14.2 GiB), kept 25 recent, 0 failed
```

There is also `pagecache advise --advice willneed|dontneed|sequential|... <path>...`
for raw fadvise, and `--format json` everywhere for machines.
Exit codes: `0` ok, `1` execution error, `2` usage error.

## Use as a library

```kotlin
// build.gradle.kts
implementation("io.github.filipp931:pagecache-evictor:0.1.0")
```

Requires JDK 22+ (final FFM API). The whole point is running *inside* your
process: eviction follows your application's lifecycle, no external cron, no
root.

```java
import io.github.filipp931.pagecache.PageCacheEvictor;
import io.github.filipp931.pagecache.PageCacheOps;
import io.github.filipp931.pagecache.Residency;

PageCacheOps ops = PageCacheOps.tryCreate();   // null -> not Linux; decide yourself
if (ops == null) {
    log.info("no page-cache control on this platform");
    return;
}

// measure
Residency r = ops.residency(Path.of("/var/lib/app/archive/seg-0017.rec"));
log.info("segment resident: {}%", Math.round(r.ratio() * 100));

// evict by age policy, from YOUR scheduler
PageCacheEvictor evictor = PageCacheEvictor.builder(ops)
        .directory(Path.of("/var/lib/app/archive"))
        .fileSuffix(".rec")
        .keepRecent(Duration.ofMinutes(2))        // readers keep the fresh tail
        .throttleBetweenFiles(Duration.ofMillis(15))
        .build();

scheduler.scheduleAtFixedRate(evictor::runOnce, 30, 30, TimeUnit.SECONDS);
```

`runOnce()` is overlap-guarded (a still-running cycle makes the next call
return immediately with `skipped=true`) and never throws for per-file
problems — it returns a `CycleStats` you can log or export as metrics.

### Spring Boot starter

```kotlin
implementation("io.github.filipp931:pagecache-evictor-spring-boot-starter:0.1.0")
```

```yaml
pagecache:
  evictor:
    enabled: true
    directories:
      - /var/lib/app/archive
    file-suffixes:
      - .rec
    keep-recent: 2m
    cron: "*/30 * * * * *"
    throttle-between-files: 15ms
```

On non-Linux platforms the starter logs a single info line and stays inert —
your application starts everywhere. Sweep results are logged at `debug`,
failures at `warn`.

## Why this exists

An append-heavy recorder on a bare-metal low-latency trading system (an Aeron
archive writing on the order of 100 MB/s) fills a NUMA zone's page cache in
minutes. When the zone is full, `kswapd` wakes up and starts reclaiming —
scanning LRU lists on the same cores where busy-spin threads run. p99 grows
monotonically with uptime, and nobody connects "we've been up for 40 minutes"
with "latency is now 3x".

The trap: **retention deletes files, not pages.** Old segments removed by your
retention job keep their pages in the cache until memory pressure finds them.
The fix is surgical: `fadvise(DONTNEED)` old segments on a schedule, keep the
fresh tail cached for readers, and the zone never fills — `kswapd` never has a
reason to wake up on your cores.

## Why the throttle exists

Second lesson, learned the hard way: evicting a large directory in one tight
loop is its own latency event. A burst of ~1500 open/fadvise/close calls in
~50 ms holds kernel page-LRU and mapping locks long enough to stall concurrent
`skb` allocation on a network thread's `sendto` path — observed as
cron-aligned 2–10 ms latency tails that vanish the moment you stop the sweep.

`throttleBetweenFiles(Duration.ofMillis(15))` spreads the calls out so each
lock acquisition stays sub-millisecond. That's the whole feature; no other
page-cache tool has it, because no other page-cache tool runs inside a
latency-sensitive process.

## Related tools

- **[vmtouch](https://hoytech.com/vmtouch/)** — the classic C swiss-army
  knife: stat, lock, evict. External process, no age policy, no throttle,
  needs shelling out or cron.
- **pcstat** (Go) and **fincore** (util-linux) — residency stats only, no
  eviction.
- **nocache** — an `LD_PRELOAD` wrapper that stops a process from polluting
  the cache; changes the whole process's I/O behavior instead of targeting
  cold files.

pagecache-evictor is the only one you can embed in a JVM process: eviction
lives in the application's lifecycle, with the application's config, metrics
and scheduler — no root, no external cron, no fork/exec on the hot box.

## Limitations

- **Linux only.** `tryCreate()` returns `null` elsewhere; the CLI says so and
  exits 1; the starter logs one line and stays inert.
- **fadvise is advisory.** `DONTNEED` drops what it can, immediately —
  but the kernel owes you nothing.
- **Dirty pages are not evicted.** `DONTNEED` skips pages not yet written
  back. Either keep `keep-recent` above your writeback horizon (the default
  2 minutes is usually enough) or `fsync` files before evicting them.
- **JDK 22+** (the FFM API is final there). On JDK 24+ run with
  `--enable-native-access=ALL-UNNAMED` to avoid the native-access warning —
  the released fat jar sets the `Enable-Native-Access` manifest attribute so
  `java -jar pagecache.jar` just works.
- The released native binary links against glibc (built on `ubuntu-latest`);
  on musl/distroless images use the fat jar instead. If you build your own
  GraalVM native image with the library embedded, the jar ships a build-time
  feature that registers its FFM downcalls automatically (GraalVM for
  JDK 25+).
- The evictor sweeps **regular files only** and never follows symlinks — a
  stray link in a swept directory cannot evict files outside it.
- Reading another user's files needs the same permissions as `open(2)` —
  there is no magic; eviction affects the shared cache, so be deliberate on
  multi-tenant boxes.

## Building

```bash
./gradlew build          # unit tests everywhere; real-syscall tests run on Linux
./gradlew :cli:nativeCompile   # native binary (Linux + GraalVM)
```

The integration tests (residency of a written-fsynced-read 64 MiB file drops
after eviction, end-to-end evictor sweeps) run in CI on a Linux runner — see
`.github/workflows/ci.yml`.

## Companion projects

- [pinlint](https://github.com/Filipp931/pinlint) — linter for CPU pinning
  topologies: validate your core layout against the machine and against
  reality. Same latency-hygiene toolbox, different resource.
- [Low-latency debugging cookbook](https://gist.github.com/Filipp931/f5d47d777423f21d8828da5d06c3d40d)
  — the war stories behind both tools, in long form.

## License

[Apache 2.0](LICENSE)
