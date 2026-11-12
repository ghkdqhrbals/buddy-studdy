package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.study.application.model.QuestionChoiceCodec
import com.buddystudy.backend.study.application.model.QuestionChoiceOption
import com.buddystudy.backend.study.application.port.inbound.AnswerGradingWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QueuedAnswerGrading
import com.buddystudy.backend.study.application.port.inbound.StudyRecordWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.StudyService
import com.buddystudy.backend.study.application.service.StudySyncService
import com.buddystudy.backend.test.PassthroughLanguageDetector
import com.buddystudy.backend.test.RecordingLocalizationRequests
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionFormat
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class StudyChoiceLocalizationTest {
    private val principal = Principal(7, "test-device", 1, false)
    private val codec = QuestionChoiceCodec()
    private val currentContract = object : com.buddystudy.backend.common.application.port.outbound.ClientQuestionContractPort {
        override suspend fun currentVersion() = 3
    }
    private fun question() = QuestionEntity(
        id = 41, userId = 7, studyId = 11, question = "검색에는 무엇을 사용합니까?", topic = "데이터베이스",
        questionFormat = QuestionFormat.MULTIPLE_CHOICE,
        choiceOptionsJson = codec.encode(listOf(QuestionChoiceOption("A", "인덱스"), QuestionChoiceOption("B", "전체 검색"))),
        correctOptionId = "A", selectedOptionId = "A", answer = "인덱스", choiceExplanation = "인덱스가 검색을 빠르게 합니다.",
    )
    private fun snapshot(question: QuestionEntity): RecordLocalizationSnapshot {
        val hashes = ContentSourceHashPolicy.recordHashes(question)
        return RecordLocalizationSnapshot(
            question = TextLocalizationSnapshot("ko", "en", hashes.question, "READY", mapOf(
                "topic" to "Databases", "question" to "What should be used for lookup?",
                "choiceOptionsJson" to codec.encode(listOf(QuestionChoiceOption("A", "Index"), QuestionChoiceOption("B", "Full scan"))),
            )),
            answer = hashes.answer?.let { TextLocalizationSnapshot("ko", "en", it, "READY", mapOf("answer" to "Index")) },
            aiResponse = hashes.aiResponse?.let { TextLocalizationSnapshot("ko", "en", it, "READY", mapOf(
                "feedback" to "Correct.", "explanation" to "An index speeds up lookup.",
            )) },
        )
    }

    @Test
    fun `choice acceptance preserves localized labels in the returned owner record`() = runBlocking<Unit> {
        val question = question().apply { status = QuestionStatus.GRADING }
        val localizations = mock(ContentLocalizationPort::class.java)
        `when`(localizations.record(41, "en")).thenReturn(snapshot(question))
        val users = mock(UserPort::class.java)
        `when`(users.findById(7)).thenReturn(UserEntity(id = 7, appLanguage = SupportedLanguage.ENGLISH))
        val writer = object : AnswerGradingWriteUseCase by mock(AnswerGradingWriteUseCase::class.java) {
            override suspend fun queueChoice(userId: Long, recordId: Long, selectedOptionId: String, aiResponseLanguage: String, now: Instant) =
                QueuedAnswerGrading(question, emptyList())
        }
        val service = StudyService(
            mock(QuestionPort::class.java), mock(QuestionStatsPort::class.java), mock(StudyRecordWriteUseCase::class.java),
            writer, mock(PublishOutboxUseCase::class.java), users, PassthroughLanguageDetector(),
            localizations, RecordingLocalizationRequests(), clientContract = currentContract,
        )
        val response = service.answerChoice(principal, 41, "A")
        assertThat(response.question.question).isEqualTo("What should be used for lookup?")
        assertThat(response.multipleChoice!!.options.map { it.text }).containsExactly("Index", "Full scan")
        assertThat(response.localization?.question?.displayLanguage).isEqualTo("en")
        assertThat(response.multipleChoice!!.selectedOptionId).isEqualTo("A")
    }

    @Test
    fun `study room completed objective record includes translated answer and explanation`() = runBlocking<Unit> {
        val question = question().apply {
            status = QuestionStatus.GRADED
            score = 100
            correct = true
            feedback = "정답입니다."
            explanation = choiceExplanation
        }
        val localizations = mock(ContentLocalizationPort::class.java)
        `when`(localizations.record(41, "en")).thenReturn(snapshot(question))
        val questions = mock(QuestionPort::class.java)
        `when`(questions.findLatestPendingByStudyIdsAndLanguage(listOf(11), "en")).thenReturn(emptyList())
        `when`(questions.findLatestCompletedByStudyIdAndUserId(11, 7)).thenReturn(question)
        val studies = mock(StudyPort::class.java)
        val study = StudyEntity(id = 11, userId = 7)
        `when`(studies.findByIdAndUserId(11, 7)).thenReturn(study)
        `when`(studies.findAllByUserId(7)).thenReturn(listOf(study))
        val stats = mock(QuestionStatsPort::class.java)
        `when`(stats.findAllByIds(listOf(41L))).thenReturn(emptyList())
        val response = StudySyncService(studies, questions, stats, localizations, currentContract).study(principal, 11, "en")
        assertThat(response.latestQuestion!!.answer).isEqualTo("Index")
        assertThat(response.latestQuestion!!.gradingResult!!.explanation).isEqualTo("An index speeds up lookup.")
        assertThat(response.latestQuestion!!.multipleChoice!!.explanation).isEqualTo("An index speeds up lookup.")
        assertThat(response.latestQuestion!!.multipleChoice!!.options.map { it.id }).containsExactly("A", "B")
        val localization = response.latestQuestion!!.localization!!
        assertThat(localization.question.sourceLanguage).isEqualTo("ko")
        assertThat(localization.question.requestedLanguage).isEqualTo("en")
        assertThat(localization.question.displayLanguage).isEqualTo("en")
        assertThat(localization.question.isTranslated).isTrue()
        assertThat(localization.answer?.displayLanguage).isEqualTo("en")
        assertThat(localization.aiResponse?.displayLanguage).isEqualTo("en")
        assertThat(localization.aiResponse?.isTranslated).isTrue()
        assertThat(question.sourceLanguage).isEqualTo(SupportedLanguage.KOREAN)
    }
}
