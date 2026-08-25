package com.buddystudy.backend.community.adapter.inbound.web

import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignPage
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignSummary
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementRankingPolicySummary
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage
import com.buddystudy.backend.community.application.model.AdminNativeAdPlacementMetrics
import com.buddystudy.backend.community.application.model.AdminNativeAdPlacementPolicyResponse
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

class AdminNativeAdvertisementControllerTest {
    @Test
    fun `placement policy endpoint exposes controls and thirty day provider metrics`(): Unit = runBlocking {
        val policies = FakeAdminNativeAdPlacementPolicyWebPort()
        val client = WebTestClient.bindToController(AdminNativeAdPlacementPolicyController(policies)).build()

        client.get()
            .uri("/api/v1/admin/native-ad-placement-policies/COMMUNITY_FEED")
            .header("Authorization", "Bearer operator-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.placement").isEqualTo("COMMUNITY_FEED")
            .jsonPath("$.enabled").isEqualTo(false)
            .jsonPath("$.dailyDeliveryCap").isEqualTo(2)
            .jsonPath("$.minimumSecondsBetweenDeliveries").isEqualTo(21600)
            .jsonPath("$.metrics.slotDeliveries").isEqualTo(9)
            .jsonPath("$.metrics.adMobImpressions").isEqualTo(5)
            .jsonPath("$.metrics.fallbackSelections").isEqualTo(2)

        assertThat(policies.adminToken).isEqualTo("operator-token")
        assertThat(policies.placement).isEqualTo("COMMUNITY_FEED")
    }

    @Test
    fun `campaign endpoint binds and forwards every list filter`(): Unit = runBlocking {
        val advertisements = FakeAdminNativeAdvertisementWebPort()
        val client = WebTestClient.bindToController(AdminNativeAdvertisementController(advertisements)).build()

        client.get()
            .uri { builder ->
                builder.path("/api/v1/admin/native-ad-campaigns")
                    .queryParam("query", " Focus Lamp ")
                    .queryParam("status", "ACTIVE")
                    .queryParam("audience", "AUTHENTICATED")
                    .queryParam("limit", "35")
                    .queryParam("offset", "7")
                    .build()
            }
            .header("Authorization", "Bearer operator-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.limit").isEqualTo(35)
            .jsonPath("$.offset").isEqualTo(7)

        assertThat(advertisements.request).isEqualTo(
            CampaignRequest(
                adminToken = "operator-token",
                query = " Focus Lamp ",
                status = "ACTIVE",
                audience = "AUTHENTICATED",
                limit = 35,
                offset = 7,
            ),
        )
    }

    @Test
    fun `campaign endpoint keeps blank filters and applies paging defaults`(): Unit = runBlocking {
        val advertisements = FakeAdminNativeAdvertisementWebPort()
        val client = WebTestClient.bindToController(AdminNativeAdvertisementController(advertisements)).build()

        client.get()
            .uri("/api/v1/admin/native-ad-campaigns?query=&status=&audience=")
            .header("Authorization", "Bearer operator-token")
            .exchange()
            .expectStatus().isOk

        assertThat(advertisements.request).isEqualTo(
            CampaignRequest("operator-token", "", "", "", 20, 0),
        )
    }

    private class FakeAdminNativeAdvertisementWebPort : AdminNativeAdvertisementWebPort {
        var request: CampaignRequest? = null

        override suspend fun campaigns(
            adminToken: String,
            query: String?,
            status: String?,
            audience: String?,
            limit: Int,
            offset: Int,
        ): AdminNativeAdvertisementCampaignPage {
            request = CampaignRequest(adminToken, query, status, audience, limit, offset)
            return AdminNativeAdvertisementCampaignPage(
                campaigns = emptyList(),
                totalCount = 0,
                limit = limit,
                offset = offset,
                rankingPolicy = rankingPolicy(),
            )
        }

        override suspend fun create(
            adminToken: String,
            request: AdminNativeAdvertisementCampaignRequest,
        ): AdminNativeAdvertisementCampaignSummary = error("Not used in this test.")

        override suspend fun update(
            adminToken: String,
            campaignId: Long,
            request: AdminNativeAdvertisementCampaignRequest,
        ): AdminNativeAdvertisementCampaignSummary = error("Not used in this test.")

        override suspend fun users(
            adminToken: String,
            campaignId: Long,
            query: String?,
            status: String?,
            limit: Int,
            offset: Int,
        ): AdminNativeAdvertisementUserPage = error("Not used in this test.")
    }

    private class FakeAdminNativeAdPlacementPolicyWebPort : AdminNativeAdPlacementPolicyWebPort {
        var adminToken: String? = null
        var placement: String? = null

        override suspend fun policy(adminToken: String, placement: String): AdminNativeAdPlacementPolicyResponse {
            this.adminToken = adminToken
            this.placement = placement
            return response()
        }

        override suspend fun updatePolicy(
            adminToken: String,
            placement: String,
            request: AdminNativeAdPlacementPolicyRequest,
        ): AdminNativeAdPlacementPolicyResponse = response()

        private fun response() = AdminNativeAdPlacementPolicyResponse(
            placement = "COMMUNITY_FEED",
            enabled = false,
            dailyDeliveryCap = 2,
            minimumSecondsBetweenDeliveries = 21_600,
            minimumFeedItemCount = 4,
            earliestPosition = 2,
            latestPosition = 7,
            startsAt = null,
            endsAt = null,
            updatedAt = Instant.parse("2026-08-25T00:00:00Z"),
            metrics = AdminNativeAdPlacementMetrics(
                slotDeliveries = 9,
                adMobImpressions = 5,
                fallbackSelections = 2,
            ),
        )
    }

    private data class CampaignRequest(
        val adminToken: String,
        val query: String?,
        val status: String?,
        val audience: String?,
        val limit: Int,
        val offset: Int,
    )
}

private fun rankingPolicy() = AdminNativeAdvertisementRankingPolicySummary(
    performanceWindowDays = 30,
    exploitationPercent = 85,
    explorationPercent = 15,
    selectionPoolSize = 3,
    basePriorityWeight = 40.0,
    relevanceWeight = 25.0,
    smoothedViewRateWeight = 20.0,
    explorationWeight = 10.0,
    freshnessWeight = 5.0,
    dailySelectionPenalty = 0.05,
)
