package com.buddystudy.backend.auth.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.PermissionEvaluationResponse
import com.buddystudy.backend.auth.application.model.PermissionEvaluationsResponse
import com.buddystudy.backend.auth.application.model.TermsAgreementCommand
import com.buddystudy.backend.auth.application.model.TermsResponse
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.port.inbound.PermissionEvaluationUseCase
import com.buddystudy.backend.auth.application.port.inbound.TermsUseCase
import com.buddystudy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementCommandPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementQueryPort
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
    private val permissions: PermissionQueryPort,
    private val evaluator: PermissionEvaluator,
) : TermsUseCase, PermissionEvaluationUseCase {
    @Transactional(readOnly = true)
    override fun activeTerms(): List<TermsResponse> =
        terms.activeTerms(Instant.now()).map {
            TermsResponse(it.code, it.version, it.title, it.url, it.contentHash)
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

    private companion object {
        private val AGREEMENT_ACTIONS = setOf("AGREED", "WITHDRAWN")
        private val AGREEMENT_SOURCES = setOf("SIGNUP", "SETTINGS", "REQUIRED_GATE", "MIGRATION")
    }
}
