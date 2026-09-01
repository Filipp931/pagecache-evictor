package io.github.filipp931.pagecache.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

@EnabledOnOs(OS.LINUX)
class LinuxCliTest {
    @TempDir
    lateinit var tempDir: Path

    private fun warmFile(name: String, megabytes: Int = 1): Path {
        val path = tempDir.resolve(name)
        Files.write(path, ByteArray(megabytes shl 20))
        Files.readAllBytes(path)
        return path
    }

    @Test
    fun `stat prints a residency table`() {
        val file = warmFile("data.bin")
        val result = runCli("stat", file.toString())

        assertThat(result.exitCode).isZero()
        assertThat(result.out).contains("FILE", "RESIDENT", "data.bin", "%")
    }

    @Test
    fun `stat emits json`() {
        val file = warmFile("data.bin")
        val result = runCli("stat", file.toString(), "--format", "json")

        assertThat(result.exitCode).isZero()
        assertThat(result.out).contains("\"files\"", "\"pagesResident\"", "\"ratio\"")
    }

    @Test
    fun `stat on a missing file fails with exit 1`() {
        val result = runCli("stat", tempDir.resolve("gone").toString())
        assertThat(result.exitCode).isEqualTo(1)
    }

    @Test
    fun `evict dry-run reports what it would evict`() {
        val old = warmFile("old.rec")
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(1))))

        val result =
            runCli(
                "evict",
                "--dir",
                tempDir.toString(),
                "--suffix",
                ".rec",
                "--keep-recent",
                "120s",
                "--dry-run",
            )

        assertThat(result.exitCode).isZero()
        assertThat(result.out).contains("dry run", "would evict 1")
    }

    @Test
    fun `blank suffix is a usage error, not a stack trace`() {
        val result = runCli("evict", "--dir", tempDir.toString(), "--suffix", " ")
        assertThat(result.exitCode).isEqualTo(2)
        assertThat(result.err).contains("blank").doesNotContain("Exception")
    }

    @Test
    fun `advise emits json`() {
        val file = warmFile("adv.bin")
        val result = runCli("advise", "--advice", "sequential", "--format", "json", file.toString())
        assertThat(result.exitCode).isZero()
        assertThat(result.out).contains("\"advice\":\"SEQUENTIAL\"", "\"applied\":1", "\"failed\":0")
    }

    @Test
    fun `evict sweeps for real`() {
        val old = warmFile("old.rec")
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(1))))

        val result = runCli("evict", "--dir", tempDir.toString(), "--keep-recent", "120s", "--throttle", "1ms")

        assertThat(result.exitCode).isZero()
        assertThat(result.out).contains("evicted 1")
    }
}
