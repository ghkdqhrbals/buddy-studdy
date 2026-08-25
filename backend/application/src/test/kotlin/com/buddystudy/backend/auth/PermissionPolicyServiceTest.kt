package com.buddystudy.backend.auth

import kotlinx.coroutines.runBlocking

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
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    fun `first profile terms agreement is saved when no prior user agreement exists`(): Unit = runBlocking {
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
    fun `privacy policy 2026 08 25 requires the exact active version and content hash`(): Unit = runBlocking {
        terms.active += privacyPolicy(
            id = 84,
            version = "2026-08-25",
            contentHash = "sha256:2026-08-25",
        )
        val principal = activePrincipal()

        assertThatThrownBy {
            runBlocking {
                service.saveAgreement(
                    principal,
                    TermsAgreementCommand(TermsType.PRIVACY_POLICY, "AGREED", "REQUIRED_GATE"),
                )
            }
        }.isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThatThrownBy {
            runBlocking {
                service.saveAgreement(
                    principal,
                    TermsAgreementCommand(
                        type = TermsType.PRIVACY_POLICY,
                        action = "AGREED",
                        source = "REQUIRED_GATE",
                        version = "2026-08-14",
                        contentHash = "sha256:2026-08-25",
                    ),
                )
            }
        }.isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThatThrownBy {
            runBlocking {
                service.saveAgreement(
                    principal,
                    TermsAgreementCommand(
                        type = TermsType.PRIVACY_POLICY,
                        action = "AGREED",
                        source = "REQUIRED_GATE",
                        version = "2026-08-25",
                        contentHash = "sha256:wrong",
                    ),
                )
            }
        }.isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.VALIDATION_ERROR)

        service.saveAgreement(
            principal,
            TermsAgreementCommand(
                type = TermsType.PRIVACY_POLICY,
                action = "AGREED",
                source = "REQUIRED_GATE",
                version = "2026-08-25",
                contentHash = "sha256:2026-08-25",
            ),
        )

        assertThat(agreements.saved).containsExactly(
            SavedAgreement(principal.userId, principal.deviceId, 84, "AGREED", "REQUIRED_GATE"),
        )
    }

    @Test
    fun `privacy policy before the exact identity cutover keeps legacy type only agreement compatibility`(): Unit = runBlocking {
        terms.active += privacyPolicy(
            id = 77,
            version = "2026-08-14",
            contentHash = "sha256:2026-08-14",
        )
        val principal = activePrincipal()

        service.saveAgreement(
            principal,
            TermsAgreementCommand(TermsType.PRIVACY_POLICY, "AGREED", "SETTINGS"),
        )

        assertThat(agreements.saved).containsExactly(
            SavedAgreement(principal.userId, principal.deviceId, 77, "AGREED", "SETTINGS"),
        )
    }

    @Test
    fun `question notification preference can be saved`(): Unit = runBlocking {
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

    private fun activePrincipal() = Principal(
        userId = 7,
        deviceId = "dev-1",
        sessionId = 11,
        anonymous = false,
        status = "ACTIVE",
    )

    private fun privacyPolicy(id: Long, version: String, contentHash: String) = ActiveTermsProjection(
        id = id,
        code = TermsType.PRIVACY_POLICY.code,
        version = version,
        title = "개인정보 처리방침",
        url = "https://example.com/privacy-$version",
        contentHash = contentHash,
        required = true,
        mutable = false,
    )

    private class FakeTermsAgreementQueryPort : TermsAgreementQueryPort {
        val active = mutableListOf<ActiveTermsProjection>()

        override suspend fun activeTerms(now: Instant): List<ActiveTermsProjection> = active

        override suspend fun activeTerms(userId: Long?, deviceId: String?, now: Instant): List<ActiveTermsProjection> = active

        override suspend fun activeTerms(code: String, now: Instant): ActiveTermsProjection? =
            active.firstOrNull { it.code == code.trim().uppercase() }

        override suspend fun hasAgreement(userId: Long?, deviceId: String?, termsId: Long): Boolean = false

        override suspend fun hasRequiredAgreements(userId: Long, deviceId: String?, now: Instant): Boolean = true
    }

    private class FakeTermsAgreementCommandPort : TermsAgreementCommandPort {
        val saved = mutableListOf<SavedAgreement>()

        override suspend fun saveAgreement(
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
        override suspend fun permissionsForUser(userId: Long): Set<UserPermissionProjection> = emptySet()
    }

    private class FakeNotificationPreferencePort : NotificationPreferenceQueryPort, NotificationPreferenceCommandPort {
        val saved = mutableListOf<SavedNotificationPreference>()

        override suspend fun isEnabled(userId: Long?, deviceId: String, key: String): Boolean = false

        override suspend fun savePreference(userId: Long?, deviceId: String, key: String, enabled: Boolean, now: Instant) {
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

        override suspend fun save(entity: UserEntity): UserEntity {
            users[entity.id] = entity
            return entity
        }

        override suspend fun findById(id: Long): UserEntity? = users[id]

        override suspend fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> =
            ids.mapNotNull { users[it] }.toMutableList()

        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null

        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakePermissionEvaluator : PermissionEvaluator {
        override suspend fun evaluate(
            userId: Long,
            deviceId: String,
            permissionCode: String,
            context: PermissionEvaluationContext,
        ): PermissionEvaluationResult = PermissionEvaluationResult.granted(permissionCode)
    }
}
