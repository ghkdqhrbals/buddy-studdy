package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.outbox.OutboxPublishSummary
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.ClaimedQuestionTranslation
import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationSource
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.port.inbound.QuestionTranslationExecutionWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionTranslationPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.port.outbound.TranslationValidationMode
import com.buddystudy.backend.study.application.service.QuestionTranslationService
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class QuestionTranslationServiceTest {
    @Test
    fun `translates generated question before localized delivery is published`(): Unit = runBlocking {
        val original = QuestionEntity(
            id = 31,
            userId = 7,
            studyId = 12,
            question = "Redis Stream의 consumer group을 설명하세요.",
            hint = "pending entry를 포함하세요.",
            topic = "메시지 큐",
            sourceLanguage = SupportedLanguage.KOREAN,
        )
        val rootStudy = StudyEntity(id = 11, userId = 7, topic = "Backend")
        val topicStudy = StudyEntity(id = 12, userId = 7, parentStudyId = 11, topic = "Redis")
        val questions = TranslationQuestionPort(original)
        val users = TranslationUserPort(
            UserEntity(
                id = 7,
                providerId = "user-7",
                status = UserStatus.ACTIVE,
                appLanguage = SupportedLanguage.ENGLISH,
            ),
        )
        val studies = TranslationStudyPort(listOf(rootStudy, topicStudy))
        val translations = RecordingTranslationPort()
        val delivery = RecordingTranslationWriter(original)
        val publisher = RecordingPublisher()
        val event = QuestionGeneratedEvent(
            eventId = "question-generated-31",
            correlationId = "correlation-31",
            questionId = 31,
            userId = 7,
            studyId = 11,
            topicId = 12,
            sourceLanguage = "ko",
            generatedAt = Instant.parse("2026-07-27T00:00:00Z"),
        )

        QuestionTranslationService(
            questions = questions,
            translations = translations,
            users = users,
            studies = studies,
            writer = delivery,
            publisher = publisher,
        ).process(event)

        assertThat(translations.calls).containsExactly("메시지 큐")
        assertThat(delivery.translation?.topic).isEqualTo("Message queues")
        assertThat(delivery.translation?.question).isEqualTo("Explain how Redis Stream consumer groups work.")
        assertThat(delivery.translation?.hint).isEqualTo("Include pending entries.")
        assertThat(delivery.question?.question).isEqualTo("Explain how Redis Stream consumer groups work.")
        assertThat(delivery.question?.hint).isEqualTo("Include pending entries.")
        assertThat(delivery.question?.topic).isEqualTo("Message queues")
        assertThat(delivery.rootStudy?.id).isEqualTo(rootStudy.id)
        assertThat(delivery.appLanguage).isEqualTo("en")
        assertThat(publisher.references).containsExactly(
            OutboxReference(OutboxType.DOMAIN_EVENT, 101),
            OutboxReference(OutboxType.QUESTION_PUSH, 102),
        )
    }

    @Test
    fun `delivery publish failure does not undo a completed translation transaction`(): Unit = runBlocking {
        val question = QuestionEntity(
            id = 32,
            userId = 7,
            studyId = 12,
            question = "Explain Redis Stream pending entries.",
            hint = "Include recovery.",
            topic = "Redis",
            sourceLanguage = SupportedLanguage.ENGLISH,
        )
        val rootStudy = StudyEntity(id = 11, userId = 7, topic = "Backend")
        val topicStudy = StudyEntity(id = 12, userId = 7, parentStudyId = 11, topic = "Redis")
        val writer = RecordingTranslationWriter(question)
        val publisher = RecordingPublisher(fail = true)
        val event = QuestionGeneratedEvent(
            eventId = "question-generated-32",
            correlationId = "correlation-32",
            questionId = 32,
            userId = 7,
            studyId = 11,
            topicId = 12,
            sourceLanguage = "en",
            generatedAt = Instant.parse("2026-07-27T00:00:00Z"),
        )

        QuestionTranslationService(
            questions = TranslationQuestionPort(question),
            translations = RecordingTranslationPort(),
            users = TranslationUserPort(
                UserEntity(
                    id = 7,
                    providerId = "user-7",
                    status = UserStatus.ACTIVE,
                    appLanguage = SupportedLanguage.ENGLISH,
                ),
            ),
            studies = TranslationStudyPort(listOf(rootStudy, topicStudy)),
            writer = writer,
            publisher = publisher,
        ).process(event)

        assertThat(writer.question?.id).isEqualTo(question.id)
        assertThat(publisher.references).containsExactly(
            OutboxReference(OutboxType.DOMAIN_EVENT, 101),
            OutboxReference(OutboxType.QUESTION_PUSH, 102),
        )
    }

    private class TranslationQuestionPort(
        private val original: QuestionEntity,
    ) : QuestionPort by unsupportedPort() {
        override suspend fun findQuestionById(id: Long): QuestionEntity? = original
    }

    private class TranslationUserPort(
        private val user: UserEntity,
    ) : UserPort by unsupportedPort() {
        override suspend fun findById(id: Long): UserEntity? = user.takeIf { it.id == id }
    }

    private class TranslationStudyPort(
        private val studies: List<StudyEntity>,
    ) : StudyPort by unsupportedPort() {
        override suspend fun findAllByUserId(userId: Long): List<StudyEntity> =
            studies.filter { it.userId == userId }
    }

    private class RecordingTranslationPort : QuestionTranslationPort {
        val calls = mutableListOf<String>()

        override suspend fun translate(
            topic: String,
            question: String,
            hint: String?,
            sourceLanguage: String,
            targetLanguage: String,
            validationMode: TranslationValidationMode,
        ): TranslatedQuestionContent {
            calls += topic
            return TranslatedQuestionContent(
                topic = "Message queues",
                question = "Explain how Redis Stream consumer groups work.",
                hint = "Include pending entries.",
            )
        }
    }

    private class RecordingTranslationWriter(
        private val completedQuestion: QuestionEntity,
    ) : QuestionTranslationExecutionWriteUseCase {
        var question: QuestionEntity? = null
        var rootStudy: StudyEntity? = null
        var appLanguage: String? = null
        var translation: TranslatedQuestionContent? = null

        override suspend fun claim(event: QuestionGeneratedEvent, now: Instant, streamKey: String) =
            ClaimedQuestionTranslation(
                saga = QuestionGenerationSaga(
                    correlationId = event.correlationId,
                    userId = event.userId,
                    studyId = event.studyId,
                    topicId = event.topicId,
                    questionId = event.questionId,
                    source = event.source,
                    status = QuestionGenerationStatus.TRANSLATING,
                    currentStep = QuestionGenerationStep.TRANSLATING,
                    idempotencyKey = "test",
                    quotaPeriodStartedAt = now,
                    quotaRefundedAt = null,
                    failedStep = null,
                    errorCode = null,
                    errorMessage = null,
                    createdAt = now,
                    updatedAt = now,
                    completedAt = null,
                ),
                inbox = StreamInboxClaim(event.eventId, "translation", "claim", 1),
            )

        override suspend fun complete(
            event: QuestionGeneratedEvent,
            claim: StreamInboxClaim,
            translation: TranslatedQuestionContent?,
            rootStudy: StudyEntity,
            appLanguage: String,
            now: Instant,
        ): QuestionWriteResult {
            val localizedQuestion = completedQuestion
            translation?.let {
                localizedQuestion.topic = it.topic
                localizedQuestion.question = it.question
                localizedQuestion.hint = it.hint
            }
            this.question = localizedQuestion
            this.rootStudy = rootStudy
            this.appLanguage = appLanguage
            this.translation = translation
            return QuestionWriteResult(
                question = localizedQuestion,
                outboxes = listOf(
                    OutboxReference(OutboxType.DOMAIN_EVENT, 101),
                    OutboxReference(OutboxType.QUESTION_PUSH, 102),
                ),
            )
        }

        override suspend fun retry(claim: StreamInboxClaim, error: String, now: Instant) = Unit

        override suspend fun fail(
            event: QuestionGeneratedEvent,
            claim: StreamInboxClaim,
            errorMessage: String,
            now: Instant,
        ) = Unit
    }

    private class RecordingPublisher(
        private val fail: Boolean = false,
    ) : PublishOutboxUseCase {
        val references = mutableListOf<OutboxReference>()

        override suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary {
            this.references += references
            if (fail) error("Push transport is unavailable.")
            return OutboxPublishSummary(references.size, references.size, 0)
        }
    }

    private companion object {
        inline fun <reified T> unsupportedPort(): T =
            java.lang.reflect.Proxy.newProxyInstance(
                T::class.java.classLoader,
                arrayOf(T::class.java),
            ) { _, method, _ ->
                error("Unexpected ${T::class.simpleName} call: ${method.name}")
            } as T
    }
}
