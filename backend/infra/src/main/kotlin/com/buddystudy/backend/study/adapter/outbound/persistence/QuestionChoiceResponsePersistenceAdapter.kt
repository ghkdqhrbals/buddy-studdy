package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.study.application.port.outbound.QuestionChoiceResponsePort
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.dao.DuplicateKeyException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class QuestionChoiceResponsePersistenceAdapter(
    private val client: DatabaseClient,
    connectionFactory: ConnectionFactory,
) : QuestionChoiceResponsePort {
    private val insertSql = when {
        connectionFactory.metadata.name.contains("mysql", ignoreCase = true) ->
            "$INSERT on duplicate key update option_id = option_id"
        connectionFactory.metadata.name.contains("postgres", ignoreCase = true) ->
            "$INSERT on conflict (question_id, user_id) do nothing"
        else -> INSERT
    }

    override suspend fun insertOrRead(questionId: Long, userId: Long, optionId: String, now: Instant): String {
        try {
            client.sql(insertSql).bind("questionId", questionId).bind("userId", userId)
                .bind("optionId", optionId).bind("createdAt", now).fetch().rowsUpdated().awaitSingle()
        } catch (_: DuplicateKeyException) {
            // Fallback dialects still enforce the same immutable account/question key.
        }
        // A locking read also observes a concurrent winner under MySQL REPEATABLE READ.
        return client.sql(
            "select option_id from question_choice_responses where question_id = :questionId and user_id = :userId for update",
        ).bind("questionId", questionId).bind("userId", userId)
            .map { row, _ -> row.get("option_id", String::class.java)!! }.one().awaitSingle()
    }

    override suspend fun findSelections(questionIds: List<Long>, userId: Long): Map<Long, String> {
        if (questionIds.isEmpty()) return emptyMap()
        return client.sql(
            "select question_id, option_id from question_choice_responses where question_id in (:questionIds) and user_id = :userId",
        ).bind("questionIds", questionIds).bind("userId", userId)
            .map { row, _ ->
                row.get("question_id", java.lang.Long::class.java)!!.toLong() to row.get("option_id", String::class.java)!!
            }.all().collectList().awaitSingle().toMap()
    }

    override suspend fun countByOptions(questionIds: List<Long>): Map<Long, Map<String, Int>> {
        if (questionIds.isEmpty()) return emptyMap()
        return client.sql(
            "select question_id, option_id, count(*) as response_count from question_choice_responses " +
                "where question_id in (:questionIds) group by question_id, option_id",
        ).bind("questionIds", questionIds).map { row, _ ->
            Triple(
                row.get("question_id", java.lang.Long::class.java)!!.toLong(),
                row.get("option_id", String::class.java)!!,
                row.get("response_count", java.lang.Long::class.java)!!.toInt(),
            )
        }.all().collectList().awaitSingle().groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, rows) -> rows.toMap() }
    }

    override suspend fun deleteByQuestionId(questionId: Long) {
        client.sql("delete from question_choice_responses where question_id = :questionId")
            .bind("questionId", questionId).fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun deleteByOwnerUserId(userId: Long) {
        client.sql("delete from question_choice_responses where question_id in (select id from questions where user_id = :userId)")
            .bind("userId", userId).fetch().rowsUpdated().awaitSingle()
    }

    private companion object {
        const val INSERT = "insert into question_choice_responses (question_id, user_id, option_id, created_at) " +
            "values (:questionId, :userId, :optionId, :createdAt)"
    }
}
