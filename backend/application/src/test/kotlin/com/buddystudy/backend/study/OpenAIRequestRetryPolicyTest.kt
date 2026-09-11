package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.openai.OpenAIRequestFailure
import com.buddystudy.backend.study.application.openai.OpenAIRequestRetryPolicy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CancellationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.io.IOException

class OpenAIRequestRetryPolicyTest {
    @Test
    fun `exact permanent provider metadata overrides nested network exceptions`() {
        assertThat(OpenAIRequestRetryPolicy.isRetryable(OpenAIRequestFailure(false, IOException("raw provider detail")))).isFalse()
        assertThat(OpenAIRequestRetryPolicy.isRetryable(OpenAIRequestFailure(true, IOException("connection")))).isTrue()
        assertThat(OpenAIRequestRetryPolicy.isRetryable(OpenAIRequestFailure(true, CancellationException()))).isFalse()
        assertThat(OpenAIRequestRetryPolicy.isRetryable(ApiException(HttpStatus.SERVICE_UNAVAILABLE,
            ApiErrorCode.VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED, "generic display text"))).isFalse()
    }

    @Test
    fun `transport failures retry but malformed output unknown errors and permanent statuses do not`() {
        assertThat(OpenAIRequestRetryPolicy.isRetryable(IOException("network"))).isTrue()
        assertThat(OpenAIRequestRetryPolicy.isRetryable(HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT))).isTrue()
        for (status in listOf(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND)) {
            assertThat(OpenAIRequestRetryPolicy.isRetryable(HttpClientErrorException(status))).isFalse()
        }
        val malformed = runCatching { jacksonObjectMapper().readTree("not json") }.exceptionOrNull()!!
        assertThat(OpenAIRequestRetryPolicy.isRetryable(malformed)).isFalse()
        assertThat(OpenAIRequestRetryPolicy.isRetryable(IllegalArgumentException("insufficient_quota"))).isFalse()
        assertThat(OpenAIRequestRetryPolicy.isRetryable(IllegalStateException("503 try again"))).isFalse()
    }
}
