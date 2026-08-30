package com.buddystudy.backend.profile.application.service

import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.profile.application.model.AccountWithdrawnEvent
import com.buddystudy.backend.profile.application.port.inbound.AccountWithdrawalCleanupUseCase
import com.buddystudy.backend.profile.application.port.outbound.ProfilePhotoStoragePort
import com.buddystudy.backend.profile.application.port.outbound.UnavailableProfilePhotoStoragePort
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorRecordingStoragePort
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorRecordingPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRecordingStoragePort
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class AccountWithdrawalCleanupService(
    private val accountDeletion: AccountDeletionPort,
    private val profilePhotos: ProfilePhotoStoragePort = UnavailableProfilePhotoStoragePort,
    private val voiceTutorRecordings: VoiceTutorRecordingStoragePort = UnavailableVoiceTutorRecordingStoragePort,
    private val voiceTutorRecordingMetadata: VoiceTutorRecordingPersistencePort =
        UnavailableVoiceTutorRecordingPersistencePort,
    private val properties: BuddyStudyProperties = BuddyStudyProperties(),
    private val clock: Clock = Clock.systemUTC(),
) : AccountWithdrawalCleanupUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override suspend fun cleanup(event: AccountWithdrawnEvent) {
        val now = clock.instant()
        val latestUploadExpiry = voiceTutorRecordingMetadata.latestUploadExpiry(event.userId)
        val recordingPrefixMayExist = latestUploadExpiry != null || properties.voiceTutor.recordingBucket.isNotBlank()
        if (recordingPrefixMayExist) {
            schedulePrefixCleanup(event.userId, latestUploadExpiry, now)
        }
        profilePhotos.delete(event.userId)
        if (recordingPrefixMayExist) {
            try {
                voiceTutorRecordings.deleteAll(event.userId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.warn(
                    "account_withdrawal_voice_recording_prefix_delete_deferred eventId={} userId={} errorType={}",
                    event.eventId,
                    event.userId,
                    error.javaClass.simpleName,
                )
            }
            // Extend again immediately before relational deletion so time spent in
            // the external prefix delete cannot shorten a concurrently issued grant.
            val accountDeletionBoundary = clock.instant()
            schedulePrefixCleanup(event.userId, latestUploadExpiry, accountDeletionBoundary)
        }
        accountDeletion.deleteAccountData(
            userId = event.userId,
            deviceIds = event.deviceIds,
            withdrawnAt = event.withdrawnAt,
        )
        log.info(
            "account_withdrawal_cleanup_completed eventId={} userId={} deviceCount={}",
            event.eventId,
            event.userId,
            event.deviceIds.size,
        )
    }

    private suspend fun schedulePrefixCleanup(userId: Long, latestUploadExpiry: Instant?, now: Instant) {
        // Cover both persisted grants and a grant request that crossed the withdrawal boundary.
        val latestPossibleGrantExpiry = maxOf(
            latestUploadExpiry ?: now,
            now.plusSeconds(configuredRecordingPresignSeconds()),
        )
        voiceTutorRecordingMetadata.schedulePrefixCleanup(
            userId = userId,
            cleanupUntil = latestPossibleGrantExpiry.plusSeconds(configuredUploadCompletionSafetySeconds()),
            now = now,
        )
    }

    private fun configuredRecordingPresignSeconds(): Long =
        properties.voiceTutor.recordingPresignSeconds.coerceIn(MIN_PRESIGN_SECONDS, MAX_PRESIGN_SECONDS)

    private fun configuredUploadCompletionSafetySeconds(): Long =
        properties.voiceTutor.recordingUploadCompletionSafetySeconds.coerceIn(
            MIN_UPLOAD_COMPLETION_SAFETY_SECONDS,
            MAX_UPLOAD_COMPLETION_SAFETY_SECONDS,
        )

    private companion object {
        const val MIN_PRESIGN_SECONDS = 30L
        const val MAX_PRESIGN_SECONDS = 900L
        const val MIN_UPLOAD_COMPLETION_SAFETY_SECONDS = 60L
        const val MAX_UPLOAD_COMPLETION_SAFETY_SECONDS = 3_600L
    }
}
