package com.buddystuddy.backend.auth.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.model.AccessResponse
import com.buddystuddy.backend.auth.application.model.AccessUserResponse
import com.buddystuddy.backend.auth.application.model.PageAccessResponse
import com.buddystuddy.backend.auth.application.permission.PermissionChecker
import com.buddystuddy.backend.auth.application.permission.Permissions
import com.buddystuddy.backend.auth.application.port.inbound.AccessUseCase
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccessService(
    private val users: UserPort,
    private val permissionChecker: PermissionChecker,
) : AccessUseCase {
    @Transactional(readOnly = true)
    override fun access(principal: Principal): AccessResponse {
        val user = users.findById(principal.userId).orElseThrow {
            ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
        }
        return AccessResponse(
            user = AccessUserResponse(user.id, user.status, user.displayName),
            pageAccess = PageAccessResponse(
                home = true,
                publicQuestions = can(principal, Permissions.PUBLIC_QUESTION_READ),
                myStudies = can(principal, Permissions.STUDY_READ),
                studyRoom = can(principal, Permissions.STUDY_READ, Permissions.RECORD_UPDATE),
                records = can(principal, Permissions.RECORD_READ),
                stats = can(principal, Permissions.STATS_READ),
                profile = can(principal, Permissions.PROFILE_READ),
                developer = canAny(principal, Permissions.DEBUG_READ, Permissions.TEST_PUSH_SEND),
                admin = canAny(principal, Permissions.ADMIN_READ, Permissions.ADMIN_WRITE),
            ),
        )
    }

    private fun can(principal: Principal, vararg permissions: String): Boolean =
        permissionChecker.has(principal, permissions.toList())

    private fun canAny(principal: Principal, vararg permissions: String): Boolean =
        permissions.any { permissionChecker.has(principal, listOf(it)) }
}
