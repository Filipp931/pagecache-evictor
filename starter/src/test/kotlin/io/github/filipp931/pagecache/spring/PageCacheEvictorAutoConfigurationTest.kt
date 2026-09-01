package io.github.filipp931.pagecache.spring

import io.github.filipp931.pagecache.PageCacheOps
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Path
import java.time.Duration

class PageCacheEvictorAutoConfigurationTest {
    @TempDir
    lateinit var tempDir: Path

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PageCacheEvictorAutoConfiguration::class.java))

    @Test
    fun `does nothing by default`() {
        runner.run { context ->
            assertThat(context).doesNotHaveBean(PageCacheEvictionScheduler::class.java)
        }
    }

    @Test
    fun `does nothing when explicitly disabled`() {
        runner
            .withPropertyValues("pagecache.evictor.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(PageCacheEvictionScheduler::class.java)
            }
    }

    @Test
    fun `activates when enabled with directories`() {
        runner
            .withPropertyValues(
                "pagecache.evictor.enabled=true",
                "pagecache.evictor.directories=$tempDir",
            ).run { context ->
                assertThat(context).hasSingleBean(PageCacheEvictionScheduler::class.java)
                val scheduler = context.getBean(PageCacheEvictionScheduler::class.java)
                // active exactly where the platform supports it; a no-op elsewhere,
                // but the application context must come up either way
                assertThat(scheduler.isActive).isEqualTo(PageCacheOps.isSupported())
            }
    }

    @Test
    fun `enabled without directories fails startup on every platform`() {
        runner
            .withPropertyValues("pagecache.evictor.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "pagecache.evictor.enabled=true but pagecache.evictor.directories is empty",
                )
            }
    }

    @Test
    fun `malformed cron fails startup on every platform`() {
        runner
            .withPropertyValues(
                "pagecache.evictor.enabled=true",
                "pagecache.evictor.directories=$tempDir",
                "pagecache.evictor.cron=every full moon",
            ).run { context ->
                assertThat(context).hasFailed()
            }
    }

    @Test
    fun `negative durations fail startup on every platform`() {
        runner
            .withPropertyValues(
                "pagecache.evictor.enabled=true",
                "pagecache.evictor.directories=$tempDir",
                "pagecache.evictor.keep-recent=-5s",
            ).run { context ->
                assertThat(context).hasFailed()
            }
        runner
            .withPropertyValues(
                "pagecache.evictor.enabled=true",
                "pagecache.evictor.directories=$tempDir",
                "pagecache.evictor.throttle-between-files=-1ms",
            ).run { context ->
                assertThat(context).hasFailed()
            }
    }

    @Test
    fun `blank file suffix fails startup on every platform`() {
        runner
            .withPropertyValues(
                "pagecache.evictor.enabled=true",
                "pagecache.evictor.directories=$tempDir",
                "pagecache.evictor.file-suffixes[0]= ",
            ).run { context ->
                assertThat(context).hasFailed()
            }
    }

    @Test
    fun `scheduler deactivates when the context closes`() {
        var captured: PageCacheEvictionScheduler? = null
        runner
            .withPropertyValues(
                "pagecache.evictor.enabled=true",
                "pagecache.evictor.directories=$tempDir",
            ).run { context ->
                captured = context.getBean(PageCacheEvictionScheduler::class.java)
            }
        // the runner closes the context after the lambda
        assertThat(captured!!.isRunning()).isFalse()
        assertThat(captured!!.isActive).isFalse()
    }

    @Test
    fun `binds every property`() {
        runner
            .withPropertyValues(
                "pagecache.evictor.enabled=true",
                "pagecache.evictor.directories=$tempDir",
                "pagecache.evictor.file-suffixes=.rec,.wal",
                "pagecache.evictor.keep-recent=5m",
                "pagecache.evictor.cron=0 * * * * *",
                "pagecache.evictor.throttle-between-files=15ms",
            ).run { context ->
                val properties = context.getBean(PageCacheEvictorProperties::class.java)
                assertThat(properties.enabled).isTrue()
                assertThat(properties.directories).containsExactly(tempDir.toString())
                assertThat(properties.fileSuffixes).containsExactly(".rec", ".wal")
                assertThat(properties.keepRecent).isEqualTo(Duration.ofMinutes(5))
                assertThat(properties.cron).isEqualTo("0 * * * * *")
                assertThat(properties.throttleBetweenFiles).isEqualTo(Duration.ofMillis(15))
            }
    }
}
