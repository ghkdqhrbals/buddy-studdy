package com.buddystudy.backend.community.application.model

import java.time.Instant

data class CommunityQuestionEvent(
    val eventId: String,
    val questionId: Long,
    val userId: Long?,
    val commentId: Long? = null,
    val translationState: String? = null,
    val translationLanguage: String? = null,
    val translationReason: String? = null,
    val requestId: String? = null,
    val questionSourceLanguage: String? = null,
    val questionDisplayLanguage: String? = null,
    val answerSourceLanguage: String? = null,
    val answerDisplayLanguage: String? = null,
    val aiResponseSourceLanguage: String? = null,
    val aiResponseDisplayLanguage: String? = null,
    val occurredAt: Instant,
)
