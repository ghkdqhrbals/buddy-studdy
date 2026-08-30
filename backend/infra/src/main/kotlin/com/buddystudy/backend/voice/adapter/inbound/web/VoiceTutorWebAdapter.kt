package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRecordingUseCase
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class VoiceTutorWebAdapter(
    private val voiceTutor: VoiceTutorUseCase,
    private val recordings: VoiceTutorRecordingUseCase,
) : VoiceTutorWebPort {
    override suspend fun status(authentication: Authentication) =
        voiceTutor.status(authentication.principalOrThrow())

    override suspend fun createSession(
        request: CreateVoiceTutorSessionRequest,
        idempotencyKey: String,
        authentication: Authentication,
    ) = voiceTutor.createSession(
        principal = authentication.principalOrThrow(),
        studyId = request.studyId,
        language = request.language,
        voice = request.voice,
        idempotencyKey = idempotencyKey,
        recordingConsent = request.recordingConsent,
        recordingConsentVersion = request.recordingConsentVersion,
    )

    override suspend fun sessions(limit: Int, cursor: String?, authentication: Authentication) =
        voiceTutor.sessions(authentication.principalOrThrow(), limit, cursor)

    override suspend fun session(sessionId: String, authentication: Authentication) =
        voiceTutor.session(authentication.principalOrThrow(), sessionId)

    override suspend fun endSession(sessionId: String, authentication: Authentication) =
        voiceTutor.endSession(authentication.principalOrThrow(), sessionId)

    override suspend fun createRecordingUpload(
        sessionId: String,
        request: CreateVoiceTutorRecordingUploadRequest,
        authentication: Authentication,
    ) = recordings.initiateUpload(
        principal = authentication.principalOrThrow(),
        sessionId = sessionId,
        contentType = request.contentType,
        contentLength = request.contentLength,
        sha256 = request.sha256,
        durationMilliseconds = request.durationMilliseconds,
        durationSeconds = request.durationSeconds,
    )

    override suspend fun completeRecordingUpload(
        sessionId: String,
        recordingId: String,
        authentication: Authentication,
    ) = recordings.completeUpload(authentication.principalOrThrow(), sessionId, recordingId)

    override suspend fun recordingAccess(sessionId: String, authentication: Authentication) =
        recordings.download(authentication.principalOrThrow(), sessionId)

    override suspend fun deleteRecording(sessionId: String, authentication: Authentication) =
        recordings.delete(authentication.principalOrThrow(), sessionId)
}
