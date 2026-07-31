package com.buddystudy.backend.admin.analytics.application.port.outbound

import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorPageResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorPrincipal
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorSummary
import com.buddystudy.backend.admin.analytics.application.model.CreateAdminOperatorCommand
import com.buddystudy.backend.admin.analytics.application.model.UpdateAdminOperatorCommand
import java.time.Instant

interface AdminOperatorPort {
    suspend fun authenticate(username: String, password: String, authenticatedAt: Instant): AdminOperatorPrincipal?
    suspend fun activeStatus(username: String): Boolean?
    suspend fun ensureBootstrap(username: String, displayName: String, password: String): AdminOperatorPrincipal
    suspend fun operators(query: String?, limit: Int, offset: Int): AdminOperatorPageResponse
    suspend fun create(command: CreateAdminOperatorCommand, createdBy: String): AdminOperatorSummary?
    suspend fun update(
        operatorId: Long,
        command: UpdateAdminOperatorCommand,
        updatedBy: String,
    ): AdminOperatorSummary?
}
