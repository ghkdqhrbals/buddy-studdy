package com.buddystudy.backend.auth

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.model.NotificationPreferenceCommand
import com.buddystudy.backend.auth.application.model.NotificationPreferenceType
import com.buddystudy.backend.auth.application.model.TermsAgreementCommand
import com.buddystudy.backend.auth.application.model.TermsType
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationResult
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.port.outbound.NotificationPreferenceCommandPort
import com.buddystudy.backend.auth.application.port.outbound.NotificationPreferenceQueryPort
import com.buddystudy.backend.auth.application.port.outbound.ActiveTermsProjection
import com.buddystudy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementCommandPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementQueryPort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.auth.application.port.outbound.UserPermissionProjection
import com.buddystudy.backend.auth.application.service.PermissionPolicyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class PermissionPolicyServiceTest {
    private val terms = FakeTermsAgreementQueryPort()
    private val agreements = FakeTermsAgreementCommandPort()
    private val permissions = FakePermissionQueryPort()
    private val evaluator = FakePermissionEvaluator()
    private val notificationPreferences = FakeNotificationPreferencePort()
    private val users = FakeUserPort()
    private val service = PermissionPolicyService(
        terms = terms,
        termAgreements = agreements,
        permissions = permissions,
        evaluator = evaluator,
        notificationPreferences = notificationPreferences,
        notificationPreferenceCommands = notificationPreferences,
        users = users,
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
            required = true,
            mutable = false,
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
                type = TermsType.TERMS_OF_SERVICE,
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

    @Test
    fun `question notification preference can be saved`() {
        val principal = Principal(
            userId = 7,
            deviceId = "dev-1",
            sessionId = 11,
            anonymous = false,
            status = "ACTIVE",
        )

        val result = service.saveNotificationPreference(
            principal,
            NotificationPreferenceCommand(
                type = NotificationPreferenceType.QUESTION_NOTIFICATION,
                enabled = true,
            ),
        )

        assertThat(result.type).isEqualTo(NotificationPreferenceType.QUESTION_NOTIFICATION)
        assertThat(result.key).isEqualTo("question_notification")
        assertThat(result.enabled).isTrue()
        assertThat(notificationPreferences.saved).containsExactly(
            SavedNotificationPreference(
                userId = 7,
                deviceId = "dev-1",
                key = "question_notification",
                enabled = true,
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

    private data class SavedNotificationPreference(
        val userId: Long?,
        val deviceId: String,
        val key: String,
        val enabled: Boolean,
    )

    private class FakeTermsAgreementQueryPort : TermsAgreementQueryPort {
        val active = mutableListOf<ActiveTermsProjection>()

        override fun activeTerms(now: Instant): List<ActiveTermsProjection> = active

        override fun activeTerms(userId: Long?, deviceId: String?, now: Instant): List<ActiveTermsProjection> = active

        override fun activeTerms(code: String, now: Instant): ActiveTermsProjection? =
            active.firstOrNull { it.code == code.trim().uppercase() }

        override fun hasAgreement(userId: Long?, deviceId: String?, termsId: Long): Boolean = false

        override fun hasRequiredAgreements(userId: Long, deviceId: String?, now: Instant): Boolean = true
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

    private class FakeNotificationPreferencePort : NotificationPreferenceQueryPort, NotificationPreferenceCommandPort {
        val saved = mutableListOf<SavedNotificationPreference>()

        override fun isEnabled(userId: Long?, deviceId: String, key: String): Boolean = false

        override fun savePreference(userId: Long?, deviceId: String, key: String, enabled: Boolean, now: Instant) {
            saved += SavedNotificationPreference(
                userId = userId,
                deviceId = deviceId,
                key = key,
                enabled = enabled,
            )
        }
    }

    private class FakeUserPort : UserPort {
        private val users = mutableMapOf<Long, UserEntity>()

        override fun save(entity: UserEntity): UserEntity {
            users[entity.id] = entity
            return entity
        }

        override fun findById(id: Long): Optional<UserEntity> = Optional.ofNullable(users[id])

        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> =
            ids.mapNotNull { users[it] }.toMutableList()

        override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null

        override fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
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
