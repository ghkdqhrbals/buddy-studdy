package com.buddystuddy.backend.auth.application.permission

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class PermissionChecker(
    private val permissions: PermissionQueryPort,
) {
    fun check(principal: Principal?, requiredPermissions: Collection<String>) {
        val required = requiredPermissions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (required.isEmpty()) return
        if (principal == null) {
            throw ApiException(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED,
                "Access token is required.",
                requiredPermissions = required,
                loginRequired = true,
            )
        }

        val granted = permissions.permissionsForUser(principal.userId)
        val grantedByCode = granted.associateBy { it.code }
        val missing = required.filter { grantedByCode[it] == null }
        if (missing.isNotEmpty()) {
            throw ApiException(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.PERMISSION_DENIED,
                "Permission denied.",
                requiredPermissions = missing,
                loginRequired = principal.anonymous,
            )
        }

        val activeRequired = required.any { grantedByCode[it]?.requiresActiveAccount == true }
        if (activeRequired && principal.status in FORBIDDEN_WRITE_STATUSES) {
            throw ApiException(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.ACCOUNT_FORBIDDEN,
                "Account is not allowed to perform this action.",
                requiredPermissions = required,
                loginRequired = false,
            )
        }
    }

    fun has(principal: Principal, requiredPermissions: Collection<String>): Boolean =
        runCatching { check(principal, requiredPermissions) }.isSuccess

    private companion object {
        private val FORBIDDEN_WRITE_STATUSES = setOf("SUSPENDED", "WITHDRAWN")
    }
}
