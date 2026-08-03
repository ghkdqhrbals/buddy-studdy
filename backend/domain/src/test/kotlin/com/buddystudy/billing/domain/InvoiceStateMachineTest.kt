package com.buddystudy.billing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InvoiceStateMachineTest {
    @Test
    fun `verified payment reaches fulfilled only through fulfillment`() {
        val verified = InvoiceStateMachine.next(InvoiceStatus.PENDING_PAYMENT, InvoiceEventType.PAYMENT_VERIFIED)
        val fulfilling = InvoiceStateMachine.next(verified, InvoiceEventType.FULFILLMENT_STARTED)
        val fulfilled = InvoiceStateMachine.next(fulfilling, InvoiceEventType.FULFILLED)

        assertEquals(InvoiceStatus.FULFILLED, fulfilled)
    }

    @Test
    fun `fulfillment failure requires compensation`() {
        assertEquals(
            InvoiceStatus.COMPENSATION_REQUIRED,
            InvoiceStateMachine.next(InvoiceStatus.FULFILLMENT_PENDING, InvoiceEventType.FULFILLMENT_FAILED),
        )
    }

    @Test
    fun `refund is only final after provider event`() {
        val requested = InvoiceStateMachine.next(InvoiceStatus.FULFILLED, InvoiceEventType.REFUND_REQUESTED)
        assertEquals(InvoiceStatus.REFUND_REQUESTED, requested)
        assertEquals(InvoiceStatus.REFUNDED, InvoiceStateMachine.next(requested, InvoiceEventType.REFUNDED))
    }

    @Test
    fun `refund reversal restores a fulfilled-equivalent state`() {
        assertEquals(
            InvoiceStatus.REFUND_REVERSED,
            InvoiceStateMachine.next(InvoiceStatus.REFUNDED, InvoiceEventType.REFUND_REVERSED),
        )
    }

    @Test
    fun `reenabling auto renewal reverses a pending cancellation`() {
        val cancelling = InvoiceStateMachine.next(
            InvoiceStatus.FULFILLED,
            InvoiceEventType.CANCELLATION_REQUESTED,
        )

        assertEquals(
            InvoiceStatus.FULFILLED,
            InvoiceStateMachine.next(cancelling, InvoiceEventType.CANCELLATION_REVERSED),
        )
    }

    @Test
    fun `illegal state jumps fail closed`() {
        assertThrows(IllegalInvoiceTransition::class.java) {
            InvoiceStateMachine.next(InvoiceStatus.PENDING_PAYMENT, InvoiceEventType.FULFILLED)
        }
    }
}
