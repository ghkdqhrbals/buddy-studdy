package com.buddystudy.backend.auth.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.AccessResponse
import com.buddystudy.backend.auth.application.model.AccessUserResponse
import com.buddystudy.backend.auth.application.model.PageAccessResponse
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.port.inbound.AccessUseCase
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccessService(
    private val users: UserPort,
    private val permissions: PermissionEvaluator,
) : AccessUseCase {
    @Transactional(readOnly = true)
    override fun access(principal: Principal): AccessResponse {
        val user = users.findById(principal.userId).orElseThrow {
            ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
        }
        return AccessResponse(
            user = AccessUserResponse(user.id, user.status, user.displayName, user.createdAt),
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

    private fun can(principal: Principal, vararg permissionCodes: String): Boolean =
        permissionCodes.all { permissions.evaluate(principal, it).granted }

    private fun canAny(principal: Principal, vararg permissionCodes: String): Boolean =
        permissionCodes.any { can(principal, it) }
}
