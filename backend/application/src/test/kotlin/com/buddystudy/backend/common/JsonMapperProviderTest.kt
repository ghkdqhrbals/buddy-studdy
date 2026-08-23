package com.buddystudy.backend.common

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class JsonMapperProviderTest {
    @Test
    fun `shares one mapper with Kotlin and Java time support`() {
        val mapper = JsonMapperProvider.mapper
        val value = JsonMapperSample(
            name = "BuddyStudy",
            createdAt = Instant.parse("2026-07-26T04:30:00Z"),
        )

        val json = mapper.writeValueAsString(value)
        val restored = mapper.readValue<JsonMapperSample>(json)

        assertThat(JsonMapperProvider.mapper).isSameAs(mapper)
        assertThat(json).contains("\"createdAt\":\"2026-07-26T04:30:00Z\"")
        assertThat(json).contains("\"optional\":null")
        assertThat(restored).isEqualTo(value)
    }

    private data class JsonMapperSample(
        val name: String,
        val createdAt: Instant,
        val optional: String? = null,
    )
}
