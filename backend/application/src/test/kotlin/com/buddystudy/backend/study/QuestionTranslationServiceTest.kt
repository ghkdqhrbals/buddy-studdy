package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.outbox.OutboxPublishSummary
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.port.inbound.QuestionDeliveryWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionTranslationPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
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
            language = "ko",
        )
        val translated = QuestionEntity(
            id = 31,
            userId = 7,
            studyId = 12,
            question = original.question,
            hint = original.hint,
            questionEn = "Explain how Redis Stream consumer groups work.",
            hintEn = "Include pending entries.",
            translationStatus = "READY",
            language = "ko",
        )
        val rootStudy = StudyEntity(id = 11, userId = 7, topic = "Backend")
        val topicStudy = StudyEntity(id = 12, userId = 7, parentStudyId = 11, topic = "Redis")
        val questions = TranslationQuestionPort(original, translated)
        val users = TranslationUserPort(
            UserEntity(id = 7, providerId = "user-7", status = "ACTIVE", appLanguage = "en-US"),
        )
        val studies = TranslationStudyPort(listOf(rootStudy, topicStudy))
        val translations = RecordingTranslationPort()
        val delivery = RecordingDeliveryWriter()
        val publisher = RecordingPublisher()
        val event = QuestionGeneratedEvent(
            eventId = "question-generated-31",
            questionId = 31,
            userId = 7,
            sourceLanguage = "ko",
            generatedAt = Instant.parse("2026-07-27T00:00:00Z"),
        )

        QuestionTranslationService(
            questions = questions,
            translations = translations,
            users = users,
            studies = studies,
            deliveryWriter = delivery,
            outboxPublisher = publisher,
        ).process(event)

        assertThat(translations.calls).containsExactly(original.question)
        assertThat(questions.savedQuestionEn).isEqualTo("Explain how Redis Stream consumer groups work.")
        assertThat(questions.savedHintEn).isEqualTo("Include pending entries.")
        assertThat(delivery.question?.question).isEqualTo(translated.questionEn)
        assertThat(delivery.question?.hint).isEqualTo(translated.hintEn)
        assertThat(delivery.rootStudy?.id).isEqualTo(rootStudy.id)
        assertThat(delivery.appLanguage).isEqualTo("en")
        assertThat(publisher.references).containsExactly(
            OutboxReference(OutboxType.DOMAIN_EVENT, 101),
            OutboxReference(OutboxType.QUESTION_PUSH, 102),
        )
    }

    private class TranslationQuestionPort(
        private val original: QuestionEntity,
        private val translated: QuestionEntity,
    ) : QuestionPort by unsupportedPort() {
        private var reads = 0
        var savedQuestionEn: String? = null
        var savedHintEn: String? = null

        override suspend fun findQuestionById(id: Long): QuestionEntity? =
            if (reads++ == 0) original else translated

        override suspend fun saveEnglishTranslation(
            questionId: Long,
            question: String,
            hint: String?,
            now: Instant,
        ): Boolean {
            savedQuestionEn = question
            savedHintEn = hint
            return questionId == original.id
        }
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

        override suspend fun translateToEnglish(
            question: String,
            hint: String?,
            sourceLanguage: String,
        ): TranslatedQuestionContent {
            calls += question
            return TranslatedQuestionContent(
                question = "Explain how Redis Stream consumer groups work.",
                hint = "Include pending entries.",
            )
        }
    }

    private class RecordingDeliveryWriter : QuestionDeliveryWriteUseCase {
        var question: QuestionEntity? = null
        var rootStudy: StudyEntity? = null
        var appLanguage: String? = null

        override suspend fun enqueue(
            question: QuestionEntity,
            rootStudy: StudyEntity,
            appLanguage: String,
            now: Instant,
        ): QuestionWriteResult {
            this.question = question
            this.rootStudy = rootStudy
            this.appLanguage = appLanguage
            return QuestionWriteResult(
                question = question,
                outboxes = listOf(
                    OutboxReference(OutboxType.DOMAIN_EVENT, 101),
                    OutboxReference(OutboxType.QUESTION_PUSH, 102),
                ),
            )
        }
    }

    private class RecordingPublisher : PublishOutboxUseCase {
        val references = mutableListOf<OutboxReference>()

        override suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary {
            this.references += references
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
