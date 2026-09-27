package com.buddystudy.backend.study

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.port.outbound.CustomQuestionReceipt
import com.buddystudy.backend.study.application.port.outbound.CustomQuestionRequestPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.CustomQuestionService
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Instant

class CustomQuestionServiceTest {
    private val studies = Mockito.mock(StudyPort::class.java)
    private val questions = QuestionStore()
    private val receipts = ReceiptStore()
    private val service = CustomQuestionService(studies, questions, receipts)
    private val owner = Principal(7, "device", 1, false)

    @Test
    fun `custom question persists supplied answer without AI quota or grading dependencies`(): Unit = runBlocking {
        Mockito.`when`(studies.findByIdAndUserId(10, 7)).thenReturn(StudyEntity(id = 10, userId = 7, topic = "Swift", difficultyLevel = 3))

        val result = service.create(owner, 10, "custom-key-1", "  Explain actors  ", "My own answer", "en")

        assertThat(result.question.question).isEqualTo("Explain actors")
        assertThat(result.answer).isEqualTo("My own answer")
        assertThat(result.source).isEqualTo("custom_question")
        assertThat(result.gradingResult).isNull()
        assertThat(result.gradingRequestId).isNull()
        assertThat(result.gradingStatus).isNull()
        assertThat(result.questionStatus).isEqualTo(QuestionStatus.GRADED)
        assertThat(result.isPublic).isFalse()
        val saved = questions.rows.values.single()
        assertThat(saved.source).isEqualTo(QuestionSource.CUSTOM_QUESTION)
        assertThat(saved.score).isNull()
        assertThat(saved.gradedAt).isNull()
        assertThat(receipts.rows).hasSize(1)
    }

    @Test
    fun `receipt replay returns original record while changed text or topic conflicts`(): Unit = runBlocking {
        Mockito.`when`(studies.findByIdAndUserId(10, 7)).thenReturn(StudyEntity(id = 10, userId = 7, topic = "Swift"))
        val first = service.create(owner, 10, "custom-key-2", "Q", "A", "en")
        val replay = service.create(owner.copy(deviceId = "second-device"), 10, "custom-key-2", "Q", "A", "en")
        assertThat(replay.id).isEqualTo(first.id)
        assertThat(questions.saveCalls).isEqualTo(1)
        assertThrows<ApiException> { service.create(owner, 10, "custom-key-2", "Changed", "A", "en") }
        assertThrows<ApiException> { service.create(owner, 11, "custom-key-2", "Q", "A", "en") }
        assertThat(receipts.rows).hasSize(1)
    }

    @Test
    fun `missing ownership and invalid input never save a question`(): Unit = runBlocking {
        assertThrows<ApiException> { service.create(owner, 10, "custom-key-3", "Q", "A", "en") }
        for ((question, answer, language) in listOf(Triple(" ", "A", "en"), Triple("Q", "", "en"), Triple("Q", "A", "xx"), Triple("Q".repeat(4001), "A", "en"))) {
            assertThrows<ApiException> { service.create(owner, 10, "custom-key-3", question, answer, language) }
        }
        assertThat(questions.saveCalls).isZero()
        assertThat(receipts.rows).isEmpty()
    }

    private class QuestionStore : QuestionPort by Mockito.mock(QuestionPort::class.java) {
        val rows = mutableMapOf<Long, QuestionEntity>()
        var saveCalls = 0
        override suspend fun save(entity: QuestionEntity): QuestionEntity {
            saveCalls += 1
            if (entity.id == 0L) entity.id = 50L + rows.size
            rows[entity.id] = entity
            return entity
        }
        override suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long) =
            rows[id]?.takeIf { it.userId == userId && it.deletedAt == null }
    }

    private class ReceiptStore : CustomQuestionRequestPort {
        val rows = mutableMapOf<Pair<Long, String>, CustomQuestionReceipt>()
        override suspend fun lockOwner(userId: Long) = true
        override suspend fun find(userId: Long, key: String) = rows[userId to key]
        override suspend fun save(userId: Long, key: String, receipt: CustomQuestionReceipt, now: Instant) { rows[userId to key] = receipt }
    }
}
