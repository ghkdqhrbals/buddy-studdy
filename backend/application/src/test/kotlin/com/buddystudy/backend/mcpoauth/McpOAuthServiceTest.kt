package com.buddystudy.backend.mcpoauth

import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationSenderPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant

class McpOAuthServiceTest {
    private val store = MemoryStore()
    private var sentCode = ""
    private var sends = 0
    private val mail = object : EmailVerificationSenderPort {
        override suspend fun send(email: String, code: String, ttl: Duration) { sentCode = code; sends++ }
    }
    private val issuer = "https://api.example.com"
    private val redirect = "https://chatgpt.com/connector_platform_oauth_redirect"
    private val service = McpOAuthService(store, mail, issuer, "client", redirect)
    private val verifier = "v".repeat(43)
    private fun request() = McpOAuthRequest("client", redirect, "opaque-state", McpOAuthService.challenge(verifier),
        "$issuer/api/v1/mcp", "learning:read offline_access", "code", "S256")

    @Test
    fun `password login verifies an existing active email identity but never bypasses consent`(): Unit = runBlocking {
        val start = service.start(request())
        assertThat(runCatching { service.passwordLogin(start.id, "wrong", "user@example.com", "fixture-password", "ip") }.isFailure).isTrue()
        assertThat(service.passwordLogin(start.id, start.browserSecret, "user@example.com", "wrong", "ip")).isFalse()
        assertThat(runCatching { service.consent(start.id, start.browserSecret, true) }.isFailure).isTrue()
        assertThat(service.passwordLogin(start.id, start.browserSecret, "USER@example.com", "fixture-password", "ip")).isTrue()
        assertThat(store.rows[start.id]!!.accessHash).isNull()
        assertThat(service.passwordLogin(start.id, start.browserSecret, "user@example.com", "fixture-password", "ip")).isFalse()
        val code = query(service.consent(start.id, start.browserSecret, true))["code"]!!
        assertThat(service.authenticate(service.token(exchange(code)).tokens!!.access_token)!!.userId).isEqualTo(7)
        assertThat(sends).isZero()
    }

    @Test
    fun `password login cannot create unknown identities exceed attempt limits or authenticate inactive owners`(): Unit = runBlocking {
        val start = service.start(request())
        repeat(5) { assertThat(service.passwordLogin(start.id, start.browserSecret, "user@example.com", "wrong", "ip")).isFalse() }
        assertThat(service.passwordLogin(start.id, start.browserSecret, "user@example.com", "fixture-password", "ip")).isFalse()
        val inactive = service.start(request())
        store.active = false
        assertThat(service.passwordLogin(inactive.id, inactive.browserSecret, "user@example.com", "fixture-password", "ip")).isFalse()
        store.active = true; store.emailUser = null
        assertThat(service.passwordLogin(inactive.id, inactive.browserSecret, "missing@example.com", "fixture-password", "ip")).isFalse()
        assertThat(store.rows[inactive.id]!!.userId).isNull()
    }

    @Test
    fun `management consent grants exact requested scopes and refresh cannot widen old consent`(): Unit = runBlocking {
        val scopes = "learning:read learning:write learning:delete offline_access"
        val start = service.start(request().copy(scope = scopes))
        assertThat(service.browserScopes(start.id, start.browserSecret)).containsExactlyInAnyOrderElementsOf(scopes.split(' '))
        assertThat(runCatching { service.browserScopes(start.id, "wrong") }.isFailure).isTrue()
        service.sendCode(start.id, start.browserSecret, "user@example.com", "ip")
        service.verifyCode(start.id, start.browserSecret, sentCode)
        val code = query(service.consent(start.id, start.browserSecret, true))["code"]!!
        val tokens = service.token(exchange(code)).tokens!!
        assertThat(service.authenticate(tokens.access_token)!!.oauthScopes).contains("learning:write", "learning:delete")
        assertThat(store.rows[start.id]!!.consentVersion).isEqualTo("learning-manage-2026-10-21")
        val (_, readCode) = authorize()
        val read = service.token(exchange(readCode)).tokens!!
        val expanded = service.token(mapOf("grant_type" to "refresh_token", "client_id" to "client", "resource" to "$issuer/api/v1/mcp", "refresh_token" to read.refresh_token!!, "scope" to scopes))
        assertThat(expanded.error).isNotNull()
        assertThat(service.authenticate(read.access_token)!!.oauthScopes).doesNotContain("learning:write", "learning:delete")
    }

