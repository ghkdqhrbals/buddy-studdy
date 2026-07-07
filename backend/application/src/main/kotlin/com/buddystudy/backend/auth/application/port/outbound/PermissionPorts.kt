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
    fun permissionsForUser(userId: Long): Set<UserPermissionProjection>
}

interface PermissionRequirementQueryPort {
    fun activeRequirements(permissionCode: String, now: Instant): List<PermissionRequirementProjection>
}

interface TermsAgreementQueryPort {
    fun activeTerms(now: Instant): List<ActiveTermsProjection>
    fun activeTerms(userId: Long?, deviceId: String?, now: Instant): List<ActiveTermsProjection>
    fun activeTerms(code: String, now: Instant): ActiveTermsProjection?
    fun hasAgreement(userId: Long?, deviceId: String?, termsId: Long): Boolean
    fun hasRequiredAgreements(userId: Long, deviceId: String?, now: Instant): Boolean
}

interface TermsAgreementCommandPort {
    fun saveAgreement(
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
    fun savePreference(
        userId: Long?,
        deviceId: String,
        key: String,
        enabled: Boolean,
        now: Instant,
    )
}

interface NotificationPreferenceQueryPort {
    fun isEnabled(userId: Long?, deviceId: String, key: String): Boolean
}

interface PermissionQuotaQueryPort {
    fun remaining(userId: Long, key: String, now: Instant): Long
}

interface EmailVerificationQueryPort {
    fun isVerified(userId: Long): Boolean
}

interface UserStatusQueryPort {
    fun status(userId: Long): String?
}

interface RoleAssignmentPort {
    fun grantRoleIfMissing(userId: Long, roleCode: String)
    fun countUserRoles(userId: Long, roleCode: String): Long
}
