package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.localization.application.port.ContentLanguageDetectionPort
import com.buddystudy.backend.localization.application.port.ContentTranslationRequestAppendPort
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.port.outbound.AnswerGradingProgressPort
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.service.QuestionCreationWriteService
import com.buddystudy.backend.study.application.service.StudyRecordWriteService
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyRecordType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.time.Instant

class StudyRecordTypeMutationGuardTest {
    private val now = Instant.parse("2026-08-31T00:00:00Z")
    private val questions = mock(QuestionPort::class.java)
    private val coverage = mock(QuestionCoveragePort::class.java)
    private val progress = mock(AnswerGradingProgressPort::class.java)
    private val outbox = mock(RedisEventOutboxAppendPort::class.java)
    private val languages = mock(ContentLanguageDetectionPort::class.java)
    private val translations = mock(ContentTranslationRequestAppendPort::class.java)
    private val writer = StudyRecordWriteService(questions, coverage, progress, outbox, languages, translations)

    @Test
    fun `skipping an unsubmitted question saves once and repeating preserves its timestamps`(): Unit = runBlocking {
        val record = ordinary()
        `when`(questions.lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)).thenReturn(record)
        `when`(questions.save(record)).thenReturn(record)

        val first = writer.skip(7, record.id)
        val skippedAt = first.skippedAt
        val updatedAt = first.updatedAt
        val repeated = writer.skip(7, record.id)

