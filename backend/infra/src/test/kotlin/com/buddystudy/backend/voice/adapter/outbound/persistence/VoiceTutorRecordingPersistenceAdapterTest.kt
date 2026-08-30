package com.buddystudy.backend.voice.adapter.outbound.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

class VoiceTutorRecordingPersistenceAdapterTest {
    @Test
    fun `withdrawal prefix tombstone commits independently from relational account cleanup`() {
        val method = VoiceTutorRecordingPersistenceAdapter::class.java.declaredMethods
            .single { it.name == "schedulePrefixCleanup" }
        val transaction = method.getAnnotation(Transactional::class.java)

        assertThat(transaction).isNotNull
        assertThat(transaction?.propagation).isEqualTo(Propagation.REQUIRES_NEW)
    }

    @Test
    fun `completion transitions require the full pending upload snapshot`() {
        assertThat(VOICE_TUTOR_RECORDING_PENDING_SNAPSHOT_PREDICATE).contains(
            "recording.status = 'PENDING'",
            "recording.object_key = :expectedObjectKey",
            "recording.content_type = :expectedContentType",
            "recording.expected_bytes = :expectedBytesSnapshot",
            "recording.sha256_hex = :expectedSha256Hex",
            "recording.duration_milliseconds = :expectedDurationMilliseconds",
            "recording.upload_expires_at = :expectedUploadExpiresAt",
            "recording.updated_at = :expectedUpdatedAt",
        )
        assertThat(VOICE_TUTOR_RECORDING_PENDING_SNAPSHOT_PREDICATE)
            .doesNotContain("AVAILABLE", "FAILED", "DELETED")
    }

    @Test
    fun `deleted recording cleanup changes from minute to permanent daily retries`() {
        assertThat(VOICE_TUTOR_RECORDING_FAILED_RETRY_PREDICATE).contains(
            "status = 'FAILED'",
            "updated_at <= :retryBefore",
        )
        assertThat(VOICE_TUTOR_RECORDING_DELETED_RETRY_PREDICATE).contains(
            "deleted_at <= :retryBefore",
            "deleted_at <= :longTermRetryBefore",
            ":now <= timestampadd(SECOND, :safetySeconds, upload_expires_at)",
            ":now > timestampadd(SECOND, :safetySeconds, upload_expires_at)",
        )
        assertThat(VOICE_TUTOR_RECORDING_DELETED_RETRY_PREDICATE)
            .doesNotContain("deleted_at < timestampadd")
    }
}
