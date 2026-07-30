package com.buddystudy.backend.auth.application.port.outbound

data class AppleIdentity(
    val providerId: String,
    val email: String,
)

interface AppleIdentityPort {
    suspend fun verify(idToken: String): AppleIdentity?
}
