package com.buddystudy.backend.voice.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingDownloadResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingRetentionResult
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingUploadResponse
import com.buddystudy.backend.voice.application.model.toResponse
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRecordingRetentionUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRecordingUseCase
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorRecordingStoragePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingStoragePort
import com.buddystudy.backend.voice.application.port.outbound.uploadSnapshot
import com.buddystudy.voice.domain.VoiceTutorRecording
import com.buddystudy.voice.domain.VoiceTutorRecordingStatus
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat

@Service
class VoiceTutorRecordingService(
    private val persistence: VoiceTutorRecordingPersistencePort,
    private val properties: BuddyStudyProperties,
    private val storage: VoiceTutorRecordingStoragePort = UnavailableVoiceTutorRecordingStoragePort,
    private val clock: Clock = Clock.systemUTC(),
) : VoiceTutorRecordingUseCase, VoiceTutorRecordingRetentionUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun initiateUpload(
        principal: Principal,
        sessionId: String,
        contentType: String,
        contentLength: Long,
        sha256: String,
        durationMilliseconds: Long?,
        durationSeconds: Long?,
    ): VoiceTutorRecordingUploadResponse {
        val userId = registeredUserId(principal)
        requireRecordingAvailable()
        val id = requiredSessionId(sessionId)
        val session = persistence.session(userId, id) ?: throw notFound()
        if (session.recordingConsentedAt == null) {
            throw ApiException(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.ACCOUNT_FORBIDDEN,
                "Explicit recording consent was not captured for this Voice Tutor session.",
            )
        }
        if (session.recordingConsentVersion != RECORDING_CONSENT_VERSION) {
            throw ApiException(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.ACCOUNT_FORBIDDEN,
                "The recording consent version is no longer valid.",
            )
        }
        if (session.status !in TERMINAL_SESSION_STATES) {
            throw conflict("Voice Tutor recording upload is available only after the session ends.")
        }
        val sessionEndedAt = session.endedAt
            ?: throw conflict("Voice Tutor recording upload requires a finalized session end time.")
        val meteredDurationSeconds = minOf(session.durationSeconds, session.maxSessionSeconds).toLong()
        if (meteredDurationSeconds <= 0) {
            throw conflict("Voice Tutor recording upload requires a metered call.")
        }
        val maximumDuration = meteredDurationSeconds * 1_000L + DURATION_TOLERANCE_MILLISECONDS

        val normalizedContentType = contentType.trim().lowercase()
        if (normalizedContentType != RECORDING_CONTENT_TYPE) {
            throw validation("Voice Tutor recording content type must be audio/mp4.")
        }
        // A grant is paid-call storage, not an arbitrary file-upload capability.
        // Bound it by server-metered time as well as the configured global cap;
        // leave ample room for the mono AAC mix and M4A container overhead.
        val meteredMaximumBytes = ((maximumDuration + 999L) / 1_000L) * MAX_AUDIO_BYTES_PER_SECOND +
            MAX_CONTAINER_OVERHEAD_BYTES
        val maximumBytes = minOf(
            properties.voiceTutor.recordingMaxBytes.coerceIn(1, MAX_RECORDING_BYTES),
            meteredMaximumBytes,
        )
        if (contentLength !in 1..maximumBytes) {
            throw validation("Voice Tutor recording size exceeds the configured limit.")
        }
        val normalizedChecksum = sha256.trim().lowercase()
        if (!SHA256_HEX.matches(normalizedChecksum)) {
            throw validation("Voice Tutor recording checksum must be a SHA-256 hex digest.")
        }
        val normalizedDurationMilliseconds = recordingDurationMilliseconds(
            durationMilliseconds = durationMilliseconds,
            durationSeconds = durationSeconds,
            fallbackDurationSeconds = session.durationSeconds,
        )
        if (normalizedDurationMilliseconds !in 1..maximumDuration) {
            throw validation("Voice Tutor recording duration exceeds the metered session duration.")
        }

        val initialRetentionDeadline = databaseInstant(sessionEndedAt)
            .plus(Duration.ofDays(configuredRetentionDays()))
        var expected = persistence.recording(userId, id)
        repeat(MAX_UPLOAD_CAS_ATTEMPTS) {
            val now = databaseInstant(clock.instant())
            expected?.let { current ->
                if (current.status != VoiceTutorRecordingStatus.PENDING) {
                    throw conflict("Voice Tutor recording upload is already finalized.")
                }
                if (!current.matchesUploadContract(
                        contentType = normalizedContentType,
                        expectedBytes = contentLength,
                        sha256Hex = normalizedChecksum,
                        durationMilliseconds = normalizedDurationMilliseconds,
                        consentedAt = session.recordingConsentedAt,
                        consentVersion = session.recordingConsentVersion,
                    )
                ) {
                    throw conflict("A different Voice Tutor recording upload contract is already pending.")
                }
            }

            // Retention is an absolute call-end deadline. Retrying the same
            // pending upload may rotate its short grant, but can never extend
            // the lifetime of the consented recording.
            val retainedUntil = expected?.retainedUntil ?: initialRetentionDeadline
            val latestSafeUploadExpiry = retainedUntil.minusSeconds(UPLOAD_RETENTION_GUARD_SECONDS)
            if (!latestSafeUploadExpiry.isAfter(now.plusSeconds(MIN_UPLOAD_SIGNATURE_SECONDS))) {
                throw conflict("Voice Tutor recording upload retention has expired.")
            }
            val uploadExpiresAt = minOf(
                now.plusSeconds(configuredPresignSeconds()),
                latestSafeUploadExpiry,
            )
            val grant = storage.presignUpload(
                userId = userId,
                sessionId = id,
                contentType = normalizedContentType,
                byteSize = contentLength,
                sha256Hex = normalizedChecksum,
                expiresAt = uploadExpiresAt,
            )
            if (expected != null && grant.objectKey != expected.objectKey) {
                throw conflict("Voice Tutor recording storage contract changed while renewing the upload.")
            }
            val candidate = VoiceTutorRecording(
                sessionId = id,
                userId = userId,
                objectKey = grant.objectKey,
                status = VoiceTutorRecordingStatus.PENDING,
                contentType = normalizedContentType,
                expectedBytes = contentLength,
                actualBytes = null,
                sha256Hex = normalizedChecksum,
                durationMilliseconds = normalizedDurationMilliseconds,
                consentedAt = session.recordingConsentedAt,
                consentVersion = session.recordingConsentVersion,
                uploadExpiresAt = grant.expiresAt,
                retainedUntil = retainedUntil,
                completedAt = null,
                deletedAt = null,
                failureMessage = null,
                createdAt = expected?.createdAt ?: now,
                updatedAt = nextMutationInstant(now, expected?.updatedAt),
            )
            val saved = persistence.savePending(candidate, expected?.uploadSnapshot())
            if (saved != null) {
                if (saved.status != VoiceTutorRecordingStatus.PENDING || saved.uploadSnapshot() != candidate.uploadSnapshot()) {
                    throw conflict("Voice Tutor recording upload changed concurrently.")
                }
                return VoiceTutorRecordingUploadResponse(
                    uploadUrl = grant.uploadUrl,
                    headers = grant.requiredHeaders,
                    recordingId = id,
                    expiresAt = grant.expiresAt,
                    recording = saved.toResponse(recordingAvailable(), configuredRetentionDays().toInt(), now),
                )
            }

            val latest = persistence.recording(userId, id) ?: return@repeat
            if (latest.status != VoiceTutorRecordingStatus.PENDING ||
                !latest.matchesUploadContract(
                    contentType = normalizedContentType,
                    expectedBytes = contentLength,
                    sha256Hex = normalizedChecksum,
                    durationMilliseconds = normalizedDurationMilliseconds,
                    consentedAt = session.recordingConsentedAt,
                    consentVersion = session.recordingConsentVersion,
                )
            ) {
                throw conflict("Voice Tutor recording upload changed concurrently.")
            }
            expected = latest
        }
        throw conflict("Voice Tutor recording upload changed concurrently; retry the request.")
    }

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun completeUpload(
        principal: Principal,
        sessionId: String,
        recordingId: String,
    ): VoiceTutorRecordingResponse {
        val userId = registeredUserId(principal)
        val id = requiredSessionId(sessionId)
        if (requiredSessionId(recordingId) != id) throw notFound()
        val recording = persistence.recording(userId, id) ?: throw notFound()
        val now = databaseInstant(clock.instant())
        if (recording.status == VoiceTutorRecordingStatus.AVAILABLE) {
            return recording.toResponse(recordingAvailable(), configuredRetentionDays().toInt(), now)
        }
        if (recording.status != VoiceTutorRecordingStatus.PENDING) {
            throw conflict("Voice Tutor recording upload is not pending.")
        }
        val actual = storage.inspect(recording.objectKey)
            ?: throw conflict("Voice Tutor recording upload has not reached private storage.")
        val expectedChecksum = sha256HexToBase64(recording.sha256Hex)
        val valid = actual.contentType.equals(recording.contentType, ignoreCase = true) &&
            actual.byteSize == recording.expectedBytes &&
            actual.checksumSha256Base64 == expectedChecksum
        if (!valid) {
            val failed = persistence.markFailed(
                userId = userId,
                sessionId = id,
                expected = recording.uploadSnapshot(),
                failureMessage = "Stored recording metadata did not match the signed upload contract.",
                now = nextMutationInstant(now, recording.updatedAt),
            ) ?: throw conflict("Voice Tutor recording upload changed while integrity validation was running.")
            storage.delete(failed.objectKey)
            throw validation("Voice Tutor recording failed integrity validation.")
        }
        val completed = markAvailableWithRetry(userId, id, recording, actual.byteSize, now)
        return completed.toResponse(recordingAvailable(), configuredRetentionDays().toInt(), now)
    }

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun download(principal: Principal, sessionId: String): VoiceTutorRecordingDownloadResponse {
        val userId = registeredUserId(principal)
        val id = requiredSessionId(sessionId)
        val recording = persistence.recording(userId, id) ?: throw notFound()
        if (recording.status != VoiceTutorRecordingStatus.AVAILABLE) throw notFound()
        val now = databaseInstant(clock.instant())
        val latestSafeExpiry = recording.retainedUntil.minusSeconds(DOWNLOAD_RETENTION_GUARD_SECONDS)
        if (!latestSafeExpiry.isAfter(now.plusSeconds(MIN_DOWNLOAD_SIGNATURE_SECONDS))) {
            val deleted = markDeletedWithRetry(userId, id, recording, now)
            storage.delete(deleted.objectKey)
            throw notFound()
        }
        val expiresAt = minOf(now.plusSeconds(configuredPresignSeconds()), latestSafeExpiry)
        val grant = storage.presignDownload(recording.objectKey, id, expiresAt)
        return VoiceTutorRecordingDownloadResponse(
            url = grant.downloadUrl,
            expiresAt = grant.expiresAt,
            recording = recording.toResponse(recordingAvailable(), configuredRetentionDays().toInt(), now),
        )
    }

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun delete(principal: Principal, sessionId: String) {
        val userId = registeredUserId(principal)
        val id = requiredSessionId(sessionId)
        persistence.session(userId, id) ?: throw notFound()
        val recording = persistence.recording(userId, id) ?: return
        val deleted = markDeletedWithRetry(userId, id, recording, databaseInstant(clock.instant()))
        // A previously authorized PUT can recreate the key after an earlier DELETE.
        // Repeat the physical all-version delete on every idempotent owner request.
        storage.delete(deleted.objectKey)
    }

    override suspend fun cleanupExpired(): VoiceTutorRecordingRetentionResult {
        val batchSize = properties.voiceTutor.recordingRetentionBatchSize.coerceIn(1, MAX_RETENTION_BATCH_SIZE)
        val maxRows = properties.voiceTutor.recordingRetentionMaxRowsPerRun.coerceIn(1, MAX_RETENTION_ROWS_PER_RUN)
        val completedPrefixes = 0
        var attemptedPrefixes = 0
        var deleted = 0
        var attempted = 0
        val attemptedSessionIds = mutableSetOf<String>()

        val prefixBatch = persistence.pendingPrefixCleanups(clock.instant(), minOf(batchSize, maxRows))
        prefixBatch.forEach { cleanup ->
            attemptedPrefixes += 1
            val attemptedAt = databaseInstant(clock.instant())
            try {
                storage.deleteAll(cleanup.userId)
                persistence.deferPrefixCleanup(
                    userId = cleanup.userId,
                    expectedCleanupUntil = cleanup.cleanupUntil,
                    attemptedAt = attemptedAt,
                    nextAttemptAt = prefixCleanupNextAttemptAt(cleanup.cleanupUntil, attemptedAt),
                    failureMessage = null,
                )
            } catch (error: Exception) {
                runCatching {
                    persistence.deferPrefixCleanup(
                        userId = cleanup.userId,
                        expectedCleanupUntil = cleanup.cleanupUntil,
                        attemptedAt = attemptedAt,
                        nextAttemptAt = prefixCleanupNextAttemptAt(cleanup.cleanupUntil, attemptedAt),
                        failureMessage = error.javaClass.simpleName,
                    )
                }.onFailure { persistenceError ->
                    log.error(
                        "voice_tutor_recording_prefix_cleanup_retry_persist_failed userId={} errorType={}",
                        cleanup.userId,
                        persistenceError.javaClass.simpleName,
                    )
                }
                log.warn(
                    "voice_tutor_recording_prefix_cleanup_failed userId={} errorType={}",
                    cleanup.userId,
                    error.javaClass.simpleName,
                )
            }
        }

        while (attempted < maxRows) {
            val batch = persistence.expired(
                now = clock.instant(),
                uploadCompletionSafetySeconds = configuredUploadCompletionSafetySeconds(),
                includeOrdinaryRetention = properties.voiceTutor.recordingRetentionEnabled,
                limit = minOf(batchSize, maxRows - attempted),
            )
            if (batch.isEmpty()) break
            val unattempted = batch.filter { attemptedSessionIds.add(it.sessionId) }
            if (unattempted.isEmpty()) break
            unattempted.forEach { recording ->
                attempted += 1
                try {
                    val deletedAt = nextMutationInstant(databaseInstant(clock.instant()), recording.updatedAt)
                    val claimed = persistence.markExpiredDeleted(recording, deletedAt) ?: return@forEach
                    storage.delete(claimed.objectKey)
                    deleted += 1
                } catch (error: Exception) {
                    log.warn(
                        "voice_tutor_recording_retention_delete_failed sessionId={} errorType={}",
                        recording.sessionId,
                        error.javaClass.simpleName,
                    )
                }
            }
            if (batch.size < batchSize) break
        }
        return VoiceTutorRecordingRetentionResult(
            deletedRecordings = deleted,
            attemptedRecordings = attempted,
            capped = attempted >= maxRows || attemptedPrefixes >= maxRows,
            completedPrefixCleanups = completedPrefixes,
            attemptedPrefixCleanups = attemptedPrefixes,
        )
    }

    private suspend fun markAvailableWithRetry(
        userId: Long,
        sessionId: String,
        observed: VoiceTutorRecording,
        actualBytes: Long,
        now: Instant,
    ): VoiceTutorRecording {
        var expected = observed
        repeat(MAX_UPLOAD_CAS_ATTEMPTS) {
            val completed = persistence.markAvailable(
                userId = userId,
                sessionId = sessionId,
                expected = expected.uploadSnapshot(),
                actualBytes = actualBytes,
                completedAt = nextMutationInstant(now, expected.updatedAt),
            )
            if (completed != null) return completed

            val latest = persistence.recording(userId, sessionId) ?: throw notFound()
            if (latest.status == VoiceTutorRecordingStatus.AVAILABLE &&
                latest.sameUploadContract(observed) &&
                latest.actualBytes == actualBytes
            ) {
                return latest
            }
            if (latest.status != VoiceTutorRecordingStatus.PENDING || !latest.sameUploadContract(observed)) {
                throw conflict("Voice Tutor recording upload changed while completion was running.")
            }
            expected = latest
        }
        throw conflict("Voice Tutor recording upload completion was superseded; retry the request.")
    }

    private suspend fun markDeletedWithRetry(
        userId: Long,
        sessionId: String,
        observed: VoiceTutorRecording,
        now: Instant,
    ): VoiceTutorRecording {
        var expected = observed
        repeat(MAX_UPLOAD_CAS_ATTEMPTS) {
            val deleted = persistence.markDeleted(
                userId = userId,
                sessionId = sessionId,
                expected = expected,
                deletedAt = nextMutationInstant(now, expected.updatedAt),
            )
            if (deleted != null) return deleted
            expected = persistence.recording(userId, sessionId) ?: throw notFound()
        }
        throw conflict("Voice Tutor recording deletion changed concurrently; retry the request.")
    }

    private fun VoiceTutorRecording.matchesUploadContract(
        contentType: String,
        expectedBytes: Long,
        sha256Hex: String,
        durationMilliseconds: Long,
        consentedAt: Instant?,
        consentVersion: String?,
    ): Boolean = this.contentType == contentType &&
        this.expectedBytes == expectedBytes &&
        this.sha256Hex == sha256Hex &&
        this.durationMilliseconds == durationMilliseconds &&
        this.consentedAt == consentedAt &&
        this.consentVersion == consentVersion

    private fun VoiceTutorRecording.sameUploadContract(other: VoiceTutorRecording): Boolean =
        sessionId == other.sessionId &&
            userId == other.userId &&
            objectKey == other.objectKey &&
            matchesUploadContract(
                contentType = other.contentType,
                expectedBytes = other.expectedBytes,
                sha256Hex = other.sha256Hex,
                durationMilliseconds = other.durationMilliseconds,
                consentedAt = other.consentedAt,
                consentVersion = other.consentVersion,
            )

    private fun prefixCleanupNextAttemptAt(cleanupUntil: Instant, attemptedAt: Instant): Instant =
        if (attemptedAt.isBefore(cleanupUntil)) {
            minOf(cleanupUntil, attemptedAt.plusSeconds(PREFIX_CLEANUP_RETRY_SECONDS))
        } else {
            attemptedAt.plusSeconds(PREFIX_CLEANUP_LONG_TERM_RETRY_SECONDS)
        }

    private fun databaseInstant(value: Instant): Instant = value.truncatedTo(ChronoUnit.MICROS)

    private fun nextMutationInstant(now: Instant, previous: Instant?): Instant {
        val normalizedNow = databaseInstant(now)
        return if (previous == null || normalizedNow.isAfter(previous)) {
            normalizedNow
        } else {
            databaseInstant(previous).plus(1, ChronoUnit.MICROS)
        }
    }

    private fun registeredUserId(principal: Principal): Long {
        if (principal.anonymous) {
            throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ACCOUNT_FORBIDDEN, "A registered account is required for Voice Tutor.")
        }
        return principal.userId
    }

    private fun requireRecordingAvailable() {
        if (!properties.voiceTutor.recordingEnabled || properties.voiceTutor.recordingBucket.isBlank()) {
            throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE,
                "Voice Tutor recording is temporarily unavailable.",
            )
        }
    }

    private fun configuredRetentionDays(): Long = properties.voiceTutor.recordingRetentionDays.coerceIn(1, 365)
    private fun configuredPresignSeconds(): Long = properties.voiceTutor.recordingPresignSeconds.coerceIn(30, 900)
    private fun configuredUploadCompletionSafetySeconds(): Long =
        properties.voiceTutor.recordingUploadCompletionSafetySeconds.coerceIn(
            MIN_UPLOAD_COMPLETION_SAFETY_SECONDS,
            MAX_UPLOAD_COMPLETION_SAFETY_SECONDS,
        )

    private fun recordingAvailable(): Boolean = properties.voiceTutor.recordingEnabled &&
        properties.voiceTutor.recordingBucket.isNotBlank()

    private fun recordingDurationMilliseconds(
        durationMilliseconds: Long?,
        durationSeconds: Long?,
        fallbackDurationSeconds: Int,
    ): Long {
        if (durationMilliseconds != null && durationSeconds != null) {
            val secondsAsMilliseconds = safeMilliseconds(durationSeconds)
            if (kotlin.math.abs(durationMilliseconds - secondsAsMilliseconds) > 1_000L) {
                throw validation("Voice Tutor recording duration fields do not match.")
            }
        }
        return durationMilliseconds ?: durationSeconds?.let(::safeMilliseconds)
            ?: safeMilliseconds(fallbackDurationSeconds.toLong())
    }

    private fun safeMilliseconds(seconds: Long): Long = runCatching { Math.multiplyExact(seconds, 1_000L) }
        .getOrElse { throw validation("Voice Tutor recording duration is invalid.") }

    private fun requiredSessionId(value: String): String = value.trim().takeIf { SESSION_ID.matches(it) }
        ?: throw validation("Voice Tutor session id is invalid.")

    private fun validation(message: String) = ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)
    private fun conflict(message: String) = ApiException(HttpStatus.CONFLICT, ApiErrorCode.VOICE_TUTOR_SESSION_CONFLICT, message)
    private fun notFound() = ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Voice Tutor recording was not found.")

    private fun sha256HexToBase64(value: String): String =
        Base64.getEncoder().encodeToString(HexFormat.of().parseHex(value))

    private companion object {
        const val RECORDING_CONTENT_TYPE = "audio/mp4"
        const val MAX_RECORDING_BYTES = 536_870_912L
        const val MAX_AUDIO_BYTES_PER_SECOND = 32_768L
        const val MAX_CONTAINER_OVERHEAD_BYTES = 262_144L
        const val DURATION_TOLERANCE_MILLISECONDS = 5_000L
        const val MAX_RETENTION_BATCH_SIZE = 1_000
        const val MAX_RETENTION_ROWS_PER_RUN = 10_000
        const val PREFIX_CLEANUP_RETRY_SECONDS = 60L
        const val PREFIX_CLEANUP_LONG_TERM_RETRY_SECONDS = 86_400L
        const val MIN_UPLOAD_COMPLETION_SAFETY_SECONDS = 60L
        const val MAX_UPLOAD_COMPLETION_SAFETY_SECONDS = 3_600L
        const val MAX_UPLOAD_CAS_ATTEMPTS = 3
        const val MIN_UPLOAD_SIGNATURE_SECONDS = 1L
        const val UPLOAD_RETENTION_GUARD_SECONDS = 1L
        const val MIN_DOWNLOAD_SIGNATURE_SECONDS = 1L
        const val DOWNLOAD_RETENTION_GUARD_SECONDS = 1L
        const val RECORDING_CONSENT_VERSION = "voice-recording-v1"
        val TERMINAL_SESSION_STATES = setOf(VoiceTutorSessionStatus.COMPLETED, VoiceTutorSessionStatus.FAILED)
        val SESSION_ID = Regex("^[0-9a-fA-F-]{36}$")
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}
