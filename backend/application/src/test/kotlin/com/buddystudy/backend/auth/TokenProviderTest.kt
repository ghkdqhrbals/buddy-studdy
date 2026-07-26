package com.buddystudy.backend.auth

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class TokenProviderTest {
    @Test
    fun `validate returns true for a signed non expired token`(): Unit = runBlocking {
        val provider = tokenProvider()
        val token = provider.create(userId = 7, deviceId = "dev-1", sessionId = 11, anonymous = false, status = "ACTIVE").first

        assertThat(provider.validate(token)).isTrue()
    }

    @Test
    fun `validate returns false for a malformed token`(): Unit = runBlocking {
        val provider = tokenProvider()

        assertThat(provider.validate("not-a-token")).isFalse()
    }

    @Test
    fun `validate returns false for a token with a tampered signature`(): Unit = runBlocking {
        val provider = tokenProvider()
        val token = provider.create(userId = 7, deviceId = "dev-1", sessionId = 11, anonymous = false, status = "ACTIVE").first
        val parts = token.split(".")
        val signature = parts[2]
        val tamperedSignature = (if (signature.first() == 'a') 'b' else 'a') + signature.drop(1)
        val tampered = "${parts[0]}.${parts[1]}.$tamperedSignature"

        assertThat(provider.validate(tampered)).isFalse()
    }

    @Test
    fun `parse returns principal from a valid token`(): Unit = runBlocking {
        val provider = tokenProvider()
        val token = provider.create(userId = 7, deviceId = "dev-1", sessionId = 11, anonymous = false, status = "ACTIVE").first

        assertThat(provider.parse(token)).isEqualTo(
            Principal(userId = 7, deviceId = "dev-1", sessionId = 11, anonymous = false),
        )
    }

    @Test
    fun `created token does not contain permission claims`(): Unit = runBlocking {
        val provider = tokenProvider()
        val token = provider.create(userId = 7, deviceId = "dev-1", sessionId = 11, anonymous = false, status = "ACTIVE").first

        val payload = String(Base64.getUrlDecoder().decode(token.split(".")[1]))

        assertThat(payload).contains("\"user_id\":7")
        assertThat(payload).contains("\"device_id\"")
        assertThat(payload).doesNotContain("permission")
    }

    @Test
    fun `parse throws unified auth error for an invalid token`(): Unit = runBlocking {
        val provider = tokenProvider()

        assertThatThrownBy { provider.parse("not-a-token") }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN)
    }

    private fun tokenProvider(): TokenProvider {
        val properties = BuddyStudyProperties()
        properties.auth.jwtSecret = "test-jwt-secret"
        properties.auth.accessTokenDays = 90
        properties.crypto.masterKey = "test-master-key"
        return TokenProvider(properties)
    }
}
