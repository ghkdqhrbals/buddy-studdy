package com.buddystudy.backend.auth.application.permission

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationQueryPort
import com.buddystudy.backend.auth.application.port.outbound.NotificationPreferenceQueryPort
import com.buddystudy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystudy.backend.auth.application.port.outbound.PermissionQuotaQueryPort
import com.buddystudy.backend.auth.application.port.outbound.PermissionRequirementProjection
import com.buddystudy.backend.auth.application.port.outbound.PermissionRequirementQueryPort
import com.buddystudy.backend.auth.application.port.outbound.TermsAgreementQueryPort
import com.buddystudy.backend.auth.application.port.outbound.UserStatusQueryPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import org.springframework.stereotype.Component
import java.time.Instant

data class PermissionEvaluationResult(
    val granted: Boolean,
    val permissionCode: String,
    val failureCode: ApiErrorCode? = null,
    val reason: String? = null,
    val requiredTerms: List<TermsRequirementResponse> = emptyList(),
    val requiredActions: List<PermissionRequiredAction> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun granted(permissionCode: String): PermissionEvaluationResult =
            PermissionEvaluationResult(granted = true, permissionCode = permissionCode)

        fun denied(
            permissionCode: String,
            failureCode: ApiErrorCode,
            reason: String? = null,
            requiredTerms: List<TermsRequirementResponse> = emptyList(),
            requiredActions: List<PermissionRequiredAction> = emptyList(),
            metadata: Map<String, Any?> = emptyMap(),
        ): PermissionEvaluationResult =
            PermissionEvaluationResult(
                granted = false,
                permissionCode = permissionCode,
                failureCode = failureCode,
                reason = reason,
                requiredTerms = requiredTerms,
                requiredActions = requiredActions,
                metadata = metadata,
            )
    }
}

data class TermsRequirementResponse(
    val code: String,
    val version: String,
    val title: String,
    val url: String,
    val contentHash: String,
)

enum class PermissionRequiredAction {
    AGREE_TERMS,
    ENABLE_PREFERENCE,
    REGISTER_DEVICE,
    VERIFY_EMAIL,
    UPGRADE_APP,
}

interface PermissionEvaluator {
    fun evaluate(principal: Principal, permissionCode: String): PermissionEvaluationResult =
        evaluate(
            userId = principal.userId,
            deviceId = principal.deviceId,
            permissionCode = permissionCode,
            context = PermissionEvaluationContext(
                now = Instant.now(),
                sessionId = principal.sessionId,
                status = principal.status,
                anonymous = principal.anonymous,
            ),
        )

    fun evaluate(
        userId: Long,
        deviceId: String,
        permissionCode: String,
        context: PermissionEvaluationContext = PermissionEvaluationContext(now = Instant.now()),
    ): PermissionEvaluationResult =
        evaluate(
            Principal(
                userId = userId,
                deviceId = deviceId,
                sessionId = context.sessionId ?: 0,
                anonymous = context.anonymous,
                status = context.status ?: "ACTIVE",
            ),
            permissionCode,
        )
}

data class PermissionEvaluationSubject(
    val userId: Long,
    val deviceId: String,
    val sessionId: Long,
    val status: String,
    val anonymous: Boolean,
) {
    companion object {
        fun from(principal: Principal): PermissionEvaluationSubject =
            PermissionEvaluationSubject(
                userId = principal.userId,
                deviceId = principal.deviceId,
                sessionId = principal.sessionId,
                status = principal.status,
                anonymous = principal.anonymous,
            )
    }
}

data class PermissionEvaluationContext(
    val now: Instant,
    val appVersion: String? = null,
    val sessionId: Long? = null,
    val status: String? = null,
    val anonymous: Boolean = false,
)

data class RequirementEvaluationResult(
    val granted: Boolean,
    val failureCode: ApiErrorCode? = null,
    val reason: String? = null,
    val requiredTerms: List<TermsRequirementResponse> = emptyList(),
    val requiredActions: List<PermissionRequiredAction> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap(),
) {
    companion object {
        val granted = RequirementEvaluationResult(granted = true)

        fun denied(
            failureCode: ApiErrorCode,
            reason: String? = null,
            requiredTerms: List<TermsRequirementResponse> = emptyList(),
            requiredActions: List<PermissionRequiredAction> = emptyList(),
            metadata: Map<String, Any?> = emptyMap(),
        ) = RequirementEvaluationResult(
            granted = false,
            failureCode = failureCode,
            reason = reason,
            requiredTerms = requiredTerms,
            requiredActions = requiredActions,
            metadata = metadata,
        )
    }
}

interface PermissionRequirementEvaluator {
    fun supports(type: PermissionRequirementType): Boolean
    fun evaluate(
        subject: PermissionEvaluationSubject,
        requirement: PermissionRequirementProjection,
        context: PermissionEvaluationContext,
    ): RequirementEvaluationResult
}

enum class PermissionRequirementType {
    TERMS_AGREED,
    PREFERENCE_ENABLED,
    QUOTA_AVAILABLE,
    DEVICE_REGISTERED,
    USER_STATUS,
    EMAIL_VERIFIED,
    MIN_APP_VERSION,
}

