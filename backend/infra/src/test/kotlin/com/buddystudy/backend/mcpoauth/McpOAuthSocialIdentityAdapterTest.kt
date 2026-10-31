package com.buddystudy.backend.mcpoauth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date

class McpOAuthSocialIdentityAdapterTest {
    private val key = RSAKeyGenerator(2048).keyID("test-key").generate()
    private var token = ""
    private val requests = mutableListOf<String>()
    private val builder = WebClient.builder().exchangeFunction { request ->
        requests += request.url().toString()
        val body = if (request.url().path == "/token") "{\"id_token\":\"$token\"}" else JWKSet(key.toPublicJWK()).toString()
        Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build())
    }
    private val adapter = McpOAuthSocialIdentityAdapter(builder, "https://api.example.com", "google-web", "server-secret", "apple-web")

    @Test
    fun `authorization uses dedicated web audience exact callback state and nonce`() {
        for (provider in listOf("google", "apple")) {
            val url = URI(adapter.authorize(provider, "random-state", "random-nonce"))
            val params = url.rawQuery.split('&').associate { field ->
                val pair = field.split('=', limit = 2); pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8)
            }
            assertThat(params).containsEntry("client_id", "$provider-web")
                .containsEntry("redirect_uri", "https://api.example.com/oauth/social/$provider/callback")
                .containsEntry("state", "random-state").containsEntry("nonce", "random-nonce")
            if (provider == "apple") assertThat(params).containsEntry("response_type", "code id_token").containsEntry("response_mode", "form_post")
            else assertThat(params).containsEntry("response_type", "code").containsEntry("prompt", "select_account")
        }
        assertThat(adapter.providers()).containsExactlyInAnyOrder("apple", "google")
        assertThat(McpOAuthSocialIdentityAdapter(builder, "https://api.example.com", "", "", "").providers()).isEmpty()
        assertThat(McpOAuthSocialIdentityAdapter(builder, "https://api.example.com", "google-web", "", "").providers()).isEmpty()
    }

    @Test
    fun `google exchanges code and verifies signature audience issuer expiry and nonce`(): Unit = runBlocking {
        token = signed()
        assertThat(adapter.verify("google", "authorization-code", "ignored")).isEqualTo(McpOAuthSocialIdentity("existing-user", "nonce"))
        assertThat(requests).contains("https://oauth2.googleapis.com/token", "https://www.googleapis.com/oauth2/v3/certs")
        for (invalid in listOf(signed(audience = "ios-client"), signed(issuer = "https://attacker.example"),
            signed(expires = Instant.now().minusSeconds(600)), signed(nonce = null), signed(wrongSignature = true))) {
            token = invalid
            assertThat(adapter.verify("google", "code", "ignored")).isNull()
        }
    }

    @Test
    fun `apple hybrid response verifies signed token code hash and dedicated web audience`(): Unit = runBlocking {
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest("apple-code".toByteArray()).copyOfRange(0, 16))
        val valid = signed("apple-web", "https://appleid.apple.com", codeHash = hash)
        assertThat(adapter.verify("apple", "apple-code", valid)).isEqualTo(McpOAuthSocialIdentity("existing-user", "nonce"))
        assertThat(adapter.verify("apple", "different-code", valid)).isNull()
        assertThat(adapter.verify("apple", "apple-code", signed("ios-client", "https://appleid.apple.com", codeHash = hash))).isNull()
        assertThat(adapter.verify("apple", "apple-code", signed("apple-web", "https://appleid.apple.com", wrongSignature = true, codeHash = hash))).isNull()
        assertThat(adapter.verify("apple", "", valid)).isNull()
    }

    private fun signed(audience: String = "google-web", issuer: String = "https://accounts.google.com",
        expires: Instant = Instant.now().plusSeconds(300), nonce: String? = "nonce", wrongSignature: Boolean = false, codeHash: String? = null): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("existing-user")
            .issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(expires))
        nonce?.let { claims.claim("nonce", it) }
        codeHash?.let { claims.claim("c_hash", it) }
        val signer = if (wrongSignature) RSAKeyGenerator(2048).keyID("test-key").generate() else key
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build()).apply { sign(RSASSASigner(signer)) }.serialize()
    }
}
