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
            database.sql("delete from revenuecat_billing_events where event_id = :eventId")
                .bind("eventId", eventId).fetch().rowsUpdated().awaitSingle()
        }
        createdRevenueCatEventIds.clear()
        createdUserIds.asReversed().forEach { userId ->
            execute("delete from quota_ledger where user_id = $userId")
            execute("delete from quota_reservations where user_id = $userId")
            execute("delete from quota_periods where user_id = $userId")
            execute("delete from quota_accounts where user_id = $userId")
            execute("delete from user_entitlement_projection where user_id = $userId")
            execute("delete from subscription_events where user_id = $userId")
            execute("delete from subscriptions where user_id = $userId")
            execute("delete from billing_actions where user_id = $userId")
            execute("delete from billing_jobs where invoice_id in (select id from invoices where user_id = $userId)")
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
            database.sql("select processing_status from revenuecat_billing_events where event_id = :eventId")
                .bind("eventId", eventId)
                .map { row -> row.get("processing_status", String::class.java)!! }
                .one().awaitSingle(),
        ).isEqualTo("IGNORED")
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
            longValue("select count(*) from billing_jobs where invoice_id = ${recorded.id} and status = 'COMPLETED'"),
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
                        source = BillingEventSource.REVENUECAT_WEBHOOK,
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
            longValue("select count(*) from billing_jobs where invoice_id = ${recorded.id} and status = 'COMPLETED'"),
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
    fun `first paid purchase and fast renewal preserve usage and the lifetime quota anchor`(): Unit = runBlocking {
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
        assertThat(renewedStatus.periodStartedAt).isEqualTo(fixture.now)
        assertThat(
            database.sql("select first_paid_at from quota_accounts where user_id = :userId")
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

        val quotaKey = "priority-quota-${fixture.suffix}"
        assertThat(quota.reserveMonthlySystemQuestion(fixture.userId, fixture.now, quotaKey, quotaKey, fixture.now))
            .isTrue()
        quota.commitMonthlySystemQuestion(quotaKey, fixture.now.plusSeconds(2))

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
            expiresAt = tier3PurchasedAt.plusSeconds(2_592_000),
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
    fun `late older purchase moves the lifetime anchor backward without losing usage`(): Unit = runBlocking {
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
            database.sql("select first_paid_at from quota_accounts where user_id = :userId")
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
    ) = VerifiedRevenueCatEvent(
        eventId = eventId,
        eventType = eventType,
        appUserId = appAccountToken.toString(),
        originalAppUserId = appAccountToken.toString(),
        aliases = emptyList(),
        store = "APP_STORE",
        productId = product.productId,
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