        assertThat(first.status).isEqualTo(QuestionStatus.SKIPPED)
        assertThat(skippedAt).isNotNull()
        assertThat(repeated).isSameAs(first)
        assertThat(repeated.skippedAt).isEqualTo(skippedAt)
        assertThat(repeated.updatedAt).isEqualTo(updatedAt)
        assertThat(repeated.answer).isNull()
        verify(questions, times(2)).lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)
        verify(questions).save(record)
        verifyNoMoreInteractions(questions)
        verifyNoInteractions(coverage, progress, outbox, languages, translations)
    }

    @Test
    fun `skip cannot overwrite a submitted answer or a non-ungraded lifecycle under the record lock`(): Unit = runBlocking {
        val changes: List<QuestionEntity.() -> Unit> = listOf(
            { status = QuestionStatus.GRADING },
            { status = QuestionStatus.GRADED },
            { status = QuestionStatus.COMPLETED },
            { status = QuestionStatus.FAILED },
            { answer = "My existing answer" },
            { answeredAt = now },
            { score = 70 },
            { gradedAt = now },
            { gradingRequestId = "already-submitted" },
            { gradingStatus = AnswerGradingStatus.QUEUED },
            { gradingRequestedAt = now },
            { gradingStartedAt = now },
            { gradingLastEventId = 91 },
            { skippedAt = now },
        )
        changes.forEachIndexed { index, change ->
            val record = ordinary().apply { id += index }.apply(change)
            val originalStatus = record.status
            val originalAnswer = record.answer
            val originalSkippedAt = record.skippedAt
            `when`(questions.lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)).thenReturn(record)

            val error = runCatching { writer.skip(7, record.id) }.exceptionOrNull()

            assertThat(error).describedAs("guard case %s", index).isInstanceOf(ApiException::class.java)
            assertThat((error as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
            assertThat(error.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
            assertThat(record.status).isEqualTo(originalStatus)
            assertThat(record.answer).isEqualTo(originalAnswer)
            assertThat(record.skippedAt).isEqualTo(originalSkippedAt)
            assertThat(record.updatedAt).isEqualTo(now)
            verify(questions).lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)
        }
        verifyNoMoreInteractions(questions)
        verifyNoInteractions(coverage, progress, outbox, languages, translations)
    }

    @Test
    fun `skip cannot find another users or deleted question through the owned lock`(): Unit = runBlocking {
        val error = runCatching { writer.skip(7, 999) }.exceptionOrNull()

        assertThat(error).isInstanceOf(ApiException::class.java)
        assertThat((error as ApiException).code).isEqualTo(ApiErrorCode.RECORD_NOT_FOUND)
        verify(questions).lockByIdAndUserIdAndDeletedAtIsNull(999, 7)
        verifyNoMoreInteractions(questions)
        verifyNoInteractions(coverage, progress, outbox, languages, translations)
    }

    @Test
    fun `a skip winning the row lock cannot be revived by a later answer or grading submission`(): Unit = runBlocking {
        val record = ordinary()
        `when`(questions.lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)).thenReturn(record)
        `when`(questions.save(record)).thenReturn(record)
        writer.skip(7, record.id)
        val skippedAt = record.skippedAt
        val updatedAt = record.updatedAt

        val laterSubmissions: List<suspend () -> Unit> = listOf(
            { writer.answer(7, record.id, "Late answer", "en", null, now.plusSeconds(1)); Unit },
            { writer.queue(7, record.id, "Late answer", "en", "en", now.plusSeconds(1)); Unit },
        )
        laterSubmissions.forEach { submit ->
            val error = runCatching { submit() }.exceptionOrNull()
            assertThat(error).isInstanceOf(ApiException::class.java)
            assertThat((error as ApiException).status).isEqualTo(HttpStatus.CONFLICT)
            assertThat(error.code).isEqualTo(ApiErrorCode.ANSWER_ALREADY_SUBMITTED)
        }

        assertThat(record.status).isEqualTo(QuestionStatus.SKIPPED)
        assertThat(record.answer).isNull()
        assertThat(record.gradingRequestId).isNull()
        assertThat(record.skippedAt).isEqualTo(skippedAt)
        assertThat(record.updatedAt).isEqualTo(updatedAt)
        verify(questions, times(3)).lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)
        verify(questions).save(record)
        verifyNoMoreInteractions(questions)
        verifyNoInteractions(coverage, progress, outbox, languages, translations)
    }

    @Test
    fun `a retained skipped timestamp also blocks answer and grading when the lifecycle is inconsistent`(): Unit = runBlocking {
        val record = ordinary().apply { skippedAt = now }
        `when`(questions.lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)).thenReturn(record)
        val submissions: List<suspend () -> Unit> = listOf(
            { writer.answer(7, record.id, "Late answer", "en", null, now.plusSeconds(1)); Unit },
            { writer.queue(7, record.id, "Late answer", "en", "en", now.plusSeconds(1)); Unit },
        )
        submissions.forEach { submit ->
            val error = runCatching { submit() }.exceptionOrNull()
            assertThat(error).isInstanceOf(ApiException::class.java)
            assertThat((error as ApiException).code).isEqualTo(ApiErrorCode.ANSWER_ALREADY_SUBMITTED)
        }
        assertThat(record.answer).isNull()
        assertThat(record.updatedAt).isEqualTo(now)
        verify(questions, times(2)).lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)
        verifyNoMoreInteractions(questions)
        verifyNoInteractions(coverage, progress, outbox, languages, translations)
    }

    @Test
    fun `voice record rejects answer skip and grading queue without touching question side effects`(): Unit = runBlocking {
        val record = voice()
        `when`(questions.lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)).thenReturn(record)

        val mutations: List<suspend () -> Unit> = listOf(
            { writer.answer(7, record.id, "replacement answer", "en", grade(), now); Unit },
            { writer.skip(7, record.id); Unit },
            { writer.queue(7, record.id, "replacement answer", "en", "en", now); Unit },
        )
        mutations.forEach { mutate ->
            val error = runCatching { mutate() }.exceptionOrNull()
            assertThat(error).isInstanceOf(ApiException::class.java)
            assertThat((error as ApiException).status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
            assertThat(error.code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }

        assertThat(record.answer).isEqualTo("실제 음성 답변")
        assertThat(record.status).isEqualTo(QuestionStatus.COMPLETED)
        assertThat(record.gradingRequestId).isNull()
        verify(questions, times(3)).lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)
        verifyNoMoreInteractions(questions)
        verifyNoInteractions(coverage, progress, outbox, languages, translations)
    }

    @Test
    fun `late forged grading events cannot change a voice result or mark generated coverage`(): Unit = runBlocking {
        val event = AnswerGradingRequestedEvent("event-1", "request-1", 100, 7, now)
        val record = voice().apply {
            gradingRequestId = event.requestId
            gradingStatus = AnswerGradingStatus.QUEUED
            conceptId = 50
            angleKey = "ordinary-concept"
        }
        `when`(questions.lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)).thenReturn(record)

        assertThat(writer.transition(event, AnswerGradingStatus.ANALYZING_EVIDENCE, now)).isFalse()
        assertThat(writer.complete(event, grade(), now).completed).isFalse()
        writer.fail(event, "late ordinary grading failure", now)

        assertThat(record.status).isEqualTo(QuestionStatus.COMPLETED)
        assertThat(record.score).isNull()
        assertThat(record.gradingStatus).isEqualTo(AnswerGradingStatus.QUEUED)
        assertThat(record.feedback).isNull()
        assertThat(record.gradingError).isNull()
        verify(questions, times(3)).lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)
        verifyNoMoreInteractions(questions)
        verifyNoInteractions(coverage, progress, outbox, languages, translations)
    }

    @Test
    fun `scoreless answered voice record can use the common publicity and deletion operations`(): Unit = runBlocking {
        val record = voice().apply { publicQuestion = false }
        `when`(questions.lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)).thenReturn(record)
        `when`(questions.save(record)).thenReturn(record)
        `when`(questions.softDelete(record.id, 7, now)).thenReturn(1)

        val shared = writer.updatePublicity(7, record.id, true)
        writer.delete(7, record.id, now)

        assertThat(shared.publicQuestion).isTrue()
        assertThat(shared.score).isNull()
        assertThat(shared.voiceRecordId).isEqualTo(400)
        verify(questions).softDelete(record.id, 7, now)
        verifyNoInteractions(coverage, progress, outbox, languages, translations)
    }

    @Test
    fun `voice question without a learner answer remains private through the common publicity operation`(): Unit = runBlocking {
        val record = voice().apply { answer = null; publicQuestion = false }
        `when`(questions.lockByIdAndUserIdAndDeletedAtIsNull(record.id, 7)).thenReturn(record)
        `when`(questions.save(record)).thenReturn(record)

        assertThat(writer.updatePublicity(7, record.id, true).publicQuestion).isFalse()
        verifyNoInteractions(coverage, progress, outbox, languages, translations)
    }

    @Test
    fun `question creation rejects both voice type and a forged ordinary type with voice extension`(): Unit = runBlocking {
        val stats = mock(QuestionStatsPort::class.java)
        val embeddings = mock(QuestionEmbeddingPort::class.java)
        val keys = mock(OpenAIQuestionKeyProvider::class.java)
        val creator = QuestionCreationWriteService(questions, stats, embeddings, coverage, keys, outbox, translations)

        listOf(voice(), voice().apply { recordType = StudyRecordType.QUESTION }).forEach { record ->
            assertThatThrownBy {
                runBlocking {
                    creator.saveQuestionWithOutboxes(record, listOf(0.25f), null, OpenAIQuestionKey("test", user = null), now)
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        verifyNoInteractions(questions, stats, embeddings, coverage, keys, outbox, translations)
    }

    private fun voice() = QuestionEntity(
        id = 100,
        userId = 7,
        studyId = 12,
        question = "원래 선생님 질문",
        answer = "실제 음성 답변",
        status = QuestionStatus.COMPLETED,
        source = QuestionSource.VOICE_TUTOR,
        recordType = StudyRecordType.VOICE_TUTOR,
        voiceRecordId = 400,
    )

    private fun ordinary() = QuestionEntity(
        id = 200, userId = 7, studyId = 12, question = "A pending generated question",
        status = QuestionStatus.UNGRADED, createdAt = now, updatedAt = now,
    )

    private fun grade() = GradedAnswer(85, true, "Do not overwrite voice feedback", "Do not infer correctness")
}
