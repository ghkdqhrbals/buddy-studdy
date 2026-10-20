package com.buddystudy.backend.mcpoauth

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

@Component
class McpOAuthPersistenceAdapter(
    private val database: DatabaseClient,
    private val mapper: ObjectMapper,
    private val redis: ReactiveStringRedisTemplate,
) : McpOAuthStorePort {
    override suspend fun insert(grant: McpOAuthGrant) {
        database.sql("INSERT INTO mcp_oauth_grants (id, payload, expires_at) VALUES (:id, :payload, :expires)")
            .bind("id", grant.id).bind("payload", mapper.writeValueAsString(grant))
            .bind("expires", grant.expiresAt).fetch().rowsUpdated().awaitSingle()
    }
    override suspend fun find(id: String, lock: Boolean): McpOAuthGrant? =
        database.sql("SELECT payload FROM mcp_oauth_grants WHERE id = :id" + if (lock) " FOR UPDATE" else "")
            .bind("id", id).map { row, _ -> mapper.readValue(row.get("payload", String::class.java), McpOAuthGrant::class.java) }
            .one().awaitSingleOrNull()

    override suspend fun save(grant: McpOAuthGrant) {
        val persistenceExpiry = grant.refreshExpiresAt ?: maxOf(grant.expiresAt, grant.codeExpiresAt ?: grant.expiresAt)
        var query = database.sql("UPDATE mcp_oauth_grants SET payload = :payload, user_id = :user, connected = :connected, expires_at = :expires WHERE id = :id")
            .bind("id", grant.id).bind("payload", mapper.writeValueAsString(grant))
            .bind("expires", persistenceExpiry)
            .bind("connected", grant.accessHash != null && !grant.revoked)
        query = grant.userId?.let { query.bind("user", it) } ?: query.bindNull("user", java.lang.Long::class.java)
        check(query.fetch().rowsUpdated().awaitSingle() == 1L)
    }
    override suspend fun uniqueUserByEmail(email: String): Long? {
        val ids = database.sql("SELECT id FROM users WHERE LOWER(email) = :email AND status = 'ACTIVE' AND provider IN ('EMAIL', 'GOOGLE', 'APPLE') LIMIT 2")
            .bind("email", email).map { row, _ -> (row.get("id") as Number).toLong() }.all().collectList().awaitSingle()
        return ids.singleOrNull()
    }
    override suspend fun activeUser(userId: Long): Boolean =
        database.sql("SELECT id FROM users WHERE id = :id AND status = 'ACTIVE' AND provider <> 'ANONYMOUS'")
            .bind("id", userId).map { _, _ -> true }.one().awaitSingleOrNull() == true

    override suspend fun list(userId: Long): List<McpOAuthGrant> =
        database.sql("SELECT payload FROM mcp_oauth_grants WHERE user_id = :user AND expires_at > CURRENT_TIMESTAMP AND connected = TRUE ORDER BY expires_at DESC LIMIT 100")
            .bind("user", userId).map { row, _ -> mapper.readValue(row.get("payload", String::class.java), McpOAuthGrant::class.java) }
            .all().collectList().awaitSingle()

    override suspend fun wasRefreshUsed(id: String, tokenHash: String): Boolean =
        database.sql("SELECT token_hash FROM mcp_oauth_used_refresh_tokens WHERE grant_id = :id AND token_hash = :hash")
            .bind("id", id).bind("hash", tokenHash).map { _, _ -> true }.one().awaitSingleOrNull() == true
    override suspend fun rememberRefresh(id: String, tokenHash: String) {
        database.sql("INSERT INTO mcp_oauth_used_refresh_tokens (grant_id, token_hash) VALUES (:id, :hash)")
            .bind("id", id).bind("hash", tokenHash).fetch().rowsUpdated().awaitSingle()
    }
    override suspend fun allowToken(idHash: String): Boolean = rate("token:$idHash", 60)
    override suspend fun purgeExpired(): Long = database.sql("DELETE FROM mcp_oauth_grants WHERE expires_at < CURRENT_TIMESTAMP LIMIT 1000")
        .fetch().rowsUpdated().awaitSingle()
    override suspend fun allowEmail(emailHash: String, remoteHash: String): Boolean {
        val email = rate("email:$emailHash", 5)
        val remote = rate("remote:$remoteHash", 30)
        return email && remote
    }
    override suspend fun allowStart(remoteHash: String): Boolean = rate("start:$remoteHash", 60)
    private suspend fun rate(key: String, limit: Long): Boolean = redis.execute(RATE,
        listOf("buddystudy:mcp-oauth:$key"), listOf(limit.toString())).next().awaitSingle() == 1L
    companion object {
        private val RATE = DefaultRedisScript("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], 3600) end
            if n <= tonumber(ARGV[1]) then return 1 end
            return 0
        """.trimIndent(), Long::class.java)
    }
}
