package io.github.filipp931.pagecache

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration

class PageCacheEvictorBuilderTest {
    private val ops = FakePageCacheOps()

    @Test
    fun `requires at least one directory`() {
        assertThatThrownBy { PageCacheEvictor.builder(ops).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("directory")
    }

    @Test
    fun `rejects blank suffix`() {
        assertThatThrownBy { PageCacheEvictor.builder(ops).fileSuffix("  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("blank")
    }

    @Test
    fun `rejects negative durations`() {
        val builder = PageCacheEvictor.builder(ops).directory(Path.of("x"))
        assertThatThrownBy { builder.keepRecent(Duration.ofSeconds(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { builder.throttleBetweenFiles(Duration.ofMillis(-5)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accepts a full configuration`() {
        assertThatCode {
            PageCacheEvictor
                .builder(ops)
                .directory(Path.of("a"))
                .directories(listOf(Path.of("b"), Path.of("c")))
                .fileSuffix(".rec")
                .fileSuffixes(listOf(".log", ".wal"))
                .keepRecent(Duration.ofMinutes(5))
                .throttleBetweenFiles(Duration.ofMillis(15))
                .dryRun(true)
                .build()
        }.doesNotThrowAnyException()
    }
}
