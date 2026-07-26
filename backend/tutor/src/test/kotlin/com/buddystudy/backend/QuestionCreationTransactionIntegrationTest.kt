package com.buddystudy.backend

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionStatsRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.StudyRepository
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystudy.backend.study.application.service.QuestionCreationWriteManager
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@SpringBootTest
@Import(QuestionCreationTransactionIntegrationTest.FailureConfig::class)
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.analytics.datasource.database-name=",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ],
)
class QuestionCreationTransactionIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var studies: StudyRepository
    @Autowired lateinit var questions: QuestionRepository
    @Autowired lateinit var stats: QuestionStatsRepository
    @Autowired lateinit var manager: QuestionCreationWriteManager

    @Test
    fun `late persistence failure rolls back question and stats together`(): Unit = runBlocking {
        val now = Instant.parse("2030-02-01T00:00:00Z")
        val user = users.save(
            UserEntity(
                provider = "EMAIL",
                providerId = "question-rollback@example.com",
                email = "question-rollback@example.com",
                status = "ACTIVE",
            ),
        )
        val study = studies.save(
            StudyEntity(
                deviceId = "question-rollback-device",
                userId = user.id,
                topic = "Transaction Rollback",
            ),
        )
        val question = QuestionEntity(
            deviceId = study.deviceId,
            userId = user.id,
            studyId = study.id,
            question = "This question must be rolled back.",
            topic = study.topic,
            language = "en",
            difficultyLevel = 3,
            status = "ungraded",
            source = "manual",
            createdAt = now,
            updatedAt = now,
        )
        val statsBefore = stats.count()

        val result = runCatching {
            manager.saveQuestionWithNotification(
                question = question,
                embedding = listOf(0.1f, 0.2f),
                coverage = null,
                questionKey = OpenAIQuestionKey(apiKey = "test", user = user),
                notification = {
                    NotificationRequestCommand(
                        eventId = "rollback-${it.id}",
                        userId = user.id,
                        title = "Question",
                        body = it.question,
                    )
                },
                push = {
                    QuestionPushRequest(
                        recordId = it.id,
                        studyId = it.studyId,
                        deviceId = it.deviceId,
                        userId = it.userId,
                        question = it.question,
                        expectedAnswerHint = it.hint,
                        topic = it.topic,
                        difficultyLevel = it.difficultyLevel,
                        language = it.language,
                        sound = null,
                        intervalMinutes = 15,
                    )
                },
                now = now,
            )
        }

        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("forced embedding failure")
        assertThat(questions.findAll().none { it.question == question.question }).isTrue()
        assertThat(stats.count()).isEqualTo(statsBefore)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FailureConfig {
        @Bean
        @Primary
        fun failingQuestionEmbeddingPort(): QuestionEmbeddingPort =
            object : QuestionEmbeddingPort {
                override suspend fun save(
                    questionId: Long,
                    userId: Long,
                    studyId: Long,
                    topic: String,
                    question: String,
                    embedding: List<Float>,
                ): QuestionEmbeddingCandidate {
                    throw IllegalStateException("forced embedding failure")
                }

                override suspend fun findRecentByStudyIdAndTopic(
                    studyId: Long,
                    topic: String,
                    limit: Int,
                ): List<QuestionEmbeddingCandidate> = emptyList()
            }
    }
}
