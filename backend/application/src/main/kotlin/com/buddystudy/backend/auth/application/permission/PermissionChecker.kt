package com.buddystudy.backend.auth.application.permission

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PermissionChecker(
    private val evaluator: PermissionEvaluator,
) {
    fun check(
        principal: Principal?,
        requiredPermissions: Collection<String>,
        context: PermissionEvaluationContext? = null,
    ) {
        val required = requiredPermissions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (required.isEmpty()) return
        if (principal == null) {
            throw ApiException(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED,
                "Access token is required.",
                requiredPermissions = required,
            )
        }

        required.forEach { permission ->
            val result = evaluator.evaluate(
                userId = principal.userId,
                deviceId = principal.deviceId,
                permissionCode = permission,
                context = context ?: PermissionEvaluationContext(
                    now = Instant.now(),
                    sessionId = principal.sessionId,
                    status = principal.status,
                    anonymous = principal.anonymous,
                ),
            )
            if (!result.granted) {
                val failureCode = result.failureCode ?: ApiErrorCode.PERMISSION_DENIED
                throw ApiRuntimeException(
                    errorCode = failureCode,
                    message = result.reason ?: failureCode.debugDescription,
                    requiredPermissions = listOf(permission),
                    requiredTerms = result.requiredTerms,
                    requiredActions = result.requiredActions.map { it.name },
                    metadata = result.metadata,
                )
            }
        }
    }

    fun has(principal: Principal, requiredPermissions: Collection<String>): Boolean =
        runCatching { check(principal, requiredPermissions) }.isSuccess
}
