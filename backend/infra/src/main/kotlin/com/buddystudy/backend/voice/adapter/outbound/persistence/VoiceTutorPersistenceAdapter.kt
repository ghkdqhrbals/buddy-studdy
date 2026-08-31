package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import com.buddystudy.backend.voice.adapter.outbound.VoiceTutorExplorationJsonCodec
import com.buddystudy.backend.voice.application.model.ReserveVoiceTutorSessionResult
import com.buddystudy.backend.voice.application.model.ReservedVoiceTutorSession
import com.buddystudy.backend.voice.application.model.VoiceTutorGeneratedResult
import com.buddystudy.backend.voice.application.model.VoiceTutorQuotaSnapshot
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionCursor
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceStudyLearningRecordAppendPort
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceStudyLearningRecordAppendPort
import com.buddystudy.voice.domain.VoiceTutorResult
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import com.fasterxml.jackson.module.kotlin.readValue
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.min

@Repository
class VoiceTutorPersistenceAdapter(
    private val database: DatabaseClient,
    private val learningRecords: VoiceStudyLearningRecordAppendPort = UnavailableVoiceStudyLearningRecordAppendPort,
) : VoiceTutorPersistencePort {
    private val mapper = JsonMapperProvider.mapper

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun reconcileExpired(
        userId: Long,
        now: Instant,
        readyTimeoutSeconds: Long,
        heartbeatLeaseSeconds: Long,
    ): List<VoiceTutorSession> {
        if (lockUserCreatedAt(userId) == null) return emptyList()
        val active = activeSessionQuery(userId, lock = true) ?: return emptyList()
        val readyLeaseExpired = active.status == VoiceTutorSessionStatus.READY &&
            !active.createdAt.plusSeconds(readyTimeoutSeconds.coerceAtLeast(1)).isAfter(now)
        val relayLeaseExpired = active.status == VoiceTutorSessionStatus.ACTIVE &&
            active.relayHeartbeatAt?.plusSeconds(heartbeatLeaseSeconds.coerceAtLeast(5))?.isAfter(now) != true
        val endingLeaseExpired = active.status == VoiceTutorSessionStatus.ENDING &&
            !active.updatedAt.plusSeconds(readyTimeoutSeconds.coerceAtLeast(1)).isAfter(now)
        if (!active.hardEndsAt.isAfter(now) || readyLeaseExpired || relayLeaseExpired || endingLeaseExpired) {
            val reason = when {
                readyLeaseExpired -> "CONNECTION_TIMEOUT"
                relayLeaseExpired -> "RELAY_HEARTBEAT_TIMEOUT"
                endingLeaseExpired -> "USER_ENDED"
                else -> "TIME_LIMIT"
            }
            settleLocked(
                active,
                reason,
                failed = false,
                failureMessage = null,
                now = now,
                usageEndedAt = when {
                    relayLeaseExpired -> staleRelayUsageEnd(active, now)
                    endingLeaseExpired -> endingSessionUsageEnd(active, now)
                    else -> now
                },
            )
            return listOfNotNull(findSessionRow(userId, active.id, lock = false))
        }
        return emptyList()
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun quota(userId: Long, now: Instant): VoiceTutorQuotaSnapshot? {
        val createdAt = lockUserCreatedAt(userId) ?: return null
        return ensureQuota(userId, createdAt, now).toSnapshot()
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun reserve(
        userId: Long,
        studyId: Long,
        idempotencyKey: String,
        language: String,
        model: String,
        voice: String,
        maxSessionSeconds: Int,
        now: Instant,
        recordingConsentedAt: Instant?,
        recordingConsentVersion: String?,
    ): ReserveVoiceTutorSessionResult {
        val createdAt = lockUserCreatedAt(userId) ?: return ReserveVoiceTutorSessionResult.UserNotFound
        val quota = ensureQuota(userId, createdAt, now)
        val existing = sessionByIdempotency(userId, idempotencyKey)
        if (existing != null) {
            val sameImmutableRequest = existing.studyId == studyId &&
                existing.language == language &&
                existing.model == model &&
                existing.voice == voice &&
                (existing.recordingConsentedAt != null) == (recordingConsentedAt != null) &&
                existing.recordingConsentVersion == recordingConsentVersion
            if (!sameImmutableRequest) return ReserveVoiceTutorSessionResult.IdempotencyConflict
            return ReserveVoiceTutorSessionResult.Reserved(
                ReservedVoiceTutorSession(existing, quota.toSnapshot()),
            )
        }
        if (!quota.toSnapshot().planEligible) {
            return ReserveVoiceTutorSessionResult.NotEligible(quota.toSnapshot())
        }
        val active = activeSessionQuery(userId, lock = true)
        if (active != null) {
            return ReserveVoiceTutorSessionResult.ActiveSession(active, quota.toSnapshot())
        }
        val study = study(userId, studyId) ?: return ReserveVoiceTutorSessionResult.StudyNotFound
        val secondsUntilReset = ceilSeconds(Duration.between(now, quota.periodEndsAt))
        val reservationSeconds = min(
            quota.remainingSeconds,
            min(maxSessionSeconds.coerceAtLeast(1), secondsUntilReset),
        )
        if (reservationSeconds <= 0) return ReserveVoiceTutorSessionResult.Exhausted(quota.toSnapshot())

        val sessionId = UUID.randomUUID().toString()
        val hardEndsAt = minInstant(now.plusSeconds(reservationSeconds.toLong()), quota.periodEndsAt)
        val updated = database.sql(
            """
            update user_voice_quota
            set reserved_seconds = reserved_seconds + :reserved,
                version = version + 1,
                updated_at = :now
            where user_id = :userId
              and version = :version
              and remaining_seconds >= :reserved
            """.trimIndent(),
        ).bind("reserved", reservationSeconds)
            .bind("now", now.utc())
            .bind("userId", userId)
            .bind("version", quota.version)
            .fetch().rowsUpdated().awaitSingle()
        check(updated == 1L) { "Voice Tutor quota changed concurrently while reserving a session." }

        var insert = database.sql(
            """
            insert into voice_tutor_sessions (
                id, user_id, study_id, idempotency_key, provider_session_id,
                status, result_status, language, model, voice,
                topic_snapshot, difficulty_snapshot,
                period_started_at, period_ends_at, reserved_seconds, charged_seconds,
                max_session_seconds, hard_ends_at, recording_consented_at, recording_consent_version,
                created_at, updated_at
            ) values (
                :id, :userId, :studyId, :idempotencyKey, null,
                'READY', 'PENDING', :language, :model, :voice,
                :topic, :difficulty,
                :periodStartedAt, :periodEndsAt, :reservedSeconds, 0,
                :maxSessionSeconds, :hardEndsAt, :recordingConsentedAt, :recordingConsentVersion,
                :now, :now
            )
            """.trimIndent(),
        ).bind("id", sessionId)
            .bind("userId", userId)
            .bind("studyId", studyId)
            .bind("idempotencyKey", idempotencyKey)
            .bind("language", language)
            .bind("model", model)
            .bind("voice", voice)
            .bind("topic", study.topic)
            .bind("difficulty", study.difficulty)
            .bind("periodStartedAt", quota.periodStartedAt.utc())
            .bind("periodEndsAt", quota.periodEndsAt.utc())
            .bind("reservedSeconds", reservationSeconds)
            .bind("maxSessionSeconds", maxSessionSeconds)
            .bind("hardEndsAt", hardEndsAt.utc())
        insert = if (recordingConsentedAt == null) {
            insert.bindNull("recordingConsentedAt", LocalDateTime::class.java)
        } else {
            insert.bind("recordingConsentedAt", recordingConsentedAt.utc())
        }
        insert = if (recordingConsentVersion == null) {
            insert.bindNull("recordingConsentVersion", String::class.java)
        } else {
            insert.bind("recordingConsentVersion", recordingConsentVersion)
        }
        insert.bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()

        val session = findSessionRow(userId, sessionId, lock = false)
            ?: error("Reserved Voice Tutor session could not be loaded.")
        return ReserveVoiceTutorSessionResult.Reserved(
            ReservedVoiceTutorSession(
                session,
                quota.copy(reservedSeconds = quota.reservedSeconds + reservationSeconds, version = quota.version + 1).toSnapshot(),
            ),
        )
    }

    override suspend fun activeSession(userId: Long): VoiceTutorSession? = activeSessionQuery(userId, lock = false)

    override suspend fun findSession(userId: Long, sessionId: String): VoiceTutorSession? =
        findSessionRow(userId, sessionId, lock = false)

    override suspend fun sessions(
        userId: Long,
        limit: Int,
        cursor: VoiceTutorSessionCursor?,
    ): List<VoiceTutorSession> {
        var spec = database.sql(
            """
            select * from voice_tutor_sessions
            where user_id = :userId
              and (
                    :cursorCreatedAt is null
                    or created_at < :cursorCreatedAt
                    or (created_at = :cursorCreatedAt and id < :cursorSessionId)
                  )
            order by created_at desc, id desc
            limit :limit
            """.trimIndent(),
        ).bind("userId", userId).bind("limit", limit)
        spec = if (cursor == null) {
            spec.bindNull("cursorCreatedAt", LocalDateTime::class.java)
                .bindNull("cursorSessionId", String::class.java)
        } else {
            spec.bind("cursorCreatedAt", cursor.createdAt.utc()).bind("cursorSessionId", cursor.sessionId)
        }
        return spec.map { row, _ -> row.session() }.all().collectList().awaitSingle()
    }

    override suspend fun sessionsAwaitingResult(
        limit: Int,
        now: Instant,
        processingLeaseSeconds: Long,
    ): List<VoiceTutorSession> = database.sql(
        """
        select session.*
        from voice_tutor_sessions session
        left join voice_tutor_results result on result.session_id = session.id
        where session.finalized_at is not null
          and (
                session.status = 'COMPLETED'
                or (
                    session.status = 'FAILED'
                    and exists (
                        select 1 from voice_tutor_transcript_turns turn
                        where turn.session_id = session.id and char_length(trim(turn.transcript)) > 0
                    )
                )
              )
          and (
                (session.result_status = 'PENDING' and result.session_id is null)
                or (
                    session.result_status = 'PROCESSING'
                    and result.status = 'PROCESSING'
                    and result.updated_at <= :staleBefore
                )
                or (
                    session.status = 'FAILED'
                    and session.result_status = 'FAILED'
                    and result.status = 'FAILED'
                    and result.model is null
                    and session.failure_message is not null
                    and binary result.error_message = binary session.failure_message
                )
              )
        order by session.finalized_at, session.id
        limit :limit
        """.trimIndent(),
    ).bind("staleBefore", now.minusSeconds(processingLeaseSeconds.coerceAtLeast(30)).utc())
        .bind("limit", limit.coerceIn(1, 100))
        .map { row, _ -> row.session() }.all().collectList().awaitSingle()

    override suspend fun staleSessionUserIds(
        limit: Int,
        now: Instant,
        readyTimeoutSeconds: Long,
        heartbeatLeaseSeconds: Long,
    ): List<Long> = database.sql(
        """
        select user_id
        from voice_tutor_sessions
        where active_user_id is not null
          and (
                hard_ends_at <= :now
                or (status = 'READY' and created_at <= :readyStaleBefore)
                or (status = 'ACTIVE' and (relay_heartbeat_at is null or relay_heartbeat_at <= :heartbeatStaleBefore))
                or (status = 'ENDING' and updated_at <= :readyStaleBefore)
              )
        order by hard_ends_at, id
        limit :limit
        """.trimIndent(),
    ).bind("now", now.utc())
        .bind("readyStaleBefore", now.minusSeconds(readyTimeoutSeconds.coerceAtLeast(1)).utc())
        .bind("heartbeatStaleBefore", now.minusSeconds(heartbeatLeaseSeconds.coerceAtLeast(5)).utc())
        .bind("limit", limit.coerceIn(1, 500))
        .map { row, _ -> row.long("user_id") }.all().collectList().awaitSingle()

    override suspend fun terminalWebRtcSessionsAwaitingHangup(limit: Int): List<VoiceTutorSession> = database.sql(
        """
        select *
        from voice_tutor_sessions
        where status in ('COMPLETED', 'FAILED')
          and provider_session_id is not null
          and left(provider_session_id, 4) = 'rtc_'
        order by finalized_at, id
        limit :limit
        """.trimIndent(),
    ).bind("limit", limit.coerceIn(1, 500))
        .map { row, _ -> row.session() }.all().collectList().awaitSingle()

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun markActive(userId: Long, sessionId: String, now: Instant): VoiceTutorSession? {
        val updated = database.sql(
            """
            update voice_tutor_sessions
            set status = 'ACTIVE',
                relay_heartbeat_at = :now,
                updated_at = :now
            where id = :sessionId and user_id = :userId and status = 'READY'
              and hard_ends_at > :now
            """.trimIndent(),
        ).bind("now", now.utc()).bind("sessionId", sessionId).bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
        return if (updated == 1L) findSessionRow(userId, sessionId, lock = false) else null
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun requestEnd(userId: Long, sessionId: String, now: Instant): VoiceTutorSession? {
        database.sql(
            """
            update voice_tutor_sessions
            set status = 'ENDING', updated_at = :now
            where id = :sessionId and user_id = :userId and status = 'ACTIVE'
            """.trimIndent(),
        ).bind("now", now.utc()).bind("sessionId", sessionId).bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
        return findSessionRow(userId, sessionId, lock = false)
    }

    override suspend fun heartbeat(
        userId: Long,
        sessionId: String,
        now: Instant,
    ): VoiceTutorSessionStatus? {
        database.sql(
            """
            update voice_tutor_sessions
            set relay_heartbeat_at = :now, updated_at = :now
            where id = :sessionId and user_id = :userId and status = 'ACTIVE'
            """.trimIndent(),
        ).bind("now", now.utc()).bind("sessionId", sessionId).bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
        return findSessionRow(userId, sessionId, lock = false)?.status
    }

    override suspend fun addAcceptedAudioBytes(
        userId: Long,
        sessionId: String,
        bytes: Long,
    ): Boolean {
        if (bytes <= 0) return true
        return database.sql(
            """
            update voice_tutor_sessions
            set accepted_audio_bytes = least(
                    accepted_audio_bytes + :bytes,
                    cast(max_session_seconds as unsigned) * :bytesPerSecond
                )
            where id = :sessionId and user_id = :userId and status in ('ACTIVE', 'ENDING')
            """.trimIndent(),
        ).bind("bytes", bytes.coerceAtMost(480_000))
            .bind("bytesPerSecond", PCM_BYTES_PER_SECOND)
            .bind("sessionId", sessionId).bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle() == 1L
    }

    override suspend fun attachProviderSession(
        userId: Long,
        sessionId: String,
        providerSessionId: String,
        now: Instant,
    ): Boolean = database.sql(
        """
        update voice_tutor_sessions
        set provider_session_id = coalesce(provider_session_id, :providerSessionId),
            connected_at = coalesce(connected_at, :now),
            updated_at = :now
        where id = :sessionId and user_id = :userId and status = 'ACTIVE'
          and (provider_session_id is null or provider_session_id = :providerSessionId)
        """.trimIndent(),
    ).bind("providerSessionId", providerSessionId)
        .bind("now", now.utc()).bind("sessionId", sessionId).bind("userId", userId)
        .fetch().rowsUpdated().awaitSingle() == 1L

    override suspend fun clearWebRtcProviderSession(
        userId: Long,
        sessionId: String,
        providerSessionId: String,
        now: Instant,
    ): Boolean = database.sql(
        """
        update voice_tutor_sessions
        set provider_session_id = null,
            updated_at = :now
        where id = :sessionId
          and user_id = :userId
          and status in ('COMPLETED', 'FAILED')
          and provider_session_id = :providerSessionId
          and left(provider_session_id, 4) = 'rtc_'
        """.trimIndent(),
    ).bind("now", now.utc())
        .bind("sessionId", sessionId)
        .bind("userId", userId)
        .bind("providerSessionId", providerSessionId)
        .fetch().rowsUpdated().awaitSingle() == 1L

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun appendTranscript(
        userId: Long,
        sessionId: String,
        providerItemId: String,
        role: VoiceTutorTranscriptRole,
        transcript: String,
        occurredAt: Instant,
        maxSessionCharacters: Int,
        maxSessionTurns: Int,
    ): Boolean {
        val owned = database.sql(
            "select id from voice_tutor_sessions where id = :sessionId and user_id = :userId and status in ('ACTIVE', 'ENDING') for update",
        ).bind("sessionId", sessionId).bind("userId", userId)
            .map { _, _ -> true }.one().awaitSingleOrNull() ?: return false
        if (!owned) return false
        val capacity = database.sql(
            """
            select count(*) as turn_count,
                   coalesce(sum(char_length(transcript)), 0) as character_count
            from voice_tutor_transcript_turns
            where session_id = :sessionId
            """.trimIndent(),
        ).bind("sessionId", sessionId)
            .map { row, _ -> TranscriptCapacity(row.int("turn_count"), row.int("character_count")) }
            .one().awaitSingle()
        val boundedTranscript = boundedVoiceTutorTranscript(
            transcript,
            capacity,
            maxSessionCharacters.coerceIn(1, 1_000_000),
            maxSessionTurns.coerceIn(1, 2_000),
        ) ?: return false
        val sequence = database.sql(
            "select coalesce(max(sequence_number), 0) + 1 as next_sequence from voice_tutor_transcript_turns where session_id = :sessionId",
        ).bind("sessionId", sessionId)
            .map { row, _ -> row.long("next_sequence") }.one().awaitSingle()
        val inserted = database.sql(
            """
            insert ignore into voice_tutor_transcript_turns (
                session_id, provider_item_id, role, transcript, sequence_number, occurred_at, created_at
            ) values (
                :sessionId, :providerItemId, :role, :transcript, :sequenceNumber, :occurredAt, :occurredAt
            )
            """.trimIndent(),
        ).bind("sessionId", sessionId).bind("providerItemId", providerItemId)
            .bind("role", role.name).bind("transcript", boundedTranscript).bind("sequenceNumber", sequence)
            .bind("occurredAt", occurredAt.utc())
            .fetch().rowsUpdated().awaitSingle()
        return inserted == 1L
    }

    override suspend fun transcript(
        userId: Long,
        sessionId: String,
        maxCharacters: Int,
    ): List<VoiceTutorTranscriptTurn> {
        val turns = database.sql(
            """
            select turn.*
            from voice_tutor_transcript_turns turn
            join voice_tutor_sessions session on session.id = turn.session_id
            where turn.session_id = :sessionId and session.user_id = :userId
            order by turn.sequence_number, turn.id
            limit 2000
            """.trimIndent(),
        ).bind("sessionId", sessionId).bind("userId", userId)
            .map { row, _ -> row.turn() }.all().collectList().awaitSingle()
        var remaining = maxCharacters.coerceAtLeast(0)
        return turns.mapNotNull { turn ->
            if (remaining <= 0) return@mapNotNull null
            val content = turn.transcript.take(remaining)
            remaining -= content.length
            turn.copy(transcript = content)
        }
    }

    override suspend fun result(userId: Long, sessionId: String): VoiceTutorResult? = database.sql(
        """
        select result.*
        from voice_tutor_results result
        join voice_tutor_sessions session on session.id = result.session_id
        where result.session_id = :sessionId and session.user_id = :userId
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("userId", userId)
        .map { row, _ -> row.result() }.one().awaitSingleOrNull()

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun finalize(
        userId: Long,
        sessionId: String,
        reason: String,
        failed: Boolean,
        failureMessage: String?,
        now: Instant,
    ): VoiceTutorSession? {
        if (lockUserCreatedAt(userId) == null) return null
        val session = findSessionRow(userId, sessionId, lock = true) ?: return null
        if (session.finalizedAt == null) settleLocked(session, reason, failed, failureMessage, now)
        return findSessionRow(userId, sessionId, lock = false)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun beginResult(
        userId: Long,
        sessionId: String,
        promptVersion: String,
        now: Instant,
        processingLeaseSeconds: Long,
    ): Boolean {
        val session = findSessionRow(userId, sessionId, lock = true) ?: return false
        val existing = result(userId, sessionId)
        if (!voiceTutorSummaryCanBeClaimed(
                session,
                existing,
                hasUsableTranscript = session.status == VoiceTutorSessionStatus.FAILED && hasUsableTranscript(session.id),
                now = now,
                processingLeaseSeconds = processingLeaseSeconds,
            )
        ) return false
        if (existing == null) {
            database.sql(
                """
                insert into voice_tutor_results (
                    session_id, status, prompt_version, created_at, updated_at
                ) values (:sessionId, 'PROCESSING', :promptVersion, :now, :now)
                """.trimIndent(),
            ).bind("sessionId", sessionId).bind("promptVersion", promptVersion).bind("now", now.utc())
                .fetch().rowsUpdated().awaitSingle()
        } else {
            database.sql(
                """
                update voice_tutor_results
                set status = 'PROCESSING', error_message = null, prompt_version = :promptVersion, updated_at = :now
                where session_id = :sessionId and status in ('FAILED', 'PROCESSING')
                """.trimIndent(),
            ).bind("promptVersion", promptVersion).bind("now", now.utc()).bind("sessionId", sessionId)
                .fetch().rowsUpdated().awaitSingle()
        }
        database.sql(
            "update voice_tutor_sessions set result_status = 'PROCESSING', updated_at = :now where id = :sessionId and user_id = :userId",
        ).bind("now", now.utc()).bind("sessionId", sessionId).bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
        return true
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun completeResult(
        userId: Long,
        generated: VoiceTutorGeneratedResult,
        sessionId: String,
        now: Instant,
    ) {
        // Use the same owner/session lock order as claim and failure. A late
        // completion must not race a claim into overwriting a completed result.
        findSessionRow(userId, sessionId, lock = true) ?: return
        val updated = database.sql(
            """
            update voice_tutor_results result
            join voice_tutor_sessions session on session.id = result.session_id
            set result.status = 'COMPLETED',
                result.summary_markdown = :summary,
                result.strengths_json = :strengths,
                result.improvements_json = :improvements,
                result.next_steps_json = :nextSteps,
                result.explorations_json = :explorations,
                result.model = :model,
                result.prompt_version = :promptVersion,
                result.error_message = null,
                result.updated_at = :now
            where result.session_id = :sessionId and session.user_id = :userId
              and result.status = 'PROCESSING'
            """.trimIndent(),
        ).bind("summary", generated.summaryMarkdown)
            .bind("strengths", mapper.writeValueAsString(generated.strengths))
            .bind("improvements", mapper.writeValueAsString(generated.improvements))
            .bind("nextSteps", mapper.writeValueAsString(generated.nextSteps))
            .bind("explorations", VoiceTutorExplorationJsonCodec.encode(generated.explorations))
            .bind("model", generated.model).bind("promptVersion", generated.promptVersion)
            .bind("now", now.utc()).bind("sessionId", sessionId).bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
        if (updated == 1L) {
            database.sql(
                "update voice_tutor_sessions set result_status = 'COMPLETED', updated_at = :now where id = :sessionId and user_id = :userId",
            ).bind("now", now.utc()).bind("sessionId", sessionId).bind("userId", userId)
                .fetch().rowsUpdated().awaitSingle()
            learningRecords.appendCompletedSession(userId, sessionId, generated.explorations, now)
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun failResult(
        userId: Long,
        sessionId: String,
        promptVersion: String,
        error: String,
        now: Instant,
    ) {
        val owned = findSessionRow(userId, sessionId, lock = true) ?: return
        database.sql(
            """
            insert into voice_tutor_results (
                session_id, status, prompt_version, error_message, created_at, updated_at
            ) values (
                :sessionId, 'FAILED', :promptVersion, :error, :now, :now
            )
            on duplicate key update
                status = if(status = 'COMPLETED', status, 'FAILED'),
                error_message = if(status = 'COMPLETED', error_message, values(error_message)),
                prompt_version = if(status = 'COMPLETED', prompt_version, values(prompt_version)),
                updated_at = if(status = 'COMPLETED', updated_at, values(updated_at))
            """.trimIndent(),
        ).bind("sessionId", sessionId).bind("promptVersion", promptVersion).bind("error", error.take(1000))
            .bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
        if (owned.resultStatus != VoiceTutorResultStatus.COMPLETED) {
            database.sql(
                "update voice_tutor_sessions set result_status = 'FAILED', updated_at = :now where id = :sessionId and user_id = :userId",
            ).bind("now", now.utc()).bind("sessionId", sessionId).bind("userId", userId)
                .fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun ensureQuota(userId: Long, createdAt: Instant, now: Instant): QuotaRow {
        var quota = quotaRow(userId, lock = true)
        if (quota == null) {
            val plan = effectivePlan(userId, now)
            val period = MonthlyQuotaWindow.periodAt(createdAt, now)
            database.sql(
                """
                insert ignore into user_voice_quota (
                    user_id, tier_code, anchor_at, period_started_at, period_ends_at,
                    limit_override_seconds, base_seconds, used_seconds, reserved_seconds,
                    version, created_at, updated_at
                ) values (
                    :userId, :tierCode, :anchorAt, :periodStartedAt, :periodEndsAt,
                    null, :baseSeconds, 0, 0, 0, :now, :now
                )
                """.trimIndent(),
            ).bind("userId", userId).bind("tierCode", plan.tierCode).bind("anchorAt", createdAt.utc())
                .bind("periodStartedAt", period.startedAt.utc()).bind("periodEndsAt", period.resetAt.utc())
                .bind("baseSeconds", plan.monthlyVoiceSeconds).bind("now", now.utc())
                .fetch().rowsUpdated().awaitSingle()
            quota = quotaRow(userId, lock = true) ?: error("Voice Tutor quota could not be materialized.")
        }
        if ((!now.isBefore(quota.periodEndsAt) || now.isBefore(quota.periodStartedAt)) && quota.reservedSeconds == 0) {
            val period = MonthlyQuotaWindow.periodAt(quota.anchorAt, now)
            database.sql(
                """
                update user_voice_quota
                set period_started_at = :periodStartedAt, period_ends_at = :periodEndsAt,
                    used_seconds = 0, reserved_seconds = 0, version = version + 1, updated_at = :now
                where user_id = :userId
                """.trimIndent(),
            ).bind("periodStartedAt", period.startedAt.utc()).bind("periodEndsAt", period.resetAt.utc())
                .bind("now", now.utc()).bind("userId", userId).fetch().rowsUpdated().awaitSingle()
            quota = quota.copy(
                periodStartedAt = period.startedAt,
                periodEndsAt = period.resetAt,
                usedSeconds = 0,
                reservedSeconds = 0,
                version = quota.version + 1,
            )
        }
        val plan = effectivePlan(userId, now)
        val effectiveBaseSeconds = quota.limitOverrideSeconds ?: plan.monthlyVoiceSeconds
        if (quota.tierCode != plan.tierCode || quota.baseSeconds != effectiveBaseSeconds) {
            database.sql(
                """
                update user_voice_quota
                set tier_code = :tierCode, base_seconds = :baseSeconds,
                    version = version + 1, updated_at = :now
                where user_id = :userId
                """.trimIndent(),
            ).bind("tierCode", plan.tierCode).bind("baseSeconds", effectiveBaseSeconds)
                .bind("now", now.utc()).bind("userId", userId).fetch().rowsUpdated().awaitSingle()
            quota = quota.copy(
                tierCode = plan.tierCode,
                baseSeconds = effectiveBaseSeconds,
                version = quota.version + 1,
            )
        }
        return quota
    }

    private suspend fun settleLocked(
        session: VoiceTutorSession,
        reason: String,
        failed: Boolean,
        failureMessage: String?,
        now: Instant,
        usageEndedAt: Instant = now,
    ) {
        if (session.finalizedAt != null) return
        val effectiveEnd = minInstant(usageEndedAt, session.hardEndsAt)
        val charged = voiceTutorChargedSeconds(session, effectiveEnd)
        val resultStatus = voiceTutorResultStatusAfterSettlement(
            session.resultStatus,
            failed,
            hasUsableTranscript = failed && hasUsableTranscript(session.id),
        )
        val quota = quotaRow(session.userId, lock = true)
        if (quota != null && quota.periodStartedAt == session.periodStartedAt && quota.periodEndsAt == session.periodEndsAt) {
            database.sql(
                """
                update user_voice_quota
                set used_seconds = used_seconds + :charged,
                    reserved_seconds = greatest(0, cast(reserved_seconds as signed) - :reserved),
                    version = version + 1,
                    updated_at = :now
                where user_id = :userId
                """.trimIndent(),
            ).bind("charged", charged).bind("reserved", session.reservedSeconds)
                .bind("now", now.utc()).bind("userId", session.userId)
                .fetch().rowsUpdated().awaitSingle()
        }
        database.sql(
            """
            update voice_tutor_sessions
            set status = :status,
                result_status = :resultStatus,
                charged_seconds = :charged,
                ended_at = :endedAt,
                finalized_at = :now,
                finalization_key = :finalizationKey,
                end_reason = :reason,
                failure_code = case when :failed then 'REALTIME_RELAY_FAILED' else null end,
                failure_message = :failureMessage,
                updated_at = :now
            where id = :sessionId and user_id = :userId and finalized_at is null
            """.trimIndent(),
        ).bind("status", if (failed) "FAILED" else "COMPLETED")
            .bind("resultStatus", resultStatus.name)
            .bind("failed", failed).bind("charged", charged).bind("endedAt", effectiveEnd.utc())
            .bind("now", now.utc()).bind("finalizationKey", "voice-session:${session.id}:finalize")
            .bind("reason", reason.take(64))
            .let { spec ->
                if (failureMessage == null) spec.bindNull("failureMessage", String::class.java)
                else spec.bind("failureMessage", failureMessage.take(1000))
            }
            .bind("sessionId", session.id).bind("userId", session.userId)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun hasUsableTranscript(sessionId: String): Boolean = database.sql(
        """
        select id from voice_tutor_transcript_turns
        where session_id = :sessionId and char_length(trim(transcript)) > 0
        limit 1
        """.trimIndent(),
    ).bind("sessionId", sessionId).map { _, _ -> true }.one().awaitSingleOrNull() ?: false

    private suspend fun effectivePlan(userId: Long, at: Instant): VoicePlan = database.sql(
        """
        select candidates.tier_code, candidates.monthly_voice_seconds_limit
        from (
            select entitlement.tier_code, tier.monthly_voice_seconds_limit,
                   case entitlement.tier_code when 'TIER3' then 3 when 'TIER2' then 2 when 'TIER1' then 1 else 0 end tier_rank,
                   entitlement.projected_at changed_at
            from user_entitlement_projection entitlement
            join user_membership_tiers tier on tier.tier_code = entitlement.tier_code
            where entitlement.user_id = :userId
              and (entitlement.source = 'FREE' or entitlement.access_status = 'GRACE_PERIOD'
                   or (entitlement.access_status = 'ACTIVE' and (entitlement.expires_at is null or entitlement.expires_at > :at)))
            union all
            select membership.tier, tier.monthly_voice_seconds_limit,
                   case membership.tier when 'TIER3' then 3 when 'TIER2' then 2 when 'TIER1' then 1 else 0 end tier_rank,
                   membership.updated_at changed_at
            from user_memberships membership
            join user_membership_tiers tier on tier.tier_code = membership.tier
            where membership.user_id = :userId and membership.status = 'ACTIVE'
              and membership.started_at <= :at and (membership.expires_at is null or membership.expires_at > :at)
        ) candidates
        order by candidates.tier_rank desc, candidates.monthly_voice_seconds_limit desc, candidates.changed_at desc
        limit 1
        """.trimIndent(),
    ).bind("userId", userId).bind("at", at.utc())
        .map { row, _ -> VoicePlan(row.string("tier_code"), row.int("monthly_voice_seconds_limit")) }
        .one().awaitSingleOrNull() ?: database.sql(
        "select tier_code, monthly_voice_seconds_limit from user_membership_tiers where tier_code = 'TIER1'",
    ).map { row, _ -> VoicePlan(row.string("tier_code"), row.int("monthly_voice_seconds_limit")) }
        .one().awaitSingle()

    private suspend fun study(userId: Long, studyId: Long): StudySnapshot? = database.sql(
        "select topic, difficulty_level from studies where id = :studyId and user_id = :userId",
    ).bind("studyId", studyId).bind("userId", userId)
        .map { row, _ -> StudySnapshot(row.string("topic"), row.int("difficulty_level")) }
        .one().awaitSingleOrNull()

    private suspend fun lockUserCreatedAt(userId: Long): Instant? = database.sql(
        "select created_at from users where id = :userId and status = 'ACTIVE' for update",
    ).bind("userId", userId).map { row, _ -> row.instant("created_at") }.one().awaitSingleOrNull()

    private suspend fun quotaRow(userId: Long, lock: Boolean): QuotaRow? = database.sql(
        "select * from user_voice_quota where user_id = :userId${if (lock) " for update" else ""}",
    ).bind("userId", userId).map { row, _ -> row.quota() }.one().awaitSingleOrNull()

    private suspend fun sessionByIdempotency(userId: Long, key: String): VoiceTutorSession? = database.sql(
        "select * from voice_tutor_sessions where user_id = :userId and idempotency_key = :key",
    ).bind("userId", userId).bind("key", key).map { row, _ -> row.session() }.one().awaitSingleOrNull()

    private suspend fun activeSessionQuery(userId: Long, lock: Boolean): VoiceTutorSession? = database.sql(
        "select * from voice_tutor_sessions where active_user_id = :userId${if (lock) " for update" else ""}",
    ).bind("userId", userId).map { row, _ -> row.session() }.one().awaitSingleOrNull()

    private suspend fun findSessionRow(userId: Long, sessionId: String, lock: Boolean): VoiceTutorSession? = database.sql(
        "select * from voice_tutor_sessions where id = :sessionId and user_id = :userId${if (lock) " for update" else ""}",
    ).bind("sessionId", sessionId).bind("userId", userId)
        .map { row, _ -> row.session() }.one().awaitSingleOrNull()

    private fun Row.session() = VoiceTutorSession(
        id = string("id"),
        userId = long("user_id"),
        studyId = nullableLong("study_id"),
        idempotencyKey = string("idempotency_key"),
        providerSessionId = get("provider_session_id", String::class.java),
        status = VoiceTutorSessionStatus.valueOf(string("status")),
        resultStatus = VoiceTutorResultStatus.valueOf(string("result_status")),
        language = string("language"),
        model = string("model"),
        voice = string("voice"),
        topic = string("topic_snapshot"),
        difficulty = int("difficulty_snapshot"),
        periodStartedAt = instant("period_started_at"),
        periodEndsAt = instant("period_ends_at"),
        reservedSeconds = int("reserved_seconds"),
        chargedSeconds = int("charged_seconds"),
        maxSessionSeconds = int("max_session_seconds"),
        hardEndsAt = instant("hard_ends_at"),
        connectedAt = nullableInstant("connected_at"),
        relayHeartbeatAt = nullableInstant("relay_heartbeat_at"),
        acceptedAudioBytes = long("accepted_audio_bytes"),
        recordingConsentedAt = nullableInstant("recording_consented_at"),
        recordingConsentVersion = get("recording_consent_version", String::class.java),
        endedAt = nullableInstant("ended_at"),
        finalizedAt = nullableInstant("finalized_at"),
        endReason = get("end_reason", String::class.java),
        failureCode = get("failure_code", String::class.java),
        failureMessage = get("failure_message", String::class.java),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    private fun Row.turn() = VoiceTutorTranscriptTurn(
        id = long("id"),
        sessionId = string("session_id"),
        providerItemId = string("provider_item_id"),
        role = VoiceTutorTranscriptRole.valueOf(string("role")),
        transcript = string("transcript"),
        sequenceNumber = long("sequence_number"),
        occurredAt = instant("occurred_at"),
    )

    private fun Row.result() = VoiceTutorResult(
        sessionId = string("session_id"),
        status = VoiceTutorResultStatus.valueOf(string("status")),
        summaryMarkdown = get("summary_markdown", String::class.java),
        strengths = get("strengths_json", String::class.java)?.let { runCatching { mapper.readValue<List<String>>(it) }.getOrDefault(emptyList()) }.orEmpty(),
        improvements = get("improvements_json", String::class.java)?.let { runCatching { mapper.readValue<List<String>>(it) }.getOrDefault(emptyList()) }.orEmpty(),
        nextSteps = get("next_steps_json", String::class.java)?.let { runCatching { mapper.readValue<List<String>>(it) }.getOrDefault(emptyList()) }.orEmpty(),
        model = get("model", String::class.java),
        promptVersion = string("prompt_version"),
        errorMessage = get("error_message", String::class.java),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
        explorations = VoiceTutorExplorationJsonCodec.decode(get("explorations_json", String::class.java)),
    )

    private fun Row.quota() = QuotaRow(
        userId = long("user_id"),
        tierCode = string("tier_code"),
        anchorAt = instant("anchor_at"),
        periodStartedAt = instant("period_started_at"),
        periodEndsAt = instant("period_ends_at"),
        limitOverrideSeconds = nullableInt("limit_override_seconds"),
        baseSeconds = int("base_seconds"),
        usedSeconds = int("used_seconds"),
        reservedSeconds = int("reserved_seconds"),
        version = long("version"),
    )

    private fun Row.string(name: String) = get(name, String::class.java)!!
    private fun Row.int(name: String) = (get(name) as Number).toInt()
    private fun Row.nullableInt(name: String) = (get(name) as? Number)?.toInt()
    private fun Row.long(name: String) = (get(name) as Number).toLong()
    private fun Row.nullableLong(name: String) = (get(name) as? Number)?.toLong()
    private fun Row.instant(name: String) = get(name, LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC)
    private fun Row.nullableInstant(name: String) = get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
    private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun QuotaRow.toSnapshot() = VoiceTutorQuotaSnapshot(
        tierCode,
        periodStartedAt,
        periodEndsAt,
        baseSeconds,
        usedSeconds,
        reservedSeconds,
    )

    private fun ceilSeconds(duration: Duration): Int {
        val millis = duration.toMillis().coerceAtLeast(0)
        return ((millis + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun minInstant(first: Instant, second: Instant) = if (first.isBefore(second)) first else second

    private data class VoicePlan(val tierCode: String, val monthlyVoiceSeconds: Int)
    private data class StudySnapshot(val topic: String, val difficulty: Int)
    private data class QuotaRow(
        val userId: Long,
        val tierCode: String,
        val anchorAt: Instant,
        val periodStartedAt: Instant,
        val periodEndsAt: Instant,
        val limitOverrideSeconds: Int?,
        val baseSeconds: Int,
        val usedSeconds: Int,
        val reservedSeconds: Int,
        val version: Long,
    ) {
        val remainingSeconds: Int
            get() = (baseSeconds - usedSeconds - reservedSeconds).coerceAtLeast(0)
    }
}

internal fun voiceTutorResultStatusAfterSettlement(
    current: VoiceTutorResultStatus,
    failed: Boolean,
    hasUsableTranscript: Boolean,
): VoiceTutorResultStatus = if (failed && !hasUsableTranscript && current == VoiceTutorResultStatus.PENDING) {
    VoiceTutorResultStatus.FAILED
} else {
    current
}

internal fun voiceTutorSummaryCanBeClaimed(
    session: VoiceTutorSession,
    existing: VoiceTutorResult?,
    hasUsableTranscript: Boolean,
    now: Instant,
    processingLeaseSeconds: Long,
): Boolean {
    if (session.finalizedAt == null || session.resultStatus == VoiceTutorResultStatus.COMPLETED) return false
    if (session.status != VoiceTutorSessionStatus.COMPLETED &&
        (session.status != VoiceTutorSessionStatus.FAILED || !hasUsableTranscript)
    ) return false
    return when (existing?.status) {
        null -> session.resultStatus == VoiceTutorResultStatus.PENDING
        VoiceTutorResultStatus.PROCESSING -> session.resultStatus == VoiceTutorResultStatus.PROCESSING &&
            !existing.updatedAt.plusSeconds(processingLeaseSeconds.coerceAtLeast(30)).isAfter(now)
        // Older finalization copied a transport failure into an unattempted
        // learning result. Recover only that exact signature, never a genuine
        // failed summary observed by another scheduler before its claim.
        VoiceTutorResultStatus.FAILED -> session.status == VoiceTutorSessionStatus.FAILED &&
            session.resultStatus == VoiceTutorResultStatus.FAILED && existing.model == null &&
            session.failureMessage != null && existing.errorMessage == session.failureMessage
        VoiceTutorResultStatus.PENDING, VoiceTutorResultStatus.COMPLETED -> false
    }
}

internal fun staleRelayUsageEnd(session: VoiceTutorSession, recoveryAt: Instant): Instant = minOf(
    session.relayHeartbeatAt ?: session.connectedAt ?: session.createdAt,
    recoveryAt,
    session.hardEndsAt,
)

internal fun endingSessionUsageEnd(session: VoiceTutorSession, recoveryAt: Instant): Instant = minOf(
    session.updatedAt,
    recoveryAt,
    session.hardEndsAt,
)

internal fun voiceTutorChargedSeconds(session: VoiceTutorSession, effectiveEnd: Instant): Int {
    val wallClockSeconds = session.connectedAt?.let { connected ->
        val millis = Duration.between(connected, minOf(effectiveEnd, session.hardEndsAt)).toMillis().coerceAtLeast(0)
        ((millis + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } ?: 0
    val audioSeconds = ((session.acceptedAudioBytes + PCM_BYTES_PER_SECOND - 1) / PCM_BYTES_PER_SECOND)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    return maxOf(wallClockSeconds, audioSeconds)
        .coerceIn(0, session.reservedSeconds)
}

internal data class TranscriptCapacity(
    val turnCount: Int,
    val characterCount: Int,
)

internal fun boundedVoiceTutorTranscript(
    transcript: String,
    capacity: TranscriptCapacity,
    maxSessionCharacters: Int,
    maxSessionTurns: Int,
): String? {
    if (capacity.turnCount >= maxSessionTurns) return null
    val remainingCharacters = (maxSessionCharacters - capacity.characterCount).coerceAtLeast(0)
    if (remainingCharacters == 0) return null
    return transcript.take(remainingCharacters).takeIf(String::isNotEmpty)
}

internal const val PCM_BYTES_PER_SECOND = 48_000L
