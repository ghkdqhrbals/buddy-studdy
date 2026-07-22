package com.buddystudy.backend.auth.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.NotificationPreferenceCommand
import com.buddystudy.backend.auth.application.model.NotificationPreferenceResponse
import com.buddystudy.backend.auth.application.model.PermissionEvaluationsResponse
import com.buddystudy.backend.auth.application.model.TermsAgreementCommand
import com.buddystudy.backend.auth.application.model.TermsResponse
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext

interface TermsUseCase {
    suspend fun activeTerms(principal: Principal? = null): List<TermsResponse>
    suspend fun saveAgreement(principal: Principal, command: TermsAgreementCommand): PermissionEvaluationsResponse
}

interface NotificationPreferenceUseCase {
    suspend fun notificationPreferences(principal: Principal): List<NotificationPreferenceResponse>
    suspend fun saveNotificationPreference(principal: Principal, command: NotificationPreferenceCommand): NotificationPreferenceResponse
}

interface PermissionEvaluationUseCase {
    suspend fun permissions(
        principal: Principal,
        context: PermissionEvaluationContext = PermissionEvaluationContext(java.time.Instant.now()),
    ): PermissionEvaluationsResponse
}
