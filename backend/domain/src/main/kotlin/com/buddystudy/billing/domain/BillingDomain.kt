package com.buddystudy.billing.domain

enum class BillingProvider {
    APPLE,
}

enum class BillingEnvironment {
    SANDBOX,
    PRODUCTION,
    XCODE,
}

enum class BillingProductType {
    CONSUMABLE,
    NON_CONSUMABLE,
    AUTO_RENEWABLE_SUBSCRIPTION,
    NON_RENEWING_SUBSCRIPTION,
}

enum class InvoiceStatus {
    PENDING_PAYMENT,
    PAYMENT_VERIFIED,
    FULFILLMENT_PENDING,
    FULFILLED,
    CANCELLATION_REQUESTED,
    CANCELLED,
    REFUND_REQUESTED,
    REFUND_PENDING,
    REFUNDED,
    REFUND_DECLINED,
    REFUND_REVERSED,
    COMPENSATION_REQUIRED,
    FAILED,
    EXPIRED,
}

enum class InvoiceEventType {
    INVOICE_CREATED,
    PAYMENT_VERIFIED,
    FULFILLMENT_STARTED,
    FULFILLED,
    CANCELLATION_REQUESTED,
    CANCELLATION_REVERSED,
    CANCELLED,
    REFUND_REQUESTED,
    REFUND_PENDING,
    REFUNDED,
    REFUND_DECLINED,
    REFUND_REVERSED,
    COMPENSATION_REQUIRED,
    FULFILLMENT_FAILED,
    EXPIRED,
    PAYMENT_REVOKED,
}

enum class PaymentStatus {
    VERIFIED,
    SETTLED,
    REFUND_PENDING,
    REFUNDED,
    REFUND_DECLINED,
    REFUND_REVERSED,
    REVOKED,
    FAILED,
}

enum class PaymentHistoryEventType {
    VERIFIED,
    SETTLED,
    REFUND_REQUESTED,
    REFUND_PENDING,
    REFUNDED,
    REFUND_DECLINED,
    REFUND_REVERSED,
    REVOKED,
    VALIDATION_FAILED,
}

enum class BillingActionType {
    REFUND,
    CANCELLATION,
    COMPENSATION,
}

enum class BillingActionStatus {
    REQUIRED,
    REQUESTED,
    AWAITING_APPLE,
    COMPLETED,
    DECLINED,
    FAILED,
    CANCELLED,
}

enum class BillingJobType {
    FULFILLMENT,
    COMPENSATION,
}

enum class BillingJobStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
}

enum class BillingEventSource {
    CLIENT,
    APPLE_NOTIFICATION,
    SYSTEM,
    ADMIN,
}

/**
 * Invoice events are the source of truth. The invoice row is only the current-state projection.
 * Every transition is intentionally enumerated so a duplicated or out-of-order Apple event fails closed.
 */
