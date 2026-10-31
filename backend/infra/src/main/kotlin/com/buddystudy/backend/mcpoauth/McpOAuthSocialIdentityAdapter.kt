package com.buddystudy.backend.mcpoauth

import io.netty.channel.ChannelOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Browser login is separate from native app login. OAuth credentials must never enter API-history logging. */
@Component
class McpOAuthSocialIdentityAdapter(
    builder: WebClient.Builder,
    @Value("\${buddystudy.mcp.oauth.issuer:https://api.ghkdqhrbals.org}") private val issuer: String,
    @Value("\${buddystudy.mcp.oauth.google-client-id:}") private val googleClientId: String,
    @Value("\${buddystudy.mcp.oauth.google-client-secret:}") private val googleClientSecret: String,
    @Value("\${buddystudy.mcp.oauth.apple-client-id:}") private val appleClientId: String,
) : McpOAuthSocialIdentityPort {
    private val client = builder.clone().clientConnector(ReactorClientHttpConnector(HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000).responseTimeout(Duration.ofSeconds(5)))).build()
    private val googleDecoder by lazy { decoder("https://www.googleapis.com/oauth2/v3/certs", "https://accounts.google.com", googleClientId) }
    private val appleDecoder by lazy { decoder("https://appleid.apple.com/auth/keys", "https://appleid.apple.com", appleClientId) }

    override fun providers(): Set<String> = buildSet {
        if (googleClientId.isNotBlank() && googleClientSecret.isNotBlank()) add("google")
        if (appleClientId.isNotBlank()) add("apple")
    }

    override fun authorize(provider: String, state: String, nonce: String): String {
        require(provider in providers())
        val apple = provider == "apple"
        val parameters = linkedMapOf("client_id" to if (apple) appleClientId else googleClientId,
            "redirect_uri" to callback(provider), "response_type" to if (apple) "code id_token" else "code",
            "scope" to "openid email", "state" to state, "nonce" to nonce)
        if (apple) {
            parameters["scope"] = "email"
            parameters["response_mode"] = "form_post"
        } else parameters["prompt"] = "select_account"
        val endpoint = if (apple) "https://appleid.apple.com/auth/authorize" else "https://accounts.google.com/o/oauth2/v2/auth"
        return endpoint + "?" + parameters.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
    }

    override suspend fun verify(provider: String, code: String, idToken: String): McpOAuthSocialIdentity? {
        if (provider !in providers() || code.isBlank()) return null
        return try {
            val jwt = if (provider == "google") {
                val payload = client.post().uri("https://oauth2.googleapis.com/token")
                    .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("client_id", googleClientId).with("client_secret", googleClientSecret)
                        .with("redirect_uri", callback(provider)).with("code", code))
                    .retrieve().bodyToMono(Map::class.java).timeout(Duration.ofSeconds(8)).awaitSingle()
                googleDecoder.decode(payload["id_token"] as? String ?: return null).timeout(Duration.ofSeconds(8)).awaitSingle()
            } else {
                val verified = appleDecoder.decode(idToken).timeout(Duration.ofSeconds(8)).awaitSingle()
                // Apple's hybrid response binds its code to the signed ID token.
                val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(code.toByteArray(StandardCharsets.US_ASCII)).copyOfRange(0, 16))
                if (verified.getClaimAsString("c_hash") != hash) return null
                verified
            }
            val subject = jwt.subject?.takeIf { it.isNotBlank() } ?: return null
            val nonce = jwt.getClaimAsString("nonce")?.takeIf { it.isNotBlank() } ?: return null
            if (jwt.expiresAt?.isAfter(Instant.now()) != true || jwt.issuedAt == null || jwt.issuedAt!!.isAfter(Instant.now().plusSeconds(60))) return null
            McpOAuthSocialIdentity(subject, nonce)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    private fun callback(provider: String) = "$issuer/oauth/social/$provider/callback"

    private fun decoder(jwks: String, issuer: String, audience: String): NimbusReactiveJwtDecoder =
        NimbusReactiveJwtDecoder.withJwkSetUri(jwks).webClient(client).build().also { decoder ->
            val audienceValidator = OAuth2TokenValidator<Jwt> { jwt ->
                val azp = jwt.getClaimAsString("azp")
                if (audience in jwt.audience && (azp == null || azp == audience) && (jwt.audience.size == 1 || azp == audience))
                    OAuth2TokenValidatorResult.success()
                else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
            }
            decoder.setJwtValidator(DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator))
        }
}
