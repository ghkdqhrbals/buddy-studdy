package com.buddystudy.backend.billing.application.service

import com.buddystudy.backend.billing.application.model.ApplyVerifiedBillingPaymentCommand
import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.port.inbound.VerifiedBillingPaymentUseCase
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.PaymentStatus
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class VerifiedBillingPaymentService(
    private val ledger: BillingLedgerPort,
    private val memberships: QuestionMembershipPort,
) : VerifiedBillingPaymentUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun apply(command: ApplyVerifiedBillingPaymentCommand): BillingInvoiceSummary {
        val transaction = command.transaction
        val recorded = ledger.recordVerifiedPayment(
            RecordVerifiedPaymentCommand(
                userId = command.userId,
                tierProduct = command.tierProduct,
                transaction = transaction,
                invoiceNumber = command.invoiceNumber,
                source = command.source,
                eventId = "apple-transaction:${transaction.transactionId}",
                occurredAt = command.occurredAt,
                authoritativeOwnershipTransfer = command.authoritativeOwnershipTransfer,
            ),
        )

        val fulfilled = try {
            ledger.fulfill(recorded.id, command.occurredAt)
        } catch (error: Exception) {
            logger.error(
                "verified_payment_membership_application_failed userId={} invoiceId={} invoiceNumber={} " +
                    "transactionId={} productId={} source={} errorType={} message={}",
                command.userId,
                recorded.id,
                recorded.invoiceNumber,
                transaction.transactionId,
                transaction.productId,
                command.source,
                error.javaClass.name,
                error.message,
                error,
            )
            throw applicationFailed()
        }

        val entitlement = ledger.entitlementForUser(command.userId)
        val quota = memberships.quotaStatusForUser(command.userId, command.occurredAt)
        val invoiceApplied = fulfilled.status == InvoiceStatus.COMPLETED &&
            fulfilled.paymentStatus == PaymentStatus.SETTLED &&
            fulfilled.fulfilledAt != null &&
            fulfilled.transactionId == transaction.transactionId &&
            fulfilled.productId == transaction.productId
        val entitlementApplied = entitlement?.tierCode == command.tierProduct.tierCode &&
            entitlement.productId == command.tierProduct.productId &&
            entitlement.accessStatus in setOf(SubscriptionAccessStatus.ACTIVE, SubscriptionAccessStatus.GRACE_PERIOD)
        val quotaApplied = quota?.tierCode == command.tierProduct.tierCode &&
            quota.baseLimit == command.tierProduct.monthlyQuestionLimit

        if (invoiceApplied && entitlementApplied && quotaApplied) return fulfilled

        logger.error(
            "verified_payment_application_postcondition_failed userId={} invoiceId={} invoiceStatus={} " +
                "paymentStatus={} fulfilledAt={} transactionMatches={} expectedTier={} entitlementTier={} " +
                "entitlementProduct={} entitlementAccess={} quotaTier={} quotaBaseLimit={}",
            command.userId,
            fulfilled.id,
            fulfilled.status,
            fulfilled.paymentStatus,
            fulfilled.fulfilledAt,
            fulfilled.transactionId == transaction.transactionId,
            command.tierProduct.tierCode,
            entitlement?.tierCode,
            entitlement?.productId,
            entitlement?.accessStatus,
            quota?.tierCode,
            quota?.baseLimit,
        )
        throw applicationFailed()
    }

    private fun applicationFailed() = ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        ApiErrorCode.BILLING_APPLICATION_FAILED,
        "The verified App Store payment was persisted, but membership application did not complete.",
    )
}
