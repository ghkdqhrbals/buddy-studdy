package com.buddystudy.backend.auth.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.PermissionEvaluationsResponse
import com.buddystudy.backend.auth.application.model.TermsAgreementCommand
import com.buddystudy.backend.auth.application.model.TermsResponse
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext

interface TermsUseCase {
    fun activeTerms(): List<TermsResponse>
    fun saveAgreement(principal: Principal, command: TermsAgreementCommand): PermissionEvaluationsResponse
}

interface PermissionEvaluationUseCase {
    fun permissions(
        principal: Principal,
        context: PermissionEvaluationContext = PermissionEvaluationContext(java.time.Instant.now()),
    ): PermissionEvaluationsResponse
}
