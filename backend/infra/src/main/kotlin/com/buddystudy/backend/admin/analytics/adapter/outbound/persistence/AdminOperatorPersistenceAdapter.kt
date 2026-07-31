package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorPageResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorPrincipal
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorSummary
import com.buddystudy.backend.admin.analytics.application.model.CreateAdminOperatorCommand
import com.buddystudy.backend.admin.analytics.application.model.UpdateAdminOperatorCommand
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminOperatorPort
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class AdminOperatorPersistenceAdapter(
    private val database: DatabaseClient,
) : AdminOperatorPort {
    private val passwordEncoder = BCryptPasswordEncoder(12)

    override suspend fun authenticate(
        username: String,
        password: String,
        authenticatedAt: Instant,
    ): AdminOperatorPrincipal? {
        val row = findCredential(username) ?: return null
        if (row.status != "ACTIVE" || !passwordEncoder.matches(password, row.passwordHash)) {
            return null
        }
        database.sql(
            """
            update admin_accounts
            set last_login_at = :authenticatedAt, updated_at = :authenticatedAt
            where id = :id
            """.trimIndent(),
        ).bind("authenticatedAt", authenticatedAt)
            .bind("id", row.id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return row.toPrincipal()
    }

    override suspend fun activeStatus(username: String): Boolean? =
        database.sql(
            "select status from admin_accounts where username = :username",
        ).bind("username", username)
            .map { row, _ -> row.string("status") == "ACTIVE" }
            .one()
            .awaitSingleOrNull()

    @Transactional
    override suspend fun ensureBootstrap(
        username: String,
        displayName: String,
        password: String,
    ): AdminOperatorPrincipal {
        findCredential(username)?.let { return it.toPrincipal() }
        val now = Instant.now()
        try {
            database.sql(
                """
                insert into admin_accounts
                    (username, display_name, password_hash, status, created_by, created_at, updated_at)
                values
                    (:username, :displayName, :passwordHash, 'ACTIVE', 'bootstrap', :now, :now)
                """.trimIndent(),
            ).bind("username", username)
                .bind("displayName", displayName)
                .bind("passwordHash", requireNotNull(passwordEncoder.encode(password)))
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        } catch (_: DataIntegrityViolationException) {
            // A concurrent first login created the same bootstrap account.
        }
        return findCredential(username)?.toPrincipal()
            ?: error("Bootstrap administrator account could not be created.")
    }

    override suspend fun operators(query: String?, limit: Int, offset: Int): AdminOperatorPageResponse {
        val search = query?.lowercase()?.let { "%$it%" }
        val where = if (search == null) "" else "where lower(username) like :query or lower(display_name) like :query"
        val count = database.sql("select count(*) as total_count from admin_accounts $where")
            .bindQuery(search)
            .map { row, _ -> row.long("total_count") }
            .one()
            .awaitSingle()
        val operators = database.sql(
            """
            select id, username, display_name, status, last_login_at, created_at, updated_at
            from admin_accounts
            $where
            order by id asc
            limit :limit offset :offset
            """.trimIndent(),
        ).bindQuery(search)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row, _ -> row.toSummary() }
            .all()
            .collectList()
            .awaitSingle()
        return AdminOperatorPageResponse(operators, count, limit, offset)
    }

    override suspend fun create(
        command: CreateAdminOperatorCommand,
        createdBy: String,
    ): AdminOperatorSummary? {
        val now = Instant.now()
        try {
            val id = database.sql(
                """
                insert into admin_accounts
                    (username, display_name, password_hash, status, created_by, created_at, updated_at)
                values
                    (:username, :displayName, :passwordHash, 'ACTIVE', :createdBy, :now, :now)
                """.trimIndent(),
            ).bind("username", command.username)
                .bind("displayName", command.displayName)
                .bind("passwordHash", requireNotNull(passwordEncoder.encode(command.password)))
                .bind("createdBy", createdBy)
                .bind("now", now)
                .filter { statement -> statement.returnGeneratedValues("id") }
                .map { row, _ -> row.long("id") }
                .one()
                .awaitSingle()
            return findSummary(id)
        } catch (_: DataIntegrityViolationException) {
            return null
        }
    }

    @Transactional
    override suspend fun update(
        operatorId: Long,
        command: UpdateAdminOperatorCommand,
        updatedBy: String,
    ): AdminOperatorSummary? {
        val current = findCredential(operatorId) ?: return null
        val nextStatus = command.status ?: current.status
        if (current.username == updatedBy && nextStatus == "DISABLED") {
            throw IllegalArgumentException("You cannot disable your own administrator account.")
        }
        if (current.status == "ACTIVE" && nextStatus == "DISABLED") {
            val activeIds = database.sql(
                "select id from admin_accounts where status = 'ACTIVE' for update",
            ).map { row, _ -> row.long("id") }.all().collectList().awaitSingle()
            if (activeIds.size <= 1) {
                throw IllegalArgumentException("At least one active administrator account is required.")
            }
        }

        val now = Instant.now()
        val spec = database.sql(
            """
            update admin_accounts
            set display_name = :displayName,
                status = :status,
                password_hash = :passwordHash,
                updated_by = :updatedBy,
                updated_at = :now
            where id = :id
            """.trimIndent(),
        ).bind("displayName", command.displayName ?: current.displayName)
            .bind("status", nextStatus)
            .bind("passwordHash", command.password?.let { requireNotNull(passwordEncoder.encode(it)) } ?: current.passwordHash)
            .bind("updatedBy", updatedBy)
            .bind("now", now)
            .bind("id", operatorId)
        spec.fetch().rowsUpdated().awaitSingle()
        return findSummary(operatorId)
    }

    private suspend fun findCredential(username: String): AdminCredentialRow? =
        database.sql(
            """
            select id, username, display_name, password_hash, status
            from admin_accounts
            where username = :username
            """.trimIndent(),
        ).bind("username", username)
            .map { row, _ -> row.toCredential() }
            .one()
            .awaitSingleOrNull()

    private suspend fun findCredential(id: Long): AdminCredentialRow? =
        database.sql(
            """
            select id, username, display_name, password_hash, status
            from admin_accounts
            where id = :id
            """.trimIndent(),
        ).bind("id", id)
            .map { row, _ -> row.toCredential() }
            .one()
            .awaitSingleOrNull()

    private suspend fun findSummary(id: Long): AdminOperatorSummary? =
        database.sql(
            """
            select id, username, display_name, status, last_login_at, created_at, updated_at
            from admin_accounts
            where id = :id
            """.trimIndent(),
        ).bind("id", id)
            .map { row, _ -> row.toSummary() }
            .one()
            .awaitSingleOrNull()

    private fun DatabaseClient.GenericExecuteSpec.bindQuery(query: String?): DatabaseClient.GenericExecuteSpec =
        if (query == null) this else bind("query", query)

    private fun Row.toCredential() = AdminCredentialRow(
        id = long("id"),
        username = string("username"),
        displayName = string("display_name"),
        passwordHash = string("password_hash"),
        status = string("status"),
    )

    private fun Row.toSummary() = AdminOperatorSummary(
        id = long("id"),
        username = string("username"),
        displayName = string("display_name"),
        status = string("status"),
        lastLoginAt = instant("last_login_at"),
        createdAt = instant("created_at") ?: Instant.EPOCH,
        updatedAt = instant("updated_at") ?: Instant.EPOCH,
    )

    private fun Row.string(name: String): String = get(name, String::class.java).orEmpty()
    private fun Row.long(name: String): Long = get(name, java.lang.Long::class.java)?.toLong() ?: 0L
    private fun Row.instant(name: String): Instant? =
        get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    private data class AdminCredentialRow(
        val id: Long,
        val username: String,
        val displayName: String,
        val passwordHash: String,
        val status: String,
    ) {
        fun toPrincipal() = AdminOperatorPrincipal(id, username, displayName, status)
    }
}
