package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.PermissionEntity
import com.buddystudy.auth.domain.entity.RoleEntity
import com.buddystudy.auth.domain.entity.RolePermissionEntity
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.Roles
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import kotlinx.coroutines.runBlocking
import java.time.Instant

@Component
class PermissionSeedInitializer(
    private val roles: RoleRepository,
    private val permissions: PermissionRepository,
    private val rolePermissions: RolePermissionRepository,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = runBlocking {
        val rolesByCode = mutableMapOf<String, RoleEntity>()
        for ((code, name) in ROLE_DEFINITIONS) {
            rolesByCode[code] = upsertRole(code, name)
        }
        val permissionsByCode = mutableMapOf<String, PermissionEntity>()
        for (definition in PERMISSION_DEFINITIONS) {
            permissionsByCode[definition.code] = upsertPermission(definition)
        }

        for ((roleCode, permissionCodes) in ROLE_PERMISSION_DEFINITIONS) {
            val role = rolesByCode[roleCode] ?: continue
            for (permissionCode in permissionCodes) {
                val permission = permissionsByCode[permissionCode] ?: continue
                if (!rolePermissions.existsByRoleIdAndPermissionId(role.id, permission.id)) {
                    val now = Instant.now()
                    rolePermissions.save(RolePermissionEntity(roleId = role.id, permissionId = permission.id, createdAt = now, updatedAt = now))
                }
            }
        }
    }

    private suspend fun upsertRole(code: String, name: String): RoleEntity {
        val now = Instant.now()
        val role = roles.findByCode(code) ?: RoleEntity(code = code, name = name, createdAt = now)
        role.name = name
        role.updatedAt = now
        return roles.save(role)
    }

    private suspend fun upsertPermission(definition: PermissionDefinition): PermissionEntity {
        val now = Instant.now()
        val permission = permissions.findByCode(definition.code) ?: PermissionEntity(code = definition.code, createdAt = now)
        permission.description = definition.description
        permission.requiresActiveAccount = definition.requiresActiveAccount
        permission.updatedAt = now
        return permissions.save(permission)
    }

    private data class PermissionDefinition(
        val code: String,
        val description: String,
        val requiresActiveAccount: Boolean,
    )

    private companion object {
        private val ROLE_DEFINITIONS = listOf(
            Roles.ANONYMOUS_USER to "Anonymous User",
            Roles.REGISTERED_USER to "Registered User",
            "TESTER" to "Tester",
            "MODERATOR" to "Moderator",
            "ADMIN" to "Admin",
        )

        private val PERMISSION_DEFINITIONS = listOf(
            PermissionDefinition("device:register", "Register device", false),
            PermissionDefinition("auth:login", "Login and issue access token", false),
            PermissionDefinition(Permissions.PROFILE_READ, "Read profile", false),
            PermissionDefinition(Permissions.PROFILE_UPDATE, "Update profile", true),
            PermissionDefinition(Permissions.PROFILE_WITHDRAW, "Withdraw profile", true),
            PermissionDefinition(Permissions.STUDY_READ, "Read studies", false),
            PermissionDefinition(Permissions.STUDY_CREATE, "Create studies or questions", true),
            PermissionDefinition(Permissions.STUDY_UPDATE, "Update studies", true),
            PermissionDefinition(Permissions.STUDY_DELETE, "Delete studies", true),
            PermissionDefinition(Permissions.RECORD_READ, "Read records", false),
            PermissionDefinition(Permissions.RECORD_UPDATE, "Update records", true),
            PermissionDefinition(Permissions.RECORD_DELETE, "Delete records", true),
            PermissionDefinition(Permissions.RECORD_PUBLISH, "Publish records", true),
            PermissionDefinition(Permissions.NOTIFICATION_READ, "Read notifications", false),
            PermissionDefinition(Permissions.NOTIFICATION_DELETE, "Delete notifications", true),
            PermissionDefinition(Permissions.STATS_READ, "Read statistics", false),
            PermissionDefinition(Permissions.PUBLIC_QUESTION_READ, "Read public questions", false),
            PermissionDefinition(Permissions.PUBLIC_QUESTION_LIKE, "Like public questions", true),
            PermissionDefinition(Permissions.PUBLIC_QUESTION_COMMENT, "Comment on public questions", true),
            PermissionDefinition(Permissions.PUBLIC_QUESTION_REPORT, "Report public questions", true),
            PermissionDefinition(Permissions.COMMENT_DELETE, "Delete comments", true),
            PermissionDefinition(Permissions.DEBUG_READ, "Read debug logs", false),
            PermissionDefinition(Permissions.TEST_PUSH_SEND, "Send test push", true),
            PermissionDefinition(Permissions.ADMIN_READ, "Read admin resources", false),
            PermissionDefinition(Permissions.ADMIN_WRITE, "Write admin resources", true),
        )

        private val REGISTERED_PERMISSIONS = listOf(
            "device:register",
            "auth:login",
            Permissions.PROFILE_READ,
            Permissions.PROFILE_UPDATE,
            Permissions.PROFILE_WITHDRAW,
            Permissions.STUDY_READ,
            Permissions.STUDY_CREATE,
            Permissions.STUDY_UPDATE,
            Permissions.STUDY_DELETE,
            Permissions.RECORD_READ,
            Permissions.RECORD_UPDATE,
            Permissions.RECORD_DELETE,
            Permissions.RECORD_PUBLISH,
            Permissions.NOTIFICATION_READ,
            Permissions.NOTIFICATION_DELETE,
            Permissions.STATS_READ,
            Permissions.PUBLIC_QUESTION_READ,
            Permissions.PUBLIC_QUESTION_LIKE,
            Permissions.PUBLIC_QUESTION_COMMENT,
            Permissions.PUBLIC_QUESTION_REPORT,
            Permissions.COMMENT_DELETE,
        )

        private val ROLE_PERMISSION_DEFINITIONS = mapOf(
            Roles.ANONYMOUS_USER to listOf("device:register", "auth:login", Permissions.PROFILE_READ, Permissions.PUBLIC_QUESTION_READ),
            Roles.REGISTERED_USER to REGISTERED_PERMISSIONS,
            "TESTER" to listOf(Permissions.DEBUG_READ, Permissions.TEST_PUSH_SEND),
            "MODERATOR" to listOf(Permissions.PUBLIC_QUESTION_READ, Permissions.COMMENT_DELETE, Permissions.ADMIN_READ),
            "ADMIN" to PERMISSION_DEFINITIONS.map { it.code },
        )
    }
}
