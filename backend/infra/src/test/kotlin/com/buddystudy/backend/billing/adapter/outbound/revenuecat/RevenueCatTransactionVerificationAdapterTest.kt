package com.buddystudy.backend.billing.adapter.outbound.revenuecat

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import com.buddystudy.billing.domain.BillingEnvironment
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun `verifies a transaction through the expected customer even when RevenueCat uses an alias`() = runBlocking<Unit> {
        val requestedUris = mutableListOf<String>()
        val adapter = adapter(requestedUris) { requestUri ->
            when {
                requestUri.path.endsWith("/customers/$accountToken/subscriptions") -> subscriptionsResponse()
                requestUri.path.endsWith("/subscriptions") -> subscriptionsResponse()
                requestUri.query?.contains("direction=desc") == true -> newestTransactionsResponse()
                requestUri.query?.contains("direction=asc") == true -> oldestTransactionResponse()
                else -> error("Unexpected RevenueCat request: $requestUri")
            }
        }

        val transaction = adapter.verify("200000000000002", accountToken, productId)

        assertThat(transaction.transactionId).isEqualTo("200000000000002")
        assertThat(transaction.appAccountToken).isEqualTo(accountToken)
        assertThat(requestedUris).hasSize(4)
        assertThat(requestedUris).anyMatch { it.contains("/customers/$accountToken/subscriptions") }
    }

    @Test
    fun `keeps confirmation retryable while RevenueCat customer association is propagating`() {
        val requestedUris = mutableListOf<String>()
        val adapter = adapter(requestedUris) { requestUri ->
            when {
                requestUri.path.endsWith("/customers/$accountToken/subscriptions") -> "{\"items\":[]}"
                requestUri.path.endsWith("/subscriptions") -> subscriptionsResponse()
                requestUri.query?.contains("direction=desc") == true -> newestTransactionsResponse()
                else -> error("Unexpected RevenueCat request: $requestUri")
            }
        }

        val error = assertThrows<ApiRuntimeException> {
            runBlocking { adapter.verify("200000000000002", accountToken, productId) }
        }

        assertThat(error.errorCode).isEqualTo(ApiErrorCode.BILLING_APPLICATION_FAILED)
        assertThat(error.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `rejects a transaction whose product differs from the prepared invoice`() {
        val requestedUris = mutableListOf<String>()
        val adapter = adapter(requestedUris) { requestUri ->
            when {
                requestUri.path.endsWith("/subscriptions") -> subscriptionsResponse()
                requestUri.query?.contains("direction=desc") == true -> newestTransactionsResponse()
                else -> error("Unexpected RevenueCat request: $requestUri")
            }
        }

        val error = assertThrows<ApiRuntimeException> {
            runBlocking {
                adapter.verify(
                    "200000000000002",
                    accountToken,
                    "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                )
            }
        }

        assertThat(error.errorCode).isEqualTo(ApiErrorCode.BILLING_TRANSACTION_INVALID)
        assertThat(error.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(requestedUris).noneMatch { it.contains("/customers/") }
    }

    @Test
    fun `resolves newest exact product transaction when SDK omits transaction identifier`() = runBlocking<Unit> {
        val requestedUris = mutableListOf<String>()
        val adapter = adapter(requestedUris) { requestUri ->
            when {
                requestUri.path.endsWith("/customers/$accountToken/subscriptions") -> subscriptionsResponse()
                requestUri.query?.contains("direction=desc") == true -> newestTransactionsResponse()
                requestUri.query?.contains("direction=asc") == true -> oldestTransactionResponse()
                else -> error("Unexpected RevenueCat request: $requestUri")
            }
        }

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

    private fun adapter(
        requestedUris: MutableList<String>,
        response: (java.net.URI) -> String,
    ): RevenueCatTransactionVerificationAdapter {
        val client = WebClient.builder().exchangeFunction { request ->
            requestedUris += request.url().toString()
            Mono.just(jsonResponse(response(request.url())))
        }
        val properties = BuddyStudyProperties().apply {
            billing.revenueCat.projectId = "project-1"
            billing.revenueCat.appId = "app-1"
            billing.revenueCat.serverApiKey = "secret-key"
            billing.revenueCat.apiBaseUrl = "https://revenuecat.example/v2"
            billing.revenueCat.maxRetries = 1
        }
        return RevenueCatTransactionVerificationAdapter(
            properties,
            client,
            jacksonObjectMapper(),
            Clock.fixed(Instant.parse("2026-08-08T14:00:00Z"), ZoneOffset.UTC),
        )
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
