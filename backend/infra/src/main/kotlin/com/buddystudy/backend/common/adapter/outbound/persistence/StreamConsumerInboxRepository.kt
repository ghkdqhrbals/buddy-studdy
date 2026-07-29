package com.buddystudy.backend.common.adapter.outbound.persistence

import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class StreamConsumerInboxRepository(
    private val databaseClient: DatabaseClient,
) : StreamInboxPort {
    @Transactional
    override suspend fun claim(
        eventId: String,
        consumerGroup: String,
        correlationId: String,
        leaseDuration: Duration,
        now: Instant,
        streamKey: String,
    ): StreamInboxClaim? {
        val claimToken = UUID.randomUUID().toString()
        val leaseExpiresAt = now.plus(leaseDuration)
        databaseClient.sql(
            """
            insert into stream_consumer_inbox (
                event_id, consumer_group, correlation_id, stream_key, status, claim_token, attempts,
                lease_expires_at, created_at, updated_at
            ) values (
                :eventId, :consumerGroup, :correlationId, :streamKey, 'PROCESSING', :claimToken, 1,
                :leaseExpiresAt, :now, :now
            )
            on duplicate key update
                stream_key = if(
                    status = 'PROCESSING' and (lease_expires_at is null or lease_expires_at <= :now),
                    values(stream_key),
                    stream_key
                ),
                claim_token = if(
                    status = 'PROCESSING' and (lease_expires_at is null or lease_expires_at <= :now),
                    values(claim_token),
                    claim_token
                ),
                attempts = if(
                    status = 'PROCESSING' and (lease_expires_at is null or lease_expires_at <= :now),
                    attempts + 1,
                    attempts
                ),
                lease_expires_at = if(
                    status = 'PROCESSING' and (lease_expires_at is null or lease_expires_at <= :now),
                    values(lease_expires_at),
                    lease_expires_at
                ),
                last_error_type = if(
                    status = 'PROCESSING' and (lease_expires_at is null or lease_expires_at <= :now),
                    null,
                    last_error_type
                ),
                last_error = if(
                    status = 'PROCESSING' and (lease_expires_at is null or lease_expires_at <= :now),
                    null,
                    last_error
                ),
                updated_at = if(
                    status = 'PROCESSING' and (lease_expires_at is null or lease_expires_at <= :now),
                    values(updated_at),
                    updated_at
                )
            """.trimIndent(),
        )
            .bind("eventId", eventId)
            .bind("consumerGroup", consumerGroup)
            .bind("correlationId", correlationId)
            .bind("streamKey", streamKey)
            .bind("claimToken", claimToken)
            .bind("leaseExpiresAt", leaseExpiresAt.utcDateTime())
            .bind("now", now.utcDateTime())
            .fetch().rowsUpdated().awaitSingle()

        val claim = databaseClient.sql(
            """
            select attempts
            from stream_consumer_inbox
            where event_id = :eventId
              and consumer_group = :consumerGroup
              and status = 'PROCESSING'
              and claim_token = :claimToken
            """.trimIndent(),
        )
            .bind("eventId", eventId)
            .bind("consumerGroup", consumerGroup)
            .bind("claimToken", claimToken)
            .map { row, _ ->
                StreamInboxClaim(
                    eventId = eventId,
                    consumerGroup = consumerGroup,
                    claimToken = claimToken,
                    attempt = row.get("attempts", java.lang.Integer::class.java)!!.toInt(),
                    streamKey = streamKey,
                )
            }
            .one()
            .awaitSingleOrNull()
        if (claim != null) {
            databaseClient.sql(
                """
                update stream_consumer_inbox_attempts
                set status = 'LEASE_EXPIRED',
                    error_type = 'InboxLeaseExpired',
                    error_message = 'The consumer lease expired before the attempt completed.',
                    finished_at = :now,
                    updated_at = :now
                where event_id = :eventId
                  and consumer_group = :consumerGroup
                  and attempt < :attempt
                  and status = 'PROCESSING'
                """.trimIndent(),
            )
                .bind("now", now.utcDateTime())
                .bind("eventId", eventId)
                .bind("consumerGroup", consumerGroup)
                .bind("attempt", claim.attempt)
                .fetch().rowsUpdated().awaitSingle()
            databaseClient.sql(
                """
                insert into stream_consumer_inbox_attempts (
                    event_id, consumer_group, correlation_id, stream_key, attempt, claim_token, status,
                    started_at, created_at, updated_at
                ) values (
                    :eventId, :consumerGroup, :correlationId, :streamKey, :attempt, :claimToken, 'PROCESSING',
                    :now, :now, :now
                )
                on duplicate key update
                    stream_key = values(stream_key),
                    claim_token = values(claim_token),
                    status = 'PROCESSING',
                    error_type = null,
                    error_message = null,
                    started_at = values(started_at),
                    finished_at = null,
                    updated_at = values(updated_at)
                """.trimIndent(),
            )
                .bind("eventId", eventId)
                .bind("consumerGroup", consumerGroup)
                .bind("correlationId", correlationId)
                .bind("streamKey", streamKey)
                .bind("attempt", claim.attempt)
                .bind("claimToken", claimToken)
                .bind("now", now.utcDateTime())
                .fetch().rowsUpdated().awaitSingle()
        }
        return claim
    }

    @Transactional
    override suspend fun markSucceeded(claim: StreamInboxClaim, now: Instant): Boolean {
        val updated = databaseClient.sql(
            """
            update stream_consumer_inbox
            set status = 'SUCCEEDED',
                lease_expires_at = null,
                last_error_type = null,
                last_error = null,
                completed_at = :now,
                failed_at = null,
                updated_at = :now
            where event_id = :eventId
              and consumer_group = :consumerGroup
              and status = 'PROCESSING'
              and claim_token = :claimToken
            """.trimIndent(),
        )
            .bind("now", now.utcDateTime())
            .bind("eventId", claim.eventId)
            .bind("consumerGroup", claim.consumerGroup)
            .bind("claimToken", claim.claimToken)
            .fetch().rowsUpdated().awaitSingle() > 0
        if (updated) {
            finishAttempt(claim, "SUCCEEDED", null, null, now)
        }
        return updated
    }

    @Transactional
    override suspend fun releaseForRetry(
        claim: StreamInboxClaim,
        errorType: String,
        errorMessage: String,
        now: Instant,
    ): Boolean {
        val updated = databaseClient.sql(
            """
            update stream_consumer_inbox
            set lease_expires_at = :now,
                last_error_type = :errorType,
                last_error = :lastError,
                updated_at = :now
            where event_id = :eventId
              and consumer_group = :consumerGroup
              and status = 'PROCESSING'
              and claim_token = :claimToken
            """.trimIndent(),
        )
            .bind("now", now.utcDateTime())
            .bind("errorType", errorType.take(255))
            .bind("lastError", errorMessage.take(1000))
            .bind("eventId", claim.eventId)
            .bind("consumerGroup", claim.consumerGroup)
            .bind("claimToken", claim.claimToken)
            .fetch().rowsUpdated().awaitSingle() > 0
        if (updated) {
            finishAttempt(claim, "RETRY_SCHEDULED", errorType, errorMessage, now)
        }
        return updated
    }

    @Transactional
    override suspend fun markFailed(
        claim: StreamInboxClaim,
        errorType: String,
        errorMessage: String,
        now: Instant,
    ): Boolean {
        val updated = databaseClient.sql(
            """
            update stream_consumer_inbox
            set status = 'FAILED',
                lease_expires_at = null,
                last_error_type = :errorType,
                last_error = :lastError,
                completed_at = :now,
                failed_at = :now,
                updated_at = :now
            where event_id = :eventId
              and consumer_group = :consumerGroup
              and status = 'PROCESSING'
              and claim_token = :claimToken
            """.trimIndent(),
        )
            .bind("now", now.utcDateTime())
            .bind("errorType", errorType.take(255))
            .bind("lastError", errorMessage.take(1000))
            .bind("eventId", claim.eventId)
            .bind("consumerGroup", claim.consumerGroup)
            .bind("claimToken", claim.claimToken)
            .fetch().rowsUpdated().awaitSingle() > 0
        if (updated) {
            finishAttempt(claim, "FAILED", errorType, errorMessage, now)
        }
        return updated
    }

    private suspend fun finishAttempt(
        claim: StreamInboxClaim,
        status: String,
        errorType: String?,
        errorMessage: String?,
        now: Instant,
    ) {
        var query = databaseClient.sql(
            """
            update stream_consumer_inbox_attempts
            set status = :status,
                error_type = :errorType,
                error_message = :errorMessage,
                finished_at = :now,
                updated_at = :now
            where event_id = :eventId
              and consumer_group = :consumerGroup
              and attempt = :attempt
              and claim_token = :claimToken
              and status = 'PROCESSING'
            """.trimIndent(),
        )
            .bind("status", status)
            .bind("now", now.utcDateTime())
            .bind("eventId", claim.eventId)
            .bind("consumerGroup", claim.consumerGroup)
            .bind("attempt", claim.attempt)
            .bind("claimToken", claim.claimToken)
        query = if (errorType == null) {
            query.bindNull("errorType", String::class.java)
        } else {
            query.bind("errorType", errorType.take(255))
        }
        query = if (errorMessage == null) {
            query.bindNull("errorMessage", String::class.java)
        } else {
            query.bind("errorMessage", errorMessage.take(1000))
        }
        check(query.fetch().rowsUpdated().awaitSingle() > 0) {
            "Stream Inbox attempt was not found for ${claim.eventId} attempt ${claim.attempt}."
        }
    }

    private fun Instant.utcDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
