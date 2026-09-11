package com.buddystudy.backend

import com.buddystudy.backend.common.adapter.inbound.web.ApiErrorResponseFactory
import com.buddystudy.backend.common.application.error.ApiErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

/** Resource/envelope coverage only: no Spring context, provider, database or account. */
class VoiceTutorProviderErrorMessagesTest {
    @Test
    fun `provider budget error has a distinct stable code and localized service owned recovery guidance`() {
        val code = ApiErrorCode.VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED
        assertThat(code.code).isEqualTo(516)
        assertThat(ApiErrorCode.entries.filter { it.code == code.code }).containsExactly(code)
        assertThat(code.code).isNotEqualTo(ApiErrorCode.VOICE_TUTOR_QUOTA_EXCEEDED.code)
        assertThat(code.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        val source = ResourceBundleMessageSource().apply {
            setBasename("messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
        }
        val factory = ApiErrorResponseFactory(source)
        val languages = listOf(
            Triple("ko", "서비스의 AI 제공업체", "고객지원"),
            Triple("en", "service's AI provider", "contact support"),
            Triple("ja", "サービスのAI提供元", "サポート"),
        )
        val localized = languages.map { (language, owner, recovery) ->
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/voice-tutor/sessions/test/webrtc")
                .header("Accept-Language", language).build())
            val error = factory.envelope(code, code.status, exchange).error
            assertThat(error.errorCode).isEqualTo("VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED")
            assertThat(error.code).isEqualTo(516)
            assertThat(error.messageKey).isEqualTo(code.messageKey)
            assertThat(error.message).contains(owner, recovery)
            assertThat(error.message).doesNotContain("API key", "API 키", "APIキー", "Pro", "멤버십", "membership")
            error.message
        }
        assertThat(localized.toSet()).hasSize(3)
    }
}
