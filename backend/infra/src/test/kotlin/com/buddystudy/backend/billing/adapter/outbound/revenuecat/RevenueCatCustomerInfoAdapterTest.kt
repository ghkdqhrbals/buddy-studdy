package com.buddystudy.backend.billing.adapter.outbound.revenuecat

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.test.testExternalApiHistoryRecorder
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import com.buddystudy.billing.domain.SubscriptionRenewalStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RevenueCatCustomerInfoAdapterTest {
    private val accountToken = UUID.fromString("f9d47348-7b53-41c3-9f06-ef0b6f3c5e22")
    private val originalTransactionId = "200000000000001"
    private val now = Instant.parse("2026-08-09T14:00:00Z")

    @Test
    fun `searches the complete subscription chain by original transaction id`() = runBlocking<Unit> {
        val requestedUris = mutableListOf<String>()
        val client = WebClient.builder().exchangeFunction { request ->
            requestedUris += request.url().toString()
            Mono.just(jsonResponse(subscriptionResponse("subscription-1")))
        }
        val adapter = RevenueCatCustomerInfoAdapter(
            properties(),
            client,
            jacksonObjectMapper(),
            testExternalApiHistoryRecorder(),
            Clock.fixed(now, ZoneOffset.UTC),
        )

        val snapshot = adapter.fetch(accountToken, originalTransactionId)

        assertThat(snapshot.accessStatus).isEqualTo(SubscriptionAccessStatus.ACTIVE)
        assertThat(snapshot.renewalStatus).isEqualTo(SubscriptionRenewalStatus.WILL_RENEW)
        assertThat(snapshot.expiresAt).isEqualTo(Instant.ofEpochMilli(1_788_789_600_000))
        assertThat(requestedUris).hasSize(2)
        assertThat(requestedUris).anyMatch {
            it.contains("/projects/project-1/subscriptions") &&
                it.contains("store_subscription_identifier=$originalTransactionId")
        }
        assertThat(requestedUris).anyMatch {
            it.contains("/projects/project-1/customers/$accountToken/subscriptions")
        }
    }

    @Test
    fun `does not treat a subscription owned by another current customer as authoritative`() = runBlocking<Unit> {
        val client = WebClient.builder().exchangeFunction { request ->
            val subscriptionId = if (request.url().path.contains("/customers/")) {
                "subscription-for-another-transaction"
            } else {
                "subscription-1"
            }
            Mono.just(jsonResponse(subscriptionResponse(subscriptionId)))
        }
        val adapter = RevenueCatCustomerInfoAdapter(
            properties(),
            client,
            jacksonObjectMapper(),
            testExternalApiHistoryRecorder(),
            Clock.fixed(now, ZoneOffset.UTC),
        )

        val snapshot = adapter.fetch(accountToken, originalTransactionId)

        assertThat(snapshot.accessStatus).isEqualTo(SubscriptionAccessStatus.UNKNOWN)
        assertThat(snapshot.renewalStatus).isEqualTo(SubscriptionRenewalStatus.UNKNOWN)
        assertThat(snapshot.expiresAt).isNull()
    }

    private fun properties() = BuddyStudyProperties().apply {
        billing.revenueCat.projectId = "project-1"
        billing.revenueCat.appId = "app-1"
        billing.revenueCat.serverApiKey = "secret-key"
        billing.revenueCat.apiBaseUrl = "https://revenuecat.example/v2"
        billing.revenueCat.maxRetries = 1
    }

    private fun subscriptionResponse(subscriptionId: String): String =
        """
        {
          "items": [{
            "id": "$subscriptionId",
            "customer_id": "${'$'}RCAnonymousID:canonical-customer",
            "original_customer_id": "legacy-customer",
            "app_id": "app-1",
            "store": "app_store",
            "status": "active",
            "environment": "sandbox",
            "gives_access": true,
            "pending_payment": false,
            "auto_renewal_status": "will_renew",
            "current_period_ends_at": 1788789600000,
            "store_subscription_identifier": "200000000000999"
          }]
        }
        """.trimIndent()

    private fun jsonResponse(body: String): ClientResponse =
        ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build()
}
