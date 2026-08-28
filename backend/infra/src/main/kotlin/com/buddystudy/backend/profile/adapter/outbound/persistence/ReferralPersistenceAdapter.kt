package com.buddystudy.backend.profile.adapter.outbound.persistence

import com.buddystudy.backend.profile.application.model.ReferralAccountSummary
import com.buddystudy.backend.profile.application.model.PendingReferralAttribution
import com.buddystudy.backend.profile.application.model.PendingReferralAttributionStatus
import com.buddystudy.backend.profile.application.model.ReferralRecord
import com.buddystudy.backend.profile.application.port.outbound.ReferralPort
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class ReferralPersistenceAdapter(
    private val database: DatabaseClient,
) : ReferralPort {
    override suspend fun codeForUser(userId: Long): String? = codeForUser(userId, forUpdate = false)

    override suspend fun lockCodeForUser(userId: Long): String? = codeForUser(userId, forUpdate = true)

    override suspend fun createCode(userId: Long, code: String, now: Instant): Boolean = database.sql(
        """
        insert ignore into referral_codes (user_id, code, created_at, updated_at)
        values (:userId, :code, :now, :now)
        """.trimIndent(),
    ).bind("userId", userId)
        .bind("code", code)
        .bind("now", now.utc())
        .fetch()
        .rowsUpdated()
        .awaitSingle() == 1L

    override suspend fun inviterUserId(code: String): Long? = database.sql(
        "select user_id from referral_codes where code = :code",
    ).bind("code", code)
        .map { row, _ -> (row.get("user_id") as Number).toLong() }
        .one()
        .awaitSingleOrNull()

    override suspend fun lockUsers(userIds: List<Long>): Int {
        val distinctIds = userIds.distinct().sorted()
        if (distinctIds.size != 2) return 0
        return database.sql(
            "select id from users where id in (:firstUserId, :secondUserId) order by id for update",
        ).bind("firstUserId", distinctIds[0])
            .bind("secondUserId", distinctIds[1])
            .map { row, _ -> (row.get("id") as Number).toLong() }
            .all()
            .collectList()
            .awaitSingle()
            .size
    }

    override suspend fun referralForReferredUser(userId: Long): ReferralRecord? = referralForReferredUser(
        userId = userId,
        forUpdate = false,
    )

    override suspend fun lockReferralForReferredUser(userId: Long): ReferralRecord? = referralForReferredUser(
        userId = userId,
        forUpdate = true,
    )

    override suspend fun createReferral(
        inviterUserId: Long,
        referredUserId: Long,
        code: String,
        now: Instant,
    ): Long? {
        val inserted = database.sql(
            """
            insert ignore into referrals (
                inviter_user_id, referred_user_id, referral_code, redeemed_at, created_at, updated_at
            ) values (
                :inviterUserId, :referredUserId, :code, :now, :now, :now
            )
            """.trimIndent(),
        ).bind("inviterUserId", inviterUserId)
            .bind("referredUserId", referredUserId)
            .bind("code", code)
            .bind("now", now.utc())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        if (inserted != 1L) return null
        return database.sql(
            "select id from referrals where referred_user_id = :referredUserId",
        ).bind("referredUserId", referredUserId)
            .map { row, _ -> (row.get("id") as Number).toLong() }
            .one()
            .awaitSingle()
    }

    override suspend fun pendingAttribution(userId: Long): PendingReferralAttribution? = pendingAttribution(
        userId = userId,
        forUpdate = false,
    )

    override suspend fun lockPendingAttribution(userId: Long): PendingReferralAttribution? = pendingAttribution(
        userId = userId,
        forUpdate = true,
    )

    override suspend fun createPendingAttribution(
        inviterUserId: Long,
        referredUserId: Long,
        code: String,
        now: Instant,
    ): Boolean = database.sql(
        """
        insert ignore into referral_signup_attributions (
            inviter_user_id, referred_user_id, referral_code, status,
            captured_at, resolved_at, created_at, updated_at
        ) values (
            :inviterUserId, :referredUserId, :code, 'PENDING',
            :now, null, :now, :now
        )
        """.trimIndent(),
    ).bind("inviterUserId", inviterUserId)
        .bind("referredUserId", referredUserId)
        .bind("code", code)
        .bind("now", now.utc())
        .fetch()
        .rowsUpdated()
        .awaitSingle() == 1L

    override suspend fun createRewardedAttribution(
        inviterUserId: Long?,
        referredUserId: Long,
        code: String,
        now: Instant,
    ): Boolean {
        var statement = database.sql(
            """
            insert ignore into referral_signup_attributions (
                inviter_user_id, referred_user_id, referral_code, status,
                captured_at, resolved_at, created_at, updated_at
            ) values (
                :inviterUserId, :referredUserId, :code, 'REWARDED',
                :now, :now, :now, :now
            )
            """.trimIndent(),
        ).bind("referredUserId", referredUserId)
            .bind("code", code)
            .bind("now", now.utc())
        statement = if (inviterUserId == null) {
            statement.bindNull("inviterUserId", java.lang.Long::class.java)
        } else {
            statement.bind("inviterUserId", inviterUserId)
        }
        return statement.fetch().rowsUpdated().awaitSingle() == 1L
    }

    override suspend fun completePendingAttribution(
        attributionId: Long,
        expectedStatus: PendingReferralAttributionStatus,
        status: PendingReferralAttributionStatus,
        now: Instant,
    ): Boolean = database.sql(
        """
        update referral_signup_attributions
        set status = :status, resolved_at = :now, updated_at = :now
        where id = :id and status = :expectedStatus
        """.trimIndent(),
    ).bind("status", status.name)
        .bind("now", now.utc())
        .bind("id", attributionId)
        .bind("expectedStatus", expectedStatus.name)
        .fetch()
        .rowsUpdated()
        .awaitSingle() == 1L

    override suspend fun rewardBase(userId: Long, now: Instant): Instant = database.sql(
        """
        select max(expires_at) as latest_expiry
        from user_memberships
        where user_id = :userId
          and status = 'ACTIVE'
          and tier in ('TIER2', 'TIER3')
          and expires_at is not null
          and expires_at > :now
        """.trimIndent(),
    ).bind("userId", userId)
        .bind("now", now.utc())
        .map { row, _ ->
            row.get("latest_expiry", LocalDateTime::class.java)
                ?: LocalDateTime.ofInstant(now, ZoneOffset.UTC)
        }
        .one()
        .awaitSingle()
        .toInstant(ZoneOffset.UTC)

    override suspend fun hasUntrackedReferralMembership(userId: Long): Boolean = database.sql(
        """
        select exists(
            select 1
            from user_memberships membership
            where membership.user_id = :userId
              and membership.source = 'REFERRAL'
              and not exists (
                  select 1
                  from referral_reward_grants grant_row
                  where grant_row.membership_id = membership.id
              )
        ) as has_untracked_referral_membership
        """.trimIndent(),
    ).bind("userId", userId)
        .map { row, _ -> row.boolean("has_untracked_referral_membership") }
        .one()
        .awaitSingle()

    override suspend fun grantTier2Month(
        referralId: Long,
        beneficiaryUserId: Long,
        startsAt: Instant,
        endsAt: Instant,
        now: Instant,
    ): Boolean {
        val existing = database.sql(
            """
            select membership_id
            from referral_reward_grants
            where referral_id = :referralId and beneficiary_user_id = :beneficiaryUserId
            """.trimIndent(),
        ).bind("referralId", referralId)
            .bind("beneficiaryUserId", beneficiaryUserId)
            .map { row, _ -> (row.get("membership_id") as Number).toLong() }
            .one()
            .awaitSingleOrNull()
        if (existing != null) return true

        val membershipInserted = database.sql(
            """
            insert into user_memberships (
                user_id, tier, monthly_question_limit_override, status, source,
                source_invoice_id, original_transaction_id, started_at, expires_at, created_at, updated_at
            ) values (
                :userId, 'TIER2', null, 'ACTIVE', 'REFERRAL',
                null, null, :startsAt, :endsAt, :now, :now
            )
            """.trimIndent(),
        ).bind("userId", beneficiaryUserId)
            .bind("startsAt", startsAt.utc())
            .bind("endsAt", endsAt.utc())
            .bind("now", now.utc())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        if (membershipInserted != 1L) return false

        val membershipId = database.sql(
            """
            select id
            from user_memberships
            where user_id = :userId
              and source = 'REFERRAL'
              and started_at = :startsAt
              and expires_at = :endsAt
            order by id desc
            limit 1
            """.trimIndent(),
        ).bind("userId", beneficiaryUserId)
            .bind("startsAt", startsAt.utc())
            .bind("endsAt", endsAt.utc())
            .map { row, _ -> (row.get("id") as Number).toLong() }
            .one()
            .awaitSingle()

        val inserted = database.sql(
            """
            insert ignore into referral_reward_grants (
                referral_id, beneficiary_user_id, membership_id, tier_code,
                reward_months, starts_at, ends_at, created_at
            ) values (
                :referralId, :beneficiaryUserId, :membershipId, 'TIER2',
                1, :startsAt, :endsAt, :now
            )
            """.trimIndent(),
        ).bind("referralId", referralId)
            .bind("beneficiaryUserId", beneficiaryUserId)
            .bind("membershipId", membershipId)
            .bind("startsAt", startsAt.utc())
            .bind("endsAt", endsAt.utc())
            .bind("now", now.utc())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        if (inserted == 1L) return true
        val existingGrantId = database.sql(
            """
            select id
            from referral_reward_grants
            where referral_id = :referralId and beneficiary_user_id = :beneficiaryUserId
            for update
            """.trimIndent(),
        ).bind("referralId", referralId)
            .bind("beneficiaryUserId", beneficiaryUserId)
            .map { row, _ -> (row.get("id") as Number).toLong() }
            .one()
            .awaitSingleOrNull()
        database.sql("delete from user_memberships where id = :membershipId")
            .bind("membershipId", membershipId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return existingGrantId != null
    }

    override suspend fun accountSummary(userId: Long): ReferralAccountSummary = database.sql(
        """
        select
            (select count(*) from referrals where inviter_user_id = :userId) as successful_count,
            coalesce((select sum(reward_months) from referral_reward_grants where beneficiary_user_id = :userId), 0) as reward_months,
            (select min(starts_at) from referral_reward_grants where beneficiary_user_id = :userId) as reward_starts_at,
            (select max(ends_at) from referral_reward_grants where beneficiary_user_id = :userId) as reward_ends_at,
            (
                exists(select 1 from referrals where referred_user_id = :userId)
                or exists(
                    select 1 from referral_signup_attributions
                    where referred_user_id = :userId and status = 'REWARDED'
                )
                or exists(
                    select 1
                    from user_memberships membership
                    where membership.user_id = :userId
                      and membership.source = 'REFERRAL'
                      and not exists (
                          select 1
                          from referral_reward_grants grant_row
                          where grant_row.membership_id = membership.id
                      )
                )
            ) as has_redeemed
        """.trimIndent(),
    ).bind("userId", userId)
        .map { row, _ ->
            ReferralAccountSummary(
                successfulReferralCount = (row.get("successful_count") as Number).toInt(),
                rewardMonthsEarned = (row.get("reward_months") as Number).toInt(),
                rewardStartsAt = row.get("reward_starts_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC),
                rewardEndsAt = row.get("reward_ends_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC),
                hasRedeemedReferral = row.boolean("has_redeemed"),
            )
        }
        .one()
        .awaitSingle()

    private suspend fun pendingAttribution(userId: Long, forUpdate: Boolean): PendingReferralAttribution? {
        val lockClause = if (forUpdate) " for update" else ""
        return database.sql(
            """
            select id, inviter_user_id, referred_user_id, referral_code, status
            from referral_signup_attributions
            where referred_user_id = :userId$lockClause
            """.trimIndent(),
        ).bind("userId", userId)
            .map { row, _ ->
                PendingReferralAttribution(
                    id = (row.get("id") as Number).toLong(),
                    inviterUserId = (row.get("inviter_user_id") as Number?)?.toLong(),
                    referredUserId = (row.get("referred_user_id") as Number).toLong(),
                    referralCode = row.get("referral_code", String::class.java)!!,
                    status = PendingReferralAttributionStatus.valueOf(row.get("status", String::class.java)!!),
                )
            }
            .one()
            .awaitSingleOrNull()
    }

    private suspend fun codeForUser(userId: Long, forUpdate: Boolean): String? {
        val lockClause = if (forUpdate) " for update" else ""
        return database.sql("select code from referral_codes where user_id = :userId$lockClause")
            .bind("userId", userId)
            .map { row, _ -> row.get("code", String::class.java)!! }
            .one()
            .awaitSingleOrNull()
    }

    private suspend fun referralForReferredUser(userId: Long, forUpdate: Boolean): ReferralRecord? {
        val lockClause = if (forUpdate) " for update" else ""
        return database.sql(
            """
            select id, inviter_user_id, referred_user_id, referral_code
            from referrals
            where referred_user_id = :userId$lockClause
            """.trimIndent(),
        ).bind("userId", userId)
            .map { row, _ ->
                ReferralRecord(
                    id = (row.get("id") as Number).toLong(),
                    inviterUserId = (row.get("inviter_user_id") as Number?)?.toLong(),
                    referredUserId = (row.get("referred_user_id") as Number).toLong(),
                    referralCode = row.get("referral_code", String::class.java)!!,
                )
            }
            .one()
            .awaitSingleOrNull()
    }
}

private fun Instant.utc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

private fun io.r2dbc.spi.Row.boolean(column: String): Boolean = when (val value = get(column)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    else -> false
}
