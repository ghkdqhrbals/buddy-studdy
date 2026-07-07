package com.buddystudy.backend.auth.adapter.inbound.web

import com.buddystudy.backend.auth.application.model.NotificationPreferenceCommand
import com.buddystudy.backend.auth.application.model.NotificationPreferenceResponse
import com.buddystudy.backend.auth.application.model.PermissionEvaluationsResponse
import com.buddystudy.backend.auth.application.model.TermsAgreementCommand
import com.buddystudy.backend.auth.application.model.TermsResponse
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.port.inbound.NotificationPreferenceUseCase
import com.buddystudy.backend.auth.application.port.inbound.PermissionEvaluationUseCase
import com.buddystudy.backend.auth.application.port.inbound.TermsUseCase
import com.buddystudy.backend.common.adapter.inbound.web.ClientIpResolver
import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class PermissionPolicyController(
    private val terms: TermsUseCase,
    private val permissions: PermissionEvaluationUseCase,
    private val notificationPreferences: NotificationPreferenceUseCase,
) {
    @GetMapping("/terms/active")
    fun activeTerms(authentication: Authentication): List<TermsResponse> =
        terms.activeTerms(authentication.principalOrThrow())

    @PostMapping("/terms/agreements")
    fun agreements(
        authentication: Authentication,
        request: HttpServletRequest,
        @RequestBody body: TermsAgreementRequest,
        @RequestHeader("User-Agent", required = false) userAgent: String?,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
    ): PermissionEvaluationsResponse =
        terms.saveAgreement(
            authentication.principalOrThrow(),
            TermsAgreementCommand(
                code = body.code,
                action = body.action,
                source = body.source,
                ipAddress = ClientIpResolver.resolve(request),
                userAgent = userAgent,
                appVersion = appVersion,
            )
        )

    @GetMapping("/me/permissions")
    fun permissions(
        authentication: Authentication,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
    ): PermissionEvaluationsResponse {
        val principal = authentication.principalOrThrow()
        return permissions.permissions(
            principal,
            PermissionEvaluationContext(
                now = Instant.now(),
                appVersion = appVersion,
                sessionId = principal.sessionId,
                status = principal.status,
                anonymous = principal.anonymous,
            ),
        )
    }

    @GetMapping("/notification-preferences")
    fun notificationPreferences(authentication: Authentication): List<NotificationPreferenceResponse> =
        notificationPreferences.notificationPreferences(authentication.principalOrThrow())

    @PostMapping("/notification-preferences")
    fun saveNotificationPreference(
        authentication: Authentication,
        @RequestBody body: NotificationPreferenceRequest,
    ): NotificationPreferenceResponse {
        val key = body.key.trim()
        if (key.isBlank()) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Notification preference key is required.")
        }
        return notificationPreferences.saveNotificationPreference(
            authentication.principalOrThrow(),
            NotificationPreferenceCommand(
                key = key,
                enabled = body.enabled,
            )
        )
    }
}

data class TermsAgreementRequest(
    val code: String = "",
    val action: String = "AGREED",
    val source: String = "SETTINGS",
)

data class NotificationPreferenceRequest(
    val key: String = "",
    val enabled: Boolean = false,
)
