package com.buddystudy.backend.study

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.study.application.model.QuestionItemResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionGenerationRequestWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.service.FollowUpQuestionService
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant

class FollowUpQuestionThreadTest {
    @Test
    fun `thread uses owner-only projection and retains skipped turns while hiding deleted turns`(): Unit = runBlocking {
        val now = Instant.parse("2026-09-24T00:00:00Z")
        val original = QuestionEntity(id = 20, userId = 7, status = QuestionStatus.GRADED)
        val skipped = QuestionEntity(id = 21, userId = 7, rootRecordId = 20, followUpDepth = 1,
            status = QuestionStatus.SKIPPED, skippedAt = now)
        val deleted = QuestionEntity(id = 22, userId = 7, rootRecordId = 20, followUpDepth = 2, deletedAt = now)
        val projectedIds = mutableListOf<Long>()
        val questions = Mockito.mock(QuestionPort::class.java)
        val records = Mockito.mock(BrowseRecordsUseCase::class.java) { call ->
            check(call.method.name == "recordForThread")
            val id = call.getArgument<Long>(1)
            projectedIds += id
            StudyRecordResponse(id.toString(), QuestionItemResponse("Question $id", createdAt = now),
                null, null, "Redis", 5, null, false, questionStatus = if (id == 21L) QuestionStatus.SKIPPED else QuestionStatus.GRADED)
        }
        Mockito.`when`(questions.findByIdAndUserIdAndDeletedAtIsNull(20, 7)).thenReturn(original)
        Mockito.`when`(questions.findThreadByRootAndUser(20, 7)).thenReturn(listOf(original, skipped, deleted))
        val service = FollowUpQuestionService(Mockito.mock(QuestionGenerationRequestWriteUseCase::class.java),
            Mockito.mock(PublishOutboxUseCase::class.java), questions, records)
        val thread = service.thread(Principal(7, "device", 1, false), 20, "en", "localized")
        assertThat(thread.records.map { it.id }).containsExactly("20", "21")
        assertThat(thread.records[1].questionStatus).isEqualTo(QuestionStatus.SKIPPED)
        assertThat(projectedIds).containsExactly(20, 21)
    }
}
