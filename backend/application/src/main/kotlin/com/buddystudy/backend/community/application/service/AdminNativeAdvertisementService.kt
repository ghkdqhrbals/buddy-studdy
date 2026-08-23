package com.buddystudy.backend.community.application.service

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignCommand
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignFilter
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignPage
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignStatus
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignSummary
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementRankingPolicySummary
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserSummary
import com.buddystudy.backend.community.application.policy.NativeAdvertisementDeepLinkPolicy
import com.buddystudy.backend.community.application.policy.NativeAdvertisementImagePolicy
import com.buddystudy.backend.community.application.policy.NativeAdvertisementRankingPolicy
import com.buddystudy.backend.community.application.port.inbound.AdminNativeAdvertisementUseCase
import com.buddystudy.backend.community.application.port.outbound.AdminNativeAdvertisementPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementCampaignPerformance
import com.buddystudy.community.domain.entity.NativeAdvertisementAudience
import com.buddystudy.community.domain.entity.NativeAdvertisementCampaignEntity
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale

@Service
class AdminNativeAdvertisementService(
    private val advertisements: AdminNativeAdvertisementPort,
) : AdminNativeAdvertisementUseCase {
    @Transactional(readOnly = true)
    override suspend fun campaigns(
        query: String?,
        status: String?,
        audience: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementCampaignPage {
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        val now = Instant.now()
        val filter = AdminNativeAdvertisementCampaignFilter(
            query = query?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT),
            status = status.toCampaignStatus(),
            audience = audience.toCampaignAudience(),
            evaluatedAt = now,
        )
        val since = NativeAdvertisementRankingPolicy.performanceWindowStart(now)
        val campaignEntities = advertisements.findCampaigns(filter, safeLimit, safeOffset)
        val performance = advertisements.findCampaignPerformance(campaignEntities.map { it.id }, since)
        val campaigns = campaignEntities.map { campaign ->
            val signals = performance[campaign.id] ?: NativeAdvertisementCampaignPerformance(
                campaignId = campaign.id,
                selections = 0,
                opens = 0,
                suppressions = 0,
            )
            campaign.toSummary(
                selections = signals.selections,
                views = signals.opens,
                suppressions = signals.suppressions,
            )
        }
        return AdminNativeAdvertisementCampaignPage(
            campaigns = campaigns,
            totalCount = advertisements.countCampaigns(filter),
            limit = safeLimit,
            offset = safeOffset,
            rankingPolicy = rankingPolicySummary(),
        )
    }

    @Transactional
    override suspend fun create(command: AdminNativeAdvertisementCampaignCommand): AdminNativeAdvertisementCampaignSummary {
        val normalized = validateAndNormalize(command)
        if (advertisements.findCampaignByKey(normalized.campaignKey) != null) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "Advertisement campaign key already exists.")
        }
        val now = Instant.now()
        val created = advertisements.saveCampaign(normalized.toEntity(createdAt = now, updatedAt = now))
        return created.toSummary(0, 0, 0)
    }

    @Transactional
    override suspend fun update(
        id: Long,
        command: AdminNativeAdvertisementCampaignCommand,
    ): AdminNativeAdvertisementCampaignSummary {
        val existing = advertisements.findCampaign(id)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Advertisement campaign not found.")
        val normalized = validateAndNormalize(command)
        val sameKey = advertisements.findCampaignByKey(normalized.campaignKey)
        if (sameKey != null && sameKey.id != id) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "Advertisement campaign key already exists.")
        }
        normalized.applyTo(existing)
        existing.updatedAt = Instant.now()
        val updated = advertisements.saveCampaign(existing)
        val since = NativeAdvertisementRankingPolicy.performanceWindowStart(Instant.now())
        val signals = advertisements.findCampaignPerformance(listOf(id), since)[id]
            ?: NativeAdvertisementCampaignPerformance(id, 0, 0, 0)
        return updated.toSummary(signals.selections, signals.opens, signals.suppressions)
    }

    @Transactional(readOnly = true)
    override suspend fun users(
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementUserPage {
        if (advertisements.findCampaign(campaignId) == null) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Advertisement campaign not found.")
        }
        val normalizedStatus = status
            ?.trim()
            ?.uppercase()
            ?.takeIf(ADMIN_USER_STATUSES::contains)
        val page = advertisements.campaignUsers(
            campaignId = campaignId,
            query = query?.trim()?.takeIf(String::isNotEmpty),
            status = normalizedStatus,
            limit = limit.coerceIn(1, 100),
            offset = offset.coerceAtLeast(0),
        )
        return page.copy(users = page.users.map { it.redactedIdentity() })
    }

    private fun validateAndNormalize(command: AdminNativeAdvertisementCampaignCommand): AdminNativeAdvertisementCampaignCommand {
        val normalized = command.copy(
            campaignKey = command.campaignKey.trim().lowercase(),
            disclosureKo = command.disclosureKo.trim(),
            disclosureEn = command.disclosureEn.trim(),
            disclosureJa = command.disclosureJa.trim(),
            titleKo = command.titleKo.trim(),
            titleEn = command.titleEn.trim(),
            titleJa = command.titleJa.trim(),
            bodyKo = command.bodyKo.cleanOptional(),
            bodyEn = command.bodyEn.cleanOptional(),
            bodyJa = command.bodyJa.cleanOptional(),
            imageUrl = command.imageUrl.cleanOptional(),
            affiliateDisclosureKo = command.affiliateDisclosureKo.cleanOptional(),
            affiliateDisclosureEn = command.affiliateDisclosureEn.cleanOptional(),
            affiliateDisclosureJa = command.affiliateDisclosureJa.cleanOptional(),
            destinationUrl = command.destinationUrl.trim(),
        )
        val required = listOf(
            normalized.campaignKey,
            normalized.disclosureKo,
            normalized.disclosureEn,
            normalized.disclosureJa,
            normalized.titleKo,
            normalized.titleEn,
            normalized.titleJa,
            normalized.destinationUrl,
        )
        val validLengths = normalized.campaignKey.length <= 96 &&
            listOf(normalized.disclosureKo, normalized.disclosureEn, normalized.disclosureJa).all { it.length <= 32 } &&
            listOf(normalized.titleKo, normalized.titleEn, normalized.titleJa).all { it.length <= 255 } &&
            listOfNotNull(normalized.bodyKo, normalized.bodyEn, normalized.bodyJa).all { it.length <= 500 } &&
            listOfNotNull(
                normalized.affiliateDisclosureKo,
                normalized.affiliateDisclosureEn,
                normalized.affiliateDisclosureJa,
            ).all { it.length <= 500 } &&
            (normalized.imageUrl?.length ?: 0) <= 1024 &&
            normalized.destinationUrl.length <= 512
        val validNumbers = normalized.basePriority.inRange() &&
            normalized.authenticatedRelevance.inRange() &&
            normalized.anonymousRelevance.inRange() &&
            normalized.dailySelectionCap in 0..100 &&
            normalized.minimumSecondsBetweenSelections in 0..2_592_000 &&
            normalized.postViewCooldownSeconds in 0..31_536_000 &&
            normalized.minimumFeedItemCount in 1..100 &&
            normalized.earliestPosition in 0..99 &&
            normalized.latestPosition in normalized.earliestPosition..99
        val validWindow = normalized.endsAt?.let { end -> normalized.startsAt?.let { end.isAfter(it) } ?: true } != false
        val coupangRequiredCreative = if (NativeAdvertisementDeepLinkPolicy.isCoupang(normalized.destinationUrl)) {
            normalized.imageUrl != null &&
                listOf(
                    normalized.affiliateDisclosureKo,
                    normalized.affiliateDisclosureEn,
                    normalized.affiliateDisclosureJa,
                ).none { it.isNullOrBlank() }
        } else {
            true
        }
        val validImage = normalized.imageUrl?.let(NativeAdvertisementImagePolicy::isSupported) != false
        if (
            required.any(String::isBlank) ||
            !CAMPAIGN_KEY.matches(normalized.campaignKey) ||
            !validLengths ||
            !validNumbers ||
            !validWindow ||
            !coupangRequiredCreative ||
            !validImage ||
            !NativeAdvertisementDeepLinkPolicy.isSupported(normalized.destinationUrl)
        ) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Advertisement campaign is invalid. Coupang ads require a Coupang CDN image and affiliate disclosure in every language.",
            )
        }
        return normalized
    }

    private fun rankingPolicySummary() = AdminNativeAdvertisementRankingPolicySummary(
        performanceWindowDays = NativeAdvertisementRankingPolicy.performanceWindowDays,
        exploitationPercent = 100 - NativeAdvertisementRankingPolicy.explorationPercent,
        explorationPercent = NativeAdvertisementRankingPolicy.explorationPercent,
        selectionPoolSize = NativeAdvertisementRankingPolicy.selectionPoolSize,
        basePriorityWeight = NativeAdvertisementRankingPolicy.basePriorityWeight,
        relevanceWeight = NativeAdvertisementRankingPolicy.relevanceWeight,
        smoothedViewRateWeight = NativeAdvertisementRankingPolicy.viewRateWeight,
        explorationWeight = NativeAdvertisementRankingPolicy.explorationWeight,
        freshnessWeight = NativeAdvertisementRankingPolicy.freshnessWeight,
        dailySelectionPenalty = NativeAdvertisementRankingPolicy.dailySelectionPenalty,
        notInterestedPenaltyWeight = NativeAdvertisementRankingPolicy.notInterestedPenaltyWeight,
    )

    private companion object {
        val CAMPAIGN_KEY = Regex("[a-z0-9][a-z0-9-]{2,95}")
        val ADMIN_USER_STATUSES = setOf("OPENED", "NOT_OPENED")
    }
}

