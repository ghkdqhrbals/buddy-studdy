package com.buddystudy.backend.auth.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.NotificationPreferenceCommand
import com.buddystudy.backend.auth.application.model.NotificationPreferenceResponse
import com.buddystudy.backend.auth.application.model.PermissionEvaluationResponse
import com.buddystudy.backend.auth.application.model.PermissionEvaluationsResponse
import com.buddystudy.backend.auth.application.model.TermsAgreementCommand
import com.buddystudy.backend.auth.application.model.TermsResponse
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.port.inbound.NotificationPreferenceUseCase
import com.buddystudy.backend.auth.application.port.inbound.PermissionEvaluationUseCase
import com.buddystudy.backend.auth.application.port.inbound.TermsUseCase
import com.buddystudy.backend.auth.application.port.outbound.NotificationPreferenceCommandPort
import com.buddystudy.backend.auth.application.port.outbound.NotificationPreferenceQueryPort
import com.buddystudy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementCommandPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementQueryPort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PermissionPolicyService(
    private val terms: TermsAgreementQueryPort,
    private val termAgreements: TermsAgreementCommandPort,
    private val notificationPreferences: NotificationPreferenceQueryPort,
    private val notificationPreferenceCommands: NotificationPreferenceCommandPort,
    private val permissions: PermissionQueryPort,
    private val evaluator: PermissionEvaluator,
    private val users: UserPort,
) : TermsUseCase, PermissionEvaluationUseCase, NotificationPreferenceUseCase {
    @Transactional(readOnly = true)
    override fun activeTerms(principal: Principal?): List<TermsResponse> {
        val userId = principal?.userId?.takeUnless { principal.anonymous }
        val deviceId = principal?.deviceId
        return terms.activeTerms(userId, deviceId, Instant.now()).map {
            TermsResponse(it.code, it.version, it.title, it.url, it.contentHash)
                .copy(required = it.required, mutable = it.mutable, agreed = it.agreed)
        }
    }

    @Transactional
    override fun saveAgreement(principal: Principal, command: TermsAgreementCommand): PermissionEvaluationsResponse {
        val action = command.action.trim().uppercase()
        if (action !in AGREEMENT_ACTIONS) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Invalid terms agreement action.")
        }
        val source = command.source.trim().uppercase().ifBlank { "SETTINGS" }
        if (source !in AGREEMENT_SOURCES) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Invalid terms agreement source.")
        }
        if (source == "PROFILE" && principal.anonymous) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED, "Profile terms agreement requires an active login.")
        }
        val activeTerms = terms.activeTerms(command.code.trim(), Instant.now())
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Active terms were not found.")

        termAgreements.saveAgreement(
            userId = principal.userId.takeUnless { principal.anonymous },
            deviceId = principal.deviceId,
            termsId = activeTerms.id,
            action = action,
            source = source,
            ipAddress = command.ipAddress,
            userAgent = command.userAgent,
            appVersion = command.appVersion,
            now = Instant.now(),
        )
        activateUserIfRequiredTermsAreAgreed(principal)

        return permissions(
            principal,
            PermissionEvaluationContext(
                now = Instant.now(),
                appVersion = command.appVersion,
                sessionId = principal.sessionId,
                status = principal.status,
                anonymous = principal.anonymous,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun permissions(
        principal: Principal,
        context: PermissionEvaluationContext,
    ): PermissionEvaluationsResponse =
        PermissionEvaluationsResponse(
            permissions = permissions.permissionsForUser(principal.userId)
                .map {
                    evaluator.evaluate(
                        userId = principal.userId,
                        deviceId = principal.deviceId,
                        permissionCode = it.code,
                        context = context.copy(
                            sessionId = principal.sessionId,
                            status = principal.status,
                            anonymous = principal.anonymous,
                        ),
                    ).toResponse()
                }
                .sortedBy { it.permissionCode }
        )

    private fun com.buddystudy.backend.auth.application.permission.PermissionEvaluationResult.toResponse(): PermissionEvaluationResponse =
        PermissionEvaluationResponse(
            permissionCode = permissionCode,
            granted = granted,
            failureCode = failureCode?.name,
            reason = reason,
            requiredTerms = requiredTerms.map { TermsResponse(it.code, it.version, it.title, it.url, it.contentHash) },
            requiredActions = requiredActions.map { it.name },
            metadata = metadata,
        )

    @Transactional(readOnly = true)
    override fun notificationPreferences(principal: Principal): List<NotificationPreferenceResponse> =
        listOf(
            NotificationPreferenceResponse(
                key = QUESTION_NOTIFICATION_PREFERENCE,
                enabled = notificationPreferences.isEnabled(
                    principal.userId.takeUnless { principal.anonymous },
                    principal.deviceId,
                    QUESTION_NOTIFICATION_PREFERENCE,
                )
            ),
            NotificationPreferenceResponse(
                key = MARKETING_NOTIFICATION_PREFERENCE,
                enabled = notificationPreferences.isEnabled(
                    principal.userId.takeUnless { principal.anonymous },
                    principal.deviceId,
                    MARKETING_NOTIFICATION_PREFERENCE,
                )
            )
        )

    @Transactional
    override fun saveNotificationPreference(
        principal: Principal,
        command: NotificationPreferenceCommand,
    ): NotificationPreferenceResponse {
        if (principal.anonymous) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED, "Notification preferences require login.")
        }
        val key = command.key.trim().lowercase()
        if (key !in ALLOWED_NOTIFICATION_PREFERENCES) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Invalid notification preference key.")
        }
        notificationPreferenceCommands.savePreference(
            userId = principal.userId,
            deviceId = principal.deviceId,
            key = key,
            enabled = command.enabled,
            now = Instant.now(),
        )
        return NotificationPreferenceResponse(key = key, enabled = command.enabled)
    }

    private fun activateUserIfRequiredTermsAreAgreed(principal: Principal) {
        if (principal.anonymous) return
        val user = users.findById(principal.userId).orElse(null) ?: return
        if (user.status != "PENDING_TERMS") return
        if (!terms.hasRequiredAgreements(user.id, principal.deviceId, Instant.now())) return
        user.status = "ACTIVE"
        user.updatedAt = Instant.now()
        users.save(user)
    }

    private companion object {
        private val AGREEMENT_ACTIONS = setOf("AGREED", "WITHDRAWN")
        private val AGREEMENT_SOURCES = setOf("SIGNUP", "SETTINGS", "PROFILE", "REQUIRED_GATE", "MIGRATION")
        private const val QUESTION_NOTIFICATION_PREFERENCE = "question_notification"
        private const val MARKETING_NOTIFICATION_PREFERENCE = "marketing_notification"
        private val ALLOWED_NOTIFICATION_PREFERENCES = setOf(
            QUESTION_NOTIFICATION_PREFERENCE,
            MARKETING_NOTIFICATION_PREFERENCE,
        )
    }
}
