package io.github.filipp931.pagecache.spring

import io.github.filipp931.pagecache.PageCacheEvictor
import io.github.filipp931.pagecache.PageCacheOps
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.support.CronExpression
import org.springframework.scheduling.support.CronTrigger
import java.nio.file.Path

/**
 * Owns a single-thread scheduler that runs [PageCacheEvictor.runOnce] on the
 * configured cron. A [SmartLifecycle] so sweeps start only after the context
 * has fully refreshed (no evicting from under beans still warming their
 * files) and stop in an orderly way on shutdown.
 *
 * On platforms without FFM page-cache support (anything but Linux) it logs one
 * info line at startup and stays inert — the application must start anywhere.
 * Configuration errors (no directories, bad cron, negative durations, blank
 * suffixes), on the other hand, fail the startup on **every** platform:
 * broken config on a dev laptop should not wait for the first Linux deploy to
 * be noticed.
 */
public class PageCacheEvictionScheduler(private val properties: PageCacheEvictorProperties) : SmartLifecycle {
    private var taskScheduler: ThreadPoolTaskScheduler? = null

    @Volatile
    private var running = false

    /** True when a sweep is actually scheduled (Linux with linked libc symbols). */
    @Volatile
    public var isActive: Boolean = false
        private set

    override fun start() {
        if (running) {
            return
        }
        validateConfiguration()
        running = true

        val ops = PageCacheOps.tryCreate()
        if (ops == null) {
            log.info(
                "pagecache-evictor is inactive: this platform has no posix_fadvise/mincore support " +
                    "(needs Linux); the application continues without page-cache eviction",
            )
            return
        }

        val builder =
            PageCacheEvictor
                .builder(ops)
                .keepRecent(properties.keepRecent)
                .throttleBetweenFiles(properties.throttleBetweenFiles)
        properties.directories.forEach { builder.directory(Path.of(it)) }
        properties.fileSuffixes.forEach(builder::fileSuffix)
        val evictor = builder.build()

        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.threadNamePrefix = "pagecache-evictor-"
        scheduler.isDaemon = true
        scheduler.initialize()
        scheduler.schedule({ runCycle(evictor) }, CronTrigger(properties.cron))
        taskScheduler = scheduler
        isActive = true
        log.info(
            "pagecache-evictor scheduled: cron='{}', directories={}, keepRecent={}",
            properties.cron,
            properties.directories,
            properties.keepRecent,
        )
    }

    override fun stop() {
        taskScheduler?.shutdown()
        taskScheduler = null
        isActive = false
        running = false
    }

    override fun isRunning(): Boolean = running

    /**
     * Every config rule the [PageCacheEvictor.Builder] would enforce, applied
     * up front and platform-independently — the builder itself only runs on
     * Linux, and a dev laptop must see the same failures production would.
     */
    private fun validateConfiguration() {
        check(properties.directories.isNotEmpty()) {
            "pagecache.evictor.enabled=true but pagecache.evictor.directories is empty"
        }
        properties.directories.forEach { Path.of(it) } // throws InvalidPathException on garbage
        CronExpression.parse(properties.cron) // throws on malformed cron
        check(!properties.keepRecent.isNegative) {
            "pagecache.evictor.keep-recent must not be negative: ${properties.keepRecent}"
        }
        check(!properties.throttleBetweenFiles.isNegative) {
            "pagecache.evictor.throttle-between-files must not be negative: ${properties.throttleBetweenFiles}"
        }
        check(properties.fileSuffixes.none(String::isBlank)) {
            "pagecache.evictor.file-suffixes must not contain blank entries"
        }
    }

    private fun runCycle(evictor: PageCacheEvictor) {
        try {
            val stats = evictor.runOnce()
            if (log.isDebugEnabled) {
                log.debug("page-cache sweep: {}", stats)
            }
        } catch (e: Exception) {
            log.warn("page-cache sweep failed", e)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(PageCacheEvictionScheduler::class.java)
    }
}
