package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcAnswer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication

class VoiceTutorWebRtcControllerTest {
    private val authentication = UsernamePasswordAuthenticationToken.authenticated("test-user", "", emptyList())

    @Test
    fun `old or unsupported clients cannot create a paid manual turn provider call`() = runBlocking<Unit> {
        val controller = VoiceTutorWebRtcController(object : VoiceTutorWebRtcWebPort {
            override suspend fun negotiate(sessionId: String, offerSdp: String, authentication: Authentication): VoiceTutorWebRtcAnswer =
                error("Capability must be checked before provider negotiation.")
        })
        for (capability in listOf(null, "", "local-vad-v0", "local-vad-v1", "realtime-native-v1,other")) {
            val result = controller.negotiate("test-session", "test-offer", authentication, capability)
            assertThat(result.statusCode).isEqualTo(HttpStatus.UPGRADE_REQUIRED)
            assertThat(result.body).isNull()
            assertThat(result.headers.getFirst("Cache-Control")).isEqualTo("no-store")
            assertThat(result.headers.getFirst(VoiceTutorRealtimeContract.TURN_PROTOCOL_HEADER)).isEqualTo("realtime-native-v1")
        }
    }

    @Test
    fun `capable client retains authenticated sdp negotiation and noncacheable response`() = runBlocking<Unit> {
        var negotiations = 0
        val controller = VoiceTutorWebRtcController(object : VoiceTutorWebRtcWebPort {
            override suspend fun negotiate(sessionId: String, offerSdp: String, authentication: Authentication): VoiceTutorWebRtcAnswer {
                negotiations += 1
                assertThat(sessionId).isEqualTo("test-session")
                assertThat(offerSdp).isEqualTo("test-offer")
                assertThat(authentication).isSameAs(this@VoiceTutorWebRtcControllerTest.authentication)
                return VoiceTutorWebRtcAnswer("test-answer", "private-provider-call")
            }
        })
        val result = controller.negotiate("test-session", "test-offer", authentication, VoiceTutorRealtimeContract.REALTIME_NATIVE_TURN_PROTOCOL)
        assertThat(negotiations).isEqualTo(1)
        assertThat(result.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(result.headers.contentType.toString()).isEqualTo("application/sdp")
        assertThat(result.headers.getFirst("Cache-Control")).isEqualTo("no-store")
        assertThat(result.body).isEqualTo("test-answer").doesNotContain("private-provider-call")
    }
}
