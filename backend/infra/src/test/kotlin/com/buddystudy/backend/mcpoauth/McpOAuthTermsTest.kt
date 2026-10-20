package com.buddystudy.backend.mcpoauth

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.*
import com.buddystudy.backend.auth.application.port.inbound.TermsUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class McpOAuthTermsTest {
    private val oauth = Mockito.mock(McpOAuthUseCase::class.java)
    private val terms = Mockito.mock(TermsUseCase::class.java)
    private val adapter = McpOAuthTermsWebAdapter(oauth, terms)
    private val principal = Principal(7, "mcp:browser", 0, false)
    private val required = TermsResponse(TermsType.TERMS_OF_SERVICE, "TERMS_OF_SERVICE", "v2", "Terms", "https://example.com/terms", "hash", true, false, false)
    private val privacy = required.copy(type = TermsType.PRIVACY_POLICY, code = "PRIVACY_POLICY", title = "Privacy", contentHash = "privacy-hash")
    private fun identity(term: TermsResponse) = OAuthTermsIdentity(term.code, term.version, term.contentHash, true)

    @Test
    fun `pending policies use verified browser owner and exclude optional and already agreed terms`(): Unit = runBlocking {
        Mockito.`when`(oauth.verifiedBrowserPrincipal("id", "secret")).thenReturn(principal)
        Mockito.`when`(terms.activeTerms(principal)).thenReturn(listOf(required, privacy.copy(agreed = true), required.copy(code = "MARKETING_NOTIFICATION", required = false)))
        assertThat(adapter.pending("id", "secret")).containsExactly(required)
        Mockito.verify(terms).activeTerms(principal)
    }

    @Test
    fun `exact explicit selection records canonical agreement without changing OAuth permissions`(): Unit = runBlocking {
        Mockito.`when`(oauth.verifiedBrowserPrincipal("id", "secret")).thenReturn(principal)
        Mockito.`when`(terms.activeTerms(principal)).thenReturn(listOf(required, privacy), listOf(required.copy(agreed = true), privacy.copy(agreed = true)))
        assertThat(adapter.agree("id", "secret", listOf(identity(required), identity(privacy)), "ip", "agent")).isEmpty()
        for (term in listOf(required, privacy)) Mockito.verify(terms).saveAgreement(principal, TermsAgreementCommand(term.type, "AGREED", "REQUIRED_GATE", term.version, term.contentHash, "ip", "agent"))
        Mockito.verify(oauth, Mockito.times(2)).verifiedBrowserPrincipal("id", "secret")
        Mockito.verifyNoMoreInteractions(oauth)
    }

    @Test
    fun `stale missing optional duplicate or unchecked selection never partially saves agreement`(): Unit = runBlocking {
        Mockito.`when`(oauth.verifiedBrowserPrincipal("id", "secret")).thenReturn(principal)
        Mockito.`when`(terms.activeTerms(principal)).thenReturn(listOf(required, privacy))
        val bad = listOf(emptyList(), listOf(identity(required)), listOf(identity(required), identity(privacy).copy(version = "old")),
            listOf(identity(required), identity(privacy).copy(agreed = false)), listOf(identity(required), identity(privacy), identity(required)),
            listOf(identity(required), identity(privacy), OAuthTermsIdentity("MARKETING_NOTIFICATION", "v2", "hash", true)))
        bad.forEach { selection -> assertThat(runCatching { adapter.agree("id", "secret", selection, null, null) }.isFailure).isTrue() }
        Mockito.verify(terms, Mockito.times(bad.size)).activeTerms(principal)
        Mockito.verifyNoMoreInteractions(terms)
    }

    @Test
    fun `replayed exact selection does not duplicate already accepted agreement`(): Unit = runBlocking {
        Mockito.`when`(oauth.verifiedBrowserPrincipal("id", "secret")).thenReturn(principal)
        Mockito.`when`(terms.activeTerms(principal)).thenReturn(listOf(required.copy(agreed = true)))
        assertThat(adapter.agree("id", "secret", listOf(identity(required)), null, null)).isEmpty()
        Mockito.verify(terms, Mockito.times(2)).activeTerms(principal)
        Mockito.verifyNoMoreInteractions(terms)
    }

    @Test
    fun `policy endpoints require fresh same origin browser cookie and custom intent`(): Unit = runBlocking {
        val port = Mockito.mock(McpOAuthTermsWebPort::class.java)
        val issuer = "https://api.example.com"
        val id = "x".repeat(43)
        val client = WebTestClient.bindToController(McpOAuthTermsController(port, issuer)).webFilter<WebTestClient.ControllerSpec>(McpOAuthSecurityFilter(true)).build()
        for (route in listOf("/oauth/terms", "/oauth/terms/agree")) {
            client.post().uri(route).header(HttpHeaders.ORIGIN, "https://evil.example").header("X-OAuth-Intent", "connect")
                .cookie("__Secure-bs-oauth-$id", "secret").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("id" to id))
                .exchange().expectStatus().isBadRequest
            client.post().uri(route).header(HttpHeaders.ORIGIN, issuer).header("X-OAuth-Intent", "connect")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("id" to id)).exchange().expectStatus().isBadRequest
        }
        Mockito.verifyNoInteractions(port)
        Mockito.`when`(port.pending(id, "secret")).thenReturn(listOf(required))
        client.post().uri("/oauth/terms").header(HttpHeaders.ORIGIN, issuer).header("X-OAuth-Intent", "connect")
            .cookie("__Secure-bs-oauth-$id", "secret").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("id" to id))
            .exchange().expectStatus().isOk.expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$[0].version").isEqualTo("v2")
    }
}
