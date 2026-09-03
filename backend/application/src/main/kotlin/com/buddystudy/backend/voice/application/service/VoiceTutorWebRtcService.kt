package com.buddystudy.backend.voice.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.model.VoiceTutorInitialStudyMutationSnapshot
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRelayUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorWebRtcUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorControlClaimPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcAnswer
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcCleanupPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcPort
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

@Service
class VoiceTutorWebRtcService(
    private val relay: VoiceTutorRelayUseCase,
    private val persistence: VoiceTutorPersistencePort,
    private val realtime: VoiceTutorWebRtcPort,
    private val controlClaims: VoiceTutorControlClaimPort,
    private val cleanup: VoiceTutorWebRtcCleanupPort,
    private val properties: BuddyStudyProperties,
    private val clock: Clock = Clock.systemUTC(),
    private val studyContexts: VoiceTutorStudyContextPort = UnavailableVoiceTutorStudyContextPort,
) : VoiceTutorWebRtcUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun negotiate(
        principal: Principal,
        sessionId: String,
        offerSdp: String,
    ): VoiceTutorWebRtcAnswer {
        val registered = registered(principal)
        val id = requiredSessionId(sessionId)
        val normalizedOffer = validatedVoiceTutorSdpOffer(offerSdp)
        val context = relay.connect(registered, id)
        // Conservative provider-call ceiling anchor: capture before the POST.
        // Response headers/body may arrive seconds later, but a 3600-second
        // provider call is already consuming its fixed lifetime during that wait.
        val providerRequestStartedAt = clock.instant()
        val providerCallId = AtomicReference<String?>()
        val recordProviderCall: suspend (String) -> Unit = { callId ->
            val validatedCallId = callId.takeIf(PROVIDER_CALL_ID::matches)
                ?: error("Voice Tutor provider returned an invalid WebRTC call ID.")
            check(
                providerCallId.compareAndSet(null, validatedCallId) ||
                    providerCallId.get() == validatedCallId,
            ) {
                "Voice Tutor provider returned multiple WebRTC call IDs."
            }
            val markerNow = clock.instant()
            withContext(NonCancellable) {
                cleanup.recordPending(
                    callId = validatedCallId,
                    userId = registered.userId,
                    sessionId = id,
                    recoverAfter = markerNow.plusSeconds(
                        properties.voiceTutor.connectTimeoutSeconds.coerceIn(5, 300),
                    ),
                    now = markerNow,
                    providerRequestStartedAt = providerRequestStartedAt,
                )
            }
        }
        try {
            val answer = realtime.negotiate(
                VoiceTutorRealtimeRequest(
                    userId = registered.userId,
                    model = context.session.model,
                    voice = context.session.voice,
                    instructions = context.instructions,
                    language = context.session.language,
                ),
                normalizedOffer,
                recordProviderCall,
            )
            if (providerCallId.get() == null) {
                recordProviderCall(answer.callId)
            }
            check(providerCallId.get() == answer.callId) {
                "Voice Tutor provider answer did not match the created WebRTC call."
            }
            if (!cleanup.attachSession(answer.callId, registered.userId, id, clock.instant())) {
                throw ApiException(
                    HttpStatus.CONFLICT,
                    ApiErrorCode.VOICE_TUTOR_SESSION_CONFLICT,
                    "Voice Tutor provider connection is no longer active.",
                )
            }
            return answer
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                providerCallId.get()?.let { callId ->
                    val providerEnded = runCatching { realtime.hangup(callId) }.isSuccess
                    if (providerEnded) {
                        runCatching { cleanup.complete(callId) }
                    } else {
                        val retryNow = clock.instant()
                        runCatching {
                            cleanup.recordPending(
                                callId = callId,
                                userId = registered.userId,
                                sessionId = id,
                                recoverAfter = retryNow,
                                now = retryNow,
                                providerRequestStartedAt = providerRequestStartedAt,
                            )
                        }.onFailure { markerError ->
                            logger.error(
                                "voice_tutor_webrtc_cleanup_marker_retry_failed sessionId={} errorType={}",
                                id,
                                markerError.javaClass.simpleName,
                            )
                        }
                    }
                }
                runCatching {
                    relay.finish(
                        principal = registered,
                        sessionId = id,
                        reason = "WEBRTC_NEGOTIATION_FAILED",
                        failed = true,
                        failureMessage = "Realtime WebRTC negotiation failed.",
                    )
                }
            }
            if (error is CancellationException) throw error
            if (error is ApiException) throw error
            throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE,
                "Voice Tutor WebRTC negotiation failed.",
            )
        }
    }

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun claimControl(
        principal: Principal,
        sessionId: String,
        connectionId: String,
    ): VoiceTutorWebRtcControlContext {
        val registered = registered(principal)
        val id = requiredSessionId(sessionId)
        val normalizedConnectionId = connectionId.takeIf(CONNECTION_ID::matches)
            ?: throw validation("Invalid Voice Tutor control connection ID.")
        if (!relay.relayAuthorized(registered)) throw conflict()
        val now = clock.instant()
        val session = persistence.findSession(registered.userId, id) ?: throw notFound()
        if (session.status != VoiceTutorSessionStatus.ACTIVE) throw conflict()
        val callId = session.providerSessionId?.takeIf(PROVIDER_CALL_ID::matches) ?: throw conflict()
        if (!now.isBefore(session.hardEndsAt)) throw conflict()
        // Resolve before acquiring the claim: failed context storage must not
        // leave behind an otherwise attachable control connection reservation.
        val lessonRevision = studyContexts.currentRevision(registered.userId, id)
        check(lessonRevision >= 0) { "Voice Tutor lesson revision was invalid." }
        val initialStudyMutationSnapshot = studyContexts.initialMutationSnapshot(
            registered.userId,
            id,
            VoiceTutorInitialStudyMutationSnapshot.MAX_CANDIDATES,
        )
        if (!controlClaims.claim(registered.userId, id, normalizedConnectionId, now)) throw conflict()
        return VoiceTutorWebRtcControlContext(
            session = session,
            callId = callId,
            principal = registered,
            initialLessonRevision = lessonRevision,
            initialStudyMutationSnapshot = initialStudyMutationSnapshot,
        )
    }

    override suspend fun relaySideband(
        context: VoiceTutorWebRtcControlContext,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
    ) = realtime.relaySideband(context, clientEvents, terminalEvents, onProviderEvent)

    override suspend fun hangup(callId: String) {
        if (PROVIDER_CALL_ID.matches(callId)) realtime.hangup(callId)
    }

    private fun registered(principal: Principal): Principal {
        if (principal.anonymous) throw ApiException(
            HttpStatus.FORBIDDEN,
            ApiErrorCode.ACCOUNT_FORBIDDEN,
            "A registered account is required for Voice Tutor.",
        )
        return principal
    }

    private fun requiredSessionId(value: String): String = value.trim().takeIf(SESSION_ID::matches)
        ?: throw validation("Invalid Voice Tutor session ID.")

    private fun validation(message: String) =
        ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)

    private fun conflict() = ApiException(
        HttpStatus.CONFLICT,
        ApiErrorCode.VOICE_TUTOR_SESSION_CONFLICT,
        "Voice Tutor control connection is not attachable.",
    )

    private fun notFound() = ApiException(
        HttpStatus.NOT_FOUND,
        ApiErrorCode.RESOURCE_NOT_FOUND,
        "Voice Tutor session was not found.",
    )

    private companion object {
        val SESSION_ID = Regex("[0-9a-fA-F-]{36}")
        val CONNECTION_ID = Regex("[0-9a-fA-F-]{36}")
        val PROVIDER_CALL_ID = Regex("rtc_[A-Za-z0-9_-]{1,187}")
    }
}

internal fun validatedVoiceTutorSdpOffer(value: String): String {
    val offer = value
    val lines = offer.split('\n').map { it.removeSuffix("\r") }
    val mediaTypes = lines.filter { it.startsWith("m=") }
        .map { it.substringAfter("m=").substringBefore(' ') }
    if (
        offer.isBlank() ||
        offer.toByteArray(Charsets.UTF_8).size > 65_536 ||
        '\u0000' in offer ||
        lines.size > 512 ||
        lines.any { it.length > 4_096 || '\r' in it } ||
        lines.firstOrNull() != "v=0" ||
        mediaTypes.count { it == "audio" } != 1 ||
        mediaTypes.any { it != "audio" } ||
        lines.none { SDP_FINGERPRINT.matches(it) } ||
        lines.none { it.startsWith("a=ice-ufrag:") && it.length > "a=ice-ufrag:".length } ||
        lines.none { it.startsWith("a=ice-pwd:") && it.length > "a=ice-pwd:".length }
    ) {
        throw ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ApiErrorCode.VALIDATION_ERROR,
            "Voice Tutor SDP offer is invalid.",
        )
    }
    return offer
}

private val SDP_FINGERPRINT = Regex(
    "a=fingerprint:sha-256(?: [0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31})",
)
