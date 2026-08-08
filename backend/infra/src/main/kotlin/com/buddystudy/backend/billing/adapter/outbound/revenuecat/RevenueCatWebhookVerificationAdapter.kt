package com.buddystudy.backend.billing.adapter.outbound.revenuecat

import com.buddystudy.backend.billing.application.model.RevenueCatWebhookRequest
import com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatWebhookVerificationPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.billing.domain.BillingEnvironment
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class RevenueCatWebhookVerificationAdapter(
    private val properties: BuddyStudyProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : RevenueCatWebhookVerificationPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun verify(request: RevenueCatWebhookRequest): VerifiedRevenueCatEvent {
        val secret = properties.billing.revenueCat.webhookSigningSecret.trim()
        if (secret.isEmpty()) {
            throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.BILLING_CONFIGURATION_ERROR,
                "RevenueCat webhook verification is not configured.",
            )
        }
        if (request.rawBody.isEmpty() || request.rawBody.size > MAX_BODY_BYTES) {
            invalidWebhook("invalid_body_size", request.rawBody.size)
        }

        val signature = parseSignature(request.signature, request.rawBody.size)
        val now = clock.instant()
        val signatureAge = Duration.between(Instant.ofEpochSecond(signature.timestamp), now).abs()
        if (signatureAge > SIGNATURE_TOLERANCE) {
            invalidWebhook("stale_timestamp", request.rawBody.size, signatureAge.seconds)
        }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        mac.update(signature.timestamp.toString().toByteArray(StandardCharsets.UTF_8))
        mac.update('.'.code.toByte())
        val computed = mac.doFinal(request.rawBody).toHex()
        if (!MessageDigest.isEqual(computed.toByteArray(), signature.value.lowercase().toByteArray())) {
            invalidWebhook("hmac_mismatch", request.rawBody.size, signatureAge.seconds)
        }

        val envelope = try {
            objectMapper.readValue(request.rawBody, RevenueCatEnvelope::class.java)
        } catch (_: Exception) {
            invalidWebhook("malformed_payload", request.rawBody.size, signatureAge.seconds)
        }
        if (envelope.apiVersion != "1.0") {
            invalidWebhook("unsupported_api_version", request.rawBody.size, signatureAge.seconds)
        }
        val event = envelope.event
            ?: invalidWebhook("missing_event", request.rawBody.size, signatureAge.seconds)
        val expectedAppId = properties.billing.revenueCat.appId.trim()
        if (expectedAppId.isNotEmpty() && event.appId != expectedAppId) {
            invalidWebhook("app_id_mismatch", request.rawBody.size, signatureAge.seconds)
        }
        val eventId = event.id?.trim()?.takeIf { PROVIDER_ID.matches(it) }
            ?: invalidWebhook("invalid_event_id", request.rawBody.size, signatureAge.seconds)
        val eventType = event.type?.trim()?.uppercase()?.takeIf { EVENT_TYPE.matches(it) }
            ?: invalidWebhook("invalid_event_type", request.rawBody.size, signatureAge.seconds)
        val eventAt = event.eventTimestampMs?.toInstant()
            ?: invalidWebhook("invalid_event_timestamp", request.rawBody.size, signatureAge.seconds)
        val productId = if (eventType == "PRODUCT_CHANGE") {
            event.newProductId?.trim()?.takeIf(String::isNotEmpty)
                ?: event.productId?.trim()?.takeIf(String::isNotEmpty)
        } else {
            event.productId?.trim()?.takeIf(String::isNotEmpty)
        }

        return VerifiedRevenueCatEvent(
            eventId = eventId,
            eventType = eventType,
            appUserId = event.appUserId?.trim()?.takeIf(String::isNotEmpty),
            originalAppUserId = event.originalAppUserId?.trim()?.takeIf(String::isNotEmpty),
            aliases = event.aliases.orEmpty().map(String::trim).filter(String::isNotEmpty).take(20),
            store = event.store?.trim()?.uppercase(),
            productId = productId,
            transactionId = event.transactionId?.trim()?.takeIf(String::isNotEmpty),
            originalTransactionId = event.originalTransactionId?.trim()?.takeIf(String::isNotEmpty),
            environment = event.environment?.toBillingEnvironment(),
            priceMilliunits = event.priceInPurchasedCurrency?.toMilliunits(),
            currency = event.currency?.trim()?.uppercase()?.takeIf { CURRENCY.matches(it) },
            purchasedAt = event.purchasedAtMs?.toInstant(),
            expiresAt = event.expirationAtMs?.toInstant(),
            eventAt = eventAt,
            cancelReason = event.cancelReason?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
            expirationReason = event.expirationReason?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
            signedPayloadSha256 = MessageDigest.getInstance("SHA-256").digest(request.rawBody).toHex(),
        )
    }

    private fun parseSignature(value: String, bodySize: Int): Signature {
        val parts = value.split(',').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.toMap()
        val timestamp = parts["t"]?.toLongOrNull() ?: invalidWebhook("invalid_signature_timestamp", bodySize)
        val signature = parts["v1"]?.takeIf { HEX_64.matches(it) }
            ?: invalidWebhook("invalid_signature_value", bodySize)
        return Signature(timestamp, signature)
    }

    private fun String.toBillingEnvironment(): BillingEnvironment? = when (uppercase()) {
        "PRODUCTION" -> BillingEnvironment.PRODUCTION
        "SANDBOX" -> BillingEnvironment.SANDBOX
        else -> null
    }

    private fun Long.toInstant(): Instant? = runCatching { Instant.ofEpochMilli(this) }.getOrNull()

    private fun BigDecimal.toMilliunits(): Long? = runCatching {
        movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun invalidWebhook(reason: String, bodySize: Int, signatureAgeSeconds: Long? = null): Nothing {
        log.warn(
            "revenuecat_webhook_rejected reason={} bodyBytes={} signatureAgeSeconds={}",
            reason,
            bodySize,
            signatureAgeSeconds ?: -1,
        )
        throw ApiException(
            HttpStatus.UNAUTHORIZED,
            ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN,
            "RevenueCat webhook signature or payload is invalid.",
        )
    }

    private data class Signature(val timestamp: Long, val value: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RevenueCatEnvelope(
        @param:JsonProperty("api_version") val apiVersion: String? = null,
        val event: RevenueCatEventPayload? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RevenueCatEventPayload(
        val id: String? = null,
        val type: String? = null,
        @param:JsonProperty("app_id") val appId: String? = null,
        @param:JsonProperty("event_timestamp_ms") val eventTimestampMs: Long? = null,
        @param:JsonProperty("app_user_id") val appUserId: String? = null,
        @param:JsonProperty("original_app_user_id") val originalAppUserId: String? = null,
        val aliases: List<String>? = null,
        val store: String? = null,
        @param:JsonProperty("product_id") val productId: String? = null,
        @param:JsonProperty("new_product_id") val newProductId: String? = null,
        @param:JsonProperty("transaction_id") val transactionId: String? = null,
        @param:JsonProperty("original_transaction_id") val originalTransactionId: String? = null,
        val environment: String? = null,
        @param:JsonProperty("price_in_purchased_currency") val priceInPurchasedCurrency: BigDecimal? = null,
        val currency: String? = null,
        @param:JsonProperty("purchased_at_ms") val purchasedAtMs: Long? = null,
        @param:JsonProperty("expiration_at_ms") val expirationAtMs: Long? = null,
        @param:JsonProperty("cancel_reason") val cancelReason: String? = null,
        @param:JsonProperty("expiration_reason") val expirationReason: String? = null,
    )

    private companion object {
        const val MAX_BODY_BYTES = 256 * 1024
        val SIGNATURE_TOLERANCE: Duration = Duration.ofMinutes(5)
        val PROVIDER_ID = Regex("^[A-Za-z0-9._:-]{1,191}$")
        val EVENT_TYPE = Regex("^[A-Z0-9_]{1,64}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
        val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
    }
}
