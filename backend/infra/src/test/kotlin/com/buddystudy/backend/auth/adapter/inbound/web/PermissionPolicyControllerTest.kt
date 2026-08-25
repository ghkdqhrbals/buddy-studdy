package com.buddystudy.backend.auth.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.PermissionEvaluationsResponse
import com.buddystudy.backend.auth.application.model.TermsAgreementCommand
import com.buddystudy.backend.auth.application.model.TermsResponse
import com.buddystudy.backend.auth.application.port.inbound.NotificationPreferenceUseCase
import com.buddystudy.backend.auth.application.port.inbound.PermissionEvaluationUseCase
import com.buddystudy.backend.auth.application.port.inbound.TermsUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono

class PermissionPolicyControllerTest {
    @Test
    fun `terms agreement forwards the exact document version and content hash`(): Unit = runBlocking {
        val terms = RecordingTermsUseCase()
        val controller = PermissionPolicyController(
            terms = terms,
            permissions = mock(PermissionEvaluationUseCase::class.java),
            notificationPreferences = mock(NotificationPreferenceUseCase::class.java),
        )
        val principal = Principal(
            userId = 7,
            deviceId = "device-1",
            sessionId = 11,
            anonymous = false,
            status = "ACTIVE",
        )
        val authentication = UsernamePasswordAuthenticationToken(principal, null)
        val client = WebTestClient.bindToController(controller)
            .webFilter<WebTestClient.ControllerSpec>(
                WebFilter { exchange, chain ->
                    chain.filter(exchange.mutate().principal(Mono.just(authentication)).build())
                },
            )
            .build()

        client.post()
            .uri("/api/v1/terms/agreements")
            .header("User-Agent", "BuddyStudyTests")
            .header("X-App-Version", "1.0.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "type": "PRIVACY_POLICY",
                  "action": "AGREED",
                  "source": "REQUIRED_GATE",
                  "version": "2026-08-25",
                  "contentHash": "sha256:privacy-2026-08-25"
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isOk

        assertThat(terms.command?.version).isEqualTo("2026-08-25")
        assertThat(terms.command?.contentHash).isEqualTo("sha256:privacy-2026-08-25")
    }

    private class RecordingTermsUseCase : TermsUseCase {
        var command: TermsAgreementCommand? = null

        override suspend fun activeTerms(principal: Principal?): List<TermsResponse> = emptyList()

        override suspend fun saveAgreement(
            principal: Principal,
            command: TermsAgreementCommand,
        ): PermissionEvaluationsResponse {
            this.command = command
            return PermissionEvaluationsResponse(emptyList())
        }
    }
}
