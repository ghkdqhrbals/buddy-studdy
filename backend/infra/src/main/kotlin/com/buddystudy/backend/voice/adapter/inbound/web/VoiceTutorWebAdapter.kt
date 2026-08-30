package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class VoiceTutorWebAdapter(
    private val voiceTutor: VoiceTutorUseCase,
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
    )

    override suspend fun sessions(limit: Int, cursor: String?, authentication: Authentication) =
        voiceTutor.sessions(authentication.principalOrThrow(), limit, cursor)

    override suspend fun session(sessionId: String, authentication: Authentication) =
        voiceTutor.session(authentication.principalOrThrow(), sessionId)

    override suspend fun endSession(sessionId: String, authentication: Authentication) =
        voiceTutor.endSession(authentication.principalOrThrow(), sessionId)
}
