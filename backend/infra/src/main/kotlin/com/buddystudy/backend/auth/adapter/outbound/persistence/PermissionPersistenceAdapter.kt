package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.UserRoleEntity
import com.buddystudy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserPermissionProjection
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface PermissionQueryRepository : org.springframework.data.repository.Repository<UserRoleEntity, Long> {
    @Query(
        """
        select distinct p.code as code, p.requiresActiveAccount as requiresActiveAccount
        from UserRoleEntity ur
        join RolePermissionEntity rp on rp.roleId = ur.roleId
        join PermissionEntity p on p.id = rp.permissionId
        where ur.userId = :userId
        """
    )
    fun permissionsForUser(@Param("userId") userId: Long): List<UserPermissionRow>
}

interface UserPermissionRow {
    val code: String
    val requiresActiveAccount: Boolean
}

@Component
class PermissionPersistenceAdapter(
    private val roles: RoleRepository,
    private val userRoles: UserRoleRepository,
    private val permissionQueries: PermissionQueryRepository,
) : PermissionQueryPort, RoleAssignmentPort {
    @Transactional(readOnly = true)
    override fun permissionsForUser(userId: Long): Set<UserPermissionProjection> =
        permissionQueries.permissionsForUser(userId)
            .map { UserPermissionProjection(it.code, it.requiresActiveAccount) }
            .toSet()

    @Transactional
    override fun grantRoleIfMissing(userId: Long, roleCode: String) {
        val role = roles.findByCode(roleCode) ?: return
        if (!userRoles.existsByUserIdAndRoleId(userId, role.id)) {
            val now = Instant.now()
            userRoles.save(UserRoleEntity(userId = userId, roleId = role.id, createdAt = now, updatedAt = now))
        }
    }

    @Transactional(readOnly = true)
    override fun countUserRoles(userId: Long, roleCode: String): Long {
        val role = roles.findByCode(roleCode) ?: return 0
        return userRoles.countByUserIdAndRoleId(userId, role.id)
    }
}
