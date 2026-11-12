package com.buddystudy.backend.study.application.port.outbound

import java.time.Instant

interface QuestionChoiceResponsePort {
    /** Inserts once and returns the canonical selection, including on concurrent retries. */
    suspend fun insertOrRead(questionId: Long, userId: Long, optionId: String, now: Instant): String
    suspend fun findSelections(questionIds: List<Long>, userId: Long): Map<Long, String>
    suspend fun countByOptions(questionIds: List<Long>): Map<Long, Map<String, Int>>
    suspend fun deleteByQuestionId(questionId: Long)
    suspend fun deleteByOwnerUserId(userId: Long)
}
