package com.buddystudy.backend.voice

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingDownloadGrant
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingObjectMetadata
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingPrefixCleanup
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingSession
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingStoragePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingUploadGrant
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingUploadSnapshot
import com.buddystudy.backend.voice.application.port.outbound.uploadSnapshot
import com.buddystudy.backend.voice.application.service.VoiceTutorRecordingService
import com.buddystudy.voice.domain.VoiceTutorRecording
import com.buddystudy.voice.domain.VoiceTutorRecordingStatus
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat

class VoiceTutorRecordingServiceTest {
    private val now = Instant.parse("2026-08-30T00:00:00Z")
    private val principal = Principal(7, "device-7", 70, anonymous = false)
    private val sessionId = "00000000-0000-4000-8000-000000000007"
    private val checksum = "ab".repeat(32)

    @Test
    fun `recording is default off and requires an explicit deployment opt in`() = runBlocking<Unit> {
        val persistence = FakePersistence(session())
        val failure = runCatching {
            service(persistence, properties = BuddyStudyProperties()).initiateUpload(
                principal, sessionId, "audio/mp4", 1_024, checksum, null, null,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
        assertThat(persistence.recording).isNull()
    }

    @Test
    fun `upload requires owner scoped session consent at the supported consent version`() = runBlocking<Unit> {
        val properties = properties()
        val noConsent = FakePersistence(session().copy(recordingConsentedAt = null, recordingConsentVersion = null))
        val wrongVersion = FakePersistence(session().copy(recordingConsentVersion = "voice-recording-v0"))
        val otherUser = principal.copy(userId = 8)

        val missingConsent = runCatching {
            service(noConsent, properties).initiateUpload(
                principal, sessionId, "audio/mp4", 1_024, checksum, null, null,
            )
        }.exceptionOrNull()
        val staleConsent = runCatching {
            service(wrongVersion, properties).initiateUpload(
                principal, sessionId, "audio/mp4", 1_024, checksum, null, null,
            )
        }.exceptionOrNull()
        val wrongOwner = runCatching {
            service(FakePersistence(session()), properties).initiateUpload(
                otherUser, sessionId, "audio/mp4", 1_024, checksum, null, null,
            )
        }.exceptionOrNull()

        assertThat((missingConsent as ApiException).code).isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        assertThat((staleConsent as ApiException).code).isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        assertThat((wrongOwner as ApiException).code).isEqualTo(ApiErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `mixed m4a upload is checksum bound and finalized without exposing its object key`() = runBlocking<Unit> {
        val persistence = FakePersistence(session())
        val storage = FakeStorage().apply {
            inspected = VoiceTutorRecordingObjectMetadata(
                contentType = "audio/mp4",
                byteSize = 1_024,
                checksumSha256Base64 = checksumBase64(checksum),
            )
        }
        val service = service(persistence, storage = storage)

        val upload = service.initiateUpload(
            principal = principal,
            sessionId = sessionId,
            contentType = "audio/mp4",
            contentLength = 1_024,
            sha256 = checksum,
            durationMilliseconds = null,
            durationSeconds = null,
        )
        val completed = service.completeUpload(principal, sessionId, upload.recordingId)

        assertThat(upload.uploadUrl).startsWith("https://storage.example.test/")
        assertThat(upload.headers).containsEntry("Content-Type", "audio/mp4")
        assertThat(upload.recordingId).isEqualTo(sessionId)
        assertThat(upload.toString()).doesNotContain(storage.objectKey)
        assertThat(persistence.recording?.durationMilliseconds).isEqualTo(60_000)
        assertThat(completed.available).isTrue()
        assertThat(completed.status).isEqualTo(VoiceTutorRecordingStatus.AVAILABLE)
        assertThat(completed.contentLength).isEqualTo(1_024)
        assertThat(storage.deletedKeys).isEmpty()
    }

    @Test
    fun `zero charge session cannot obtain recording storage without consuming voice quota`() = runBlocking<Unit> {
        val persistence = FakePersistence(session().copy(durationSeconds = 0))

        val failure = runCatching {
            service(persistence).initiateUpload(
                principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null,
            )
        }.exceptionOrNull()

        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_SESSION_CONFLICT)
        assertThat(persistence.recording).isNull()
    }

    @Test
    fun `one metered second cannot authorize a claimed hour or arbitrary large recording`() = runBlocking<Unit> {
        val persistence = FakePersistence(session().copy(durationSeconds = 1))
        val service = service(persistence)

        val fabricatedDuration = runCatching {
            service.initiateUpload(
                principal, sessionId, "audio/mp4", 1_024, checksum, 3_600_000, null,
            )
        }.exceptionOrNull()
        val oversizedContent = runCatching {
            service.initiateUpload(
                principal, sessionId, "audio/mp4", 1_048_576, checksum, 1_000, null,
            )
        }.exceptionOrNull()

        assertThat((fabricatedDuration as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThat((oversizedContent as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThat(persistence.recording).isNull()
    }

    @Test
    fun `metered recording duration permits the bounded five second export tolerance`() = runBlocking<Unit> {
        val persistence = FakePersistence(session().copy(durationSeconds = 1))

        service(persistence).initiateUpload(
            principal, sessionId, "audio/mp4", 32_768, checksum, 6_000, null,
        )

        assertThat(persistence.recording?.durationMilliseconds).isEqualTo(6_000)
        assertThat(persistence.recording?.expectedBytes).isEqualTo(32_768)
    }

    @Test
    fun `same pending contract can renew its grant but changed and failed contracts cannot`() = runBlocking<Unit> {
        val persistence = FakePersistence(session())
        val storage = FakeStorage()
        val service = service(persistence, storage = storage)

        val first = service.initiateUpload(
            principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null,
        )
        val retainedUntil = persistence.recording?.retainedUntil
        val renewed = service.initiateUpload(
            principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null,
        )
        val changed = runCatching {
            service.initiateUpload(principal, sessionId, "audio/mp4", 2_048, checksum, 60_000, null)
        }.exceptionOrNull()
        persistence.recording = persistence.recording?.copy(status = VoiceTutorRecordingStatus.FAILED)
        val failedRetry = runCatching {
            service.initiateUpload(principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null)
        }.exceptionOrNull()

        assertThat(first.recordingId).isEqualTo(sessionId)
        assertThat(renewed.recordingId).isEqualTo(sessionId)
        assertThat(persistence.recording?.retainedUntil).isEqualTo(retainedUntil)
        assertThat(storage.presignCount).isEqualTo(2)
        assertThat((changed as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_SESSION_CONFLICT)
        assertThat((failedRetry as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_SESSION_CONFLICT)
    }

    @Test
    fun `recording retention is fixed to call end and cannot be restarted later`() = runBlocking<Unit> {
        val endedAt = now.minusSeconds(60)
        val persistence = FakePersistence(session().copy(endedAt = endedAt))
        val storage = FakeStorage()

        service(persistence, storage = storage).initiateUpload(
            principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null,
        )
        val originalDeadline = persistence.recording?.retainedUntil
        service(
            persistence,
            storage = storage,
            instant = now.plusSeconds(24 * 60 * 60),
        ).initiateUpload(
            principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null,
        )

        assertThat(originalDeadline).isEqualTo(endedAt.plusSeconds(30 * 24 * 60 * 60L))
        assertThat(persistence.recording?.retainedUntil).isEqualTo(originalDeadline)

        val expired = FakePersistence(
            session().copy(endedAt = now.minusSeconds(31 * 24 * 60 * 60L)),
        )
        val failure = runCatching {
            service(expired, storage = storage).initiateUpload(
                principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null,
            )
        }.exceptionOrNull()

        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_SESSION_CONFLICT)
        assertThat(expired.recording).isNull()
    }

    @Test
    fun `completion retries the latest identical pending snapshot without resurrecting deleted metadata`() = runBlocking<Unit> {
        val persistence = FakePersistence(session())
        val storage = FakeStorage().apply {
            inspected = VoiceTutorRecordingObjectMetadata(
                contentType = "audio/mp4",
                byteSize = 1_024,
                checksumSha256Base64 = checksumBase64(checksum),
            )
        }
        val service = service(persistence, storage = storage)
        service.initiateUpload(principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null)
        storage.onInspect = {
            persistence.recording = persistence.recording?.copy(
                uploadExpiresAt = persistence.recording!!.uploadExpiresAt.plusSeconds(1),
                updatedAt = persistence.recording!!.updatedAt.plusNanos(1_000),
            )
        }

        val completed = service.completeUpload(principal, sessionId, sessionId)

        assertThat(completed.status).isEqualTo(VoiceTutorRecordingStatus.AVAILABLE)

        persistence.recording = persistence.recording?.copy(
            status = VoiceTutorRecordingStatus.PENDING,
            actualBytes = null,
            completedAt = null,
            updatedAt = now.plusSeconds(2),
        )
        storage.onInspect = {
            persistence.recording = persistence.recording?.copy(
                status = VoiceTutorRecordingStatus.DELETED,
                deletedAt = now.plusSeconds(3),
                updatedAt = now.plusSeconds(3),
            )
        }
        val deletedRace = runCatching {
            service.completeUpload(principal, sessionId, sessionId)
        }.exceptionOrNull()

        assertThat((deletedRace as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_SESSION_CONFLICT)
        assertThat(persistence.recording?.status).isEqualTo(VoiceTutorRecordingStatus.DELETED)
    }

    @Test
    fun `complete rejects a recording id from another session`() = runBlocking<Unit> {
        val persistence = FakePersistence(session()).apply { recording = availableRecording() }
        val failure = runCatching {
            service(persistence).completeUpload(
                principal,
                sessionId,
                "00000000-0000-4000-8000-000000000008",
            )
        }.exceptionOrNull()

        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `integrity mismatch deletes the object and records a failed tombstone`() = runBlocking<Unit> {
        val persistence = FakePersistence(session())
        val storage = FakeStorage().apply {
            inspected = VoiceTutorRecordingObjectMetadata("audio/mp4", 2_048, checksumBase64(checksum))
        }
        val service = service(persistence, storage = storage)
        service.initiateUpload(principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null)

        val failure = runCatching { service.completeUpload(principal, sessionId, sessionId) }.exceptionOrNull()

        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThat(storage.deletedKeys).containsExactly(storage.objectKey)
        assertThat(persistence.recording?.status).isEqualTo(VoiceTutorRecordingStatus.FAILED)
    }

    @Test
    fun `integrity mismatch claims failed metadata before object deletion`() = runBlocking<Unit> {
        val persistence = FakePersistence(session())
        val storage = FakeStorage().apply {
            inspected = VoiceTutorRecordingObjectMetadata("audio/mp4", 2_048, checksumBase64(checksum))
            failDelete = true
        }
        val service = service(persistence, storage = storage)
        service.initiateUpload(principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null)

        val failure = runCatching { service.completeUpload(principal, sessionId, sessionId) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(persistence.recording?.status).isEqualTo(VoiceTutorRecordingStatus.FAILED)

        storage.failDelete = false
        val properties = properties().apply { voiceTutor.recordingRetentionEnabled = false }
        val early = service(persistence, properties, storage, now.plusSeconds(59)).cleanupExpired()
        val retried = service(persistence, properties, storage, now.plusSeconds(61)).cleanupExpired()

        assertThat(early.attemptedRecordings).isZero()
        assertThat(retried.deletedRecordings).isEqualTo(1)
        assertThat(persistence.recording?.status).isEqualTo(VoiceTutorRecordingStatus.DELETED)
        assertThat(storage.deletedKeys).containsExactly(storage.objectKey)
    }

    @Test
    fun `integrity mismatch never deletes after its pending snapshot is superseded`() = runBlocking<Unit> {
        val persistence = FakePersistence(session())
        val storage = FakeStorage().apply {
            inspected = VoiceTutorRecordingObjectMetadata("audio/mp4", 2_048, checksumBase64(checksum))
        }
        val service = service(persistence, storage = storage)
        service.initiateUpload(principal, sessionId, "audio/mp4", 1_024, checksum, 60_000, null)
        storage.onInspect = {
            persistence.recording = persistence.recording?.copy(
                uploadExpiresAt = persistence.recording!!.uploadExpiresAt.plusSeconds(1),
                updatedAt = persistence.recording!!.updatedAt.plusNanos(1_000),
            )
        }

        val failure = runCatching { service.completeUpload(principal, sessionId, sessionId) }.exceptionOrNull()

        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_SESSION_CONFLICT)
        assertThat(storage.deletedKeys).isEmpty()
        assertThat(persistence.recording?.status).isEqualTo(VoiceTutorRecordingStatus.PENDING)
    }

    @Test
    fun `download is short lived and delete is owner scoped and idempotent`() = runBlocking<Unit> {
        val persistence = FakePersistence(session()).apply { recording = availableRecording() }
        val storage = FakeStorage()
        val service = service(persistence, storage = storage)

        val access = service.download(principal, sessionId)
        service.delete(principal, sessionId)
        service.delete(principal, sessionId)

        assertThat(access.url).isEqualTo("https://storage.example.test/download")
        assertThat(access.expiresAt).isEqualTo(now.plusSeconds(300))
        assertThat(access.recording.available).isTrue()
        assertThat(storage.deletedKeys).containsExactly(storage.objectKey, storage.objectKey)
        assertThat(persistence.recording?.status).isEqualTo(VoiceTutorRecordingStatus.DELETED)
    }

    @Test
    fun `download never signs access across the retention boundary`() = runBlocking<Unit> {
        val persistence = FakePersistence(session()).apply {
            recording = availableRecording().copy(retainedUntil = now.plusSeconds(2))
        }
        val storage = FakeStorage()

        val failure = runCatching {
            service(persistence, storage = storage).download(principal, sessionId)
        }.exceptionOrNull()

        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.RESOURCE_NOT_FOUND)
        assertThat(persistence.recording?.status).isEqualTo(VoiceTutorRecordingStatus.DELETED)
        assertThat(storage.deletedKeys).containsExactly(storage.objectKey)

        persistence.recording = availableRecording().copy(retainedUntil = now.plusSeconds(3))
        val access = service(persistence, storage = storage).download(principal, sessionId)

        assertThat(access.expiresAt).isEqualTo(now.plusSeconds(2))
    }

    @Test
    fun `retention cleanup deletes expired objects in bounded batches`() = runBlocking<Unit> {
        val expired = availableRecording().copy(retainedUntil = now.minusSeconds(1))
        val persistence = FakePersistence(session()).apply { recording = expired }
        val storage = FakeStorage()

        val result = service(persistence, storage = storage).cleanupExpired()

        assertThat(result.deletedRecordings).isEqualTo(1)
        assertThat(result.attemptedRecordings).isEqualTo(1)
        assertThat(storage.deletedKeys).containsExactly(storage.objectKey)
        assertThat(persistence.recording?.status).isEqualTo(VoiceTutorRecordingStatus.DELETED)
    }

    @Test
    fun `retention rechecks a deleted key after its reusable upload grant expires`() = runBlocking<Unit> {
        val deletedBeforeGrantExpiry = availableRecording().copy(
            status = VoiceTutorRecordingStatus.DELETED,
            deletedAt = now.minusSeconds(600),
            uploadExpiresAt = now.minusSeconds(1),
        )
        val persistence = FakePersistence(session()).apply { recording = deletedBeforeGrantExpiry }
        val storage = FakeStorage()

        val result = service(persistence, storage = storage).cleanupExpired()

        assertThat(result.deletedRecordings).isEqualTo(1)
        assertThat(storage.deletedKeys).containsExactly(storage.objectKey)
        assertThat(persistence.recording?.deletedAt).isEqualTo(now)
    }

    @Test
    fun `owner deleted keys keep retrying when ordinary retention is disabled`() = runBlocking<Unit> {
        val persistence = FakePersistence(session()).apply {
            recording = availableRecording().copy(
                status = VoiceTutorRecordingStatus.DELETED,
                deletedAt = now.minusSeconds(600),
                uploadExpiresAt = now.minusSeconds(1),
            )
        }
        val storage = FakeStorage()
        val properties = properties().apply { voiceTutor.recordingRetentionEnabled = false }

        val result = service(persistence, properties, storage).cleanupExpired()

        assertThat(result.deletedRecordings).isEqualTo(1)
        assertThat(storage.deletedKeys).containsExactly(storage.objectKey)
    }

    @Test
    fun `owner deletion is repeated every minute through safety and daily forever afterward`() = runBlocking<Unit> {
        val uploadExpiresAt = now.plusSeconds(60)
        val persistence = FakePersistence(session()).apply {
            recording = availableRecording().copy(uploadExpiresAt = uploadExpiresAt)
        }
        val storage = FakeStorage()
        val properties = properties().apply { voiceTutor.recordingUploadCompletionSafetySeconds = 300 }

        service(persistence, properties, storage, now).delete(principal, sessionId)
        service(persistence, properties, storage, uploadExpiresAt).cleanupExpired()
        service(persistence, properties, storage, uploadExpiresAt.plusSeconds(60)).cleanupExpired()
        service(persistence, properties, storage, uploadExpiresAt.plusSeconds(120)).cleanupExpired()
        service(persistence, properties, storage, uploadExpiresAt.plusSeconds(180)).cleanupExpired()
        service(persistence, properties, storage, uploadExpiresAt.plusSeconds(240)).cleanupExpired()
        service(persistence, properties, storage, uploadExpiresAt.plusSeconds(300)).cleanupExpired()
        val afterWindow = service(
            persistence,
            properties,
            storage,
            uploadExpiresAt.plusSeconds(360),
        ).cleanupExpired()
        val longTermRetry = service(
            persistence,
            properties,
            storage,
            uploadExpiresAt.plusSeconds(300 + 86_400),
        ).cleanupExpired()

        assertThat(storage.deletedKeys).hasSize(8)
        assertThat(persistence.recording?.deletedAt).isEqualTo(uploadExpiresAt.plusSeconds(300 + 86_400))
        assertThat(afterWindow.attemptedRecordings).isZero()
        assertThat(longTermRetry.deletedRecordings).isEqualTo(1)
    }

    @Test
    fun `withdrawal prefix tombstone retries every minute then daily without completion`() = runBlocking<Unit> {
        val cleanupUntil = now.plusSeconds(120)
        val persistence = FakePersistence(session()).apply {
            prefixCleanup = VoiceTutorRecordingPrefixCleanup(principal.userId, cleanupUntil)
            prefixNextAttemptAt = now
        }
        val storage = FakeStorage()
        val properties = properties().apply { voiceTutor.recordingRetentionEnabled = false }

        val first = service(persistence, properties, storage, now).cleanupExpired()
        val second = service(persistence, properties, storage, now.plusSeconds(60)).cleanupExpired()
        val atDeadline = service(persistence, properties, storage, cleanupUntil).cleanupExpired()
        val beforeDaily = service(persistence, properties, storage, cleanupUntil.plusSeconds(60)).cleanupExpired()
        val daily = service(persistence, properties, storage, cleanupUntil.plusSeconds(86_400)).cleanupExpired()

        assertThat(first.attemptedPrefixCleanups).isEqualTo(1)
        assertThat(first.completedPrefixCleanups).isZero()
        assertThat(second.completedPrefixCleanups).isZero()
        assertThat(atDeadline.completedPrefixCleanups).isZero()
        assertThat(beforeDaily.attemptedPrefixCleanups).isZero()
        assertThat(daily.attemptedPrefixCleanups).isEqualTo(1)
        assertThat(storage.deletedUserPrefixes).containsExactly(7L, 7L, 7L, 7L)
        assertThat(persistence.prefixCleanup).isNotNull
    }

    @Test
    fun `failed final withdrawal prefix delete keeps its tombstone for a later retry`() = runBlocking<Unit> {
        val persistence = FakePersistence(session()).apply {
            prefixCleanup = VoiceTutorRecordingPrefixCleanup(principal.userId, now)
            prefixNextAttemptAt = now
        }
        val storage = FakeStorage().apply { failDeleteAll = true }

        val failed = service(persistence, storage = storage, instant = now).cleanupExpired()
        val persistedFailure = persistence.prefixFailure
        storage.failDeleteAll = false
        val early = service(persistence, storage = storage, instant = now.plusSeconds(60)).cleanupExpired()
        val retried = service(persistence, storage = storage, instant = now.plusSeconds(86_400)).cleanupExpired()

        assertThat(failed.attemptedPrefixCleanups).isEqualTo(1)
        assertThat(failed.completedPrefixCleanups).isZero()
        assertThat(persistedFailure).isEqualTo("IllegalStateException")
        assertThat(early.attemptedPrefixCleanups).isZero()
        assertThat(retried.attemptedPrefixCleanups).isEqualTo(1)
        assertThat(retried.completedPrefixCleanups).isZero()
        assertThat(persistence.prefixCleanup).isNotNull
    }

    @Test
    fun `retention claims the selected snapshot before deleting storage`() = runBlocking<Unit> {
        val stale = availableRecording().copy(
            status = VoiceTutorRecordingStatus.PENDING,
            actualBytes = null,
            completedAt = null,
            retainedUntil = now.minusSeconds(1),
        )
        val persistence = FakePersistence(session()).apply {
            recording = stale
            beforeMarkExpiredDeleted = {
                recording = recording?.copy(
                    uploadExpiresAt = recording!!.uploadExpiresAt.plusSeconds(300),
                    retainedUntil = now.plusSeconds(86_400),
                    updatedAt = now,
                )
            }
        }
        val storage = FakeStorage()

        val result = service(persistence, storage = storage).cleanupExpired()

        assertThat(result.deletedRecordings).isZero()
        assertThat(storage.deletedKeys).isEmpty()
        assertThat(persistence.recording?.status).isEqualTo(VoiceTutorRecordingStatus.PENDING)
        assertThat(persistence.recording?.retainedUntil).isAfter(now)
    }

    private fun service(
        persistence: FakePersistence,
        properties: BuddyStudyProperties = properties(),
        storage: FakeStorage = FakeStorage(),
        instant: Instant = now,
    ) = VoiceTutorRecordingService(
        persistence = persistence,
        properties = properties,
        storage = storage,
        clock = Clock.fixed(instant, ZoneOffset.UTC),
    )

    private fun properties() = BuddyStudyProperties().apply {
        voiceTutor.recordingEnabled = true
        voiceTutor.recordingBucket = "private-recordings"
        voiceTutor.recordingRetentionDays = 30
        voiceTutor.recordingPresignSeconds = 300
    }

    private fun session() = VoiceTutorRecordingSession(
        sessionId = sessionId,
        userId = principal.userId,
        status = VoiceTutorSessionStatus.COMPLETED,
        maxSessionSeconds = 3_600,
        durationSeconds = 60,
        endedAt = now.minusSeconds(1),
        recordingConsentedAt = now.minusSeconds(70),
        recordingConsentVersion = "voice-recording-v1",
    )

    private fun availableRecording() = VoiceTutorRecording(
        sessionId = sessionId,
        userId = principal.userId,
        objectKey = "voice-tutor-recordings/7/$sessionId/recording.m4a",
        status = VoiceTutorRecordingStatus.AVAILABLE,
        contentType = "audio/mp4",
        expectedBytes = 1_024,
        actualBytes = 1_024,
        sha256Hex = checksum,
        durationMilliseconds = 60_000,
        consentedAt = now.minusSeconds(70),
        consentVersion = "voice-recording-v1",
        uploadExpiresAt = now.minusSeconds(10),
        retainedUntil = now.plusSeconds(86_400),
        completedAt = now.minusSeconds(5),
        deletedAt = null,
        failureMessage = null,
        createdAt = now.minusSeconds(60),
        updatedAt = now.minusSeconds(5),
    )

    private inner class FakePersistence(
        private val ownedSession: VoiceTutorRecordingSession,
    ) : VoiceTutorRecordingPersistencePort {
        var recording: VoiceTutorRecording? = null
        var prefixCleanup: VoiceTutorRecordingPrefixCleanup? = null
        var prefixNextAttemptAt: Instant? = null
        var prefixFailure: String? = null
        var beforeMarkExpiredDeleted: (() -> Unit)? = null

        override suspend fun latestUploadExpiry(userId: Long): Instant? =
            recording?.takeIf { it.userId == userId }?.uploadExpiresAt

        override suspend fun schedulePrefixCleanup(userId: Long, cleanupUntil: Instant, now: Instant) {
            val existing = prefixCleanup
            prefixCleanup = VoiceTutorRecordingPrefixCleanup(
                userId,
                maxOf(existing?.cleanupUntil ?: cleanupUntil, cleanupUntil),
            )
            prefixNextAttemptAt = minOf(prefixNextAttemptAt ?: now, now)
        }

        override suspend fun pendingPrefixCleanups(now: Instant, limit: Int) = listOfNotNull(prefixCleanup)
            .filter { prefixNextAttemptAt?.isAfter(now) != true }
            .take(limit)

        override suspend fun deferPrefixCleanup(
            userId: Long,
            expectedCleanupUntil: Instant,
            attemptedAt: Instant,
            nextAttemptAt: Instant,
            failureMessage: String?,
        ): Boolean {
            val current = prefixCleanup?.takeIf {
                it.userId == userId && it.cleanupUntil == expectedCleanupUntil
            } ?: return false
            prefixCleanup = current
            prefixNextAttemptAt = nextAttemptAt
            prefixFailure = failureMessage
            return true
        }

        override suspend fun session(userId: Long, sessionId: String) =
            ownedSession.takeIf { it.userId == userId && it.sessionId == sessionId }

        override suspend fun recording(userId: Long, sessionId: String) =
            recording?.takeIf { it.userId == userId && it.sessionId == sessionId }

        override suspend fun savePending(
            recording: VoiceTutorRecording,
            expected: VoiceTutorRecordingUploadSnapshot?,
        ): VoiceTutorRecording? {
            val current = this.recording
            if (expected == null) {
                if (current != null) return null
                this.recording = recording
                return recording
            }
            if (current?.status != VoiceTutorRecordingStatus.PENDING || current.uploadSnapshot() != expected) return null
            if (!current.sameImmutableUploadContract(recording)) return null
            return current.copy(
                uploadExpiresAt = recording.uploadExpiresAt,
                retainedUntil = maxOf(current.retainedUntil, recording.retainedUntil),
                updatedAt = recording.updatedAt,
            ).also { this.recording = it }
        }

        override suspend fun markAvailable(
            userId: Long,
            sessionId: String,
            expected: VoiceTutorRecordingUploadSnapshot,
            actualBytes: Long,
            completedAt: Instant,
        ) = recording(userId, sessionId)
            ?.takeIf { it.status == VoiceTutorRecordingStatus.PENDING && it.uploadSnapshot() == expected }
            ?.copy(
                status = VoiceTutorRecordingStatus.AVAILABLE,
                actualBytes = actualBytes,
                completedAt = completedAt,
                updatedAt = completedAt,
            )?.also { recording = it }

        override suspend fun markFailed(
            userId: Long,
            sessionId: String,
            expected: VoiceTutorRecordingUploadSnapshot,
            failureMessage: String,
            now: Instant,
        ) = recording(userId, sessionId)
            ?.takeIf { it.status == VoiceTutorRecordingStatus.PENDING && it.uploadSnapshot() == expected }
            ?.copy(
                status = VoiceTutorRecordingStatus.FAILED,
                failureMessage = failureMessage,
                updatedAt = now,
            )?.also { recording = it }

        override suspend fun markDeleted(
            userId: Long,
            sessionId: String,
            expected: VoiceTutorRecording,
            deletedAt: Instant,
        ): VoiceTutorRecording? {
            val current = recording(userId, sessionId)
                ?.takeIf { it.status == expected.status && it.uploadSnapshot() == expected.uploadSnapshot() }
                ?: return null
            return current.copy(
                status = VoiceTutorRecordingStatus.DELETED,
                deletedAt = deletedAt,
                updatedAt = deletedAt,
            ).also { recording = it }
        }

        override suspend fun markExpiredDeleted(
            expected: VoiceTutorRecording,
            deletedAt: Instant,
        ): VoiceTutorRecording? {
            beforeMarkExpiredDeleted?.also { beforeMarkExpiredDeleted = null }?.invoke()
            val current = recording(expected.userId, expected.sessionId)
                ?.takeIf { it.status == expected.status && it.uploadSnapshot() == expected.uploadSnapshot() }
                ?.takeIf {
                    (it.status in setOf(VoiceTutorRecordingStatus.PENDING, VoiceTutorRecordingStatus.AVAILABLE) &&
                        !it.retainedUntil.isAfter(deletedAt)) ||
                        (it.status == VoiceTutorRecordingStatus.FAILED &&
                            !it.updatedAt.isAfter(deletedAt.minusSeconds(60))) ||
                        (it.status == VoiceTutorRecordingStatus.DELETED && !it.uploadExpiresAt.isAfter(deletedAt))
                }
                ?: return null
            return current.copy(
                status = VoiceTutorRecordingStatus.DELETED,
                deletedAt = deletedAt,
                updatedAt = deletedAt,
            ).also { recording = it }
        }

        override suspend fun expired(
            now: Instant,
            uploadCompletionSafetySeconds: Long,
            includeOrdinaryRetention: Boolean,
            limit: Int,
        ) = listOfNotNull(recording)
            .filter {
                (includeOrdinaryRetention &&
                    it.status != VoiceTutorRecordingStatus.DELETED &&
                    !it.retainedUntil.isAfter(now)) ||
                    (it.status == VoiceTutorRecordingStatus.FAILED &&
                        !it.updatedAt.isAfter(now.minusSeconds(60))) ||
                    (it.status == VoiceTutorRecordingStatus.DELETED &&
                        !it.uploadExpiresAt.isAfter(now) &&
                        it.deletedAt?.isAfter(
                            now.minusSeconds(
                                if (now.isAfter(it.uploadExpiresAt.plusSeconds(uploadCompletionSafetySeconds))) {
                                    86_400
                                } else {
                                    60
                                },
                            ),
                        ) != true)
            }
            .take(limit)

        private fun VoiceTutorRecording.sameImmutableUploadContract(other: VoiceTutorRecording): Boolean =
            sessionId == other.sessionId &&
                userId == other.userId &&
                objectKey == other.objectKey &&
                contentType == other.contentType &&
                expectedBytes == other.expectedBytes &&
                sha256Hex == other.sha256Hex &&
                durationMilliseconds == other.durationMilliseconds &&
                consentedAt == other.consentedAt &&
                consentVersion == other.consentVersion
    }

    private inner class FakeStorage : VoiceTutorRecordingStoragePort {
        val objectKey = "voice-tutor-recordings/7/$sessionId/recording.m4a"
        var inspected: VoiceTutorRecordingObjectMetadata? = null
        val deletedKeys = mutableListOf<String>()
        val deletedUserPrefixes = mutableListOf<Long>()
        var onInspect: (() -> Unit)? = null
        var presignCount = 0
        var failDelete = false
        var failDeleteAll = false

        override suspend fun presignUpload(
            userId: Long,
            sessionId: String,
            contentType: String,
            byteSize: Long,
            sha256Hex: String,
            expiresAt: Instant,
        ): VoiceTutorRecordingUploadGrant {
            presignCount += 1
            return VoiceTutorRecordingUploadGrant(
                objectKey = objectKey,
                uploadUrl = "https://storage.example.test/upload?signature=temporary",
                requiredHeaders = mapOf("Content-Type" to contentType, "Content-Length" to byteSize.toString()),
                expiresAt = expiresAt,
            )
        }

        override suspend fun inspect(objectKey: String): VoiceTutorRecordingObjectMetadata? {
            onInspect?.also { onInspect = null }?.invoke()
            return inspected
        }

        override suspend fun presignDownload(objectKey: String, sessionId: String, expiresAt: Instant) =
            VoiceTutorRecordingDownloadGrant("https://storage.example.test/download", expiresAt)

        override suspend fun delete(objectKey: String) {
            if (failDelete) error("storage delete failed")
            deletedKeys += objectKey
        }

        override suspend fun deleteAll(userId: Long) {
            deletedUserPrefixes += userId
            if (failDeleteAll) error("storage prefix delete failed")
        }
    }

    private fun checksumBase64(hex: String): String =
        Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex))
}
