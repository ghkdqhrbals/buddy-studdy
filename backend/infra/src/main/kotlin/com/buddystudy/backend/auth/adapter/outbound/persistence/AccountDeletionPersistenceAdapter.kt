package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
import com.buddystudy.backend.common.adapter.outbound.persistence.bindIndexed
import com.buddystudy.backend.common.adapter.outbound.persistence.indexedBindMarkers
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class AccountDeletionPersistenceAdapter(
    private val client: DatabaseClient,
) : AccountDeletionPort {
    @Transactional
    override suspend fun deleteAccountData(userId: Long, currentDeviceId: String, now: Instant) {
        val questionIds = longs("select id from questions where user_id = :userId", "userId" to userId)
        val studyIds = longs("select id from studies where user_id = :userId", "userId" to userId)
        val deviceIds = strings(
            "select device_id from devices where user_id = :userId or device_id = :currentDeviceId",
            "userId" to userId,
            "currentDeviceId" to currentDeviceId,
        )
        val ids = DeletionIds(
            questionIds = questionIds.ifEmpty { listOf(-1L) },
            studyIds = studyIds.ifEmpty { listOf(-1L) },
            deviceIds = deviceIds.ifEmpty { listOf("__none__") },
        )

        update("delete from app_notifications where user_id = :userId or actor_user_id = :userId or device_id in (:deviceIds)", userId, ids)
        update("delete from notification_preferences where user_id = :userId or device_id in (:deviceIds)", userId, ids)
        update("delete from user_term_agreements where user_id = :userId or device_id in (:deviceIds)", userId, ids)
        listOf(
            "delete from user_monthly_question_usage where user_id = :userId",
            "delete from user_memberships where user_id = :userId",
            "delete from user_avatar_items where user_id = :userId",
            "delete from user_stats_dirty_keys where user_id = :userId",
            "delete from user_stats where user_id = :userId",
            "delete from user_roles where user_id = :userId",
        ).forEach { update(it, userId, ids) }

        update("delete from reports where reporter_user_id = :userId or question_id in (:questionIds)", userId, ids)
        update("delete from question_comments where user_id = :userId or question_id in (:questionIds)", userId, ids)
        update("delete from question_likes where user_id = :userId or question_id in (:questionIds)", userId, ids)
        update("delete from question_search where user_id = :userId or question_id in (:questionIds)", userId, ids)
        update("delete from question_stats where question_id in (:questionIds)", userId, ids)
        update("delete from question_embeddings where user_id = :userId or question_id in (:questionIds)", userId, ids)
        update("delete from question_push_outbox where user_id = :userId or question_id in (:questionIds) or study_id in (:studyIds)", userId, ids)
        update("delete from study_question_jobs where user_id = :userId or created_question_id in (:questionIds) or study_id in (:studyIds)", userId, ids)
        update("delete from study_question_coverage where study_id in (:studyIds)", userId, ids)
        update("delete from study_question_concepts where study_id in (:studyIds)", userId, ids)
        update("delete from questions where user_id = :userId", userId, ids)
        update("delete from schedules where user_id = :userId", userId, ids)
        update("delete from studies where user_id = :userId", userId, ids)
        update("delete from user_devices where user_id = :userId", userId, ids)

        client.sql("update devices set user_id = null, updated_at = :now where user_id = :userId")
            .bind("now", now).bind("userId", userId).fetch().rowsUpdated().awaitSingle()
        update("delete from users where id = :userId", userId, ids)
    }

    private suspend fun longs(sql: String, vararg bindings: Pair<String, Any>): List<Long> {
        var spec = client.sql(sql)
        bindings.forEach { (key, value) -> spec = spec.bind(key, value) }
        return spec.map { row, _ -> row.get(0, java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
    }

    private suspend fun strings(sql: String, vararg bindings: Pair<String, Any>): List<String> {
        var spec = client.sql(sql)
        bindings.forEach { (key, value) -> spec = spec.bind(key, value) }
        return spec.map { row, _ -> row.get(0, String::class.java)!! }.all().collectList().awaitSingle()
    }

    private suspend fun update(sql: String, userId: Long, ids: DeletionIds) {
        val expandedSql = sql
            .replace(":questionIds", indexedBindMarkers("questionId", ids.questionIds.size))
            .replace(":studyIds", indexedBindMarkers("studyId", ids.studyIds.size))
            .replace(":deviceIds", indexedBindMarkers("deviceId", ids.deviceIds.size))
        var spec = client.sql(expandedSql)
        if (sql.contains(":userId")) spec = spec.bind("userId", userId)
        if (sql.contains(":questionIds")) spec = spec.bindIndexed("questionId", ids.questionIds)
        if (sql.contains(":studyIds")) spec = spec.bindIndexed("studyId", ids.studyIds)
        if (sql.contains(":deviceIds")) spec = spec.bindIndexed("deviceId", ids.deviceIds)
        spec.fetch().rowsUpdated().awaitSingle()
    }

    private data class DeletionIds(
        val questionIds: List<Long>,
        val studyIds: List<Long>,
        val deviceIds: List<String>,
    )
}
