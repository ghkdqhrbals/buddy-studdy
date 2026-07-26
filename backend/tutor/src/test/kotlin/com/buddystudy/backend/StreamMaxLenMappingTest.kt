package com.buddystudy.backend

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.PropertiesConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class StreamMaxLenMappingTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java)

    @Test
    fun `stream-specific environment values override the legacy maximum`() {
        withStreamMappings(
            "REACTION_STREAM_XADD_MAX_LEN=1000",
            "BUDDYSTUDY_DOMAIN_STREAM_MAX_LEN=2000",
            "BUDDYSTUDY_PUSH_STREAM_MAX_LEN=8000",
        ).run { context ->
            val streams = context.getBean(BuddyStudyProperties::class.java).streams

            assertThat(streams.domainMaxLen).isEqualTo(2_000)
            assertThat(streams.pushMaxLen).isEqualTo(8_000)
        }
    }

    @Test
    fun `legacy maximum remains the fallback for both streams`() {
        withStreamMappings(
            "REACTION_STREAM_XADD_MAX_LEN=3000",
        ).run { context ->
            val streams = context.getBean(BuddyStudyProperties::class.java).streams

            assertThat(streams.domainMaxLen).isEqualTo(3_000)
            assertThat(streams.pushMaxLen).isEqualTo(3_000)
        }
    }

    private fun withStreamMappings(vararg values: String): ApplicationContextRunner {
        val properties = values.toMutableList()
        properties += "buddystudy.streams.domain-max-len=\${BUDDYSTUDY_DOMAIN_STREAM_MAX_LEN:\${REACTION_STREAM_XADD_MAX_LEN:1000}}"
        properties += "buddystudy.streams.push-max-len=\${BUDDYSTUDY_PUSH_STREAM_MAX_LEN:\${REACTION_STREAM_XADD_MAX_LEN:1000}}"
        return contextRunner.withPropertyValues(*properties.toTypedArray())
    }
}
