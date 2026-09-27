package com.buddystudy.backend.notification.application.service

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.notification.application.model.*
import com.buddystudy.backend.notification.application.port.inbound.ManagePushCampaignsUseCase
import com.buddystudy.backend.notification.application.port.outbound.PushCampaignPersistencePort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Instant
import java.util.UUID

@Service
class PushCampaignService(private val campaigns: PushCampaignPersistencePort) : ManagePushCampaignsUseCase {
    override suspend fun preview(command: PushCampaignCommand): PushCampaignPreviewResponse {
        val normalized = validate(command)
        return PushCampaignPreviewResponse(normalized.title, normalized.body, normalized.deepLink, normalized.audience,
            audienceCount(normalized.audience, normalized.userIds))
    }

    @Transactional
    override suspend fun create(command: PushCampaignCommand): PushCampaignResponse {
        val normalized = validate(command)
        val id = command.idempotencyKey?.let { raw ->
            runCatching { UUID.fromString(raw).toString().takeIf { it == raw.lowercase() } }.getOrNull()
        } ?: invalid("A UUID idempotencyKey is required.")
        val candidate = PushCampaignRecord(id, normalized.title, normalized.body, normalized.deepLink,
            normalized.audience, normalized.userIds, createdAt = Instant.now())
        // The unique primary key also fences concurrent HTTP retries.
        campaigns.createIfAbsent(candidate)
        // SELECT FOR UPDATE is a current read: a concurrent insert winner must be
        // visible even when audience validation already established a MySQL RR snapshot.
        val saved = campaigns.find(id, forUpdate = true) ?: error("Push campaign was not persisted.")
        if (saved.copy(createdAt = candidate.createdAt, status = candidate.status, sentAt = null) != candidate) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR,
                "This idempotency key was already used for different campaign content.")
        }
        return response(saved)
    }

    @Transactional(readOnly = true)
    override suspend fun campaigns(limit: Int, offset: Int): PushCampaignPageResponse {
        val boundedLimit = limit.coerceIn(1, 100)
        val boundedOffset = offset.coerceAtLeast(0)
        val page = campaigns.page(boundedLimit, boundedOffset)
        val metrics = campaigns.metrics(page.map { it.id })
        return PushCampaignPageResponse(page.map { response(it, metrics[it.id] ?: PushCampaignMetrics()) },
            campaigns.count(), boundedLimit, boundedOffset)
    }

    override suspend fun campaign(id: String): PushCampaignResponse = response(ownedCampaign(id))

    @Transactional
    override suspend fun send(id: String): PushCampaignResponse {
        val campaign = ownedCampaign(id, forUpdate = true)
        if (campaign.status == PushCampaignStatus.DRAFT) {
            validateLanding(campaign.deepLink)
            if (audienceCount(campaign.audience, campaign.userIds) == 0L) invalid("There are no eligible recipients.")
            campaigns.snapshotRecipients(campaign, Instant.now())
        }
        return response(ownedCampaign(id))
    }

    private suspend fun validate(command: PushCampaignCommand): PushCampaignCommand {
        val title = command.title.trim()
        if (title.isEmpty() || title.length > 160) invalid("Title must contain between 1 and 160 characters.")
        if (command.body.isBlank() || command.body.length > 2_000) invalid("Body must contain between 1 and 2000 characters.")
        val ids = command.userIds.distinct().sorted()
        when (command.audience) {
            PushCampaignAudience.ALL_REGISTERED -> if (ids.isNotEmpty()) invalid("All registered audience cannot specify user IDs.")
            PushCampaignAudience.SELECTED_USERS -> {
                if (ids.isEmpty() || ids.size > 500 || ids.any { it <= 0 }) invalid("Select between 1 and 500 valid user IDs.")
                audienceCount(command.audience, ids)
            }
        }
        val link = command.deepLink.trim()
        validateLanding(link)
        return command.copy(title = title, deepLink = link, userIds = ids)
    }

    private suspend fun audienceCount(audience: PushCampaignAudience, userIds: List<Long>): Long {
        val count = campaigns.countAudience(audience, userIds)
        if (audience == PushCampaignAudience.SELECTED_USERS && count != userIds.size.toLong()) {
            invalid("Every selected user must be an active registered member.")
        }
        return count
    }

    private suspend fun validateLanding(value: String) {
        if (!PushCampaignLandingPolicy.isSupported(value)) invalid("Choose a supported BuddyStudy landing page.")
        PushCampaignLandingPolicy.publicQuestionId(value)?.let {
            if (!campaigns.isPublicQuestion(it)) invalid("The landing question must exist and be public.")
        }
    }

    private suspend fun ownedCampaign(id: String, forUpdate: Boolean = false): PushCampaignRecord =
        campaigns.find(id, forUpdate) ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Push campaign not found.")

    private suspend fun response(campaign: PushCampaignRecord, knownMetrics: PushCampaignMetrics? = null): PushCampaignResponse {
        val metrics = knownMetrics ?: campaigns.metrics(listOf(campaign.id))[campaign.id] ?: PushCampaignMetrics()
        val recipientCount = if (campaign.status == PushCampaignStatus.DRAFT) {
            campaigns.countAudience(campaign.audience, campaign.userIds)
        } else metrics.recipientCount
        return PushCampaignResponse(campaign.id, campaign.title, campaign.body, campaign.deepLink, campaign.audience,
            campaign.userIds, campaign.status, campaign.createdAt, campaign.sentAt, recipientCount,
            metrics.queuedCount, metrics.acceptedCount, metrics.failedCount, metrics.uniqueOpenCount,
            metrics.inboxOpenCount, metrics.clickThroughRate)
    }

    private fun invalid(message: String): Nothing =
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)
}

/** Exact allowlist: no external URLs, credentials, query tricks, or private record IDs in a broadcast. */
object PushCampaignLandingPolicy {
    private val pages = setOf("home", "public", "studies", "records", "stats", "statistics", "settings", "profile")
    fun isSupported(value: String): Boolean = runCatching {
        val uri = URI(value)
        if (value.length > 1_000 || uri.scheme != "buddystudy" || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawPath != uri.path) return false
        val path = uri.path.orEmpty()
        (uri.host in pages && path in setOf("", "/")) ||
            (uri.host == "public" && path == "/questions") ||
            (uri.host == "home" && path == "/message") || publicQuestionId(value) != null
    }.getOrDefault(false)

    fun publicQuestionId(value: String): Long? = runCatching {
        val uri = URI(value)
        if (uri.host != "public" || !uri.path.orEmpty().matches(Regex("/questions/[1-9][0-9]*"))) null
        else uri.path.substringAfterLast('/').toLongOrNull()
    }.getOrNull()
}
