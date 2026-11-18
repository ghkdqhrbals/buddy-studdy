package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.study.application.model.QuestionChoiceCodec
import com.buddystudy.backend.study.application.model.QuestionChoiceOption
import com.buddystudy.backend.study.application.model.forQuestionContract
import com.buddystudy.backend.study.application.port.inbound.AnswerGradingWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QueuedAnswerGrading
import com.buddystudy.backend.study.application.port.inbound.StudyRecordWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.service.StudyService
import com.buddystudy.backend.study.application.service.toStudyRecord
import com.buddystudy.backend.study.application.model.toRecordResponse
import com.buddystudy.backend.test.EmptyContentLocalizationPort
import com.buddystudy.backend.test.PassthroughLanguageDetector
import com.buddystudy.backend.test.RecordingLocalizationRequests
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionFormat
import com.buddystudy.study.domain.entity.QuestionStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class OwnedQuestionContractTest {
    private fun question() = QuestionEntity(id = 41, userId = 7, question = "짝수는 무엇입니까?", topic = "수학",
        questionFormat = QuestionFormat.MULTIPLE_CHOICE,
        choiceOptionsJson = QuestionChoiceCodec().encode(listOf(QuestionChoiceOption("A", "3"), QuestionChoiceOption("B", "2"))),
        correctOptionId = "B", choiceExplanation = "2는 짝수입니다.")

    @Test
    fun `legacy owner keeps pending question with numbered options and no interactive fields`() {
        val response = question().toStudyRecord().toProjection().toRecordResponse()
        val legacy = response.forQuestionContract(1)
        assertThat(legacy.id).isEqualTo(response.id)
        assertThat(legacy.questionStatus).isEqualTo(QuestionStatus.UNGRADED)
        assertThat(legacy.question.question).contains("1. 3", "2. 2", "정답 번호")
        assertThat(legacy.questionFormat).isEqualTo(QuestionFormat.FREE_TEXT)
        assertThat(legacy.multipleChoice).isNull()
        assertThat(response.forQuestionContract(3)).isSameAs(response)
    }

    @Test
    fun `legacy answer accepts only a normalized exact option number and submits stable identity`() = runBlocking<Unit> {
        val question = question()
        val questions = mock(QuestionPort::class.java)
        `when`(questions.findByIdAndUserIdAndDeletedAtIsNull(41, 7)).thenReturn(question)
        val users = mock(UserPort::class.java)
        `when`(users.findById(7)).thenReturn(UserEntity(id = 7))
        var submitted: String? = null
        val writer = object : AnswerGradingWriteUseCase by mock(AnswerGradingWriteUseCase::class.java) {
            override suspend fun queueChoice(userId: Long, recordId: Long, selectedOptionId: String, aiResponseLanguage: String, now: Instant): QueuedAnswerGrading {
                submitted = selectedOptionId
                question.selectedOptionId = selectedOptionId
                question.answer = "2"
                question.status = QuestionStatus.GRADING
                return QueuedAnswerGrading(question, emptyList())
            }
        }
        val service = StudyService(questions, mock(QuestionStatsPort::class.java), mock(StudyRecordWriteUseCase::class.java),
            writer, mock(PublishOutboxUseCase::class.java), users, PassthroughLanguageDetector(),
            EmptyContentLocalizationPort(), RecordingLocalizationRequests())
        val principal = Principal(7, "device", 1, false)
        for (invalid in listOf("B", "2 because it is even", "0", "5", "1,2")) {
            assertThat(runCatching { service.answer(principal, 41, invalid, "ko", true) }.exceptionOrNull()).isInstanceOf(ApiException::class.java)
        }
        assertThat(submitted).isNull()
        val accepted = service.answer(principal, 41, " ２ ", "ko", true)
        assertThat(submitted).isEqualTo("B")
        assertThat(accepted.multipleChoice).isNull()
        assertThat(accepted.question.question).contains("1. 3", "2. 2")
    }
}