    @Test
    fun `login consent and code exchange issue owner-scoped independent tokens`(): Unit = runBlocking {
        val start = service.start(request())
        assertThat(service.sendCode(start.id, start.browserSecret, "USER@example.com", "ip")).isTrue()
        assertThat(sentCode).matches("[0-9]{8}")
        assertThat(store.rows[start.id]!!.emailCodeHash).doesNotContain(sentCode)
        assertThat(service.verifyCode(start.id, start.browserSecret, sentCode)).isTrue()
        val location = service.consent(start.id, start.browserSecret, true)
        assertThat(query(location)["state"]).isEqualTo("opaque-state")
        assertThat(query(location)["iss"]).isEqualTo(issuer)
        val code = query(location)["code"]!!
        val tokens = service.token(exchange(code)).tokens!!
        assertThat(tokens.access_token).startsWith("bs_mcp_a.")
        val principal = service.authenticate(tokens.access_token)!!
        assertThat(principal.userId).isEqualTo(7)
        assertThat(principal.oauthScopes).contains("learning:read")
        assertThat(principal.deviceId).startsWith("mcp:")
        assertThat(store.rows[start.id]!!.accessHash).doesNotContain(tokens.access_token)
        assertThat(store.rows[start.id]!!.consentVersion).isEqualTo("learning-read-2026-10-20")
        assertThat(service.token(exchange(code)).error).isEqualTo("invalid_grant")
    }

