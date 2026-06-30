package com.buddystudy.backend.auth.application.port.outbound

data class UserPermissionProjection(
    val code: String,
    val requiresActiveAccount: Boolean,
)

interface PermissionQueryPort {
    fun permissionsForUser(userId: Long): Set<UserPermissionProjection>
}

interface RoleAssignmentPort {
    fun grantRoleIfMissing(userId: Long, roleCode: String)
    fun countUserRoles(userId: Long, roleCode: String): Long
}
