package com.buddystudy.backend.profile

import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
import com.buddystudy.backend.auth.application.port.outbound.AccountWithdrawalSnapshot
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.profile.application.model.AccountWithdrawnEvent
import com.buddystudy.backend.profile.application.port.outbound.ProfilePhotoStoragePort
import com.buddystudy.backend.profile.application.port.outbound.StoredProfilePhoto
import com.buddystudy.backend.profile.application.service.AccountWithdrawalCleanupService
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingDownloadGrant
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingObjectMetadata
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingStoragePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingUploadGrant
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorRecordingPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingPersistencePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset

class AccountWithdrawalCleanupServiceTest {
    private val withdrawnAt = Instant.parse("2026-08-30T00:00:00Z")
    private val event = AccountWithdrawnEvent("account-withdrawn-7", 7, listOf("device-7"), withdrawnAt)

    @Test
    fun `withdrawal commits a tombstone before the initial prefix attempt and relational cleanup`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val service = AccountWithdrawalCleanupService(
            accountDeletion = deletion(calls),
            profilePhotos = photos(calls),
            voiceTutorRecordings = recordings(calls),
            voiceTutorRecordingMetadata = recordingMetadata(calls),
            properties = recordingProperties(),
            clock = Clock.fixed(withdrawnAt, ZoneOffset.UTC),
        )

        service.cleanup(event)

