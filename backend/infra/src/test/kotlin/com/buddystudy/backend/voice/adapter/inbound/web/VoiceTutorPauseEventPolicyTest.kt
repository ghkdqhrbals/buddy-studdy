package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceTutorPauseEventPolicyTest {
    private val mapper = JsonMapperProvider.mapper
    private val policy = VoiceTutorRealtimeEventPolicy()

    @Test
    fun `pause commands are local app controls and never forwarded to the provider`() {
        for (type in listOf(
            VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT,
            VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT,
            VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT,
        )) {
            assertThat(policy.shouldForwardClientEvent(raw(type, "sequence" to 1))).isFalse()
        }
    }

    @Test
    fun `pause does not grant a client permission to clear input or interrupt teacher output`() {
        for (type in listOf(
            "input_audio_buffer.clear", "output_audio_buffer.clear", "response.cancel", "response.create",
            "session.update", "conversation.item.truncate",
        )) {
            assertThatThrownBy { policy.shouldForwardClientEvent(raw(type)) }
                .isInstanceOf(VoiceTutorClientProtocolException::class.java)
        }
    }

    @Test
    fun `server pause acknowledgement is strictly typed and strips unrelated fields`() {
        val result = policy.providerDecision(
            raw(VoiceTutorRealtimeContract.PAUSE_STATE_EVENT,
                "sequence" to 7, "paused" to true, "quotaSeconds" to 99_999, "providerBody" to "not-client-data"),
            "owned-session", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
        )
        assertThat(result.terminate).isFalse()
        val body = mapper.readTree(result.payload)
        assertThat(body.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("type", "sequence", "paused")
        assertThat(body.path("sequence").asLong()).isEqualTo(7)
        assertThat(body.path("paused").asBoolean()).isTrue()
    }

    @Test
    fun `malformed or legacy acknowledgements cannot become paused client state`() {
        val invalid = listOf(
            raw(VoiceTutorRealtimeContract.PAUSE_STATE_EVENT, "sequence" to true, "paused" to true),
            raw(VoiceTutorRealtimeContract.PAUSE_STATE_EVENT, "sequence" to "1", "paused" to true),
            raw(VoiceTutorRealtimeContract.PAUSE_STATE_EVENT, "sequence" to 1.5, "paused" to true),
            raw(VoiceTutorRealtimeContract.PAUSE_STATE_EVENT, "sequence" to 0, "paused" to true),
            raw(VoiceTutorRealtimeContract.PAUSE_STATE_EVENT, "sequence" to 1, "paused" to "true"),
            raw(VoiceTutorRealtimeContract.PAUSE_STATE_EVENT, "sequence" to 1, "paused" to 1),
            raw(VoiceTutorRealtimeContract.PAUSE_STATE_EVENT, "sequence" to 1),
        )
        for (event in invalid) {
            val decision = policy.providerDecision(event, "owned-session", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
            assertThat(decision.payload).isNull()
            assertThat(decision.terminate).isFalse()
        }
        val legacy = policy.providerDecision(
            raw(VoiceTutorRealtimeContract.PAUSE_STATE_EVENT, "sequence" to 1, "paused" to true),
            "owned-session", Instant.EPOCH, VoiceTutorProviderTransport.LEGACY_PCM_RELAY,
        )
        assertThat(legacy.payload).isNull()
    }

    @Test
    fun `the pause speech-stop and quiescence sequence fits existing bounded controls and keeps heartbeat separate`() {
        val guard = VoiceTutorClientTrafficGuard(policy, maxSessionSeconds = 3_600, nanoTime = { 0 })
        val commands = listOf(
            VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT,
            VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT,
            VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
            VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT,
            VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT, // Safe idempotent replay.
            VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT,
        )
        for (type in commands) {
            val decision = guard.inspect(raw(type, "sequence" to 1))
            assertThat(decision.forward).isFalse()
            assertThat(decision.acceptedLocalEvent).isTrue()
            assertThat(decision.acceptedAudioBytes).isZero()
        }
        assertThat(guard.inspect(raw(VoiceTutorRealtimeEventPolicy.CLIENT_HEARTBEAT_EVENT)).acceptedLocalEvent).isTrue()
        assertThat(guard.inspect(raw(VoiceTutorRealtimeEventPolicy.CLIENT_HEARTBEAT_EVENT)).acceptedLocalEvent).isFalse()
    }

    private fun raw(type: String, vararg fields: Pair<String, Any?>): String =
        mapper.writeValueAsString(mapOf("type" to type, *fields))
}
