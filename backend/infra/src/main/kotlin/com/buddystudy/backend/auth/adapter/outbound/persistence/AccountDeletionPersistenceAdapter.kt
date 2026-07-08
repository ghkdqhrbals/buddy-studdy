package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

@Component
class AccountDeletionPersistenceAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : AccountDeletionPort {
    override fun deleteAccountData(userId: Long, currentDeviceId: String, now: Instant) {
        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("currentDeviceId", currentDeviceId)
            .addValue("now", Timestamp.from(now))
        val questionIds = ids("select id from questions where user_id = :userId", params)
        val studyIds = ids("select id from studies where user_id = :userId", params)
        val deviceIds = strings(
            """
            select device_id
              from devices
             where user_id = :userId
                or device_id = :currentDeviceId
            """.trimIndent(),
            params,
        )
        val deletionParams = params
            .addValue("questionIds", questionIds.ifEmpty { listOf(-1L) })
            .addValue("studyIds", studyIds.ifEmpty { listOf(-1L) })
            .addValue("deviceIds", deviceIds.ifEmpty { listOf("__none__") })

        update("delete from app_notifications where user_id = :userId or actor_user_id = :userId or device_id in (:deviceIds)", deletionParams)
        update("delete from notification_preferences where user_id = :userId or device_id in (:deviceIds)", deletionParams)
        update("delete from user_term_agreements where user_id = :userId or device_id in (:deviceIds)", deletionParams)
        update("delete from user_monthly_question_usage where user_id = :userId", deletionParams)
        update("delete from user_memberships where user_id = :userId", deletionParams)
        update("delete from user_avatar_items where user_id = :userId", deletionParams)
        update("delete from user_stats_dirty_keys where user_id = :userId", deletionParams)
        update("delete from user_stats where user_id = :userId", deletionParams)
        update("delete from user_roles where user_id = :userId", deletionParams)

        update("delete from reports where reporter_user_id = :userId or question_id in (:questionIds)", deletionParams)
        update("delete from question_comments where user_id = :userId or question_id in (:questionIds)", deletionParams)
        update("delete from question_likes where user_id = :userId or question_id in (:questionIds)", deletionParams)
        update("delete from question_search where user_id = :userId or question_id in (:questionIds)", deletionParams)
        update("delete from question_stats where question_id in (:questionIds)", deletionParams)
        update("delete from question_embeddings where user_id = :userId or question_id in (:questionIds)", deletionParams)
        update("delete from question_push_outbox where user_id = :userId or question_id in (:questionIds) or study_id in (:studyIds)", deletionParams)
        update("delete from study_question_jobs where user_id = :userId or created_question_id in (:questionIds) or study_id in (:studyIds)", deletionParams)
        update("delete from study_question_coverage where study_id in (:studyIds)", deletionParams)
        update("delete from study_question_concepts where study_id in (:studyIds)", deletionParams)
        update("delete from questions where user_id = :userId", deletionParams)
        update("delete from schedules where user_id = :userId", deletionParams)
        update("delete from studies where user_id = :userId", deletionParams)

        update("delete from user_devices where user_id = :userId", deletionParams)
        update(
            """
            update devices
               set user_id = null,
                   updated_at = :now
             where user_id = :userId
            """.trimIndent(),
            deletionParams,
        )
        update("delete from users where id = :userId", deletionParams)
    }

    private fun ids(sql: String, params: MapSqlParameterSource): List<Long> =
        jdbc.query(sql, params) { rs, _ -> rs.getLong(1) }

    private fun strings(sql: String, params: MapSqlParameterSource): List<String> =
        jdbc.query(sql, params) { rs, _ -> rs.getString(1) }

    private fun update(sql: String, params: MapSqlParameterSource) {
        jdbc.update(sql, params)
    }
}
