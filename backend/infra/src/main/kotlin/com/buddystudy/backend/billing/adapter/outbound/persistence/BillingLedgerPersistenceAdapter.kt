package com.buddystudy.backend.billing.adapter.outbound.persistence

import com.buddystudy.backend.billing.application.model.ApplyAppleNotificationCommand
import com.buddystudy.backend.billing.application.model.AdminBillingInvoice
import com.buddystudy.backend.billing.application.model.AdminBillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.AdminBillingInvoicePage
import com.buddystudy.backend.billing.application.model.BillingAction
import com.buddystudy.backend.billing.application.model.BillingClientAction
import com.buddystudy.backend.billing.application.model.BillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.BillingInvoiceEvent
import com.buddystudy.backend.billing.application.model.BillingInvoicePage
import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.BillingFulfillmentJobClaim
import com.buddystudy.backend.billing.application.model.BillingTierProduct
import com.buddystudy.backend.billing.application.model.PaymentHistoryEntry
import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.VerifiedAppleNotification
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
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
        val token = UUID.randomUUID()
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
        return existingToken(userId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Unable to create an Apple billing account token.")
    }

    override suspend fun userIdForAppAccountToken(appAccountToken: UUID): Long? =
        database.sql(
            "select user_id from apple_billing_accounts where app_account_token = :token",
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
        database.sql(
            """
            select p.tier_code, t.description, t.monthly_question_limit, p.product_id,
                   p.product_type, p.billing_period, p.sort_order
            from membership_tier_products p
            join user_membership_tiers t on t.tier_code = p.tier_code
            where p.provider = 'APPLE' and p.product_id = :productId and p.enabled = true
            """.trimIndent(),
        ).bind("productId", productId)
            .map { row, _ -> row.tierProduct() }
            .one().awaitSingleOrNull()

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
    override suspend fun recordVerifiedPayment(command: RecordVerifiedPaymentCommand): BillingInvoiceSummary {
        lockAndValidateAccount(command.userId, command.transaction.appAccountToken)

        existingInvoiceForTransaction(command.transaction.transactionId)?.let { existing ->
            if (existing.userId != command.userId || existing.productId != command.tierProduct.productId) {
                throw billingFailure(
                    ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                    "The App Store transaction is already attached to another invoice.",
                    HttpStatus.CONFLICT,
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
        insertBillingJob(invoiceId, paymentId, BillingJobType.FULFILLMENT, now)
        return loadInvoiceSummary(invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Created invoice could not be read.")
    }

    @Transactional
    override suspend fun fulfill(invoiceId: Long, now: Instant): BillingInvoiceSummary {
        val locked = lockInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.", HttpStatus.NOT_FOUND)
        if (locked.status == InvoiceStatus.COMPLETED) return requireInvoiceSummary(invoiceId)
        if (locked.type != InvoiceType.NORMAL || locked.status != InvoiceStatus.WAITING) {
            throw billingFailure(
                ApiErrorCode.BILLING_ACTION_NOT_ALLOWED,
                "Invoice cannot be fulfilled from ${locked.status}.",
                HttpStatus.CONFLICT,
            )
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

        val payment = lockPaymentByInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Invoice payment is missing.")
        grantMembership(locked, payment, now)
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
        appendInvoiceEvent(
            invoiceId,
            "invoice-fulfilled:${locked.invoiceNumber}",
            InvoiceEventType.FULFILLED,
            BillingEventSource.SYSTEM,
            null,
            null,
            now,
        )
        database.sql(
            """
            update billing_jobs
            set status = 'COMPLETED', completed_at = :now, claimed_at = null, claim_token = null,
                last_error = null, updated_at = :now
            where invoice_id = :invoiceId and job_type = 'FULFILLMENT'
            """.trimIndent(),
        ).bind("invoiceId", invoiceId).bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
        return requireInvoiceSummary(invoiceId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun requireCompensation(invoiceId: Long, reason: String, now: Instant): BillingInvoiceSummary {
        val locked = lockInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.", HttpStatus.NOT_FOUND)
        if (locked.status == InvoiceStatus.FAILED) return requireInvoiceSummary(invoiceId)
        if (locked.type != InvoiceType.NORMAL || locked.status != InvoiceStatus.WAITING) {
            return requireInvoiceSummary(invoiceId)
        }
        val payment = lockPaymentByInvoice(invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "Invoice payment is missing.")
        appendInvoiceEvent(
            invoiceId,
            "invoice-compensation-required:${locked.invoiceNumber}",
            InvoiceEventType.COMPENSATION_REQUIRED,
            BillingEventSource.SYSTEM,
            null,
            reason.take(1000),
            now,
        )
        database.sql(
            """
            update billing_jobs
            set status = 'FAILED', attempts = least(attempts + 1, max_attempts), last_error = :reason,
                claimed_at = null, claim_token = null, updated_at = :now
            where invoice_id = :invoiceId and job_type = 'FULFILLMENT'
            """.trimIndent(),
        ).bind("invoiceId", invoiceId).bind("reason", reason.take(4000)).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        insertBillingJob(invoiceId, payment.id, BillingJobType.COMPENSATION, now)
        insertActionIfAbsent(
            invoice = locked,
            payment = payment,
            actionType = BillingActionType.COMPENSATION,
            status = BillingActionStatus.REQUIRED,
            idempotencyKey = "compensation:$invoiceId",
            reason = reason.take(1000),
            now = now,
        )
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
            from billing_jobs
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
                update billing_jobs
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
            update billing_jobs
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
            insert ignore into apple_billing_notifications (
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
        return inserted == 1L
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
        applyProviderLifecycle(invoice, payment, command)
        markNotification(notification.notificationUUID, BillingReceiptStatus.PROCESSED, null, command.occurredAt)
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
            insert ignore into revenuecat_billing_events (
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
        if (inserted == 1L) return true

        return database.sql(
            """
            update revenuecat_billing_events
            set processing_status = 'RECEIVED', last_error = null, updated_at = :now
            where event_id = :eventId and processing_status = 'FAILED'
            """.trimIndent(),
        ).bind("now", now.utc()).bind("eventId", event.eventId)
            .fetch().rowsUpdated().awaitSingle() == 1L
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
        val mapped = event.toProviderNotification() ?: run {
            markRevenueCatEvent(event.eventId, BillingReceiptStatus.IGNORED, null, now)
            return true
        }
        val transactionId = event.transactionId
            ?: throw billingFailure(ApiErrorCode.BILLING_TRANSACTION_INVALID, "RevenueCat lifecycle event has no transaction ID.")
        val payment = lockPaymentByTransaction(transactionId)
            ?: throw billingFailure(ApiErrorCode.RESOURCE_NOT_FOUND, "RevenueCat transaction has not reached the payment ledger yet.")
        val invoice = lockInvoice(payment.invoiceId)
            ?: throw billingFailure(ApiErrorCode.INTERNAL_SERVER_ERROR, "RevenueCat payment invoice is missing.")
        applyProviderLifecycle(
            invoice = invoice,
            payment = payment,
            command = ApplyAppleNotificationCommand(mapped, now),
            source = BillingEventSource.REVENUECAT_WEBHOOK,
            eventPrefix = "revenuecat",
        )
        markRevenueCatEvent(event.eventId, BillingReceiptStatus.PROCESSED, null, now)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun markRevenueCatEventFailed(eventId: String, error: String, now: Instant) {
        markRevenueCatEvent(eventId, BillingReceiptStatus.FAILED, error.take(4000), now)
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

    private suspend fun applyProviderLifecycle(
        invoice: InvoiceEntity,
        payment: PaymentEntity,
        command: ApplyAppleNotificationCommand,
        source: BillingEventSource = BillingEventSource.APPLE_NOTIFICATION,
        eventPrefix: String = "apple",
    ) {
        val notification = command.notification
        val now = command.occurredAt
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
                reactivateMembership(invoice, payment, now)
            }
            "REVOKE" -> {
                val refundInvoice = requireRefundInvoice(invoice, notification, now, source)
                transitionPayment(payment, PaymentStatus.REVOKED, PaymentHistoryEventType.REVOKED,
                    "$eventPrefix-revoked:${notification.notificationUUID}", source,
                    notification.notificationUUID, null, now, historyInvoiceId = refundInvoice.id)
                appendIfAllowed(refundInvoice.id, InvoiceEventType.PAYMENT_REVOKED, notification, now, source, eventPrefix)
                deactivateMembership(payment.providerOriginalTransactionId, invoice.id, now)
            }
            "EXPIRED", "GRACE_PERIOD_EXPIRED" -> {
                appendIfAllowed(invoice.id, InvoiceEventType.EXPIRED, notification, now, source, eventPrefix)
                deactivateMembership(payment.providerOriginalTransactionId, invoice.id, now)
            }
            "CONSUMPTION_REQUEST" -> {
                val refundInvoice = requireRefundInvoice(invoice, notification, now, source)
                var current = lockInvoice(refundInvoice.id) ?: return
                if (InvoiceStateMachine.canApply(current.type, current.status, InvoiceEventType.REFUND_REQUESTED)) {
                    appendIfAllowed(refundInvoice.id, InvoiceEventType.REFUND_REQUESTED, notification, now, source, eventPrefix)
                    current = lockInvoice(refundInvoice.id) ?: return
                }
                if (InvoiceStateMachine.canApply(current.type, current.status, InvoiceEventType.REFUND_PENDING)) {
                    appendIfAllowed(refundInvoice.id, InvoiceEventType.REFUND_PENDING, notification, now, source, eventPrefix)
                }
            }
        }
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

    private suspend fun grantMembership(invoice: InvoiceEntity, payment: PaymentEntity, now: Instant) {
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
                tier = values(tier), status = 'ACTIVE', source = 'APPLE', source_invoice_id = values(source_invoice_id),
                started_at = least(started_at, values(started_at)), expires_at = values(expires_at), updated_at = values(updated_at)
            """.trimIndent(),
        ).bind("userId", invoice.userId).bind("tier", invoice.tierCode).bind("invoiceId", invoice.id)
            .bind("originalTransactionId", payment.providerOriginalTransactionId)
            .bind("startedAt", payment.purchaseAt.utc())
            .bindNullable("expiresAt", payment.expiresAt?.utc(), LocalDateTime::class.java)
            .bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun reactivateMembership(invoice: InvoiceEntity, payment: PaymentEntity, now: Instant) =
        grantMembership(invoice, payment, now)

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

    private suspend fun insertBillingJob(invoiceId: Long, paymentId: Long, type: BillingJobType, now: Instant) {
        database.sql(
            """
            insert into billing_jobs (
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
            update apple_billing_notifications
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
            update revenuecat_billing_events
            set processing_status = :status, processed_at = :processedAt, last_error = :error, updated_at = :now
            where event_id = :eventId
            """.trimIndent(),
        ).bind("status", status.name)
            .bindNullable("processedAt", if (status in TERMINAL_RECEIPT_STATES) now.utc() else null, LocalDateTime::class.java)
            .bindNullable("error", error, String::class.java)
            .bind("now", now.utc())
            .bind("eventId", eventId)
            .fetch().rowsUpdated().awaitSingle()
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
        val owner = database.sql(
            "select user_id from apple_billing_accounts where app_account_token = :token for update",
        ).bind("token", token.toString().lowercase()).map { row, _ -> row.long("user_id") }
            .one().awaitSingleOrNull()
        if (owner != userId) {
            throw billingFailure(
                ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                "The App Store transaction does not belong to this user.",
                HttpStatus.CONFLICT,
            )
        }
    }

    private suspend fun validateProductMapping(command: RecordVerifiedPaymentCommand) {
        val mapped = enabledTierProduct(command.transaction.productId)
        if (mapped != command.tierProduct) {
            throw billingFailure(ApiErrorCode.BILLING_TRANSACTION_INVALID, "Tier product mapping changed during verification.")
        }
    }

    private suspend fun existingToken(userId: Long): UUID? =
        database.sql("select app_account_token from apple_billing_accounts where user_id = :userId")
            .bind("userId", userId).map { row, _ -> UUID.fromString(row.string("app_account_token")) }
            .one().awaitSingleOrNull()

    private suspend fun existingInvoiceForTransaction(transactionId: String): ExistingPayment? =
        database.sql(
            "select invoice_id, user_id, product_id from payments where provider = 'APPLE' and provider_transaction_id = :id",
        ).bind("id", transactionId).map { row, _ ->
            ExistingPayment(row.long("invoice_id"), row.long("user_id"), row.string("product_id"))
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

    private data class ExistingPayment(val invoiceId: Long, val userId: Long, val productId: String)
    private data class ExistingCheckout(val invoiceId: Long, val productId: String)

    private companion object {
        val REVENUECAT_PURCHASE_EVENTS = setOf("INITIAL_PURCHASE", "RENEWAL", "NON_RENEWING_PURCHASE")
        val REFUNDABLE_PAYMENT_STATES = setOf(
            PaymentStatus.SETTLED,
            PaymentStatus.REFUND_DECLINED,
            PaymentStatus.REFUND_REVERSED,
        )
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
                   i.created_at, i.updated_at
            from invoices i
            left join payments p on p.invoice_id = coalesce(i.original_invoice_id, i.id)
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
                   i.created_at, i.updated_at,
                   u.id as user_id, u.email as user_email, u.display_name as user_display_name
            from invoices i
            left join payments p on p.invoice_id = coalesce(i.original_invoice_id, i.id)
            join users u on u.id = i.user_id
        """
    }
}
