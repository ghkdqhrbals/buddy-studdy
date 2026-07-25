package com.buddystudy.backend.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class R2dbcCustomConversionsConfigTest {
    @Test
    fun `converts offset timestamps to instants`() {
        val source = OffsetDateTime.of(2026, 7, 23, 14, 35, 27, 0, ZoneOffset.ofHours(9))

        val result = OffsetDateTimeToInstantConverter.convert(source)

        assertThat(result).isEqualTo(Instant.parse("2026-07-23T05:35:27Z"))
    }

    @Test
    fun `registers the native timestamp reading converter`() {
        val conversions = R2dbcConnectionDetailsConfig().r2dbcCustomConversions()

        assertThat(conversions.hasCustomReadTarget(OffsetDateTime::class.java, Instant::class.java)).isTrue()
    }
}
