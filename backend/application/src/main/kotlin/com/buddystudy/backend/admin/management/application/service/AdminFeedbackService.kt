package com.buddystudy.backend.admin.management.application.service

import com.buddystudy.backend.admin.management.application.model.AdminFeedbackPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminFeedbackSummary
import com.buddystudy.backend.admin.management.application.port.inbound.AdminFeedbackUseCase
import com.buddystudy.backend.admin.management.application.port.outbound.AdminFeedbackPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AdminFeedbackService(
    private val feedbackStore: AdminFeedbackPort,
) : AdminFeedbackUseCase {
    @Transactional(readOnly = true)
    override suspend fun feedbacks(
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminFeedbackPageResponse {
        val normalizedStatus = status
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.uppercase()
        if (normalizedStatus != null && normalizedStatus !in STATUSES) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Feedback status is invalid.",
            )
        }
        return feedbackStore.feedbacks(
            query = query?.trim()?.takeIf(String::isNotEmpty),
            status = normalizedStatus,
            limit = limit.coerceIn(1, 100),
            offset = offset.coerceAtLeast(0),
        )
    }

    @Transactional
    override suspend fun markReviewed(feedbackId: Long): AdminFeedbackSummary =
        feedbackStore.markReviewed(feedbackId, Instant.now())
            ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "Feedback not found.",
            )

    private companion object {
        val STATUSES = setOf("NEW", "REVIEWED", "REPLIED")
    }
}
