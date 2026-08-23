package com.buddystudy.backend.billing.adapter.outbound.revenuecat

import com.buddystudy.backend.billing.application.model.RevenueCatCustomerSnapshot
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatCustomerInfoPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.externalapi.adapter.outbound.history.ExternalApiHistoryRecorder
import com.buddystudy.backend.externalapi.adapter.outbound.history.ExternalApiRequest
import com.buddystudy.backend.externalapi.adapter.outbound.history.ExternalApiResponse
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import com.buddystudy.billing.domain.SubscriptionRenewalStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.ChannelOption
import org.springframework.http.HttpStatus
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import kotlinx.coroutines.reactor.awaitSingle
import reactor.util.retry.Retry
import reactor.netty.http.client.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class RevenueCatCustomerInfoAdapter(
    private val properties: BuddyStudyProperties,
    webClientBuilder: WebClient.Builder,
    private val objectMapper: ObjectMapper,
    private val history: ExternalApiHistoryRecorder,
    private val clock: Clock = Clock.systemUTC(),
) : RevenueCatCustomerInfoPort {
    private val client = webClientBuilder.clientConnector(
        ReactorClientHttpConnector(
            HttpClient.create().option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                properties.billing.revenueCat.connectTimeoutMs.coerceIn(500, 30_000).toInt(),
            ),
        ),
    ).build()

    override suspend fun fetch(
        appAccountToken: UUID,
        originalTransactionId: String,
    ): RevenueCatCustomerSnapshot {
        val config = properties.billing.revenueCat
        val key = config.serverApiKey.trim()
        val projectId = config.projectId.trim()
        if (key.isEmpty() || projectId.isEmpty()) {
            throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.BILLING_CONFIGURATION_ERROR,
                "RevenueCat server reconciliation is not configured.",
            )
        }
        val transactionLookupUri = UriComponentsBuilder.fromUriString(config.apiBaseUrl.trimEnd('/'))
            .path("/projects/{projectId}/subscriptions")
            .queryParam("store_subscription_identifier", originalTransactionId)
            .queryParam("limit", 100)
            .buildAndExpand(projectId)
            .toUri()
        val customerSubscriptionsUri = UriComponentsBuilder.fromUriString(config.apiBaseUrl.trimEnd('/'))
            .path("/projects/{projectId}/customers/{customerId}/subscriptions")
            .queryParam("limit", 100)
            .buildAndExpand(projectId, appAccountToken.toString().lowercase())
            .toUri()
        val now = clock.instant()
        val customerSubscriptionIds = requestSubscriptions(customerSubscriptionsUri.toString(), key, config)
            .mapNotNull(CustomerSubscription::id)
            .toSet()
        val candidates = requestSubscriptions(transactionLookupUri.toString(), key, config).filter { subscription ->
            subscription.id in customerSubscriptionIds &&
            subscription.store == "app_store" &&
                (config.appId.isBlank() || subscription.appId == null || subscription.appId == config.appId)
        }
        val subscription = candidates.singleOrNull()
        if (subscription == null) {
            return RevenueCatCustomerSnapshot(
                accessStatus = SubscriptionAccessStatus.UNKNOWN,
                renewalStatus = SubscriptionRenewalStatus.UNKNOWN,
                expiresAt = null,
                fetchedAt = now,
            )
        }
        return RevenueCatCustomerSnapshot(
            accessStatus = subscription.accessStatus(),
            renewalStatus = subscription.renewalStatus(),
            expiresAt = subscription.currentPeriodEndsAt?.let(Instant::ofEpochMilli),
            fetchedAt = now,
        )
    }

    private fun isRetryable(error: Throwable): Boolean =
        error is java.util.concurrent.TimeoutException ||
            (error is WebClientResponseException && (error.statusCode.is5xxServerError || error.statusCode.value() == 429))

    private suspend fun requestSubscriptions(
        uri: String,
        key: String,
        config: BuddyStudyProperties.RevenueCat,
    ): List<CustomerSubscription> = history.record(
        ExternalApiRequest(
            provider = "revenuecat",
            operation = "list-subscriptions",
            method = "GET",
            url = uri,
            headers = mapOf("Authorization" to "Bearer $key"),
        ),
    ) {
        val entity = client.get().uri(uri).headers { it.setBearerAuth(key) }
            .retrieve()
            .toEntity(String::class.java)
            .timeout(Duration.ofMillis(config.readTimeoutMs.coerceIn(500, 30_000)))
            .retryWhen(
                Retry.backoff((config.maxRetries.coerceIn(1, 3) - 1).toLong(), Duration.ofMillis(200))
                    .filter(::isRetryable),
            )
            .awaitSingle()
        val payload = entity.body.orEmpty()
        ExternalApiResponse(
            value = payload,
            statusCode = entity.statusCode.value(),
            headers = entity.headers.toSingleValueMap(),
            body = payload,
        )
    }
        .let(objectMapper::readTree)
        .items()
        .map(::subscription)

    private data class CustomerSubscription(
        val id: String?,
        val appId: String?,
        val store: String?,
        val status: String?,
        val givesAccess: Boolean,
        val autoRenewalStatus: String?,
        val currentPeriodEndsAt: Long?,
    ) {
        fun accessStatus(): SubscriptionAccessStatus = when (status) {
            "trialing", "active" -> if (givesAccess) SubscriptionAccessStatus.ACTIVE else SubscriptionAccessStatus.PENDING
            "in_grace_period" -> if (givesAccess) SubscriptionAccessStatus.GRACE_PERIOD else SubscriptionAccessStatus.PENDING
            "expired" -> SubscriptionAccessStatus.EXPIRED
            "in_billing_retry" -> if (givesAccess) SubscriptionAccessStatus.ACTIVE else SubscriptionAccessStatus.PENDING
            "paused", "incomplete" -> SubscriptionAccessStatus.PENDING
            else -> SubscriptionAccessStatus.UNKNOWN
        }

        fun renewalStatus(): SubscriptionRenewalStatus = when {
            status == "in_billing_retry" -> SubscriptionRenewalStatus.BILLING_RETRY
            status == "expired" -> SubscriptionRenewalStatus.NOT_APPLICABLE
            autoRenewalStatus in setOf("will_renew", "will_change_product", "has_already_renewed") ->
                SubscriptionRenewalStatus.WILL_RENEW
            autoRenewalStatus in setOf("will_not_renew", "will_pause") -> SubscriptionRenewalStatus.CANCELED
            else -> SubscriptionRenewalStatus.UNKNOWN
        }
    }

    private fun subscription(node: JsonNode): CustomerSubscription = CustomerSubscription(
        id = node.textOrNull("id"),
        appId = node.textOrNull("app_id"),
        store = node.textOrNull("store"),
        status = node.textOrNull("status"),
        givesAccess = node.path("gives_access").asBoolean(false),
        autoRenewalStatus = node.textOrNull("auto_renewal_status"),
        currentPeriodEndsAt = node.longOrNull("current_period_ends_at"),
    )

    private fun JsonNode.items(): List<JsonNode> =
        path("items").takeIf(JsonNode::isArray)?.toList().orEmpty()

    private fun JsonNode.textOrNull(field: String): String? =
        path(field).takeUnless { it.isMissingNode || it.isNull }?.asText()?.takeIf(String::isNotBlank)

    private fun JsonNode.longOrNull(field: String): Long? =
        path(field).takeUnless { it.isMissingNode || it.isNull }?.asLong()
}
