package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.backend.auth.application.permission.PermissionRequirementOperator
import com.buddystudy.backend.auth.application.permission.PermissionRequirementType
import com.buddystudy.backend.auth.application.port.outbound.ActiveTermsProjection
import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationQueryPort
import com.buddystudy.backend.auth.application.port.outbound.NotificationPreferenceQueryPort
import com.buddystudy.backend.auth.application.port.outbound.PermissionQuotaQueryPort
import com.buddystudy.backend.auth.application.port.outbound.PermissionRequirementProjection
import com.buddystudy.backend.auth.application.port.outbound.PermissionRequirementQueryPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementCommandPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementQueryPort
import com.buddystudy.backend.auth.application.port.outbound.UserStatusQueryPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

@Component
class PermissionPolicyPersistenceAdapter(
    private val jdbc: JdbcTemplate,
) : PermissionRequirementQueryPort,
    TermsAgreementQueryPort,
    TermsAgreementCommandPort,
    NotificationPreferenceQueryPort,
    PermissionQuotaQueryPort,
    EmailVerificationQueryPort,
    UserStatusQueryPort {
    override fun activeRequirements(permissionCode: String, now: Instant): List<PermissionRequirementProjection> =
        jdbc.query(
            """
            select pr.id,
                   p.code as permission_code,
                   pr.requirement_type,
                   pr.requirement_key,
                   pr.operator,
                   pr.requirement_value,
                   pr.failure_code
              from permission_requirements pr
              join permissions p on p.id = pr.permission_id
             where p.code = ?
               and pr.effective_at <= ?
               and (pr.retired_at is null or pr.retired_at > ?)
             order by pr.id
            """.trimIndent(),
            { rs, _ -> rs.toPermissionRequirementProjection() },
            permissionCode,
            Timestamp.from(now),
            Timestamp.from(now),
        )

    override fun activeTerms(now: Instant): List<ActiveTermsProjection> =
        jdbc.query(
            """
            select distinct on (code) id, code, version, title, url, content_hash
              from terms
             where effective_at <= ?
               and (retired_at is null or retired_at > ?)
             order by code, effective_at desc, id desc
            """.trimIndent(),
            { rs, _ ->
                ActiveTermsProjection(
                    id = rs.getLong("id"),
                    code = rs.getString("code"),
                    version = rs.getString("version"),
                    title = rs.getString("title"),
                    url = rs.getString("url"),
                    contentHash = rs.getString("content_hash"),
                )
            },
            Timestamp.from(now),
            Timestamp.from(now),
        )

    override fun activeTerms(code: String, now: Instant): ActiveTermsProjection? =
        nullableQuery {
            jdbc.queryForObject(
                """
                select id, code, version, title, url, content_hash
                  from terms
                 where code = ?
                   and effective_at <= ?
                   and (retired_at is null or retired_at > ?)
                 order by effective_at desc, id desc
                 limit 1
                """.trimIndent(),
                { rs, _ ->
                    ActiveTermsProjection(
                        id = rs.getLong("id"),
                        code = rs.getString("code"),
                        version = rs.getString("version"),
                        title = rs.getString("title"),
                        url = rs.getString("url"),
                        contentHash = rs.getString("content_hash"),
                    )
                },
                code,
                Timestamp.from(now),
                Timestamp.from(now),
            )
        }

    override fun hasAgreement(userId: Long?, deviceId: String?, termsId: Long): Boolean {
        val action = nullableQuery {
            jdbc.queryForObject(
                """
                select action
                  from user_term_agreements
                 where terms_id = ?
                   and (
                        (? is not null and user_id = ?)
                        or (? is not null and device_id = ?)
                   )
                 order by created_at desc, id desc
                 limit 1
                """.trimIndent(),
                String::class.java,
                termsId,
                userId,
                userId,
                deviceId,
                deviceId,
            )
        }
        return action == "AGREED"
    }

    override fun saveAgreement(
        userId: Long?,
        deviceId: String?,
        termsId: Long,
        action: String,
        source: String,
        ipAddress: String?,
        userAgent: String?,
        appVersion: String?,
        now: Instant,
    ) {
        jdbc.update(
            """
            insert into user_term_agreements (
                user_id,
                device_id,
                terms_id,
                action,
                source,
                ip_address,
                user_agent,
                app_version,
                created_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            userId,
            deviceId,
            termsId,
            action,
            source,
            ipAddress,
            userAgent,
            appVersion,
            Timestamp.from(now),
        )
    }

    override fun isEnabled(userId: Long?, deviceId: String, key: String): Boolean {
        val enabled = nullableQuery {
            jdbc.queryForObject(
                """
                select enabled
                  from notification_preferences
                 where preference_key = ?
                   and (
                        (? is not null and user_id = ?)
                        or (user_id is null and device_id = ?)
                   )
                 order by case when user_id is not null then 0 else 1 end,
                          updated_at desc,
                          id desc
                 limit 1
                """.trimIndent(),
                Boolean::class.java,
                key,
                userId,
                userId,
                deviceId,
            )
        }
        return enabled == true
    }

    override fun remaining(userId: Long, key: String, now: Instant): Long {
        if (key != "monthly_question") return 0
        val yearMonth = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString()
        return jdbc.queryForObject(
            """
            select greatest(coalesce(t.monthly_question_limit, 0) - coalesce(u.system_question_count, 0), 0)
              from users usr
              left join lateral (
                    select m.tier
                      from user_memberships m
                     where m.user_id = usr.id
                       and m.status = 'ACTIVE'
                       and (m.expires_at is null or m.expires_at > ?)
                     order by m.updated_at desc, m.id desc
                     limit 1
              ) m on true
              left join user_membership_tiers t on t.tier_code = coalesce(m.tier, 'TIER1')
              left join user_monthly_question_usage u on u.user_id = usr.id and u.year_month = ?
             where usr.id = ?
            """.trimIndent(),
            Long::class.java,
            Timestamp.from(now),
            yearMonth,
            userId,
        ) ?: 0L
    }

    override fun isVerified(userId: Long): Boolean =
        jdbc.queryForObject(
            """
            select exists(
                select 1
                  from users
                 where id = ?
                   and status = 'ACTIVE'
                   and provider in ('EMAIL', 'GOOGLE')
                   and email <> ''
            )
            """.trimIndent(),
            Boolean::class.java,
            userId,
        ) == true

    override fun status(userId: Long): String? =
        nullableQuery {
            jdbc.queryForObject(
                "select status from users where id = ?",
                String::class.java,
                userId,
            )
        }

    private fun ResultSet.toPermissionRequirementProjection(): PermissionRequirementProjection =
        PermissionRequirementProjection(
            id = getLong("id"),
            permissionCode = getString("permission_code"),
            type = PermissionRequirementType.valueOf(getString("requirement_type")),
            key = getString("requirement_key"),
            operator = PermissionRequirementOperator.valueOf(getString("operator")),
            value = getString("requirement_value"),
            failureCode = ApiErrorCode.valueOf(getString("failure_code")),
        )

    private fun <T> nullableQuery(block: () -> T): T? =
        try {
            block()
        } catch (_: EmptyResultDataAccessException) {
            null
        }
}