private fun String?.toCampaignStatus(): AdminNativeAdvertisementCampaignStatus? {
    val normalized = this?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return null
    return AdminNativeAdvertisementCampaignStatus.entries.firstOrNull { it.name == normalized }
}

private fun String?.toCampaignAudience(): NativeAdvertisementAudience? {
    val normalized = this?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return null
    return NativeAdvertisementAudience.entries.firstOrNull { it.name == normalized }
}

private fun BigDecimal.inRange() = this >= BigDecimal.ZERO && this <= BigDecimal.TEN

private fun String?.cleanOptional() = this?.trim()?.takeIf(String::isNotEmpty)

private fun AdminNativeAdvertisementUserSummary.redactedIdentity(): AdminNativeAdvertisementUserSummary =
    if (accountStatus.uppercase() in REDACTED_AD_USER_STATUSES) {
        copy(email = null, displayName = null)
    } else {
        copy(
            email = email?.trim()?.takeIf(String::isNotEmpty),
            displayName = displayName?.trim()?.takeIf(String::isNotEmpty),
        )
    }

private val REDACTED_AD_USER_STATUSES = setOf("ANONYMOUS", "WITHDRAWN")

private fun AdminNativeAdvertisementCampaignCommand.toEntity(createdAt: Instant, updatedAt: Instant) =
    NativeAdvertisementCampaignEntity(
        campaignKey = campaignKey,
        placement = NativeAdvertisementRankingPolicy.placement,
        audience = audience,
        disclosureKo = disclosureKo,
        disclosureEn = disclosureEn,
        disclosureJa = disclosureJa,
        titleKo = titleKo,
        titleEn = titleEn,
        titleJa = titleJa,
        bodyKo = bodyKo,
        bodyEn = bodyEn,
        bodyJa = bodyJa,
        imageUrl = imageUrl,
        affiliateDisclosureKo = affiliateDisclosureKo,
        affiliateDisclosureEn = affiliateDisclosureEn,
        affiliateDisclosureJa = affiliateDisclosureJa,
        deepLink = destinationUrl,
        basePriority = basePriority,
        authenticatedRelevance = authenticatedRelevance,
        anonymousRelevance = anonymousRelevance,
        dailySelectionCap = dailySelectionCap,
        minimumSecondsBetweenSelections = minimumSecondsBetweenSelections,
        postViewCooldownSeconds = postViewCooldownSeconds,
        minimumFeedItemCount = minimumFeedItemCount,
        earliestPosition = earliestPosition,
        latestPosition = latestPosition,
        active = active,
        startsAt = startsAt,
        endsAt = endsAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun AdminNativeAdvertisementCampaignCommand.applyTo(entity: NativeAdvertisementCampaignEntity) {
    entity.campaignKey = campaignKey
    entity.placement = NativeAdvertisementRankingPolicy.placement
    entity.audience = audience
    entity.disclosureKo = disclosureKo
    entity.disclosureEn = disclosureEn
    entity.disclosureJa = disclosureJa
    entity.titleKo = titleKo
    entity.titleEn = titleEn
    entity.titleJa = titleJa
    entity.bodyKo = bodyKo
    entity.bodyEn = bodyEn
    entity.bodyJa = bodyJa
    entity.imageUrl = imageUrl
    entity.affiliateDisclosureKo = affiliateDisclosureKo
    entity.affiliateDisclosureEn = affiliateDisclosureEn
    entity.affiliateDisclosureJa = affiliateDisclosureJa
    entity.deepLink = destinationUrl
    entity.basePriority = basePriority
    entity.authenticatedRelevance = authenticatedRelevance
    entity.anonymousRelevance = anonymousRelevance
    entity.dailySelectionCap = dailySelectionCap
    entity.minimumSecondsBetweenSelections = minimumSecondsBetweenSelections
    entity.postViewCooldownSeconds = postViewCooldownSeconds
    entity.minimumFeedItemCount = minimumFeedItemCount
    entity.earliestPosition = earliestPosition
    entity.latestPosition = latestPosition
    entity.active = active
    entity.startsAt = startsAt
    entity.endsAt = endsAt
}

private fun NativeAdvertisementCampaignEntity.toSummary(
    selections: Long,
    views: Long,
    suppressions: Long,
) =
    AdminNativeAdvertisementCampaignSummary(
        id = id,
        campaignKey = campaignKey,
        placement = placement,
        audience = audience,
        disclosureKo = disclosureKo,
        disclosureEn = disclosureEn,
        disclosureJa = disclosureJa,
        titleKo = titleKo,
        titleEn = titleEn,
        titleJa = titleJa,
        bodyKo = bodyKo,
        bodyEn = bodyEn,
        bodyJa = bodyJa,
        imageUrl = imageUrl,
        affiliateDisclosureKo = affiliateDisclosureKo,
        affiliateDisclosureEn = affiliateDisclosureEn,
        affiliateDisclosureJa = affiliateDisclosureJa,
        destinationUrl = deepLink,
        basePriority = basePriority,
        authenticatedRelevance = authenticatedRelevance,
        anonymousRelevance = anonymousRelevance,
        dailySelectionCap = dailySelectionCap,
        minimumSecondsBetweenSelections = minimumSecondsBetweenSelections,
        postViewCooldownSeconds = postViewCooldownSeconds,
        minimumFeedItemCount = minimumFeedItemCount,
        earliestPosition = earliestPosition,
        latestPosition = latestPosition,
        active = active,
        startsAt = startsAt,
        endsAt = endsAt,
        performanceSelections = selections,
        performanceViews = views,
        performanceViewRate = if (selections > 0) views.toDouble() / selections else 0.0,
        performanceSuppressions = suppressions,
        performanceSuppressionRate = if (selections > 0) {
            suppressions.coerceIn(0, selections).toDouble() / selections
        } else {
            0.0
        },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