enum class PermissionRequirementOperator {
    EQ,
    EXISTS,
    LATEST,
    GTE,
}

@Component
class DatabasePermissionEvaluator(
    private val permissions: PermissionQueryPort,
    private val requirements: PermissionRequirementQueryPort,
    private val users: UserStatusQueryPort,
    private val evaluators: List<PermissionRequirementEvaluator>,
) : PermissionEvaluator {
    override fun evaluate(
        userId: Long,
        deviceId: String,
        permissionCode: String,
        context: PermissionEvaluationContext,
    ): PermissionEvaluationResult {
        val granted = permissions.permissionsForUser(userId).associateBy { it.code }
        val permission = granted[permissionCode]
            ?: return PermissionEvaluationResult.denied(
                permissionCode = permissionCode,
                failureCode = ApiErrorCode.PERMISSION_DENIED,
                reason = "Permission denied.",
            )

        val currentStatus = users.status(userId) ?: context.status ?: "ACTIVE"
        val subject = PermissionEvaluationSubject(
            userId = userId,
            deviceId = deviceId,
            sessionId = context.sessionId ?: 0,
            status = currentStatus,
            anonymous = context.anonymous,
        )
        requirements.activeRequirements(permissionCode, context.now).forEach { requirement ->
            val evaluator = evaluators.firstOrNull { it.supports(requirement.type) }
                ?: return PermissionEvaluationResult.denied(
                    permissionCode = permissionCode,
                    failureCode = requirement.failureCode,
                    reason = "Permission requirement evaluator is missing for ${requirement.type}.",
                )
            val result = evaluator.evaluate(subject, requirement, context)
            if (!result.granted) {
                return PermissionEvaluationResult.denied(
                    permissionCode = permissionCode,
                    failureCode = result.failureCode ?: requirement.failureCode,
                    reason = result.reason,
                    requiredTerms = result.requiredTerms,
                    requiredActions = result.requiredActions,
                    metadata = result.metadata,
                )
            }
        }

        if (permission.requiresActiveAccount && currentStatus in FORBIDDEN_WRITE_STATUSES) {
            return PermissionEvaluationResult.denied(
                permissionCode = permissionCode,
                failureCode = ApiErrorCode.ACCOUNT_FORBIDDEN,
                reason = "Account is not allowed to perform this action.",
            )
        }

        return PermissionEvaluationResult.granted(permissionCode)
    }

    private companion object {
        private val FORBIDDEN_WRITE_STATUSES = setOf("SUSPENDED", "WITHDRAWN")
    }
}

@Component
class TermsAgreedRequirementEvaluator(
    private val terms: TermsAgreementQueryPort,
) : PermissionRequirementEvaluator {
    override fun supports(type: PermissionRequirementType): Boolean =
        type == PermissionRequirementType.TERMS_AGREED

    override fun evaluate(
        subject: PermissionEvaluationSubject,
        requirement: PermissionRequirementProjection,
        context: PermissionEvaluationContext,
    ): RequirementEvaluationResult {
        val activeTerms = terms.activeTerms(requirement.key, context.now)
            ?: return RequirementEvaluationResult.denied(
                requirement.failureCode,
                reason = "Active terms were not found for ${requirement.key}.",
                requiredActions = listOf(PermissionRequiredAction.AGREE_TERMS),
            )
        val userId = subject.userId.takeUnless { subject.anonymous }
        if (terms.hasAgreement(userId, subject.deviceId, activeTerms.id)) {
            return RequirementEvaluationResult.granted
        }

        return RequirementEvaluationResult.denied(
            requirement.failureCode,
            reason = "Latest terms agreement is required.",
            requiredTerms = listOf(
                TermsRequirementResponse(
                    code = activeTerms.code,
                    version = activeTerms.version,
                    title = activeTerms.title,
                    url = activeTerms.url,
                    contentHash = activeTerms.contentHash,
                )
            ),
            requiredActions = listOf(PermissionRequiredAction.AGREE_TERMS),
        )
    }
}

@Component
class PreferenceEnabledRequirementEvaluator(
    private val preferences: NotificationPreferenceQueryPort,
) : PermissionRequirementEvaluator {
    override fun supports(type: PermissionRequirementType): Boolean =
        type == PermissionRequirementType.PREFERENCE_ENABLED

    override fun evaluate(
        subject: PermissionEvaluationSubject,
        requirement: PermissionRequirementProjection,
        context: PermissionEvaluationContext,
    ): RequirementEvaluationResult {
        val userId = subject.userId.takeUnless { subject.anonymous }
        return if (preferences.isEnabled(userId, subject.deviceId, requirement.key)) {
            RequirementEvaluationResult.granted
        } else {
            RequirementEvaluationResult.denied(
                requirement.failureCode,
                reason = "Notification preference is disabled.",
                requiredActions = listOf(PermissionRequiredAction.ENABLE_PREFERENCE),
            )
        }
    }
}

