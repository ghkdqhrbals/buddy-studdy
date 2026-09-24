package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.FollowUpQuestionUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionGenerationRequestWriteUseCase
import com.buddystudy.backend.study.application.model.QuestionThreadResponse
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

/** Composes durable question generation and localized record browsing. */
@Service
class FollowUpQuestionService(
    private val writer: QuestionGenerationRequestWriteUseCase,
    private val publisher: PublishOutboxUseCase,
    private val questions: QuestionPort,
    private val records: BrowseRecordsUseCase,
) : FollowUpQuestionUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @RequirePermission(Permissions.QUESTION_FOLLOW_UP)
    override suspend fun request(principal: Principal, recordId: Long, idempotencyKey: String) =
        writer.enqueueFollowUp(principal.userId, recordId, idempotencyKey, Instant.now()).let { queued ->
            // The durable outbox owns recovery if the immediate Redis publication fails.
            runCatching { publisher.publishNow(queued.outboxes) }
                .onFailure { log.warn("follow_up_publish_deferred correlationId={}", queued.accepted.correlationId) }
            queued.accepted
        }

    override suspend fun thread(principal: Principal, recordId: Long, language: String, view: String): QuestionThreadResponse {
        val record = questions.findByIdAndUserIdAndDeletedAtIsNull(recordId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val thread = questions.findThreadByRootAndUser(record.rootRecordId ?: record.id, principal.userId)
        return QuestionThreadResponse(thread.filter { it.deletedAt == null }.map {
            records.recordForThread(principal, it.id, language, view)
        })
    }
}
