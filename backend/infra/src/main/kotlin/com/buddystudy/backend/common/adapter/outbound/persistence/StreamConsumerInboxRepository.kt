package com.buddystudy.backend.common.adapter.outbound.persistence

import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class StreamConsumerInboxRepository(
    private val databaseClient: DatabaseClient,
) : StreamInboxPort {
    override suspend fun claim(
        eventId: String,
        consumerGroup: String,
        correlationId: String,
        leaseDuration: Duration,
        now: Instant,
    ): StreamInboxClaim? {
        val claimToken = UUID.randomUUID().toString()
        val leaseExpiresAt = now.plus(leaseDuration)
        databaseClient.sql(
            """
            insert into stream_consumer_inbox (
                event_id, consumer_group, correlation_id, status, claim_token, attempts,
                lease_expires_at, created_at, updated_at
            ) values (
                :eventId, :consumerGroup, :correlationId, 'PROCESSING', :claimToken, 1,
                :leaseExpiresAt, :now, :now
            )
            on duplicate key update
                claim_token = if(
                    status <> 'SUCCEEDED' and (lease_expires_at is null or lease_expires_at <= :now),
                    values(claim_token),
                    claim_token
                ),
                attempts = if(
                    status <> 'SUCCEEDED' and (lease_expires_at is null or lease_expires_at <= :now),
                    attempts + 1,
                    attempts
                ),
                lease_expires_at = if(
                    status <> 'SUCCEEDED' and (lease_expires_at is null or lease_expires_at <= :now),
                    values(lease_expires_at),
                    lease_expires_at
                ),
                last_error = if(
                    status <> 'SUCCEEDED' and (lease_expires_at is null or lease_expires_at <= :now),
                    null,
                    last_error
                ),
                updated_at = if(
                    status <> 'SUCCEEDED' and (lease_expires_at is null or lease_expires_at <= :now),
                    values(updated_at),
                    updated_at
                )
            """.trimIndent(),
        )
            .bind("eventId", eventId)
            .bind("consumerGroup", consumerGroup)
            .bind("correlationId", correlationId)
            .bind("claimToken", claimToken)
            .bind("leaseExpiresAt", leaseExpiresAt.utcDateTime())
            .bind("now", now.utcDateTime())
            .fetch().rowsUpdated().awaitSingle()

        return databaseClient.sql(
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
                )
            }
            .one()
            .awaitSingleOrNull()
    }

    override suspend fun markSucceeded(claim: StreamInboxClaim, now: Instant): Boolean =
        databaseClient.sql(
            """
            update stream_consumer_inbox
            set status = 'SUCCEEDED',
                lease_expires_at = null,
                completed_at = :now,
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

    override suspend fun releaseForRetry(claim: StreamInboxClaim, error: String, now: Instant): Boolean =
        databaseClient.sql(
            """
            update stream_consumer_inbox
            set lease_expires_at = :now,
                last_error = :lastError,
                updated_at = :now
            where event_id = :eventId
              and consumer_group = :consumerGroup
              and status = 'PROCESSING'
              and claim_token = :claimToken
            """.trimIndent(),
        )
            .bind("now", now.utcDateTime())
            .bind("lastError", error.take(1000))
            .bind("eventId", claim.eventId)
            .bind("consumerGroup", claim.consumerGroup)
            .bind("claimToken", claim.claimToken)
            .fetch().rowsUpdated().awaitSingle() > 0

    private fun Instant.utcDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