object InvoiceStateMachine {
    private val transitions: Map<Pair<InvoiceStatus, InvoiceEventType>, InvoiceStatus> = buildMap {
        allow(InvoiceStatus.PENDING_PAYMENT, InvoiceEventType.PAYMENT_VERIFIED, InvoiceStatus.PAYMENT_VERIFIED)
        allow(InvoiceStatus.PAYMENT_VERIFIED, InvoiceEventType.FULFILLMENT_STARTED, InvoiceStatus.FULFILLMENT_PENDING)
        allow(InvoiceStatus.FULFILLMENT_PENDING, InvoiceEventType.FULFILLED, InvoiceStatus.FULFILLED)
        allow(InvoiceStatus.PAYMENT_VERIFIED, InvoiceEventType.COMPENSATION_REQUIRED, InvoiceStatus.COMPENSATION_REQUIRED)
        allow(InvoiceStatus.FULFILLMENT_PENDING, InvoiceEventType.COMPENSATION_REQUIRED, InvoiceStatus.COMPENSATION_REQUIRED)
        allow(InvoiceStatus.FULFILLMENT_PENDING, InvoiceEventType.FULFILLMENT_FAILED, InvoiceStatus.COMPENSATION_REQUIRED)

        listOf(InvoiceStatus.FULFILLED, InvoiceStatus.REFUND_DECLINED, InvoiceStatus.REFUND_REVERSED).forEach {
            allow(it, InvoiceEventType.CANCELLATION_REQUESTED, InvoiceStatus.CANCELLATION_REQUESTED)
            allow(it, InvoiceEventType.REFUND_REQUESTED, InvoiceStatus.REFUND_REQUESTED)
            allow(it, InvoiceEventType.REFUNDED, InvoiceStatus.REFUNDED)
            allow(it, InvoiceEventType.PAYMENT_REVOKED, InvoiceStatus.REFUNDED)
            allow(it, InvoiceEventType.EXPIRED, InvoiceStatus.EXPIRED)
        }
        allow(InvoiceStatus.CANCELLATION_REQUESTED, InvoiceEventType.CANCELLED, InvoiceStatus.CANCELLED)
        allow(InvoiceStatus.CANCELLATION_REQUESTED, InvoiceEventType.CANCELLATION_REVERSED, InvoiceStatus.FULFILLED)
        allow(InvoiceStatus.CANCELLATION_REQUESTED, InvoiceEventType.REFUND_REQUESTED, InvoiceStatus.REFUND_REQUESTED)
        allow(InvoiceStatus.CANCELLATION_REQUESTED, InvoiceEventType.REFUNDED, InvoiceStatus.REFUNDED)
        allow(InvoiceStatus.CANCELLATION_REQUESTED, InvoiceEventType.EXPIRED, InvoiceStatus.EXPIRED)

        allow(InvoiceStatus.REFUND_REQUESTED, InvoiceEventType.REFUND_PENDING, InvoiceStatus.REFUND_PENDING)
        allow(InvoiceStatus.REFUND_REQUESTED, InvoiceEventType.REFUNDED, InvoiceStatus.REFUNDED)
        allow(InvoiceStatus.REFUND_REQUESTED, InvoiceEventType.REFUND_DECLINED, InvoiceStatus.REFUND_DECLINED)
        allow(InvoiceStatus.REFUND_PENDING, InvoiceEventType.REFUNDED, InvoiceStatus.REFUNDED)
        allow(InvoiceStatus.REFUND_PENDING, InvoiceEventType.REFUND_DECLINED, InvoiceStatus.REFUND_DECLINED)
        allow(InvoiceStatus.REFUND_DECLINED, InvoiceEventType.REFUND_REQUESTED, InvoiceStatus.REFUND_REQUESTED)
        allow(InvoiceStatus.REFUNDED, InvoiceEventType.REFUND_REVERSED, InvoiceStatus.REFUND_REVERSED)
        allow(InvoiceStatus.REFUND_REVERSED, InvoiceEventType.REFUND_REQUESTED, InvoiceStatus.REFUND_REQUESTED)
        allow(InvoiceStatus.REFUND_REVERSED, InvoiceEventType.EXPIRED, InvoiceStatus.EXPIRED)

        allow(InvoiceStatus.COMPENSATION_REQUIRED, InvoiceEventType.REFUND_REQUESTED, InvoiceStatus.REFUND_REQUESTED)
        allow(InvoiceStatus.COMPENSATION_REQUIRED, InvoiceEventType.REFUND_PENDING, InvoiceStatus.REFUND_PENDING)
        allow(InvoiceStatus.COMPENSATION_REQUIRED, InvoiceEventType.REFUNDED, InvoiceStatus.REFUNDED)
        allow(InvoiceStatus.COMPENSATION_REQUIRED, InvoiceEventType.REFUND_DECLINED, InvoiceStatus.REFUND_DECLINED)
    }

    fun next(current: InvoiceStatus, event: InvoiceEventType): InvoiceStatus =
        transitions[current to event]
            ?: throw IllegalInvoiceTransition(current, event)

    fun canApply(current: InvoiceStatus, event: InvoiceEventType): Boolean = transitions.containsKey(current to event)

    private fun MutableMap<Pair<InvoiceStatus, InvoiceEventType>, InvoiceStatus>.allow(
        from: InvoiceStatus,
        event: InvoiceEventType,
        to: InvoiceStatus,
    ) {
        put(from to event, to)
    }
}

class IllegalInvoiceTransition(
    val current: InvoiceStatus,
    val event: InvoiceEventType,
) : IllegalStateException("Invoice transition is not allowed: $current + $event")
