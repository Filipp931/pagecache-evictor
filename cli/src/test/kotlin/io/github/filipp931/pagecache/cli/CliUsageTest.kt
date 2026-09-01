package io.github.filipp931.pagecache.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

class CliUsageTest {
    @Test
    fun `no subcommand is a usage error`() {
        val result = runCli()
        assertThat(result.exitCode).isEqualTo(2)
        assertThat(result.err).contains("specify a subcommand")
    }

    @Test
    fun `evict requires --dir`() {
        val result = runCli("evict")
        assertThat(result.exitCode).isEqualTo(2)
        assertThat(result.err).contains("--dir")
    }

    @Test
    fun `advise requires a known advice`() {
        val result = runCli("advise", "--advice", "sometimes", "file.bin")
        assertThat(result.exitCode).isEqualTo(2)
        assertThat(result.err).contains("sometimes")
    }

    @Test
    fun `bad duration is a usage error`() {
        val result = runCli("evict", "--dir", "x", "--keep-recent", "soon")
        assertThat(result.exitCode).isEqualTo(2)
        assertThat(result.err).contains("soon")
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    fun `blank suffix is rejected before touching the platform`() {
        // parsing succeeds; the builder-level validation must not surface as a stack trace.
        // Off Linux the platform check fires first (exit 1); on Linux it exits 2 —
        // the Linux side is covered by LinuxCliTest.
        val result = runCli("evict", "--dir", "x", "--suffix", " ")
        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.err).doesNotContain("Exception")
    }

    @Test
    fun `stat requires at least one path`() {
        val result = runCli("stat")
        assertThat(result.exitCode).isEqualTo(2)
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    fun `stat off linux is an execution error with a clear message`() {
        val result = runCli("stat", "some-file")
        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.err).contains("Linux")
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    fun `evict off linux is an execution error`() {
        val result = runCli("evict", "--dir", "somewhere")
        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.err).contains("Linux")
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    fun `advise off linux is an execution error`() {
        val result = runCli("advise", "--advice", "dontneed", "file.bin")
        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.err).contains("Linux")
    }

    @Test
    fun `advice values are case insensitive`() {
        // parsing succeeds regardless of platform; execution result differs, so only
        // assert it got past argument parsing (exit 2 would mean a converter error)
        val result = runCli("advise", "--advice", "DontNeed", "file.bin")
        assertThat(result.exitCode).isNotEqualTo(2)
    }
}
