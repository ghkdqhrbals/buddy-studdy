package com.buddystudy.backend

import com.buddystudy.backend.billing.application.model.ApplyAppleNotificationCommand
import com.buddystudy.backend.billing.application.model.BillingTierProduct
import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.VerifiedAppleNotification
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.billing.domain.BillingActionStatus
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentStatus
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
    @Autowired lateinit var database: DatabaseClient
    private val createdUserIds = mutableListOf<Long>()

    @AfterEach
    fun cleanUpBillingFixtures(): Unit = runBlocking {
        createdUserIds.asReversed().forEach { userId ->
            execute("delete from billing_actions where user_id = $userId")
            execute("delete from billing_jobs where invoice_id in (select id from invoices where user_id = $userId)")
            execute("delete from payments_history where invoice_id in (select id from invoices where user_id = $userId)")
            execute("delete from invoice_events where invoice_id in (select id from invoices where user_id = $userId)")
            execute("delete from payments where user_id = $userId")
            execute("delete from user_memberships where user_id = $userId")
            execute("delete from invoices where user_id = $userId")
            execute("delete from apple_billing_accounts where user_id = $userId")
            execute("delete from users where id = $userId")
        }
        createdUserIds.clear()
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
        assertThat(recorded.status).isEqualTo(InvoiceStatus.WAITING)
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

    private suspend fun fixture(label: String): Fixture {
        val suffix = UUID.randomUUID().toString()
        val now = Instant.parse("2032-08-04T00:00:00Z")
        val userId = insertUser("$label-$suffix", now.minusSeconds(60))
        createdUserIds += userId
        val token = ledger.findOrCreateAppAccountToken(userId, now.minusSeconds(30))
        val product = requireNotNull(
            ledger.enabledTierProduct("io.github.ghkdqhrbals.StudyMate.tier2.monthly"),
        )
        return Fixture(suffix, userId, token, product, "checkout-$suffix", now)
    }

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
