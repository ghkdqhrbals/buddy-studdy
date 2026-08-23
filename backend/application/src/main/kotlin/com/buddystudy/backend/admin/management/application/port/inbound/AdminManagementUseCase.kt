package com.buddystudy.backend.admin.management.application.port.inbound

import com.buddystudy.backend.admin.management.application.model.AdminMembershipTierResponse
import com.buddystudy.backend.admin.management.application.model.AdminFeedbackPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminFeedbackSummary
import com.buddystudy.backend.admin.management.application.model.AdminNotificationCommand
import com.buddystudy.backend.admin.management.application.model.AdminNotificationDispatchResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserSummary
import com.buddystudy.backend.admin.management.application.model.AssignUserPlanCommand

interface AdminManagementUseCase {
    suspend fun users(query: String?, limit: Int, offset: Int): AdminUserPageResponse
    suspend fun tiers(): List<AdminMembershipTierResponse>
    suspend fun updateTier(tierCode: String, monthlyQuestionLimit: Int): AdminMembershipTierResponse
    suspend fun assignPlan(userId: Long, command: AssignUserPlanCommand): AdminUserSummary
    suspend fun setCurrentPeriodQuestionLimit(userId: Long, questionLimitOverride: Int?): AdminUserSummary
}

interface AdminFeedbackUseCase {
    suspend fun feedbacks(query: String?, status: String?, limit: Int, offset: Int): AdminFeedbackPageResponse
    suspend fun markReviewed(feedbackId: Long): AdminFeedbackSummary
}

interface AdminMessagingUseCase {
    suspend fun notifyUser(userId: Long, command: AdminNotificationCommand): AdminNotificationDispatchResponse
    suspend fun notifyFeedback(feedbackId: Long, command: AdminNotificationCommand): AdminNotificationDispatchResponse
}
