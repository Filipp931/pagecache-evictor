package io.github.filipp931.pagecache.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class OptionsTest {
    @Test
    fun `parses all duration units`() {
        assertThat(parseDuration("500ms")).isEqualTo(Duration.ofMillis(500))
        assertThat(parseDuration("120s")).isEqualTo(Duration.ofSeconds(120))
        assertThat(parseDuration("2m")).isEqualTo(Duration.ofMinutes(2))
        assertThat(parseDuration("1h")).isEqualTo(Duration.ofHours(1))
        assertThat(parseDuration("42")).isEqualTo(Duration.ofSeconds(42))
    }

    @Test
    fun `rejects garbage durations`() {
        assertThatThrownBy { parseDuration("soon") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { parseDuration("1.5s") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { parseDuration("-5s") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `human bytes scale through units`() {
        assertThat(humanBytes(0)).isEqualTo("0 B")
        assertThat(humanBytes(512)).isEqualTo("512 B")
        assertThat(humanBytes(1536)).isEqualTo("1.5 KiB")
        assertThat(humanBytes(64L shl 20)).isEqualTo("64.0 MiB")
        assertThat(humanBytes(3L shl 30)).isEqualTo("3.0 GiB")
        assertThat(humanBytes(5L shl 40)).isEqualTo("5.0 TiB")
    }

    @Test
    fun `json escapes and renders`() {
        assertThat(Json.str("a\"b\\c\n")).isEqualTo("\"a\\\"b\\\\c\\n\"")
        assertThat(Json.obj("x" to Json.num(1), "y" to Json.bool(true))).isEqualTo("{\"x\":1,\"y\":true}")
        assertThat(Json.num(0.25)).isEqualTo("0.2500")
    }
}
