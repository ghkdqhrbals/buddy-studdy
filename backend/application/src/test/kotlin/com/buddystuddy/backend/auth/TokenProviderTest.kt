package com.buddystuddy.backend.auth

import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TokenProviderTest {
    @Test
    fun `validate returns true for a signed non expired token`() {
        val provider = tokenProvider()
        val token = provider.create(userId = 7, deviceId = "dev-1", sessionId = 11, anonymous = false, status = "ACTIVE").first

        assertThat(provider.validate(token)).isTrue()
    }

    @Test
    fun `validate returns false for a malformed token`() {
        val provider = tokenProvider()

        assertThat(provider.validate("not-a-token")).isFalse()
    }

    @Test
    fun `parse returns principal from a valid token`() {
        val provider = tokenProvider()
        val token = provider.create(userId = 7, deviceId = "dev-1", sessionId = 11, anonymous = false, status = "ACTIVE").first

        assertThat(provider.parse(token)).isEqualTo(
            Principal(userId = 7, deviceId = "dev-1", sessionId = 11, anonymous = false),
        )
    }

    @Test
    fun `parse throws unified auth error for an invalid token`() {
        val provider = tokenProvider()

        assertThatThrownBy { provider.parse("not-a-token") }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN)
    }

    private fun tokenProvider(): TokenProvider {
        val properties = BuddyStuddyProperties()
        properties.auth.jwtSecret = "test-jwt-secret"
        properties.auth.accessTokenDays = 90
        properties.crypto.masterKey = "test-master-key"
        return TokenProvider(properties)
    }
}
