package com.buddystudy.backend.study.application.port.outbound

import java.time.Instant

data class CustomQuestionReceipt(val questionId: Long, val requestHash: String)

/** Only the idempotency receipt lives here; all study content remains in the existing question store. */
interface CustomQuestionRequestPort {
    suspend fun lockOwner(userId: Long): Boolean
    suspend fun find(userId: Long, key: String): CustomQuestionReceipt?
    suspend fun save(userId: Long, key: String, receipt: CustomQuestionReceipt, now: Instant)
}
