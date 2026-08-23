package com.buddystudy.backend.study

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.model.AnswerGradingStatus
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.study.application.port.inbound.AnswerGradingWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.AnswerGradingService
import com.buddystudy.study.domain.entity.QuestionEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant

class AnswerGradingServiceTest {
    @Test
    fun `redelivered terminal grading closes the Inbox claim without calling OpenAI`() = runBlocking<Unit> {
        val event = AnswerGradingRequestedEvent(
            eventId = "answer-grading-requested:request-1",
            requestId = "request-1",
            recordId = 10,
            userId = 7,
            requestedAt = Instant.parse("2026-07-30T00:00:00Z"),
        )
        val questions = mock(QuestionPort::class.java)
        val studies = mock(StudyPort::class.java)
        val openAI = mock(OpenAIPort::class.java)
        val writer = mock(AnswerGradingWriteUseCase::class.java)
        val inbox = RecordingInbox()
        `when`(questions.findByIdAndUserIdAndDeletedAtIsNull(10, 7)).thenReturn(
            QuestionEntity(
                id = 10,
                userId = 7,
                gradingRequestId = event.requestId,
                gradingStatus = AnswerGradingStatus.COMPLETED,
            ),
        )
        val properties = BuddyStudyProperties().apply {
            openai.userContentApiKey = "test-key"
        }
        val service = AnswerGradingService(
            properties = properties,
            questions = questions,
            studies = studies,
            userContentKeys = UserContentOpenAIKeyProvider(properties),
            openAI = openAI,
            writer = writer,
            inbox = inbox,
            publisher = mock(PublishOutboxUseCase::class.java),
        )

        service.process(event, "study.answer-grading.requested.v1")

        assertThat(inbox.claimedStreamKey).isEqualTo("study.answer-grading.requested.v1")
        assertThat(inbox.succeeded?.eventId).isEqualTo(event.eventId)
        verifyNoInteractions(studies, openAI, writer)
    }

    private class RecordingInbox : StreamInboxPort {
        var claimedStreamKey: String? = null
        var succeeded: StreamInboxClaim? = null

        override suspend fun claim(
            eventId: String,
            consumerGroup: String,
            correlationId: String,
            leaseDuration: Duration,
            now: Instant,
            streamKey: String,
        ): StreamInboxClaim {
            claimedStreamKey = streamKey
            return StreamInboxClaim(eventId, consumerGroup, "claim-1", 1, streamKey)
        }

        override suspend fun markSucceeded(claim: StreamInboxClaim, now: Instant): Boolean {
            succeeded = claim
            return true
        }

        override suspend fun releaseForRetry(
            claim: StreamInboxClaim,
            errorType: String,
            errorMessage: String,
            now: Instant,
        ): Boolean = error("Not expected.")

        override suspend fun markFailed(
            claim: StreamInboxClaim,
            errorType: String,
            errorMessage: String,
            now: Instant,
        ): Boolean = error("Not expected.")
    }
}
