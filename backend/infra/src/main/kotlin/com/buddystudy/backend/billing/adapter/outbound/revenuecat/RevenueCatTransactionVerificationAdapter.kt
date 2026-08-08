package com.buddystudy.backend.billing.adapter.outbound.revenuecat

import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatTransactionVerificationPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingProductType
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import reactor.netty.http.client.HttpClient
import reactor.util.retry.Retry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class RevenueCatTransactionVerificationAdapter(
    private val properties: BuddyStudyProperties,
    webClientBuilder: WebClient.Builder,
    private val clock: Clock = Clock.systemUTC(),
) : RevenueCatTransactionVerificationPort {
    private val client = webClientBuilder.clientConnector(
        ReactorClientHttpConnector(
            HttpClient.create().option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                properties.billing.revenueCat.connectTimeoutMs.coerceIn(500, 30_000).toInt(),
            ),
        ),
    ).build()

    override suspend fun verify(transactionId: String): VerifiedAppleTransaction {
        val normalizedTransactionId = transactionId.trim()
        val config = configuredRevenueCat()
        val searchUri = UriComponentsBuilder.fromUriString(config.apiBaseUrl.trimEnd('/'))
            .path("/projects/{projectId}/subscriptions")
            .queryParam("store_subscription_identifier", normalizedTransactionId)
            .queryParam("limit", 100)
            .buildAndExpand(config.projectId.trim())
            .toUri()
        val search = request(searchUri.toString(), SubscriptionSearchResponse::class.java, config.serverApiKey)
        val candidates = search.items.orEmpty().filter { subscription ->
            subscription.store == "app_store" &&
                (config.appId.isBlank() || subscription.appId == null || subscription.appId == config.appId)
        }
        val subscription = when (candidates.size) {
            1 -> candidates.single()
            0 -> throw temporarilyUnavailable("RevenueCat has not indexed this App Store transaction yet.")
            else -> throw invalidTransaction("RevenueCat returned more than one subscription for the transaction.")
        }
        val subscriptionId = subscription.id?.takeIf(String::isNotBlank)
            ?: throw invalidTransaction("RevenueCat subscription ID is missing.")
        val appAccountToken = sequenceOf(subscription.customerId, subscription.originalCustomerId)
            .filterNotNull()
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .firstOrNull()
            ?: throw invalidTransaction("RevenueCat customer ID is not a BuddyStudy appAccountToken.")
        val environment = when (subscription.environment) {
            "production" -> BillingEnvironment.PRODUCTION
            "sandbox" -> BillingEnvironment.SANDBOX
            else -> throw invalidTransaction("RevenueCat transaction environment is invalid.")
        }
        if (subscription.pendingPayment || !subscription.givesAccess || subscription.status !in ACCESS_GRANTING_STATUSES) {
            throw invalidTransaction("RevenueCat has not verified an access-granting purchase for the transaction.")
        }

        val newestTransactionsUri = UriComponentsBuilder.fromUriString(config.apiBaseUrl.trimEnd('/'))
            .path("/projects/{projectId}/subscriptions/{subscriptionId}/transactions")
            .queryParam("limit", 100)
            .queryParam("sort", "purchased_at")
            .queryParam("direction", "desc")
            .buildAndExpand(config.projectId.trim(), subscriptionId)
            .toUri()
        val newestTransactions = request(
            newestTransactionsUri.toString(),
            SubscriptionTransactionsResponse::class.java,
            config.serverApiKey,
        )
        val transaction = newestTransactions.items.orEmpty().singleOrNull { it.id == normalizedTransactionId }
            ?: throw temporarilyUnavailable("RevenueCat has not exposed the completed transaction yet.")
        val oldestTransactionsUri = UriComponentsBuilder.fromUriString(config.apiBaseUrl.trimEnd('/'))
            .path("/projects/{projectId}/subscriptions/{subscriptionId}/transactions")
            .queryParam("limit", 1)
            .queryParam("sort", "purchased_at")
            .queryParam("direction", "asc")
            .buildAndExpand(config.projectId.trim(), subscriptionId)
            .toUri()
        val originalTransactionId = request(
            oldestTransactionsUri.toString(),
            SubscriptionTransactionsResponse::class.java,
            config.serverApiKey,
        ).items.orEmpty().singleOrNull()?.id?.takeIf(String::isNotBlank)
            ?: throw invalidTransaction("RevenueCat original App Store transaction ID is missing.")
        val productId = transaction.productStoreIdentifier?.takeIf(PRODUCT_ID::matches)
            ?: throw invalidTransaction("RevenueCat transaction product ID is missing or invalid.")
        val purchasedAt = transaction.purchasedAt?.let(Instant::ofEpochMilli)
            ?: throw invalidTransaction("RevenueCat transaction purchase timestamp is missing.")
        val expiresAt = (transaction.effectiveExpirationDate ?: transaction.expirationDate)?.let(Instant::ofEpochMilli)
            ?: subscription.currentPeriodEndsAt?.let(Instant::ofEpochMilli)
        val verifiedAt = clock.instant().coerceAtLeast(purchasedAt)

        return VerifiedAppleTransaction(
            transactionId = normalizedTransactionId,
            originalTransactionId = originalTransactionId,
            appTransactionId = null,
            webOrderLineItemId = null,
            appAccountToken = appAccountToken,
            productId = productId,
            productType = BillingProductType.AUTO_RENEWABLE_SUBSCRIPTION,
            environment = environment,
            quantity = 1,
            priceMilliunits = null,
            currency = null,
            purchaseAt = purchasedAt,
            originalPurchaseAt = null,
            expiresAt = expiresAt,
            revocationAt = null,
            revocationReason = null,
            signedAt = verifiedAt,
            signedPayloadSha256 = verificationHash(subscriptionId, transaction),
        )
    }

    private suspend fun <T : Any> request(uri: String, bodyType: Class<T>, apiKey: String): T {
        val config = properties.billing.revenueCat
        return try {
            client.get().uri(uri).headers { it.setBearerAuth(apiKey.trim()) }
                .retrieve()
                .bodyToMono(bodyType)
                .timeout(Duration.ofMillis(config.readTimeoutMs.coerceIn(500, 30_000)))
                .retryWhen(
                    Retry.backoff((config.maxRetries.coerceIn(1, 3) - 1).toLong(), Duration.ofMillis(200))
                        .filter(::isRetryable),
                )
                .awaitSingle()
        } catch (error: WebClientResponseException.Unauthorized) {
            throw configurationError("RevenueCat server API key was rejected.")
        } catch (error: WebClientResponseException.Forbidden) {
            throw configurationError("RevenueCat server API key cannot read subscriptions.")
        } catch (error: ApiException) {
            throw error
        } catch (error: Exception) {
            throw temporarilyUnavailable(
                "RevenueCat transaction verification is temporarily unavailable: ${error.javaClass.simpleName}",
            )
        }
    }

    private fun configuredRevenueCat(): BuddyStudyProperties.RevenueCat {
        val config = properties.billing.revenueCat
        if (config.serverApiKey.isBlank() || config.projectId.isBlank()) {
            throw configurationError("RevenueCat transaction verification is not configured.")
        }
        return config
    }

    private fun verificationHash(subscriptionId: String, transaction: SubscriptionTransaction): String {
        val canonical = listOf(
            subscriptionId,
            transaction.id.orEmpty(),
            transaction.productStoreIdentifier.orEmpty(),
            transaction.purchasedAt?.toString().orEmpty(),
            transaction.effectiveExpirationDate?.toString().orEmpty(),
            transaction.expirationDate?.toString().orEmpty(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun isRetryable(error: Throwable): Boolean =
        error is java.util.concurrent.TimeoutException ||
            (error is WebClientResponseException && (error.statusCode.is5xxServerError || error.statusCode.value() == 429))

    private fun configurationError(message: String) = ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        ApiErrorCode.BILLING_CONFIGURATION_ERROR,
        message,
    )

    private fun temporarilyUnavailable(message: String): ApiException =
        ApiException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.BILLING_APPLICATION_FAILED, message)

    private fun invalidTransaction(message: String) = ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ApiErrorCode.BILLING_TRANSACTION_INVALID,
        message,
    )

    private fun Instant.coerceAtLeast(other: Instant): Instant = if (isBefore(other)) other else this

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SubscriptionSearchResponse(val items: List<RevenueCatSubscription>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RevenueCatSubscription(
        val id: String? = null,
        @param:JsonProperty("customer_id") val customerId: String? = null,
        @param:JsonProperty("original_customer_id") val originalCustomerId: String? = null,
        @param:JsonProperty("app_id") val appId: String? = null,
        val store: String? = null,
        val status: String? = null,
        val environment: String? = null,
        @param:JsonProperty("gives_access") val givesAccess: Boolean = false,
        @param:JsonProperty("pending_payment") val pendingPayment: Boolean = false,
        @param:JsonProperty("store_subscription_identifier") val storeSubscriptionIdentifier: String? = null,
        @param:JsonProperty("current_period_ends_at") val currentPeriodEndsAt: Long? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SubscriptionTransactionsResponse(val items: List<SubscriptionTransaction>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SubscriptionTransaction(
        val id: String? = null,
        @param:JsonProperty("product_store_identifier") val productStoreIdentifier: String? = null,
        @param:JsonProperty("purchased_at") val purchasedAt: Long? = null,
        @param:JsonProperty("expiration_date") val expirationDate: Long? = null,
        @param:JsonProperty("effective_expiration_date") val effectiveExpirationDate: Long? = null,
    )

    private companion object {
        val ACCESS_GRANTING_STATUSES = setOf("trialing", "active", "in_grace_period", "in_billing_retry")
        val PRODUCT_ID = Regex("^[A-Za-z0-9._-]{1,191}$")
    }
}
