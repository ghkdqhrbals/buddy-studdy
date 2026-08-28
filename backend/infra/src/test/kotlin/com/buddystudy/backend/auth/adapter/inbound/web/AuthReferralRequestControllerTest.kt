package com.buddystudy.backend.auth.adapter.inbound.web

import com.buddystudy.backend.auth.adapter.inbound.web.dto.AppleLoginRequest
import com.buddystudy.backend.auth.adapter.inbound.web.dto.DeviceRegisterRequest
import com.buddystudy.backend.auth.adapter.inbound.web.dto.EmailLoginRequest
import com.buddystudy.backend.auth.adapter.inbound.web.dto.EmailVerificationCodeRequest
import com.buddystudy.backend.auth.adapter.inbound.web.dto.GoogleLoginRequest
import com.buddystudy.backend.auth.adapter.inbound.web.dto.PushTokenRequest
import com.buddystudy.backend.auth.application.model.EmailVerificationCodeResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.test.web.reactive.server.WebTestClient

class AuthReferralRequestControllerTest {
    private val auth = CapturingAuthWebPort()
    private val client = WebTestClient.bindToController(AuthController(auth)).build()

    @Test
    fun `apple google and email login requests bind optional referral code`() {
        client.post().uri("/api/v1/auth/apple")
            .header("X-Device-Id", "device")
            .header("X-Client-Secret", "secret")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"idToken":"apple-token","referralCode":"BS-ABCDEFGH"}""")
            .exchange()
            .expectStatus().isOk

        client.post().uri("/api/v1/auth/google")
            .header("X-Device-Id", "device")
            .header("X-Client-Secret", "secret")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"idToken":"google-token","referralCode":"BS-JKLMNPQR"}""")
            .exchange()
            .expectStatus().isOk

        client.post().uri("/api/v1/auth/email")
            .header("X-Device-Id", "device")
            .header("X-Client-Secret", "secret")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"email":"new@example.com","password":"password","verificationCode":"123456","referralCode":"BS-STUVWXYZ"}""",
            )
            .exchange()
            .expectStatus().isOk

        assertThat(auth.appleReferralCode).isEqualTo("BS-ABCDEFGH")
        assertThat(auth.googleReferralCode).isEqualTo("BS-JKLMNPQR")
        assertThat(auth.emailReferralCode).isEqualTo("BS-STUVWXYZ")
    }

    private class CapturingAuthWebPort : AuthWebPort {
        var appleReferralCode: String? = null
        var googleReferralCode: String? = null
        var emailReferralCode: String? = null

        override suspend fun register(body: DeviceRegisterRequest): Any = emptyMap<String, String>()
        override suspend fun token(deviceId: String, clientSecret: String): Any = emptyMap<String, String>()

        override suspend fun apple(
            body: AppleLoginRequest,
            authentication: Authentication?,
            deviceId: String?,
            clientSecret: String?,
        ): Any = mapOf("ok" to true).also { appleReferralCode = body.referralCode }

        override suspend fun google(
            body: GoogleLoginRequest,
            authentication: Authentication?,
            deviceId: String?,
            clientSecret: String?,
        ): Any = mapOf("ok" to true).also { googleReferralCode = body.referralCode }

        override suspend fun emailCode(
            body: EmailVerificationCodeRequest,
            authentication: Authentication?,
            deviceId: String?,
            clientSecret: String?,
        ): EmailVerificationCodeResponse = EmailVerificationCodeResponse(body.email, 180)

        override suspend fun email(
            body: EmailLoginRequest,
            authentication: Authentication?,
            deviceId: String?,
            clientSecret: String?,
        ): Any = mapOf("ok" to true).also { emailReferralCode = body.referralCode }

        override suspend fun logout(authentication: Authentication): ResponseEntity<Unit> = ResponseEntity.noContent().build()
        override suspend fun loggedInDevices(authentication: Authentication): Any = emptyMap<String, String>()
        override suspend fun pushToken(body: PushTokenRequest, authentication: Authentication): ResponseEntity<Unit> =
            ResponseEntity.noContent().build()
    }
}