@Component
class QuotaAvailableRequirementEvaluator(
    private val quotas: PermissionQuotaQueryPort,
) : PermissionRequirementEvaluator {
    override fun supports(type: PermissionRequirementType): Boolean =
        type == PermissionRequirementType.QUOTA_AVAILABLE

    override fun evaluate(
        subject: PermissionEvaluationSubject,
        requirement: PermissionRequirementProjection,
        context: PermissionEvaluationContext,
    ): RequirementEvaluationResult {
        val required = requirement.value?.toLongOrNull() ?: 1L
        val remaining = quotas.remaining(subject.userId, requirement.key, context.now)
        return if (remaining >= required) {
            RequirementEvaluationResult.granted
        } else {
            RequirementEvaluationResult.denied(
                requirement.failureCode,
                reason = "Quota is exceeded.",
                metadata = mapOf("remaining" to remaining, "required" to required),
            )
        }
    }
}

@Component
class DeviceRegisteredRequirementEvaluator(
    private val devices: DevicePort,
) : PermissionRequirementEvaluator {
    override fun supports(type: PermissionRequirementType): Boolean =
        type == PermissionRequirementType.DEVICE_REGISTERED

    override fun evaluate(
        subject: PermissionEvaluationSubject,
        requirement: PermissionRequirementProjection,
        context: PermissionEvaluationContext,
    ): RequirementEvaluationResult {
        val device = devices.findByDeviceId(subject.deviceId)
            ?: return RequirementEvaluationResult.denied(
                requirement.failureCode,
                reason = "Device is not registered.",
                requiredActions = listOf(PermissionRequiredAction.REGISTER_DEVICE),
            )

        if (requirement.operator == PermissionRequirementOperator.EXISTS && requirement.key == "apns_token" && device.apnsToken.isBlank()) {
            return RequirementEvaluationResult.denied(
                requirement.failureCode,
                reason = "APNs token is not registered.",
                requiredActions = listOf(PermissionRequiredAction.REGISTER_DEVICE),
            )
        }

        return RequirementEvaluationResult.granted
    }
}

@Component
class UserStatusRequirementEvaluator(
    private val users: UserStatusQueryPort,
) : PermissionRequirementEvaluator {
    override fun supports(type: PermissionRequirementType): Boolean =
        type == PermissionRequirementType.USER_STATUS

    override fun evaluate(
        subject: PermissionEvaluationSubject,
        requirement: PermissionRequirementProjection,
        context: PermissionEvaluationContext,
    ): RequirementEvaluationResult {
        val actual = users.status(subject.userId) ?: subject.status
        return if (requirement.operator == PermissionRequirementOperator.EQ && actual == requirement.value) {
            RequirementEvaluationResult.granted
        } else {
            RequirementEvaluationResult.denied(
                requirement.failureCode,
                reason = "User status does not satisfy permission requirement.",
                metadata = mapOf("actual" to actual, "expected" to requirement.value),
            )
        }
    }
}

@Component
class EmailVerifiedRequirementEvaluator(
    private val emails: EmailVerificationQueryPort,
) : PermissionRequirementEvaluator {
    override fun supports(type: PermissionRequirementType): Boolean =
        type == PermissionRequirementType.EMAIL_VERIFIED

    override fun evaluate(
        subject: PermissionEvaluationSubject,
        requirement: PermissionRequirementProjection,
        context: PermissionEvaluationContext,
    ): RequirementEvaluationResult =
        if (emails.isVerified(subject.userId)) {
            RequirementEvaluationResult.granted
        } else {
            RequirementEvaluationResult.denied(
                requirement.failureCode,
                reason = "Email is not verified.",
                requiredActions = listOf(PermissionRequiredAction.VERIFY_EMAIL),
            )
        }
}

@Component
class MinAppVersionRequirementEvaluator : PermissionRequirementEvaluator {
    override fun supports(type: PermissionRequirementType): Boolean =
        type == PermissionRequirementType.MIN_APP_VERSION

    override fun evaluate(
        subject: PermissionEvaluationSubject,
        requirement: PermissionRequirementProjection,
        context: PermissionEvaluationContext,
    ): RequirementEvaluationResult {
        val current = context.appVersion ?: return RequirementEvaluationResult.denied(
            requirement.failureCode,
            reason = "App version is missing.",
            requiredActions = listOf(PermissionRequiredAction.UPGRADE_APP),
        )
        val minimum = requirement.value.orEmpty()
        return if (compareVersion(current, minimum) >= 0) {
            RequirementEvaluationResult.granted
        } else {
            RequirementEvaluationResult.denied(
                requirement.failureCode,
                reason = "App version is unsupported.",
                requiredActions = listOf(PermissionRequiredAction.UPGRADE_APP),
                metadata = mapOf("current" to current, "minimum" to minimum),
            )
        }
    }

    private fun compareVersion(left: String, right: String): Int {
        val lhs = left.split(".").map { it.toIntOrNull() ?: 0 }
        val rhs = right.split(".").map { it.toIntOrNull() ?: 0 }
        val size = maxOf(lhs.size, rhs.size)
        for (index in 0 until size) {
            val diff = lhs.getOrElse(index) { 0 } - rhs.getOrElse(index) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}
