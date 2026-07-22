package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.UserRoleEntity
import com.buddystudy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserPermissionProjection
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PermissionPersistenceAdapter(
    private val roles: RoleRepository,
    private val userRoles: UserRoleRepository,
    private val databaseClient: DatabaseClient,
) : PermissionQueryPort, RoleAssignmentPort {
    override suspend fun permissionsForUser(userId: Long): Set<UserPermissionProjection> =
        databaseClient.sql(
            """
            select distinct p.code, p.requires_active_account
            from user_roles ur
            join role_permissions rp on rp.role_id = ur.role_id
            join permissions p on p.id = rp.permission_id
            where ur.user_id = :userId
            """.trimIndent(),
        )
            .bind("userId", userId)
            .map { row, _ ->
                UserPermissionProjection(
                    code = requireNotNull(row.get("code", String::class.java)),
                    requiresActiveAccount =
                        row.get("requires_active_account", java.lang.Boolean::class.java)?.booleanValue() ?: false,
                )
            }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()

    override suspend fun grantRoleIfMissing(userId: Long, roleCode: String) {
        val role = roles.findByCode(roleCode) ?: return
        if (!userRoles.existsByUserIdAndRoleId(userId, role.id)) {
            val now = Instant.now()
            userRoles.save(UserRoleEntity(userId = userId, roleId = role.id, createdAt = now, updatedAt = now))
        }
    }

    override suspend fun countUserRoles(userId: Long, roleCode: String): Long {
        val role = roles.findByCode(roleCode) ?: return 0
        return userRoles.countByUserIdAndRoleId(userId, role.id)
    }
}
