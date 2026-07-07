package com.buddystudy.backend.auth

import com.buddystudy.backend.auth.application.model.TermsAgreementCommand
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationResult
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.port.outbound.ActiveTermsProjection
import com.buddystudy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementCommandPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementQueryPort
import com.buddystudy.backend.auth.application.port.outbound.UserPermissionProjection
import com.buddystudy.backend.auth.application.service.PermissionPolicyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class PermissionPolicyServiceTest {
    private val terms = FakeTermsAgreementQueryPort()
    private val agreements = FakeTermsAgreementCommandPort()
    private val permissions = FakePermissionQueryPort()
    private val evaluator = FakePermissionEvaluator()
    private val service = PermissionPolicyService(
        terms = terms,
        termAgreements = agreements,
        permissions = permissions,
        evaluator = evaluator,
    )

    @Test
    fun `first profile terms agreement is saved when no prior user agreement exists`() {
        terms.active += ActiveTermsProjection(
            id = 42,
            code = "TERMS_OF_SERVICE",
            version = "2026-07-07",
            title = "서비스 이용약관",
            url = "https://example.com/terms",
            contentHash = "sha256:test",
        )
        val principal = Principal(
            userId = 7,
            deviceId = "dev-1",
            sessionId = 11,
            anonymous = false,
            status = "ACTIVE",
        )

        val result = service.saveAgreement(
            principal,
            TermsAgreementCommand(
                code = "terms_of_service",
                action = "AGREED",
                source = "PROFILE",
                ipAddress = "127.0.0.1",
                userAgent = "test",
                appVersion = "1.0.0",
            ),
        )

        assertThat(result.permissions).isEmpty()
        assertThat(agreements.saved).containsExactly(
            SavedAgreement(
                userId = 7,
                deviceId = "dev-1",
                termsId = 42,
                action = "AGREED",
                source = "PROFILE",
            ),
        )
    }

    private data class SavedAgreement(
        val userId: Long?,
        val deviceId: String?,
        val termsId: Long,
        val action: String,
        val source: String,
    )

    private class FakeTermsAgreementQueryPort : TermsAgreementQueryPort {
        val active = mutableListOf<ActiveTermsProjection>()

        override fun activeTerms(now: Instant): List<ActiveTermsProjection> = active

        override fun activeTerms(code: String, now: Instant): ActiveTermsProjection? =
            active.firstOrNull { it.code == code.trim().uppercase() }

        override fun hasAgreement(userId: Long?, deviceId: String?, termsId: Long): Boolean = false
    }

    private class FakeTermsAgreementCommandPort : TermsAgreementCommandPort {
        val saved = mutableListOf<SavedAgreement>()

        override fun saveAgreement(
            userId: Long?,
            deviceId: String?,
            termsId: Long,
            action: String,
            source: String,
            ipAddress: String?,
            userAgent: String?,
            appVersion: String?,
            now: Instant,
        ) {
            saved += SavedAgreement(
                userId = userId,
                deviceId = deviceId,
                termsId = termsId,
                action = action,
                source = source,
            )
        }
    }

    private class FakePermissionQueryPort : PermissionQueryPort {
        override fun permissionsForUser(userId: Long): Set<UserPermissionProjection> = emptySet()
    }

    private class FakePermissionEvaluator : PermissionEvaluator {
        override fun evaluate(
            userId: Long,
            deviceId: String,
            permissionCode: String,
            context: PermissionEvaluationContext,
        ): PermissionEvaluationResult = PermissionEvaluationResult.granted(permissionCode)
    }
}
