package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.study.application.content.QuestionNotificationContentPolicy
import com.buddystudy.backend.study.application.service.QuestionNotificationMetadata
import com.buddystudy.backend.study.application.service.QuestionNotificationSerializationException
import com.buddystudy.backend.study.application.service.translateNotificationMetadataSerializationError
import com.buddystudy.backend.study.application.service.toJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.internal.KotlinReflectionInternalError

class QuestionNotificationMetadataTest {
    @Test
    fun `localizes question notification title`() {
        assertThat(QuestionNotificationContentPolicy.title("ko")).isEqualTo("새 질문 도착")
        assertThat(QuestionNotificationContentPolicy.title("en-US")).isEqualTo("New Question")
    }

    @Test
    fun `serializes question notification metadata`() {
        val json = QuestionNotificationMetadata(
            recordId = 101,
            studyId = 19,
            topic = "Kotlin",
            difficultyLevel = 7,
            language = "ko",
            sound = "default",
            intervalMinutes = 30,
        ).toJson()

        val parsed = JsonMapperProvider.mapper.readTree(json)
        assertThat(parsed["recordId"].asLong()).isEqualTo(101)
        assertThat(parsed["studyId"].asLong()).isEqualTo(19)
        assertThat(parsed["topic"].asText()).isEqualTo("Kotlin")
        assertThat(parsed["difficultyLevel"].asInt()).isEqualTo(7)
        assertThat(parsed["language"].asText()).isEqualTo("ko")
        assertThat(parsed["sound"].asText()).isEqualTo("default")
        assertThat(parsed["intervalMinutes"].asInt()).isEqualTo(30)
    }

    @Test
    fun `converts recoverable reflection errors into loggable exceptions`() {
        val reflectionError = KotlinReflectionInternalError("native reflection metadata missing")

        assertThatThrownBy {
            translateNotificationMetadataSerializationError<Unit> {
                throw reflectionError
            }
        }
            .isInstanceOf(QuestionNotificationSerializationException::class.java)
            .hasMessageContaining("native reflection metadata missing")
            .hasCause(reflectionError)
    }
}
