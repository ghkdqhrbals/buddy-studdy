package com.buddystudy.backend.admin.management

import com.buddystudy.backend.admin.management.application.model.AdminFeedbackPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminFeedbackSummary
import com.buddystudy.backend.admin.management.application.model.AdminMembershipTierResponse
import com.buddystudy.backend.admin.management.application.model.AdminNotificationCommand
import com.buddystudy.backend.admin.management.application.model.AdminUserPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserSummary
import com.buddystudy.backend.admin.management.application.model.AssignUserPlanCommand
import com.buddystudy.backend.admin.management.application.port.outbound.AdminFeedbackPort
import com.buddystudy.backend.admin.management.application.port.outbound.AdminManagementPort
import com.buddystudy.backend.admin.management.application.service.AdminFeedbackService
import com.buddystudy.backend.admin.management.application.service.AdminMessagingService
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class AdminMessagingServiceTest {
    private val management = FakeAdminManagementPort()
    private val feedbacks = FakeAdminFeedbackPort()
    private val notifications = FakeNotificationPublisher()
    private val messaging = AdminMessagingService(management, feedbacks, notifications)

    @Test
    fun `administrator queues a home popup notification for a specific user`() = runBlocking {
        val result = messaging.notifyUser(
            userId = 7,
            command = AdminNotificationCommand(
                title = "피드백 크레딧을 드렸어요",
                body = "소중한 의견 감사합니다.",
                deepLink = null,
            ),
        )

        assertThat(result.status).isEqualTo("QUEUED")
        assertThat(result.deepLink).isEqualTo("buddystudy://home/message")
        assertThat(notifications.commands.single()).satisfies({ command ->
            assertThat(command.userId).isEqualTo(7)
            assertThat(command.deviceId).isNull()
            assertThat(command.type).isEqualTo("ADMIN_MESSAGE")
            assertThat(command.title).isEqualTo("피드백 크레딧을 드렸어요")
            assertThat(command.body).isEqualTo("소중한 의견 감사합니다.")
            assertThat(command.deepLink).isEqualTo("buddystudy://home/message")
            assertThat(command.shouldPush).isTrue()
        })
    }

    @Test
    fun `administrator can choose an app deep link destination`() = runBlocking {
        messaging.notifyUser(
            userId = 7,
            command = AdminNotificationCommand(
                title = "학습 기록을 확인해 보세요",
                body = "통계가 업데이트되었습니다.",
                deepLink = "buddystudy://statistics",
            ),
        )

        assertThat(notifications.commands.single().deepLink).isEqualTo("buddystudy://statistics")
    }

    @Test
    fun `external and unknown app deep links are rejected`() {
        assertThatThrownBy {
            runBlocking {
                messaging.notifyUser(
                    7,
                    AdminNotificationCommand("제목", "본문", "https://example.com"),
                )
            }
        }.isInstanceOf(ApiException::class.java)

        assertThatThrownBy {
            runBlocking {
                messaging.notifyUser(
                    7,
                    AdminNotificationCommand("제목", "본문", "buddystudy://unknown"),
                )
            }
        }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `feedback reply targets its owner and records replied state`() = runBlocking {
        val result = messaging.notifyFeedback(
            feedbackId = 10,
            command = AdminNotificationCommand("감사합니다", "무료 크레딧을 추가했습니다.", null),
        )

        assertThat(result.feedbackId).isEqualTo(10)
        assertThat(notifications.commands.single().userId).isEqualTo(7)
        assertThat(feedbacks.repliedFeedbackId).isEqualTo(10)
    }

    @Test
    fun `anonymous feedback reply targets the captured device`() = runBlocking {
        feedbacks.row = feedbacks.row.copy(userId = null, email = null, displayName = null)

        messaging.notifyFeedback(
            feedbackId = 10,
            command = AdminNotificationCommand("감사합니다", "의견을 확인했습니다.", null),
        )

        assertThat(notifications.commands.single().userId).isNull()
        assertThat(notifications.commands.single().deviceId).isEqualTo("dev-feedback")
    }

    @Test
    fun `feedback browsing normalizes filters and review updates status`() = runBlocking {
        val service = AdminFeedbackService(feedbacks)

        service.feedbacks(query = "  search  ", status = " new ", limit = 500, offset = -1)
        val reviewed = service.markReviewed(10)

        assertThat(feedbacks.lastQuery).isEqualTo("search")
        assertThat(feedbacks.lastStatus).isEqualTo("NEW")
        assertThat(feedbacks.lastLimit).isEqualTo(100)
        assertThat(feedbacks.lastOffset).isZero()
        assertThat(reviewed.status).isEqualTo("REVIEWED")
    }

    private class FakeNotificationPublisher : PublishNotificationUseCase {
        val commands = mutableListOf<NotificationRequestCommand>()

        override suspend fun publish(command: NotificationRequestCommand): Boolean {
            commands += command
            return true
        }
    }

    private class FakeAdminFeedbackPort : AdminFeedbackPort {
        var row = AdminFeedbackSummary(
            id = 10,
            userId = 7,
            deviceId = "dev-feedback",
            email = "user@example.com",
            displayName = "Helpful-User-1000",
            content = "검색을 개선해 주세요.",
            status = "NEW",
            reviewedAt = null,
            repliedAt = null,
            createdAt = Instant.parse("2026-07-30T00:00:00Z"),
        )
        var lastQuery: String? = null
        var lastStatus: String? = null
        var lastLimit = 0
        var lastOffset = 0
        var repliedFeedbackId: Long? = null

        override suspend fun feedbacks(query: String?, status: String?, limit: Int, offset: Int): AdminFeedbackPageResponse {
            lastQuery = query
            lastStatus = status
            lastLimit = limit
            lastOffset = offset
            return AdminFeedbackPageResponse(listOf(row), 1, limit, offset)
        }

        override suspend fun feedback(feedbackId: Long): AdminFeedbackSummary? =
            row.takeIf { it.id == feedbackId }

        override suspend fun markReviewed(feedbackId: Long, reviewedAt: Instant): AdminFeedbackSummary? {
            row = row.copy(status = "REVIEWED", reviewedAt = reviewedAt)
            return row.takeIf { it.id == feedbackId }
        }

        override suspend fun markReplied(feedbackId: Long, repliedAt: Instant): AdminFeedbackSummary? {
            repliedFeedbackId = feedbackId
            row = row.copy(status = "REPLIED", reviewedAt = row.reviewedAt ?: repliedAt, repliedAt = repliedAt)
            return row.takeIf { it.id == feedbackId }
        }
    }

    private class FakeAdminManagementPort : AdminManagementPort {
        override suspend fun users(query: String?, limit: Int, offset: Int) =
            AdminUserPageResponse(emptyList(), 0, limit, offset)

        override suspend fun user(userId: Long): AdminUserSummary? =
            if (userId == 7L) summary() else null

        override suspend fun tiers(): List<AdminMembershipTierResponse> = emptyList()

        override suspend fun updateTier(tierCode: String, monthlyQuestionLimit: Int): AdminMembershipTierResponse? = null

        override suspend fun assignPlan(userId: Long, command: AssignUserPlanCommand): AdminUserSummary? = null

        override suspend fun setCurrentPeriodQuestionLimit(
            userId: Long,
            questionLimitOverride: Int?,
        ): AdminUserSummary? = null

        private fun summary() = AdminUserSummary(
            id = 7,
            email = "user@example.com",
            displayName = "Helpful-User-1000",
            provider = "GOOGLE",
            status = "ACTIVE",
            tierCode = "TIER1",
            tierDescription = "Free",
            monthlyLimit = 30,
            monthlyLimitOverride = null,
            currentPeriodQuestionLimitOverride = null,
            usedCount = 0,
            remainingCount = 30,
            periodStartedAt = Instant.parse("2026-07-01T00:00:00Z"),
            resetAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdAt = Instant.parse("2026-07-01T00:00:00Z"),
        )
    }
}
