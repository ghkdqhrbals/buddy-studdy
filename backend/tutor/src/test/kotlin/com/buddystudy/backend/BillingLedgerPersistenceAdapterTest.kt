package com.buddystudy.backend

import com.buddystudy.backend.billing.application.model.ApplyAppleNotificationCommand
import com.buddystudy.backend.billing.application.model.BillingTierProduct
import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.model.RevenueCatCustomerSnapshot
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.SubscriptionReconciliationClaim
import com.buddystudy.backend.billing.application.model.VerifiedAppleNotification
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.admin.management.application.port.outbound.AdminManagementPort
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.quota.MonthlyQuestionQuotaPolicy
import com.buddystudy.billing.domain.BillingActionStatus
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.InvoiceEventType
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentStatus
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import com.buddystudy.billing.domain.SubscriptionRenewalStatus
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.analytics.datasource.database-name=",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ],
)
class BillingLedgerPersistenceAdapterTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var ledger: BillingLedgerPort
    @Autowired lateinit var quota: QuestionMembershipPort
    @Autowired lateinit var adminManagement: AdminManagementPort
    @Autowired lateinit var database: DatabaseClient
    private val createdUserIds = mutableListOf<Long>()
    private val createdRevenueCatEventIds = mutableListOf<String>()

    @AfterEach
    fun cleanUpBillingFixtures(): Unit = runBlocking {
        createdRevenueCatEventIds.forEach { eventId ->
            database.sql("delete from subscription_events where provider = 'REVENUECAT' and provider_event_id = :eventId")
                .bind("eventId", eventId).fetch().rowsUpdated().awaitSingle()
            database.sql("delete from billing_revenuecat_event_inbox where event_id = :eventId")
                .bind("eventId", eventId).fetch().rowsUpdated().awaitSingle()
        }
        createdRevenueCatEventIds.clear()
        createdUserIds.asReversed().forEach { userId ->
            execute("delete from user_quota_history where user_id = $userId")
            execute("delete from quota_reservations where user_id = $userId")
            execute("delete from user_quota where user_id = $userId")
            execute("delete from user_entitlement_projection where user_id = $userId")
            execute("delete from subscription_events where user_id = $userId")
            execute("delete from subscriptions where user_id = $userId")
            execute("delete from billing_actions where user_id = $userId")
            execute("delete from billing_fulfillment_outbox where invoice_id in (select id from invoices where user_id = $userId)")
            execute("delete from payments_history where invoice_id in (select id from invoices where user_id = $userId)")
            execute("delete from invoice_events where invoice_id in (select id from invoices where user_id = $userId)")
            execute("delete from payments where user_id = $userId")
            execute("delete from user_memberships where user_id = $userId")
            execute("delete from invoices where user_id = $userId and original_invoice_id is not null")
            execute("delete from invoices where user_id = $userId")
            execute("delete from billing_accounts where user_id = $userId")
            execute("delete from apple_billing_accounts where user_id = $userId")
            execute("delete from users where id = $userId")
        }
        createdUserIds.clear()
    }

    @Test
    fun `annual mappings remain historical-only and cannot enter catalog or checkout lookup`(): Unit = runBlocking {
        val annualProductId = "io.github.ghkdqhrbals.StudyMate.tier2.yearly"

        assertThat(ledger.enabledTierProducts())
            .isNotEmpty
            .allMatch { it.billingPeriod == "P1M" }
        assertThat(ledger.enabledTierProduct(annualProductId)).isNull()
        val historicalProduct = ledger.tierProduct(annualProductId)
        assertThat(historicalProduct?.billingPeriod).isEqualTo("P1Y")
        assertThat(historicalProduct?.productId).isEqualTo(annualProductId)
    }

    @Test
    fun `failed RevenueCat receipt remains retryable and duplicate processed delivery is ignored`(): Unit = runBlocking {
        val eventId = "rc-${UUID.randomUUID()}"
        createdRevenueCatEventIds += eventId
        val event = VerifiedRevenueCatEvent(
            eventId = eventId,
            eventType = "TEST",
            appUserId = null,
            originalAppUserId = null,
            aliases = emptyList(),
            store = "APP_STORE",
            productId = null,
            transactionId = null,
            originalTransactionId = null,
            environment = BillingEnvironment.SANDBOX,
            priceMilliunits = null,
            currency = null,
            purchasedAt = null,
            expiresAt = null,
            eventAt = Instant.parse("2032-08-04T00:00:00Z"),
            cancelReason = null,
            expirationReason = null,
            signedPayloadSha256 = "c".repeat(64),
        )

        assertThat(ledger.recordRevenueCatEvent(event, event.eventAt)).isTrue()
        ledger.markRevenueCatEventFailed(eventId, "temporary failure", event.eventAt.plusSeconds(1))
        assertThat(ledger.recordRevenueCatEvent(event, event.eventAt.plusSeconds(2))).isTrue()
        assertThat(ledger.applyRevenueCatEvent(event, event.eventAt.plusSeconds(3))).isTrue()
        assertThat(ledger.recordRevenueCatEvent(event, event.eventAt.plusSeconds(4))).isFalse()
        assertThat(
            database.sql("select processing_status from billing_revenuecat_event_inbox where event_id = :eventId")
                .bind("eventId", eventId)
                .map { row -> row.get("processing_status", String::class.java)!! }
                .one().awaitSingle(),
        ).isEqualTo("IGNORED")
    }

    @Test
    fun `RevenueCat processing exhausts after three failures and is visible to administrators`(): Unit = runBlocking {
        val eventId = "rc-exhausted-${UUID.randomUUID()}"
        createdRevenueCatEventIds += eventId
        val occurredAt = Instant.parse("2032-08-04T00:00:00Z")
        val event = VerifiedRevenueCatEvent(
            eventId = eventId,
            eventType = "TEST_FAILURE",
            appUserId = null,
            originalAppUserId = null,
            aliases = emptyList(),
            store = "APP_STORE",
            productId = "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
            transactionId = "transaction-${UUID.randomUUID()}",
            originalTransactionId = "original-${UUID.randomUUID()}",
            environment = BillingEnvironment.SANDBOX,
            priceMilliunits = null,
            currency = null,
            purchasedAt = null,
            expiresAt = null,
            eventAt = occurredAt,
            cancelReason = null,
            expirationReason = null,
            signedPayloadSha256 = "f".repeat(64),
        )

        assertThat(ledger.recordRevenueCatEvent(event, occurredAt)).isTrue()

        val first = ledger.markRevenueCatEventFailed(eventId, "provider timeout 1", occurredAt.plusSeconds(1))
        val second = ledger.markRevenueCatEventFailed(eventId, "provider timeout 2", occurredAt.plusSeconds(2))
        val third = ledger.markRevenueCatEventFailed(eventId, "provider timeout 3", occurredAt.plusSeconds(3))
        val repeated = ledger.markRevenueCatEventFailed(eventId, "late duplicate", occurredAt.plusSeconds(4))

        assertThat(first.status).isEqualTo("RETRYING")
        assertThat(first.attemptCount).isEqualTo(1)
        assertThat(second.status).isEqualTo("RETRYING")
        assertThat(second.attemptCount).isEqualTo(2)
        assertThat(third.status).isEqualTo("EXHAUSTED")
        assertThat(third.attemptCount).isEqualTo(3)
        assertThat(third.terminalTransition).isTrue()
        assertThat(repeated.attemptCount).isEqualTo(3)
        assertThat(repeated.terminalTransition).isFalse()
        assertThat(ledger.claimDueRevenueCatEvents(occurredAt.plusSeconds(3600), 100)).isEmpty()

        val failures = ledger.adminProcessingFailures(
            source = "REVENUECAT_EVENT",
            status = "EXHAUSTED",
            limit = 20,
            offset = 0,
        )
        assertThat(failures.failures).hasSize(1)
        val failure = failures.failures.single()
        assertThat(failure.eventId).isEqualTo(eventId)
        assertThat(failure.attemptCount).isEqualTo(3)
        assertThat(failure.maxAttempts).isEqualTo(3)
        assertThat(failure.lastError).isEqualTo("provider timeout 3")
    }

    @Test
    fun `abandoned RevenueCat processing claim is reclaimed after its lease expires`(): Unit = runBlocking {
        val eventId = "rc-lease-${UUID.randomUUID()}"
        createdRevenueCatEventIds += eventId
        val occurredAt = Instant.parse("2032-08-04T00:00:00Z")
        val event = VerifiedRevenueCatEvent(
            eventId = eventId,
            eventType = "TEST",
            appUserId = null,
            originalAppUserId = null,
            aliases = emptyList(),
            store = "APP_STORE",
            productId = null,
            transactionId = null,
            originalTransactionId = null,
            environment = BillingEnvironment.SANDBOX,
            priceMilliunits = null,
            currency = null,
            purchasedAt = null,
            expiresAt = null,
            eventAt = occurredAt,
            cancelReason = null,
            expirationReason = null,
            signedPayloadSha256 = "e".repeat(64),
        )

        assertThat(ledger.recordRevenueCatEvent(event, occurredAt)).isTrue()
        assertThat(ledger.claimDueRevenueCatEvents(occurredAt.plusSeconds(1), 100).map { it.eventId })
            .containsExactly(eventId)
        assertThat(ledger.claimDueRevenueCatEvents(occurredAt.plusSeconds(299), 100)).isEmpty()

        // No completion or failure update simulates process death after the claim transaction commits.
        assertThat(ledger.claimDueRevenueCatEvents(occurredAt.plusSeconds(302), 100).map { it.eventId })
            .containsExactly(eventId)
    }

    @Test
    fun `lifecycle event received before its purchase ledger remains retryable`(): Unit = runBlocking {
        val fixture = fixture("lifecycle-before-purchase")
        val transaction = fixture.transaction()
        val event = fixture.revenueCatLifecycleEvent(
            eventId = "rc-early-cancel-${fixture.suffix}",
            eventType = "CANCELLATION",
            transaction = transaction,
            eventAt = fixture.now.plusSeconds(1),
            cancelReason = "UNSUBSCRIBE",
        )
        createdRevenueCatEventIds += event.eventId
        assertThat(ledger.recordRevenueCatEvent(event, fixture.now.plusSeconds(1))).isTrue()

        assertThatThrownBy {
            runBlocking { ledger.applyRevenueCatEvent(event, fixture.now.plusSeconds(2)) }
        }.isInstanceOf(ApiException::class.java)

        val processingStatus = database.sql(
            "select processing_status from subscription_events where provider_event_id = :eventId",
        ).bind("eventId", event.eventId)
            .map { row, _ -> row.get("processing_status", String::class.java)!! }
            .one().awaitSingle()
        assertThat(processingStatus).isEqualTo("PENDING")
    }

    @Test
    fun `permanent validation failure idempotently fails only the prepared invoice`(): Unit = runBlocking {
        val fixture = fixture("validation-failure")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )

        val failed = ledger.failPendingInvoiceValidation(
            userId = fixture.userId,
            invoiceNumber = checkout.invoiceNumber,
            source = BillingEventSource.CLIENT,
            reason = "BILLING_TRANSACTION_INVALID: signature verification failed",
            now = fixture.now.plusSeconds(1),
        )
        val repeated = ledger.failPendingInvoiceValidation(
            userId = fixture.userId,
            invoiceNumber = checkout.invoiceNumber,
            source = BillingEventSource.CLIENT,
            reason = "BILLING_TRANSACTION_INVALID: duplicate retry",
            now = fixture.now.plusSeconds(2),
        )

        assertThat(failed.status).isEqualTo(InvoiceStatus.FAILED)
        assertThat(failed.latestEventType).isEqualTo(InvoiceEventType.PAYMENT_VALIDATION_FAILED)
        assertThat(repeated.status).isEqualTo(InvoiceStatus.FAILED)
        assertThat(eventTypes(checkout.id).count { it == InvoiceEventType.PAYMENT_VALIDATION_FAILED.name }).isEqualTo(1)
        assertThat(longValue("select count(*) from payments where invoice_id = ${checkout.id}")).isZero()
    }

    @Test
    fun `late validation failure cannot regress an invoice with verified payment`(): Unit = runBlocking {
        val fixture = fixture("late-validation-failure")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val verified = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                userId = fixture.userId,
                tierProduct = fixture.product,
                transaction = transaction,
                invoiceNumber = checkout.invoiceNumber,
                source = BillingEventSource.CLIENT,
                eventId = "apple-transaction:${transaction.transactionId}",
                occurredAt = fixture.now.plusSeconds(1),
            ),
        )

        val unchanged = ledger.failPendingInvoiceValidation(
            userId = fixture.userId,
            invoiceNumber = checkout.invoiceNumber,
            source = BillingEventSource.CLIENT,
            reason = "late duplicate client request",
            now = fixture.now.plusSeconds(2),
        )

        assertThat(verified.status).isEqualTo(InvoiceStatus.COMPLETED)
        assertThat(unchanged.status).isEqualTo(InvoiceStatus.COMPLETED)
        assertThat(unchanged.transactionId).isEqualTo(transaction.transactionId)
        assertThat(eventTypes(checkout.id)).doesNotContain(InvoiceEventType.PAYMENT_VALIDATION_FAILED.name)
    }

    @Test
    fun `failed or abandoned Apple notification can be claimed again`(): Unit = runBlocking {
        val fixture = fixture("apple-notification-lease")
        val notification = VerifiedAppleNotification(
            notificationUUID = "apple-lease-${fixture.suffix}",
            notificationType = "DID_CHANGE_RENEWAL_STATUS",
            subtype = "AUTO_RENEW_DISABLED",
            environment = BillingEnvironment.SANDBOX,
            signedAt = fixture.now,
            signedPayloadSha256 = "a".repeat(64),
            transaction = fixture.transaction(),
        )

        assertThat(ledger.recordAppleNotification(notification, fixture.now)).isTrue()
        assertThat(ledger.recordAppleNotification(notification, fixture.now.plusSeconds(1))).isFalse()

        ledger.markAppleNotificationFailed(notification.notificationUUID, "simulated failure", fixture.now.plusSeconds(2))
        assertThat(ledger.recordAppleNotification(notification, fixture.now.plusSeconds(3))).isTrue()
        assertThat(ledger.recordAppleNotification(notification, fixture.now.plusSeconds(302))).isFalse()
        assertThat(ledger.recordAppleNotification(notification, fixture.now.plusSeconds(304))).isTrue()
    }

    @Test
    fun `late RevenueCat lifecycle event cannot overwrite a newer subscription state`(): Unit = runBlocking {
        val fixture = fixture("revenuecat-order")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val recorded = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                userId = fixture.userId,
                tierProduct = fixture.product,
                transaction = transaction,
                invoiceNumber = checkout.invoiceNumber,
                source = BillingEventSource.CLIENT,
                eventId = "apple-transaction:${transaction.transactionId}",
                occurredAt = fixture.now.plusSeconds(1),
            ),
        )
        ledger.fulfill(recorded.id, fixture.now.plusSeconds(2))

        val cancellation = fixture.revenueCatLifecycleEvent(
            eventId = "rc-cancel-${fixture.suffix}",
            eventType = "CANCELLATION",
            transaction = transaction,
            eventAt = fixture.now.plusSeconds(100),
            cancelReason = "UNSUBSCRIBE",
        )
        val staleUncancellation = fixture.revenueCatLifecycleEvent(
            eventId = "rc-uncancel-${fixture.suffix}",
            eventType = "UNCANCELLATION",
            transaction = transaction,
            eventAt = fixture.now.plusSeconds(50),
        )
        createdRevenueCatEventIds += cancellation.eventId
        createdRevenueCatEventIds += staleUncancellation.eventId

        assertThat(ledger.recordRevenueCatEvent(cancellation, fixture.now.plusSeconds(101))).isTrue()
        assertThat(ledger.applyRevenueCatEvent(cancellation, fixture.now.plusSeconds(102))).isTrue()
        assertThat(ledger.recordRevenueCatEvent(staleUncancellation, fixture.now.plusSeconds(103))).isTrue()
        assertThat(ledger.applyRevenueCatEvent(staleUncancellation, fixture.now.plusSeconds(104))).isTrue()

        val subscription = database.sql(
            "select renewal_status, last_provider_event_at from subscriptions where original_transaction_id = :id",
        ).bind("id", transaction.originalTransactionId).map { row, _ ->
            row.get("renewal_status", String::class.java)!! to
                row.get("last_provider_event_at", java.time.LocalDateTime::class.java)!!
        }.one().awaitSingle()
        assertThat(subscription.first).isEqualTo("CANCELED")
        assertThat(subscription.second).isEqualTo(
            java.time.LocalDateTime.ofInstant(cancellation.eventAt, java.time.ZoneOffset.UTC),
        )
        assertThat(eventTypes(recorded.id)).doesNotContain("CANCELLATION_REVERSED")
    }

    @Test
    fun `cancellation clears a scheduled downgrade and stale product change cannot restore it`(): Unit = runBlocking {
        val fixture = fixture("cancel-scheduled-downgrade")
        val tier3 = requireNotNull(
            ledger.enabledTierProduct("io.github.ghkdqhrbals.StudyMate.tier3.monthly"),
        )
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            tier3,
            "tier3-${fixture.suffix}",
            fixture.now,
        )
        val transaction = fixture.transaction().copy(
            productId = tier3.productId,
            productType = tier3.productType,
            priceMilliunits = 17_900_000,
        )
        val invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                tier3,
                transaction,
                checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "tier3-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(invoice.id, fixture.now.plusSeconds(1))
        val invoiceCountBeforeChange = longValue("select count(*) from invoices where user_id = ${fixture.userId}")
        val paymentCountBeforeChange = longValue("select count(*) from payments where user_id = ${fixture.userId}")

        val productChangeAt = fixture.now.plusSeconds(90)
        val productChange = fixture.revenueCatLifecycleEvent(
            eventId = "rc-downgrade-${fixture.suffix}",
            eventType = "PRODUCT_CHANGE",
            transaction = transaction.copy(productId = fixture.product.productId),
            eventAt = productChangeAt,
            productOverride = fixture.product,
        )
        createdRevenueCatEventIds += productChange.eventId
        assertThat(ledger.recordRevenueCatEvent(productChange, productChangeAt)).isTrue()
        assertThat(ledger.applyRevenueCatEvent(productChange, productChangeAt)).isTrue()
        assertThat(ledger.entitlementForUser(fixture.userId)?.pendingProductId)
            .isEqualTo(fixture.product.productId)
        assertThat(longValue("select count(*) from invoices where user_id = ${fixture.userId}"))
            .isEqualTo(invoiceCountBeforeChange)
        assertThat(longValue("select count(*) from payments where user_id = ${fixture.userId}"))
            .isEqualTo(paymentCountBeforeChange)

        val cancellationAt = fixture.now.plusSeconds(100)
        val cancellation = fixture.revenueCatLifecycleEvent(
            eventId = "rc-cancel-downgrade-${fixture.suffix}",
            eventType = "CANCELLATION",
            transaction = transaction,
            eventAt = cancellationAt,
            cancelReason = "UNSUBSCRIBE",
            productOverride = tier3,
        )
        createdRevenueCatEventIds += cancellation.eventId
        assertThat(ledger.recordRevenueCatEvent(cancellation, cancellationAt)).isTrue()
        assertThat(ledger.applyRevenueCatEvent(cancellation, cancellationAt)).isTrue()

        val staleProductChange = productChange.copy(
            eventId = "rc-stale-downgrade-${fixture.suffix}",
            eventAt = fixture.now.plusSeconds(95),
            signedPayloadSha256 = "stale-${fixture.suffix}".padEnd(64, 'f').take(64),
        )
        createdRevenueCatEventIds += staleProductChange.eventId
        assertThat(ledger.recordRevenueCatEvent(staleProductChange, cancellationAt.plusSeconds(1))).isTrue()
        assertThat(ledger.applyRevenueCatEvent(staleProductChange, cancellationAt.plusSeconds(2))).isTrue()

        val entitlement = requireNotNull(ledger.entitlementForUser(fixture.userId))
        assertThat(entitlement.renewalStatus).isEqualTo(SubscriptionRenewalStatus.CANCELED)
        assertThat(entitlement.willRenew).isFalse()
        assertThat(entitlement.pendingProductId).isNull()
        assertThat(longValue("select count(*) from invoices where user_id = ${fixture.userId}"))
            .isEqualTo(invoiceCountBeforeChange)
        assertThat(longValue("select count(*) from payments where user_id = ${fixture.userId}"))
            .isEqualTo(paymentCountBeforeChange)
    }

    @Test
    fun `cancelled reconciliation snapshot clears a stale pending product`(): Unit = runBlocking {
        val fixture = fixture("reconcile-cancelled-pending")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                transaction,
                checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "initial-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(invoice.id, fixture.now.plusSeconds(1))
        forceStaleSubscriptionProjection(
            fixture,
            transaction,
            pendingProductId = fixture.product.productId,
            accessStatus = "ACTIVE",
            renewalStatus = "WILL_RENEW",
            lastProviderEventAt = fixture.now.plusSeconds(10),
        )

        val subscriptionId = longValue(
            "select id from subscriptions where original_transaction_id = '${transaction.originalTransactionId}'",
        )
        val fetchedAt = fixture.now.plusSeconds(100)
        ledger.applySubscriptionSnapshot(
            SubscriptionReconciliationClaim(
                subscriptionId,
                fixture.userId,
                transaction.originalTransactionId,
                fixture.appAccountToken,
                1,
            ),
            RevenueCatCustomerSnapshot(
                SubscriptionAccessStatus.ACTIVE,
                SubscriptionRenewalStatus.CANCELED,
                transaction.expiresAt,
                fetchedAt,
            ),
            fetchedAt,
        )

        val entitlement = requireNotNull(ledger.entitlementForUser(fixture.userId))
        assertThat(entitlement.renewalStatus).isEqualTo(SubscriptionRenewalStatus.CANCELED)
        assertThat(entitlement.pendingProductId).isNull()
    }

    @Test
    fun `inconclusive reconciliation cannot revoke a paid entitlement or pending change`(): Unit = runBlocking {
        val fixture = fixture("reconcile-inconclusive")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                transaction,
                checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "initial-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(invoice.id, fixture.now.plusSeconds(1))
        val pendingProductId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly"
        forceStaleSubscriptionProjection(
            fixture,
            transaction,
            pendingProductId = pendingProductId,
            accessStatus = "ACTIVE",
            renewalStatus = "WILL_RENEW",
            lastProviderEventAt = fixture.now.plusSeconds(10),
        )

        val subscriptionId = longValue(
            "select id from subscriptions where original_transaction_id = '${transaction.originalTransactionId}'",
        )
        val fetchedAt = fixture.now.plusSeconds(100)
        ledger.applySubscriptionSnapshot(
            SubscriptionReconciliationClaim(
                subscriptionId,
                fixture.userId,
                transaction.originalTransactionId,
                fixture.appAccountToken,
                1,
            ),
            RevenueCatCustomerSnapshot(
                SubscriptionAccessStatus.UNKNOWN,
                SubscriptionRenewalStatus.UNKNOWN,
                null,
                fetchedAt,
            ),
            fetchedAt,
        )

        val entitlement = requireNotNull(ledger.entitlementForUser(fixture.userId))
        assertThat(entitlement.tierCode).isEqualTo("TIER2")
        assertThat(entitlement.accessStatus).isEqualTo(SubscriptionAccessStatus.ACTIVE)
        assertThat(entitlement.renewalStatus).isEqualTo(SubscriptionRenewalStatus.WILL_RENEW)
        assertThat(entitlement.pendingProductId).isEqualTo(pendingProductId)
        assertThat(
            database.sql("select access_status from subscriptions where id = :id")
                .bind("id", subscriptionId)
                .map { row -> row.get("access_status", String::class.java)!! }
                .one().awaitSingle(),
        ).isEqualTo("ACTIVE")
        assertThat(
            database.sql(
                "select processing_status from subscription_events where provider_event_id = :eventId",
            ).bind("eventId", "reconcile:$subscriptionId:${fetchedAt.toEpochMilli()}")
                .map { row -> row.get("processing_status", String::class.java)!! }
                .one().awaitSingle(),
        ).isEqualTo("IGNORED")
    }

    @Test
    fun `stale expired reconciliation cannot override current verified payment`(): Unit = runBlocking {
        val fixture = fixture("reconcile-stale-expired")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                transaction,
                checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "initial-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(invoice.id, fixture.now.plusSeconds(1))
        val subscriptionId = longValue(
            "select id from subscriptions where original_transaction_id = '${transaction.originalTransactionId}'",
        )
        val fetchedAt = fixture.now.plusSeconds(30)

        ledger.applySubscriptionSnapshot(
            SubscriptionReconciliationClaim(
                subscriptionId,
                fixture.userId,
                transaction.originalTransactionId,
                fixture.appAccountToken,
                1,
            ),
            RevenueCatCustomerSnapshot(
                SubscriptionAccessStatus.EXPIRED,
                SubscriptionRenewalStatus.NOT_APPLICABLE,
                fixture.now.minusSeconds(86_400),
                fetchedAt,
            ),
            fetchedAt,
        )

        val entitlement = requireNotNull(ledger.entitlementForUser(fixture.userId))
        assertThat(entitlement.tierCode).isEqualTo("TIER2")
        assertThat(entitlement.accessStatus).isEqualTo(SubscriptionAccessStatus.ACTIVE)
        assertThat(requireNotNull(quota.quotaStatusForUser(fixture.userId, fetchedAt)).baseLimit).isEqualTo(300)
        assertThat(
            database.sql("select expires_at from subscriptions where id = :id")
                .bind("id", subscriptionId)
                .map { row -> row.get("expires_at", java.time.LocalDateTime::class.java)!! }
                .one().awaitSingle(),
        ).isEqualTo(java.time.LocalDateTime.ofInstant(transaction.expiresAt, java.time.ZoneOffset.UTC))
        val eventId = "reconcile:$subscriptionId:${fetchedAt.toEpochMilli()}"
        assertThat(
            database.sql(
                "select event_type, processing_status from subscription_events where provider_event_id = :eventId",
            ).bind("eventId", eventId)
                .map { row ->
                    row.get("event_type", String::class.java)!! to
                        row.get("processing_status", String::class.java)!!
                }.one().awaitSingle(),
        ).isEqualTo("SUBSCRIPTION_SNAPSHOT_STALE" to "IGNORED")
    }

    @Test
    fun `expired reconciliation applies after verified payment period ends`(): Unit = runBlocking {
        val fixture = fixture("reconcile-current-expired")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val expiresAt = fixture.now.plusSeconds(30)
        val transaction = fixture.transaction().copy(expiresAt = expiresAt)
        val invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                transaction,
                checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "initial-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(invoice.id, fixture.now.plusSeconds(1))
        val subscriptionId = longValue(
            "select id from subscriptions where original_transaction_id = '${transaction.originalTransactionId}'",
        )
        val fetchedAt = expiresAt.plusSeconds(1)

        ledger.applySubscriptionSnapshot(
            SubscriptionReconciliationClaim(
                subscriptionId,
                fixture.userId,
                transaction.originalTransactionId,
                fixture.appAccountToken,
                1,
            ),
            RevenueCatCustomerSnapshot(
                SubscriptionAccessStatus.EXPIRED,
                SubscriptionRenewalStatus.NOT_APPLICABLE,
                expiresAt,
                fetchedAt,
            ),
            fetchedAt,
        )

        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER1")
        assertThat(requireNotNull(quota.quotaStatusForUser(fixture.userId, fetchedAt)).baseLimit).isEqualTo(30)
    }

    @Test
    fun `user reconciliation claims a subscription previously corrupted to unknown`(): Unit = runBlocking {
        val fixture = fixture("reconcile-unknown-claim")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                transaction,
                checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "initial-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(invoice.id, fixture.now.plusSeconds(1))
        execute(
            "update subscriptions set access_status = 'UNKNOWN' " +
                "where original_transaction_id = '${transaction.originalTransactionId}'",
        )

        val claims = ledger.claimUserSubscriptionReconciliations(fixture.userId, fixture.now.plusSeconds(2), 10)

        assertThat(claims).hasSize(1)
        assertThat(claims.single().originalTransactionId).isEqualTo(transaction.originalTransactionId)
        assertThat(claims.single().appAccountToken).isEqualTo(fixture.appAccountToken)
    }

    @Test
    fun `late renewal payment cannot reactivate an expired subscription`(): Unit = runBlocking {
        val fixture = fixture("revenuecat-late-renewal")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val initial = fixture.transaction()
        val initialInvoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                initial,
                checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "apple-transaction:${initial.transactionId}",
                fixture.now.plusSeconds(1),
            ),
        )
        ledger.fulfill(initialInvoice.id, fixture.now.plusSeconds(2))

        val expiration = fixture.revenueCatLifecycleEvent(
            eventId = "rc-expire-${fixture.suffix}",
            eventType = "EXPIRATION",
            transaction = initial,
            eventAt = fixture.now.plusSeconds(100),
        )
        createdRevenueCatEventIds += expiration.eventId
        assertThat(ledger.recordRevenueCatEvent(expiration, fixture.now.plusSeconds(101))).isTrue()
        assertThat(ledger.applyRevenueCatEvent(expiration, fixture.now.plusSeconds(102))).isTrue()

        val staleRenewal = initial.copy(
            transactionId = "stale-renewal-${fixture.suffix}",
            originalPurchaseAt = initial.purchaseAt,
            purchaseAt = fixture.now.plusSeconds(50),
            expiresAt = fixture.now.plusSeconds(2_592_050),
            signedAt = fixture.now.plusSeconds(103),
        )
        val staleInvoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                staleRenewal,
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "apple-transaction:${staleRenewal.transactionId}",
                fixture.now.plusSeconds(103),
            ),
        )
        ledger.fulfill(staleInvoice.id, fixture.now.plusSeconds(104))

        val subscriptionStatus = database.sql(
            "select access_status from subscriptions where original_transaction_id = :id",
        ).bind("id", initial.originalTransactionId)
            .map { row, _ -> row.get("access_status", String::class.java)!! }
            .one().awaitSingle()
        assertThat(subscriptionStatus).isEqualTo("EXPIRED")
        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER1")
        assertThat(
            longValue("select count(*) from user_memberships where user_id = ${fixture.userId} and status = 'ACTIVE'"),
        ).isZero()
    }

    @Test
    fun `refund committed before fulfillment prevents a late entitlement grant`(): Unit = runBlocking {
        val fixture = fixture("refund-before-fulfillment")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val recorded = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                transaction,
                checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "apple-transaction:${transaction.transactionId}",
                fixture.now.plusSeconds(1),
            ),
        )
        val notification = VerifiedAppleNotification(
            notificationUUID = "refund-before-fulfillment-${fixture.suffix}",
            notificationType = "REFUND",
            subtype = null,
            environment = BillingEnvironment.SANDBOX,
            signedAt = fixture.now.plusSeconds(2),
            signedPayloadSha256 = "c".repeat(64),
            transaction = transaction,
        )
        assertThat(ledger.recordAppleNotification(notification, fixture.now.plusSeconds(2))).isTrue()
        assertThat(
            ledger.applyAppleNotification(
                ApplyAppleNotificationCommand(notification, fixture.now.plusSeconds(3)),
            ),
        ).isTrue()

        val fulfilled = ledger.fulfill(recorded.id, fixture.now.plusSeconds(4))

        assertThat(fulfilled.paymentStatus).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(eventTypes(recorded.id)).contains("FULFILLMENT_FAILED").doesNotContain("FULFILLED")
        assertThat(
            longValue("select count(*) from user_memberships where user_id = ${fixture.userId} and status = 'ACTIVE'"),
        ).isZero()
        assertThat(
            longValue("select count(*) from billing_fulfillment_outbox where invoice_id = ${recorded.id} and status = 'COMPLETED'"),
        ).isEqualTo(1)
    }

    @Test
    fun `expiration committed before fulfillment prevents a late entitlement grant`(): Unit = runBlocking {
        val fixture = fixture("expiration-before-fulfillment")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val recorded = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                transaction,
                checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "apple-transaction:${transaction.transactionId}",
                fixture.now.plusSeconds(1),
            ),
        )
        val expiration = fixture.revenueCatLifecycleEvent(
            eventId = "rc-expire-before-fulfillment-${fixture.suffix}",
            eventType = "EXPIRATION",
            transaction = transaction,
            eventAt = fixture.now.plusSeconds(2),
        )
        createdRevenueCatEventIds += expiration.eventId
        assertThat(ledger.recordRevenueCatEvent(expiration, fixture.now.plusSeconds(2))).isTrue()
        assertThat(ledger.applyRevenueCatEvent(expiration, fixture.now.plusSeconds(3))).isTrue()

        ledger.fulfill(recorded.id, fixture.now.plusSeconds(4))

        val subscriptionStatus = database.sql(
            "select access_status from subscriptions where original_transaction_id = :id",
        ).bind("id", transaction.originalTransactionId)
            .map { row, _ -> row.get("access_status", String::class.java)!! }
            .one().awaitSingle()
        assertThat(subscriptionStatus).isEqualTo("EXPIRED")
        assertThat(
            longValue("select count(*) from user_memberships where user_id = ${fixture.userId} and status = 'ACTIVE'"),
        ).isZero()
        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER1")
    }

    @Test
    fun `checkout is idempotent and abandoned invoice remains failed`(): Unit = runBlocking {
        val fixture = fixture("checkout")
        val first = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val duplicate = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now.plusSeconds(1),
        )

        assertThat(duplicate.id).isEqualTo(first.id)
        assertThat(first.type).isEqualTo(InvoiceType.NORMAL)
        assertThat(first.status).isEqualTo(InvoiceStatus.WAITING)
        assertThat(first.paymentId).isNull()
        assertThat(eventTypes(first.id)).containsExactly("INVOICE_CREATED")

        val failed = ledger.abandonPendingInvoice(
            fixture.userId,
            first.invoiceNumber,
            fixture.now.plusSeconds(2),
        )
        val repeated = ledger.abandonPendingInvoice(
            fixture.userId,
            first.invoiceNumber,
            fixture.now.plusSeconds(3),
        )

        assertThat(failed.status).isEqualTo(InvoiceStatus.FAILED)
        assertThat(repeated.status).isEqualTo(InvoiceStatus.FAILED)
        assertThat(eventTypes(first.id)).containsExactly("INVOICE_CREATED", "CANCELLED")
        assertThat(longValue("select count(*) from payments where invoice_id = ${first.id}")).isZero()
    }

    @Test
    fun `checkout idempotency key cannot be reused for another product`(): Unit = runBlocking {
        val fixture = fixture("checkout-conflict")
        ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val otherProduct = requireNotNull(
            ledger.enabledTierProduct("io.github.ghkdqhrbals.StudyMate.tier3.monthly"),
        )

        assertThatThrownBy {
            runBlocking {
                ledger.createPendingInvoice(
                    fixture.userId,
                    fixture.appAccountToken,
                    otherProduct,
                    fixture.idempotencyKey,
                    fixture.now.plusSeconds(1),
                )
            }
        }.isInstanceOf(ApiException::class.java)

        assertThat(longValue("select count(*) from invoices where user_id = ${fixture.userId}")).isEqualTo(1)
    }

    @Test
    fun `verified transaction reconciles a duplicate prepared invoice but rejects another user`(): Unit = runBlocking {
        val fixture = fixture("transaction-invoice-conflict")
        val first = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            "${fixture.idempotencyKey}-first",
            fixture.now,
        )
        val second = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            "${fixture.idempotencyKey}-second",
            fixture.now.plusSeconds(1),
        )
        val transaction = fixture.transaction()

        val recorded = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                userId = fixture.userId,
                tierProduct = fixture.product,
                transaction = transaction,
                invoiceNumber = first.invoiceNumber,
                source = BillingEventSource.CLIENT,
                eventId = "apple-transaction:${transaction.transactionId}",
                occurredAt = fixture.now.plusSeconds(2),
            ),
        )

        assertThat(recorded.id).isEqualTo(first.id)
        val reconciled = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                userId = fixture.userId,
                tierProduct = fixture.product,
                transaction = transaction,
                invoiceNumber = second.invoiceNumber,
                source = BillingEventSource.CLIENT,
                eventId = "apple-transaction:${transaction.transactionId}",
                occurredAt = fixture.now.plusSeconds(3),
            ),
        )

        assertThat(reconciled.id).isEqualTo(first.id)
        assertThat(requireNotNull(ledger.invoice(fixture.userId, second.id)).invoice.status)
            .isEqualTo(InvoiceStatus.FAILED)
        assertThat(eventTypes(second.id)).containsExactly("INVOICE_CREATED", "CANCELLED")

        val anotherUser = fixture("transaction-invoice-other-user", fixture.now)
        assertThatThrownBy {
            runBlocking {
                ledger.recordVerifiedPayment(
                    RecordVerifiedPaymentCommand(
                        userId = anotherUser.userId,
                        tierProduct = anotherUser.product,
                        transaction = transaction.copy(appAccountToken = anotherUser.appAccountToken),
                        invoiceNumber = null,
                        source = BillingEventSource.CLIENT,
                        eventId = "apple-transaction:${transaction.transactionId}",
                        occurredAt = fixture.now.plusSeconds(4),
                    ),
                )
            }
        }.isInstanceOf(ApiException::class.java)

        assertThat(longValue("select count(*) from payments where provider_transaction_id = '${transaction.transactionId}'"))
            .isEqualTo(1)
        assertThat(longValue("select count(*) from payments where invoice_id = ${second.id}"))
            .isZero()
    }

    @Test
    fun `RevenueCat verified ownership transfer reassigns projections without duplicating payment`(): Unit =
        runBlocking {
            val previous = fixture("revenuecat-transfer-previous")
            val transaction = previous.transaction().copy(
                originalTransactionId = "original-${previous.suffix}",
            )
            val previousCheckout = ledger.createPendingInvoice(
                previous.userId,
                previous.appAccountToken,
                previous.product,
                previous.idempotencyKey,
                previous.now,
            )
            val previousInvoice = ledger.recordVerifiedPayment(
                RecordVerifiedPaymentCommand(
                    previous.userId,
                    previous.product,
                    transaction,
                    previousCheckout.invoiceNumber,
                    BillingEventSource.CLIENT,
                    "apple-transaction:${transaction.transactionId}",
                    previous.now,
                ),
            )
            ledger.fulfill(previousInvoice.id, previous.now.plusSeconds(1))

            val current = fixture("revenuecat-transfer-current", previous.now.plusSeconds(60))
            val currentCheckout = ledger.createPendingInvoice(
                current.userId,
                current.appAccountToken,
                current.product,
                current.idempotencyKey,
                current.now,
            )
            val transferred = transaction.copy(
                appAccountToken = current.appAccountToken,
                originalTransactionId = transaction.transactionId,
                signedAt = current.now,
            )

            val reconciled = ledger.recordVerifiedPayment(
                RecordVerifiedPaymentCommand(
                    current.userId,
                    current.product,
                    transferred,
                    currentCheckout.invoiceNumber,
                    BillingEventSource.CLIENT,
                    "revenuecat-transfer:${transaction.transactionId}:${current.userId}",
                    current.now,
                    authoritativeOwnershipTransfer = true,
                ),
            )
            ledger.fulfill(reconciled.id, current.now.plusSeconds(1))

            assertThat(reconciled.id).isEqualTo(previousInvoice.id)
            assertThat(requireNotNull(ledger.invoice(current.userId, currentCheckout.id)).invoice.status)
                .isEqualTo(InvoiceStatus.FAILED)
            assertThat(
                longValue(
                    "select user_id from subscriptions where original_transaction_id = '${transaction.originalTransactionId}'",
                ),
            ).isEqualTo(current.userId)
            assertThat(
                longValue(
                    "select user_id from user_memberships where original_transaction_id = '${transaction.originalTransactionId}'",
                ),
            ).isEqualTo(current.userId)
            assertThat(ledger.entitlementForUser(previous.userId)?.tierCode).isEqualTo("TIER1")
            assertThat(ledger.entitlementForUser(current.userId)?.tierCode).isEqualTo("TIER2")
            assertThat(
                longValue("select count(*) from payments where provider_transaction_id = '${transaction.transactionId}'"),
            ).isEqualTo(1)
            assertThat(
                longValue("select user_id from payments where provider_transaction_id = '${transaction.transactionId}'"),
            ).isEqualTo(previous.userId)
            assertThat(
                longValue(
                    "select count(*) from subscriptions where original_transaction_id = '${transferred.originalTransactionId}'",
                ),
            ).isZero()
        }

    @Test
    fun `newer verified transaction transfers subscription ownership and revokes the previous projection`(): Unit =
        runBlocking {
            val previous = fixture("subscription-owner-previous")
            val originalTransaction = previous.transaction()
            val previousCheckout = ledger.createPendingInvoice(
                previous.userId,
                previous.appAccountToken,
                previous.product,
                previous.idempotencyKey,
                previous.now,
            )
            val previousInvoice = ledger.recordVerifiedPayment(
                RecordVerifiedPaymentCommand(
                    previous.userId,
                    previous.product,
                    originalTransaction,
                    previousCheckout.invoiceNumber,
                    BillingEventSource.CLIENT,
                    "apple-transaction:${originalTransaction.transactionId}",
                    previous.now,
                ),
            )
            ledger.fulfill(previousInvoice.id, previous.now.plusSeconds(1))

            val current = fixture("subscription-owner-current", previous.now.plusSeconds(60))
            val renewalAt = previous.now.plusSeconds(120)
            val renewalTransaction = originalTransaction.copy(
                transactionId = "transferred-renewal-${current.suffix}",
                appAccountToken = current.appAccountToken,
                purchaseAt = renewalAt,
                originalPurchaseAt = originalTransaction.purchaseAt,
                expiresAt = renewalAt.plusSeconds(2_592_000),
                signedAt = renewalAt,
            )
            val currentCheckout = ledger.createPendingInvoice(
                current.userId,
                current.appAccountToken,
                current.product,
                current.idempotencyKey,
                renewalAt,
            )
            val currentInvoice = ledger.recordVerifiedPayment(
                RecordVerifiedPaymentCommand(
                    current.userId,
                    current.product,
                    renewalTransaction,
                    currentCheckout.invoiceNumber,
                    BillingEventSource.CLIENT,
                    "apple-transaction:${renewalTransaction.transactionId}",
                    renewalAt,
                ),
            )
            ledger.fulfill(currentInvoice.id, renewalAt.plusSeconds(1))

            val subscriptionOwner = longValue(
                "select user_id from subscriptions where original_transaction_id = '${originalTransaction.originalTransactionId}'",
            )
            val membershipOwner = longValue(
                "select user_id from user_memberships where original_transaction_id = '${originalTransaction.originalTransactionId}'",
            )
            assertThat(subscriptionOwner).isEqualTo(current.userId)
            assertThat(membershipOwner).isEqualTo(current.userId)
            assertThat(ledger.entitlementForUser(previous.userId)?.tierCode).isEqualTo("TIER1")
            assertThat(ledger.entitlementForUser(current.userId)?.tierCode).isEqualTo("TIER2")
            assertThat(
                longValue(
                    "select count(*) from user_memberships where user_id = ${previous.userId} and status = 'ACTIVE'",
                ),
            ).isZero()
            assertThat(
                longValue(
                    "select count(*) from user_memberships where user_id = ${current.userId} and status = 'ACTIVE'",
                ),
            ).isEqualTo(1)
        }

    @Test
    fun `checkout expiration cancels only old unpaid normal invoices`(): Unit = runBlocking {
        val fixture = fixture("checkout-expiration")
        val oldUnpaid = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            "${fixture.idempotencyKey}-old",
            fixture.now,
        )
        val paid = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            "${fixture.idempotencyKey}-paid",
            fixture.now.plusSeconds(1),
        )
        val transaction = fixture.transaction()
        ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                userId = fixture.userId,
                tierProduct = fixture.product,
                transaction = transaction,
                invoiceNumber = paid.invoiceNumber,
                source = BillingEventSource.CLIENT,
                eventId = "apple-transaction:${transaction.transactionId}",
                occurredAt = fixture.now.plusSeconds(2),
            ),
        )
        val recentUnpaid = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            "${fixture.idempotencyKey}-recent",
            fixture.now.plusSeconds(60),
        )

        val expired = ledger.expirePendingCheckouts(
            expiredBefore = fixture.now.plusSeconds(30),
            now = fixture.now.plusSeconds(630),
            limit = 100,
        )

        assertThat(expired).isEqualTo(1)
        val expiredInvoice = requireNotNull(ledger.invoice(fixture.userId, oldUnpaid.id)).invoice
        assertThat(expiredInvoice.status).isEqualTo(InvoiceStatus.FAILED)
        assertThat(expiredInvoice.latestEventType).isEqualTo(InvoiceEventType.CANCELLED)
        assertThat(requireNotNull(ledger.invoice(fixture.userId, paid.id)).invoice.status)
            .isEqualTo(InvoiceStatus.COMPLETED)
        assertThat(requireNotNull(ledger.invoice(fixture.userId, recentUnpaid.id)).invoice.status)
            .isEqualTo(InvoiceStatus.WAITING)
        assertThat(eventTypes(oldUnpaid.id)).containsExactly("INVOICE_CREATED", "CANCELLED")
        assertThat(
            database.sql(
                "select source from invoice_events where invoice_id = :invoiceId and event_type = 'CANCELLED'",
            )
                .bind("invoiceId", oldUnpaid.id)
                .map { row -> row.get("source", String::class.java)!! }
                .one().awaitSingle(),
        ).isEqualTo("SYSTEM")
    }

    @Test
    fun `verified charge fulfillment can be reclaimed after backend process death`(): Unit = runBlocking {
        val fixture = fixture("crash-recovery")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val recorded = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                userId = fixture.userId,
                tierProduct = fixture.product,
                transaction = transaction,
                invoiceNumber = checkout.invoiceNumber,
                source = BillingEventSource.CLIENT,
                eventId = "apple-transaction:${transaction.transactionId}",
                occurredAt = fixture.now.plusSeconds(1),
            ),
        )

        val abandonedClaim = ledger.claimDueFulfillmentJobs(
            fixture.now.plusSeconds(2),
            fixture.now.minusSeconds(120),
            10,
        ).single { it.invoiceId == recorded.id }

        // No completion or release call simulates SIGKILL after the job was claimed.
        val reclaimed = ledger.claimDueFulfillmentJobs(
            fixture.now.plusSeconds(200),
            fixture.now.plusSeconds(80),
            10,
        ).single { it.invoiceId == recorded.id }
        assertThat(reclaimed.claimToken).isNotEqualTo(abandonedClaim.claimToken)

        ledger.rescheduleFulfillmentJob(
            reclaimed,
            "temporary membership database outage",
            fixture.now.plusSeconds(210),
            fixture.now.plusSeconds(200),
        )
        assertThat(
            ledger.claimDueFulfillmentJobs(
                fixture.now.plusSeconds(205),
                fixture.now.plusSeconds(85),
                10,
            ),
        ).noneMatch { it.invoiceId == recorded.id }

        val retry = ledger.claimDueFulfillmentJobs(
            fixture.now.plusSeconds(211),
            fixture.now.plusSeconds(90),
            10,
        ).single { it.invoiceId == recorded.id }
        assertThat(retry.attempts).isEqualTo(1)
        val completed = ledger.fulfill(retry.invoiceId, fixture.now.plusSeconds(212))

        assertThat(completed.status).isEqualTo(InvoiceStatus.COMPLETED)
        assertThat(
            longValue("select count(*) from billing_fulfillment_outbox where invoice_id = ${recorded.id} and status = 'COMPLETED'"),
        ).isEqualTo(1)
    }

    @Test
    fun `verified payment completes normal invoice and Apple refund completes a linked refund invoice`(): Unit = runBlocking {
        val fixture = fixture("refund")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val recorded = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                userId = fixture.userId,
                tierProduct = fixture.product,
                transaction = transaction,
                invoiceNumber = checkout.invoiceNumber,
                source = BillingEventSource.CLIENT,
                eventId = "apple-transaction:${transaction.transactionId}",
                occurredAt = fixture.now.plusSeconds(1),
            ),
        )

        assertThat(recorded.id).isEqualTo(checkout.id)
        assertThat(recorded.type).isEqualTo(InvoiceType.NORMAL)
        assertThat(recorded.status).isEqualTo(InvoiceStatus.COMPLETED)
        assertThat(recorded.paymentStatus).isEqualTo(PaymentStatus.VERIFIED)

        val completed = ledger.fulfill(recorded.id, fixture.now.plusSeconds(2))
        val completedAgain = ledger.fulfill(recorded.id, fixture.now.plusSeconds(3))

        assertThat(completed.status).isEqualTo(InvoiceStatus.COMPLETED)
        assertThat(completed.paymentStatus).isEqualTo(PaymentStatus.SETTLED)
        assertThat(completedAgain.status).isEqualTo(InvoiceStatus.COMPLETED)
        assertThat(eventTypes(recorded.id)).containsExactly(
            "INVOICE_CREATED",
            "PAYMENT_VERIFIED",
            "FULFILLMENT_STARTED",
            "FULFILLED",
        )
        assertThat(
            longValue(
                "select count(*) from user_memberships where user_id = ${fixture.userId} and status = 'ACTIVE'",
            ),
        ).isEqualTo(1)

        val paymentId = requireNotNull(completed.paymentId)
        val refundAction = ledger.requestRefund(
            fixture.userId,
            paymentId,
            RequestBillingActionCommand("refund-${fixture.suffix}", "duplicate charge"),
            fixture.now.plusSeconds(4),
        )
        val duplicateAction = ledger.requestRefund(
            fixture.userId,
            paymentId,
            RequestBillingActionCommand("refund-${fixture.suffix}", "duplicate charge"),
            fixture.now.plusSeconds(5),
        )

        assertThat(duplicateAction.actionId).isEqualTo(refundAction.actionId)
        assertThat(refundAction.status).isEqualTo(BillingActionStatus.AWAITING_APPLE)
        val awaitingRefund = requireNotNull(ledger.invoice(fixture.userId, refundAction.invoiceId))
        assertThat(awaitingRefund.invoice.type).isEqualTo(InvoiceType.REFUND)
        assertThat(awaitingRefund.invoice.originalInvoiceId).isEqualTo(completed.id)
        assertThat(awaitingRefund.invoice.status).isEqualTo(InvoiceStatus.WAITING)
        assertThat(awaitingRefund.events.map { it.eventType }).containsExactly("INVOICE_CREATED", "REFUND_REQUESTED")
        assertThat(awaitingRefund.paymentHistory.map { it.eventType }).containsExactly("REFUND_REQUESTED")

        val notification = VerifiedAppleNotification(
            notificationUUID = "refund-notification-${fixture.suffix}",
            notificationType = "REFUND",
            subtype = null,
            environment = BillingEnvironment.SANDBOX,
            signedAt = fixture.now.plusSeconds(6),
            signedPayloadSha256 = "b".repeat(64),
            transaction = transaction,
        )
        assertThat(ledger.recordAppleNotification(notification, fixture.now.plusSeconds(6))).isTrue()
        assertThat(
            ledger.applyAppleNotification(
                ApplyAppleNotificationCommand(notification, fixture.now.plusSeconds(7)),
            ),
        ).isTrue()

        val normalAfterRefund = requireNotNull(ledger.invoice(fixture.userId, completed.id))
        val refundAfterApple = requireNotNull(ledger.invoice(fixture.userId, refundAction.invoiceId))
        assertThat(normalAfterRefund.invoice.status).isEqualTo(InvoiceStatus.COMPLETED)
        assertThat(normalAfterRefund.invoice.type).isEqualTo(InvoiceType.NORMAL)
        assertThat(refundAfterApple.invoice.status).isEqualTo(InvoiceStatus.COMPLETED)
        assertThat(refundAfterApple.invoice.type).isEqualTo(InvoiceType.REFUND)
        assertThat(refundAfterApple.invoice.paymentStatus).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(refundAfterApple.events.map { it.eventType }).containsExactly(
            "INVOICE_CREATED",
            "REFUND_REQUESTED",
            "REFUNDED",
        )
        assertThat(
            longValue(
                "select count(*) from user_memberships where user_id = ${fixture.userId} and status = 'ACTIVE'",
            ),
        ).isZero()
    }

    @Test
    fun `RevenueCat customer support cancellation preserves access until provider reconciliation`(): Unit = runBlocking {
        val fixture = fixture("revenuecat-refund")
        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val transaction = fixture.transaction()
        val recorded = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                userId = fixture.userId,
                tierProduct = fixture.product,
                transaction = transaction,
                invoiceNumber = checkout.invoiceNumber,
                source = BillingEventSource.CLIENT,
                eventId = "apple-transaction:${transaction.transactionId}",
                occurredAt = fixture.now.plusSeconds(1),
            ),
        )
        ledger.fulfill(recorded.id, fixture.now.plusSeconds(2))

        val eventId = "rc-refund-${fixture.suffix}"
        createdRevenueCatEventIds += eventId
        val refund = VerifiedRevenueCatEvent(
            eventId = eventId,
            eventType = "CANCELLATION",
            appUserId = fixture.appAccountToken.toString(),
            originalAppUserId = fixture.appAccountToken.toString(),
            aliases = emptyList(),
            store = "APP_STORE",
            productId = fixture.product.productId,
            transactionId = transaction.transactionId,
            originalTransactionId = transaction.originalTransactionId,
            environment = BillingEnvironment.SANDBOX,
            priceMilliunits = -7_900_000,
            currency = "KRW",
            purchasedAt = transaction.purchaseAt,
            expiresAt = transaction.expiresAt,
            eventAt = fixture.now.plusSeconds(3),
            cancelReason = "CUSTOMER_SUPPORT",
            expirationReason = null,
            signedPayloadSha256 = "d".repeat(64),
        )

        assertThat(ledger.recordRevenueCatEvent(refund, refund.eventAt)).isTrue()
        assertThat(ledger.applyRevenueCatEvent(refund, refund.eventAt)).isTrue()

        assertThat(longValue("select count(*) from invoices where original_invoice_id = ${recorded.id} and type = 'REFUND'"))
            .isZero()
        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER2")
        assertThat(
            longValue(
                "select count(*) from user_memberships where user_id = ${fixture.userId} and status = 'ACTIVE'",
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `first paid upgrade and renewal preserve usage in the anchored quota window`(): Unit = runBlocking {
        val fixture = fixture("quota-anchor", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(60))
        val beforePurchase = fixture.now.minusSeconds(10)
        assertThat(
            quota.reserveMonthlySystemQuestion(
                fixture.userId,
                beforePurchase,
                "reserve-${fixture.suffix}",
                "question-${fixture.suffix}",
                beforePurchase,
            ),
        ).isTrue()
        quota.commitMonthlySystemQuestion("reserve-${fixture.suffix}", beforePurchase.plusSeconds(1))
        assertThat(requireNotNull(quota.quotaStatusForUser(fixture.userId, beforePurchase)).usedCount).isEqualTo(1)

        val checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            fixture.idempotencyKey,
            fixture.now,
        )
        val initialTransaction = fixture.transaction()
        val initialInvoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                initialTransaction,
                checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "apple-transaction:${initialTransaction.transactionId}",
                fixture.now.plusSeconds(1),
            ),
        )
        ledger.fulfill(initialInvoice.id, fixture.now.plusSeconds(2))

        val paidStatus = requireNotNull(quota.quotaStatusForUser(fixture.userId, fixture.now.plusSeconds(3)))
        assertThat(paidStatus.tierCode).isEqualTo("TIER2")
        assertThat(paidStatus.usedCount).isEqualTo(1)
        assertThat(paidStatus.baseLimit).isEqualTo(300)
        assertThat(paidStatus.anchorType).isEqualTo("FIRST_PAID")
        assertThat(paidStatus.periodStartedAt).isEqualTo(fixture.now)

        assertThat(
            quota.reserveMonthlySystemQuestion(
                fixture.userId,
                fixture.now.plusSeconds(4),
                "pending-${fixture.suffix}",
                "pending-question-${fixture.suffix}",
                fixture.now.plusSeconds(4),
            ),
        ).isTrue()
        ledger.adminAdjustQuota(
            fixture.userId,
            20,
            "support bonus",
            "bonus-${fixture.suffix}",
            fixture.now.plusSeconds(5),
        )
        val adminSummary = requireNotNull(adminManagement.user(fixture.userId))
        assertThat(adminSummary.tierCode).isEqualTo("TIER2")
        assertThat(adminSummary.baseLimit).isEqualTo(300)
        assertThat(adminSummary.bonusLimit).isEqualTo(20)
        assertThat(adminSummary.usedCount).isEqualTo(1)
        assertThat(adminSummary.reservedCount).isEqualTo(1)
        assertThat(adminSummary.remainingCount).isEqualTo(318)

        val renewalAt = fixture.now.plusSeconds(86_400)
        val renewalTransaction = initialTransaction.copy(
            transactionId = "renewal-${fixture.suffix}",
            purchaseAt = renewalAt,
            expiresAt = renewalAt.plusSeconds(2_592_000),
            signedAt = renewalAt,
        )
        val renewalInvoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                renewalTransaction,
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "apple-transaction:${renewalTransaction.transactionId}",
                renewalAt,
            ),
        )
        ledger.fulfill(renewalInvoice.id, renewalAt.plusSeconds(1))

        val renewedStatus = requireNotNull(quota.quotaStatusForUser(fixture.userId, renewalAt.plusSeconds(2)))
        assertThat(renewedStatus.usedCount).isEqualTo(1)
        assertThat(renewedStatus.reservedCount).isEqualTo(1)
        assertThat(renewedStatus.baseLimit).isEqualTo(300)
        assertThat(renewedStatus.bonusLimit).isEqualTo(20)
        assertThat(renewedStatus.monthlyQuestionLimit).isEqualTo(320)
        assertThat(renewedStatus.periodStartedAt).isEqualTo(fixture.now)
        assertThat(
            database.sql("select first_paid_at from user_quota where user_id = :userId")
                .bind("userId", fixture.userId)
                .map { row -> row.get("first_paid_at", java.time.LocalDateTime::class.java)!! }
                .one().awaitSingle(),
        ).isEqualTo(java.time.LocalDateTime.ofInstant(fixture.now, java.time.ZoneOffset.UTC))
    }

    @Test
    fun `highest active subscription wins while expiration and resubscription preserve quota usage`(): Unit = runBlocking {
        val fixture = fixture("subscription-priority")
        val tier2Checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            "tier2-${fixture.suffix}",
            fixture.now,
        )
        val tier2Transaction = fixture.transaction().copy(expiresAt = fixture.now.plusSeconds(105))
        val tier2Invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                tier2Transaction,
                tier2Checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "tier2-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(tier2Invoice.id, fixture.now.plusSeconds(1))

        val quotaKey = "priority-quota-${fixture.suffix}"
        assertThat(quota.reserveMonthlySystemQuestion(fixture.userId, fixture.now, quotaKey, quotaKey, fixture.now))
            .isTrue()
        quota.commitMonthlySystemQuestion(quotaKey, fixture.now.plusSeconds(2))
        assertThat(
            intValue("select committed_count from user_quota where user_id = ${fixture.userId}"),
        ).isEqualTo(1)

        val tier3 = requireNotNull(
            ledger.enabledTierProduct("io.github.ghkdqhrbals.StudyMate.tier3.monthly"),
        )
        val tier3PurchasedAt = fixture.now.plusSeconds(100)
        val tier3Transaction = tier2Transaction.copy(
            transactionId = "tier3-tx-${fixture.suffix}",
            originalTransactionId = "tier3-original-${fixture.suffix}",
            productId = tier3.productId,
            priceMilliunits = 17_900_000,
            purchaseAt = tier3PurchasedAt,
            originalPurchaseAt = tier3PurchasedAt,
            expiresAt = tier3PurchasedAt.plusSeconds(3),
            signedAt = tier3PurchasedAt,
        )
        val tier3Checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            tier3,
            "tier3-${fixture.suffix}",
            tier3PurchasedAt,
        )
        val tier3Invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                tier3,
                tier3Transaction,
                tier3Checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "tier3-payment-${fixture.suffix}",
                tier3PurchasedAt,
            ),
        )
        ledger.fulfill(tier3Invoice.id, tier3PurchasedAt.plusSeconds(1))

        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER3")
        val tier3Quota = requireNotNull(quota.quotaStatusForUser(fixture.userId, tier3PurchasedAt.plusSeconds(2)))
        assertThat(tier3Quota.usedCount).isEqualTo(1)
        assertThat(tier3Quota.baseLimit).isEqualTo(1_000)
        assertThat(tier3Quota.periodStartedAt).isEqualTo(fixture.now)

        expireSubscription(
            fixture.userId,
            tier3Transaction.originalTransactionId,
            fixture.appAccountToken,
            tier3PurchasedAt.plusSeconds(3),
        )
        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER2")
        assertThat(requireNotNull(quota.quotaStatusForUser(fixture.userId, tier3PurchasedAt.plusSeconds(4))).usedCount)
            .isEqualTo(1)

        expireSubscription(
            fixture.userId,
            tier2Transaction.originalTransactionId,
            fixture.appAccountToken,
            tier3PurchasedAt.plusSeconds(5),
        )
        val expiredQuota = requireNotNull(quota.quotaStatusForUser(fixture.userId, tier3PurchasedAt.plusSeconds(6)))
        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER1")
        assertThat(expiredQuota.usedCount).isEqualTo(1)
        assertThat(expiredQuota.baseLimit).isEqualTo(30)

        val resubscribeAt = tier3PurchasedAt.plusSeconds(10)
        val resubscribeTransaction = tier3Transaction.copy(
            transactionId = "resubscribe-${fixture.suffix}",
            originalTransactionId = "resubscribe-original-${fixture.suffix}",
            purchaseAt = resubscribeAt,
            originalPurchaseAt = resubscribeAt,
            expiresAt = resubscribeAt.plusSeconds(2_592_000),
            signedAt = resubscribeAt,
        )
        val resubscribeInvoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                tier3,
                resubscribeTransaction,
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "resubscribe-payment-${fixture.suffix}",
                resubscribeAt,
            ),
        )
        ledger.fulfill(resubscribeInvoice.id, resubscribeAt.plusSeconds(1))
        val resubscribedQuota = requireNotNull(quota.quotaStatusForUser(fixture.userId, resubscribeAt.plusSeconds(2)))
        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER3")
        assertThat(resubscribedQuota.usedCount).isEqualTo(1)
        assertThat(resubscribedQuota.periodStartedAt).isEqualTo(fixture.now)
    }

    @Test
    fun `product change cannot block an immediate upgrade and preserved quota history is idempotent`(): Unit = runBlocking {
        val fixture = fixture("same-chain-upgrade")
        val tier2Checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            fixture.product,
            "tier2-${fixture.suffix}",
            fixture.now,
        )
        val tier2Transaction = fixture.transaction()
        val tier2Invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                tier2Transaction,
                tier2Checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "tier2-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(tier2Invoice.id, fixture.now.plusSeconds(1))
        val quotaKey = "same-chain-quota-${fixture.suffix}"
        assertThat(quota.reserveMonthlySystemQuestion(fixture.userId, fixture.now, quotaKey, quotaKey, fixture.now))
            .isTrue()
        quota.commitMonthlySystemQuestion(quotaKey, fixture.now.plusSeconds(2))

        val tier3 = requireNotNull(
            ledger.enabledTierProduct("io.github.ghkdqhrbals.StudyMate.tier3.monthly"),
        )
        val tier3PurchasedAt = fixture.now.plusSeconds(100)
        val productChangeAt = fixture.now.plusSeconds(120)
        val productChangeEventId = "product-change-${fixture.suffix}"
        createdRevenueCatEventIds += productChangeEventId
        val productChange = fixture.revenueCatLifecycleEvent(
            productChangeEventId,
            "PRODUCT_CHANGE",
            tier2Transaction.copy(productId = tier3.productId),
            productChangeAt,
            productOverride = tier3,
        )
        assertThat(ledger.recordRevenueCatEvent(productChange, productChangeAt)).isTrue()
        assertThat(ledger.applyRevenueCatEvent(productChange, productChangeAt)).isTrue()

        val tier3Checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            tier3,
            "tier3-${fixture.suffix}",
            tier3PurchasedAt,
        )
        val tier3Transaction = tier2Transaction.copy(
            transactionId = "tier3-tx-${fixture.suffix}",
            productId = tier3.productId,
            priceMilliunits = 17_900_000,
            purchaseAt = tier3PurchasedAt,
            expiresAt = tier3PurchasedAt.plusSeconds(2_592_000),
            signedAt = tier3PurchasedAt,
        )
        val tier3Invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                tier3,
                tier3Transaction,
                tier3Checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "tier3-payment-${fixture.suffix}",
                tier3PurchasedAt,
            ),
        )
        ledger.fulfill(tier3Invoice.id, tier3PurchasedAt.plusSeconds(1))

        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER3")
        val upgradedQuota = requireNotNull(quota.quotaStatusForUser(fixture.userId, tier3PurchasedAt.plusSeconds(2)))
        assertThat(upgradedQuota.baseLimit).isEqualTo(1_000)
        assertThat(upgradedQuota.usedCount).isEqualTo(1)
        assertThat(upgradedQuota.reservedCount).isZero()
        assertThat(upgradedQuota.bonusLimit).isZero()
        assertThat(upgradedQuota.monthlyQuestionLimit).isEqualTo(1_000)
        assertThat(upgradedQuota.periodStartedAt).isEqualTo(fixture.now)
        assertThat(upgradedQuota.policyVersion).isEqualTo(MonthlyQuestionQuotaPolicy.VERSION)
        assertThat(
            intValue("select committed_count from user_quota where user_id = ${fixture.userId}"),
        ).isEqualTo(1)
        assertThat(
            longValue(
                "select count(*) from subscriptions where original_transaction_id = " +
                    "'${tier2Transaction.originalTransactionId}' and pending_product_id is not null",
            ),
        ).isZero()

        ledger.fulfill(tier3Invoice.id, tier3PurchasedAt.plusSeconds(3))
        assertThat(
            longValue(
                "select count(*) from user_quota_history " +
                    "where event_id = 'billing-tier-change:${tier3Invoice.id}' and event_type = 'PLAN_UPGRADED'",
            ),
        ).isEqualTo(1)
        assertThat(requireNotNull(quota.quotaStatusForUser(fixture.userId, tier3PurchasedAt.plusSeconds(4))).usedCount)
            .isEqualTo(1)
    }

    @Test
    fun `completed upgrade repairs a stale product projection without regressing newer lifecycle state`(): Unit = runBlocking {
        val fixture = fixture("stale-upgrade-projection")
        val tier2Invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                fixture.transaction(),
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "tier2-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(tier2Invoice.id, fixture.now.plusSeconds(1))

        val tier3 = requireNotNull(
            ledger.enabledTierProduct("io.github.ghkdqhrbals.StudyMate.tier3.monthly"),
        )
        val tier3PurchasedAt = fixture.now.plusSeconds(100)
        val tier3Transaction = fixture.transaction().copy(
            transactionId = "tier3-stale-tx-${fixture.suffix}",
            productId = tier3.productId,
            priceMilliunits = 17_900_000,
            purchaseAt = tier3PurchasedAt,
            expiresAt = tier3PurchasedAt.plusSeconds(2_592_000),
            signedAt = tier3PurchasedAt,
        )
        val tier3Invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                tier3,
                tier3Transaction,
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "tier3-payment-${fixture.suffix}",
                tier3PurchasedAt,
            ),
        )
        ledger.fulfill(tier3Invoice.id, tier3PurchasedAt.plusSeconds(1))

        val laterLifecycleAt = tier3PurchasedAt.plusSeconds(300)
        forceStaleSubscriptionProjection(
            fixture = fixture,
            transaction = tier3Transaction,
            pendingProductId = tier3.productId,
            accessStatus = "ACTIVE",
            renewalStatus = "CANCELED",
            lastProviderEventAt = laterLifecycleAt,
        )
        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER2")
        assertThat(requireNotNull(quota.quotaStatusForUser(fixture.userId, laterLifecycleAt)).baseLimit)
            .isEqualTo(300)

        ledger.fulfill(tier3Invoice.id, laterLifecycleAt.plusSeconds(1))

        val entitlement = requireNotNull(ledger.entitlementForUser(fixture.userId))
        assertThat(entitlement.tierCode).isEqualTo("TIER3")
        assertThat(entitlement.renewalStatus).isEqualTo(SubscriptionRenewalStatus.CANCELED)
        assertThat(requireNotNull(quota.quotaStatusForUser(fixture.userId, laterLifecycleAt.plusSeconds(2))).baseLimit)
            .isEqualTo(1_000)
        assertThat(
            longValue(
                "select count(*) from subscriptions where original_transaction_id = " +
                    "'${tier3Transaction.originalTransactionId}' and product_id = '${tier3.productId}' " +
                    "and tier_code = 'TIER3' and pending_product_id is null " +
                    "and renewal_status = 'CANCELED' and last_provider_event_at = '${laterLifecycleAt.utcText()}'",
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `completed transaction replay repairs an unexpired subscription corrupted to unknown`(): Unit = runBlocking {
        val fixture = fixture("unknown-transaction-replay")
        val transaction = fixture.transaction()
        val invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                transaction,
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "initial-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(invoice.id, fixture.now.plusSeconds(1))

        val inconclusiveAt = fixture.now.plusSeconds(300)
        execute(
            """
            update subscriptions
            set access_status = 'UNKNOWN',
                renewal_status = 'UNKNOWN',
                last_provider_event_at = '${inconclusiveAt.utcText()}',
                updated_at = '${inconclusiveAt.utcText()}'
            where original_transaction_id = '${transaction.originalTransactionId}'
            """.trimIndent(),
        )
        execute(
            """
            update user_memberships
            set status = 'INACTIVE', updated_at = '${inconclusiveAt.utcText()}'
            where source_invoice_id = ${invoice.id}
            """.trimIndent(),
        )
        execute(
            """
            update user_entitlement_projection
            set tier_code = 'TIER1',
                source = 'FREE',
                access_status = 'ACTIVE',
                renewal_status = 'NOT_APPLICABLE',
                product_id = null,
                pending_product_id = null,
                projected_at = '${inconclusiveAt.utcText()}',
                version = version + 1
            where user_id = ${fixture.userId}
            """.trimIndent(),
        )
        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER1")
        assertThat(
            longValue(
                "select count(*) from invoices where id = ${invoice.id} and status = 'COMPLETED' " +
                    "and fulfilled_at is not null",
            ),
        ).isEqualTo(1)
        assertThat(
            longValue(
                "select count(*) from payments where invoice_id = ${invoice.id} and status = 'SETTLED'",
            ),
        ).isEqualTo(1)

        val replayed = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                transaction,
                null,
                BillingEventSource.CLIENT,
                "current-entitlement-replay-${fixture.suffix}",
                inconclusiveAt.plusSeconds(1),
            ),
        )

        assertThat(replayed.id).isEqualTo(invoice.id)
        assertThat(
            longValue(
                "select count(*) from subscriptions where original_transaction_id = " +
                    "'${transaction.originalTransactionId}' and access_status = 'ACTIVE' " +
                    "and product_id = '${fixture.product.productId}' and tier_code = 'TIER2'",
            ),
        ).isEqualTo(1)
        assertThat(
            longValue(
                "select count(*) from user_memberships where source_invoice_id = ${invoice.id} " +
                    "and status = 'ACTIVE' and tier = 'TIER2'",
            ),
        ).isEqualTo(1)
        val entitlement = requireNotNull(ledger.entitlementForUser(fixture.userId))
        assertThat(entitlement.tierCode).isEqualTo("TIER2")
        assertThat(entitlement.accessStatus).isEqualTo(SubscriptionAccessStatus.ACTIVE)
        assertThat(entitlement.renewalStatus).isEqualTo(SubscriptionRenewalStatus.UNKNOWN)
        assertThat(
            longValue(
                "select count(*) from subscriptions where original_transaction_id = " +
                    "'${transaction.originalTransactionId}' and access_status = 'ACTIVE' " +
                    "and renewal_status = 'UNKNOWN' and last_provider_event_at = '${inconclusiveAt.utcText()}'",
            ),
        ).isEqualTo(1)
        assertThat(
            longValue("select count(*) from invoices where user_id = ${fixture.userId}"),
        ).isEqualTo(1)
        assertThat(
            longValue("select count(*) from payments where user_id = ${fixture.userId}"),
        ).isEqualTo(1)
    }

    @Test
    fun `completed transaction replay repairs a stale expired projection while signed access is current`(): Unit =
        runBlocking {
            val fixture = fixture("expired-current-replay")
            val transaction = fixture.transaction()
            val invoice = ledger.recordVerifiedPayment(
                RecordVerifiedPaymentCommand(
                    fixture.userId,
                    fixture.product,
                    transaction,
                    null,
                    BillingEventSource.REVENUECAT_WEBHOOK,
                    "initial-payment-${fixture.suffix}",
                    fixture.now,
                ),
            )
            ledger.fulfill(invoice.id, fixture.now.plusSeconds(1))

            val staleSnapshotAt = fixture.now.plusSeconds(300)
            forceStaleSubscriptionProjection(
                fixture = fixture,
                transaction = transaction,
                pendingProductId = fixture.product.productId,
                accessStatus = "EXPIRED",
                renewalStatus = "NOT_APPLICABLE",
                lastProviderEventAt = staleSnapshotAt,
            )
            val subscriptionId = longValue(
                "select id from subscriptions where original_transaction_id = '${transaction.originalTransactionId}'",
            )
            ledger.applySubscriptionSnapshot(
                SubscriptionReconciliationClaim(
                    subscriptionId,
                    fixture.userId,
                    transaction.originalTransactionId,
                    fixture.appAccountToken,
                    1,
                ),
                RevenueCatCustomerSnapshot(
                    SubscriptionAccessStatus.EXPIRED,
                    SubscriptionRenewalStatus.NOT_APPLICABLE,
                    fixture.now.minusSeconds(86_400),
                    staleSnapshotAt,
                ),
                staleSnapshotAt,
            )
            assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER1")

            val replayed = ledger.recordVerifiedPayment(
                RecordVerifiedPaymentCommand(
                    fixture.userId,
                    fixture.product,
                    transaction,
                    null,
                    BillingEventSource.CLIENT,
                    "expired-current-replay-${fixture.suffix}",
                    staleSnapshotAt.plusSeconds(1),
                ),
            )

            assertThat(replayed.id).isEqualTo(invoice.id)
            val entitlement = requireNotNull(ledger.entitlementForUser(fixture.userId))
            assertThat(entitlement.tierCode).isEqualTo("TIER2")
            assertThat(entitlement.accessStatus).isEqualTo(SubscriptionAccessStatus.ACTIVE)
            assertThat(entitlement.renewalStatus).isEqualTo(SubscriptionRenewalStatus.NOT_APPLICABLE)
            assertThat(requireNotNull(quota.quotaStatusForUser(fixture.userId, staleSnapshotAt)).baseLimit)
                .isEqualTo(300)
        }

    @Test
    fun `completed transaction replay never revives access after signed expiry`(): Unit = runBlocking {
        val fixture = fixture("expired-stale-upgrade")
        val tier2Invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                fixture.transaction(),
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "tier2-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(tier2Invoice.id, fixture.now.plusSeconds(1))

        val tier3 = requireNotNull(
            ledger.enabledTierProduct("io.github.ghkdqhrbals.StudyMate.tier3.monthly"),
        )
        val tier3PurchasedAt = fixture.now.plusSeconds(100)
        val tier3Transaction = fixture.transaction().copy(
            transactionId = "tier3-expired-tx-${fixture.suffix}",
            productId = tier3.productId,
            priceMilliunits = 17_900_000,
            purchaseAt = tier3PurchasedAt,
            expiresAt = tier3PurchasedAt.plusSeconds(200),
            signedAt = tier3PurchasedAt,
        )
        val tier3Invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                tier3,
                tier3Transaction,
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "tier3-payment-${fixture.suffix}",
                tier3PurchasedAt,
            ),
        )
        ledger.fulfill(tier3Invoice.id, tier3PurchasedAt.plusSeconds(1))

        val expirationAt = tier3PurchasedAt.plusSeconds(300)
        forceStaleSubscriptionProjection(
            fixture = fixture,
            transaction = tier3Transaction,
            pendingProductId = tier3.productId,
            accessStatus = "EXPIRED",
            renewalStatus = "NOT_APPLICABLE",
            lastProviderEventAt = expirationAt,
        )
        assertThat(
            longValue(
                "select count(*) from invoices where id = ${tier3Invoice.id} and status = 'COMPLETED' " +
                    "and fulfilled_at is not null",
            ),
        ).isEqualTo(1)
        assertThat(
            longValue(
                "select count(*) from payments where invoice_id = ${tier3Invoice.id} and status = 'SETTLED'",
            ),
        ).isEqualTo(1)
        assertThat(
            longValue(
                "select count(*) from user_memberships where source_invoice_id = ${tier3Invoice.id} " +
                    "and status = 'ACTIVE'",
            ),
        ).isEqualTo(1)

        val replayed = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                tier3,
                tier3Transaction,
                null,
                BillingEventSource.CLIENT,
                "expired-transaction-replay-${fixture.suffix}",
                expirationAt.plusSeconds(1),
            ),
        )

        assertThat(replayed.id).isEqualTo(tier3Invoice.id)
        assertThat(ledger.entitlementForUser(fixture.userId)?.tierCode).isEqualTo("TIER1")
        assertThat(
            longValue(
                "select count(*) from subscriptions where original_transaction_id = " +
                    "'${tier3Transaction.originalTransactionId}' and access_status = 'EXPIRED' " +
                    "and product_id = '${fixture.product.productId}' and tier_code = 'TIER2'",
            ),
        ).isEqualTo(1)
        assertThat(
            longValue(
                "select count(*) from user_memberships where source_invoice_id = ${tier3Invoice.id} " +
                    "and status = 'ACTIVE'",
            ),
        ).isZero()
    }

    @Test
    fun `same-chain downgrade keeps the higher tier until renewal and then preserves lower tier usage`(): Unit = runBlocking {
        val fixture = fixture("same-chain-downgrade")
        val tier3 = requireNotNull(
            ledger.enabledTierProduct("io.github.ghkdqhrbals.StudyMate.tier3.monthly"),
        )
        val tier3Checkout = ledger.createPendingInvoice(
            fixture.userId,
            fixture.appAccountToken,
            tier3,
            "tier3-${fixture.suffix}",
            fixture.now,
        )
        val tier3Transaction = fixture.transaction().copy(
            productId = tier3.productId,
            productType = tier3.productType,
            priceMilliunits = 17_900_000,
        )
        val tier3Invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                tier3,
                tier3Transaction,
                tier3Checkout.invoiceNumber,
                BillingEventSource.CLIENT,
                "tier3-payment-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(tier3Invoice.id, fixture.now.plusSeconds(1))

        val committedKey = "downgrade-committed-${fixture.suffix}"
        assertThat(
            quota.reserveMonthlySystemQuestion(
                fixture.userId,
                fixture.now.plusSeconds(2),
                committedKey,
                committedKey,
                fixture.now.plusSeconds(2),
            ),
        ).isTrue()
        quota.commitMonthlySystemQuestion(committedKey, fixture.now.plusSeconds(3))
        val reservedKey = "downgrade-reserved-${fixture.suffix}"
        assertThat(
            quota.reserveMonthlySystemQuestion(
                fixture.userId,
                fixture.now.plusSeconds(4),
                reservedKey,
                reservedKey,
                fixture.now.plusSeconds(4),
            ),
        ).isTrue()
        ledger.adminAdjustQuota(
            fixture.userId,
            25,
            "support bonus before downgrade",
            "downgrade-bonus-${fixture.suffix}",
            fixture.now.plusSeconds(5),
        )
        val beforeDowngrade = requireNotNull(quota.quotaStatusForUser(fixture.userId, fixture.now.plusSeconds(6)))
        assertThat(beforeDowngrade.tierCode).isEqualTo("TIER3")
        assertThat(beforeDowngrade.baseLimit).isEqualTo(1_000)
        assertThat(beforeDowngrade.bonusLimit).isEqualTo(25)
        assertThat(beforeDowngrade.usedCount).isEqualTo(1)
        assertThat(beforeDowngrade.reservedCount).isEqualTo(1)

        val productChangeAt = fixture.now.plusSeconds(90)
        val productChangeEventId = "downgrade-product-change-${fixture.suffix}"
        createdRevenueCatEventIds += productChangeEventId
        val productChange = fixture.revenueCatLifecycleEvent(
            productChangeEventId,
            "PRODUCT_CHANGE",
            tier3Transaction.copy(productId = fixture.product.productId),
            productChangeAt,
            productOverride = fixture.product,
        )
        assertThat(ledger.recordRevenueCatEvent(productChange, productChangeAt)).isTrue()
        assertThat(ledger.applyRevenueCatEvent(productChange, productChangeAt)).isTrue()

        val downgradeRequestedAt = fixture.now.plusSeconds(100)
        val tier2Transaction = tier3Transaction.copy(
            transactionId = "tier2-downgrade-${fixture.suffix}",
            productId = fixture.product.productId,
            productType = fixture.product.productType,
            priceMilliunits = 7_900_000,
            purchaseAt = downgradeRequestedAt,
            expiresAt = downgradeRequestedAt.plusSeconds(2_592_000),
            signedAt = downgradeRequestedAt,
        )
        val tier2Invoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                tier2Transaction,
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "tier2-downgrade-payment-${fixture.suffix}",
                downgradeRequestedAt,
            ),
        )
        ledger.fulfill(tier2Invoice.id, downgradeRequestedAt.plusSeconds(1))

        val scheduledEntitlement = requireNotNull(ledger.entitlementForUser(fixture.userId))
        assertThat(scheduledEntitlement.tierCode).isEqualTo("TIER3")
        assertThat(scheduledEntitlement.productId).isEqualTo(tier3.productId)
        assertThat(scheduledEntitlement.pendingProductId).isEqualTo(fixture.product.productId)
        assertThat(scheduledEntitlement.expiresAt).isEqualTo(tier3Transaction.expiresAt)
        val scheduledQuota = requireNotNull(
            quota.quotaStatusForUser(fixture.userId, downgradeRequestedAt.plusSeconds(2)),
        )
        assertThat(scheduledQuota.tierCode).isEqualTo("TIER3")
        assertThat(scheduledQuota.baseLimit).isEqualTo(1_000)
        assertThat(scheduledQuota.usedCount).isEqualTo(1)
        assertThat(scheduledQuota.reservedCount).isEqualTo(1)
        assertThat(scheduledQuota.bonusLimit).isEqualTo(25)
        assertThat(scheduledQuota.periodStartedAt).isEqualTo(beforeDowngrade.periodStartedAt)
        assertThat(
            intValue("select committed_count from user_quota where user_id = ${fixture.userId}"),
        ).isEqualTo(1)
        assertThat(
            longValue(
                "select count(*) from subscriptions where original_transaction_id = " +
                    "'${tier3Transaction.originalTransactionId}' and product_id = '${tier3.productId}' " +
                    "and tier_code = 'TIER3' and pending_product_id = '${fixture.product.productId}'",
            ),
        ).isEqualTo(1)
        assertThat(
            longValue("select count(*) from user_quota_history where event_id = 'billing-tier-change:${tier2Invoice.id}'"),
        ).isZero()
        assertThat(
            longValue(
                "select count(*) from user_memberships where source_invoice_id = ${tier2Invoice.id} " +
                    "and status = 'ACTIVE'",
            ),
        ).isZero()

        ledger.fulfill(tier2Invoice.id, downgradeRequestedAt.plusSeconds(3))
        assertThat(
            longValue("select count(*) from user_quota_history where event_id = 'billing-tier-change:${tier2Invoice.id}'"),
        ).isZero()
        val replayedQuota = requireNotNull(
            quota.quotaStatusForUser(fixture.userId, downgradeRequestedAt.plusSeconds(4)),
        )
        assertThat(replayedQuota.tierCode).isEqualTo("TIER3")
        assertThat(replayedQuota.usedCount).isEqualTo(1)
        assertThat(replayedQuota.bonusLimit).isEqualTo(25)

        val renewalAt = requireNotNull(tier3Transaction.expiresAt)
        val renewalTransaction = tier2Transaction.copy(
            transactionId = "tier2-renewal-${fixture.suffix}",
            purchaseAt = renewalAt,
            expiresAt = renewalAt.plusSeconds(2_592_000),
            signedAt = renewalAt,
        )
        val renewalInvoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                renewalTransaction,
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "tier2-renewal-payment-${fixture.suffix}",
                renewalAt,
            ),
        )
        ledger.fulfill(renewalInvoice.id, renewalAt.plusSeconds(1))
        val renewedQuota = requireNotNull(quota.quotaStatusForUser(fixture.userId, renewalAt.plusSeconds(2)))
        assertThat(renewedQuota.tierCode).isEqualTo("TIER2")
        assertThat(renewedQuota.periodStartedAt).isEqualTo(beforeDowngrade.periodStartedAt)
        assertThat(renewedQuota.usedCount).isEqualTo(1)
        assertThat(renewedQuota.reservedCount).isEqualTo(1)
        assertThat(renewedQuota.bonusLimit).isEqualTo(25)
        assertThat(renewedQuota.monthlyQuestionLimit).isEqualTo(325)
        assertThat(renewedQuota.policyVersion).isEqualTo(MonthlyQuestionQuotaPolicy.VERSION)
        assertThat(
            intValue("select committed_count from user_quota where user_id = ${fixture.userId}"),
        ).isEqualTo(1)
        val renewedEntitlement = requireNotNull(ledger.entitlementForUser(fixture.userId))
        assertThat(renewedEntitlement.tierCode).isEqualTo("TIER2")
        assertThat(renewedEntitlement.productId).isEqualTo(fixture.product.productId)
        assertThat(renewedEntitlement.pendingProductId).isNull()
        assertThat(
            longValue(
                "select count(*) from subscriptions where original_transaction_id = " +
                    "'${tier3Transaction.originalTransactionId}' and product_id = '${fixture.product.productId}' " +
                    "and tier_code = 'TIER2' and pending_product_id is null",
            ),
        ).isEqualTo(1)
        assertThat(
            longValue(
                "select count(*) from user_quota_history " +
                    "where event_id = 'billing-tier-change:${renewalInvoice.id}' and event_type = 'PLAN_DOWNGRADED'",
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `late earlier initial purchase corrects the first paid anchor without losing usage`(): Unit = runBlocking {
        val fixture = fixture("late-first-paid")
        val initialInvoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                fixture.transaction(),
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "initial-${fixture.suffix}",
                fixture.now,
            ),
        )
        ledger.fulfill(initialInvoice.id, fixture.now.plusSeconds(1))
        val quotaKey = "late-anchor-quota-${fixture.suffix}"
        assertThat(quota.reserveMonthlySystemQuestion(fixture.userId, fixture.now, quotaKey, quotaKey, fixture.now))
            .isTrue()
        quota.commitMonthlySystemQuestion(quotaKey, fixture.now.plusSeconds(2))

        val earlierPurchaseAt = fixture.now.minusSeconds(86_400)
        val lateTransaction = fixture.transaction().copy(
            transactionId = "late-older-${fixture.suffix}",
            originalTransactionId = "late-older-original-${fixture.suffix}",
            purchaseAt = earlierPurchaseAt,
            originalPurchaseAt = earlierPurchaseAt,
            expiresAt = earlierPurchaseAt.plusSeconds(2_592_000),
            signedAt = fixture.now.plusSeconds(10),
        )
        val lateInvoice = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                fixture.userId,
                fixture.product,
                lateTransaction,
                null,
                BillingEventSource.REVENUECAT_WEBHOOK,
                "late-older-payment-${fixture.suffix}",
                fixture.now.plusSeconds(10),
            ),
        )
        ledger.fulfill(lateInvoice.id, fixture.now.plusSeconds(11))

        val status = requireNotNull(quota.quotaStatusForUser(fixture.userId, fixture.now.plusSeconds(12)))
        assertThat(status.anchorType).isEqualTo("FIRST_PAID")
        assertThat(status.periodStartedAt).isEqualTo(earlierPurchaseAt)
        assertThat(status.usedCount).isEqualTo(1)
        assertThat(
            database.sql("select first_paid_at from user_quota where user_id = :userId")
                .bind("userId", fixture.userId)
                .map { row -> row.get("first_paid_at", java.time.LocalDateTime::class.java)!! }
                .one().awaitSingle(),
        ).isEqualTo(java.time.LocalDateTime.ofInstant(earlierPurchaseAt, java.time.ZoneOffset.UTC))
    }

    private suspend fun fixture(
        label: String,
        now: Instant = Instant.parse("2032-08-04T00:00:00Z"),
    ): Fixture {
        val suffix = UUID.randomUUID().toString()
        val userId = insertUser("$label-$suffix", now.minusSeconds(60))
        createdUserIds += userId
        val token = ledger.findOrCreateAppAccountToken(userId, now.minusSeconds(30))
        val product = requireNotNull(
            ledger.enabledTierProduct("io.github.ghkdqhrbals.StudyMate.tier2.monthly"),
        )
        return Fixture(suffix, userId, token, product, "checkout-$suffix", now)
    }

    private fun Fixture.revenueCatLifecycleEvent(
        eventId: String,
        eventType: String,
        transaction: VerifiedAppleTransaction,
        eventAt: Instant,
        cancelReason: String? = null,
        productOverride: BillingTierProduct = product,
    ) = VerifiedRevenueCatEvent(
        eventId = eventId,
        eventType = eventType,
        appUserId = appAccountToken.toString(),
        originalAppUserId = appAccountToken.toString(),
        aliases = emptyList(),
        store = "APP_STORE",
        productId = productOverride.productId,
        transactionId = transaction.transactionId,
        originalTransactionId = transaction.originalTransactionId,
        environment = BillingEnvironment.SANDBOX,
        priceMilliunits = transaction.priceMilliunits,
        currency = transaction.currency,
        purchasedAt = transaction.purchaseAt,
        expiresAt = transaction.expiresAt,
        eventAt = eventAt,
        cancelReason = cancelReason,
        expirationReason = null,
        signedPayloadSha256 = eventId.padEnd(64, 'f').take(64),
    )

    private suspend fun insertUser(suffix: String, createdAt: Instant): Long =
        database.sql(
            """
            insert into users (
                provider, provider_id, password_hash, status, email, display_name,
                created_at, updated_at
            ) values (
                'EMAIL', :providerId, 'hash', 'ACTIVE', :email, :displayName,
                :createdAt, :createdAt
            )
            """.trimIndent(),
        )
            .bind("providerId", "billing-$suffix")
            .bind("email", "$suffix@example.com")
            .bind("displayName", "Billing-$suffix")
            .bind("createdAt", createdAt)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    private suspend fun eventTypes(invoiceId: Long): List<String> =
        database.sql(
            "select event_type from invoice_events where invoice_id = :invoiceId order by sequence_number",
        )
            .bind("invoiceId", invoiceId)
            .map { row -> row.get("event_type", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    private suspend fun longValue(sql: String): Long =
        database.sql(sql)
            .map { row -> row.get(0, java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    private suspend fun intValue(sql: String): Int =
        database.sql(sql)
            .map { row -> row.get(0, Integer::class.java)!!.toInt() }
            .one()
            .awaitSingle()

    private suspend fun execute(sql: String) {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun expireSubscription(
        userId: Long,
        originalTransactionId: String,
        token: UUID,
        now: Instant,
    ) {
        val subscriptionId = longValue(
            "select id from subscriptions where original_transaction_id = '$originalTransactionId'",
        )
        val claim = SubscriptionReconciliationClaim(subscriptionId, userId, originalTransactionId, token, 1)
        ledger.applySubscriptionSnapshot(
            claim,
            RevenueCatCustomerSnapshot(
                SubscriptionAccessStatus.EXPIRED,
                SubscriptionRenewalStatus.NOT_APPLICABLE,
                now,
                now,
            ),
            now,
        )
    }

    private suspend fun forceStaleSubscriptionProjection(
        fixture: Fixture,
        transaction: VerifiedAppleTransaction,
        pendingProductId: String,
        accessStatus: String,
        renewalStatus: String,
        lastProviderEventAt: Instant,
    ) {
        database.sql(
            """
            update subscriptions
            set latest_transaction_id = :transactionId,
                product_id = :productId,
                tier_code = 'TIER2',
                access_status = :accessStatus,
                renewal_status = :renewalStatus,
                pending_product_id = :pendingProductId,
                pending_product_event_at = :pendingProductEventAt,
                last_provider_event_at = :lastProviderEventAt,
                updated_at = :lastProviderEventAt
            where original_transaction_id = :originalTransactionId
            """.trimIndent(),
        ).bind("transactionId", transaction.transactionId)
            .bind("productId", fixture.product.productId)
            .bind("accessStatus", accessStatus)
            .bind("renewalStatus", renewalStatus)
            .bind("pendingProductId", pendingProductId)
            .bind("pendingProductEventAt", transaction.purchaseAt)
            .bind("lastProviderEventAt", lastProviderEventAt)
            .bind("originalTransactionId", transaction.originalTransactionId)
            .fetch().rowsUpdated().awaitSingle()
        database.sql(
            """
            update user_entitlement_projection
            set tier_code = :tierCode,
                source = :source,
                access_status = :accessStatus,
                renewal_status = :renewalStatus,
                product_id = :productId,
                pending_product_id = :pendingProductId,
                projected_at = :projectedAt,
                version = version + 1
            where user_id = :userId
            """.trimIndent(),
        ).bind("tierCode", if (accessStatus == "EXPIRED") "TIER1" else "TIER2")
            .bind("source", if (accessStatus == "EXPIRED") "FREE" else "APP_STORE")
            .bind("accessStatus", if (accessStatus == "EXPIRED") "ACTIVE" else accessStatus)
            .bind("renewalStatus", if (accessStatus == "EXPIRED") "NOT_APPLICABLE" else renewalStatus)
            .bind("productId", fixture.product.productId)
            .bind("pendingProductId", pendingProductId)
            .bind("projectedAt", lastProviderEventAt)
            .bind("userId", fixture.userId)
            .fetch().rowsUpdated().awaitSingle()
    }

    private fun Instant.utcText(): String =
        java.time.LocalDateTime.ofInstant(this, java.time.ZoneOffset.UTC).toString().replace('T', ' ')

    private data class Fixture(
        val suffix: String,
        val userId: Long,
        val appAccountToken: UUID,
        val product: BillingTierProduct,
        val idempotencyKey: String,
        val now: Instant,
    ) {
        fun transaction() = VerifiedAppleTransaction(
            transactionId = "tx-$suffix",
            originalTransactionId = "tx-$suffix",
            appTransactionId = null,
            webOrderLineItemId = null,
            appAccountToken = appAccountToken,
            productId = product.productId,
            productType = product.productType,
            environment = BillingEnvironment.SANDBOX,
            quantity = 1,
            priceMilliunits = 7_900_000,
            currency = "KRW",
            purchaseAt = now,
            originalPurchaseAt = now,
            expiresAt = now.plusSeconds(2_592_000),
            revocationAt = null,
            revocationReason = null,
            signedAt = now,
            signedPayloadSha256 = "a".repeat(64),
        )
    }
}
