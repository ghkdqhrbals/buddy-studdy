package com.buddystudy.backend.notification.application.port.outbound

import com.buddystudy.backend.notification.application.model.*
import java.time.Instant

interface PushCampaignPersistencePort {
    suspend fun createIfAbsent(record: PushCampaignRecord)
    suspend fun find(id: String, forUpdate: Boolean = false): PushCampaignRecord?
    suspend fun page(limit: Int, offset: Int): List<PushCampaignRecord>
    suspend fun count(): Long
    suspend fun countAudience(audience: PushCampaignAudience, userIds: List<Long>): Long
    suspend fun isPublicQuestion(questionId: Long): Boolean
    suspend fun snapshotRecipients(campaign: PushCampaignRecord, now: Instant)
    suspend fun metrics(campaignIds: List<String>): Map<String, PushCampaignMetrics>
    suspend fun pendingRecipientsForUpdate(limit: Int): List<PendingPushCampaignRecipient>
    suspend fun markEnqueued(campaignId: String, userId: Long, now: Instant)
}

interface NotificationOpenPersistencePort {
    suspend fun markOpen(notificationId: Long, source: NotificationOpenSource, now: Instant)
}

interface ArchivePushCampaignOutcomesPort {
    suspend fun archiveAndForgetUser(userId: Long)
}
