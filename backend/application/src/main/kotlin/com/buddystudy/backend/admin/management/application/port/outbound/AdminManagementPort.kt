package com.buddystudy.backend.admin.management.application.port.outbound

import com.buddystudy.backend.admin.management.application.model.AdminMembershipTierResponse
import com.buddystudy.backend.admin.management.application.model.AdminFeedbackPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminFeedbackSummary
import com.buddystudy.backend.admin.management.application.model.AdminUserPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserSummary
import com.buddystudy.backend.admin.management.application.model.AssignUserPlanCommand
import java.time.Instant

interface AdminManagementPort {
    suspend fun users(query: String?, limit: Int, offset: Int): AdminUserPageResponse
    suspend fun user(userId: Long): AdminUserSummary?
    suspend fun tiers(): List<AdminMembershipTierResponse>
    suspend fun updateTier(tierCode: String, monthlyQuestionLimit: Int): AdminMembershipTierResponse?
    suspend fun assignPlan(userId: Long, command: AssignUserPlanCommand): AdminUserSummary?
}

interface AdminFeedbackPort {
    suspend fun feedbacks(query: String?, status: String?, limit: Int, offset: Int): AdminFeedbackPageResponse
    suspend fun feedback(feedbackId: Long): AdminFeedbackSummary?
    suspend fun markReviewed(feedbackId: Long, reviewedAt: Instant): AdminFeedbackSummary?
    suspend fun markReplied(feedbackId: Long, repliedAt: Instant): AdminFeedbackSummary?
}
