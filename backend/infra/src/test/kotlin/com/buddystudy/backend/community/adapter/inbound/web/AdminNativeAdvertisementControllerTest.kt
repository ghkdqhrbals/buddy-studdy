package com.buddystudy.backend.community.adapter.inbound.web

import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignPage
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignSummary
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementRankingPolicySummary
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

class AdminNativeAdvertisementControllerTest {
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
