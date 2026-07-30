package com.buddystudy.backend.auth.adapter.outbound.apple

import com.buddystudy.backend.auth.application.port.outbound.AppleIdentity
import com.buddystudy.backend.auth.application.port.outbound.AppleIdentityPort
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.stereotype.Component

@Component
class AppleIdentityJwtAdapter(
    @param:Value("\${buddystudy.auth.apple-client-id:io.github.ghkdqhrbals.StudyMate}")
    private val clientId: String,
) : AppleIdentityPort {
    private val decoder = NimbusReactiveJwtDecoder.withJwkSetUri(APPLE_JWK_SET_URI)
        .build()
        .also { jwtDecoder ->
            val issuerValidator = JwtValidators.createDefaultWithIssuer(APPLE_ISSUER)
            val audienceValidator = OAuth2TokenValidator<Jwt> { jwt ->
                if (clientId in jwt.audience) {
                    OAuth2TokenValidatorResult.success()
                } else {
                    OAuth2TokenValidatorResult.failure(
                        OAuth2Error(
                            "invalid_token",
                            "Apple identity token audience does not match this app.",
                            null,
                        ),
                    )
                }
            }
            jwtDecoder.setJwtValidator(
                DelegatingOAuth2TokenValidator(issuerValidator, audienceValidator),
            )
        }

    override suspend fun verify(idToken: String): AppleIdentity? =
        runCatching { decoder.decode(idToken).awaitSingle() }
            .getOrNull()
            ?.let { jwt ->
                val providerId = jwt.subject.takeIf(String::isNotBlank) ?: return@let null
                AppleIdentity(
                    providerId = providerId,
                    email = jwt.getClaimAsString("email").orEmpty(),
                )
            }

    private companion object {
        const val APPLE_ISSUER = "https://appleid.apple.com"
        const val APPLE_JWK_SET_URI = "$APPLE_ISSUER/auth/keys"
    }
}
