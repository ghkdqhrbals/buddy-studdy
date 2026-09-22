package com.buddystudy.backend.community.application.model

/** Public question content only. Answers, grades and author identity never enter this projection. */
data class PublicQuestionSharePreview(
    val id: Long,
    val topic: String,
    val question: String,
    val contentLanguage: String,
)