    @Test
    fun `rejects unregistered clients redirects targets scopes and insecure PKCE`(): Unit = runBlocking {
        for (bad in listOf(request().copy(clientId = "other"), request().copy(redirectUri = "https://attacker.example"),
            request().copy(redirectUri = "$redirect/extra"), request().copy(resource = "https://other.example"),
            request().copy(scope = "learning:read study:delete"), request().copy(challengeMethod = "plain"), request().copy(challenge = "bad"))) {
            assertThat(runCatching { service.start(bad) }.isFailure).isTrue()
        }
        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `wrong verifier client audience and redirect never consume a valid code`(): Unit = runBlocking {
        val (_, code) = authorize()
        for ((key, value) in mapOf("code_verifier" to "x".repeat(43), "client_id" to "other", "resource" to issuer,
            "redirect_uri" to "https://other.example")) {
            assertThat(service.token(exchange(code) + (key to value)).error).isNotNull()
        }
        assertThat(service.token(exchange(code)).tokens).isNotNull()
    }

    @Test
    fun `browser cookie and verified identity are required before consent`(): Unit = runBlocking {
        val start = service.start(request())
        assertThat(runCatching { service.sendCode(start.id, "wrong", "user@example.com", "ip") }.isFailure).isTrue()
        assertThat(runCatching { service.consent(start.id, start.browserSecret, true) }.isFailure).isTrue()
        assertThat(sends).isZero()
        service.sendCode(start.id, start.browserSecret, "user@example.com", "ip")
        repeat(5) { assertThat(service.verifyCode(start.id, start.browserSecret, "wrong")).isFalse() }
        assertThat(service.verifyCode(start.id, start.browserSecret, sentCode)).isFalse()
    }

    @Test
    fun `unknown and ambiguous email cannot authenticate or create an account`(): Unit = runBlocking {
        store.emailUser = null
        val start = service.start(request())
        assertThat(service.sendCode(start.id, start.browserSecret, "unknown@example.com", "ip")).isTrue()
        assertThat(sends).isZero()
        assertThat(service.verifyCode(start.id, start.browserSecret, "00000000")).isFalse()
    }

    @Test
    fun `refresh rotates tokens and replay revokes the connection`(): Unit = runBlocking {
        val (_, code) = authorize()
        val first = service.token(exchange(code)).tokens!!
        val refresh = mapOf("grant_type" to "refresh_token", "client_id" to "client", "resource" to "$issuer/api/v1/mcp", "refresh_token" to first.refresh_token!!, "scope" to "offline_access learning:read")
        val next = service.token(refresh).tokens!!
        assertThat(next.refresh_token).isNotEqualTo(first.refresh_token)
        assertThat(service.authenticate(first.access_token)).isNull()
        assertThat(service.authenticate(next.access_token)).isNotNull()
        assertThat(service.token(refresh).error).isEqualTo("invalid_grant")
        assertThat(service.authenticate(next.access_token)).isNull()
    }

    @Test
    fun `revocation withdrawal and expiration stop access and foreign users cannot disconnect`(): Unit = runBlocking {
        val (start, code) = authorize()
        val tokens = service.token(exchange(code)).tokens!!
        service.disconnect(99, start.id)
        assertThat(service.authenticate(tokens.access_token)).isNotNull()
        store.active = false
        assertThat(service.authenticate(tokens.access_token)).isNull()
        store.active = true
        store.rows[start.id] = store.rows[start.id]!!.copy(accessExpiresAt = Instant.now().minusSeconds(1))
        assertThat(service.authenticate(tokens.access_token)).isNull()
        service.revoke(tokens.refresh_token!!, "client")
        assertThat(service.connections(7)).isEmpty()
    }

    @Test
    fun `deny and expired code do not grant access`(): Unit = runBlocking {
        val start = service.start(request())
        service.sendCode(start.id, start.browserSecret, "user@example.com", "ip")
        service.verifyCode(start.id, start.browserSecret, sentCode)
        val denial = service.consent(start.id, start.browserSecret, false)
        assertThat(query(denial)["error"]).isEqualTo("access_denied")
        assertThat(query(denial)["iss"]).isEqualTo(issuer)
        val (next, code) = authorize()
        store.rows[next.id] = store.rows[next.id]!!.copy(codeExpiresAt = Instant.now().minusSeconds(1))
        assertThat(service.token(exchange(code)).error).isEqualTo("invalid_grant")
    }

    @Test
    fun `rate limits prevent email and authorization abuse`(): Unit = runBlocking {
        store.allowed = false
        assertThat(runCatching { service.start(request()) }.isFailure).isTrue()
        store.allowed = true
        val start = service.start(request())
        store.allowed = false
        assertThat(service.sendCode(start.id, start.browserSecret, "user@example.com", "ip")).isFalse()
        assertThat(sends).isZero()
    }

    @Test
    fun `management login can revoke owned connections but cannot authorize a client`(): Unit = runBlocking {
        val (_, code) = authorize()
        val tokens = service.token(exchange(code)).tokens!!
        val manage = service.startManagement("ip")
        service.sendCode(manage.id, manage.browserSecret, "user@example.com", "ip")
        service.verifyCode(manage.id, manage.browserSecret, sentCode)
        val connection = service.browserConnections(manage.id, manage.browserSecret).single()
        assertThat(runCatching { service.consent(manage.id, manage.browserSecret, true) }.isFailure).isTrue()
        service.browserDisconnect(manage.id, manage.browserSecret, connection.id)
        assertThat(service.authenticate(tokens.access_token)).isNull()
    }

    @Test
    fun `access without offline scope cannot refresh and never exceeds fifteen minutes`(): Unit = runBlocking {
        val start = service.start(request().copy(scope = "learning:read"))
        service.sendCode(start.id, start.browserSecret, "user@example.com", "ip")
        service.verifyCode(start.id, start.browserSecret, sentCode)
        val code = query(service.consent(start.id, start.browserSecret, true))["code"]!!
        val tokens = service.token(exchange(code)).tokens!!
        assertThat(tokens.refresh_token).isNull()
        assertThat(tokens.scope).isEqualTo("learning:read")
        assertThat(tokens.expires_in).isLessThanOrEqualTo(900)
    }

    @Test
    fun `changing account creates a fresh login without inheriting the verified identity`(): Unit = runBlocking {
        val first = service.start(request())
        service.sendCode(first.id, first.browserSecret, "user@example.com", "ip")
        service.verifyCode(first.id, first.browserSecret, sentCode)
        val next = service.restart(first.id, first.browserSecret, "ip")
        assertThat(next.id).isNotEqualTo(first.id)
        assertThat(store.rows[next.id]!!.verified).isFalse()
        assertThat(store.rows[next.id]!!.userId).isNull()
        assertThat(store.rows[first.id]!!.revoked).isTrue()
        assertThat(store.rows[next.id]!!.challenge).isEqualTo(request().challenge)
        assertThat(store.rows[next.id]!!.state).isEqualTo(request().state)
        assertThat(runCatching { service.consent(next.id, next.browserSecret, true) }.isFailure).isTrue()
    }

    private suspend fun authorize(): Pair<McpOAuthStart, String> {
        val start = service.start(request())
        service.sendCode(start.id, start.browserSecret, "user@example.com", "ip")
        check(service.verifyCode(start.id, start.browserSecret, sentCode))
        return start to query(service.consent(start.id, start.browserSecret, true))["code"]!!
    }
    private fun exchange(code: String) = mapOf("grant_type" to "authorization_code", "client_id" to "client", "resource" to "$issuer/api/v1/mcp",
        "redirect_uri" to redirect, "code" to code, "code_verifier" to verifier)
    private fun query(uri: String) = URI(uri).rawQuery.split('&').associate { field ->
        val parts = field.split('=', limit = 2); parts[0] to URLDecoder.decode(parts[1], Charsets.UTF_8)
    }
    private class MemoryStore : McpOAuthStorePort {
        val rows = mutableMapOf<String, McpOAuthGrant>()
        var emailUser: Long? = 7; var active = true; var allowed = true
        override suspend fun insert(grant: McpOAuthGrant) { rows[grant.id] = grant }
        override suspend fun find(id: String, lock: Boolean) = rows[id]
        override suspend fun save(grant: McpOAuthGrant) { rows[grant.id] = grant }
        override suspend fun uniqueUserByEmail(email: String) = emailUser
        override suspend fun emailCredential(email: String) = if (emailUser != null) McpEmailCredential(emailUser!!, com.buddystudy.backend.auth.sha256("fixture-password")) else null
        override suspend fun activeUser(userId: Long) = active
        override suspend fun list(userId: Long) = rows.values.filter { it.userId == userId }
        val used = mutableSetOf<Pair<String, String>>()
        override suspend fun wasRefreshUsed(id: String, tokenHash: String) = (id to tokenHash) in used
        override suspend fun rememberRefresh(id: String, tokenHash: String) { used += id to tokenHash }
        override suspend fun allowToken(idHash: String) = allowed
        override suspend fun purgeExpired() = 0L
        override suspend fun allowEmail(emailHash: String, remoteHash: String) = allowed
        override suspend fun allowStart(remoteHash: String) = allowed
    }
}
