package com.buddystudy.backend.notification.application.model

import com.buddystudy.backend.common.application.model.PageResponse
import java.time.Instant

enum class PushCampaignAudience { ALL_REGISTERED, SELECTED_USERS }
enum class PushCampaignStatus { DRAFT, QUEUED }
enum class NotificationOpenSource { PUSH, INBOX }

data class PushCampaignCommand(
    val idempotencyKey: String? = null,
    val title: String,
    val body: String,
    val deepLink: String,
    val audience: PushCampaignAudience,
    val userIds: List<Long> = emptyList(),
)

data class PushCampaignRecord(
    val id: String,
    val title: String,
    val body: String,
    val deepLink: String,
    val audience: PushCampaignAudience,
    val userIds: List<Long>,
    val status: PushCampaignStatus = PushCampaignStatus.DRAFT,
    val createdAt: Instant,
    val sentAt: Instant? = null,
)

data class PushCampaignMetrics(
    val recipientCount: Long = 0,
    val acceptedCount: Long = 0,
    val failedCount: Long = 0,
    val uniqueOpenCount: Long = 0,
    val inboxOpenCount: Long = 0,
) {
    val queuedCount: Long get() = (recipientCount - acceptedCount - failedCount).coerceAtLeast(0)
    // APNs acceptance is observable; delivery to the device is not.
    val clickThroughRate: Double get() = if (acceptedCount == 0L) 0.0 else uniqueOpenCount.toDouble() / acceptedCount
}

data class PushCampaignResponse(
    val id: String,
    val title: String,
    val body: String,
    val deepLink: String,
    val audience: PushCampaignAudience,
    val userIds: List<Long>,
    val status: PushCampaignStatus,
    val createdAt: Instant,
    val sentAt: Instant?,
    val recipientCount: Long,
    val queuedCount: Long,
    val acceptedCount: Long,
    val failedCount: Long,
    val uniqueOpenCount: Long,
    val inboxOpenCount: Long,
    val clickThroughRate: Double,
)

data class PushCampaignPageResponse(
    val campaigns: List<PushCampaignResponse>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse

data class PushCampaignPreviewResponse(
    val title: String,
    val body: String,
    val deepLink: String,
    val audience: PushCampaignAudience,
    val recipientCount: Long,
)

data class PendingPushCampaignRecipient(
    val campaignId: String,
    val userId: Long,
    val eventId: String,
    val title: String,
    val body: String,
    val deepLink: String,
)
