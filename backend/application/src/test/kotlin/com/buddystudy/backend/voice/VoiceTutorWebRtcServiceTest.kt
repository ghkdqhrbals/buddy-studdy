package com.buddystudy.backend.voice

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorInitialStudyMutationSnapshot
import com.buddystudy.backend.voice.application.model.VoiceTutorRelayContext
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRelayUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorControlClaimPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcAnswer
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcCleanupClaim
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcCleanupPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcPort
import com.buddystudy.backend.voice.application.service.VoiceTutorWebRtcService
import com.buddystudy.backend.voice.application.service.validatedVoiceTutorSdpOffer
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class VoiceTutorWebRtcServiceTest {
    private val now = Instant.parse("2026-08-30T00:00:00Z")
    private val principal = Principal(7, "device-7", 70, anonymous = false)

    @Test
    fun `valid SDP offer must contain audio media and a DTLS fingerprint`() {
        val fingerprint = List(32) { "00" }.joinToString(":")
        val offer = """
            v=0
            o=- 1 1 IN IP4 127.0.0.1
            s=-
            t=0 0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=ice-ufrag:test
            a=ice-pwd:test-password
            a=fingerprint:sha-256 $fingerprint
        """.trimIndent()

        assertThat(validatedVoiceTutorSdpOffer(offer)).isEqualTo(offer)
    }

    @Test
    fun `SDP without an audio fingerprint fails before claiming quota relay`() {
        val error = runCatching { validatedVoiceTutorSdpOffer("v=0\ns=-\nt=0 0") }.exceptionOrNull()

        assertThat(error).isInstanceOf(ApiException::class.java)
        assertThat((error as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
    }

    @Test
    fun `oversized SDP offer is rejected`() {
        val oversized = "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\na=ice-ufrag:test\na=ice-pwd:test\n" +
            "a".repeat(65_536)

        assertThatThrownBy { validatedVoiceTutorSdpOffer(oversized) }
            .isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `SDP data channel is rejected so provider controls remain sideband only`() {
        val fingerprint = List(32) { "00" }.joinToString(":")
        val offer = """
            v=0
            o=- 1 1 IN IP4 127.0.0.1
            s=-
            t=0 0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=ice-ufrag:test
            a=ice-pwd:test-password
            a=fingerprint:sha-256 $fingerprint
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            a=sctp-port:5000
        """.trimIndent()

        assertThatThrownBy { validatedVoiceTutorSdpOffer(offer) }
            .isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `invalid SDP is rejected before claiming the quota relay`() = runBlocking<Unit> {
        val calls = Calls()
        val service = service(calls = calls)

        val failure = runCatching {
            service.negotiate(principal, SESSION_ID, "v=0\ns=-\nt=0 0")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThat(calls.connect).isZero()
    }

    @Test
    fun `negotiated provider call is durably marked before its session is atomically attached`() = runBlocking<Unit> {
        val calls = Calls()
        val cleanup = FakeCleanup(calls)
        val service = service(
            calls = calls,
            connectContext = VoiceTutorRelayContext(activeSession().copy(providerSessionId = null), "instructions"),
            negotiatedAnswer = VoiceTutorWebRtcAnswer("answer-sdp", "rtc_new-call"),
            cleanup = cleanup,
        )

        val answer = service.negotiate(principal, SESSION_ID, validSdp())

        assertThat(answer.callId).isEqualTo("rtc_new-call")
        assertThat(calls.lifecycle).containsExactly("provider-negotiate", "marker-record", "session-attach")
        assertThat(cleanup.recordedCallId).isEqualTo("rtc_new-call")
        assertThat(cleanup.recordedUserId).isEqualTo(principal.userId)
        assertThat(cleanup.recordedSessionId).isEqualTo(SESSION_ID)
        assertThat(cleanup.recoverAfter).isEqualTo(now.plusSeconds(15))
    }

    @Test
    fun `WebRTC negotiation forwards the connected session language independently of instruction language`(): Unit = runBlocking {
        for ((language, instructions) in listOf(
            "ko" to "Tutor instructions written in English.",
            "en" to "한국어로 작성된 선생님 지시문입니다.",
            "ja" to "Tutor instructions written in English.",
        )) {
            val calls = Calls()
            val context = VoiceTutorRelayContext(
                activeSession().copy(language = language, providerSessionId = null),
                instructions,
            )
            val service = service(
                calls = calls,
                connectContext = context,
                negotiatedAnswer = VoiceTutorWebRtcAnswer("answer-sdp", "rtc_language-$language"),
            )

            service.negotiate(principal, SESSION_ID, validSdp())

            val request = requireNotNull(calls.negotiationRequest)
            assertThat(request.language).isEqualTo(language)
            assertThat(request.instructions).isEqualTo(instructions)
            assertThat(request.userId).isEqualTo(principal.userId)
            assertThat(request.model).isEqualTo(context.session.model)
            assertThat(request.voice).isEqualTo(context.session.voice)
            assertThat(calls.connect).isEqualTo(1)
        }
    }

    @Test
    fun `invalid provider SDP after call creation hangs up and removes its durable marker`() = runBlocking<Unit> {
        val calls = Calls()
        val cleanup = FakeCleanup(calls)
        val service = service(
            calls = calls,
            connectContext = VoiceTutorRelayContext(activeSession().copy(providerSessionId = null), "instructions"),
            negotiatedAnswer = VoiceTutorWebRtcAnswer("invalid-answer", "rtc_invalid-sdp"),
            negotiationFailure = IllegalArgumentException("provider SDP was invalid"),
            cleanup = cleanup,
        )

        val failure = runCatching { service.negotiate(principal, SESSION_ID, validSdp()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
        assertThat(calls.lifecycle).containsExactly(
            "provider-negotiate",
            "marker-record",
            "provider-hangup",
        )
        assertThat(cleanup.completedCalls).containsExactly("rtc_invalid-sdp")
        assertThat(calls.finish).isEqualTo(1)
    }

    @Test
    fun `provider body cancellation preserves its identity and marker when immediate hangup fails`() = runBlocking<Unit> {
        val calls = Calls()
        val cleanup = FakeCleanup(calls)
        val cancellation = CancellationException("provider SDP body was cancelled")
        val service = service(
            calls = calls,
            connectContext = VoiceTutorRelayContext(activeSession().copy(providerSessionId = null), "instructions"),
            negotiatedAnswer = VoiceTutorWebRtcAnswer("unused-answer", "rtc_cancelled-body"),
            negotiationFailure = cancellation,
            cleanup = cleanup,
            hangupFails = true,
        )

        val failure = runCatching { service.negotiate(principal, SESSION_ID, validSdp()) }.exceptionOrNull()

        assertThat(failure).isSameAs(cancellation)
        assertThat(calls.lifecycle).containsExactly(
            "provider-negotiate",
            "marker-record",
            "provider-hangup",
            "marker-record",
        )
        assertThat(cleanup.recordAttempts).isEqualTo(2)
        assertThat(cleanup.recordedCallId).isEqualTo("rtc_cancelled-body")
        assertThat(cleanup.recoverAfter).isEqualTo(now)
        assertThat(cleanup.completedCalls).isEmpty()
        assertThat(calls.finish).isEqualTo(1)
    }

    @Test
    fun `failed attach keeps the durable marker when immediate provider hangup also fails`() = runBlocking<Unit> {
        val calls = Calls()
        val cleanup = FakeCleanup(calls, attachResult = false)
        val service = service(
            calls = calls,
            connectContext = VoiceTutorRelayContext(activeSession().copy(providerSessionId = null), "instructions"),
            negotiatedAnswer = VoiceTutorWebRtcAnswer("answer-sdp", "rtc_orphan-call"),
            cleanup = cleanup,
            hangupFails = true,
        )

        val failure = runCatching { service.negotiate(principal, SESSION_ID, validSdp()) }.exceptionOrNull()

        assertConflict(failure)
        assertThat(calls.lifecycle).containsExactly(
            "provider-negotiate",
            "marker-record",
            "session-attach",
            "provider-hangup",
            "marker-record",
        )
        assertThat(calls.finish).isEqualTo(1)
        assertThat(cleanup.completedCalls).isEmpty()
        assertThat(cleanup.recordedCallId).isEqualTo("rtc_orphan-call")
    }

    @Test
    fun `failed initial marker write is retried when immediate provider hangup also fails`() = runBlocking<Unit> {
        val calls = Calls()
        val cleanup = FakeCleanup(calls, recordFailuresRemaining = 1)
        val service = service(
            calls = calls,
            connectContext = VoiceTutorRelayContext(activeSession().copy(providerSessionId = null), "instructions"),
            negotiatedAnswer = VoiceTutorWebRtcAnswer("answer-sdp", "rtc_marker-retry"),
            cleanup = cleanup,
            hangupFails = true,
        )

        val failure = runCatching { service.negotiate(principal, SESSION_ID, validSdp()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
        assertThat(calls.lifecycle).containsExactly(
            "provider-negotiate",
            "marker-record",
            "provider-hangup",
            "marker-record",
        )
        assertThat(cleanup.recordAttempts).isEqualTo(2)
        assertThat(cleanup.recordedCallId).isEqualTo("rtc_marker-retry")
        assertThat(cleanup.recoverAfter).isEqualTo(now)
    }

    @Test
    fun `revoked device cannot look up or claim a WebRTC control session`() = runBlocking<Unit> {
        val calls = Calls()
        val service = service(authorized = false, calls = calls)

        val failure = runCatching {
            service.claimControl(principal, SESSION_ID, CONNECTION_ID)
        }.exceptionOrNull()

        assertConflict(failure)
        assertThat(calls.authorize).isEqualTo(1)
        assertThat(calls.findSession).isZero()
        assertThat(calls.claim).isZero()
        assertThat(calls.heartbeat).isZero()
    }

    @Test
    fun `control session lookup remains owner scoped`() = runBlocking<Unit> {
        val calls = Calls()
        val service = service(session = null, calls = calls)

        val failure = runCatching {
            service.claimControl(principal, SESSION_ID, CONNECTION_ID)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.RESOURCE_NOT_FOUND)
        assertThat(calls.findSessionUserId).isEqualTo(principal.userId)
        assertThat(calls.findSessionId).isEqualTo(SESSION_ID)
        assertThat(calls.claim).isZero()
    }

    @Test
    fun `duplicate control claim fails without renewing the relay heartbeat`() = runBlocking<Unit> {
        val calls = Calls()
        val service = service(claimed = false, calls = calls)

        val failure = runCatching {
            service.claimControl(principal, SESSION_ID, CONNECTION_ID)
        }.exceptionOrNull()

        assertConflict(failure)
        assertThat(calls.claim).isEqualTo(1)
        assertThat(calls.claimUserId).isEqualTo(principal.userId)
        assertThat(calls.claimSessionId).isEqualTo(SESSION_ID)
        assertThat(calls.claimConnectionId).isEqualTo(CONNECTION_ID)
        assertThat(calls.claimedAt).isEqualTo(now)
        assertThat(calls.heartbeat).isZero()
    }

    @Test
    fun `successful claim returns only the validated persisted provider call`() = runBlocking<Unit> {
        val calls = Calls()
        val expected = activeSession()
        val service = service(session = expected, calls = calls)

        val context = service.claimControl(principal, SESSION_ID, CONNECTION_ID)

        assertThat(context.session).isEqualTo(expected)
        assertThat(context.callId).isEqualTo("rtc_call-1")
        assertThat(context.principal).isSameAs(principal)
        assertThat(context.initialLessonRevision).isZero()
        assertThat(calls.claim).isEqualTo(1)
        assertThat(calls.heartbeat).isZero()
    }

    @Test
    fun `control reattachment restores only the authenticated sessions current lesson revision`(): Unit = runBlocking {
        val calls = Calls()
        val lookups = mutableListOf<Pair<Long, String>>()
        val contexts = proxy<VoiceTutorStudyContextPort> { method, args ->
            when (method) {
                "currentRevision" -> {
                    lookups += (args[0] as Long) to (args[1] as String)
                    7L
                }
                "initialMutationSnapshot" -> null
                else -> error("Unexpected VoiceTutorStudyContextPort call: $method")
            }
        }

        val context = service(calls = calls, studyContexts = contexts)
            .claimControl(principal, SESSION_ID, CONNECTION_ID)

        assertThat(context.initialLessonRevision).isEqualTo(7)
        assertThat(lookups).containsExactly(principal.userId to SESSION_ID)
        assertThat(calls.claim).isEqualTo(1)
        assertThat(calls.connect).isZero()
        assertThat(calls.heartbeat).isZero()
    }

    @Test
    fun `fresh active control claim forwards the complete bounded owner mutation snapshot`(): Unit = runBlocking {
        val calls = Calls()
        val expected = VoiceTutorInitialStudyMutationSnapshot(
            listOf(
                VoiceTutorStudyTargetCandidate(11, null, "Redis", 5),
                VoiceTutorStudyTargetCandidate(12, 11, "Eviction", 7),
            ),
        )
        val lookups = mutableListOf<Triple<String, Long, String>>()
        var requestedMaximum: Int? = null
        val contexts = proxy<VoiceTutorStudyContextPort> { method, args ->
            when (method) {
                "currentRevision" -> {
                    lookups += Triple(method, args[0] as Long, args[1] as String)
                    0L
                }
                "initialMutationSnapshot" -> {
                    lookups += Triple(method, args[0] as Long, args[1] as String)
                    requestedMaximum = args[2] as Int
                    expected
                }
                else -> error("Unexpected VoiceTutorStudyContextPort call: $method")
            }
        }

        val context = service(calls = calls, studyContexts = contexts)
            .claimControl(principal, SESSION_ID, CONNECTION_ID)

        assertThat(context.initialStudyMutationSnapshot).isSameAs(expected)
        assertThat(requestedMaximum).isEqualTo(VoiceTutorInitialStudyMutationSnapshot.MAX_CANDIDATES)
        assertThat(lookups).containsExactly(
            Triple("currentRevision", principal.userId, SESSION_ID),
            Triple("initialMutationSnapshot", principal.userId, SESSION_ID),
        )
        assertThat(calls.claim).isEqualTo(1)
    }

    @Test
    fun `resumed or overflowed control claim preserves an absent initial mutation snapshot`(): Unit = runBlocking {
        for (reason in listOf("existing USER turn", "owner candidate overflow")) {
            val calls = Calls()
            val contextLookups = mutableListOf<String>()
            val contexts = proxy<VoiceTutorStudyContextPort> { method, _ ->
                contextLookups += method
                when (method) {
                    "currentRevision" -> 3L
                    // The persistence adapter deliberately collapses both unsafe states to null.
                    "initialMutationSnapshot" -> null
                    else -> error("Unexpected VoiceTutorStudyContextPort call for $reason: $method")
                }
            }

            val context = service(calls = calls, studyContexts = contexts)
                .claimControl(principal, SESSION_ID, CONNECTION_ID)

            assertThat(context.initialStudyMutationSnapshot).describedAs(reason).isNull()
            assertThat(contextLookups).containsExactly("currentRevision", "initialMutationSnapshot")
            assertThat(calls.claim).isEqualTo(1)
        }
    }

    @Test
    fun `a failed revision lookup does not acquire a control claim or start provider work`(): Unit = runBlocking {
        val calls = Calls()
        val unavailable = IllegalStateException("Synthetic metadata storage unavailable.")
        val contexts = proxy<VoiceTutorStudyContextPort> { method, _ ->
            when (method) {
                "currentRevision" -> throw unavailable
                else -> error("Unexpected VoiceTutorStudyContextPort call: $method")
            }
        }

        val failure = runCatching {
            service(calls = calls, studyContexts = contexts).claimControl(principal, SESSION_ID, CONNECTION_ID)
        }.exceptionOrNull()

        assertThat(failure).isSameAs(unavailable)
        assertThat(calls.claim).isZero()
        assertThat(calls.connect).isZero()
        assertThat(calls.lifecycle).isEmpty()
    }

    private fun service(
        authorized: Boolean = true,
        session: VoiceTutorSession? = activeSession(),
        claimed: Boolean = true,
        calls: Calls,
        connectContext: VoiceTutorRelayContext? = null,
        negotiatedAnswer: VoiceTutorWebRtcAnswer? = null,
        negotiationFailure: Throwable? = null,
        cleanup: VoiceTutorWebRtcCleanupPort = FakeCleanup(calls),
        hangupFails: Boolean = false,
        studyContexts: VoiceTutorStudyContextPort = UnavailableVoiceTutorStudyContextPort,
    ): VoiceTutorWebRtcService {
        val relay = proxy<VoiceTutorRelayUseCase> { method, _ ->
            when (method) {
                "relayAuthorized" -> {
                    calls.authorize += 1
                    authorized
                }
                "connect" -> {
                    calls.connect += 1
                    connectContext ?: error("A valid negotiation was not expected in this test.")
                }
                "heartbeat" -> {
                    calls.heartbeat += 1
                    VoiceTutorSessionStatus.ACTIVE
                }
                "finish" -> {
                    calls.finish += 1
                    error("Cleanup finish result is not observed by negotiation failure handling.")
                }
                else -> error("Unexpected VoiceTutorRelayUseCase call: $method")
            }
        }
        val persistence = proxy<VoiceTutorPersistencePort> { method, arguments ->
            when (method) {
                "findSession" -> {
                    calls.findSession += 1
                    calls.findSessionUserId = arguments[0] as Long
                    calls.findSessionId = arguments[1] as String
                    session
                }
                else -> error("Unexpected VoiceTutorPersistencePort call: $method")
            }
        }
        val realtime = object : VoiceTutorWebRtcPort {
            override suspend fun negotiate(
                request: VoiceTutorRealtimeRequest,
                offerSdp: String,
                onProviderCallCreated: suspend (callId: String) -> Unit,
            ): VoiceTutorWebRtcAnswer {
                calls.lifecycle += "provider-negotiate"
                calls.negotiationRequest = request
                val answer = negotiatedAnswer ?: error("Unexpected VoiceTutorWebRtcPort negotiation.")
                onProviderCallCreated(answer.callId)
                negotiationFailure?.let { throw it }
                return answer
            }

            override suspend fun relaySideband(
                context: com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext,
                clientEvents: Flow<String>,
                terminalEvents: Flow<VoiceTutorRelayTermination>,
                onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
            ) = error("Unexpected VoiceTutorWebRtcPort sideband relay.")

            override suspend fun hangup(callId: String) {
                calls.lifecycle += "provider-hangup"
                if (hangupFails) error("provider hangup failed")
            }
        }
        val controlClaims = object : VoiceTutorControlClaimPort {
            override suspend fun claim(
                userId: Long,
                sessionId: String,
                connectionId: String,
                now: Instant,
            ): Boolean {
                calls.claim += 1
                calls.claimUserId = userId
                calls.claimSessionId = sessionId
                calls.claimConnectionId = connectionId
                calls.claimedAt = now
                return claimed
            }
        }
        return VoiceTutorWebRtcService(
            relay = relay,
            persistence = persistence,
            realtime = realtime,
            controlClaims = controlClaims,
            cleanup = cleanup,
            properties = BuddyStudyProperties(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            studyContexts = studyContexts,
        )
    }

    private fun validSdp(): String {
        val fingerprint = List(32) { "00" }.joinToString(":")
        return """
            v=0
            o=- 1 1 IN IP4 127.0.0.1
            s=-
            t=0 0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=ice-ufrag:test
            a=ice-pwd:test-password
            a=fingerprint:sha-256 $fingerprint
        """.trimIndent()
    }

    private class FakeCleanup(
        private val calls: Calls,
        private val attachResult: Boolean = true,
        private var recordFailuresRemaining: Int = 0,
    ) : VoiceTutorWebRtcCleanupPort {
        var recordedCallId: String? = null
        var recordedUserId: Long? = null
        var recordedSessionId: String? = null
        var recoverAfter: Instant? = null
        var recordAttempts = 0
        val completedCalls = mutableListOf<String>()

        override suspend fun recordPending(
            callId: String,
            userId: Long,
            sessionId: String,
            recoverAfter: Instant,
            now: Instant,
        ) {
            calls.lifecycle += "marker-record"
            recordAttempts += 1
            if (recordFailuresRemaining > 0) {
                recordFailuresRemaining -= 1
                error("marker write failed")
            }
            recordedCallId = callId
            recordedUserId = userId
            recordedSessionId = sessionId
            this.recoverAfter = recoverAfter
        }

        override suspend fun attachSession(callId: String, userId: Long, sessionId: String, now: Instant): Boolean {
            calls.lifecycle += "session-attach"
            return attachResult
        }

        override suspend fun claimOrphaned(
            limit: Int,
            now: Instant,
            claimLeaseSeconds: Long,
        ): List<VoiceTutorWebRtcCleanupClaim> = emptyList()

        override suspend fun complete(callId: String): Boolean {
            completedCalls += callId
            return true
        }

        override suspend fun completeClaim(callId: String, claimToken: String): Boolean = false
        override suspend fun retryClaim(callId: String, claimToken: String, error: String, now: Instant): Boolean = false
    }

    private fun activeSession(): VoiceTutorSession = VoiceTutorSession(
        id = SESSION_ID,
        userId = principal.userId,
        studyId = 42,
        idempotencyKey = "voice-webrtc-1",
        providerSessionId = "rtc_call-1",
        status = VoiceTutorSessionStatus.ACTIVE,
        resultStatus = VoiceTutorResultStatus.PENDING,
        language = "ko",
        model = "gpt-realtime-test",
        voice = "marin",
        topic = "calculus",
        difficulty = 3,
        periodStartedAt = now,
        periodEndsAt = now.plusSeconds(3_600),
        reservedSeconds = 3_600,
        chargedSeconds = 0,
        maxSessionSeconds = 3_600,
        hardEndsAt = now.plusSeconds(3_600),
        connectedAt = now,
        relayHeartbeatAt = now,
        acceptedAudioBytes = 0,
        endedAt = null,
        finalizedAt = null,
        endReason = null,
        failureCode = null,
        failureMessage = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun assertConflict(failure: Throwable?) {
        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_SESSION_CONFLICT)
    }

    private inline fun <reified T : Any> proxy(
        crossinline invocation: (String, Array<out Any?>) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "${T::class.simpleName}TestProxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> invocation(method.name, arguments ?: emptyArray())
        }
    } as T

    private data class Calls(
        val lifecycle: MutableList<String> = mutableListOf(),
        var negotiationRequest: VoiceTutorRealtimeRequest? = null,
        var authorize: Int = 0,
        var connect: Int = 0,
        var finish: Int = 0,
        var findSession: Int = 0,
        var findSessionUserId: Long? = null,
        var findSessionId: String? = null,
        var claim: Int = 0,
        var claimUserId: Long? = null,
        var claimSessionId: String? = null,
        var claimConnectionId: String? = null,
        var claimedAt: Instant? = null,
        var heartbeat: Int = 0,
    )

    private companion object {
        const val SESSION_ID = "00000000-0000-0000-0000-000000000001"
        const val CONNECTION_ID = "00000000-0000-0000-0000-000000000002"
    }
}
