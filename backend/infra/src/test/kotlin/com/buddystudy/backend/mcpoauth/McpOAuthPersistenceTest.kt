package com.buddystudy.backend.mcpoauth

import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationSenderPort
import com.buddystudy.backend.auth.sha256
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** R2DBC round trips and real transaction locks; the production Flyway migration is MySQL-specific. */
class McpOAuthPersistenceTest {
    @Test
    fun `durable grant survives JSON roundtrip and concurrent code redemption has one winner`(): Unit = runBlocking {
        val factory = ConnectionFactories.get("r2dbc:h2:mem:///oauth_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        val db = DatabaseClient.create(factory)
        val transaction = TransactionalOperator.create(R2dbcTransactionManager(factory))
        suspend fun sql(value: String) { db.sql(value).fetch().rowsUpdated().awaitSingle() }
        sql("CREATE TABLE users(id BIGINT PRIMARY KEY, email VARCHAR(254), status VARCHAR(30), provider VARCHAR(30))")
        sql("INSERT INTO users VALUES(7, 'user@example.com', 'ACTIVE', 'EMAIL')")
        sql("CREATE TABLE mcp_oauth_grants(id VARCHAR(43) PRIMARY KEY, user_id BIGINT, payload CLOB, connected BOOLEAN DEFAULT FALSE, expires_at TIMESTAMP WITH TIME ZONE, FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE)")
        sql("CREATE TABLE mcp_oauth_used_refresh_tokens(token_hash VARCHAR(64) PRIMARY KEY, grant_id VARCHAR(43), FOREIGN KEY(grant_id) REFERENCES mcp_oauth_grants(id) ON DELETE CASCADE)")
        val persistence = McpOAuthPersistenceAdapter(db, JsonMapperProvider.mapper, Mockito.mock(ReactiveStringRedisTemplate::class.java))
        val store = object : McpOAuthStorePort by persistence {
            override suspend fun allowToken(idHash: String) = true
            override suspend fun allowEmail(emailHash: String, remoteHash: String) = true
            override suspend fun allowStart(remoteHash: String) = true
        }
        var code = ""
        val mail = object : EmailVerificationSenderPort {
            override suspend fun send(email: String, codeValue: String, ttl: Duration) { code = codeValue }
        }
        val issuer = "https://api.example.com"
        val redirect = "https://chatgpt.com/connector_platform_oauth_redirect"
        val verifier = "a".repeat(43)
        val service = McpOAuthService(store, mail, issuer, "client", redirect)
        val start = transaction.executeAndAwait { service.start(McpOAuthRequest("client", redirect, "state",
            McpOAuthService.challenge(verifier), "$issuer/api/v1/mcp", "learning:read offline_access", "code", "S256")) }!!
        transaction.executeAndAwait { service.sendCode(start.id, start.browserSecret, "user@example.com", "ip") }
        transaction.executeAndAwait { service.verifyCode(start.id, start.browserSecret, code) }
        val location = transaction.executeAndAwait { service.consent(start.id, start.browserSecret, true) }!!
        val authorizationCode = URI(location).rawQuery.split('&').single { it.startsWith("code=") }.substringAfter('=').let { URLDecoder.decode(it, Charsets.UTF_8) }
        val parameters = mapOf("grant_type" to "authorization_code", "client_id" to "client", "redirect_uri" to redirect,
            "resource" to "$issuer/api/v1/mcp", "code" to authorizationCode, "code_verifier" to verifier)
        val results = coroutineScope {
            val first = async(Dispatchers.Default) { transaction.executeAndAwait { service.token(parameters) }!! }
            val second = async(Dispatchers.Default) { transaction.executeAndAwait { service.token(parameters) }!! }
            listOf(first.await(), second.await())
        }
        assertThat(results.count { it.tokens != null }).isEqualTo(1)
        assertThat(results.count { it.error == "invalid_grant" }).isEqualTo(1)
        val tokens = results.single { it.tokens != null }.tokens!!
        assertThat(service.authenticate(tokens.access_token)!!.userId).isEqualTo(7)
        assertThat(service.connections(7)).hasSize(1)
        val rotated = transaction.executeAndAwait { service.token(mapOf("grant_type" to "refresh_token", "client_id" to "client",
            "resource" to "$issuer/api/v1/mcp", "refresh_token" to tokens.refresh_token!!)) }!!.tokens!!
        assertThat(persistence.wasRefreshUsed(start.id, sha256(tokens.refresh_token!!))).isTrue()
        assertThat(service.authenticate(rotated.access_token)).isNotNull()
        transaction.executeAndAwait { service.disconnect(7, start.id) }
        assertThat(service.authenticate(tokens.access_token)).isNull()
        assertThat(service.authenticate(rotated.access_token)).isNull()
        assertThat(service.connections(7)).isEmpty()
        sql("DELETE FROM users WHERE id = 7")
        assertThat(persistence.find(start.id)).isNull()
    }

    @Test
    fun `ambiguous provider emails and inactive accounts are rejected`(): Unit = runBlocking {
        val db = DatabaseClient.create(ConnectionFactories.get("r2dbc:h2:mem:///oauth_${UUID.randomUUID()};DB_CLOSE_DELAY=-1"))
        db.sql("CREATE TABLE users(id BIGINT PRIMARY KEY, email VARCHAR(254), status VARCHAR(30), provider VARCHAR(30))").fetch().rowsUpdated().awaitSingle()
        db.sql("INSERT INTO users VALUES(1, 'same@example.com', 'ACTIVE', 'GOOGLE'), (2, 'same@example.com', 'ACTIVE', 'EMAIL'), (3, 'old@example.com', 'WITHDRAWN', 'APPLE')").fetch().rowsUpdated().awaitSingle()
        val adapter = McpOAuthPersistenceAdapter(db, JsonMapperProvider.mapper, Mockito.mock(ReactiveStringRedisTemplate::class.java))
        assertThat(adapter.uniqueUserByEmail("same@example.com")).isNull()
        assertThat(adapter.uniqueUserByEmail("old@example.com")).isNull()
        assertThat(adapter.activeUser(3)).isFalse()
    }
}
