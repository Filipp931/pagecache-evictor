package io.github.filipp931.pagecache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class PageCacheOpsSupportTest {
    @Test
    @DisabledOnOs(OS.LINUX)
    fun `tryCreate is null off linux`() {
        assertThat(PageCacheOps.tryCreate()).isNull()
        assertThat(PageCacheOps.isSupported()).isFalse()
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `tryCreate links libc on linux`() {
        val ops = PageCacheOps.tryCreate()
        assertThat(ops).isNotNull()
        assertThat(PageCacheOps.isSupported()).isTrue()
        assertThat(ops!!.pageSize()).isGreaterThan(0)
    }
}
