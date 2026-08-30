package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingPrefixCleanup
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingSession
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingUploadSnapshot
import com.buddystudy.backend.voice.application.port.outbound.uploadSnapshot
import com.buddystudy.voice.domain.VoiceTutorRecording
import com.buddystudy.voice.domain.VoiceTutorRecordingStatus
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class VoiceTutorRecordingPersistenceAdapter(
    private val database: DatabaseClient,
) : VoiceTutorRecordingPersistencePort {
    override suspend fun latestUploadExpiry(userId: Long): Instant? = database.sql(
        """
        select upload_expires_at
        from voice_tutor_recordings
        where user_id = :userId
        order by upload_expires_at desc
        limit 1
        """.trimIndent(),
    ).bind("userId", userId)
        .map { row, _ -> row.instant("upload_expires_at") }
        .one().awaitSingleOrNull()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun schedulePrefixCleanup(userId: Long, cleanupUntil: Instant, now: Instant) {
        database.sql(
            """
            insert into voice_tutor_recording_prefix_cleanups (
                user_id, cleanup_until, next_attempt_at, last_attempted_at,
                attempt_count, last_failure_message, created_at, updated_at
            ) values (
                :userId, :cleanupUntil, :now, null,
                0, null, :now, :now
            ) on duplicate key update
                cleanup_until = greatest(cleanup_until, values(cleanup_until)),
                next_attempt_at = least(next_attempt_at, values(next_attempt_at)),
                updated_at = values(updated_at)
            """.trimIndent(),
        ).bind("userId", userId)
            .bind("cleanupUntil", cleanupUntil.utc())
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun pendingPrefixCleanups(now: Instant, limit: Int): List<VoiceTutorRecordingPrefixCleanup> = database.sql(
        """
        select user_id, cleanup_until
        from voice_tutor_recording_prefix_cleanups
        where next_attempt_at <= :now
        order by next_attempt_at, user_id
        limit :limit
        """.trimIndent(),
    ).bind("now", now.utc()).bind("limit", limit.coerceIn(1, 1_000))
        .map { row, _ -> VoiceTutorRecordingPrefixCleanup(row.long("user_id"), row.instant("cleanup_until")) }
        .all().collectList().awaitSingle()

    override suspend fun deferPrefixCleanup(
        userId: Long,
        expectedCleanupUntil: Instant,
        attemptedAt: Instant,
        nextAttemptAt: Instant,
        failureMessage: String?,
    ): Boolean {
        val statement = database.sql(
            """
            update voice_tutor_recording_prefix_cleanups
            set next_attempt_at = :nextAttemptAt,
                last_attempted_at = :attemptedAt,
                attempt_count = attempt_count + 1,
                last_failure_message = :failureMessage,
                updated_at = :attemptedAt
            where user_id = :userId and cleanup_until = :expectedCleanupUntil
            """.trimIndent(),
        ).bind("nextAttemptAt", nextAttemptAt.utc())
            .bind("attemptedAt", attemptedAt.utc())
            .bind("userId", userId)
            .bind("expectedCleanupUntil", expectedCleanupUntil.utc())
        val bound = failureMessage?.let { statement.bind("failureMessage", it.take(255)) }
            ?: statement.bindNull("failureMessage", String::class.java)
        return bound.fetch().rowsUpdated().awaitSingle() > 0
    }

    override suspend fun session(userId: Long, sessionId: String): VoiceTutorRecordingSession? = database.sql(
        """
        select id, user_id, status, max_session_seconds, charged_seconds, ended_at,
               recording_consented_at, recording_consent_version
        from voice_tutor_sessions
        where id = :sessionId and user_id = :userId
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("userId", userId)
        .map { row, _ ->
            VoiceTutorRecordingSession(
                sessionId = row.string("id"),
                userId = row.long("user_id"),
                status = VoiceTutorSessionStatus.valueOf(row.string("status")),
                maxSessionSeconds = row.int("max_session_seconds"),
                durationSeconds = row.int("charged_seconds"),
                endedAt = row.nullableInstant("ended_at"),
                recordingConsentedAt = row.nullableInstant("recording_consented_at"),
                recordingConsentVersion = row.get("recording_consent_version", String::class.java),
            )
        }.one().awaitSingleOrNull()

    override suspend fun recording(userId: Long, sessionId: String): VoiceTutorRecording? = database.sql(
        """
        select recording.*
        from voice_tutor_recordings recording
        join voice_tutor_sessions session on session.id = recording.session_id
        where recording.session_id = :sessionId and session.user_id = :userId
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("userId", userId)
        .map { row, _ -> row.recording() }.one().awaitSingleOrNull()

    @Transactional
    override suspend fun savePending(
        recording: VoiceTutorRecording,
        expected: VoiceTutorRecordingUploadSnapshot?,
    ): VoiceTutorRecording? {
        val rowsUpdated = if (expected == null) {
            database.sql(
                """
                insert into voice_tutor_recordings (
                    session_id, user_id, object_key, status, content_type,
                    expected_bytes, actual_bytes, sha256_hex, duration_milliseconds,
                    consented_at, consent_version, upload_expires_at, retained_until,
                    completed_at, deleted_at, failure_message, created_at, updated_at
                ) values (
                    :sessionId, :userId, :objectKey, 'PENDING', :contentType,
                    :expectedBytes, null, :sha256Hex, :durationMilliseconds,
                    :consentedAt, :consentVersion, :uploadExpiresAt, :retainedUntil,
                    null, null, null, :createdAt, :updatedAt
                ) on duplicate key update session_id = voice_tutor_recordings.session_id
                """.trimIndent(),
            ).bind("sessionId", recording.sessionId)
                .bind("userId", recording.userId)
                .bind("objectKey", recording.objectKey)
                .bind("contentType", recording.contentType)
                .bind("expectedBytes", recording.expectedBytes)
                .bind("sha256Hex", recording.sha256Hex)
                .bind("durationMilliseconds", recording.durationMilliseconds)
                .bind("consentedAt", recording.consentedAt.utc())
                .bind("consentVersion", recording.consentVersion)
                .bind("uploadExpiresAt", recording.uploadExpiresAt.utc())
                .bind("retainedUntil", recording.retainedUntil.utc())
                .bind("createdAt", recording.createdAt.utc())
                .bind("updatedAt", recording.updatedAt.utc())
                .fetch().rowsUpdated().awaitSingle()
        } else {
            database.sql(
                """
                update voice_tutor_recordings recording
                join voice_tutor_sessions session on session.id = recording.session_id
                set recording.upload_expires_at = :uploadExpiresAt,
                    recording.updated_at = :updatedAt
                where recording.session_id = :sessionId
                  and session.user_id = :userId
                  and $VOICE_TUTOR_RECORDING_PENDING_SNAPSHOT_PREDICATE
                """.trimIndent(),
            ).bind("uploadExpiresAt", recording.uploadExpiresAt.utc())
                .bind("updatedAt", recording.updatedAt.utc())
                .bind("sessionId", recording.sessionId)
                .bind("userId", recording.userId)
                .bindUploadSnapshot(expected)
                .fetch().rowsUpdated().awaitSingle()
        }
        if (rowsUpdated == 0L) return null
        return recording(recording.userId, recording.sessionId)
            ?.takeIf { it.status == VoiceTutorRecordingStatus.PENDING && it.uploadSnapshot() == recording.uploadSnapshot() }
    }

    @Transactional
    override suspend fun markAvailable(
        userId: Long,
        sessionId: String,
        expected: VoiceTutorRecordingUploadSnapshot,
        actualBytes: Long,
        completedAt: Instant,
    ): VoiceTutorRecording? {
        val rowsUpdated = database.sql(
            """
            update voice_tutor_recordings recording
            join voice_tutor_sessions session on session.id = recording.session_id
            set recording.status = 'AVAILABLE',
                recording.actual_bytes = :actualBytes,
                recording.completed_at = :completedAt,
                recording.failure_message = null,
                recording.updated_at = :completedAt
            where recording.session_id = :sessionId
              and session.user_id = :userId
              and $VOICE_TUTOR_RECORDING_PENDING_SNAPSHOT_PREDICATE
            """.trimIndent(),
        ).bind("actualBytes", actualBytes).bind("completedAt", completedAt.utc())
            .bind("sessionId", sessionId).bind("userId", userId)
            .bindUploadSnapshot(expected)
            .fetch().rowsUpdated().awaitSingle()
        if (rowsUpdated == 0L) return null
        return recording(userId, sessionId)
    }

    @Transactional
    override suspend fun markFailed(
        userId: Long,
        sessionId: String,
        expected: VoiceTutorRecordingUploadSnapshot,
        failureMessage: String,
        now: Instant,
    ): VoiceTutorRecording? {
        val rowsUpdated = database.sql(
            """
            update voice_tutor_recordings recording
            join voice_tutor_sessions session on session.id = recording.session_id
            set recording.status = 'FAILED',
                recording.failure_message = :failureMessage,
                recording.updated_at = :now
            where recording.session_id = :sessionId
              and session.user_id = :userId
              and $VOICE_TUTOR_RECORDING_PENDING_SNAPSHOT_PREDICATE
            """.trimIndent(),
        ).bind("failureMessage", failureMessage.take(255)).bind("now", now.utc())
            .bind("sessionId", sessionId).bind("userId", userId)
            .bindUploadSnapshot(expected)
            .fetch().rowsUpdated().awaitSingle()
        if (rowsUpdated == 0L) return null
        return recording(userId, sessionId)
    }

    @Transactional
    override suspend fun markDeleted(
        userId: Long,
        sessionId: String,
        expected: VoiceTutorRecording,
        deletedAt: Instant,
    ): VoiceTutorRecording? = markDeleted(userId, sessionId, expected, deletedAt, requireExpired = false)

    @Transactional
    override suspend fun markExpiredDeleted(
        expected: VoiceTutorRecording,
        deletedAt: Instant,
    ): VoiceTutorRecording? = markDeleted(
        userId = expected.userId,
        sessionId = expected.sessionId,
        expected = expected,
        deletedAt = deletedAt,
        requireExpired = true,
    )

    override suspend fun expired(
        now: Instant,
        uploadCompletionSafetySeconds: Long,
        includeOrdinaryRetention: Boolean,
        limit: Int,
    ): List<VoiceTutorRecording> = database.sql(
        """
        select * from voice_tutor_recordings
        where (
                :includeOrdinaryRetention = true
                and status in ('PENDING', 'AVAILABLE', 'FAILED')
                and retained_until <= :now
              ) or (
                $VOICE_TUTOR_RECORDING_FAILED_RETRY_PREDICATE
              ) or (
                status = 'DELETED'
                and $VOICE_TUTOR_RECORDING_DELETED_RETRY_PREDICATE
              )
        order by if(status = 'DELETED', upload_expires_at, retained_until), session_id
        limit :limit
        """.trimIndent(),
    ).bind("now", now.utc())
        .bind("includeOrdinaryRetention", includeOrdinaryRetention)
        .bind("retryBefore", now.minusSeconds(DELETED_RETRY_SECONDS).utc())
        .bind("longTermRetryBefore", now.minusSeconds(DELETED_LONG_TERM_RETRY_SECONDS).utc())
        .bind("safetySeconds", uploadCompletionSafetySeconds.coerceIn(60, 3_600))
        .bind("limit", limit.coerceIn(1, 1_000))
        .map { row, _ -> row.recording() }.all().collectList().awaitSingle()

    private fun Row.recording() = VoiceTutorRecording(
        sessionId = string("session_id"),
        userId = long("user_id"),
        objectKey = string("object_key"),
        status = VoiceTutorRecordingStatus.valueOf(string("status")),
        contentType = string("content_type"),
        expectedBytes = long("expected_bytes"),
        actualBytes = nullableLong("actual_bytes"),
        sha256Hex = string("sha256_hex"),
        durationMilliseconds = long("duration_milliseconds"),
        consentedAt = instant("consented_at"),
        consentVersion = string("consent_version"),
        uploadExpiresAt = instant("upload_expires_at"),
        retainedUntil = instant("retained_until"),
        completedAt = nullableInstant("completed_at"),
        deletedAt = nullableInstant("deleted_at"),
        failureMessage = get("failure_message", String::class.java),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    private fun Row.string(name: String) = get(name, String::class.java)!!
    private fun Row.int(name: String) = (get(name) as Number).toInt()
    private fun Row.long(name: String) = (get(name) as Number).toLong()
    private fun Row.nullableLong(name: String) = (get(name) as? Number)?.toLong()
    private fun Row.instant(name: String) = get(name, LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC)
    private fun Row.nullableInstant(name: String) = get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
    private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private suspend fun markDeleted(
        userId: Long,
        sessionId: String,
        expected: VoiceTutorRecording,
        deletedAt: Instant,
        requireExpired: Boolean,
    ): VoiceTutorRecording? {
        val expiryPredicate = if (requireExpired) {
            """
            and (
                (recording.status in ('PENDING', 'AVAILABLE') and recording.retained_until <= :deletedAt)
                or (
                    recording.status = 'FAILED'
                    and recording.updated_at <= timestampadd(SECOND, -$FAILED_DELETE_RETRY_SECONDS, :deletedAt)
                )
                or (recording.status = 'DELETED' and recording.upload_expires_at <= :deletedAt)
            )
            """.trimIndent()
        } else {
            ""
        }
        val rowsUpdated = database.sql(
            """
            update voice_tutor_recordings recording
            join voice_tutor_sessions session on session.id = recording.session_id
            set recording.status = 'DELETED',
                recording.deleted_at = :deletedAt,
                recording.failure_message = null,
                recording.updated_at = :deletedAt
            where recording.session_id = :sessionId
              and session.user_id = :userId
              and recording.status = :expectedStatus
              and $VOICE_TUTOR_RECORDING_UPLOAD_SNAPSHOT_PREDICATE
              $expiryPredicate
            """.trimIndent(),
        ).bind("deletedAt", deletedAt.utc())
            .bind("sessionId", sessionId)
            .bind("userId", userId)
            .bind("expectedStatus", expected.status.name)
            .bindUploadSnapshot(expected.uploadSnapshot())
            .fetch().rowsUpdated().awaitSingle()
        if (rowsUpdated == 0L) return null
        return recording(userId, sessionId)
    }

    private fun DatabaseClient.GenericExecuteSpec.bindUploadSnapshot(
        expected: VoiceTutorRecordingUploadSnapshot,
    ): DatabaseClient.GenericExecuteSpec = bind("expectedObjectKey", expected.objectKey)
        .bind("expectedContentType", expected.contentType)
        .bind("expectedBytesSnapshot", expected.expectedBytes)
        .bind("expectedSha256Hex", expected.sha256Hex)
        .bind("expectedDurationMilliseconds", expected.durationMilliseconds)
        .bind("expectedUploadExpiresAt", expected.uploadExpiresAt.utc())
        .bind("expectedUpdatedAt", expected.updatedAt.utc())

    private companion object {
        const val DELETED_RETRY_SECONDS = 60L
        const val DELETED_LONG_TERM_RETRY_SECONDS = 86_400L
        const val FAILED_DELETE_RETRY_SECONDS = 60L
    }
}

internal val VOICE_TUTOR_RECORDING_UPLOAD_SNAPSHOT_PREDICATE = """
    recording.object_key = :expectedObjectKey
    and recording.content_type = :expectedContentType
    and recording.expected_bytes = :expectedBytesSnapshot
    and recording.sha256_hex = :expectedSha256Hex
    and recording.duration_milliseconds = :expectedDurationMilliseconds
    and recording.upload_expires_at = :expectedUploadExpiresAt
    and recording.updated_at = :expectedUpdatedAt
""".trimIndent()

internal val VOICE_TUTOR_RECORDING_PENDING_SNAPSHOT_PREDICATE = """
    recording.status = 'PENDING'
    and $VOICE_TUTOR_RECORDING_UPLOAD_SNAPSHOT_PREDICATE
""".trimIndent()

internal val VOICE_TUTOR_RECORDING_DELETED_RETRY_PREDICATE = """
    upload_expires_at <= :now
    and (
        deleted_at is null
        or (
            :now <= timestampadd(SECOND, :safetySeconds, upload_expires_at)
            and deleted_at <= :retryBefore
        )
        or (
            :now > timestampadd(SECOND, :safetySeconds, upload_expires_at)
            and deleted_at <= :longTermRetryBefore
        )
    )
""".trimIndent()

internal val VOICE_TUTOR_RECORDING_FAILED_RETRY_PREDICATE = """
    status = 'FAILED'
    and updated_at <= :retryBefore
""".trimIndent()
