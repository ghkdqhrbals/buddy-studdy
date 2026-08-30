package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.voice.application.model.VoiceTutorCreateSessionResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionDetailResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionsPageResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorStatusResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingDownloadResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingUploadResponse
import org.springframework.security.core.Authentication

interface VoiceTutorWebPort {
    suspend fun status(authentication: Authentication): VoiceTutorStatusResponse

    suspend fun createSession(
        request: CreateVoiceTutorSessionRequest,
        idempotencyKey: String,
        authentication: Authentication,
    ): VoiceTutorCreateSessionResponse

    suspend fun sessions(
        limit: Int,
        cursor: String?,
        authentication: Authentication,
    ): VoiceTutorSessionsPageResponse

    suspend fun session(sessionId: String, authentication: Authentication): VoiceTutorSessionDetailResponse
    suspend fun endSession(sessionId: String, authentication: Authentication): VoiceTutorSessionDetailResponse
    suspend fun createRecordingUpload(
        sessionId: String,
        request: CreateVoiceTutorRecordingUploadRequest,
        authentication: Authentication,
    ): VoiceTutorRecordingUploadResponse
    suspend fun completeRecordingUpload(
        sessionId: String,
        recordingId: String,
        authentication: Authentication,
    ): VoiceTutorRecordingResponse
    suspend fun recordingAccess(sessionId: String, authentication: Authentication): VoiceTutorRecordingDownloadResponse
    suspend fun deleteRecording(sessionId: String, authentication: Authentication)
}
