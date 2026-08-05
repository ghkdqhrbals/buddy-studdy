package com.buddystudy.backend.billing.adapter.outbound.revenuecat

import com.buddystudy.backend.billing.application.model.RevenueCatCustomerSnapshot
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatCustomerInfoPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import com.buddystudy.billing.domain.SubscriptionRenewalStatus
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.netty.channel.ChannelOption
import org.springframework.http.HttpStatus
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
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
        val uri = "${config.apiBaseUrl.trimEnd('/')}/projects/$projectId/customers/" +
            "${appAccountToken.toString().lowercase()}/subscriptions?limit=100"
        val response = client.get().uri(uri).headers { it.setBearerAuth(key) }
            .retrieve()
            .bodyToMono(CustomerSubscriptionsResponse::class.java)
            .timeout(Duration.ofMillis(config.readTimeoutMs.coerceIn(500, 30_000)))
            .retryWhen(
                Retry.backoff((config.maxRetries.coerceIn(1, 3) - 1).toLong(), Duration.ofMillis(200))
                    .filter(::isRetryable),
            )
            .awaitSingle()
        val now = clock.instant()
        val subscription = response.items.orEmpty().firstOrNull {
            it.storeSubscriptionIdentifier == originalTransactionId
        }
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CustomerSubscriptionsResponse(val items: List<CustomerSubscription>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CustomerSubscription(
        val status: String? = null,
        @param:com.fasterxml.jackson.annotation.JsonProperty("gives_access") val givesAccess: Boolean = false,
        @param:com.fasterxml.jackson.annotation.JsonProperty("auto_renewal_status") val autoRenewalStatus: String? = null,
        @param:com.fasterxml.jackson.annotation.JsonProperty("current_period_ends_at") val currentPeriodEndsAt: Long? = null,
        @param:com.fasterxml.jackson.annotation.JsonProperty("store_subscription_identifier")
        val storeSubscriptionIdentifier: String? = null,
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
}
