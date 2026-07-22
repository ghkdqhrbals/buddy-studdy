package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.auth.application.permission.PermissionChecker
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PermissionWebGuard(
    private val permissionChecker: PermissionChecker,
) {
    suspend fun check(authentication: Authentication, vararg requiredPermissions: String) {
        val principal = authentication.optionalPrincipal()
        val requestDetails = authentication.details as? ReactiveRequestDetails
        permissionChecker.check(
            principal,
            requiredPermissions.asList(),
            principal?.let {
                PermissionEvaluationContext(
                    now = Instant.now(),
                    appVersion = requestDetails?.appVersion,
                    sessionId = it.sessionId,
                    status = it.status,
                    anonymous = it.anonymous,
                )
            },
        )
    }
}
