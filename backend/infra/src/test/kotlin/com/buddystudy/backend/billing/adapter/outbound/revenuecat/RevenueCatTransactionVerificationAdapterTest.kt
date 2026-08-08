package com.buddystudy.backend.billing.adapter.outbound.revenuecat

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.billing.domain.BillingEnvironment
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

class RevenueCatTransactionVerificationAdapterTest {
    private val accountToken = UUID.fromString("f9d47348-7b53-41c3-9f06-ef0b6f3c5e22")
    private val productId = "io.github.ghkdqhrbals.StudyMate.tier2.monthly"

    @Test
    fun `resolves newest exact product transaction when SDK omits transaction identifier`() = runBlocking<Unit> {
        val requestedUris = mutableListOf<String>()
        val mapper = jacksonObjectMapper()
        val client = WebClient.builder().exchangeFunction { request ->
            requestedUris += request.url().toString()
            val body = when {
                request.url().path.endsWith("/customers/$accountToken/subscriptions") -> subscriptionsResponse()
                request.url().query?.contains("direction=desc") == true -> newestTransactionsResponse()
                request.url().query?.contains("direction=asc") == true -> oldestTransactionResponse()
                else -> error("Unexpected RevenueCat request: ${request.url()}")
            }
            Mono.just(jsonResponse(body))
        }
        val properties = BuddyStudyProperties().apply {
            billing.revenueCat.projectId = "project-1"
            billing.revenueCat.appId = "app-1"
            billing.revenueCat.serverApiKey = "secret-key"
            billing.revenueCat.apiBaseUrl = "https://revenuecat.example/v2"
            billing.revenueCat.maxRetries = 1
        }
        val adapter = RevenueCatTransactionVerificationAdapter(
            properties,
            client,
            mapper,
            Clock.fixed(Instant.parse("2026-08-08T14:00:00Z"), ZoneOffset.UTC),
        )

        val transaction = runCatching { adapter.verifyLatest(accountToken, productId) }
            .getOrElse { throw AssertionError("RevenueCat requests were $requestedUris", it) }

        assertThat(transaction.transactionId).isEqualTo("200000000000002")
        assertThat(transaction.originalTransactionId).isEqualTo("200000000000001")
        assertThat(transaction.appAccountToken).isEqualTo(accountToken)
        assertThat(transaction.productId).isEqualTo(productId)
        assertThat(transaction.environment).isEqualTo(BillingEnvironment.SANDBOX)
        assertThat(requestedUris).hasSize(3)
        assertThat(requestedUris.first()).contains("/projects/project-1/customers/$accountToken/subscriptions")
    }

    private fun subscriptionsResponse(): String =
        """
        {
          "items": [{
            "id": "subscription-1",
            "customer_id": "${'$'}RCAnonymousID:canonical-customer",
            "original_customer_id": "${'$'}RCAnonymousID:original-customer",
            "app_id": "app-1",
            "store": "app_store",
            "status": "active",
            "environment": "sandbox",
            "gives_access": true,
            "pending_payment": false,
            "current_period_ends_at": 1788789600000
          }]
        }
        """.trimIndent()

    private fun newestTransactionsResponse(): String =
        """
        {
          "items": [
            {
              "id": "200000000000002",
              "product_store_identifier": "$productId",
              "purchased_at": 1786196319000,
              "expiration_date": 1788789600000
            },
            {
              "id": "200000000000010",
              "product_store_identifier": "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
              "purchased_at": 1786196320000,
              "expiration_date": 1788789600000
            }
          ]
        }
        """.trimIndent()

    private fun oldestTransactionResponse(): String =
        """
        {
          "items": [{
            "id": "200000000000001",
            "product_store_identifier": "$productId",
            "purchased_at": 1783604319000,
            "expiration_date": 1786196319000
          }]
        }
        """.trimIndent()

    private fun jsonResponse(body: String): ClientResponse =
        ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build()
}
