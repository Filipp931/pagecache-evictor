package io.github.filipp931.pagecache

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ResidencyTest {
    @Test
    fun `derives bytes and ratio`() {
        val residency = Residency(pagesTotal = 16, pagesResident = 4, pageSize = 4096)
        assertThat(residency.bytesResident()).isEqualTo(4 * 4096L)
        assertThat(residency.bytesTotal()).isEqualTo(16 * 4096L)
        assertThat(residency.ratio()).isEqualTo(0.25, within(1e-9))
    }

    @Test
    fun `empty file has zero ratio`() {
        val residency = Residency(0, 0, 4096)
        assertThat(residency.ratio()).isZero()
        assertThat(residency.bytesResident()).isZero()
    }

    @Test
    fun `fully resident file has ratio one`() {
        assertThat(Residency(7, 7, 4096).ratio()).isEqualTo(1.0)
    }
}
