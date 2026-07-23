package com.buddystudy.backend.auth.application.port.outbound

data class GoogleIdentity(
    val providerId: String,
    val email: String,
    val name: String?,
)

interface GoogleIdentityPort {
    suspend fun verify(idToken: String): GoogleIdentity?
}