        assertThat(calls).containsExactly(
            "voice-prefix-tombstone:7:${withdrawnAt.plusSeconds(600)}",
            "profile-photo:7",
            "voice-recordings:7",
            "voice-prefix-tombstone:7:${withdrawnAt.plusSeconds(600)}",
            "account-data:7",
        )
    }

    @Test
    fun `storage failure leaves a durable retry tombstone while relational withdrawal completes`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val failingStorage = recordings(calls, failure = IllegalStateException("storage unavailable"))
        val service = AccountWithdrawalCleanupService(
            accountDeletion = deletion(calls),
            profilePhotos = photos(calls),
            voiceTutorRecordings = failingStorage,
            voiceTutorRecordingMetadata = recordingMetadata(calls),
            properties = recordingProperties(),
            clock = Clock.fixed(withdrawnAt, ZoneOffset.UTC),
        )

        service.cleanup(event)

        assertThat(calls).containsExactly(
            "voice-prefix-tombstone:7:${withdrawnAt.plusSeconds(600)}",
            "profile-photo:7",
            "voice-recordings:7",
            "voice-prefix-tombstone:7:${withdrawnAt.plusSeconds(600)}",
            "account-data:7",
        )
    }

    @Test
    fun `storage cancellation propagates without starting relational deletion`() {
        val calls = mutableListOf<String>()
        val cancellation = CancellationException("cleanup cancelled")
        val service = AccountWithdrawalCleanupService(
            accountDeletion = deletion(calls),
            profilePhotos = photos(calls),
            voiceTutorRecordings = recordings(calls, failure = cancellation),
            voiceTutorRecordingMetadata = recordingMetadata(calls),
            properties = recordingProperties(),
            clock = Clock.fixed(withdrawnAt, ZoneOffset.UTC),
        )

        assertThatThrownBy {
            runBlocking { service.cleanup(event) }
        }.isSameAs(cancellation)
        assertThat(calls).containsExactly(
            "voice-prefix-tombstone:7:${withdrawnAt.plusSeconds(600)}",
            "profile-photo:7",
            "voice-recordings:7",
        )
    }

    @Test
    fun `account cleanup persists the frequent retry deadline through the latest grant expiry`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val activeExpiry = withdrawnAt.plusSeconds(600)
        val service = AccountWithdrawalCleanupService(
            accountDeletion = deletion(calls),
            profilePhotos = photos(calls),
            voiceTutorRecordings = recordings(calls),
            voiceTutorRecordingMetadata = recordingMetadata(calls, latestExpiry = activeExpiry),
            properties = recordingProperties(),
            clock = Clock.fixed(withdrawnAt, ZoneOffset.UTC),
        )

        service.cleanup(event)

        assertThat(calls).containsExactly(
            "voice-prefix-tombstone:7:${activeExpiry.plusSeconds(300)}",
            "profile-photo:7",
            "voice-recordings:7",
            "voice-prefix-tombstone:7:${activeExpiry.plusSeconds(300)}",
            "account-data:7",
        )
    }

    @Test
    fun `account cleanup extends the tombstone after a slow initial prefix delete`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val afterStorageDelete = withdrawnAt.plusSeconds(120)
        val service = AccountWithdrawalCleanupService(
            accountDeletion = deletion(calls),
            profilePhotos = photos(calls),
            voiceTutorRecordings = recordings(calls),
            voiceTutorRecordingMetadata = recordingMetadata(calls),
            properties = recordingProperties(),
            clock = SequenceClock(withdrawnAt, afterStorageDelete),
        )

        service.cleanup(event)

        assertThat(calls).containsExactly(
            "voice-prefix-tombstone:7:${withdrawnAt.plusSeconds(600)}",
            "profile-photo:7",
            "voice-recordings:7",
            "voice-prefix-tombstone:7:${afterStorageDelete.plusSeconds(600)}",
            "account-data:7",
        )
    }

    private fun deletion(calls: MutableList<String>) = object : AccountDeletionPort {
        override suspend fun beginWithdrawal(userId: Long, now: Instant) = AccountWithdrawalSnapshot(emptyList())
        override suspend fun deleteAccountData(userId: Long, deviceIds: List<String>, withdrawnAt: Instant) {
            calls += "account-data:$userId"
        }
    }

    private fun photos(calls: MutableList<String>) = object : ProfilePhotoStoragePort {
        override suspend fun save(userId: Long, contentType: String, bytes: ByteArray) = error("not used")
        override suspend fun load(userId: Long): StoredProfilePhoto? = null
        override suspend fun delete(userId: Long) {
            calls += "profile-photo:$userId"
        }
    }

    private fun recordings(
        calls: MutableList<String>,
        failure: Throwable? = null,
    ) = object : VoiceTutorRecordingStoragePort {
        override suspend fun presignUpload(
            userId: Long,
            sessionId: String,
            contentType: String,
            byteSize: Long,
            sha256Hex: String,
            expiresAt: Instant,
        ): VoiceTutorRecordingUploadGrant = error("not used")

        override suspend fun inspect(objectKey: String): VoiceTutorRecordingObjectMetadata? = error("not used")
        override suspend fun presignDownload(
            objectKey: String,
            sessionId: String,
            expiresAt: Instant,
        ): VoiceTutorRecordingDownloadGrant = error("not used")
        override suspend fun delete(objectKey: String) = error("not used")
        override suspend fun deleteAll(userId: Long) {
            calls += "voice-recordings:$userId"
            failure?.let { throw it }
        }
    }

    private fun recordingMetadata(
        calls: MutableList<String>,
        latestExpiry: Instant? = null,
    ) = object : VoiceTutorRecordingPersistencePort by
        UnavailableVoiceTutorRecordingPersistencePort {
        override suspend fun latestUploadExpiry(userId: Long) = latestExpiry?.takeIf { userId == event.userId }
        override suspend fun schedulePrefixCleanup(userId: Long, cleanupUntil: Instant, now: Instant) {
            calls += "voice-prefix-tombstone:$userId:$cleanupUntil"
        }
    }

    private fun recordingProperties() = BuddyStudyProperties().apply {
        voiceTutor.recordingBucket = "private-recordings"
        voiceTutor.recordingPresignSeconds = 300
        voiceTutor.recordingUploadCompletionSafetySeconds = 300
    }

    private class SequenceClock(vararg instants: Instant) : Clock() {
        private val values = ArrayDeque(instants.toList())
        private var latest = values.first()

        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant {
            if (values.isNotEmpty()) latest = values.removeFirst()
            return latest
        }
    }
}
