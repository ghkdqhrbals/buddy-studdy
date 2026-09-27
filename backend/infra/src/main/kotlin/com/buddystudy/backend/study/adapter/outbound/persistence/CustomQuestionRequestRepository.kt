package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.study.application.port.outbound.CustomQuestionReceipt
import com.buddystudy.backend.study.application.port.outbound.CustomQuestionRequestPort
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class CustomQuestionRequestRepository(private val database: DatabaseClient) : CustomQuestionRequestPort {
    override suspend fun lockOwner(userId: Long): Boolean = database.sql("select id from users where id = :id for update")
        .bind("id", userId).fetch().one().awaitSingleOrNull() != null

    override suspend fun find(userId: Long, key: String): CustomQuestionReceipt? = database.sql(
        "select question_id, request_hash from question_custom_requests where user_id = :userId and idempotency_key = :key for update",
    ).bind("userId", userId).bind("key", key).map { row, _ ->
        CustomQuestionReceipt(row.get("question_id", java.lang.Long::class.java)!!.toLong(), row.get("request_hash", String::class.java)!!)
    }.one().awaitSingleOrNull()

    override suspend fun save(userId: Long, key: String, receipt: CustomQuestionReceipt, now: Instant) {
        database.sql("insert into question_custom_requests (user_id, idempotency_key, question_id, request_hash, created_at) values (:userId, :key, :questionId, :hash, :now)")
            .bind("userId", userId).bind("key", key).bind("questionId", receipt.questionId)
            .bind("hash", receipt.requestHash).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
            .fetch().rowsUpdated().awaitSingle()
    }
}
