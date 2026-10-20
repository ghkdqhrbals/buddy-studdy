package com.buddystudy.backend.mcpoauth

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.sha256
import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationSenderPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

@Service
class McpOAuthService(
    private val store: McpOAuthStorePort,
    private val mail: EmailVerificationSenderPort,
    @Value("\${buddystudy.mcp.oauth.issuer:https://api.ghkdqhrbals.org}") val issuer: String,
    @Value("\${buddystudy.mcp.oauth.client-id:buddystudy-chatgpt}") private val clientId: String,
    @Value("\${buddystudy.mcp.oauth.redirect-uris:https://chatgpt.com/connector_platform_oauth_redirect}") redirects: String,
) : McpOAuthUseCase {
    private val random = SecureRandom()
    private val redirectUris = redirects.split(',').map(String::trim).toSet()
    val resource = "$issuer/api/v1/mcp"
    init {
        require(URI(issuer).let { it.scheme == "https" && it.host != null && it.rawQuery == null && it.rawFragment == null && it.rawUserInfo == null && it.rawPath.isNullOrEmpty() })
        require(!issuer.endsWith('/'))
        require(redirectUris.all { URI(it).let { uri -> uri.scheme == "https" && uri.host != null && uri.rawFragment == null } })
    }

    @Transactional
    override suspend fun start(request: McpOAuthRequest): McpOAuthStart {
        require(request.clientId == clientId && request.redirectUri in redirectUris) { "invalid_client" }
        require(request.responseType == "code" && request.challengeMethod == "S256") { "invalid_request" }
        require(request.resource == resource) { "invalid_target" }
        require(request.challenge.matches(Regex("[A-Za-z0-9_-]{43}"))) { "invalid_request" }
        require(request.state.length in 1..1024) { "invalid_request" }
        val scopes = request.scope.split(' ').filter(String::isNotEmpty).toSet()
        require("learning:read" in scopes && scopes.all { it in setOf("learning:read", "learning:write", "learning:delete", "offline_access") }) { "invalid_scope" }
        require(store.allowStart(sha256(request.remoteAddress))) { "temporarily_unavailable" }
        val id = secret(); val browser = secret()
        store.insert(McpOAuthGrant(id, clientId, request.redirectUri, request.state, request.challenge,
            resource, scopes.sorted().joinToString(" "), sha256(browser), Instant.now().plusSeconds(600)))
        return McpOAuthStart(id, browser)
    }

    @Transactional
    override suspend fun restart(id: String, browserSecret: String, remoteAddress: String): McpOAuthStart {
        val grant = browserGrant(id, browserSecret)
        val next = if (grant.managementOnly) startManagement(remoteAddress) else start(McpOAuthRequest(
            grant.clientId, grant.redirectUri, grant.state, grant.challenge, grant.resource, grant.scope,
            "code", "S256", remoteAddress))
        // Switching the pending identity must not revoke an already established connection.
        if (grant.accessHash == null) store.save(grant.copy(revoked = true))
        return next.copy(managementOnly = grant.managementOnly)
    }

    @Transactional
    override suspend fun startManagement(remoteAddress: String): McpOAuthStart {
        val start = start(McpOAuthRequest(clientId, redirectUris.first(), secret(), challenge(secret()),
            resource, "learning:read", "code", "S256", remoteAddress))
        val grant = store.find(start.id, true)!!
        store.save(grant.copy(managementOnly = true))
        return start
    }
    @Transactional
    override suspend fun browserConnections(id: String, browserSecret: String): List<McpOAuthConnection> {
        val grant = browserGrant(id, browserSecret)
        require(grant.verified && grant.userId != null) { "invalid_request" }
        return connections(grant.userId)
    }
    @Transactional
    override suspend fun browserDisconnect(id: String, browserSecret: String, connectionId: String) {
        val grant = browserGrant(id, browserSecret)
        require(grant.verified && grant.userId != null) { "invalid_request" }
        disconnect(grant.userId, connectionId)
    }
    @Transactional
    override suspend fun browserScopes(id: String, browserSecret: String): Set<String> =
        browserGrant(id, browserSecret).scope.split(' ').toSet()

    @Transactional
    override suspend fun verifiedBrowserPrincipal(id: String, browserSecret: String): Principal {
        val grant = browserGrant(id, browserSecret)
        require(grant.verified && grant.userId != null && grant.codeHash == null && grant.accessHash == null && store.activeUser(grant.userId)) { "invalid_request" }
        return Principal(grant.userId, "mcp:${grant.id}", 0, false, oauthScopes = grant.scope.split(' ').toSet())
    }

    override suspend fun cleanup(): Long = store.purgeExpired()

    @Transactional
    override suspend fun passwordLogin(id: String, browserSecret: String, email: String, password: String, remoteAddress: String): Boolean {
        val grant = browserGrant(id, browserSecret)
        if (grant.verified || grant.codeHash != null || grant.accessHash != null || grant.emailAttempts >= 5) return false
        val normalized = email.trim().lowercase()
        require(normalized.length in 3..254 && password.length in 1..256) { "invalid_request" }
        if (!store.allowEmail(sha256(normalized), sha256(remoteAddress))) return false
        val credential = store.emailCredential(normalized)
        val passwordMatches = equal(credential?.passwordHash ?: "0".repeat(64), sha256(password))
        val valid = credential != null && passwordMatches && store.activeUser(credential.userId)
        store.save(grant.copy(userId = if (valid) credential?.userId else null,
            verified = valid, emailAttempts = grant.emailAttempts + 1, emailCodeHash = null))
        return valid
    }

    @Transactional
    override suspend fun sendCode(id: String, browserSecret: String, email: String, remoteAddress: String): Boolean {
        val grant = browserGrant(id, browserSecret)
        require(grant.codeHash == null && grant.accessHash == null && !grant.verified) { "invalid_request" }
        val normalized = email.trim().lowercase()
        require(normalized.length in 3..254 && normalized.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { "invalid_request" }
        if (!store.allowEmail(sha256(normalized), sha256(remoteAddress))) return false
        val userId = store.uniqueUserByEmail(normalized)
        val code = "%08d".format(random.nextInt(100_000_000))
        // Store indistinguishable state even for unknown addresses. No account creation or linking.
        store.save(grant.copy(userId = userId, emailCodeHash = sha256("$id:$code"), emailAttempts = 0))
        if (userId != null) mail.send(normalized, code, Duration.ofMinutes(10))
        return true
    }

    @Transactional
    override suspend fun verifyCode(id: String, browserSecret: String, code: String): Boolean {
        val grant = browserGrant(id, browserSecret)
        if (grant.emailAttempts >= 5 || grant.emailCodeHash == null || grant.verified) return false
        val valid = grant.userId != null && equal(grant.emailCodeHash, sha256("$id:$code"))
        store.save(grant.copy(emailAttempts = grant.emailAttempts + 1, verified = valid,
            emailCodeHash = if (valid) null else grant.emailCodeHash))
        return valid
    }

    @Transactional
    override suspend fun consent(id: String, browserSecret: String, allow: Boolean): String {
        val grant = browserGrant(id, browserSecret)
        require(!grant.managementOnly && grant.verified && grant.userId != null && grant.codeHash == null && grant.accessHash == null) { "invalid_request" }
        if (!allow) {
            store.save(grant.copy(revoked = true))
            return callback(grant, "error", "access_denied")
        }
        require(store.activeUser(grant.userId)) { "access_denied" }
        val code = "bs_mcp_c.$id.${secret()}"
        store.save(grant.copy(codeHash = sha256(code), codeExpiresAt = Instant.now().plusSeconds(60),
            consentVersion = if (grant.scope.split(' ').any { it in setOf("learning:write", "learning:delete") }) "learning-manage-2026-10-21" else "learning-read-2026-10-20", consentedAt = Instant.now()))
        return callback(grant, "code", code)
    }

    @Transactional
    override suspend fun token(parameters: Map<String, String>): McpOAuthResult {
        if (parameters["client_id"] != clientId) return McpOAuthResult(error = "invalid_client")
        if (parameters["resource"] != resource) return McpOAuthResult(error = "invalid_target")
        val refresh = parameters["grant_type"] == "refresh_token"
        if (!refresh && parameters["grant_type"] != "authorization_code") return McpOAuthResult(error = "unsupported_grant_type")
        val raw = parameters[if (refresh) "refresh_token" else "code"].orEmpty()
        val id = tokenId(raw, if (refresh) "r" else "c") ?: return McpOAuthResult(error = "invalid_grant")
        val grant = store.find(id, true) ?: return McpOAuthResult(error = "invalid_grant")
        if (!store.allowToken(sha256(id))) return McpOAuthResult(error = "temporarily_unavailable")
        if (grant.clientId != clientId || grant.resource != resource || grant.revoked || grant.userId == null || !store.activeUser(grant.userId)) return McpOAuthResult(error = "invalid_grant")
        if (refresh) {
            if ("offline_access" !in grant.scope.split(' ') || grant.refreshExpiresAt?.isAfter(Instant.now()) != true)
                return McpOAuthResult(error = "invalid_grant")
            if (!equal(grant.refreshHash, sha256(raw))) {
                if (store.wasRefreshUsed(id, sha256(raw))) store.save(grant.copy(revoked = true))
                return McpOAuthResult(error = "invalid_grant")
            }
            val requestedScope = parameters["scope"]
            if (requestedScope != null && requestedScope.split(' ').filter(String::isNotEmpty).toSet() != grant.scope.split(' ').toSet()) return McpOAuthResult(error = "invalid_scope")
        } else {
            val verifier = parameters["code_verifier"].orEmpty()
            if (!verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")) ||
                parameters["redirect_uri"] != grant.redirectUri ||
                grant.codeExpiresAt?.isAfter(Instant.now()) != true || !equal(grant.codeHash, sha256(raw)) ||
                !equal(grant.challenge, challenge(verifier))) return McpOAuthResult(error = "invalid_grant")
        }
        val access = "bs_mcp_a.$id.${secret()}"
        val refreshToken = if ("offline_access" in grant.scope.split(' ')) "bs_mcp_r.$id.${secret()}" else null
        val now = Instant.now()
        val connectionEnd = grant.refreshExpiresAt ?: if (refreshToken == null) now.plusSeconds(900) else (grant.consentedAt ?: now).plusSeconds(30 * 86400L)
        val accessEnd = minOf(now.plusSeconds(900), connectionEnd)
        if (refresh) store.rememberRefresh(id, sha256(raw))
        store.save(grant.copy(codeHash = null, accessHash = sha256(access), accessExpiresAt = accessEnd,
            refreshHash = refreshToken?.let(::sha256), refreshExpiresAt = connectionEnd))
        return McpOAuthResult(tokens = McpOAuthTokens(access, refreshToken,
            expires_in = Duration.between(now, accessEnd).seconds, scope = grant.scope))
    }

    override suspend fun authenticate(token: String): Principal? {
        val id = tokenId(token, "a") ?: return null
        val grant = store.find(id) ?: return null
        if (grant.revoked || grant.userId == null || grant.clientId != clientId || grant.resource != resource ||
            "learning:read" !in grant.scope.split(' ') || grant.accessExpiresAt?.isAfter(Instant.now()) != true ||
            !equal(grant.accessHash, sha256(token)) || !store.activeUser(grant.userId)) return null
        return Principal(grant.userId, "mcp:$id", 0, false, oauthScopes = grant.scope.split(' ').toSet())
    }

    @Transactional
    override suspend fun revoke(token: String, clientId: String) {
        if (clientId != this.clientId) return
        val id = tokenId(token, "r") ?: tokenId(token, "a") ?: return
        val grant = store.find(id, true) ?: return
        if (grant.clientId == clientId && (equal(grant.refreshHash, sha256(token)) || equal(grant.accessHash, sha256(token))))
            store.save(grant.copy(revoked = true))
    }
    override suspend fun connections(userId: Long): List<McpOAuthConnection> =
        store.list(userId).filter { !it.revoked && it.accessHash != null }.map { McpOAuthConnection(it.id, it.clientId, it.refreshExpiresAt) }

    @Transactional
    override suspend fun disconnect(userId: Long, id: String) {
        val grant = store.find(id, true) ?: return
        if (grant.userId == userId) store.save(grant.copy(revoked = true))
    }
    private suspend fun browserGrant(id: String, browser: String): McpOAuthGrant {
        val grant = store.find(id, true)
        require(grant != null && !grant.revoked && grant.expiresAt.isAfter(Instant.now()) && equal(grant.browserHash, sha256(browser))) { "invalid_request" }
        return grant
    }
    private fun callback(grant: McpOAuthGrant, name: String, value: String): String =
        grant.redirectUri + (if ('?' in grant.redirectUri) "&" else "?") + "$name=${encode(value)}&state=${encode(grant.state)}&iss=${encode(issuer)}"
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun secret() = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun tokenId(raw: String, kind: String): String? = raw.split('.').takeIf {
        it.size == 3 && it[0] == "bs_mcp_$kind" && it[1].matches(Regex("[A-Za-z0-9_-]{43}")) && it[2].matches(Regex("[A-Za-z0-9_-]{43}"))
    }?.get(1)
    private fun equal(expected: String?, actual: String) = expected != null && MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
    companion object {
        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
    }
}
