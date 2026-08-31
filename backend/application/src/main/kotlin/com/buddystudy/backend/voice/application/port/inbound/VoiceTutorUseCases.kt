package com.buddystudy.backend.voice.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.voice.application.model.VoiceTutorCreateSessionResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRelayContext
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionDetailResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionsPageResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorStatusResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingDownloadResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingRetentionResult
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingUploadResponse
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface VoiceTutorUseCase {
    suspend fun status(principal: Principal): VoiceTutorStatusResponse

    suspend fun createSession(
        principal: Principal,
        studyId: Long?,
        language: String,
        voice: String?,
        idempotencyKey: String,
        recordingConsent: Boolean = false,
        recordingConsentVersion: String? = null,
    ): VoiceTutorCreateSessionResponse

    suspend fun sessions(principal: Principal, limit: Int, cursor: String?): VoiceTutorSessionsPageResponse
    suspend fun session(principal: Principal, sessionId: String): VoiceTutorSessionDetailResponse
    suspend fun endSession(principal: Principal, sessionId: String): VoiceTutorSessionDetailResponse
}

interface VoiceTutorRecordingUseCase {
    suspend fun initiateUpload(
        principal: Principal,
        sessionId: String,
        contentType: String,
        contentLength: Long,
        sha256: String,
        durationMilliseconds: Long?,
        durationSeconds: Long?,
    ): VoiceTutorRecordingUploadResponse

    suspend fun completeUpload(principal: Principal, sessionId: String, recordingId: String): VoiceTutorRecordingResponse
    suspend fun download(principal: Principal, sessionId: String): VoiceTutorRecordingDownloadResponse
    suspend fun delete(principal: Principal, sessionId: String)
}

interface VoiceTutorRecordingRetentionUseCase {
    suspend fun cleanupExpired(): VoiceTutorRecordingRetentionResult
}

interface VoiceTutorRelayUseCase {
    suspend fun connect(principal: Principal, sessionId: String): VoiceTutorRelayContext
    suspend fun relayAuthorized(principal: Principal): Boolean
    suspend fun attachProviderSession(principal: Principal, sessionId: String, providerSessionId: String)
    suspend fun heartbeat(principal: Principal, sessionId: String): VoiceTutorSessionStatus
    suspend fun recordAcceptedAudioBytes(principal: Principal, sessionId: String, bytes: Long)
    suspend fun relayState(principal: Principal, sessionId: String): VoiceTutorSessionStatus

    suspend fun relayProvider(
        principal: Principal,
        context: VoiceTutorRelayContext,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (
            raw: String,
            persist: Boolean,
            forwardToClient: Boolean,
        ) -> Boolean,
    )

    suspend fun appendTranscript(
        principal: Principal,
        sessionId: String,
        providerItemId: String,
        role: VoiceTutorTranscriptRole,
        transcript: String,
        occurredAt: Instant,
        lessonRevision: Long = 0,
    ): Boolean

    suspend fun finish(
        principal: Principal,
        sessionId: String,
        reason: String,
        failed: Boolean = false,
        failureMessage: String? = null,
    ): VoiceTutorSessionDetailResponse

    suspend fun finishWebRtc(
        principal: Principal,
        sessionId: String,
        providerSessionId: String,
        reason: String,
        failed: Boolean = false,
        failureMessage: String? = null,
    ): VoiceTutorSessionDetailResponse
}

interface VoiceTutorResultRecoveryUseCase {
    suspend fun recoverPendingResults(limit: Int): Int
}

interface VoiceTutorSessionRecoveryUseCase {
    suspend fun recoverStaleSessions(limit: Int): Int
}
