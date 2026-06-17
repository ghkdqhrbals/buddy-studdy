package com.buddystuddy.backend.auth.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.model.AccessResponse
import com.buddystuddy.backend.auth.application.model.AccessUserResponse
import com.buddystuddy.backend.auth.application.model.PageAccessResponse
import com.buddystuddy.backend.auth.application.permission.Permissions
import com.buddystuddy.backend.auth.application.port.inbound.AccessUseCase
import com.buddystuddy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystuddy.backend.auth.application.port.outbound.UserPermissionProjection
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccessService(
    private val users: UserPort,
    private val permissions: PermissionQueryPort,
) : AccessUseCase {
    @Transactional(readOnly = true)
    override fun access(principal: Principal): AccessResponse {
        val user = users.findById(principal.userId).orElseThrow {
            ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
        }
        val granted = permissions.permissionsForUser(principal.userId).associateBy { it.code }
        return AccessResponse(
            user = AccessUserResponse(user.id, user.status, user.displayName, user.createdAt),
            pageAccess = PageAccessResponse(
                home = true,
                publicQuestions = can(granted, user.status, Permissions.PUBLIC_QUESTION_READ),
                myStudies = can(granted, user.status, Permissions.STUDY_READ),
                studyRoom = can(granted, user.status, Permissions.STUDY_READ, Permissions.RECORD_UPDATE),
                records = can(granted, user.status, Permissions.RECORD_READ),
                stats = can(granted, user.status, Permissions.STATS_READ),
                profile = can(granted, user.status, Permissions.PROFILE_READ),
                developer = canAny(granted, user.status, Permissions.DEBUG_READ, Permissions.TEST_PUSH_SEND),
                admin = canAny(granted, user.status, Permissions.ADMIN_READ, Permissions.ADMIN_WRITE),
            ),
        )
    }

    private fun can(granted: Map<String, UserPermissionProjection>, status: String, vararg permissions: String): Boolean =
        permissions.all { permission ->
            val projection = granted[permission] ?: return false
            !projection.requiresActiveAccount || status !in FORBIDDEN_WRITE_STATUSES
        }

    private fun canAny(granted: Map<String, UserPermissionProjection>, status: String, vararg permissions: String): Boolean =
        permissions.any { can(granted, status, it) }

    private companion object {
        private val FORBIDDEN_WRITE_STATUSES = setOf("SUSPENDED", "WITHDRAWN")
    }
}
