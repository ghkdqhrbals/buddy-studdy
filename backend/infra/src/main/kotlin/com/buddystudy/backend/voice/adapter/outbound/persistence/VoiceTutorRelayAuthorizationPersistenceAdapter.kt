package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayAuthorizationPort
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class VoiceTutorRelayAuthorizationPersistenceAdapter(
    private val database: DatabaseClient,
) : VoiceTutorRelayAuthorizationPort {
    override suspend fun isAuthorized(
        userId: Long,
        deviceId: String,
        authSessionId: Long,
        now: Instant,
    ): Boolean = database.sql(
        """
        select count(*) as active_count
        from user_devices
        where id = :authSessionId
          and user_id = :userId
          and device_id = :deviceId
          and logged_out_at is null
          and revoked_at is null
          and (session_expires_at is null or session_expires_at > :now)
        """.trimIndent(),
    )
        .bind("authSessionId", authSessionId)
        .bind("userId", userId)
        .bind("deviceId", deviceId)
        .bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
        .map { row, _ -> (row.get("active_count") as Number).toLong() > 0 }
        .one()
        .awaitSingle()
}
