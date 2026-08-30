package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorControlClaimPort
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class VoiceTutorControlClaimPersistenceAdapter(
    private val database: DatabaseClient,
) : VoiceTutorControlClaimPort {
    override suspend fun claim(
        userId: Long,
        sessionId: String,
        connectionId: String,
        now: Instant,
    ): Boolean = database.sql(
        """
        update voice_tutor_sessions
        set control_connection_id = :connectionId,
            control_claimed_at = :now,
            relay_heartbeat_at = :now,
            updated_at = :now
        where id = :sessionId
          and user_id = :userId
          and status = 'ACTIVE'
          and provider_session_id is not null
          and control_connection_id is null
          and hard_ends_at > :now
        """.trimIndent(),
    )
        .bind("connectionId", connectionId)
        .bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
        .bind("sessionId", sessionId)
        .bind("userId", userId)
        .fetch()
        .rowsUpdated()
        .map { it == 1L }
        .awaitSingle()
}
