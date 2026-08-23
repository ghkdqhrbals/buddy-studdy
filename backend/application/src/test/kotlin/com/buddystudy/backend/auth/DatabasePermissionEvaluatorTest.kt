package com.buddystudy.backend.auth

import kotlinx.coroutines.runBlocking

import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.backend.auth.application.permission.DatabasePermissionEvaluator
import com.buddystudy.backend.auth.application.permission.DeviceRegisteredRequirementEvaluator
import com.buddystudy.backend.auth.application.permission.EmailVerifiedRequirementEvaluator
import com.buddystudy.backend.auth.application.permission.MinAppVersionRequirementEvaluator
import com.buddystudy.backend.auth.application.permission.PermissionRequirementOperator
import com.buddystudy.backend.auth.application.permission.PermissionRequirementType
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.permission.PreferenceEnabledRequirementEvaluator
import com.buddystudy.backend.auth.application.permission.QuotaAvailableRequirementEvaluator
import com.buddystudy.backend.auth.application.permission.TermsAgreedRequirementEvaluator
import com.buddystudy.backend.auth.application.permission.UserStatusRequirementEvaluator
import com.buddystudy.backend.auth.application.port.outbound.ActiveTermsProjection
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationQueryPort
import com.buddystudy.backend.auth.application.port.outbound.NotificationPreferenceQueryPort
import com.buddystudy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystudy.backend.auth.application.port.outbound.PermissionQuotaQueryPort
import com.buddystudy.backend.auth.application.port.outbound.PermissionQuotaStatus
import com.buddystudy.backend.auth.application.port.outbound.PermissionRequirementProjection
import com.buddystudy.backend.auth.application.port.outbound.PermissionRequirementQueryPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementQueryPort
import com.buddystudy.backend.auth.application.port.outbound.UserPermissionProjection
import com.buddystudy.backend.auth.application.port.outbound.UserStatusQueryPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class DatabasePermissionEvaluatorTest {
    private val permissions = FakePermissionQueryPort()
    private val requirements = FakePermissionRequirementQueryPort()
    private val terms = FakeTermsAgreementQueryPort()
    private val preferences = FakeNotificationPreferenceQueryPort()
    private val quotas = FakePermissionQuotaQueryPort()
    private val devices = FakeDevicePort()
    private val users = FakeUserStatusQueryPort()
    private val emails = FakeEmailVerificationQueryPort()
    private val evaluator = DatabasePermissionEvaluator(
        permissions = permissions,
        requirements = requirements,
        users = users,
        evaluators = listOf(
            TermsAgreedRequirementEvaluator(terms),
            PreferenceEnabledRequirementEvaluator(preferences),
            QuotaAvailableRequirementEvaluator(quotas),
            DeviceRegisteredRequirementEvaluator(devices),
            UserStatusRequirementEvaluator(users),
            EmailVerifiedRequirementEvaluator(emails),
            MinAppVersionRequirementEvaluator(),
        ),
    )

    @Test
    fun `permission with no active requirements is granted from database permission`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection(Permissions.RECORD_READ, requiresActiveAccount = false)

        val result = evaluator.evaluate(principal(), Permissions.RECORD_READ)

        assertThat(result.granted).isTrue()
        assertThat(permissions.calls).isEqualTo(1)
    }

    @Test
    fun `latest terms agreement failure includes terms content hash`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection(Permissions.STUDY_CREATE, requiresActiveAccount = true)
        users.statusByUser[7] = "ACTIVE"
        requirements.rows += requirement(
            type = PermissionRequirementType.TERMS_AGREED,
            key = "TERMS_OF_SERVICE",
            operator = PermissionRequirementOperator.LATEST,
            failureCode = ApiErrorCode.TERMS_AGREEMENT_REQUIRED,
        )
        terms.activeByCode["TERMS_OF_SERVICE"] = ActiveTermsProjection(
            id = 11,
            code = "TERMS_OF_SERVICE",
            version = "2026-07-07",
            title = "서비스 이용약관",
            url = "https://example.com/terms",
            contentHash = "sha256:test",
            required = true,
            mutable = false,
        )

        val result = evaluator.evaluate(principal(status = "ACTIVE"), Permissions.STUDY_CREATE)

        assertThat(result.granted).isFalse()
        assertThat(result.failureCode).isEqualTo(ApiErrorCode.TERMS_AGREEMENT_REQUIRED)
        assertThat(result.requiredTerms.single().contentHash).isEqualTo("sha256:test")
    }

    @Test
    fun `user status requirement uses database status instead of JWT status`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection(Permissions.STUDY_CREATE, requiresActiveAccount = true)
        users.statusByUser[7] = "SUSPENDED"
        requirements.rows += requirement(
            type = PermissionRequirementType.USER_STATUS,
            key = "status",
            operator = PermissionRequirementOperator.EQ,
            value = "ACTIVE",
            failureCode = ApiErrorCode.USER_INACTIVE,
        )

        val result = evaluator.evaluate(principal(status = "ACTIVE"), Permissions.STUDY_CREATE)

        assertThat(result.granted).isFalse()
        assertThat(result.failureCode).isEqualTo(ApiErrorCode.USER_INACTIVE)
        assertThat(result.metadata["actual"]).isEqualTo("SUSPENDED")
    }

    @Test
    fun `preference off fails notification permission`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection("notification:receive-marketing", requiresActiveAccount = true)
        users.statusByUser[7] = "ACTIVE"
        requirements.rows += requirement(
            permissionCode = "notification:receive-marketing",
            type = PermissionRequirementType.PREFERENCE_ENABLED,
            key = "marketing_notification",
            operator = PermissionRequirementOperator.EQ,
            value = "true",
            failureCode = ApiErrorCode.NOTIFICATION_PREFERENCE_DISABLED,
        )
        preferences.enabled = false

        val result = evaluator.evaluate(principal(status = "ACTIVE"), "notification:receive-marketing")

        assertThat(result.granted).isFalse()
        assertThat(result.failureCode).isEqualTo(ApiErrorCode.NOTIFICATION_PREFERENCE_DISABLED)
    }

    @Test
    fun `terms agreement changes the next evaluation without issuing a new token`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection(Permissions.STUDY_CREATE, requiresActiveAccount = true)
        users.statusByUser[7] = "ACTIVE"
        requirements.rows += requirement(
            type = PermissionRequirementType.TERMS_AGREED,
            key = "TERMS_OF_SERVICE",
            operator = PermissionRequirementOperator.LATEST,
            failureCode = ApiErrorCode.TERMS_AGREEMENT_REQUIRED,
        )
        terms.activeByCode["TERMS_OF_SERVICE"] = ActiveTermsProjection(
            id = 12,
            code = "TERMS_OF_SERVICE",
            version = "2026-07-07",
            title = "서비스 이용약관",
            url = "https://example.com/terms",
            contentHash = "sha256:new",
            required = true,
            mutable = false,
        )
        val sameTokenPrincipal = principal(status = "ACTIVE")

        assertThat(evaluator.evaluate(sameTokenPrincipal, Permissions.STUDY_CREATE).granted).isFalse()

        terms.agreedTermIds += 12

        assertThat(evaluator.evaluate(sameTokenPrincipal, Permissions.STUDY_CREATE).granted).isTrue()
    }

    @Test
    fun `old terms agreement does not satisfy latest terms requirement`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection(Permissions.STUDY_CREATE, requiresActiveAccount = true)
        users.statusByUser[7] = "ACTIVE"
        requirements.rows += requirement(
            type = PermissionRequirementType.TERMS_AGREED,
            key = "TERMS_OF_SERVICE",
            operator = PermissionRequirementOperator.LATEST,
            failureCode = ApiErrorCode.TERMS_REAGREEMENT_REQUIRED,
        )
        terms.activeByCode["TERMS_OF_SERVICE"] = ActiveTermsProjection(
            id = 22,
            code = "TERMS_OF_SERVICE",
            version = "2026-07-07",
            title = "서비스 이용약관",
            url = "https://example.com/terms",
            contentHash = "sha256:new",
            required = true,
            mutable = false,
        )
        terms.agreedTermIds += 21

        val result = evaluator.evaluate(principal(status = "ACTIVE"), Permissions.STUDY_CREATE)

        assertThat(result.granted).isFalse()
        assertThat(result.failureCode).isEqualTo(ApiErrorCode.TERMS_REAGREEMENT_REQUIRED)
        assertThat(result.requiredTerms.single().version).isEqualTo("2026-07-07")
    }

    @Test
    fun `quota shortage fails with quota exceeded metadata`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection(Permissions.QUESTION_CREATE, requiresActiveAccount = true)
        users.statusByUser[7] = "ACTIVE"
        requirements.rows += requirement(
            permissionCode = Permissions.QUESTION_CREATE,
            type = PermissionRequirementType.QUOTA_AVAILABLE,
            key = "monthly_question",
            operator = PermissionRequirementOperator.GTE,
            value = "1",
            failureCode = ApiErrorCode.QUOTA_EXCEEDED,
        )
        quotas.remaining = 0

        val result = evaluator.evaluate(
            userId = 7,
            deviceId = "dev-1",
            permissionCode = Permissions.QUESTION_CREATE,
            context = PermissionEvaluationContext(
                now = Instant.parse("2026-07-23T12:34:56Z"),
                sessionId = 1,
                status = "ACTIVE",
            ),
        )

        assertThat(result.granted).isFalse()
        assertThat(result.failureCode).isEqualTo(ApiErrorCode.QUOTA_EXCEEDED)
        assertThat(result.metadata["remaining"]).isEqualTo(0L)
        assertThat(result.metadata["required"]).isEqualTo(1L)
        assertThat(result.metadata["quotaPeriod"]).isEqualTo("MONTHLY")
        assertThat(result.metadata["quotaPeriodStartedAt"]).isEqualTo("2026-07-07T10:00:00Z")
        assertThat(result.metadata["quotaResetAt"]).isEqualTo("2026-08-07T10:00:00Z")
        assertThat(result.metadata["quotaTimeZone"]).isEqualTo("Z")
    }

    @Test
    fun `study creation is independent from question quota`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection(Permissions.STUDY_CREATE, requiresActiveAccount = true)
        permissions.rows += UserPermissionProjection(Permissions.QUESTION_CREATE, requiresActiveAccount = true)
        users.statusByUser[7] = "ACTIVE"
        requirements.rows += requirement(
            permissionCode = Permissions.QUESTION_CREATE,
            type = PermissionRequirementType.QUOTA_AVAILABLE,
            key = "monthly_question",
            operator = PermissionRequirementOperator.GTE,
            value = "1",
            failureCode = ApiErrorCode.QUOTA_EXCEEDED,
        )
        quotas.remaining = 0

        val result = evaluator.evaluate(principal(status = "ACTIVE"), Permissions.STUDY_CREATE)

        assertThat(result.granted).isTrue()
    }

    @Test
    fun `device registration requirement fails when device is missing`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection("notification:receive-info", requiresActiveAccount = true)
        users.statusByUser[7] = "ACTIVE"
        requirements.rows += requirement(
            permissionCode = "notification:receive-info",
            type = PermissionRequirementType.DEVICE_REGISTERED,
            key = "apns_token",
            operator = PermissionRequirementOperator.EXISTS,
            failureCode = ApiErrorCode.DEVICE_NOT_REGISTERED,
        )
        devices.device = null

        val result = evaluator.evaluate(principal(status = "ACTIVE"), "notification:receive-info")

        assertThat(result.granted).isFalse()
        assertThat(result.failureCode).isEqualTo(ApiErrorCode.DEVICE_NOT_REGISTERED)
    }

    @Test
    fun `email verification requirement fails when email is not verified`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection(Permissions.STUDY_CREATE, requiresActiveAccount = true)
        users.statusByUser[7] = "ACTIVE"
        requirements.rows += requirement(
            type = PermissionRequirementType.EMAIL_VERIFIED,
            key = "email",
            operator = PermissionRequirementOperator.EXISTS,
            failureCode = ApiErrorCode.EMAIL_NOT_VERIFIED,
        )
        emails.verified = false

        val result = evaluator.evaluate(principal(status = "ACTIVE"), Permissions.STUDY_CREATE)

        assertThat(result.granted).isFalse()
        assertThat(result.failureCode).isEqualTo(ApiErrorCode.EMAIL_NOT_VERIFIED)
    }

    @Test
    fun `minimum app version requirement fails with upgrade metadata`(): Unit = runBlocking {
        permissions.rows += UserPermissionProjection(Permissions.STUDY_CREATE, requiresActiveAccount = true)
        users.statusByUser[7] = "ACTIVE"
        requirements.rows += requirement(
            type = PermissionRequirementType.MIN_APP_VERSION,
            key = "ios",
            operator = PermissionRequirementOperator.GTE,
            value = "2.0.0",
            failureCode = ApiErrorCode.APP_VERSION_UNSUPPORTED,
        )

        val result = evaluator.evaluate(
            userId = 7,
            deviceId = "dev-1",
            permissionCode = Permissions.STUDY_CREATE,
            context = PermissionEvaluationContext(
                now = Instant.parse("2026-07-07T00:00:00Z"),
                appVersion = "1.9.9",
                sessionId = 1,
                status = "ACTIVE",
            ),
        )

        assertThat(result.granted).isFalse()
        assertThat(result.failureCode).isEqualTo(ApiErrorCode.APP_VERSION_UNSUPPORTED)
        assertThat(result.metadata["minimum"]).isEqualTo("2.0.0")
    }

    private fun principal(status: String = "ACTIVE") =
        Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false, status = status)

    private fun requirement(
        permissionCode: String = Permissions.STUDY_CREATE,
        type: PermissionRequirementType,
        key: String,
        operator: PermissionRequirementOperator,
        value: String? = null,
        failureCode: ApiErrorCode,
    ) = PermissionRequirementProjection(
        id = requirements.rows.size.toLong() + 1,
        permissionCode = permissionCode,
        type = type,
        key = key,
        operator = operator,
        value = value,
        failureCode = failureCode,
    )

    private class FakePermissionQueryPort : PermissionQueryPort {
        val rows = mutableSetOf<UserPermissionProjection>()
        var calls = 0
        override suspend fun permissionsForUser(userId: Long): Set<UserPermissionProjection> {
            calls += 1
            return rows
        }
    }

    private class FakePermissionRequirementQueryPort : PermissionRequirementQueryPort {
        val rows = mutableListOf<PermissionRequirementProjection>()
        override suspend fun activeRequirements(permissionCode: String, now: Instant): List<PermissionRequirementProjection> =
            rows.filter { it.permissionCode == permissionCode }
    }

    private class FakeTermsAgreementQueryPort : TermsAgreementQueryPort {
        val activeByCode = mutableMapOf<String, ActiveTermsProjection>()
        val agreedTermIds = mutableSetOf<Long>()
        override suspend fun activeTerms(now: Instant): List<ActiveTermsProjection> = activeByCode.values.toList()
        override suspend fun activeTerms(userId: Long?, deviceId: String?, now: Instant): List<ActiveTermsProjection> =
            activeTerms(now).map { it.copy(agreed = hasAgreement(userId, deviceId, it.id)) }
        override suspend fun activeTerms(code: String, now: Instant): ActiveTermsProjection? = activeByCode[code]
        override suspend fun hasAgreement(userId: Long?, deviceId: String?, termsId: Long): Boolean = termsId in agreedTermIds
        override suspend fun hasRequiredAgreements(userId: Long, deviceId: String?, now: Instant): Boolean =
            activeTerms(now).filter { it.required }.all { it.id in agreedTermIds }
    }

    private class FakeNotificationPreferenceQueryPort : NotificationPreferenceQueryPort {
        var enabled = true
        override suspend fun isEnabled(userId: Long?, deviceId: String, key: String): Boolean = enabled
    }

    private class FakePermissionQuotaQueryPort : PermissionQuotaQueryPort {
        var remaining = 1L
        override suspend fun status(userId: Long, key: String, now: Instant): PermissionQuotaStatus =
            PermissionQuotaStatus(
                remaining = remaining,
                periodStartedAt = Instant.parse("2026-07-07T10:00:00Z"),
                resetAt = Instant.parse("2026-08-07T10:00:00Z"),
            )
    }

    private class FakeDevicePort : DevicePort {
        var device: DeviceEntity? = DeviceEntity(deviceId = "dev-1", apnsToken = "token")
        override suspend fun save(entity: DeviceEntity): DeviceEntity = entity
        override suspend fun findByDeviceId(deviceId: String): DeviceEntity? = device
        override suspend fun findByInstallationKeyHash(installationKeyHash: String): DeviceEntity? =
            device?.takeIf { it.installationKeyHash == installationKeyHash }
        override suspend fun findAllByUserId(userId: Long): List<DeviceEntity> = emptyList()
    }

    private class FakeUserStatusQueryPort : UserStatusQueryPort {
        val statusByUser = mutableMapOf<Long, String>()
        override suspend fun status(userId: Long): String? = statusByUser[userId]
    }

    private class FakeEmailVerificationQueryPort : EmailVerificationQueryPort {
        var verified = true
        override suspend fun isVerified(userId: Long): Boolean = verified
    }
}
