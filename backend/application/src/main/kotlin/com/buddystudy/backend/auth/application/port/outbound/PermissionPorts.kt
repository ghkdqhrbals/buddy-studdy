package com.buddystudy.backend.auth.application.port.outbound

import com.buddystudy.backend.auth.application.permission.PermissionRequirementOperator
import com.buddystudy.backend.auth.application.permission.PermissionRequirementType
import com.buddystudy.backend.common.application.error.ApiErrorCode
import java.time.Instant

data class UserPermissionProjection(
    val code: String,
    val requiresActiveAccount: Boolean,
)

data class PermissionRequirementProjection(
    val id: Long,
    val permissionCode: String,
    val type: PermissionRequirementType,
    val key: String,
    val operator: PermissionRequirementOperator,
    val value: String?,
    val failureCode: ApiErrorCode,
)

data class ActiveTermsProjection(
    val id: Long,
    val code: String,
    val version: String,
    val title: String,
    val url: String,
    val contentHash: String,
    val required: Boolean,
    val mutable: Boolean,
    val agreed: Boolean = false,
)

interface PermissionQueryPort {
    suspend fun permissionsForUser(userId: Long): Set<UserPermissionProjection>
}

interface PermissionRequirementQueryPort {
    suspend fun activeRequirements(permissionCode: String, now: Instant): List<PermissionRequirementProjection>
}

interface TermsAgreementQueryPort {
    suspend fun activeTerms(now: Instant): List<ActiveTermsProjection>
    suspend fun activeTerms(userId: Long?, deviceId: String?, now: Instant): List<ActiveTermsProjection>
    suspend fun activeTerms(code: String, now: Instant): ActiveTermsProjection?
    suspend fun hasAgreement(userId: Long?, deviceId: String?, termsId: Long): Boolean
    suspend fun hasRequiredAgreements(userId: Long, deviceId: String?, now: Instant): Boolean
}

interface TermsAgreementCommandPort {
    suspend fun saveAgreement(
        userId: Long?,
        deviceId: String?,
        termsId: Long,
        action: String,
        source: String,
        ipAddress: String?,
        userAgent: String?,
        appVersion: String?,
        now: Instant,
    )
}

interface NotificationPreferenceCommandPort {
    suspend fun savePreference(
        userId: Long?,
        deviceId: String,
        key: String,
        enabled: Boolean,
        now: Instant,
    )
}

interface NotificationPreferenceQueryPort {
    suspend fun isEnabled(userId: Long?, deviceId: String, key: String): Boolean
}

interface PermissionQuotaQueryPort {
    suspend fun status(userId: Long, key: String, now: Instant): PermissionQuotaStatus
}

data class PermissionQuotaStatus(
    val remaining: Long,
    val periodStartedAt: Instant,
    val resetAt: Instant,
)

interface EmailVerificationQueryPort {
    suspend fun isVerified(userId: Long): Boolean
}

interface UserStatusQueryPort {
    suspend fun status(userId: Long): String?
}

interface RoleAssignmentPort {
    suspend fun grantRoleIfMissing(userId: Long, roleCode: String)
    suspend fun countUserRoles(userId: Long, roleCode: String): Long
}
