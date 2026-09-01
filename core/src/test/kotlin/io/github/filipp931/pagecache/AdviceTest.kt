package io.github.filipp931.pagecache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdviceTest {
    @Test
    fun `native values match the generic linux abi`() {
        assertThat(Advice.NORMAL.nativeValue).isEqualTo(0)
        assertThat(Advice.RANDOM.nativeValue).isEqualTo(1)
        assertThat(Advice.SEQUENTIAL.nativeValue).isEqualTo(2)
        assertThat(Advice.WILLNEED.nativeValue).isEqualTo(3)
        assertThat(Advice.DONTNEED.nativeValue).isEqualTo(4)
        assertThat(Advice.NOREUSE.nativeValue).isEqualTo(5)
    }
}
