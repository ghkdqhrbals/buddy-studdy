package com.buddystudy.backend.billing.adapter.outbound.persistence

import com.buddystudy.backend.billing.application.model.ApplyAppleNotificationCommand
import com.buddystudy.backend.billing.application.model.AdminBillingInvoice
import com.buddystudy.backend.billing.application.model.AdminBillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.AdminBillingInvoicePage
import com.buddystudy.backend.billing.application.model.AdminQuotaAdjustment
import com.buddystudy.backend.billing.application.model.AdminBillingReconcileRequest
import com.buddystudy.backend.billing.application.model.AdminBillingTimelineEntry
import com.buddystudy.backend.billing.application.model.AdminUserBillingTimeline
import com.buddystudy.backend.billing.application.model.SubscriptionReconciliationClaim
import com.buddystudy.backend.billing.application.model.RevenueCatCustomerSnapshot
import com.buddystudy.backend.billing.application.model.BillingAction
import com.buddystudy.backend.billing.application.model.BillingClientAction
import com.buddystudy.backend.billing.application.model.BillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.BillingInvoiceEvent
import com.buddystudy.backend.billing.application.model.BillingInvoicePage
import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.BillingFulfillmentJobClaim
import com.buddystudy.backend.billing.application.model.BillingTierProduct
import com.buddystudy.backend.billing.application.model.BillingEntitlementProjection
import com.buddystudy.backend.billing.application.model.PaymentHistoryEntry
import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.VerifiedAppleNotification
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import com.buddystudy.backend.common.application.quota.MonthlyQuestionQuotaPolicy
import com.buddystudy.billing.domain.BillingActionStatus
import com.buddystudy.billing.domain.BillingActionType
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.BillingJobType
import com.buddystudy.billing.domain.BillingProductType
import com.buddystudy.billing.domain.BillingReceiptStatus
import com.buddystudy.billing.domain.InvoiceEventType
import com.buddystudy.billing.domain.InvoiceStateMachine
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentHistoryEventType
import com.buddystudy.billing.domain.PaymentStatus
import com.buddystudy.billing.domain.EntitlementSource
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import com.buddystudy.billing.domain.SubscriptionRenewalStatus
import com.buddystudy.billing.domain.entity.BillingJobEntity
import com.buddystudy.billing.domain.entity.InvoiceEntity
import com.buddystudy.billing.domain.entity.InvoiceEventEntity
import com.buddystudy.billing.domain.entity.PaymentEntity
import com.buddystudy.billing.domain.entity.PaymentHistoryEntity
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class BillingLedgerPersistenceAdapter(
    private val database: DatabaseClient,
) : BillingLedgerPort {
    @Transactional
    override suspend fun findOrCreateAppAccountToken(userId: Long, now: Instant): UUID {
        existingToken(userId)?.let { return it }
        val token = legacyToken(userId) ?: UUID.randomUUID()
        database.sql(
            """
            insert into apple_billing_accounts (user_id, app_account_token, created_at, updated_at)
            values (:userId, :token, :now, :now)
            on duplicate key update updated_at = updated_at
            """.trimIndent(),
        ).bind("userId", userId)
            .bind("token", token.toString().lowercase())
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        database.sql(
            """
            insert into billing_accounts (user_id, app_account_token, status, created_at, updated_at)
            values (:userId, :token, 'ACTIVE', :now, :now)
            on duplicate key update status = 'ACTIVE', updated_at = :now
            """.trimIndent(),
        ).bind("userId", userId).bind("token", token.toString().lowercase()).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        return existingToken(userId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Unable to create an Apple billing account token.")
    }

    override suspend fun userIdForAppAccountToken(appAccountToken: UUID): Long? =
        database.sql(
            "select user_id from billing_accounts where app_account_token = :token and status = 'ACTIVE'",
        ).bind("token", appAccountToken.toString().lowercase())
            .map { row, _ -> row.long("user_id") }
            .one().awaitSingleOrNull()

    override suspend fun enabledTierProducts(): List<BillingTierProduct> =
        database.sql(
            """
            select p.tier_code, t.description, t.monthly_question_limit, p.product_id,
                   p.product_type, p.billing_period, p.sort_order
            from membership_tier_products p
            join user_membership_tiers t on t.tier_code = p.tier_code
            where p.provider = 'APPLE' and p.enabled = true
            order by p.sort_order, p.tier_code
            """.trimIndent(),
        ).map { row, _ -> row.tierProduct() }
            .all().collectList().awaitSingle()

    override suspend fun enabledTierProduct(productId: String): BillingTierProduct? =
        tierProduct(productId, enabledOnly = true)

    override suspend fun tierProduct(productId: String): BillingTierProduct? =
        tierProduct(productId, enabledOnly = false)

    private suspend fun tierProduct(productId: String, enabledOnly: Boolean): BillingTierProduct? =
        database.sql(
            """
            select p.tier_code, t.description, t.monthly_question_limit, p.product_id,
                   p.product_type, p.billing_period, p.sort_order
            from membership_tier_products p
            join user_membership_tiers t on t.tier_code = p.tier_code
            where p.provider = 'APPLE' and p.product_id = :productId
              and (:enabledOnly = false or p.enabled = true)
            """.trimIndent(),
        ).bind("productId", productId).bind("enabledOnly", enabledOnly)
            .map { row, _ -> row.tierProduct() }
            .one().awaitSingleOrNull()

    override suspend fun entitlementForUser(userId: Long): BillingEntitlementProjection? =
        database.sql(
            """
            select tier_code, source, access_status, renewal_status, product_id, started_at,
                   expires_at, will_renew, pending_product_id, projected_at
            from user_entitlement_projection where user_id = :userId
            """.trimIndent(),
        ).bind("userId", userId).map { row, _ ->
            BillingEntitlementProjection(
                tierCode = row.string("tier_code"),
                source = EntitlementSource.valueOf(row.string("source")),
                accessStatus = SubscriptionAccessStatus.valueOf(row.string("access_status")),
                renewalStatus = SubscriptionRenewalStatus.valueOf(row.string("renewal_status")),
                productId = row.nullableString("product_id"),
                startedAt = row.nullableInstant("started_at"),
                expiresAt = row.nullableInstant("expires_at"),
                willRenew = row.boolean("will_renew"),
                pendingProductId = row.nullableString("pending_product_id"),
                synchronizedAt = row.instant("projected_at"),
            )
        }.one().awaitSingleOrNull()

    @Transactional
    override suspend fun createPendingInvoice(
        userId: Long,
        appAccountToken: UUID,
        tierProduct: BillingTierProduct,
        idempotencyKey: String,
        now: Instant,
    ): BillingInvoiceSummary {
        lockAndValidateAccount(userId, appAccountToken)
        existingCheckoutInvoice(userId, idempotencyKey)?.let { existing ->
            if (existing.productId != tierProduct.productId) {
                throw billingFailure(
                    ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                    "The checkout idempotency key is already used for another product.",
                    HttpStatus.CONFLICT,
                )
            }
            return requireInvoiceSummary(existing.invoiceId)
        }
        val invoiceId = insertPendingInvoice(
            userId = userId,
            tierProduct = tierProduct,
            appAccountToken = appAccountToken,
            source = BillingEventSource.CLIENT,
            actorUserId = userId,
            correlationId = idempotencyKey,
            now = now,
        )
        return requireInvoiceSummary(invoiceId)
    }

    @Transactional
    override suspend fun abandonPendingInvoice(
        userId: Long,
        invoiceNumber: UUID,
        now: Instant,
    ): BillingInvoiceSummary {
        val invoice = lockInvoiceByNumber(invoiceNumber)
            ?.takeIf { it.userId == userId }
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.", HttpStatus.NOT_FOUND)
        if (invoice.status == InvoiceStatus.FAILED && lockPaymentByInvoice(invoice.id) == null) {
            return requireInvoiceSummary(invoice.id)
        }
        if (invoice.type != InvoiceType.NORMAL || invoice.status != InvoiceStatus.WAITING || lockPaymentByInvoice(invoice.id) != null) {
            throw billingFailure(
                ApiErrorCode.BILLING_ACTION_NOT_ALLOWED,
                "Only a pending checkout can be abandoned.",
                HttpStatus.CONFLICT,
            )
        }
        appendInvoiceEvent(
            invoice.id,
            "checkout-abandoned:${invoice.invoiceNumber}",
            InvoiceEventType.CANCELLED,
            BillingEventSource.CLIENT,
            userId,
            "StoreKit purchase sheet was cancelled before a transaction was created.",
            now,
        )
        return requireInvoiceSummary(invoice.id)
    }

    @Transactional
    override suspend fun failPendingInvoiceValidation(
        userId: Long,
        invoiceNumber: UUID,
        source: BillingEventSource,
        reason: String,
        now: Instant,
    ): BillingInvoiceSummary {
        val invoice = lockInvoiceByNumber(invoiceNumber)
            ?.takeIf { it.userId == userId }
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.", HttpStatus.NOT_FOUND)
        val payment = lockPaymentByInvoice(invoice.id)
        if (invoice.status == InvoiceStatus.FAILED || payment != null) {
            return requireInvoiceSummary(invoice.id)
        }
        if (invoice.type != InvoiceType.NORMAL || invoice.status != InvoiceStatus.WAITING) {
            throw billingFailure(
                ApiErrorCode.BILLING_ACTION_NOT_ALLOWED,
                "Only a prepared invoice can record a payment validation failure.",
                HttpStatus.CONFLICT,
            )
        }
        appendInvoiceEvent(
            invoiceId = invoice.id,
            eventId = "invoice-payment-validation-failed:${invoice.invoiceNumber}",
            eventType = InvoiceEventType.PAYMENT_VALIDATION_FAILED,
            source = source,
            actorUserId = userId.takeIf { source == BillingEventSource.CLIENT },
            reason = reason,
            occurredAt = now,
        )
        return requireInvoiceSummary(invoice.id)
    }

    @Transactional
    override suspend fun expirePendingCheckouts(expiredBefore: Instant, now: Instant, limit: Int): Int {
        val invoiceIds = database.sql(
            """
            select i.id
            from invoices i
            where i.type = 'NORMAL'
              and i.status = 'WAITING'
              and i.created_at <= :expiredBefore
              and not exists (
                  select 1
                  from payments p
                  where p.invoice_id = i.id
              )
            order by i.created_at, i.id
            limit :limit
            for update skip locked
            """.trimIndent(),
        ).bind("expiredBefore", expiredBefore.utc())
            .bind("limit", limit.coerceIn(1, 100))
            .map { row, _ -> row.long("id") }
            .all().collectList().awaitSingle()

        invoiceIds.forEach { invoiceId ->
            val invoice = lockInvoice(invoiceId)
                ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.", HttpStatus.NOT_FOUND)
            appendInvoiceEvent(
                invoiceId = invoice.id,
                eventId = "checkout-expired:${invoice.invoiceNumber}",
                eventType = InvoiceEventType.CANCELLED,
                source = BillingEventSource.SYSTEM,
                actorUserId = null,
                reason = "Checkout automatically cancelled after waiting 10 minutes without verified payment.",
                occurredAt = now,
            )
        }
        return invoiceIds.size
    }

    @Transactional
    override suspend fun recordVerifiedPayment(command: RecordVerifiedPaymentCommand): BillingInvoiceSummary {
        lockAndValidateAccount(command.userId, command.transaction.appAccountToken)

        existingInvoiceForTransaction(command.transaction.transactionId)?.let { existing ->
            if (existing.productId != command.tierProduct.productId) {
                throw billingFailure(
                    ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                    "The App Store transaction is already attached to another invoice.",
                    HttpStatus.CONFLICT,
                )
            }
            if (existing.userId != command.userId) {
                if (!command.authoritativeOwnershipTransfer) {
                    throw billingFailure(
                        ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                        "The App Store transaction is already attached to another invoice.",
                        HttpStatus.CONFLICT,
                    )
                }
                transferVerifiedSubscriptionOwnership(existing, command)
            }
            val requestedInvoiceNumber = command.invoiceNumber
            if (requestedInvoiceNumber != null && existing.invoiceNumber != requestedInvoiceNumber) {
                val duplicateCheckout = lockInvoiceByNumber(requestedInvoiceNumber)
                    ?: throw billingFailure(
                        ApiErrorCode.RESOURCE_NOT_FOUND,
                        "Pending invoice not found.",
                        HttpStatus.NOT_FOUND,
                    )
                validatePendingInvoice(duplicateCheckout, command)
                appendInvoiceEvent(
                    invoiceId = duplicateCheckout.id,
                    eventId = "checkout-reconciled:${duplicateCheckout.invoiceNumber}:${command.transaction.transactionId}",
                    eventType = InvoiceEventType.CANCELLED,
                    source = command.source,
                    actorUserId = command.userId,
                    reason = "Checkout superseded because the verified transaction was already applied to invoice ${existing.invoiceNumber}.",
                    occurredAt = command.occurredAt,
                )
            }
            return loadInvoiceSummary(existing.invoiceId)
                ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Existing payment invoice is missing.")
        }

        validateProductMapping(command)
        val now = command.occurredAt
        val requestedInvoice = command.invoiceNumber?.let { invoiceNumber ->
            lockInvoiceByNumber(invoiceNumber)
                ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Pending invoice not found.", HttpStatus.NOT_FOUND)
        }
        if (requestedInvoice != null) validatePendingInvoice(requestedInvoice, command)
        val fallbackInvoice = if (requestedInvoice == null && isInitialPurchase(command.transaction)) {
            lockLatestPendingInvoice(command.userId, command.tierProduct.productId)
        } else {
            null
        }
        val invoiceId = (requestedInvoice ?: fallbackInvoice)?.id ?: insertPendingInvoice(
            userId = command.userId,
            tierProduct = command.tierProduct,
            appAccountToken = command.transaction.appAccountToken,
            source = command.source,
            actorUserId = command.userId.takeIf { command.source == BillingEventSource.CLIENT },
            correlationId = null,
            now = now,
        )
        updatePendingInvoiceFromTransaction(invoiceId, command.transaction, now)
        val paymentId = insertPayment(invoiceId, command)
        insertPaymentHistory(
            paymentId = paymentId,
            invoiceId = invoiceId,
            eventId = command.eventId,
            eventType = PaymentHistoryEventType.VERIFIED,
            source = command.source,
            fromStatus = null,
            toStatus = PaymentStatus.VERIFIED,
            providerNotificationUUID = null,
            reason = null,
            occurredAt = now,
        )
        appendInvoiceEvent(
            invoiceId = invoiceId,
            eventId = "invoice-payment-verified:${command.transaction.transactionId}",
            eventType = InvoiceEventType.PAYMENT_VERIFIED,
            source = command.source,
            actorUserId = command.userId,
            reason = null,
            occurredAt = now,
        )
        val recordedInvoice = lockInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Recorded invoice could not be locked.")
        val recordedPayment = lockPayment(paymentId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Recorded payment could not be locked.")
        upsertActiveSubscription(recordedInvoice, recordedPayment, recordedPayment.purchaseAt, now)
        insertBillingJob(invoiceId, paymentId, BillingJobType.FULFILLMENT, now)
        return loadInvoiceSummary(invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Created invoice could not be read.")
    }

    @Transactional
    override suspend fun fulfill(invoiceId: Long, now: Instant): BillingInvoiceSummary {
        val payment = lockPaymentByInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice payment is missing.", HttpStatus.NOT_FOUND)
        val locked = lockInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.", HttpStatus.NOT_FOUND)
        if (locked.status == InvoiceStatus.COMPLETED && hasInvoiceEvent(invoiceId, InvoiceEventType.FULFILLED)) {
            if (
                payment.status !in TERMINAL_ENTITLEMENT_DENIED_PAYMENT_STATES &&
                subscriptionOwnedBy(payment.providerOriginalTransactionId, locked.userId)
            ) {
                grantMembership(locked, payment, payment.purchaseAt, now)
            }
            return requireInvoiceSummary(invoiceId)
        }
        if (locked.type != InvoiceType.NORMAL || locked.status != InvoiceStatus.COMPLETED) {
            throw billingFailure(
                ApiErrorCode.BILLING_ACTION_NOT_ALLOWED,
                "Invoice cannot be fulfilled from ${locked.status}.",
                HttpStatus.CONFLICT,
            )
        }
        if (payment.status in TERMINAL_ENTITLEMENT_DENIED_PAYMENT_STATES) {
            if (!hasInvoiceEvent(invoiceId, InvoiceEventType.FULFILLMENT_FAILED)) {
                appendInvoiceEvent(
                    invoiceId,
                    "invoice-fulfillment-suppressed:${locked.invoiceNumber}:${payment.status.name.lowercase()}",
                    InvoiceEventType.FULFILLMENT_FAILED,
                    BillingEventSource.SYSTEM,
                    null,
                    "Entitlement fulfillment was suppressed because payment is ${payment.status}.",
                    now,
                )
            }
            completeFulfillmentJob(invoiceId, now)
            return requireInvoiceSummary(invoiceId)
        }
        if (!hasInvoiceEvent(invoiceId, InvoiceEventType.FULFILLMENT_STARTED)) {
            appendInvoiceEvent(
                invoiceId,
                "invoice-fulfillment-started:${locked.invoiceNumber}",
                InvoiceEventType.FULFILLMENT_STARTED,
                BillingEventSource.SYSTEM,
                null,
                null,
                now,
            )
        }

        grantMembership(locked, payment, payment.purchaseAt, now)
        if (payment.status == PaymentStatus.VERIFIED) {
            transitionPayment(
                payment,
                PaymentStatus.SETTLED,
                PaymentHistoryEventType.SETTLED,
                "payment-settled:${payment.providerTransactionId}",
                BillingEventSource.SYSTEM,
                null,
                null,
                now,
            )
        }
        appendInvoiceEvent(
            invoiceId,
            "invoice-fulfilled:${locked.invoiceNumber}",
            InvoiceEventType.FULFILLED,
            BillingEventSource.SYSTEM,
            null,
            null,
            now,
        )
        completeFulfillmentJob(invoiceId, now)
        return requireInvoiceSummary(invoiceId)
    }

    private suspend fun completeFulfillmentJob(invoiceId: Long, now: Instant) {
        database.sql(
            """
            update billing_fulfillment_outbox
            set status = 'COMPLETED', completed_at = :now, claimed_at = null, claim_token = null,
                last_error = null, updated_at = :now
            where invoice_id = :invoiceId and job_type = 'FULFILLMENT'
            """.trimIndent(),
        ).bind("invoiceId", invoiceId).bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun requireCompensation(invoiceId: Long, reason: String, now: Instant): BillingInvoiceSummary {
        val locked = lockInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.", HttpStatus.NOT_FOUND)
        if (locked.type != InvoiceType.NORMAL || locked.status != InvoiceStatus.COMPLETED) {
            return requireInvoiceSummary(invoiceId)
        }
        appendInvoiceEvent(
            invoiceId,
            "invoice-fulfillment-failed:${locked.invoiceNumber}",
            InvoiceEventType.FULFILLMENT_FAILED,
            BillingEventSource.SYSTEM,
            null,
            reason.take(1000),
            now,
        )
        database.sql(
            """
            update billing_fulfillment_outbox
            set status = 'FAILED', attempts = least(attempts + 1, max_attempts), last_error = :reason,
                claimed_at = null, claim_token = null, updated_at = :now
            where invoice_id = :invoiceId and job_type = 'FULFILLMENT'
            """.trimIndent(),
        ).bind("invoiceId", invoiceId).bind("reason", reason.take(4000)).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        // A verified Apple charge is never rolled back because entitlement projection failed.
        // The immutable invoice stays COMPLETED and the failed projection job remains visible for operations.
        return requireInvoiceSummary(invoiceId)
    }

    @Transactional
    override suspend fun claimDueFulfillmentJobs(
        now: Instant,
        staleBefore: Instant,
        limit: Int,
    ): List<BillingFulfillmentJobClaim> {
        val claimToken = UUID.randomUUID()
        val claims = database.sql(
            """
            select *
            from billing_fulfillment_outbox
            where job_type = 'FULFILLMENT'
              and (
                (status = 'PENDING' and next_attempt_at <= :now)
                or (status = 'PROCESSING' and claimed_at <= :staleBefore)
              )
            order by next_attempt_at, id
            limit :limit
            for update skip locked
            """.trimIndent(),
        ).bind("now", now.utc()).bind("staleBefore", staleBefore.utc()).bind("limit", limit.coerceIn(1, 100))
            .map { row, _ ->
                val job = row.billingJobEntity()
                BillingFulfillmentJobClaim(
                    jobId = job.id,
                    invoiceId = job.invoiceId,
                    attempts = job.attempts,
                    maxAttempts = job.maxAttempts,
                    claimToken = claimToken,
                )
            }.all().collectList().awaitSingle()

        for (claim in claims) {
            database.sql(
                """
                update billing_fulfillment_outbox
                set status = 'PROCESSING', claimed_at = :now, claim_token = :claimToken,
                    updated_at = :now
                where id = :jobId
                """.trimIndent(),
            ).bind("now", now.utc()).bind("claimToken", claimToken.toString()).bind("jobId", claim.jobId)
                .fetch().rowsUpdated().awaitSingle()
        }
        return claims
    }

    @Transactional
    override suspend fun rescheduleFulfillmentJob(
        claim: BillingFulfillmentJobClaim,
        error: String,
        nextAttemptAt: Instant,
        now: Instant,
    ) {
        database.sql(
            """
            update billing_fulfillment_outbox
            set status = 'PENDING', attempts = least(attempts + 1, max_attempts),
                next_attempt_at = :nextAttemptAt, claimed_at = null, claim_token = null,
                last_error = :error, updated_at = :now
            where id = :jobId and status = 'PROCESSING' and claim_token = :claimToken
            """.trimIndent(),
        ).bind("nextAttemptAt", nextAttemptAt.utc()).bind("error", error.take(4000)).bind("now", now.utc())
            .bind("jobId", claim.jobId).bind("claimToken", claim.claimToken.toString())
            .fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun invoice(userId: Long, invoiceId: Long): BillingInvoiceDetail? {
        val invoice = loadInvoiceSummary(invoiceId)?.takeIf { summary ->
            database.sql("select count(*) as count_value from invoices where id = :id and user_id = :userId")
                .bind("id", invoiceId).bind("userId", userId)
                .map { row, _ -> row.long("count_value") > 0 }.one().awaitSingle()
        } ?: return null
        return BillingInvoiceDetail(
            invoice = invoice,
            events = invoiceEvents(invoiceId),
            paymentHistory = paymentHistory(invoiceId),
            actions = actions(invoiceId),
        )
    }

    override suspend fun invoiceByNumber(userId: Long, invoiceNumber: UUID): BillingInvoiceSummary? =
        database.sql(INVOICE_SUMMARY_SQL + " where i.user_id = :userId and i.invoice_number = :invoiceNumber")
            .bind("userId", userId)
            .bind("invoiceNumber", invoiceNumber.toString())
            .map { row, _ -> row.invoiceSummary() }
            .one()
            .awaitSingleOrNull()

    override suspend fun latestPendingInvoice(userId: Long): BillingInvoiceSummary? =
        database.sql(
            INVOICE_SUMMARY_SQL +
                " where i.user_id = :userId and i.type = 'NORMAL' and i.status = 'WAITING' and p.id is null" +
                " order by i.created_at desc, i.id desc limit 1",
        ).bind("userId", userId)
            .map { row, _ -> row.invoiceSummary() }
            .one()
            .awaitSingleOrNull()

    override suspend fun invoices(userId: Long, limit: Int, offset: Int): BillingInvoicePage {
        val items = database.sql(INVOICE_SUMMARY_SQL + " where i.user_id = :userId order by i.created_at desc, i.id desc limit :limit offset :offset")
            .bind("userId", userId).bind("limit", limit).bind("offset", offset)
            .map { row, _ -> row.invoiceSummary() }.all().collectList().awaitSingle()
        return BillingInvoicePage(limit, offset, items)
    }

    override suspend fun paymentOwner(paymentId: Long): Long? =
        database.sql("select user_id from payments where id = :id")
            .bind("id", paymentId).map { row, _ -> row.long("user_id") }.one().awaitSingleOrNull()

    @Transactional
    override suspend fun requestRefund(
        userId: Long,
        paymentId: Long,
        command: RequestBillingActionCommand,
        now: Instant,
    ): BillingAction = requestRefundFromSource(userId, paymentId, command, now, BillingEventSource.CLIENT)

    private suspend fun requestRefundFromSource(
        userId: Long,
        paymentId: Long,
        command: RequestBillingActionCommand,
        now: Instant,
        source: BillingEventSource,
    ): BillingAction {
        existingAction(userId, BillingActionType.REFUND, command.idempotencyKey)?.let { return it }
        val payment = lockPayment(paymentId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Payment not found.", HttpStatus.NOT_FOUND)
        if (payment.userId != userId) throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Payment not found.", HttpStatus.NOT_FOUND)
        val invoice = lockInvoice(payment.invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Payment invoice is missing.")
        existingAction(userId, BillingActionType.REFUND, command.idempotencyKey)?.let { return it }
        if (
            invoice.type != InvoiceType.NORMAL ||
            invoice.status != InvoiceStatus.COMPLETED ||
            payment.status !in REFUNDABLE_PAYMENT_STATES
        ) {
            throw billingFailure(
                ApiErrorCode.BILLING_ACTION_NOT_ALLOWED,
                "A refund cannot be requested while the invoice is ${invoice.status}.",
                HttpStatus.CONFLICT,
            )
        }
        val refundInvoiceId = insertRefundInvoice(
            originalInvoice = invoice,
            source = source,
            actorUserId = userId.takeIf { source == BillingEventSource.CLIENT },
            correlationId = command.idempotencyKey,
            now = now,
        )
        val refundInvoice = lockInvoice(refundInvoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Refund invoice could not be locked.")
        appendInvoiceEvent(
            refundInvoice.id,
            "refund-requested:${source.name.lowercase()}:${userId}:${command.idempotencyKey}",
            InvoiceEventType.REFUND_REQUESTED,
            source,
            userId.takeIf { source == BillingEventSource.CLIENT },
            command.reason,
            now,
        )
        transitionPayment(
            payment,
            PaymentStatus.REFUND_PENDING,
            PaymentHistoryEventType.REFUND_REQUESTED,
            "payment-refund-requested:${source.name.lowercase()}:${userId}:${command.idempotencyKey}",
            source,
            null,
            command.reason,
            now,
            historyInvoiceId = refundInvoice.id,
        )
        return insertActionIfAbsent(
            refundInvoice,
            payment,
            BillingActionType.REFUND,
            BillingActionStatus.AWAITING_APPLE,
            command.idempotencyKey,
            command.reason,
            now,
        )
    }

    @Transactional
    override suspend fun requestCancellation(
        userId: Long,
        originalTransactionId: String,
        command: RequestBillingActionCommand,
        now: Instant,
    ): BillingAction = requestCancellationFromSource(
        userId, originalTransactionId, command, now, BillingEventSource.CLIENT,
    )

    private suspend fun requestCancellationFromSource(
        userId: Long,
        originalTransactionId: String,
        command: RequestBillingActionCommand,
        now: Instant,
        source: BillingEventSource,
    ): BillingAction {
        existingAction(userId, BillingActionType.CANCELLATION, command.idempotencyKey)?.let { return it }
        val payment = lockLatestPayment(userId, originalTransactionId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Subscription payment not found.", HttpStatus.NOT_FOUND)
        val invoice = lockInvoice(payment.invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Payment invoice is missing.")
        existingAction(userId, BillingActionType.CANCELLATION, command.idempotencyKey)?.let { return it }
        if (payment.productType != BillingProductType.AUTO_RENEWABLE_SUBSCRIPTION) {
            throw billingFailure(
                ApiErrorCode.BILLING_ACTION_NOT_ALLOWED,
                "Only an auto-renewable subscription can be cancelled.",
                HttpStatus.CONFLICT,
            )
        }
        if (
            invoice.type != InvoiceType.NORMAL ||
            invoice.status != InvoiceStatus.COMPLETED ||
            !InvoiceStateMachine.canApply(invoice.type, invoice.status, InvoiceEventType.CANCELLATION_REQUESTED)
        ) {
            throw billingFailure(
                ApiErrorCode.BILLING_ACTION_NOT_ALLOWED,
                "Cancellation cannot be requested while the invoice is ${invoice.status}.",
                HttpStatus.CONFLICT,
            )
        }
        appendInvoiceEvent(
            invoice.id,
            "cancellation-requested:${source.name.lowercase()}:${userId}:${command.idempotencyKey}",
            InvoiceEventType.CANCELLATION_REQUESTED,
            source,
            userId.takeIf { source == BillingEventSource.CLIENT },
            command.reason,
            now,
        )
        return insertActionIfAbsent(
            invoice,
            payment,
            BillingActionType.CANCELLATION,
            BillingActionStatus.AWAITING_APPLE,
            command.idempotencyKey,
            command.reason,
            now,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun recordAppleNotification(notification: VerifiedAppleNotification, now: Instant): Boolean {
        val inserted = database.sql(
            """
            insert ignore into billing_apple_notification_inbox (
                notification_uuid, notification_type, subtype, environment, signed_payload_sha256,
                transaction_id, processing_status, received_at, updated_at
            ) values (
                :uuid, :type, :subtype, :environment, :hash, :transactionId, 'RECEIVED', :now, :now
            )
            """.trimIndent(),
        ).bind("uuid", notification.notificationUUID)
            .bind("type", notification.notificationType)
            .bindNullable("subtype", notification.subtype, String::class.java)
            .bind("environment", notification.environment.name)
            .bind("hash", notification.signedPayloadSha256)
            .bindNullable("transactionId", notification.transaction?.transactionId, String::class.java)
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        if (inserted == 1L) return true
        return database.sql(
            """
            update billing_apple_notification_inbox
            set processing_status = 'RECEIVED', last_error = null, updated_at = :now
            where notification_uuid = :uuid
              and (
                    processing_status = 'FAILED'
                    or (processing_status = 'RECEIVED' and updated_at <= timestampadd(minute, -5, :now))
                  )
            """.trimIndent(),
        ).bind("uuid", notification.notificationUUID).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle() == 1L
    }

    @Transactional
    override suspend fun applyAppleNotification(command: ApplyAppleNotificationCommand): Boolean {
        val notification = command.notification

        val transactionId = notification.transaction?.transactionId
        val payment = transactionId?.let { lockPaymentByTransaction(it) }
        if (payment == null) {
            markNotification(notification.notificationUUID, BillingReceiptStatus.IGNORED, null, command.occurredAt)
            return true
        }
        val invoice = lockInvoice(payment.invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Notification invoice is missing.")
        val applied = applyProviderLifecycle(invoice, payment, command)
        markNotification(
            notification.notificationUUID,
            if (applied) BillingReceiptStatus.PROCESSED else BillingReceiptStatus.IGNORED,
            null,
            command.occurredAt,
        )
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun markAppleNotificationFailed(notificationUUID: String, error: String, now: Instant) {
        markNotification(notificationUUID, BillingReceiptStatus.FAILED, error.take(4000), now)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun recordRevenueCatEvent(
        event: com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent,
        now: Instant,
    ): Boolean {
        val inserted = database.sql(
            """
            insert ignore into billing_revenuecat_event_inbox (
                event_id, event_type, app_user_id, original_app_user_id, store, product_id, transaction_id,
                environment, cancel_reason, expiration_reason, signed_payload_sha256,
                processing_status, event_at, received_at, updated_at
            ) values (
                :eventId, :eventType, :appUserId, :originalAppUserId, :store, :productId, :transactionId,
                :environment, :cancelReason, :expirationReason, :hash,
                'RECEIVED', :eventAt, :now, :now
            )
            """.trimIndent(),
        ).bind("eventId", event.eventId)
            .bind("eventType", event.eventType)
            .bindNullable("appUserId", event.appUserId, String::class.java)
            .bindNullable("originalAppUserId", event.originalAppUserId, String::class.java)
            .bindNullable("store", event.store, String::class.java)
            .bindNullable("productId", event.productId, String::class.java)
            .bindNullable("transactionId", event.transactionId, String::class.java)
            .bindNullable("environment", event.environment?.name, String::class.java)
            .bindNullable("cancelReason", event.cancelReason, String::class.java)
            .bindNullable("expirationReason", event.expirationReason, String::class.java)
            .bind("hash", event.signedPayloadSha256)
            .bind("eventAt", event.eventAt.utc())
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        if (inserted == 1L) {
            insertSubscriptionEventReceipt(event, now)
            return true
        }

        return database.sql(
            """
            update billing_revenuecat_event_inbox
            set processing_status = 'RECEIVED', last_error = null, updated_at = :now
            where event_id = :eventId and processing_status = 'FAILED'
            """.trimIndent(),
        ).bind("now", now.utc()).bind("eventId", event.eventId)
            .fetch().rowsUpdated().awaitSingle() == 1L
    }

    @Transactional
    override suspend fun claimDueRevenueCatEvents(now: Instant, limit: Int): List<VerifiedRevenueCatEvent> {
        val events = database.sql(
            """
            select e.provider_event_id, e.event_type, e.store, e.product_id, e.transaction_id,
                   e.original_transaction_id, e.environment, e.price_milliunits, e.currency,
                   e.purchased_at, e.expires_at, e.occurred_at, e.provider_reason, e.payload_sha256,
                   a.app_account_token
            from subscription_events e
            left join billing_accounts a on a.id = e.billing_account_id and a.status = 'ACTIVE'
            where e.provider = 'REVENUECAT'
              and (
                    (e.processing_status in ('PENDING', 'FAILED') and e.next_attempt_at <= :now)
                    or (e.processing_status = 'PROCESSING' and e.updated_at <= timestampadd(minute, -5, :now))
                  )
              and e.attempt_count < e.max_attempts
            order by e.next_attempt_at, e.id
            limit :limit for update skip locked
            """.trimIndent(),
        ).bind("now", now.utc()).bind("limit", limit.coerceIn(1, 100)).map { row, _ ->
            val eventType = row.string("event_type")
            VerifiedRevenueCatEvent(
                eventId = row.string("provider_event_id"),
                eventType = eventType,
                appUserId = row.nullableString("app_account_token"),
                originalAppUserId = null,
                aliases = emptyList(),
                store = row.nullableString("store"),
                productId = row.nullableString("product_id"),
                transactionId = row.nullableString("transaction_id"),
                originalTransactionId = row.nullableString("original_transaction_id"),
                environment = row.nullableString("environment")?.let(com.buddystudy.billing.domain.BillingEnvironment::valueOf),
                priceMilliunits = row.get("price_milliunits", java.lang.Long::class.java)?.toLong(),
                currency = row.nullableString("currency"),
                purchasedAt = row.nullableInstant("purchased_at"),
                expiresAt = row.nullableInstant("expires_at"),
                eventAt = row.instant("occurred_at"),
                cancelReason = row.nullableString("provider_reason").takeIf { eventType == "CANCELLATION" },
                expirationReason = row.nullableString("provider_reason").takeIf { eventType == "EXPIRATION" },
                signedPayloadSha256 = row.string("payload_sha256"),
            )
        }.all().collectList().awaitSingle()
        events.forEach { event ->
            database.sql(
                """
                update subscription_events
                set processing_status = 'PROCESSING', updated_at = :now
                where provider = 'REVENUECAT' and provider_event_id = :eventId
                  and (
                        processing_status in ('PENDING', 'FAILED')
                        or (processing_status = 'PROCESSING' and updated_at <= timestampadd(minute, -5, :now))
                      )
                """.trimIndent(),
            ).bind("now", now.utc()).bind("eventId", event.eventId).fetch().rowsUpdated().awaitSingle()
        }
        return events
    }

    @Transactional
    override suspend fun applyRevenueCatEvent(
        event: VerifiedRevenueCatEvent,
        now: Instant,
    ): Boolean {
        if (event.eventType in REVENUECAT_PURCHASE_EVENTS) {
            markRevenueCatEvent(event.eventId, BillingReceiptStatus.PROCESSED, null, now)
            return true
        }
        val mapped = event.toProviderNotification()
        val projectionOnlyEvent = event.eventType == "PRODUCT_CHANGE" || event.eventType == "BILLING_ISSUE" ||
            (event.eventType == "CANCELLATION" && event.cancelReason == "CUSTOMER_SUPPORT")
        if (mapped == null && !projectionOnlyEvent) {
            markRevenueCatEvent(event.eventId, BillingReceiptStatus.IGNORED, null, now)
            return true
        }
        val originalTransactionId = event.originalTransactionId
        if (projectionOnlyEvent && event.eventType != "PRODUCT_CHANGE" && originalTransactionId != null &&
            !advanceProviderEventCursor(originalTransactionId, event.eventAt, now)
        ) {
            markRevenueCatEvent(event.eventId, BillingReceiptStatus.IGNORED, "Stale provider event.", now)
            return true
        }
        if (event.eventType == "PRODUCT_CHANGE") {
            originalTransactionId?.let {
                updatePendingProduct(originalTransactionId, event.productId, event.eventAt, now)
            }
            markRevenueCatEvent(event.eventId, BillingReceiptStatus.PROCESSED, null, now)
            return true
        }
        if (event.eventType == "BILLING_ISSUE") {
            originalTransactionId?.let {
                updateSubscriptionLifecycle(
                    originalTransactionId,
                    accessStatus = null,
                    renewalStatus = "BILLING_RETRY",
                    providerEventAt = event.eventAt,
                    processedAt = now,
                    nextReconcileAt = now.plusSeconds(15 * 60),
                )
            }
            markRevenueCatEvent(event.eventId, BillingReceiptStatus.PROCESSED, null, now)
            return true
        }
        if (event.eventType == "CANCELLATION" && event.cancelReason == "CUSTOMER_SUPPORT") {
            originalTransactionId?.let {
                updateSubscriptionLifecycle(
                    originalTransactionId,
                    accessStatus = null,
                    renewalStatus = null,
                    providerEventAt = event.eventAt,
                    processedAt = now,
                    nextReconcileAt = now,
                )
            }
            markRevenueCatEvent(event.eventId, BillingReceiptStatus.PROCESSED, null, now)
            return true
        }
        requireNotNull(mapped)
        val transactionId = event.transactionId
            ?: throw billingFailure(ApiErrorCode.BILLING_TRANSACTION_INVALID, "RevenueCat lifecycle event has no transaction ID.")
        val payment = lockPaymentByTransaction(transactionId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "RevenueCat transaction has not reached the payment ledger yet.")
        val invoice = lockInvoice(payment.invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "RevenueCat payment invoice is missing.")
        val applied = applyProviderLifecycle(
            invoice = invoice,
            payment = payment,
            command = ApplyAppleNotificationCommand(mapped, now),
            source = BillingEventSource.REVENUECAT_WEBHOOK,
            eventPrefix = "revenuecat",
        )
        markRevenueCatEvent(
            event.eventId,
            if (applied) BillingReceiptStatus.PROCESSED else BillingReceiptStatus.IGNORED,
            if (applied) null else "Stale provider event.",
            now,
        )
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun markRevenueCatEventFailed(eventId: String, error: String, now: Instant) {
        markRevenueCatEvent(eventId, BillingReceiptStatus.FAILED, error.take(4000), now)
        database.sql(
            """
            update subscription_events
            set processing_status = 'FAILED', attempt_count = least(attempt_count + 1, max_attempts),
                last_error = :error, next_attempt_at = timestampadd(minute, 15, :now), updated_at = :now
            where provider = 'REVENUECAT' and provider_event_id = :eventId
            """.trimIndent(),
        ).bind("error", error.take(4000)).bind("now", now.utc()).bind("eventId", eventId)
            .fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun adminInvoices(
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminBillingInvoicePage {
        val conditions = mutableListOf<String>()
        if (query != null) {
            conditions += "(cast(i.id as char) = :query or i.invoice_number like :likeQuery or u.email like :likeQuery or u.display_name like :likeQuery or p.provider_transaction_id = :query)"
        }
        if (status != null) conditions += "i.status = :status"
        val where = if (conditions.isNotEmpty()) conditions.joinToString(" and ", prefix = " where ") else ""
        var spec = database.sql(
            ADMIN_INVOICE_SQL + where + " order by i.created_at desc, i.id desc limit :limit offset :offset",
        ).bind("limit", limit).bind("offset", offset)
        if (query != null) {
            spec = spec.bind("query", query).bind("likeQuery", "%$query%")
        }
        if (status != null) spec = spec.bind("status", status)
        val results = spec.map { row, _ -> row.adminInvoice() }.all().collectList().awaitSingle()
        var countSpec = database.sql(
            "select count(*) as total_count from invoices i " +
                "join users u on u.id = i.user_id left join payments p on p.invoice_id = coalesce(i.original_invoice_id, i.id)" + where,
        )
        if (query != null) {
            countSpec = countSpec.bind("query", query).bind("likeQuery", "%$query%")
        }
        if (status != null) countSpec = countSpec.bind("status", status)
        val totalCount = countSpec.map { row, _ -> row.long("total_count") }.one().awaitSingle()
        return AdminBillingInvoicePage(limit, offset, totalCount, results)
    }

    override suspend fun adminInvoice(invoiceId: Long): AdminBillingInvoiceDetail? {
        val owner = database.sql(
            "select u.id as user_id, u.email, u.display_name from invoices i join users u on u.id = i.user_id where i.id = :id",
        ).bind("id", invoiceId).map { row, _ ->
            Triple(row.long("user_id"), row.string("email"), row.string("display_name"))
        }.one().awaitSingleOrNull() ?: return null
        val detail = invoice(owner.first, invoiceId) ?: return null
        return AdminBillingInvoiceDetail(owner.first, owner.second, owner.third, detail)
    }

    @Transactional
    override suspend fun adminRequestRefund(
        invoiceId: Long,
        command: RequestBillingActionCommand,
        now: Instant,
    ): BillingAction {
        val payment = lockPaymentByInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice payment not found.", HttpStatus.NOT_FOUND)
        return requestRefundFromSource(payment.userId, payment.id, command, now, BillingEventSource.ADMIN)
    }

    @Transactional
    override suspend fun adminRequestCancellation(
        invoiceId: Long,
        command: RequestBillingActionCommand,
        now: Instant,
    ): BillingAction {
        val payment = lockPaymentByInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice payment not found.", HttpStatus.NOT_FOUND)
        return requestCancellationFromSource(
            payment.userId,
            payment.providerOriginalTransactionId,
            command,
            now,
            BillingEventSource.ADMIN,
        )
    }

    @Transactional
    override suspend fun adminAdjustQuota(
        userId: Long,
        bonusDelta: Int,
        reason: String,
        idempotencyKey: String,
        now: Instant,
    ): AdminQuotaAdjustment {
        val eventId = "admin-quota:$idempotencyKey".take(191)
        existingAdminQuotaAdjustment(eventId)?.let { return it }
        val anchor = database.sql("select anchor_at from quota_accounts where user_id = :userId for update")
            .bind("userId", userId).map { row, _ -> row.instant("anchor_at") }.one().awaitSingleOrNull()
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Quota account not found.", HttpStatus.NOT_FOUND)
        val window = MonthlyQuotaWindow.periodAt(anchor, now)
        database.sql(
            """
            insert ignore into quota_periods (
                user_id, period_started_at, period_ends_at, committed_count, reserved_count, bonus_count,
                policy_version, created_at, updated_at
            ) values (:userId, :start, :end, 0, 0, 0, :policyVersion, :now, :now)
            """.trimIndent(),
        ).bind("userId", userId).bind("start", window.startedAt.utc()).bind("end", window.resetAt.utc())
            .bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION)
            .bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
        val period = database.sql(
            "select id, bonus_count from quota_periods where user_id = :userId and period_started_at = :start for update",
        ).bind("userId", userId).bind("start", window.startedAt.utc()).map { row, _ ->
            row.long("id") to row.int("bonus_count")
        }.one().awaitSingle()
        val appliedDelta = bonusDelta.coerceAtLeast(-period.second)
        database.sql("update quota_periods set bonus_count = bonus_count + :delta, updated_at = :now where id = :id")
            .bind("delta", appliedDelta).bind("now", now.utc()).bind("id", period.first)
            .fetch().rowsUpdated().awaitSingle()
        database.sql(
            """
            insert into quota_ledger (
                ledger_event_id, user_id, quota_period_id, reservation_id, ledger_type,
                committed_delta, reserved_delta, bonus_delta, reason, occurred_at, created_at
            ) values (:eventId, :userId, :periodId, null, :type, 0, 0, :delta, :reason, :now, :now)
            """.trimIndent(),
        ).bind("eventId", eventId).bind("userId", userId).bind("periodId", period.first)
            .bind("type", if (appliedDelta >= 0) "BONUS_GRANT" else "BONUS_REVOKE")
            .bind("delta", appliedDelta).bind("reason", reason.take(1000)).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        return AdminQuotaAdjustment(
            userId, eventId, window.startedAt, window.resetAt, appliedDelta,
            period.second + appliedDelta, reason.take(1000), now,
        )
    }

    @Transactional
    override suspend fun adminRequestReconcile(
        userId: Long,
        reason: String?,
        now: Instant,
    ): AdminBillingReconcileRequest {
        val subscription = database.sql(
            """
            select s.original_transaction_id, s.billing_account_id
            from subscriptions s where s.user_id = :userId
            order by s.updated_at desc, s.id desc limit 1 for update
            """.trimIndent(),
        ).bind("userId", userId).map { row, _ -> row.string("original_transaction_id") to row.long("billing_account_id") }
            .one().awaitSingleOrNull()
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Subscription not found.", HttpStatus.NOT_FOUND)
        val eventId = "admin-reconcile:${UUID.randomUUID()}"
        database.sql(
            """
            insert into subscription_events (
                provider_event_id, provider, event_type, user_id, billing_account_id,
                original_transaction_id, processing_status, attempt_count, max_attempts, next_attempt_at,
                payload_sha256, last_error, occurred_at, created_at, updated_at
            ) values (
                :eventId, 'REVENUECAT', 'ADMIN_RECONCILE_REQUESTED', :userId, :accountId,
                :originalTransactionId, 'PENDING', 0, 3, :now,
                :hash, :reason, :now, :now, :now
            )
            """.trimIndent(),
        ).bind("eventId", eventId).bind("userId", userId).bind("accountId", subscription.second)
            .bind("originalTransactionId", subscription.first).bind("now", now.utc())
            .bind("hash", "0".repeat(64)).bindNullable("reason", reason?.take(1000), String::class.java)
            .fetch().rowsUpdated().awaitSingle()
        database.sql(
            "update subscriptions set next_reconcile_at = :now, updated_at = :now where user_id = :userId",
        ).bind("now", now.utc()).bind("userId", userId).fetch().rowsUpdated().awaitSingle()
        return AdminBillingReconcileRequest(userId, eventId, now)
    }

    override suspend fun adminUserTimeline(userId: Long, limit: Int): AdminUserBillingTimeline {
        val entries = database.sql(
            """
            select category, event_id, event_type, status, reason, occurred_at
            from (
                select 'SUBSCRIPTION' category, provider_event_id event_id, event_type,
                       processing_status status, last_error reason, occurred_at
                from subscription_events where user_id = :userId
                union all
                select 'INVOICE', e.event_id, e.event_type, e.to_status, e.reason, e.occurred_at
                from invoice_events e join invoices i on i.id = e.invoice_id where i.user_id = :userId
                union all
                select 'PAYMENT', h.event_id, h.event_type, h.to_status, h.reason, h.occurred_at
                from payments_history h join payments p on p.id = h.payment_id where p.user_id = :userId
                union all
                select 'QUOTA', q.ledger_event_id, q.ledger_type, null, q.reason, q.occurred_at
                from quota_ledger q where q.user_id = :userId
            ) timeline
            order by occurred_at desc, event_id desc
            limit :limit
            """.trimIndent(),
        ).bind("userId", userId).bind("limit", limit).map { row, _ ->
            AdminBillingTimelineEntry(
                category = row.string("category"),
                eventId = row.string("event_id"),
                eventType = row.string("event_type"),
                status = row.get("status", String::class.java),
                reason = row.get("reason", String::class.java),
                occurredAt = row.instant("occurred_at"),
            )
        }.all().collectList().awaitSingle()
        return AdminUserBillingTimeline(userId, entitlementForUser(userId), entries)
    }

    private suspend fun existingAdminQuotaAdjustment(eventId: String): AdminQuotaAdjustment? =
        database.sql(
            """
            select l.user_id, l.ledger_event_id, p.period_started_at, p.period_ends_at,
                   l.bonus_delta, p.bonus_count, l.reason, l.occurred_at
            from quota_ledger l join quota_periods p on p.id = l.quota_period_id
            where l.ledger_event_id = :eventId
            """.trimIndent(),
        ).bind("eventId", eventId).map { row, _ ->
            AdminQuotaAdjustment(
                userId = row.long("user_id"), ledgerEventId = row.string("ledger_event_id"),
                periodStartedAt = row.instant("period_started_at"), resetAt = row.instant("period_ends_at"),
                bonusDelta = row.int("bonus_delta"), bonusLimit = row.int("bonus_count"),
                reason = row.nullableString("reason").orEmpty(), occurredAt = row.instant("occurred_at"),
            )
        }.one().awaitSingleOrNull()

    @Transactional
    override suspend fun claimDueSubscriptionReconciliations(
        now: Instant,
        limit: Int,
    ): List<SubscriptionReconciliationClaim> {
        val claims = database.sql(
            """
            select s.id, s.user_id, s.original_transaction_id, a.app_account_token,
                   (
                       select count(*) from subscription_events e
                       where e.original_transaction_id = s.original_transaction_id
                         and e.event_type = 'SUBSCRIPTION_RECONCILE_FAILED'
                         and (s.last_reconciled_at is null or e.occurred_at > s.last_reconciled_at)
                   ) as failure_count
            from subscriptions s join billing_accounts a on a.id = s.billing_account_id
            where s.user_id is not null and a.status = 'ACTIVE'
              and coalesce(s.next_reconcile_at, s.updated_at) <= :now
              and (
                  select count(*) from subscription_events e
                  where e.original_transaction_id = s.original_transaction_id
                    and e.event_type = 'SUBSCRIPTION_RECONCILE_FAILED'
                    and (s.last_reconciled_at is null or e.occurred_at > s.last_reconciled_at)
              ) < 3
            order by coalesce(s.next_reconcile_at, s.updated_at), s.id
            limit :limit for update skip locked
            """.trimIndent(),
        ).bind("now", now.utc()).bind("limit", limit.coerceIn(1, 100)).map { row, _ ->
            SubscriptionReconciliationClaim(
                subscriptionId = row.long("id"), userId = row.long("user_id"),
                originalTransactionId = row.string("original_transaction_id"),
                appAccountToken = UUID.fromString(row.string("app_account_token")),
                attempt = row.int("failure_count") + 1,
            )
        }.all().collectList().awaitSingle()
        claims.forEach { claim ->
            database.sql(
                "update subscriptions set next_reconcile_at = timestampadd(minute, 5, :now), updated_at = :now where id = :id",
            ).bind("now", now.utc()).bind("id", claim.subscriptionId).fetch().rowsUpdated().awaitSingle()
        }
        return claims
    }

    @Transactional
    override suspend fun applySubscriptionSnapshot(
        claim: SubscriptionReconciliationClaim,
        snapshot: RevenueCatCustomerSnapshot,
        now: Instant,
    ) {
        val eventId = "reconcile:${claim.subscriptionId}:${snapshot.fetchedAt.toEpochMilli()}".take(191)
        val access = snapshot.accessStatus.name
        val renewal = snapshot.renewalStatus.name
        val nextReconcileAt = when {
            snapshot.renewalStatus == com.buddystudy.billing.domain.SubscriptionRenewalStatus.BILLING_RETRY ->
                now.plusSeconds(15 * 60)
            snapshot.accessStatus == com.buddystudy.billing.domain.SubscriptionAccessStatus.UNKNOWN ->
                now.plusSeconds(15 * 60)
            snapshot.accessStatus in setOf(
                com.buddystudy.billing.domain.SubscriptionAccessStatus.ACTIVE,
                com.buddystudy.billing.domain.SubscriptionAccessStatus.GRACE_PERIOD,
            ) -> now.plusSeconds(6 * 60 * 60)
            else -> now.plusSeconds(24 * 60 * 60)
        }
        database.sql(
            """
            insert ignore into subscription_events (
                provider_event_id, provider, event_type, user_id, billing_account_id,
                original_transaction_id, expires_at, access_status, renewal_status, processing_status,
                attempt_count, max_attempts, next_attempt_at, payload_sha256,
                occurred_at, processed_at, created_at, updated_at
            ) select :eventId, 'REVENUECAT', 'SUBSCRIPTION_SNAPSHOT_RECONCILED', s.user_id, s.billing_account_id,
                     s.original_transaction_id, :expiresAt, :access, :renewal, 'COMPLETED',
                     :attempt, 3, :now, :hash, :fetchedAt, :now, :now, :now
              from subscriptions s where s.id = :subscriptionId
            """.trimIndent(),
        ).bind("eventId", eventId).bindNullable("expiresAt", snapshot.expiresAt?.utc(), LocalDateTime::class.java)
            .bind("access", access).bind("renewal", renewal).bind("attempt", claim.attempt).bind("now", now.utc())
            .bind("hash", "0".repeat(64)).bind("fetchedAt", snapshot.fetchedAt.utc())
            .bind("subscriptionId", claim.subscriptionId).fetch().rowsUpdated().awaitSingle()
        database.sql(
            """
            update subscriptions
            set access_status = :access, renewal_status = :renewal,
                expires_at = coalesce(:expiresAt, expires_at),
                last_reconciled_at = :fetchedAt,
                next_reconcile_at = :nextReconcileAt,
                version = version + 1, updated_at = :now
            where id = :id and user_id = :userId
            """.trimIndent(),
        ).bind("access", access).bind("renewal", renewal)
            .bindNullable("expiresAt", snapshot.expiresAt?.utc(), LocalDateTime::class.java)
            .bind("fetchedAt", snapshot.fetchedAt.utc()).bind("nextReconcileAt", nextReconcileAt.utc())
            .bind("now", now.utc()).bind("id", claim.subscriptionId).bind("userId", claim.userId)
            .fetch().rowsUpdated().awaitSingle()
        rebuildEntitlementProjection(claim.userId, now)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun recordSubscriptionReconcileFailure(
        claim: SubscriptionReconciliationClaim,
        error: String,
        now: Instant,
    ) {
        database.sql(
            """
            insert into subscription_events (
                provider_event_id, provider, event_type, user_id, billing_account_id,
                original_transaction_id, processing_status, attempt_count, max_attempts,
                next_attempt_at, payload_sha256, last_error, occurred_at, created_at, updated_at
            ) select :eventId, 'REVENUECAT', 'SUBSCRIPTION_RECONCILE_FAILED', s.user_id, s.billing_account_id,
                     s.original_transaction_id, 'FAILED', :attempt, 3,
                     timestampadd(minute, 15, :now), :hash, :error, :now, :now, :now
              from subscriptions s where s.id = :subscriptionId
            """.trimIndent(),
        ).bind("eventId", "reconcile-failed:${claim.subscriptionId}:${now.toEpochMilli()}".take(191))
            .bind("attempt", claim.attempt).bind("now", now.utc()).bind("hash", "0".repeat(64))
            .bind("error", error.take(4000)).bind("subscriptionId", claim.subscriptionId)
            .fetch().rowsUpdated().awaitSingle()
        database.sql(
            "update subscriptions set next_reconcile_at = timestampadd(minute, :delay, :now), updated_at = :now where id = :id",
        ).bind("delay", if (claim.attempt >= 3) 1440 else 15).bind("now", now.utc())
            .bind("id", claim.subscriptionId).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun applyProviderLifecycle(
        invoice: InvoiceEntity,
        payment: PaymentEntity,
        command: ApplyAppleNotificationCommand,
        source: BillingEventSource = BillingEventSource.APPLE_NOTIFICATION,
        eventPrefix: String = "apple",
    ): Boolean {
        val notification = command.notification
        val now = command.occurredAt
        if (!advanceProviderEventCursor(
                payment.providerOriginalTransactionId,
                notification.signedAt,
                now,
            )
        ) {
            return false
        }
        when (notification.notificationType) {
            "DID_CHANGE_RENEWAL_STATUS" -> when (notification.subtype) {
                "AUTO_RENEW_DISABLED" -> {
                    if (InvoiceStateMachine.canApply(invoice.type, invoice.status, InvoiceEventType.CANCELLATION_REQUESTED)) {
                        appendInvoiceEvent(
                            invoice.id,
                            "$eventPrefix-cancellation-requested:${notification.notificationUUID}",
                            InvoiceEventType.CANCELLATION_REQUESTED,
                            source,
                            null,
                            null,
                            now,
                        )
                    }
                    completeActions(
                        invoice.id,
                        setOf(BillingActionType.CANCELLATION),
                        BillingActionStatus.COMPLETED,
                        now,
                    )
                    updateSubscriptionLifecycle(
                        payment.providerOriginalTransactionId,
                        accessStatus = null,
                        renewalStatus = "CANCELED",
                        providerEventAt = notification.signedAt,
                        processedAt = now,
                    )
                }
                "AUTO_RENEW_ENABLED" -> {
                    if (InvoiceStateMachine.canApply(invoice.type, invoice.status, InvoiceEventType.CANCELLATION_REVERSED)) {
                        appendInvoiceEvent(
                            invoice.id,
                            "$eventPrefix-cancellation-reversed:${notification.notificationUUID}",
                            InvoiceEventType.CANCELLATION_REVERSED,
                            source,
                            null,
                            null,
                            now,
                        )
                    }
                    completeActions(
                        invoice.id,
                        setOf(BillingActionType.CANCELLATION),
                        BillingActionStatus.CANCELLED,
                        now,
                    )
                    updateSubscriptionLifecycle(
                        payment.providerOriginalTransactionId,
                        accessStatus = null,
                        renewalStatus = "WILL_RENEW",
                        providerEventAt = notification.signedAt,
                        processedAt = now,
                    )
                }
            }
            "REFUND" -> {
                val refundInvoice = requireRefundInvoice(invoice, notification, now, source)
                transitionPayment(payment, PaymentStatus.REFUNDED, PaymentHistoryEventType.REFUNDED,
                    "$eventPrefix-refund:${notification.notificationUUID}", source,
                    notification.notificationUUID, null, now, historyInvoiceId = refundInvoice.id)
                appendIfAllowed(refundInvoice.id, InvoiceEventType.REFUNDED, notification, now, source, eventPrefix)
                completeActions(refundInvoice.id, setOf(BillingActionType.REFUND, BillingActionType.COMPENSATION), BillingActionStatus.COMPLETED, now)
                completeActions(invoice.id, setOf(BillingActionType.COMPENSATION), BillingActionStatus.COMPLETED, now)
                deactivateMembership(payment.providerOriginalTransactionId, invoice.id, now)
                updateSubscriptionLifecycle(
                    payment.providerOriginalTransactionId,
                    accessStatus = "REVOKED",
                    renewalStatus = "NOT_APPLICABLE",
                    providerEventAt = notification.signedAt,
                    processedAt = now,
                )
            }
            "REFUND_DECLINED" -> {
                val refundInvoice = requireRefundInvoice(invoice, notification, now, source)
                transitionPayment(payment, PaymentStatus.REFUND_DECLINED, PaymentHistoryEventType.REFUND_DECLINED,
                    "$eventPrefix-refund-declined:${notification.notificationUUID}", source,
                    notification.notificationUUID, null, now, historyInvoiceId = refundInvoice.id)
                appendIfAllowed(refundInvoice.id, InvoiceEventType.REFUND_DECLINED, notification, now, source, eventPrefix)
                completeActions(refundInvoice.id, setOf(BillingActionType.REFUND, BillingActionType.COMPENSATION), BillingActionStatus.DECLINED, now)
                completeActions(invoice.id, setOf(BillingActionType.COMPENSATION), BillingActionStatus.DECLINED, now)
            }
            "REFUND_REVERSED" -> {
                val refundInvoice = lockLatestRefundInvoice(invoice.id, setOf(InvoiceStatus.COMPLETED))
                transitionPayment(payment, PaymentStatus.REFUND_REVERSED, PaymentHistoryEventType.REFUND_REVERSED,
                    "$eventPrefix-refund-reversed:${notification.notificationUUID}", source,
                    notification.notificationUUID, null, now, historyInvoiceId = refundInvoice?.id ?: invoice.id)
                refundInvoice?.let { appendIfAllowed(it.id, InvoiceEventType.REFUND_REVERSED, notification, now, source, eventPrefix) }
                reactivateMembership(invoice, payment, notification.signedAt, now)
            }
            "REVOKE" -> {
                val refundInvoice = requireRefundInvoice(invoice, notification, now, source)
                transitionPayment(payment, PaymentStatus.REVOKED, PaymentHistoryEventType.REVOKED,
                    "$eventPrefix-revoked:${notification.notificationUUID}", source,
                    notification.notificationUUID, null, now, historyInvoiceId = refundInvoice.id)
                appendIfAllowed(refundInvoice.id, InvoiceEventType.PAYMENT_REVOKED, notification, now, source, eventPrefix)
                deactivateMembership(payment.providerOriginalTransactionId, invoice.id, now)
                updateSubscriptionLifecycle(
                    payment.providerOriginalTransactionId,
                    accessStatus = "REVOKED",
                    renewalStatus = "NOT_APPLICABLE",
                    providerEventAt = notification.signedAt,
                    processedAt = now,
                )
            }
            "EXPIRED", "GRACE_PERIOD_EXPIRED" -> {
                appendIfAllowed(invoice.id, InvoiceEventType.EXPIRED, notification, now, source, eventPrefix)
                deactivateMembership(payment.providerOriginalTransactionId, invoice.id, now)
                updateSubscriptionLifecycle(
                    payment.providerOriginalTransactionId,
                    accessStatus = "EXPIRED",
                    renewalStatus = "NOT_APPLICABLE",
                    providerEventAt = notification.signedAt,
                    processedAt = now,
                    nextReconcileAt = now.plusSeconds(24 * 60 * 60),
                )
            }
            "CONSUMPTION_REQUEST" -> {
                val refundInvoice = requireRefundInvoice(invoice, notification, now, source)
                var current = lockInvoice(refundInvoice.id) ?: return true
                if (InvoiceStateMachine.canApply(current.type, current.status, InvoiceEventType.REFUND_REQUESTED)) {
                    appendIfAllowed(refundInvoice.id, InvoiceEventType.REFUND_REQUESTED, notification, now, source, eventPrefix)
                    current = lockInvoice(refundInvoice.id) ?: return true
                }
                if (InvoiceStateMachine.canApply(current.type, current.status, InvoiceEventType.REFUND_PENDING)) {
                    appendIfAllowed(refundInvoice.id, InvoiceEventType.REFUND_PENDING, notification, now, source, eventPrefix)
                }
            }
        }
        return true
    }

    private suspend fun requireRefundInvoice(
        originalInvoice: InvoiceEntity,
        notification: VerifiedAppleNotification,
        now: Instant,
        source: BillingEventSource = BillingEventSource.APPLE_NOTIFICATION,
    ): InvoiceEntity {
        lockLatestRefundInvoice(originalInvoice.id, setOf(InvoiceStatus.WAITING))?.let { return it }
        val id = insertRefundInvoice(
            originalInvoice = originalInvoice,
            source = source,
            actorUserId = null,
            correlationId = notification.notificationUUID,
            now = now,
        )
        return lockInvoice(id)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Refund invoice could not be locked.")
    }

    private suspend fun appendIfAllowed(
        invoiceId: Long,
        type: InvoiceEventType,
        notification: com.buddystudy.backend.billing.application.model.VerifiedAppleNotification,
        now: Instant,
        source: BillingEventSource = BillingEventSource.APPLE_NOTIFICATION,
        eventPrefix: String = "apple-notification",
    ) {
        val current = lockInvoice(invoiceId) ?: return
        if (!InvoiceStateMachine.canApply(current.type, current.status, type)) return
        appendInvoiceEvent(
            invoiceId,
            "$eventPrefix:${notification.notificationUUID}:$type",
            type,
            source,
            null,
            notification.subtype,
            now,
            correlationId = notification.notificationUUID,
        )
    }

    private suspend fun appendInvoiceEvent(
        invoiceId: Long,
        eventId: String,
        eventType: InvoiceEventType,
        source: BillingEventSource,
        actorUserId: Long?,
        reason: String?,
        occurredAt: Instant,
        correlationId: String? = null,
    ) {
        val invoice = lockInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.", HttpStatus.NOT_FOUND)
        val nextStatus = InvoiceStateMachine.next(invoice.type, invoice.status, eventType)
        val nextSequence = invoice.latestEventSequence + 1
        database.sql(
            """
            insert into invoice_events (
                invoice_id, event_id, sequence_number, event_type, source, from_status, to_status,
                correlation_id, actor_user_id, reason, occurred_at, created_at
            ) values (
                :invoiceId, :eventId, :sequence, :eventType, :source, :fromStatus, :toStatus,
                :correlationId, :actorUserId, :reason, :occurredAt, :createdAt
            )
            """.trimIndent(),
        ).bind("invoiceId", invoiceId)
            .bind("eventId", eventId.take(191))
            .bind("sequence", nextSequence)
            .bind("eventType", eventType.name)
            .bind("source", source.name)
            .bind("fromStatus", invoice.status.name)
            .bind("toStatus", nextStatus.name)
            .bindNullable("correlationId", correlationId?.take(191), String::class.java)
            .bindNullable("actorUserId", actorUserId, java.lang.Long::class.java)
            .bindNullable("reason", reason?.take(1000), String::class.java)
            .bind("occurredAt", occurredAt.utc())
            .bind("createdAt", occurredAt.utc())
            .fetch().rowsUpdated().awaitSingle()
        val changed = database.sql(
            """
            update invoices
            set status = :toStatus,
                version = version + 1,
                latest_event_sequence = :sequence,
                paid_at = case when :eventType = 'PAYMENT_VERIFIED' then :now else paid_at end,
                fulfilled_at = case when :eventType = 'FULFILLED' then :now else fulfilled_at end,
                cancelled_at = case when :eventType = 'CANCELLED' then :now else cancelled_at end,
                refunded_at = case when :eventType in ('REFUNDED', 'PAYMENT_REVOKED') then :now else refunded_at end,
                updated_at = :now
            where id = :invoiceId and version = :expectedVersion and latest_event_sequence = :expectedSequence
            """.trimIndent(),
        ).bind("toStatus", nextStatus.name)
            .bind("sequence", nextSequence)
            .bind("eventType", eventType.name)
            .bind("now", occurredAt.utc())
            .bind("invoiceId", invoiceId)
            .bind("expectedVersion", invoice.version)
            .bind("expectedSequence", invoice.latestEventSequence)
            .fetch().rowsUpdated().awaitSingle()
        if (changed != 1L) {
            throw billingFailure(ApiErrorCode.BILLING_TRANSACTION_CONFLICT, "Invoice version changed concurrently.", HttpStatus.CONFLICT)
        }
    }

    private suspend fun insertPendingInvoice(
        userId: Long,
        tierProduct: BillingTierProduct,
        appAccountToken: UUID,
        source: BillingEventSource,
        actorUserId: Long?,
        correlationId: String?,
        now: Instant,
    ): Long {
        val invoiceNumber = UUID.randomUUID()
        database.sql(
            """
            insert into invoices (
                invoice_number, type, original_invoice_id, user_id, tier_code, provider, product_id, app_account_token,
                currency, subtotal_milliunits, tax_milliunits, total_milliunits,
                status, version, latest_event_sequence, paid_at, expires_at, created_at, updated_at
            ) values (
                :invoiceNumber, 'NORMAL', null, :userId, :tierCode, 'APPLE', :productId, :appAccountToken,
                null, null, null, null,
                'WAITING', 1, 1, null, null, :now, :now
            )
            """.trimIndent(),
        ).bind("invoiceNumber", invoiceNumber.toString())
            .bind("userId", userId)
            .bind("tierCode", tierProduct.tierCode)
            .bind("productId", tierProduct.productId)
            .bind("appAccountToken", appAccountToken.toString().lowercase())
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        val invoiceId = lastInsertedId()
        insertInitialInvoiceEvent(
            invoiceId = invoiceId,
            invoiceNumber = invoiceNumber,
            source = source,
            actorUserId = actorUserId,
            correlationId = correlationId,
            now = now,
        )
        return invoiceId
    }

    private suspend fun insertRefundInvoice(
        originalInvoice: InvoiceEntity,
        source: BillingEventSource,
        actorUserId: Long?,
        correlationId: String?,
        now: Instant,
    ): Long {
        require(originalInvoice.type == InvoiceType.NORMAL) { "A refund invoice must reference a NORMAL invoice." }
        val invoiceNumber = UUID.randomUUID()
        val inserted = database.sql(
            """
            insert into invoices (
                invoice_number, type, original_invoice_id, user_id, tier_code, provider, product_id,
                app_account_token, currency, subtotal_milliunits, tax_milliunits, total_milliunits,
                status, version, latest_event_sequence, paid_at, expires_at, created_at, updated_at
            )
            select :invoiceNumber, 'REFUND', i.id, i.user_id, i.tier_code, i.provider, i.product_id,
                   i.app_account_token, i.currency, i.subtotal_milliunits, i.tax_milliunits, i.total_milliunits,
                   'WAITING', 1, 1, i.paid_at, i.expires_at, :now, :now
            from invoices i
            where i.id = :originalInvoiceId and i.type = 'NORMAL'
            """.trimIndent(),
        ).bind("invoiceNumber", invoiceNumber.toString().lowercase())
            .bind("originalInvoiceId", originalInvoice.id)
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        if (inserted != 1L) {
            throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Refund invoice could not be created.")
        }
        val invoiceId = lastInsertedId()
        insertInitialInvoiceEvent(
            invoiceId = invoiceId,
            invoiceNumber = invoiceNumber,
            source = source,
            actorUserId = actorUserId,
            correlationId = correlationId,
            now = now,
        )
        return invoiceId
    }

    private suspend fun updatePendingInvoiceFromTransaction(
        invoiceId: Long,
        transaction: VerifiedAppleTransaction,
        now: Instant,
    ) {
        val changed = database.sql(
            """
            update invoices
            set currency = :currency,
                subtotal_milliunits = :price,
                tax_milliunits = case when :price is null then null else 0 end,
                total_milliunits = :price,
                expires_at = :expiresAt,
                updated_at = :now
            where id = :invoiceId and type = 'NORMAL' and status = 'WAITING'
            """.trimIndent(),
        ).bindNullable("currency", transaction.currency, String::class.java)
            .bindNullable("price", transaction.priceMilliunits, java.lang.Long::class.java)
            .bindNullable("expiresAt", transaction.expiresAt?.utc(), LocalDateTime::class.java)
            .bind("now", now.utc())
            .bind("invoiceId", invoiceId)
            .fetch().rowsUpdated().awaitSingle()
        if (changed != 1L) {
            throw billingFailure(
                ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                "Invoice is no longer awaiting payment.",
                HttpStatus.CONFLICT,
            )
        }
    }

    private suspend fun insertInitialInvoiceEvent(
        invoiceId: Long,
        invoiceNumber: UUID,
        source: BillingEventSource,
        actorUserId: Long?,
        correlationId: String?,
        now: Instant,
    ) {
        database.sql(
            """
            insert into invoice_events (
                invoice_id, event_id, sequence_number, event_type, source, from_status, to_status,
                correlation_id, actor_user_id, occurred_at, created_at
            ) values (
                :invoiceId, :eventId, 1, 'INVOICE_CREATED', :source, null, 'WAITING',
                :correlationId, :actorUserId, :now, :now
            )
            """.trimIndent(),
        ).bind("invoiceId", invoiceId)
            .bind("eventId", "invoice-created:$invoiceNumber")
            .bind("source", source.name)
            .bindNullable("correlationId", correlationId, String::class.java)
            .bindNullable("actorUserId", actorUserId, java.lang.Long::class.java)
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertPayment(invoiceId: Long, command: RecordVerifiedPaymentCommand): Long {
        val transaction = command.transaction
        database.sql(
            """
            insert into payments (
                invoice_id, user_id, provider, provider_transaction_id, provider_original_transaction_id,
                app_transaction_id, web_order_line_item_id, app_account_token, product_id, product_type,
                environment, quantity, price_milliunits, currency, status, purchase_at, original_purchase_at,
                expires_at, revocation_at, revocation_reason, signed_at, verified_at, signed_payload_sha256,
                version, created_at, updated_at
            ) values (
                :invoiceId, :userId, 'APPLE', :transactionId, :originalTransactionId,
                :appTransactionId, :webOrderLineItemId, :appAccountToken, :productId, :productType,
                :environment, :quantity, :price, :currency, 'VERIFIED', :purchaseAt, :originalPurchaseAt,
                :expiresAt, :revocationAt, :revocationReason, :signedAt, :verifiedAt, :hash,
                0, :now, :now
            )
            """.trimIndent(),
        ).bind("invoiceId", invoiceId)
            .bind("userId", command.userId)
            .bind("transactionId", transaction.transactionId)
            .bind("originalTransactionId", transaction.originalTransactionId)
            .bindNullable("appTransactionId", transaction.appTransactionId, String::class.java)
            .bindNullable("webOrderLineItemId", transaction.webOrderLineItemId, String::class.java)
            .bind("appAccountToken", transaction.appAccountToken.toString().lowercase())
            .bind("productId", transaction.productId)
            .bind("productType", transaction.productType.name)
            .bind("environment", transaction.environment.name)
            .bind("quantity", transaction.quantity)
            .bindNullable("price", transaction.priceMilliunits, java.lang.Long::class.java)
            .bindNullable("currency", transaction.currency, String::class.java)
            .bind("purchaseAt", transaction.purchaseAt.utc())
            .bindNullable("originalPurchaseAt", transaction.originalPurchaseAt?.utc(), LocalDateTime::class.java)
            .bindNullable("expiresAt", transaction.expiresAt?.utc(), LocalDateTime::class.java)
            .bindNullable("revocationAt", transaction.revocationAt?.utc(), LocalDateTime::class.java)
            .bindNullable("revocationReason", transaction.revocationReason, java.lang.Integer::class.java)
            .bind("signedAt", transaction.signedAt.utc())
            .bind("verifiedAt", command.occurredAt.utc())
            .bind("hash", transaction.signedPayloadSha256)
            .bind("now", command.occurredAt.utc())
            .fetch().rowsUpdated().awaitSingle()
        return lastInsertedId()
    }

    private suspend fun transitionPayment(
        payment: PaymentEntity,
        toStatus: PaymentStatus,
        eventType: PaymentHistoryEventType,
        eventId: String,
        source: BillingEventSource,
        notificationUUID: String?,
        reason: String?,
        now: Instant,
        historyInvoiceId: Long = payment.invoiceId,
    ) {
        if (payment.status == toStatus) return
        val changed = database.sql(
            """
            update payments
            set status = :status, version = version + 1,
                revocation_at = case when :status in ('REFUNDED', 'REVOKED') then coalesce(revocation_at, :now) else revocation_at end,
                updated_at = :now
            where id = :id and version = :version
            """.trimIndent(),
        ).bind("status", toStatus.name).bind("now", now.utc())
            .bind("id", payment.id).bind("version", payment.version)
            .fetch().rowsUpdated().awaitSingle()
        if (changed != 1L) {
            throw billingFailure(ApiErrorCode.BILLING_TRANSACTION_CONFLICT, "Payment version changed concurrently.", HttpStatus.CONFLICT)
        }
        insertPaymentHistory(
            payment.id, historyInvoiceId, eventId, eventType, source,
            payment.status, toStatus, notificationUUID, reason, now,
        )
    }

    private suspend fun insertPaymentHistory(
        paymentId: Long,
        invoiceId: Long,
        eventId: String,
        eventType: PaymentHistoryEventType,
        source: BillingEventSource,
        fromStatus: PaymentStatus?,
        toStatus: PaymentStatus,
        providerNotificationUUID: String?,
        reason: String?,
        occurredAt: Instant,
    ) {
        database.sql(
            """
            insert into payments_history (
                payment_id, invoice_id, event_id, event_type, source, from_status, to_status,
                provider_notification_uuid, reason, occurred_at, created_at
            ) values (
                :paymentId, :invoiceId, :eventId, :eventType, :source, :fromStatus, :toStatus,
                :notificationUUID, :reason, :occurredAt, :createdAt
            )
            """.trimIndent(),
        ).bind("paymentId", paymentId).bind("invoiceId", invoiceId)
            .bind("eventId", eventId.take(191)).bind("eventType", eventType.name).bind("source", source.name)
            .bindNullable("fromStatus", fromStatus?.name, String::class.java).bind("toStatus", toStatus.name)
            .bindNullable("notificationUUID", providerNotificationUUID, String::class.java)
            .bindNullable("reason", reason?.take(1000), String::class.java)
            .bind("occurredAt", occurredAt.utc()).bind("createdAt", occurredAt.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun grantMembership(
        invoice: InvoiceEntity,
        payment: PaymentEntity,
        providerEventAt: Instant,
        now: Instant,
    ) {
        lockBillingAccountForProjection(invoice.userId)
        val previousTierCode = projectedTierCode(invoice.userId)
        val previousTierRank = MonthlyQuestionQuotaPolicy.tierRank(previousTierCode)
        val nextTierRank = MonthlyQuestionQuotaPolicy.tierRank(invoice.tierCode)
        val downgradeSnapshot = if (nextTierRank < previousTierRank) {
            quotaTransitionSnapshot(invoice.userId, previousTierCode, payment.purchaseAt)
                ?: throw billingFailure(
                    ApiErrorCode.INTERNAL_SERVER_ERROR,
                    "Previous quota could not be locked for a paid tier downgrade.",
                )
        } else {
            null
        }
        upsertActiveSubscription(invoice, payment, providerEventAt, now)
        if (subscriptionAllowsEntitlement(payment.providerOriginalTransactionId)) {
            upsertActiveMembership(invoice, payment, now)
        } else {
            deactivateMembership(payment.providerOriginalTransactionId, invoice.id, now)
        }
        rebuildEntitlementProjection(invoice.userId, now)
        val appliedTierCode = projectedTierCode(invoice.userId)
        activateFirstPaidAnchor(invoice.userId, payment.purchaseAt, now)
        if (appliedTierCode == invoice.tierCode && appliedTierCode != previousTierCode) {
            resetQuotaForPaidTierChange(
                invoice = invoice,
                previousTierCode = previousTierCode,
                previousQuota = downgradeSnapshot,
                purchasedAt = payment.purchaseAt,
                now = now,
            )
        }
    }

    private suspend fun projectedTierCode(userId: Long): String =
        database.sql("select tier_code from user_entitlement_projection where user_id = :userId")
            .bind("userId", userId)
            .map { row, _ -> row.string("tier_code") }
            .one().awaitSingleOrNull() ?: "TIER1"

    private suspend fun lockBillingAccountForProjection(userId: Long) {
        database.sql("select id from billing_accounts where user_id = :userId for update")
            .bind("userId", userId)
            .map { row, _ -> row.long("id") }
            .one().awaitSingle()
    }

    private suspend fun upsertActiveMembership(invoice: InvoiceEntity, payment: PaymentEntity, now: Instant) {
        database.sql(
            """
            insert into user_memberships (
                user_id, tier, monthly_question_limit_override, status, source, source_invoice_id,
                original_transaction_id, started_at, expires_at, created_at, updated_at
            ) values (
                :userId, :tier, null, 'ACTIVE', 'APPLE', :invoiceId,
                :originalTransactionId, :startedAt, :expiresAt, :now, :now
            )
            on duplicate key update
                user_id = values(user_id), tier = values(tier), status = 'ACTIVE', source = 'APPLE',
                source_invoice_id = values(source_invoice_id),
                started_at = least(started_at, values(started_at)), expires_at = values(expires_at), updated_at = values(updated_at)
            """.trimIndent(),
        ).bind("userId", invoice.userId).bind("tier", invoice.tierCode).bind("invoiceId", invoice.id)
            .bind("originalTransactionId", payment.providerOriginalTransactionId)
            .bind("startedAt", payment.purchaseAt.utc())
            .bindNullable("expiresAt", payment.expiresAt?.utc(), LocalDateTime::class.java)
            .bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun reactivateMembership(
        invoice: InvoiceEntity,
        payment: PaymentEntity,
        providerEventAt: Instant,
        now: Instant,
    ) = grantMembership(invoice, payment, providerEventAt, now)

    private suspend fun deactivateMembership(originalTransactionId: String, invoiceId: Long, now: Instant) {
        database.sql(
            """
            update user_memberships
            set status = 'INACTIVE', updated_at = :now
            where source = 'APPLE' and original_transaction_id = :originalTransactionId
              and source_invoice_id = :invoiceId
            """.trimIndent(),
        ).bind("originalTransactionId", originalTransactionId).bind("invoiceId", invoiceId)
            .bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun upsertActiveSubscription(
        invoice: InvoiceEntity,
        payment: PaymentEntity,
        providerEventAt: Instant,
        now: Instant,
    ) {
        val ownership = lockSubscriptionOwnership(payment.providerOriginalTransactionId)
        val paymentMatchesLatestTransaction =
            ownership?.latestTransactionId == payment.providerTransactionId
        val paymentOwnsProjection = ownership == null ||
            ownership.latestPurchaseAt == null ||
            payment.purchaseAt.isAfter(ownership.latestPurchaseAt) ||
            (payment.purchaseAt == ownership.latestPurchaseAt && paymentMatchesLatestTransaction)
        // Product changes and lifecycle events have independent provider clocks. A newer cancellation
        // may preserve lifecycle state while the verified transaction completes its pending tier change.
        val lifecycleProjectionAccepted = paymentOwnsProjection &&
            ownership?.lastProviderEventAt?.isAfter(providerEventAt) != true
        val pendingTransitionRepairAccepted = paymentOwnsProjection &&
            !lifecycleProjectionAccepted &&
            ownership?.pendingProductId == payment.productId &&
            ownership.accessStatus in ENTITLEMENT_GRANTING_ACCESS_STATES
        val productProjectionAccepted = lifecycleProjectionAccepted || pendingTransitionRepairAccepted
        database.sql(
            """
            insert into billing_accounts (user_id, app_account_token, status, created_at, updated_at)
            values (:userId, :token, 'ACTIVE', :now, :now)
            on duplicate key update status = 'ACTIVE', updated_at = :now
            """.trimIndent(),
        ).bind("userId", invoice.userId).bind("token", payment.appAccountToken.toString().lowercase())
            .bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
        val accountId = database.sql("select id from billing_accounts where user_id = :userId")
            .bind("userId", invoice.userId).map { row, _ -> row.long("id") }.one().awaitSingle()
        val eventType = if (payment.purchaseAt == payment.originalPurchaseAt || payment.originalPurchaseAt == null) {
            "INITIAL_PURCHASE"
        } else {
            "RENEWAL"
        }
        database.sql(
            """
            insert ignore into subscription_events (
                provider_event_id, provider, event_type, user_id, billing_account_id,
                original_transaction_id, transaction_id, product_id, environment, purchased_at, expires_at,
                access_status, renewal_status, processing_status, attempt_count, max_attempts, next_attempt_at,
                payload_sha256, occurred_at, processed_at, created_at, updated_at
            ) values (
                :eventId, 'APPLE', :eventType, :userId, :accountId,
                :originalTransactionId, :transactionId, :productId, :environment, :purchasedAt, :expiresAt,
                'ACTIVE', 'WILL_RENEW', 'COMPLETED', 1, 3, :now,
                :hash, :occurredAt, :now, :now, :now
            )
            """.trimIndent(),
        ).bind("eventId", "apple:${payment.providerTransactionId}:$eventType".take(191))
            .bind("eventType", eventType).bind("userId", invoice.userId).bind("accountId", accountId)
            .bind("originalTransactionId", payment.providerOriginalTransactionId)
            .bind("transactionId", payment.providerTransactionId).bind("productId", payment.productId)
            .bind("environment", payment.environment.name).bind("purchasedAt", payment.purchaseAt.utc())
            .bindNullable("expiresAt", payment.expiresAt?.utc(), LocalDateTime::class.java)
            .bind("hash", payment.signedPayloadSha256).bind("occurredAt", payment.purchaseAt.utc())
            .bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
        database.sql(
            """
            insert into subscriptions (
                billing_account_id, user_id, provider, original_transaction_id, latest_transaction_id,
                product_id, tier_code, access_status, renewal_status, started_at, expires_at,
                last_provider_event_at, last_reconciled_at, next_reconcile_at, version, created_at, updated_at
            ) values (
                :accountId, :userId, 'APPLE', :originalTransactionId, :transactionId,
                :productId, :tierCode, 'ACTIVE', 'WILL_RENEW', :startedAt, :expiresAt,
                :providerEventAt, :now, timestampadd(hour, 6, :now), 0, :now, :now
            ) on duplicate key update
                billing_account_id = if(:paymentOwnsProjection, values(billing_account_id), billing_account_id),
                user_id = if(:paymentOwnsProjection, values(user_id), user_id),
                latest_transaction_id = if(:paymentOwnsProjection, values(latest_transaction_id), latest_transaction_id),
                product_id = if(:productProjectionAccepted, values(product_id), product_id),
                tier_code = if(:productProjectionAccepted, values(tier_code), tier_code),
                access_status = if(:lifecycleProjectionAccepted, 'ACTIVE', access_status),
                renewal_status = if(:lifecycleProjectionAccepted, 'WILL_RENEW', renewal_status),
                started_at = if(
                    :productProjectionAccepted,
                    least(coalesce(started_at, values(started_at)), values(started_at)),
                    started_at
                ),
                expires_at = case
                    when :lifecycleProjectionAccepted then values(expires_at)
                    when :pendingTransitionRepairAccepted then case
                        when expires_at is null then values(expires_at)
                        when values(expires_at) is null then expires_at
                        else greatest(expires_at, values(expires_at))
                    end
                    else expires_at
                end,
                pending_product_event_at = if(
                    :productProjectionAccepted and pending_product_id = values(product_id),
                    null,
                    pending_product_event_at
                ),
                pending_product_id = if(
                    :productProjectionAccepted and pending_product_id = values(product_id),
                    null,
                    pending_product_id
                ),
                next_reconcile_at = if(:lifecycleProjectionAccepted, values(next_reconcile_at), next_reconcile_at),
                version = if(:productProjectionAccepted, version + 1, version),
                updated_at = if(:productProjectionAccepted, values(updated_at), updated_at),
                last_provider_event_at = if(
                    :lifecycleProjectionAccepted,
                    greatest(coalesce(last_provider_event_at, values(last_provider_event_at)), values(last_provider_event_at)),
                    last_provider_event_at
                )
            """.trimIndent(),
        ).bind("accountId", accountId).bind("userId", invoice.userId)
            .bind("paymentOwnsProjection", paymentOwnsProjection)
            .bind("lifecycleProjectionAccepted", lifecycleProjectionAccepted)
            .bind("pendingTransitionRepairAccepted", pendingTransitionRepairAccepted)
            .bind("productProjectionAccepted", productProjectionAccepted)
            .bind("originalTransactionId", payment.providerOriginalTransactionId)
            .bind("transactionId", payment.providerTransactionId).bind("productId", payment.productId)
            .bind("tierCode", invoice.tierCode).bind("startedAt", payment.purchaseAt.utc())
            .bindNullable("expiresAt", payment.expiresAt?.utc(), LocalDateTime::class.java)
            .bind("providerEventAt", providerEventAt.utc()).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        if (paymentOwnsProjection && ownership?.userId != null && ownership.userId != invoice.userId) {
            database.sql(
                """
                update user_memberships
                set status = 'INACTIVE', updated_at = :now
                where source = 'APPLE' and original_transaction_id = :originalTransactionId
                  and user_id = :previousUserId and status = 'ACTIVE'
                """.trimIndent(),
            ).bind("now", now.utc())
                .bind("originalTransactionId", payment.providerOriginalTransactionId)
                .bind("previousUserId", ownership.userId)
                .fetch().rowsUpdated().awaitSingle()
            rebuildEntitlementProjection(ownership.userId, now)
        }
    }

    private suspend fun lockSubscriptionOwnership(originalTransactionId: String): SubscriptionOwnership? =
        database.sql(
            """
            select s.user_id, s.latest_transaction_id, s.pending_product_id,
                   s.access_status, s.last_provider_event_at,
                   (select p.purchase_at
                    from payments p
                    where p.provider = 'APPLE'
                      and p.provider_transaction_id = s.latest_transaction_id
                    limit 1) as latest_purchase_at
            from subscriptions s
            where s.provider = 'APPLE' and s.original_transaction_id = :originalTransactionId
            for update
            """.trimIndent(),
        ).bind("originalTransactionId", originalTransactionId)
            .map { row, _ ->
                SubscriptionOwnership(
                    userId = row.get("user_id", java.lang.Long::class.java)?.toLong(),
                    latestTransactionId = row.nullableString("latest_transaction_id"),
                    latestPurchaseAt = row.nullableInstant("latest_purchase_at"),
                    pendingProductId = row.nullableString("pending_product_id"),
                    accessStatus = row.string("access_status"),
                    lastProviderEventAt = row.nullableInstant("last_provider_event_at"),
                )
            }.one().awaitSingleOrNull()

    private suspend fun subscriptionOwnedBy(originalTransactionId: String, userId: Long): Boolean =
        database.sql(
            """
            select count(*) as owned
            from subscriptions
            where provider = 'APPLE'
              and original_transaction_id = :originalTransactionId
              and user_id = :userId
            """.trimIndent(),
        ).bind("originalTransactionId", originalTransactionId)
            .bind("userId", userId)
            .map { row, _ -> row.long("owned") > 0 }
            .one().awaitSingle()

    private suspend fun subscriptionAllowsEntitlement(originalTransactionId: String): Boolean =
        database.sql(
            "select access_status from subscriptions where original_transaction_id = :id limit 1",
        ).bind("id", originalTransactionId)
            .map { row, _ -> row.string("access_status") in ENTITLEMENT_GRANTING_ACCESS_STATES }
            .one().awaitSingleOrNull() == true

    private suspend fun rebuildEntitlementProjection(userId: Long, now: Instant) {
        val best = database.sql(
            """
            select id, tier_code, product_id, access_status, renewal_status, started_at, expires_at,
                   pending_product_id
            from subscriptions
            where user_id = :userId and access_status in ('ACTIVE', 'GRACE_PERIOD')
              and (expires_at is null or expires_at > :now)
            order by case tier_code when 'TIER3' then 3 when 'TIER2' then 2 else 1 end desc,
                     expires_at desc, id desc
            limit 1
            """.trimIndent(),
        ).bind("userId", userId).bind("now", now.utc()).map { row, _ ->
            ActiveSubscriptionProjection(
                id = row.long("id"), tierCode = row.string("tier_code"), productId = row.nullableString("product_id"),
                accessStatus = row.string("access_status"), renewalStatus = row.string("renewal_status"),
                startedAt = row.nullableInstant("started_at"), expiresAt = row.nullableInstant("expires_at"),
                pendingProductId = row.nullableString("pending_product_id"),
            )
        }.one().awaitSingleOrNull()
        if (best == null) {
            database.sql(
                """
                insert into user_entitlement_projection (
                    user_id, subscription_id, tier_code, source, access_status, renewal_status,
                    will_renew, projected_at, version
                ) values (:userId, null, 'TIER1', 'FREE', 'ACTIVE', 'NOT_APPLICABLE', false, :now, 0)
                on duplicate key update subscription_id = null, tier_code = 'TIER1', source = 'FREE',
                    access_status = 'ACTIVE', renewal_status = 'NOT_APPLICABLE', product_id = null,
                    started_at = null, expires_at = null, will_renew = false, pending_product_id = null,
                    projected_at = :now, version = version + 1
                """.trimIndent(),
            ).bind("userId", userId).bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
            return
        }
        val willRenew = best.renewalStatus == "WILL_RENEW"
        var spec = database.sql(
            """
            insert into user_entitlement_projection (
                user_id, subscription_id, tier_code, source, access_status, renewal_status, product_id,
                started_at, expires_at, will_renew, pending_product_id, projected_at, version
            ) values (
                :userId, :subscriptionId, :tierCode, 'APP_STORE', :accessStatus, :renewalStatus, :productId,
                :startedAt, :expiresAt, :willRenew, :pendingProductId, :now, 0
            ) on duplicate key update subscription_id = values(subscription_id), tier_code = values(tier_code),
                source = 'APP_STORE', access_status = values(access_status), renewal_status = values(renewal_status),
                product_id = values(product_id), started_at = values(started_at), expires_at = values(expires_at),
                will_renew = values(will_renew), pending_product_id = values(pending_product_id),
                projected_at = values(projected_at), version = version + 1
            """.trimIndent(),
        ).bind("userId", userId).bind("subscriptionId", best.id).bind("tierCode", best.tierCode)
            .bind("accessStatus", best.accessStatus).bind("renewalStatus", best.renewalStatus)
            .bind("willRenew", willRenew).bind("now", now.utc())
        spec = spec.bindNullable("productId", best.productId, String::class.java)
            .bindNullable("startedAt", best.startedAt?.utc(), LocalDateTime::class.java)
            .bindNullable("expiresAt", best.expiresAt?.utc(), LocalDateTime::class.java)
            .bindNullable("pendingProductId", best.pendingProductId, String::class.java)
        spec.fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun advanceProviderEventCursor(
        originalTransactionId: String,
        providerEventAt: Instant,
        processedAt: Instant,
    ): Boolean {
        val lastProviderEventAt = database.sql(
            "select last_provider_event_at from subscriptions where original_transaction_id = :id for update",
        ).bind("id", originalTransactionId)
            .map { row, _ -> row.nullableInstant("last_provider_event_at") ?: Instant.EPOCH }
            .one().awaitSingleOrNull()
            ?: throw billingFailure(
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "Subscription projection has not been created for the provider event yet.",
            )
        if (lastProviderEventAt.isAfter(providerEventAt)) return false
        database.sql(
            """
            update subscriptions
            set last_provider_event_at = :providerEventAt, updated_at = :processedAt
            where original_transaction_id = :originalTransactionId
            """.trimIndent(),
        ).bind("providerEventAt", providerEventAt.utc()).bind("processedAt", processedAt.utc())
            .bind("originalTransactionId", originalTransactionId)
            .fetch().rowsUpdated().awaitSingle()
        return true
    }

    private suspend fun updateSubscriptionLifecycle(
        originalTransactionId: String,
        accessStatus: String?,
        renewalStatus: String?,
        providerEventAt: Instant,
        processedAt: Instant,
        nextReconcileAt: Instant = processedAt.plusSeconds(6 * 60 * 60),
    ) {
        var spec = database.sql(
            """
            update subscriptions
            set access_status = coalesce(:accessStatus, access_status),
                renewal_status = coalesce(:renewalStatus, renewal_status),
                last_provider_event_at = :providerEventAt, next_reconcile_at = :nextReconcileAt,
                version = version + 1, updated_at = :processedAt
            where original_transaction_id = :originalTransactionId
            """.trimIndent(),
        ).bindNullable("accessStatus", accessStatus, String::class.java)
            .bindNullable("renewalStatus", renewalStatus, String::class.java)
            .bind("providerEventAt", providerEventAt.utc()).bind("processedAt", processedAt.utc())
            .bind("nextReconcileAt", nextReconcileAt.utc())
            .bind("originalTransactionId", originalTransactionId)
        spec.fetch().rowsUpdated().awaitSingle()
        val userId = database.sql(
            "select user_id from subscriptions where original_transaction_id = :originalTransactionId limit 1",
        ).bind("originalTransactionId", originalTransactionId)
            .map { row, _ -> row.long("user_id") }.one().awaitSingleOrNull()
        if (userId != null) rebuildEntitlementProjection(userId, processedAt)
    }

    private suspend fun updatePendingProduct(
        originalTransactionId: String,
        productId: String?,
        providerEventAt: Instant,
        now: Instant,
    ) {
        var spec = database.sql(
            """
            update subscriptions
            set pending_product_id = :productId, pending_product_event_at = :eventAt,
                next_reconcile_at = :now, version = version + 1, updated_at = :now
            where original_transaction_id = :originalTransactionId
              and (pending_product_event_at is null or pending_product_event_at <= :eventAt)
            """.trimIndent(),
        ).bindNullable("productId", productId, String::class.java)
            .bind("eventAt", providerEventAt.utc()).bind("now", now.utc())
            .bind("originalTransactionId", originalTransactionId)
        val changed = spec.fetch().rowsUpdated().awaitSingle()
        if (changed == 0L) return
        val userId = database.sql(
            "select user_id from subscriptions where original_transaction_id = :originalTransactionId limit 1",
        ).bind("originalTransactionId", originalTransactionId)
            .map { row, _ -> row.long("user_id") }.one().awaitSingleOrNull()
        if (userId != null) rebuildEntitlementProjection(userId, now)
    }

    private suspend fun activateFirstPaidAnchor(userId: Long, purchasedAt: Instant, now: Instant) {
        database.sql(
            """
            insert ignore into quota_accounts (
                user_id, anchor_type, anchor_at, anchor_day, first_paid_at,
                policy_version, created_at, updated_at
            )
            select id, 'ACCOUNT_CREATED', created_at, day(created_at), null, :policyVersion, :now, :now
            from users where id = :userId
            """.trimIndent(),
        ).bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION)
            .bind("now", now.utc()).bind("userId", userId).fetch().rowsUpdated().awaitSingle()
        val account = database.sql(
            "select anchor_at, first_paid_at from quota_accounts where user_id = :userId for update",
        ).bind("userId", userId).map { row, _ ->
            row.instant("anchor_at") to row.nullableInstant("first_paid_at")
        }.one().awaitSingleOrNull() ?: return
        if (account.second?.let { !purchasedAt.isBefore(it) } == true) return
        val oldWindow = MonthlyQuotaWindow.periodAt(account.first, now)
        val newWindow = MonthlyQuotaWindow.periodAt(purchasedAt, now)
        if (oldWindow.startedAt != newWindow.startedAt) {
            val carried = database.sql(
                """
                select committed_count, reserved_count, bonus_count, policy_version
                from quota_periods where user_id = :userId and period_started_at = :oldStart
                """.trimIndent(),
            ).bind("userId", userId).bind("oldStart", oldWindow.startedAt.utc()).map { row, _ ->
                listOf(
                    row.int("committed_count"),
                    row.int("reserved_count"),
                    row.int("bonus_count"),
                    row.int("policy_version"),
                )
            }.one().awaitSingleOrNull()
            if (carried != null) {
                database.sql(
                    """
                    insert ignore into quota_periods (
                        user_id, period_started_at, period_ends_at, committed_count, reserved_count, bonus_count,
                        policy_version, created_at, updated_at
                    ) values (:userId, :newStart, :newEnd, 0, 0, 0, :policyVersion, :now, :now)
                    """.trimIndent(),
                ).bind("userId", userId).bind("newStart", newWindow.startedAt.utc())
                    .bind("newEnd", newWindow.resetAt.utc()).bind("policyVersion", carried[3])
                    .bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
                database.sql(
                    """
                    update quota_periods
                    set committed_count = greatest(committed_count, :committed),
                        reserved_count = greatest(reserved_count, :reserved),
                        bonus_count = greatest(bonus_count, :bonus), updated_at = :now
                    where user_id = :userId and period_started_at = :newStart
                    """.trimIndent(),
                ).bind("committed", carried[0]).bind("reserved", carried[1]).bind("bonus", carried[2])
                    .bind("now", now.utc()).bind("userId", userId).bind("newStart", newWindow.startedAt.utc())
                    .fetch().rowsUpdated().awaitSingle()
            }
            val newPeriodId = database.sql(
                "select id from quota_periods where user_id = :userId and period_started_at = :newStart",
            ).bind("userId", userId).bind("newStart", newWindow.startedAt.utc())
                .map { row, _ -> row.long("id") }.one().awaitSingleOrNull()
            if (newPeriodId != null) {
                database.sql(
                    """
                    update quota_reservations r join quota_periods p on p.id = r.quota_period_id
                    set r.quota_period_id = :newPeriodId, r.updated_at = :now
                    where r.user_id = :userId and p.period_started_at = :oldStart and r.status = 'RESERVED'
                    """.trimIndent(),
                ).bind("newPeriodId", newPeriodId).bind("now", now.utc()).bind("userId", userId)
                    .bind("oldStart", oldWindow.startedAt.utc()).fetch().rowsUpdated().awaitSingle()
            }
        }
        database.sql(
            """
            update quota_accounts
            set anchor_type = 'FIRST_PAID', anchor_at = :purchasedAt, anchor_day = :anchorDay,
                first_paid_at = :purchasedAt, updated_at = :now
            where user_id = :userId and (first_paid_at is null or first_paid_at > :purchasedAt)
            """.trimIndent(),
        ).bind("purchasedAt", purchasedAt.utc()).bind("anchorDay", purchasedAt.atZone(ZoneOffset.UTC).dayOfMonth)
            .bind("now", now.utc()).bind("userId", userId).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun quotaTransitionSnapshot(
        userId: Long,
        tierCode: String,
        purchasedAt: Instant,
    ): QuotaTransitionSnapshot? {
        val accountAnchor = database.sql(
            "select anchor_at from quota_accounts where user_id = :userId for update",
        ).bind("userId", userId)
            .map { row, _ -> row.instant("anchor_at") }
            .one().awaitSingleOrNull() ?: return null
        val snapshotAt = purchasedAt.minusNanos(1).takeUnless { it.isBefore(accountAnchor) } ?: accountAnchor
        val previousWindow = MonthlyQuotaWindow.periodAt(accountAnchor, snapshotAt)
        val baseLimit = database.sql(
            "select monthly_question_limit from user_membership_tiers where tier_code = :tierCode",
        ).bind("tierCode", tierCode)
            .map { row, _ -> row.int("monthly_question_limit") }
            .one().awaitSingleOrNull() ?: return null
        val counters = database.sql(
            """
            select committed_count, bonus_count
            from quota_periods
            where user_id = :userId and period_started_at = :periodStartedAt
            for update
            """.trimIndent(),
        ).bind("userId", userId).bind("periodStartedAt", previousWindow.startedAt.utc())
            .map { row, _ -> row.int("committed_count") to row.int("bonus_count") }
            .one().awaitSingleOrNull() ?: (0 to 0)
        return QuotaTransitionSnapshot(
            baseLimit = baseLimit,
            committedCount = counters.first,
            bonusCount = counters.second,
            periodStartedAt = previousWindow.startedAt,
        )
    }

    private suspend fun resetQuotaForPaidTierChange(
        invoice: InvoiceEntity,
        previousTierCode: String,
        previousQuota: QuotaTransitionSnapshot?,
        purchasedAt: Instant,
        now: Instant,
    ) {
        val ledgerEventId = "billing-tier-change:${invoice.id}".take(191)
        val legacyUpgradeEventId = "billing-tier-upgrade:${invoice.id}".take(191)
        val alreadyApplied = database.sql(
            """
            select count(*) as count_value
            from quota_ledger
            where ledger_event_id in (:eventId, :legacyEventId)
            """.trimIndent(),
        ).bind("eventId", ledgerEventId).bind("legacyEventId", legacyUpgradeEventId)
            .map { row, _ -> row.long("count_value") > 0 }
            .one().awaitSingle()
        if (alreadyApplied) return

        val accountAnchor = database.sql(
            "select anchor_at from quota_accounts where user_id = :userId for update",
        ).bind("userId", invoice.userId)
            .map { row, _ -> row.instant("anchor_at") }
            .one().awaitSingleOrNull() ?: return
        val previousAt = purchasedAt.minusNanos(1).takeUnless { it.isBefore(accountAnchor) } ?: accountAnchor
        val previousWindow = MonthlyQuotaWindow.periodAt(accountAnchor, previousAt)
        val changedWindow = MonthlyQuotaWindow.periodAt(purchasedAt, purchasedAt)
        val previousPeriodStartedAt = previousQuota?.periodStartedAt ?: previousWindow.startedAt
        val carriedBonus = MonthlyQuestionQuotaPolicy.carriedBonusForTierChange(
            previousTierRank = MonthlyQuestionQuotaPolicy.tierRank(previousTierCode),
            nextTierRank = MonthlyQuestionQuotaPolicy.tierRank(invoice.tierCode),
            previousBaseLimit = previousQuota?.baseLimit ?: 0,
            previousBonusLimit = previousQuota?.bonusCount ?: 0,
            previousCommittedCount = previousQuota?.committedCount ?: 0,
        )

        database.sql(
            """
            insert ignore into quota_periods (
                user_id, period_started_at, period_ends_at, committed_count, reserved_count, bonus_count,
                policy_version, created_at, updated_at
            ) values (:userId, :startedAt, :endsAt, 0, 0, 0, :policyVersion, :now, :now)
            """.trimIndent(),
        ).bind("userId", invoice.userId).bind("startedAt", changedWindow.startedAt.utc())
            .bind("endsAt", changedWindow.resetAt.utc()).bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION)
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        val changedPeriodId = database.sql(
            "select id from quota_periods where user_id = :userId and period_started_at = :startedAt for update",
        ).bind("userId", invoice.userId).bind("startedAt", changedWindow.startedAt.utc())
            .map { row, _ -> row.long("id") }.one().awaitSingle()

        if (previousWindow.startedAt != changedWindow.startedAt) {
            database.sql(
                """
                update quota_reservations r join quota_periods p on p.id = r.quota_period_id
                set r.quota_period_id = :newPeriodId, r.updated_at = :now
                where r.user_id = :userId and p.period_started_at = :previousStart and r.status = 'RESERVED'
                """.trimIndent(),
            ).bind("newPeriodId", changedPeriodId).bind("now", now.utc()).bind("userId", invoice.userId)
                .bind("previousStart", previousPeriodStartedAt.utc())
                .fetch().rowsUpdated().awaitSingle()
            database.sql(
                """
                update quota_periods
                set reserved_count = 0, updated_at = :now
                where user_id = :userId and period_started_at = :previousStart
                """.trimIndent(),
            ).bind("now", now.utc()).bind("userId", invoice.userId)
                .bind("previousStart", previousPeriodStartedAt.utc())
                .fetch().rowsUpdated().awaitSingle()
        }
        val reservedCount = database.sql(
            "select count(*) as count_value from quota_reservations where quota_period_id = :periodId and status = 'RESERVED'",
        ).bind("periodId", changedPeriodId)
            .map { row, _ -> row.long("count_value").toInt() }.one().awaitSingle()
        database.sql(
            """
            update quota_periods
            set committed_count = 0, reserved_count = :reservedCount, bonus_count = :bonusCount,
                policy_version = :policyVersion, updated_at = :now
            where id = :periodId
            """.trimIndent(),
        ).bind("reservedCount", reservedCount).bind("bonusCount", carriedBonus)
            .bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION)
            .bind("now", now.utc()).bind("periodId", changedPeriodId)
            .fetch().rowsUpdated().awaitSingle()
        database.sql(
            """
            update quota_accounts
            set anchor_type = 'FIRST_PAID', anchor_at = :purchasedAt, anchor_day = :anchorDay,
                first_paid_at = least(coalesce(first_paid_at, :purchasedAt), :purchasedAt),
                policy_version = :policyVersion, updated_at = :now
            where user_id = :userId
            """.trimIndent(),
        ).bind("purchasedAt", purchasedAt.utc()).bind("anchorDay", purchasedAt.atZone(ZoneOffset.UTC).dayOfMonth)
            .bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION)
            .bind("now", now.utc()).bind("userId", invoice.userId).fetch().rowsUpdated().awaitSingle()
        database.sql(
            """
            insert into quota_ledger (
                ledger_event_id, user_id, quota_period_id, reservation_id, ledger_type,
                committed_delta, reserved_delta, bonus_delta, reason, occurred_at, created_at
            ) values (:eventId, :userId, :periodId, null, 'MIGRATION_ADJUSTMENT', 0, 0, :bonusDelta, :reason, :now, :now)
            """.trimIndent(),
        ).bind("eventId", ledgerEventId).bind("userId", invoice.userId).bind("periodId", changedPeriodId)
            .bind("bonusDelta", carriedBonus)
            .bind("reason", "Immediate quota reset for paid tier change $previousTierCode -> ${invoice.tierCode}")
            .bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
        database.sql(
            """
            insert into user_monthly_question_usage (
                user_id, usage_month, period_start, system_question_count,
                current_period_question_limit_override, created_at, updated_at
            ) values (:userId, :usageMonth, :startedAt, 0, null, :now, :now)
            on duplicate key update system_question_count = 0,
                current_period_question_limit_override = null, updated_at = :now
            """.trimIndent(),
        ).bind("userId", invoice.userId).bind("usageMonth", changedWindow.usageMonth.toString())
            .bind("startedAt", changedWindow.startedAt.utc()).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    private data class QuotaTransitionSnapshot(
        val baseLimit: Int,
        val committedCount: Int,
        val bonusCount: Int,
        val periodStartedAt: Instant,
    )

    private suspend fun insertBillingJob(invoiceId: Long, paymentId: Long, type: BillingJobType, now: Instant) {
        database.sql(
            """
            insert into billing_fulfillment_outbox (
                job_id, invoice_id, payment_id, job_type, status, attempts, max_attempts,
                next_attempt_at, created_at, updated_at
            ) values (
                :jobId, :invoiceId, :paymentId, :type, 'PENDING', 0, 3, :now, :now, :now
            ) on duplicate key update updated_at = updated_at
            """.trimIndent(),
        ).bind("jobId", UUID.randomUUID().toString()).bind("invoiceId", invoiceId).bind("paymentId", paymentId)
            .bind("type", type.name).bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertActionIfAbsent(
        invoice: InvoiceEntity,
        payment: PaymentEntity,
        actionType: BillingActionType,
        status: BillingActionStatus,
        idempotencyKey: String,
        reason: String?,
        now: Instant,
    ): BillingAction {
        val actionId = UUID.randomUUID()
        database.sql(
            """
            insert ignore into billing_actions (
                action_id, idempotency_key, invoice_id, payment_id, user_id, action_type, status,
                reason, requested_at, created_at, updated_at
            ) values (
                :actionId, :idempotencyKey, :invoiceId, :paymentId, :userId, :actionType, :status,
                :reason, :now, :now, :now
            )
            """.trimIndent(),
        ).bind("actionId", actionId.toString()).bind("idempotencyKey", idempotencyKey)
            .bind("invoiceId", invoice.id).bind("paymentId", payment.id).bind("userId", invoice.userId)
            .bind("actionType", actionType.name).bind("status", status.name)
            .bindNullable("reason", reason, String::class.java).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        return existingAction(invoice.userId, actionType, idempotencyKey)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Billing action could not be read.")
    }

    private suspend fun existingAction(userId: Long, type: BillingActionType, idempotencyKey: String): BillingAction? =
        database.sql(ACTION_SQL + " where a.user_id = :userId and a.action_type = :type and a.idempotency_key = :key")
            .bind("userId", userId).bind("type", type.name).bind("key", idempotencyKey)
            .map { row, _ -> row.billingAction() }.one().awaitSingleOrNull()

    private suspend fun actions(invoiceId: Long): List<BillingAction> =
        database.sql(ACTION_SQL + " where a.invoice_id = :invoiceId order by a.created_at, a.id")
            .bind("invoiceId", invoiceId).map { row, _ -> row.billingAction() }
            .all().collectList().awaitSingle()

    private suspend fun completeActions(
        invoiceId: Long,
        types: Set<BillingActionType>,
        status: BillingActionStatus,
        now: Instant,
    ) {
        if (types.isEmpty()) return
        val names = types.joinToString(",") { "'${it.name}'" }
        database.sql(
            """
            update billing_actions
            set status = :status,
                completed_at = case when :status = 'COMPLETED' then :now else completed_at end,
                failed_at = case when :status in ('DECLINED', 'FAILED') then :now else failed_at end,
                updated_at = :now
            where invoice_id = :invoiceId and action_type in ($names)
              and status in ('REQUIRED', 'REQUESTED', 'AWAITING_APPLE')
            """.trimIndent(),
        ).bind("status", status.name).bind("now", now.utc()).bind("invoiceId", invoiceId)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun markNotification(uuid: String, status: BillingReceiptStatus, error: String?, now: Instant) {
        database.sql(
            """
            update billing_apple_notification_inbox
            set processing_status = :status, processed_at = :processedAt, last_error = :error, updated_at = :now
            where notification_uuid = :uuid
            """.trimIndent(),
        ).bind("status", status.name)
            .bindNullable("processedAt", if (status in TERMINAL_RECEIPT_STATES) now.utc() else null, LocalDateTime::class.java)
            .bindNullable("error", error, String::class.java).bind("now", now.utc()).bind("uuid", uuid)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun markRevenueCatEvent(eventId: String, status: BillingReceiptStatus, error: String?, now: Instant) {
        database.sql(
            """
            update billing_revenuecat_event_inbox
            set processing_status = :status, processed_at = :processedAt, last_error = :error, updated_at = :now
            where event_id = :eventId
            """.trimIndent(),
        ).bind("status", status.name)
            .bindNullable("processedAt", if (status in TERMINAL_RECEIPT_STATES) now.utc() else null, LocalDateTime::class.java)
            .bindNullable("error", error, String::class.java)
            .bind("now", now.utc())
            .bind("eventId", eventId)
            .fetch().rowsUpdated().awaitSingle()
        if (status in TERMINAL_RECEIPT_STATES) {
            database.sql(
                """
                update subscription_events
                set processing_status = :status, processed_at = :now, last_error = :error, updated_at = :now
                where provider = 'REVENUECAT' and provider_event_id = :eventId
                """.trimIndent(),
            ).bind("status", if (status == BillingReceiptStatus.IGNORED) "IGNORED" else "COMPLETED")
                .bindNullable("error", error, String::class.java).bind("now", now.utc()).bind("eventId", eventId)
                .fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun insertSubscriptionEventReceipt(event: VerifiedRevenueCatEvent, now: Instant) {
        val token = sequenceOf(event.appUserId, event.originalAppUserId).plus(event.aliases.asSequence())
            .filterNotNull().mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.firstOrNull()
        val account = token?.let { value ->
            database.sql("select id, user_id from billing_accounts where app_account_token = :token and status = 'ACTIVE'")
                .bind("token", value.toString().lowercase()).map { row, _ -> row.long("id") to row.long("user_id") }
                .one().awaitSingleOrNull()
        }
        val statuses = when (event.eventType) {
            "INITIAL_PURCHASE", "RENEWAL", "NON_RENEWING_PURCHASE" -> "ACTIVE" to "WILL_RENEW"
            "CANCELLATION" -> null to "CANCELED"
            "UNCANCELLATION" -> null to "WILL_RENEW"
            "BILLING_ISSUE" -> null to "BILLING_RETRY"
            "EXPIRATION" -> "EXPIRED" to "NOT_APPLICABLE"
            else -> null to null
        }
        var spec = database.sql(
            """
            insert ignore into subscription_events (
                provider_event_id, provider, event_type, store, provider_reason, price_milliunits, currency,
                user_id, billing_account_id,
                original_transaction_id, transaction_id, product_id, environment, purchased_at, expires_at,
                access_status, renewal_status, processing_status, attempt_count, max_attempts,
                next_attempt_at, payload_sha256, occurred_at, created_at, updated_at
            ) values (
                :eventId, 'REVENUECAT', :eventType, :store, :providerReason, :priceMilliunits, :currency,
                :userId, :accountId,
                :originalTransactionId, :transactionId, :productId, :environment, :purchasedAt, :expiresAt,
                :accessStatus, :renewalStatus, 'PENDING', 0, 3,
                :now, :hash, :occurredAt, :now, :now
            )
            """.trimIndent(),
        ).bind("eventId", event.eventId).bind("eventType", event.eventType)
            .bindNullable("store", event.store, String::class.java)
            .bindNullable("providerReason", event.cancelReason ?: event.expirationReason, String::class.java)
            .bindNullable("priceMilliunits", event.priceMilliunits, java.lang.Long::class.java)
            .bindNullable("currency", event.currency, String::class.java)
            .bindNullable("userId", account?.second, java.lang.Long::class.java)
            .bindNullable("accountId", account?.first, java.lang.Long::class.java)
            .bindNullable("originalTransactionId", event.originalTransactionId, String::class.java)
            .bindNullable("transactionId", event.transactionId, String::class.java)
            .bindNullable("productId", event.productId, String::class.java)
            .bindNullable("environment", event.environment?.name, String::class.java)
            .bindNullable("purchasedAt", event.purchasedAt?.utc(), LocalDateTime::class.java)
            .bindNullable("expiresAt", event.expiresAt?.utc(), LocalDateTime::class.java)
            .bindNullable("accessStatus", statuses.first, String::class.java)
            .bindNullable("renewalStatus", statuses.second, String::class.java)
            .bind("now", now.utc()).bind("hash", event.signedPayloadSha256).bind("occurredAt", event.eventAt.utc())
        spec.fetch().rowsUpdated().awaitSingle()
    }

    private fun VerifiedRevenueCatEvent.toProviderNotification(): VerifiedAppleNotification? {
        val mapped = when (eventType) {
            "CANCELLATION" -> if (cancelReason == "CUSTOMER_SUPPORT") {
                "REFUND" to null
            } else {
                "DID_CHANGE_RENEWAL_STATUS" to "AUTO_RENEW_DISABLED"
            }
            "UNCANCELLATION" -> "DID_CHANGE_RENEWAL_STATUS" to "AUTO_RENEW_ENABLED"
            "EXPIRATION" -> "EXPIRED" to expirationReason
            "REFUND_REVERSED" -> "REFUND_REVERSED" to null
            else -> return null
        }
        return VerifiedAppleNotification(
            notificationUUID = "revenuecat:$eventId".take(191),
            notificationType = mapped.first,
            subtype = mapped.second,
            environment = environment ?: return null,
            signedAt = eventAt,
            signedPayloadSha256 = signedPayloadSha256,
            transaction = null,
        )
    }

    private suspend fun lockAndValidateAccount(userId: Long, token: UUID) {
        val account = database.sql(
            "select user_id, status from billing_accounts where app_account_token = :token for update",
        ).bind("token", token.toString().lowercase()).map { row, _ ->
            row.get("user_id", java.lang.Long::class.java)?.toLong() to row.string("status")
        }.one().awaitSingleOrNull()
        if (account == null || account.first != userId || account.second != "ACTIVE") {
            throw billingFailure(
                ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                "The App Store transaction does not belong to this user.",
                HttpStatus.CONFLICT,
            )
        }
    }

    private suspend fun validateProductMapping(command: RecordVerifiedPaymentCommand) {
        val mapped = tierProduct(command.transaction.productId)
        if (mapped != command.tierProduct) {
            throw billingFailure(ApiErrorCode.BILLING_TRANSACTION_INVALID, "Tier product mapping changed during verification.")
        }
    }

    private suspend fun existingToken(userId: Long): UUID? =
        database.sql("select app_account_token from billing_accounts where user_id = :userId and status = 'ACTIVE'")
            .bind("userId", userId).map { row, _ -> UUID.fromString(row.string("app_account_token")) }
            .one().awaitSingleOrNull()

    private suspend fun legacyToken(userId: Long): UUID? =
        database.sql("select app_account_token from apple_billing_accounts where user_id = :userId")
            .bind("userId", userId).map { row, _ -> UUID.fromString(row.string("app_account_token")) }
            .one().awaitSingleOrNull()

    private suspend fun transferVerifiedSubscriptionOwnership(
        existing: ExistingPayment,
        command: RecordVerifiedPaymentCommand,
    ) {
        val payment = lockPaymentByTransaction(command.transaction.transactionId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Existing payment is missing.")
        if (payment.productId != command.transaction.productId) {
            throw billingFailure(
                ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                "The verified RevenueCat transaction does not match the existing purchase chain.",
                HttpStatus.CONFLICT,
            )
        }
        // RevenueCat can model a resubscription as a new subscription whose original transaction ID
        // is the latest transaction. Preserve the Apple JWS chain already recorded for that transaction.
        val canonicalOriginalTransactionId = payment.providerOriginalTransactionId

        val accountId = database.sql(
            "select id from billing_accounts where user_id = :userId and app_account_token = :token and status = 'ACTIVE' for update",
        ).bind("userId", command.userId)
            .bind("token", command.transaction.appAccountToken.toString().lowercase())
            .map { row, _ -> row.long("id") }
            .one().awaitSingleOrNull()
            ?: throw billingFailure(
                ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                "The RevenueCat transfer destination is not an active billing account.",
                HttpStatus.CONFLICT,
            )

        database.sql(
            """
            update subscriptions
            set billing_account_id = :accountId,
                user_id = :userId,
                latest_transaction_id = :transactionId,
                product_id = :productId,
                tier_code = :tierCode,
                access_status = 'ACTIVE',
                expires_at = :expiresAt,
                last_provider_event_at = greatest(coalesce(last_provider_event_at, :occurredAt), :occurredAt),
                next_reconcile_at = timestampadd(hour, 6, :occurredAt),
                version = version + 1,
                updated_at = :occurredAt
            where provider = 'APPLE' and original_transaction_id = :originalTransactionId
            """.trimIndent(),
        ).bind("accountId", accountId)
            .bind("userId", command.userId)
            .bind("transactionId", command.transaction.transactionId)
            .bind("productId", command.transaction.productId)
            .bind("tierCode", command.tierProduct.tierCode)
            .bindNullable("expiresAt", command.transaction.expiresAt?.utc(), LocalDateTime::class.java)
            .bind("occurredAt", command.occurredAt.utc())
            .bind("originalTransactionId", canonicalOriginalTransactionId)
            .fetch().rowsUpdated().awaitSingle()

        database.sql(
            """
            update user_memberships
            set user_id = :userId,
                tier = :tierCode,
                status = 'ACTIVE',
                expires_at = :expiresAt,
                updated_at = :occurredAt
            where source = 'APPLE' and original_transaction_id = :originalTransactionId
            """.trimIndent(),
        ).bind("userId", command.userId)
            .bind("tierCode", command.tierProduct.tierCode)
            .bindNullable("expiresAt", command.transaction.expiresAt?.utc(), LocalDateTime::class.java)
            .bind("occurredAt", command.occurredAt.utc())
            .bind("originalTransactionId", canonicalOriginalTransactionId)
            .fetch().rowsUpdated().awaitSingle()

        database.sql(
            """
            insert ignore into subscription_events (
                provider_event_id, provider, event_type, user_id, billing_account_id,
                original_transaction_id, transaction_id, product_id, environment, purchased_at, expires_at,
                access_status, renewal_status, processing_status, attempt_count, max_attempts, next_attempt_at,
                payload_sha256, occurred_at, processed_at, created_at, updated_at
            ) values (
                :eventId, 'REVENUECAT', 'TRANSFER', :userId, :accountId,
                :originalTransactionId, :transactionId, :productId, :environment, :purchasedAt, :expiresAt,
                'ACTIVE', 'UNKNOWN', 'COMPLETED', 1, 3, :occurredAt,
                :hash, :occurredAt, :occurredAt, :occurredAt, :occurredAt
            )
            """.trimIndent(),
        ).bind("eventId", "revenuecat-transfer:${command.transaction.transactionId}:${command.userId}".take(191))
            .bind("userId", command.userId)
            .bind("accountId", accountId)
            .bind("originalTransactionId", canonicalOriginalTransactionId)
            .bind("transactionId", command.transaction.transactionId)
            .bind("productId", command.transaction.productId)
            .bind("environment", command.transaction.environment.name)
            .bind("purchasedAt", command.transaction.purchaseAt.utc())
            .bindNullable("expiresAt", command.transaction.expiresAt?.utc(), LocalDateTime::class.java)
            .bind("hash", command.transaction.signedPayloadSha256)
            .bind("occurredAt", command.occurredAt.utc())
            .fetch().rowsUpdated().awaitSingle()

        rebuildEntitlementProjection(existing.userId, command.occurredAt)
        rebuildEntitlementProjection(command.userId, command.occurredAt)
        activateFirstPaidAnchor(command.userId, payment.purchaseAt, command.occurredAt)
    }

    private suspend fun existingInvoiceForTransaction(transactionId: String): ExistingPayment? =
        database.sql(
            """
            select p.invoice_id, p.user_id, p.product_id, i.invoice_number
            from payments p
            join invoices i on i.id = p.invoice_id
            where p.provider = 'APPLE' and p.provider_transaction_id = :id
            """.trimIndent(),
        ).bind("id", transactionId).map { row, _ ->
            ExistingPayment(
                row.long("invoice_id"),
                UUID.fromString(row.string("invoice_number")),
                row.long("user_id"),
                row.string("product_id"),
            )
        }.one().awaitSingleOrNull()

    private suspend fun existingCheckoutInvoice(userId: Long, idempotencyKey: String): ExistingCheckout? =
        database.sql(
            """
            select i.id as invoice_id, i.product_id
            from invoice_events e
            join invoices i on i.id = e.invoice_id
            where e.event_type = 'INVOICE_CREATED'
              and e.source = 'CLIENT'
              and e.actor_user_id = :userId
              and e.correlation_id = :idempotencyKey
            limit 1
            """.trimIndent(),
        ).bind("userId", userId)
            .bind("idempotencyKey", idempotencyKey)
            .map { row, _ -> ExistingCheckout(row.long("invoice_id"), row.string("product_id")) }
            .one().awaitSingleOrNull()

    private suspend fun lockInvoiceByNumber(invoiceNumber: UUID): InvoiceEntity? =
        database.sql(
            """
            select *
            from invoices where invoice_number = :invoiceNumber for update
            """.trimIndent(),
        ).bind("invoiceNumber", invoiceNumber.toString().lowercase())
            .map { row, _ -> row.invoiceEntity() }.one().awaitSingleOrNull()

    private suspend fun lockLatestPendingInvoice(userId: Long, productId: String): InvoiceEntity? =
        database.sql(
            """
            select *
            from invoices
            where user_id = :userId and provider = 'APPLE' and product_id = :productId
              and type = 'NORMAL' and status = 'WAITING'
            order by created_at desc, id desc
            limit 1 for update
            """.trimIndent(),
        ).bind("userId", userId).bind("productId", productId)
            .map { row, _ -> row.invoiceEntity() }.one().awaitSingleOrNull()

    private suspend fun lockLatestRefundInvoice(originalInvoiceId: Long, statuses: Set<InvoiceStatus>): InvoiceEntity? {
        val names = statuses.joinToString(",") { "'${it.name}'" }
        return database.sql(
            """
            select *
            from invoices
            where type = 'REFUND' and original_invoice_id = :originalInvoiceId and status in ($names)
            order by created_at desc, id desc
            limit 1 for update
            """.trimIndent(),
        ).bind("originalInvoiceId", originalInvoiceId)
            .map { row, _ -> row.invoiceEntity() }.one().awaitSingleOrNull()
    }

    private suspend fun hasInvoiceEvent(invoiceId: Long, eventType: InvoiceEventType): Boolean =
        database.sql(
            "select count(*) as count_value from invoice_events where invoice_id = :invoiceId and event_type = :eventType",
        ).bind("invoiceId", invoiceId).bind("eventType", eventType.name)
            .map { row, _ -> row.long("count_value") > 0 }.one().awaitSingle()

    private fun validatePendingInvoice(invoice: InvoiceEntity, command: RecordVerifiedPaymentCommand) {
        if (
            invoice.userId != command.userId ||
            invoice.productId != command.tierProduct.productId ||
            invoice.tierCode != command.tierProduct.tierCode
        ) {
            throw billingFailure(
                ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                "The verified transaction does not match the pending invoice.",
                HttpStatus.CONFLICT,
            )
        }
        if (invoice.type != InvoiceType.NORMAL || invoice.status != InvoiceStatus.WAITING) {
            throw billingFailure(
                ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                "Invoice is no longer awaiting payment.",
                HttpStatus.CONFLICT,
            )
        }
    }

    private fun isInitialPurchase(transaction: VerifiedAppleTransaction): Boolean =
        transaction.transactionId == transaction.originalTransactionId

    private suspend fun lockInvoice(invoiceId: Long): InvoiceEntity? =
        database.sql(
            """
            select *
            from invoices where id = :id for update
            """.trimIndent(),
        ).bind("id", invoiceId).map { row, _ -> row.invoiceEntity() }.one().awaitSingleOrNull()

    private suspend fun lockPayment(paymentId: Long): PaymentEntity? =
        database.sql(LOCK_PAYMENT_SQL + " where p.id = :id for update")
            .bind("id", paymentId).map { row, _ -> row.paymentEntity() }.one().awaitSingleOrNull()

    private suspend fun lockPaymentByInvoice(invoiceId: Long): PaymentEntity? =
        database.sql(LOCK_PAYMENT_SQL + " where p.invoice_id = :invoiceId for update")
            .bind("invoiceId", invoiceId).map { row, _ -> row.paymentEntity() }.one().awaitSingleOrNull()

    private suspend fun lockPaymentByTransaction(transactionId: String): PaymentEntity? =
        database.sql(LOCK_PAYMENT_SQL + " where p.provider = 'APPLE' and p.provider_transaction_id = :id for update")
            .bind("id", transactionId).map { row, _ -> row.paymentEntity() }.one().awaitSingleOrNull()

    private suspend fun lockLatestPayment(userId: Long, originalTransactionId: String): PaymentEntity? =
        database.sql(
            LOCK_PAYMENT_SQL +
                " where p.user_id = :userId and p.provider = 'APPLE' and p.provider_original_transaction_id = :originalId" +
                " order by p.purchase_at desc, p.id desc limit 1 for update",
        ).bind("userId", userId).bind("originalId", originalTransactionId)
            .map { row, _ -> row.paymentEntity() }.one().awaitSingleOrNull()

    private suspend fun loadInvoiceSummary(invoiceId: Long): BillingInvoiceSummary? =
        database.sql(INVOICE_SUMMARY_SQL + " where i.id = :id")
            .bind("id", invoiceId).map { row, _ -> row.invoiceSummary() }.one().awaitSingleOrNull()

    private suspend fun requireInvoiceSummary(invoiceId: Long): BillingInvoiceSummary =
        loadInvoiceSummary(invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Invoice projection could not be read.")

    private suspend fun invoiceEvents(invoiceId: Long): List<BillingInvoiceEvent> =
        database.sql(
            """
            select *
            from invoice_events where invoice_id = :invoiceId order by sequence_number
            """.trimIndent(),
        ).bind("invoiceId", invoiceId).map { row, _ ->
            val event = row.invoiceEventEntity()
            BillingInvoiceEvent(
                eventId = event.eventId,
                sequenceNumber = event.sequenceNumber,
                eventType = event.eventType.name,
                source = event.source,
                fromStatus = event.fromStatus,
                toStatus = event.toStatus,
                reason = event.reason,
                occurredAt = event.occurredAt,
            )
        }.all().collectList().awaitSingle()

    private suspend fun paymentHistory(invoiceId: Long): List<PaymentHistoryEntry> =
        database.sql(
            """
            select *
            from payments_history where invoice_id = :invoiceId order by occurred_at, id
            """.trimIndent(),
        ).bind("invoiceId", invoiceId).map { row, _ ->
            val event = row.paymentHistoryEntity()
            PaymentHistoryEntry(
                eventId = event.eventId,
                eventType = event.eventType.name,
                source = event.source,
                fromStatus = event.fromStatus,
                toStatus = event.toStatus,
                reason = event.reason,
                occurredAt = event.occurredAt,
            )
        }.all().collectList().awaitSingle()

    private suspend fun lastInsertedId(): Long =
        database.sql("select last_insert_id() as inserted_id")
            .map { row, _ -> row.long("inserted_id") }.one().awaitSingle()

    private fun Row.tierProduct() = BillingTierProduct(
        tierCode = string("tier_code"),
        description = string("description"),
        monthlyQuestionLimit = int("monthly_question_limit"),
        productId = string("product_id"),
        productType = BillingProductType.valueOf(string("product_type")),
        billingPeriod = nullableString("billing_period"),
        sortOrder = int("sort_order"),
    )

    private fun Row.invoiceSummary() = BillingInvoiceSummary(
        id = long("invoice_id"),
        invoiceNumber = UUID.fromString(string("invoice_number")),
        type = InvoiceType.valueOf(string("invoice_type")),
        originalInvoiceId = nullableLong("original_invoice_id"),
        tierCode = string("tier_code"),
        productId = string("product_id"),
        status = InvoiceStatus.valueOf(string("invoice_status")),
        version = long("invoice_version"),
        paymentId = nullableLong("payment_id"),
        transactionId = nullableString("provider_transaction_id"),
        originalTransactionId = nullableString("provider_original_transaction_id"),
        paymentStatus = nullableString("payment_status")?.let(PaymentStatus::valueOf),
        priceMilliunits = nullableLong("price_milliunits"),
        currency = nullableString("currency"),
        purchaseAt = nullableInstant("purchase_at"),
        expiresAt = nullableInstant("expires_at"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
        fulfilledAt = nullableInstant("fulfilled_at"),
        latestEventType = nullableString("latest_event_type")?.let(InvoiceEventType::valueOf),
    )

    private fun Row.invoiceEntity() = InvoiceEntity(
        id = long("id"),
        invoiceNumber = uuid("invoice_number"),
        type = enum("type"),
        originalInvoiceId = nullableLong("original_invoice_id"),
        userId = long("user_id"),
        tierCode = string("tier_code"),
        provider = enum("provider"),
        productId = string("product_id"),
        appAccountToken = uuid("app_account_token"),
        currency = nullableString("currency"),
        subtotalMilliunits = nullableLong("subtotal_milliunits"),
        taxMilliunits = nullableLong("tax_milliunits"),
        totalMilliunits = nullableLong("total_milliunits"),
        status = enum("status"),
        version = long("version"),
        latestEventSequence = long("latest_event_sequence"),
        paidAt = nullableInstant("paid_at"),
        fulfilledAt = nullableInstant("fulfilled_at"),
        cancelledAt = nullableInstant("cancelled_at"),
        refundedAt = nullableInstant("refunded_at"),
        expiresAt = nullableInstant("expires_at"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    private fun Row.paymentEntity() = PaymentEntity(
        id = long("id"),
        invoiceId = long("invoice_id"),
        userId = long("user_id"),
        provider = enum("provider"),
        providerTransactionId = string("provider_transaction_id"),
        providerOriginalTransactionId = string("provider_original_transaction_id"),
        appTransactionId = nullableString("app_transaction_id"),
        webOrderLineItemId = nullableString("web_order_line_item_id"),
        appAccountToken = uuid("app_account_token"),
        productId = string("product_id"),
        productType = enum("product_type"),
        environment = enum("environment"),
        quantity = int("quantity"),
        priceMilliunits = nullableLong("price_milliunits"),
        currency = nullableString("currency"),
        status = enum("status"),
        purchaseAt = instant("purchase_at"),
        originalPurchaseAt = nullableInstant("original_purchase_at"),
        expiresAt = nullableInstant("expires_at"),
        revocationAt = nullableInstant("revocation_at"),
        revocationReason = nullableInt("revocation_reason"),
        signedAt = instant("signed_at"),
        verifiedAt = instant("verified_at"),
        signedPayloadSha256 = string("signed_payload_sha256"),
        version = long("version"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    private fun Row.invoiceEventEntity() = InvoiceEventEntity(
        id = long("id"),
        invoiceId = long("invoice_id"),
        eventId = string("event_id"),
        sequenceNumber = long("sequence_number"),
        eventType = enum("event_type"),
        source = enum("source"),
        fromStatus = nullableEnum<InvoiceStatus>("from_status"),
        toStatus = enum("to_status"),
        correlationId = nullableString("correlation_id"),
        causationId = nullableString("causation_id"),
        actorUserId = nullableLong("actor_user_id"),
        reason = nullableString("reason"),
        metadataJson = nullableString("metadata_json"),
        occurredAt = instant("occurred_at"),
        createdAt = instant("created_at"),
    )

    private fun Row.paymentHistoryEntity() = PaymentHistoryEntity(
        id = long("id"),
        paymentId = long("payment_id"),
        invoiceId = long("invoice_id"),
        eventId = string("event_id"),
        eventType = enum("event_type"),
        source = enum("source"),
        fromStatus = nullableEnum<PaymentStatus>("from_status"),
        toStatus = enum("to_status"),
        providerNotificationUuid = nullableString("provider_notification_uuid"),
        reason = nullableString("reason"),
        metadataJson = nullableString("metadata_json"),
        occurredAt = instant("occurred_at"),
        createdAt = instant("created_at"),
    )

    private fun Row.billingJobEntity() = BillingJobEntity(
        id = long("id"),
        jobId = uuid("job_id"),
        invoiceId = long("invoice_id"),
        paymentId = long("payment_id"),
        jobType = enum("job_type"),
        status = enum("status"),
        attempts = int("attempts"),
        maxAttempts = int("max_attempts"),
        nextAttemptAt = instant("next_attempt_at"),
        claimedAt = nullableInstant("claimed_at"),
        claimToken = nullableUuid("claim_token"),
        lastError = nullableString("last_error"),
        completedAt = nullableInstant("completed_at"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    private fun Row.billingAction(): BillingAction {
        val type = BillingActionType.valueOf(string("action_type"))
        return BillingAction(
            actionId = UUID.fromString(string("action_id")), actionType = type,
            status = BillingActionStatus.valueOf(string("action_status")), invoiceId = long("invoice_id"),
            paymentId = long("payment_id"), providerTransactionId = string("provider_transaction_id"),
            providerOriginalTransactionId = string("provider_original_transaction_id"), reason = nullableString("reason"),
            requestedAt = instant("requested_at"), completedAt = nullableInstant("completed_at"),
            clientAction = when (type) {
                BillingActionType.REFUND -> BillingClientAction.BEGIN_APPLE_REFUND_REQUEST
                BillingActionType.CANCELLATION -> BillingClientAction.OPEN_APPLE_SUBSCRIPTION_MANAGEMENT
                BillingActionType.COMPENSATION -> BillingClientAction.NONE
            },
        )
    }

    private fun Row.adminInvoice() = AdminBillingInvoice(
        userId = long("user_id"),
        userEmail = string("user_email"),
        userDisplayName = string("user_display_name"),
        invoice = invoiceSummary(),
    )

    private fun Row.string(name: String): String = get(name, String::class.java)
        ?: throw IllegalStateException("Column $name is null")
    private fun Row.nullableString(name: String): String? = get(name, String::class.java)
    private fun Row.long(name: String): Long = (get(name) as Number).toLong()
    private fun Row.nullableLong(name: String): Long? = (get(name) as? Number)?.toLong()
    private fun Row.int(name: String): Int = (get(name) as Number).toInt()
    private fun Row.nullableInt(name: String): Int? = (get(name) as? Number)?.toInt()
    private fun Row.boolean(name: String): Boolean = when (val value = get(name)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> throw IllegalStateException("Column $name is not boolean")
    }
    private fun Row.uuid(name: String): UUID = UUID.fromString(string(name))
    private fun Row.nullableUuid(name: String): UUID? = nullableString(name)?.let(UUID::fromString)
    private inline fun <reified T : Enum<T>> Row.enum(name: String): T = enumValueOf(string(name))
    private inline fun <reified T : Enum<T>> Row.nullableEnum(name: String): T? =
        nullableString(name)?.let { enumValueOf<T>(it) }
    private fun Row.instant(name: String): Instant = get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
        ?: throw IllegalStateException("Column $name is null")
    private fun Row.nullableInstant(name: String): Instant? = get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    private fun DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: Any?,
        type: Class<*>,
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

    private fun Instant.utc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun billingFailure(
        code: ApiErrorCode,
        message: String,
        status: HttpStatus = code.status,
    ) = ApiException(status, code, message)

    private data class ExistingPayment(
        val invoiceId: Long,
        val invoiceNumber: UUID,
        val userId: Long,
        val productId: String,
    )
    private data class ExistingCheckout(val invoiceId: Long, val productId: String)
    private data class SubscriptionOwnership(
        val userId: Long?,
        val latestTransactionId: String?,
        val latestPurchaseAt: Instant?,
        val pendingProductId: String?,
        val accessStatus: String,
        val lastProviderEventAt: Instant?,
    )
    private data class ActiveSubscriptionProjection(
        val id: Long,
        val tierCode: String,
        val productId: String?,
        val accessStatus: String,
        val renewalStatus: String,
        val startedAt: Instant?,
        val expiresAt: Instant?,
        val pendingProductId: String?,
    )

    private companion object {
        val REVENUECAT_PURCHASE_EVENTS = setOf("INITIAL_PURCHASE", "RENEWAL", "NON_RENEWING_PURCHASE")
        val REFUNDABLE_PAYMENT_STATES = setOf(
            PaymentStatus.SETTLED,
            PaymentStatus.REFUND_DECLINED,
            PaymentStatus.REFUND_REVERSED,
        )
        val TERMINAL_ENTITLEMENT_DENIED_PAYMENT_STATES = setOf(
            PaymentStatus.REFUNDED,
            PaymentStatus.REVOKED,
            PaymentStatus.FAILED,
        )
        val ENTITLEMENT_GRANTING_ACCESS_STATES = setOf("ACTIVE", "GRACE_PERIOD")
        val TERMINAL_RECEIPT_STATES = setOf(BillingReceiptStatus.PROCESSED, BillingReceiptStatus.IGNORED)

        const val LOCK_PAYMENT_SQL = """
            select p.*
            from payments p
        """

        const val INVOICE_SUMMARY_SQL = """
            select i.id as invoice_id, i.invoice_number, i.type as invoice_type, i.original_invoice_id,
                   i.tier_code, i.product_id,
                   i.status as invoice_status, i.version as invoice_version,
                   p.id as payment_id, p.provider_transaction_id, p.provider_original_transaction_id,
                   p.status as payment_status, p.price_milliunits, p.currency, p.purchase_at,
                   coalesce(p.expires_at, i.expires_at) as expires_at,
                   i.created_at, i.updated_at, i.fulfilled_at,
                   latest_event.event_type as latest_event_type
            from invoices i
            left join payments p on p.invoice_id = coalesce(i.original_invoice_id, i.id)
            left join invoice_events latest_event
              on latest_event.invoice_id = i.id
             and latest_event.sequence_number = i.latest_event_sequence
        """

        const val ACTION_SQL = """
            select a.action_id, a.action_type, a.status as action_status, a.invoice_id, a.payment_id,
                   p.provider_transaction_id, p.provider_original_transaction_id,
                   a.reason, a.requested_at, a.completed_at
            from billing_actions a
            join payments p on p.id = a.payment_id
        """

        const val ADMIN_INVOICE_SQL = """
            select i.id as invoice_id, i.invoice_number, i.type as invoice_type, i.original_invoice_id,
                   i.tier_code, i.product_id,
                   i.status as invoice_status, i.version as invoice_version,
                   p.id as payment_id, p.provider_transaction_id, p.provider_original_transaction_id,
                   p.status as payment_status, p.price_milliunits, p.currency, p.purchase_at,
                   coalesce(p.expires_at, i.expires_at) as expires_at,
                   i.created_at, i.updated_at, i.fulfilled_at,
                   latest_event.event_type as latest_event_type,
                   u.id as user_id, u.email as user_email, u.display_name as user_display_name
            from invoices i
            left join payments p on p.invoice_id = coalesce(i.original_invoice_id, i.id)
            left join invoice_events latest_event
              on latest_event.invoice_id = i.id
             and latest_event.sequence_number = i.latest_event_sequence
            join users u on u.id = i.user_id
        """
    }
}
