package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.backend.auth.application.permission.PermissionRequirementOperator
import com.buddystudy.backend.auth.application.permission.PermissionRequirementType
import com.buddystudy.backend.auth.application.port.outbound.*
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class PermissionPolicyPersistenceAdapter(
    private val client: DatabaseClient,
) : PermissionRequirementQueryPort,
    TermsAgreementQueryPort,
    TermsAgreementCommandPort,
    NotificationPreferenceQueryPort,
    NotificationPreferenceCommandPort,
    PermissionQuotaQueryPort,
    EmailVerificationQueryPort,
    UserStatusQueryPort {

    override suspend fun activeRequirements(permissionCode: String, now: Instant): List<PermissionRequirementProjection> =
        client.sql(
            """
            select pr.id, p.code as permission_code, pr.requirement_type, pr.requirement_key,
                   pr.operator, pr.requirement_value, pr.failure_code
            from permission_requirements pr join permissions p on p.id = pr.permission_id
            where p.code = :permissionCode and pr.effective_at <= :now
              and (pr.retired_at is null or pr.retired_at > :now)
            order by pr.id
            """.trimIndent(),
        ).bind("permissionCode", permissionCode).bind("now", now)
            .map { row, _ -> row.toPermissionRequirementProjection() }
            .all().collectList().awaitSingle()

    override suspend fun activeTerms(now: Instant): List<ActiveTermsProjection> =
        client.sql(
            """
            select id, code, version, title, url, content_hash, required, mutable
            from (
                select t.id, t.code, t.version, t.title, t.url, t.content_hash,
                       tcr.required, tcr.mutable, tcr.display_order,
                       row_number() over (partition by t.code order by t.effective_at desc, t.id desc) as rn
                from term_context_requirements tcr join terms t on t.code = tcr.terms_code
                where tcr.context = 'SIGNUP' and tcr.effective_at <= :now
                  and (tcr.retired_at is null or tcr.retired_at > :now)
                  and t.effective_at <= :now and (t.retired_at is null or t.retired_at > :now)
            ) active_terms where rn = 1 order by display_order, id
            """.trimIndent(),
        ).bind("now", now)
            .map { row, _ -> row.toActiveTermsProjection() }
            .all().collectList().awaitSingle()

    override suspend fun activeTerms(userId: Long?, deviceId: String?, now: Instant): List<ActiveTermsProjection> =
        activeTerms(now).map { it.copy(agreed = hasAgreement(userId, deviceId, it.id)) }

    override suspend fun activeTerms(code: String, now: Instant): ActiveTermsProjection? =
        activeTerms(now).firstOrNull { it.code == code.trim().uppercase() }

    override suspend fun hasRequiredAgreements(userId: Long, deviceId: String?, now: Instant): Boolean =
        activeTerms(now).filter { it.required }.all { hasAgreement(userId, deviceId, it.id) }

    override suspend fun hasAgreement(userId: Long?, deviceId: String?, termsId: Long): Boolean {
        var spec = client.sql(
            """
            select action from user_term_agreements
            where terms_id = :termsId and (
                (:userId is not null and user_id = :userId) or
                (:deviceId is not null and device_id = :deviceId)
            ) order by created_at desc, id desc limit 1
            """.trimIndent(),
        ).bind("termsId", termsId)
            .bindNullable("userId", userId, Long::class.javaObjectType)
            .bindNullable("deviceId", deviceId, String::class.java)
        return spec.map { row, _ -> row.get("action", String::class.java)!! }.one().awaitSingleOrNull() == "AGREED"
    }

    override suspend fun saveAgreement(
        userId: Long?, deviceId: String?, termsId: Long, action: String, source: String,
        ipAddress: String?, userAgent: String?, appVersion: String?, now: Instant,
    ) {
        client.sql(
            """
            insert into user_term_agreements
                (user_id, device_id, terms_id, action, source, ip_address, user_agent, app_version, created_at)
            values (:userId, :deviceId, :termsId, :action, :source, :ipAddress, :userAgent, :appVersion, :now)
            """.trimIndent(),
        ).bindNullable("userId", userId, Long::class.javaObjectType)
            .bindNullable("deviceId", deviceId, String::class.java)
            .bind("termsId", termsId).bind("action", action).bind("source", source)
            .bindNullable("ipAddress", ipAddress, String::class.java)
            .bindNullable("userAgent", userAgent, String::class.java)
            .bindNullable("appVersion", appVersion, String::class.java)
            .bind("now", now).fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun savePreference(userId: Long?, deviceId: String, key: String, enabled: Boolean, now: Instant) {
        val sql = if (userId != null) {
            """
            insert into notification_preferences (user_id, device_id, preference_key, enabled, created_at, updated_at)
            values (:userId, :deviceId, :key, :enabled, :now, :now)
            on duplicate key update
                enabled = values(enabled), device_id = values(device_id), updated_at = values(updated_at)
            """.trimIndent()
        } else {
            """
            insert into notification_preferences (user_id, device_id, preference_key, enabled, created_at, updated_at)
            values (null, :deviceId, :key, :enabled, :now, :now)
            on duplicate key update enabled = values(enabled), updated_at = values(updated_at)
            """.trimIndent()
        }
        var spec = client.sql(sql).bind("deviceId", deviceId).bind("key", key).bind("enabled", enabled).bind("now", now)
        if (userId != null) spec = spec.bind("userId", userId)
        spec.fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun isEnabled(userId: Long?, deviceId: String, key: String): Boolean {
        val storedPreference = client.sql(
            """
            select enabled from notification_preferences
            where preference_key = :key and (
                (:userId is not null and user_id = :userId) or (user_id is null and device_id = :deviceId)
            ) order by case when user_id is not null then 0 else 1 end, updated_at desc, id desc limit 1
            """.trimIndent(),
        ).bind("key", key).bind("deviceId", deviceId)
            .bindNullable("userId", userId, Long::class.javaObjectType)
            .map { row, _ -> row.get("enabled", java.lang.Boolean::class.java)!!.booleanValue() }
            .one().awaitSingleOrNull()
        return storedPreference ?: defaultNotificationPreference(userId, key)
    }

    override suspend fun status(userId: Long, key: String, now: Instant): PermissionQuotaStatus {
        val accountCreatedAt = client.sql("select created_at from users where id = :userId")
            .bind("userId", userId)
            .map { row, _ ->
                row.get("created_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
                    ?: now
            }
            .one()
            .awaitSingleOrNull()
            ?: now
        val period = MonthlyQuotaWindow.periodAt(accountCreatedAt, now)
        if (key != "monthly_question") {
            return PermissionQuotaStatus(0, period.startedAt, period.resetAt)
        }
        val remaining = client.sql(
            """
            select greatest(coalesce(t.monthly_question_limit, 0) - coalesce(u.system_question_count, 0), 0) as remaining
            from users usr
            left join user_membership_tiers t on t.tier_code = coalesce((
                select m.tier from user_memberships m
                where m.user_id = usr.id and m.status = 'ACTIVE'
                  and (m.expires_at is null or m.expires_at > :now)
                order by m.updated_at desc, m.id desc limit 1
            ), 'TIER1')
            left join user_monthly_question_usage u on u.user_id = usr.id and u.period_start = :periodStartedAt
            where usr.id = :userId
            """.trimIndent(),
        ).bind("now", now)
            .bind("periodStartedAt", LocalDateTime.ofInstant(period.startedAt, ZoneOffset.UTC))
            .bind("userId", userId)
            .map { row, _ -> (row.get("remaining") as Number).toLong() }
            .one().awaitSingleOrNull() ?: 0L
        return PermissionQuotaStatus(remaining, period.startedAt, period.resetAt)
    }

    override suspend fun isVerified(userId: Long): Boolean =
        client.sql(
            "select exists(select 1 from users where id = :userId and status = 'ACTIVE' and provider in ('EMAIL', 'GOOGLE') and email <> '') as verified",
        ).bind("userId", userId)
            .map { row, _ -> row.get("verified", java.lang.Boolean::class.java)!!.booleanValue() }
            .one().awaitSingleOrNull() == true

    override suspend fun status(userId: Long): String? =
        client.sql("select status from users where id = :userId").bind("userId", userId)
            .map { row, _ -> row.get("status", String::class.java)!! }.one().awaitSingleOrNull()

    private fun Row.toPermissionRequirementProjection() = PermissionRequirementProjection(
        id = get("id", java.lang.Long::class.java)!!.toLong(),
        permissionCode = get("permission_code", String::class.java)!!,
        type = PermissionRequirementType.valueOf(get("requirement_type", String::class.java)!!),
        key = get("requirement_key", String::class.java)!!,
        operator = PermissionRequirementOperator.valueOf(get("operator", String::class.java)!!),
        value = get("requirement_value", String::class.java),
        failureCode = ApiErrorCode.valueOf(get("failure_code", String::class.java)!!),
    )

    private fun Row.toActiveTermsProjection() = ActiveTermsProjection(
        id = get("id", java.lang.Long::class.java)!!.toLong(), code = get("code", String::class.java)!!,
        version = get("version", String::class.java)!!, title = get("title", String::class.java)!!,
        url = get("url", String::class.java)!!, contentHash = get("content_hash", String::class.java)!!,
        required = get("required", java.lang.Boolean::class.java)!!.booleanValue(),
        mutable = get("mutable", java.lang.Boolean::class.java)!!.booleanValue(),
    )

    private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(name: String, value: T?, type: Class<T>) =
        if (value == null) bindNull(name, type) else bind(name, value)

    internal companion object {
        fun defaultNotificationPreference(userId: Long?, key: String): Boolean =
            userId != null && key == "question_notification"
    }
}
