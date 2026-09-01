package io.github.filipp931.pagecache.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the scheduled page-cache eviction sweep.
 *
 * ```yaml
 * pagecache:
 *   evictor:
 *     enabled: true
 *     directories:
 *       - /var/lib/app/archive
 *     file-suffixes:
 *       - .rec
 *     keep-recent: 2m
 *     throttle-between-files: 15ms
 * ```
 *
 * Deliberately a mutable JavaBean (not a data class with constructor binding):
 * setter binding works without `kotlin-reflect` on the consumer's classpath.
 */
@ConfigurationProperties("pagecache.evictor")
public class PageCacheEvictorProperties {
    /** Master switch; the autoconfiguration backs off entirely when `false`. */
    public var enabled: Boolean = false

    /** Directories to sweep (files directly inside; not recursive). */
    public var directories: List<String> = emptyList()

    /** Only file names with one of these suffixes are touched; empty = all files. */
    public var fileSuffixes: List<String> = emptyList()

    /** Files modified within this window are never evicted. */
    public var keepRecent: Duration = Duration.ofMinutes(2)

    /** 6-field Spring cron for the sweep cadence; the default fires every 30 seconds. */
    public var cron: String = "*/30 * * * * *"

    /** Pause between files to avoid kernel lock bursts. */
    public var throttleBetweenFiles: Duration = Duration.ZERO
}
