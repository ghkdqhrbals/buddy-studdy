package com.buddystudy.backend.mcpoauth

import com.buddystudy.backend.auth.Principal
import java.time.Instant

/** Separate from app sessions: an OAuth token is never an app access token. */
data class McpOAuthGrant(
    val id: String,
    val clientId: String,
    val redirectUri: String,
    val state: String,
    val challenge: String,
    val resource: String,
    val scope: String,
    val browserHash: String,
    val expiresAt: Instant,
    val userId: Long? = null,
    val emailCodeHash: String? = null,
    val emailAttempts: Int = 0,
    val verified: Boolean = false,
    val codeHash: String? = null,
    val codeExpiresAt: Instant? = null,
    val accessHash: String? = null,
    val accessExpiresAt: Instant? = null,
    val refreshHash: String? = null,
    val refreshExpiresAt: Instant? = null,
    val revoked: Boolean = false,
    val consentVersion: String? = null,
    val consentedAt: Instant? = null,
    val managementOnly: Boolean = false,
)
data class McpOAuthRequest(
    val clientId: String, val redirectUri: String, val state: String,
    val challenge: String, val resource: String, val scope: String,
    val responseType: String, val challengeMethod: String, val remoteAddress: String = "unknown",
)
data class McpOAuthStart(val id: String, val browserSecret: String, val managementOnly: Boolean = false)
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
data class McpOAuthTokens(
    val access_token: String, val refresh_token: String?,
    val token_type: String = "Bearer", val expires_in: Long = 900,
    val scope: String = "learning:read offline_access",
)
data class McpOAuthResult(val tokens: McpOAuthTokens? = null, val error: String? = null)
data class McpOAuthConnection(val id: String, val clientId: String, val expiresAt: Instant?)

interface McpOAuthUseCase {
    suspend fun start(request: McpOAuthRequest): McpOAuthStart
    suspend fun restart(id: String, browserSecret: String, remoteAddress: String): McpOAuthStart
    suspend fun startManagement(remoteAddress: String): McpOAuthStart
    suspend fun browserScopes(id: String, browserSecret: String): Set<String>
    suspend fun browserConnections(id: String, browserSecret: String): List<McpOAuthConnection>
    suspend fun browserDisconnect(id: String, browserSecret: String, connectionId: String)
    suspend fun sendCode(id: String, browserSecret: String, email: String, remoteAddress: String): Boolean
    suspend fun verifyCode(id: String, browserSecret: String, code: String): Boolean
    suspend fun consent(id: String, browserSecret: String, allow: Boolean): String
    suspend fun token(parameters: Map<String, String>): McpOAuthResult
    suspend fun authenticate(token: String): Principal?
    suspend fun revoke(token: String, clientId: String)
    suspend fun connections(userId: Long): List<McpOAuthConnection>
    suspend fun disconnect(userId: Long, id: String)
    suspend fun cleanup(): Long
}
interface McpOAuthStorePort {
    suspend fun insert(grant: McpOAuthGrant)
    suspend fun find(id: String, lock: Boolean = false): McpOAuthGrant?
    suspend fun save(grant: McpOAuthGrant)
    /** Only existing accounts; ambiguous addresses must fail closed. */
    suspend fun uniqueUserByEmail(email: String): Long?
    suspend fun activeUser(userId: Long): Boolean
    suspend fun list(userId: Long): List<McpOAuthGrant>
    suspend fun wasRefreshUsed(id: String, tokenHash: String): Boolean
    suspend fun rememberRefresh(id: String, tokenHash: String)
    suspend fun allowToken(idHash: String): Boolean
    suspend fun purgeExpired(): Long
    suspend fun allowStart(remoteHash: String): Boolean
    suspend fun allowEmail(emailHash: String, remoteHash: String): Boolean
}
