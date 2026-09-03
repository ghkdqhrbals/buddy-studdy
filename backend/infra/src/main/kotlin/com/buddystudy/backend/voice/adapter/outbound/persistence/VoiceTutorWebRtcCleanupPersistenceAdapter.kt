package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcCleanupClaim
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcCleanupPort
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class VoiceTutorWebRtcCleanupPersistenceAdapter(
    private val database: DatabaseClient,
) : VoiceTutorWebRtcCleanupPort {
    override suspend fun recordPending(
        callId: String,
        userId: Long,
        sessionId: String,
        recoverAfter: Instant,
        now: Instant,
        providerRequestStartedAt: Instant,
    ) {
        database.sql(
            """
            insert into voice_tutor_webrtc_cleanup_outbox (
                call_id, user_id, session_id, attached_at, recover_after,
                claimed_at, claim_token, attempt_count, last_error, created_at, updated_at
            ) values (
                :callId, :userId, :sessionId, null, :recoverAfter,
                null, null, 0, null, :providerRequestStartedAt, :now
            )
            on duplicate key update
                recover_after = least(recover_after, values(recover_after)),
                updated_at = values(updated_at)
            """.trimIndent(),
        ).bind("callId", callId)
            .bind("userId", userId)
            .bind("sessionId", sessionId)
            .bind("recoverAfter", recoverAfter.utc())
            .bind("providerRequestStartedAt", providerRequestStartedAt.utc())
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun attachSession(
        callId: String,
        userId: Long,
        sessionId: String,
        now: Instant,
    ): Boolean {
        val marker = database.sql(
            """
            select call_id, user_id, session_id, attached_at, claim_token, created_at
            from voice_tutor_webrtc_cleanup_outbox
            where call_id = :callId
            for update
            """.trimIndent(),
        ).bind("callId", callId)
            .map { row, _ ->
                CleanupMarker(
                    callId = row.get("call_id", String::class.java)!!,
                    userId = row.get("user_id", java.lang.Long::class.java)!!.toLong(),
                    sessionId = row.get("session_id", String::class.java)!!,
                    attachedAt = row.get("attached_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC),
                    claimToken = row.get("claim_token", String::class.java),
                    providerCreatedAt = row.get("created_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
                )
            }
            .one().awaitSingleOrNull() ?: return false
        if (
            marker.userId != userId || marker.sessionId != sessionId ||
            marker.claimToken != null
        ) return false

        val attached = database.sql(
            """
            update voice_tutor_sessions
            set provider_session_id = coalesce(provider_session_id, :callId),
                hard_ends_at = case
                    when connected_at is null then least(
                        period_ends_at,
                        timestampadd(second, reserved_seconds, :providerCreatedAt)
                    )
                    else hard_ends_at
                end,
                monthly_quota_exhausts_at_hard_end = case
                    when connected_at is null then
                        monthly_quota_exhausts_at_hard_end
                        and timestampadd(second, reserved_seconds, :providerCreatedAt) <= period_ends_at
                    else monthly_quota_exhausts_at_hard_end
                end,
                connected_at = coalesce(connected_at, :providerCreatedAt),
                updated_at = :now
            where id = :sessionId
              and user_id = :userId
              and status = 'ACTIVE'
              and (provider_session_id is null or provider_session_id = :callId)
            """.trimIndent(),
        ).bind("callId", callId)
            .bind("providerCreatedAt", marker.providerCreatedAt.utc())
            .bind("now", now.utc())
            .bind("sessionId", sessionId)
            .bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
        if (attached != 1L) return false

        val marked = database.sql(
            """
            update voice_tutor_webrtc_cleanup_outbox
            set attached_at = coalesce(attached_at, :now),
                updated_at = :now
            where call_id = :callId
              and user_id = :userId
              and session_id = :sessionId
              and claim_token is null
            """.trimIndent(),
        ).bind("now", now.utc())
            .bind("callId", callId)
            .bind("userId", userId)
            .bind("sessionId", sessionId)
            .fetch().rowsUpdated().awaitSingle()
        check(marked == 1L) { "Voice Tutor WebRTC cleanup marker changed while attaching its session." }
        return true
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun claimOrphaned(
        limit: Int,
        now: Instant,
        claimLeaseSeconds: Long,
    ): List<VoiceTutorWebRtcCleanupClaim> {
        val candidates = database.sql(
            """
            select cleanup.call_id, cleanup.user_id, cleanup.session_id
            from voice_tutor_webrtc_cleanup_outbox cleanup
            where cleanup.recover_after <= :now
              and (
                    cleanup.claim_token is null
                    or cleanup.claimed_at <= :staleBefore
                  )
              and not exists (
                    select 1
                    from voice_tutor_sessions session
                    where session.provider_session_id = cleanup.call_id
                  )
            order by cleanup.recover_after, cleanup.call_id
            limit :limit
            for update skip locked
            """.trimIndent(),
        ).bind("now", now.utc())
            .bind("staleBefore", now.minusSeconds(claimLeaseSeconds.coerceAtLeast(5)).utc())
            .bind("limit", limit.coerceIn(1, 500))
            .map { row, _ ->
                CleanupCandidate(
                    callId = row.get("call_id", String::class.java)!!,
                    userId = row.get("user_id", java.lang.Long::class.java)!!.toLong(),
                    sessionId = row.get("session_id", String::class.java)!!,
                )
            }
            .all().collectList().awaitSingle()

        return candidates.map { candidate ->
            val claimToken = UUID.randomUUID().toString()
            val updated = database.sql(
                """
                update voice_tutor_webrtc_cleanup_outbox
                set claimed_at = :now,
                    claim_token = :claimToken,
                    attempt_count = attempt_count + 1,
                    last_error = null,
                    updated_at = :now
                where call_id = :callId
                  and not exists (
                        select 1
                        from voice_tutor_sessions session
                        where session.provider_session_id = voice_tutor_webrtc_cleanup_outbox.call_id
                      )
                """.trimIndent(),
            ).bind("now", now.utc())
                .bind("claimToken", claimToken)
                .bind("callId", candidate.callId)
                .fetch().rowsUpdated().awaitSingle()
            check(updated == 1L) { "Voice Tutor WebRTC cleanup claim changed while locked." }
            VoiceTutorWebRtcCleanupClaim(
                callId = candidate.callId,
                userId = candidate.userId,
                sessionId = candidate.sessionId,
                claimToken = claimToken,
            )
        }
    }

    override suspend fun complete(callId: String): Boolean = database.sql(
        "delete from voice_tutor_webrtc_cleanup_outbox where call_id = :callId",
    ).bind("callId", callId)
        .fetch().rowsUpdated().awaitSingle() == 1L

    override suspend fun completeClaim(callId: String, claimToken: String): Boolean = database.sql(
        """
        delete from voice_tutor_webrtc_cleanup_outbox
        where call_id = :callId and claim_token = :claimToken
        """.trimIndent(),
    ).bind("callId", callId)
        .bind("claimToken", claimToken)
        .fetch().rowsUpdated().awaitSingle() == 1L

    override suspend fun retryClaim(
        callId: String,
        claimToken: String,
        error: String,
        now: Instant,
    ): Boolean = database.sql(
        """
        update voice_tutor_webrtc_cleanup_outbox
        set claimed_at = null,
            claim_token = null,
            recover_after = :now,
            last_error = :error,
            updated_at = :now
        where call_id = :callId and claim_token = :claimToken
        """.trimIndent(),
    ).bind("now", now.utc())
        .bind("error", error.take(1000))
        .bind("callId", callId)
        .bind("claimToken", claimToken)
        .fetch().rowsUpdated().awaitSingle() == 1L

    private data class CleanupMarker(
        val callId: String,
        val userId: Long,
        val sessionId: String,
        val attachedAt: Instant?,
        val claimToken: String?,
        val providerCreatedAt: Instant,
    )

    private data class CleanupCandidate(
        val callId: String,
        val userId: Long,
        val sessionId: String,
    )
}

private fun Instant.utc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
