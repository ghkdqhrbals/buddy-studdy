package com.buddystudy.backend.billing.adapter.outbound.revenuecat

import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatTransactionVerificationPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingProductType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
    private val objectMapper: ObjectMapper,
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

    override suspend fun verify(
        transactionId: String,
        appAccountToken: UUID,
        expectedProductId: String,
    ): VerifiedAppleTransaction {
        val normalizedTransactionId = transactionId.trim()
        val normalizedProductId = expectedProductId.trim()
        if (!PRODUCT_ID.matches(normalizedProductId)) {
            throw invalidTransaction("Prepared invoice product ID is invalid.")
        }
        val config = configuredRevenueCat()
        val searchUri = UriComponentsBuilder.fromUriString(config.apiBaseUrl.trimEnd('/'))
            .path("/projects/{projectId}/subscriptions")
            .queryParam("store_subscription_identifier", normalizedTransactionId)
            .queryParam("limit", 100)
            .buildAndExpand(config.projectId.trim())
            .toUri()
        val candidates = requestSubscriptions(searchUri.toString(), config.serverApiKey).filter { subscription ->
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
        val transaction = transactions(config, subscriptionId, descending = true)
            .singleOrNull { it.id == normalizedTransactionId }
            ?: throw temporarilyUnavailable("RevenueCat has not exposed the completed transaction yet.")
        val transactionProductId = transaction.productStoreIdentifier?.takeIf(PRODUCT_ID::matches)
            ?: throw invalidTransaction("RevenueCat transaction product ID is missing or invalid.")
        if (transactionProductId != normalizedProductId) {
            throw invalidTransaction("RevenueCat transaction product does not match the prepared invoice.")
        }
        val customerSubscription = customerSubscriptions(config, appAccountToken)
            .singleOrNull { candidate ->
                candidate.id == subscriptionId &&
                    candidate.store == "app_store" &&
                    (config.appId.isBlank() || candidate.appId == null || candidate.appId == config.appId)
            }
            ?: throw temporarilyUnavailable(
                "RevenueCat has not associated the completed transaction with the signed-in BuddyStudy account yet.",
            )
        validateAccess(customerSubscription)
        return verifiedTransaction(
            config = config,
            subscription = customerSubscription,
            transaction = transaction,
            appAccountToken = appAccountToken,
            expectedProductId = normalizedProductId,
        )
    }

    override suspend fun verifyLatest(
        appAccountToken: UUID,
        productId: String,
    ): VerifiedAppleTransaction {
        val config = configuredRevenueCat()
        val subscriptions = customerSubscriptions(config, appAccountToken).filter { subscription ->
            subscription.store == "app_store" &&
                (config.appId.isBlank() || subscription.appId == null || subscription.appId == config.appId) &&
                !subscription.pendingPayment && subscription.givesAccess &&
                subscription.status in ACCESS_GRANTING_STATUSES
        }

        val matches = subscriptions.mapNotNull { subscription ->
            val subscriptionId = subscription.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            transactions(config, subscriptionId, descending = true)
                .filter { it.productStoreIdentifier == productId && it.purchasedAt != null }
                .maxByOrNull { it.purchasedAt!! }
                ?.let { subscription to it }
        }
        val selected = matches.maxByOrNull { it.second.purchasedAt!! }
            ?: throw temporarilyUnavailable("RevenueCat has not exposed the completed purchase for this invoice yet.")
        return verifiedTransaction(config, selected.first, selected.second, appAccountToken, productId)
    }

    private suspend fun customerSubscriptions(
        config: BuddyStudyProperties.RevenueCat,
        appAccountToken: UUID,
    ): List<RevenueCatSubscription> {
        val uri = UriComponentsBuilder.fromUriString(config.apiBaseUrl.trimEnd('/'))
            .path("/projects/{projectId}/customers/{customerId}/subscriptions")
            .queryParam("limit", 100)
            .buildAndExpand(config.projectId.trim(), appAccountToken.toString().lowercase())
            .toUri()
        return requestSubscriptions(uri.toString(), config.serverApiKey)
    }

    private suspend fun verifiedTransaction(
        config: BuddyStudyProperties.RevenueCat,
        subscription: RevenueCatSubscription,
        transaction: SubscriptionTransaction,
        appAccountToken: UUID,
        expectedProductId: String? = null,
    ): VerifiedAppleTransaction {
        validateAccess(subscription)
        val subscriptionId = subscription.id?.takeIf(String::isNotBlank)
            ?: throw invalidTransaction("RevenueCat subscription ID is missing.")
        val originalTransactionId = transactions(config, subscriptionId, descending = false, limit = 1)
            .singleOrNull()?.id?.takeIf(String::isNotBlank)
            ?: throw invalidTransaction("RevenueCat original App Store transaction ID is missing.")
        val transactionId = transaction.id?.takeIf(String::isNotBlank)
            ?: throw invalidTransaction("RevenueCat transaction ID is missing.")
        val resolvedProductId = transaction.productStoreIdentifier?.takeIf(PRODUCT_ID::matches)
            ?: throw invalidTransaction("RevenueCat transaction product ID is missing or invalid.")
        if (expectedProductId != null && resolvedProductId != expectedProductId) {
            throw invalidTransaction("RevenueCat transaction product does not match the prepared invoice.")
        }
        val purchasedAt = transaction.purchasedAt?.let(Instant::ofEpochMilli)
            ?: throw invalidTransaction("RevenueCat transaction purchase timestamp is missing.")
        val expiresAt = (transaction.effectiveExpirationDate ?: transaction.expirationDate)?.let(Instant::ofEpochMilli)
            ?: subscription.currentPeriodEndsAt?.let(Instant::ofEpochMilli)
        val environment = when (subscription.environment) {
            "production" -> BillingEnvironment.PRODUCTION
            "sandbox" -> BillingEnvironment.SANDBOX
            else -> throw invalidTransaction("RevenueCat transaction environment is invalid.")
        }
        val verifiedAt = clock.instant().coerceAtLeast(purchasedAt)

        return VerifiedAppleTransaction(
            transactionId = transactionId,
            originalTransactionId = originalTransactionId,
            appTransactionId = null,
            webOrderLineItemId = null,
            appAccountToken = appAccountToken,
            productId = resolvedProductId,
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

    private suspend fun transactions(
        config: BuddyStudyProperties.RevenueCat,
        subscriptionId: String,
        descending: Boolean,
        limit: Int = 100,
    ): List<SubscriptionTransaction> {
        val uri = UriComponentsBuilder.fromUriString(config.apiBaseUrl.trimEnd('/'))
            .path("/projects/{projectId}/subscriptions/{subscriptionId}/transactions")
            .queryParam("limit", limit)
            .queryParam("sort", "purchased_at")
            .queryParam("direction", if (descending) "desc" else "asc")
            .buildAndExpand(config.projectId.trim(), subscriptionId)
            .toUri()
        return requestTransactions(uri.toString(), config.serverApiKey)
    }

    private fun validateAccess(subscription: RevenueCatSubscription) {
        if (subscription.pendingPayment || !subscription.givesAccess || subscription.status !in ACCESS_GRANTING_STATUSES) {
            throw invalidTransaction("RevenueCat has not verified an access-granting purchase for the transaction.")
        }
    }

    private suspend fun requestSubscriptions(uri: String, apiKey: String): List<RevenueCatSubscription> =
        requestJson(uri, apiKey).items().map { item ->
            RevenueCatSubscription(
                id = item.textOrNull("id"),
                customerId = item.textOrNull("customer_id"),
                originalCustomerId = item.textOrNull("original_customer_id"),
                appId = item.textOrNull("app_id"),
                store = item.textOrNull("store"),
                status = item.textOrNull("status"),
                environment = item.textOrNull("environment"),
                givesAccess = item.path("gives_access").asBoolean(false),
                pendingPayment = item.path("pending_payment").asBoolean(false),
                storeSubscriptionIdentifier = item.textOrNull("store_subscription_identifier"),
                currentPeriodEndsAt = item.longOrNull("current_period_ends_at"),
            )
        }

    private suspend fun requestTransactions(uri: String, apiKey: String): List<SubscriptionTransaction> =
        requestJson(uri, apiKey).items().map { item ->
            SubscriptionTransaction(
                id = item.textOrNull("id"),
                productStoreIdentifier = item.textOrNull("product_store_identifier"),
                purchasedAt = item.longOrNull("purchased_at"),
                expirationDate = item.longOrNull("expiration_date"),
                effectiveExpirationDate = item.longOrNull("effective_expiration_date"),
            )
        }

    private suspend fun requestJson(uri: String, apiKey: String): JsonNode {
        val config = properties.billing.revenueCat
        return try {
            client.get().uri(uri).headers { it.setBearerAuth(apiKey.trim()) }
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofMillis(config.readTimeoutMs.coerceIn(500, 30_000)))
                .retryWhen(
                    Retry.backoff((config.maxRetries.coerceIn(1, 3) - 1).toLong(), Duration.ofMillis(200))
                        .filter(::isRetryable),
                )
                .awaitSingle()
                .let(objectMapper::readTree)
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

    private fun JsonNode.items(): List<JsonNode> =
        path("items").takeIf(JsonNode::isArray)?.toList().orEmpty()

    private fun JsonNode.textOrNull(field: String): String? =
        path(field).takeUnless { it.isMissingNode || it.isNull }?.asText()?.takeIf(String::isNotBlank)

    private fun JsonNode.longOrNull(field: String): Long? =
        path(field).takeUnless { it.isMissingNode || it.isNull }?.asLong()

    private data class RevenueCatSubscription(
        val id: String?,
        val customerId: String?,
        val originalCustomerId: String?,
        val appId: String?,
        val store: String?,
        val status: String?,
        val environment: String?,
        val givesAccess: Boolean,
        val pendingPayment: Boolean,
        val storeSubscriptionIdentifier: String?,
        val currentPeriodEndsAt: Long?,
    )

    private data class SubscriptionTransaction(
        val id: String?,
        val productStoreIdentifier: String?,
        val purchasedAt: Long?,
        val expirationDate: Long?,
        val effectiveExpirationDate: Long?,
    )

    private companion object {
        val ACCESS_GRANTING_STATUSES = setOf("trialing", "active", "in_grace_period", "in_billing_retry")
        val PRODUCT_ID = Regex("^[A-Za-z0-9._-]{1,191}$")
    }
}
