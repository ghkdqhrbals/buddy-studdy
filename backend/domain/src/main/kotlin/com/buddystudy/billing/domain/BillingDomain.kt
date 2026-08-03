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

enum class InvoiceType(val desc: String) {
    NORMAL("일반"),
    REFUND("환불"),
}

enum class InvoiceStatus {
    WAITING,
    COMPLETED,
    FAILED,
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
    private data class Key(val type: InvoiceType, val status: InvoiceStatus, val event: InvoiceEventType)

    private val transitions: Map<Key, InvoiceStatus> = buildMap {
        allowWaiting(InvoiceType.NORMAL, InvoiceEventType.PAYMENT_VERIFIED)
        allowWaiting(InvoiceType.NORMAL, InvoiceEventType.FULFILLMENT_STARTED)
        allow(InvoiceType.NORMAL, InvoiceStatus.WAITING, InvoiceEventType.FULFILLED, InvoiceStatus.COMPLETED)
        allow(InvoiceType.NORMAL, InvoiceStatus.WAITING, InvoiceEventType.CANCELLED, InvoiceStatus.FAILED)
        allow(InvoiceType.NORMAL, InvoiceStatus.WAITING, InvoiceEventType.COMPENSATION_REQUIRED, InvoiceStatus.FAILED)
        allow(InvoiceType.NORMAL, InvoiceStatus.WAITING, InvoiceEventType.FULFILLMENT_FAILED, InvoiceStatus.FAILED)

        listOf(
            InvoiceEventType.CANCELLATION_REQUESTED,
            InvoiceEventType.CANCELLATION_REVERSED,
            InvoiceEventType.CANCELLED,
            InvoiceEventType.EXPIRED,
        ).forEach { allowCompleted(InvoiceType.NORMAL, it) }

        allowWaiting(InvoiceType.REFUND, InvoiceEventType.REFUND_REQUESTED)
        allowWaiting(InvoiceType.REFUND, InvoiceEventType.REFUND_PENDING)
        allow(InvoiceType.REFUND, InvoiceStatus.WAITING, InvoiceEventType.REFUNDED, InvoiceStatus.COMPLETED)
        allow(InvoiceType.REFUND, InvoiceStatus.WAITING, InvoiceEventType.PAYMENT_REVOKED, InvoiceStatus.COMPLETED)
        allow(InvoiceType.REFUND, InvoiceStatus.WAITING, InvoiceEventType.REFUND_DECLINED, InvoiceStatus.FAILED)
        allow(InvoiceType.REFUND, InvoiceStatus.COMPLETED, InvoiceEventType.REFUND_REVERSED, InvoiceStatus.FAILED)
    }

    fun next(type: InvoiceType, current: InvoiceStatus, event: InvoiceEventType): InvoiceStatus =
        transitions[Key(type, current, event)]
            ?: throw IllegalInvoiceTransition(type, current, event)

    fun canApply(type: InvoiceType, current: InvoiceStatus, event: InvoiceEventType): Boolean =
        transitions.containsKey(Key(type, current, event))

    private fun MutableMap<Key, InvoiceStatus>.allow(
        type: InvoiceType,
        from: InvoiceStatus,
        event: InvoiceEventType,
        to: InvoiceStatus,
    ) {
        put(Key(type, from, event), to)
    }

    private fun MutableMap<Key, InvoiceStatus>.allowWaiting(type: InvoiceType, event: InvoiceEventType) =
        allow(type, InvoiceStatus.WAITING, event, InvoiceStatus.WAITING)

    private fun MutableMap<Key, InvoiceStatus>.allowCompleted(type: InvoiceType, event: InvoiceEventType) =
        allow(type, InvoiceStatus.COMPLETED, event, InvoiceStatus.COMPLETED)
}

class IllegalInvoiceTransition(
    val type: InvoiceType,
    val current: InvoiceStatus,
    val event: InvoiceEventType,
) : IllegalStateException("Invoice transition is not allowed: $type/$current + $event")
