package com.buddystudy.backend.billing.adapter.outbound.revenuecat

import com.buddystudy.backend.billing.application.model.RevenueCatWebhookRequest
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.billing.domain.BillingEnvironment
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RevenueCatWebhookVerificationAdapterTest {
    private val now = Instant.parse("2026-08-04T00:00:00Z")
    private val secret = "revenuecat-webhook-test-secret"
    private val body =
        """{"api_version":"1.0","event":{"id":"rc-event-1","type":"INITIAL_PURCHASE","app_id":"app123","event_timestamp_ms":1785801600000,"app_user_id":"3f0c5f50-6521-4ba0-a990-73500e915f57","original_app_user_id":"3f0c5f50-6521-4ba0-a990-73500e915f57","aliases":[],"store":"APP_STORE","product_id":"io.github.ghkdqhrbals.StudyMate.tier2.monthly","transaction_id":"200000000000001","original_transaction_id":"200000000000000","environment":"SANDBOX","price_in_purchased_currency":7900,"currency":"KRW","purchased_at_ms":1785801590000,"expiration_at_ms":1788393600000}}"""
            .toByteArray()

    @Test
    fun `verifies exact raw body HMAC and maps a RevenueCat purchase`() = runBlocking<Unit> {
        val adapter = adapter()
        val timestamp = now.epochSecond

        val event = adapter.verify(RevenueCatWebhookRequest(body, signature(timestamp, body)))

        assertThat(event.eventId).isEqualTo("rc-event-1")
        assertThat(event.environment).isEqualTo(BillingEnvironment.SANDBOX)
        assertThat(event.priceMilliunits).isEqualTo(7_900_000)
        assertThat(event.transactionId).isEqualTo("200000000000001")
        assertThat(event.originalAppUserId).isEqualTo("3f0c5f50-6521-4ba0-a990-73500e915f57")
    }

    @Test
    fun `maps RevenueCat expiration reason independently from cancellation reason`() = runBlocking<Unit> {
        val expirationBody =
            """{"api_version":"1.0","event":{"id":"rc-expiration-1","type":"EXPIRATION","app_id":"app123","event_timestamp_ms":1785801600000,"app_user_id":"3f0c5f50-6521-4ba0-a990-73500e915f57","aliases":[],"store":"APP_STORE","transaction_id":"200000000000001","original_transaction_id":"200000000000000","environment":"SANDBOX","expiration_reason":"BILLING_ERROR"}}"""
                .toByteArray()

        val event = adapter().verify(
            RevenueCatWebhookRequest(expirationBody, signature(now.epochSecond, expirationBody)),
        )

        assertThat(event.cancelReason).isNull()
        assertThat(event.expirationReason).isEqualTo("BILLING_ERROR")
    }

    @Test
    fun `rejects a signature produced for different body bytes`() {
        val adapter = adapter()
        val signature = signature(now.epochSecond, "{}".toByteArray())

        assertThrows(ApiRuntimeException::class.java) {
            runBlocking { adapter.verify(RevenueCatWebhookRequest(body, signature)) }
        }
    }

    private fun adapter(): RevenueCatWebhookVerificationAdapter {
        val properties = BuddyStudyProperties()
        properties.billing.revenueCat.webhookSigningSecret = secret
        properties.billing.revenueCat.appId = "app123"
        return RevenueCatWebhookVerificationAdapter(
            properties,
            jacksonObjectMapper(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    private fun signature(timestamp: Long, rawBody: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        mac.update(timestamp.toString().toByteArray(StandardCharsets.UTF_8))
        mac.update('.'.code.toByte())
        val value = mac.doFinal(rawBody).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$value"
    }
}
