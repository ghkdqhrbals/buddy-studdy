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
    fun `stream-specific environment values configure independent event streams`() {
        withStreamMappings(
            "BUDDYSTUDY_NOTIFICATION_REQUESTED_STREAM_MAX_LEN=2000",
            "BUDDYSTUDY_QUESTION_GENERATED_STREAM_MAX_LEN=4000",
            "BUDDYSTUDY_QUESTION_PUSH_REQUESTED_STREAM_MAX_LEN=8000",
            "BUDDYSTUDY_QUESTION_COMMENTED_STREAM_MAX_LEN=16000",
        ).run { context ->
            val streams = context.getBean(BuddyStudyProperties::class.java).streams

            assertThat(streams.notificationRequestedMaxLen).isEqualTo(2_000)
            assertThat(streams.questionGeneratedMaxLen).isEqualTo(4_000)
            assertThat(streams.questionPushRequestedMaxLen).isEqualTo(8_000)
            assertThat(streams.questionCommentedMaxLen).isEqualTo(16_000)
        }
    }

    @Test
    fun `removed legacy maximum does not configure active event streams`() {
        withStreamMappings(
            "REACTION_STREAM_XADD_MAX_LEN=3000",
        ).run { context ->
            val streams = context.getBean(BuddyStudyProperties::class.java).streams

            assertThat(streams.notificationRequestedMaxLen).isEqualTo(1_000)
            assertThat(streams.questionGeneratedMaxLen).isEqualTo(1_000)
            assertThat(streams.questionPushRequestedMaxLen).isEqualTo(1_000)
            assertThat(streams.questionCommentedMaxLen).isEqualTo(1_000)
        }
    }

    private fun withStreamMappings(vararg values: String): ApplicationContextRunner {
        val properties = values.toMutableList()
        properties += "buddystudy.streams.notification-requested-max-len=\${BUDDYSTUDY_NOTIFICATION_REQUESTED_STREAM_MAX_LEN:1000}"
        properties += "buddystudy.streams.question-generated-max-len=\${BUDDYSTUDY_QUESTION_GENERATED_STREAM_MAX_LEN:1000}"
        properties += "buddystudy.streams.question-push-requested-max-len=\${BUDDYSTUDY_QUESTION_PUSH_REQUESTED_STREAM_MAX_LEN:1000}"
        properties += "buddystudy.streams.question-commented-max-len=\${BUDDYSTUDY_QUESTION_COMMENTED_STREAM_MAX_LEN:1000}"
        return contextRunner.withPropertyValues(*properties.toTypedArray())
    }
}
