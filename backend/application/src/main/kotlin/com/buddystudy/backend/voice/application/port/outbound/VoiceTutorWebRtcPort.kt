package com.buddystudy.backend.voice.application.port.outbound

import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class VoiceTutorWebRtcAnswer(
    val answerSdp: String,
    val callId: String,
)

/**
 * Server-owned WebRTC negotiation and sideband control boundary.
 *
 * Media flows directly between the iOS peer and the provider. BuddyStudy keeps
 * the standard API key and all response/turn policy on the sideband connection.
 */
interface VoiceTutorWebRtcPort {
    /**
     * [onProviderCallCreated] must run after the provider call ID is validated
     * and before the answer body is consumed. This lets the application arm
     * durable cleanup even when body decoding fails or the caller is cancelled.
     */
    suspend fun negotiate(
        request: VoiceTutorRealtimeRequest,
        offerSdp: String,
        onProviderCallCreated: suspend (callId: String) -> Unit,
    ): VoiceTutorWebRtcAnswer

    /** Callback true means this exact transcript was newly persisted, not merely forwarded. */
    suspend fun relaySideband(
        context: VoiceTutorWebRtcControlContext,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (
            raw: String,
            persist: Boolean,
            forwardToClient: Boolean,
        ) -> Boolean,
    )

    suspend fun hangup(callId: String)
}

object UnavailableVoiceTutorWebRtcPort : VoiceTutorWebRtcPort {
    private fun unavailable(): Nothing = error("Voice Tutor WebRTC is not configured.")

    override suspend fun negotiate(
        request: VoiceTutorRealtimeRequest,
        offerSdp: String,
        onProviderCallCreated: suspend (callId: String) -> Unit,
    ): VoiceTutorWebRtcAnswer = unavailable()

    override suspend fun relaySideband(
        context: VoiceTutorWebRtcControlContext,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
    ) = unavailable()

    override suspend fun hangup(callId: String) = Unit
}

interface VoiceTutorControlClaimPort {
    suspend fun claim(
        userId: Long,
        sessionId: String,
        connectionId: String,
        now: java.time.Instant,
    ): Boolean
}

data class VoiceTutorWebRtcCleanupClaim(
    val callId: String,
    val userId: Long,
    val sessionId: String,
    val claimToken: String,
)

/**
 * Durable lifecycle marker for a provider call that already exists outside MySQL.
 * The marker intentionally has no relational foreign key so it survives a failed
 * session attach and remains claimable after a process exit.
 */
interface VoiceTutorWebRtcCleanupPort {
    suspend fun recordPending(
        callId: String,
        userId: Long,
        sessionId: String,
        recoverAfter: Instant,
        now: Instant,
    )

    suspend fun attachSession(
        callId: String,
        userId: Long,
        sessionId: String,
        now: Instant,
    ): Boolean

    suspend fun claimOrphaned(
        limit: Int,
        now: Instant,
        claimLeaseSeconds: Long,
    ): List<VoiceTutorWebRtcCleanupClaim>

    suspend fun complete(callId: String): Boolean
    suspend fun completeClaim(callId: String, claimToken: String): Boolean
    suspend fun retryClaim(callId: String, claimToken: String, error: String, now: Instant): Boolean
}

object UnavailableVoiceTutorWebRtcCleanupPort : VoiceTutorWebRtcCleanupPort {
    override suspend fun recordPending(
        callId: String,
        userId: Long,
        sessionId: String,
        recoverAfter: Instant,
        now: Instant,
    ) = error("Voice Tutor WebRTC cleanup persistence is not configured.")

    override suspend fun attachSession(callId: String, userId: Long, sessionId: String, now: Instant): Boolean = false

    override suspend fun claimOrphaned(
        limit: Int,
        now: Instant,
        claimLeaseSeconds: Long,
    ): List<VoiceTutorWebRtcCleanupClaim> = emptyList()

    override suspend fun complete(callId: String): Boolean = false
    override suspend fun completeClaim(callId: String, claimToken: String): Boolean = false
    override suspend fun retryClaim(callId: String, claimToken: String, error: String, now: Instant): Boolean = false
}
