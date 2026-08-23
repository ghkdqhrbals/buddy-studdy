package com.buddystudy.billing.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InvoiceStateMachineTest {
    @Test
    fun `invoice projection exposes only the approved types and statuses`() {
        assertEquals(listOf(InvoiceType.NORMAL, InvoiceType.REFUND), InvoiceType.entries)
        assertEquals("일반", InvoiceType.NORMAL.desc)
        assertEquals("환불", InvoiceType.REFUND.desc)
        assertEquals(
            listOf(InvoiceStatus.WAITING, InvoiceStatus.COMPLETED, InvoiceStatus.FAILED),
            InvoiceStatus.entries,
        )
    }

    @Test
    fun `verified payment completes financial invoice before entitlement projection`() {
        val verified = InvoiceStateMachine.next(
            InvoiceType.NORMAL,
            InvoiceStatus.WAITING,
            InvoiceEventType.PAYMENT_VERIFIED,
        )
        val fulfilling = InvoiceStateMachine.next(
            InvoiceType.NORMAL,
            verified,
            InvoiceEventType.FULFILLMENT_STARTED,
        )
        val completed = InvoiceStateMachine.next(
            InvoiceType.NORMAL,
            fulfilling,
            InvoiceEventType.FULFILLED,
        )

        assertEquals(InvoiceStatus.COMPLETED, verified)
        assertEquals(InvoiceStatus.COMPLETED, fulfilling)
        assertEquals(InvoiceStatus.COMPLETED, completed)
    }

    @Test
    fun `entitlement projection failure never regresses a completed charge`() {
        assertEquals(
            InvoiceStatus.COMPLETED,
            InvoiceStateMachine.next(
                InvoiceType.NORMAL,
                InvoiceStatus.COMPLETED,
                InvoiceEventType.FULFILLMENT_FAILED,
            ),
        )
    }

    @Test
    fun `abandoned checkout fails normal invoice`() {
        assertEquals(
            InvoiceStatus.FAILED,
            InvoiceStateMachine.next(
                InvoiceType.NORMAL,
                InvoiceStatus.WAITING,
                InvoiceEventType.CANCELLED,
            ),
        )
    }

    @Test
    fun `permanent payment validation failure fails a prepared normal invoice`() {
        assertEquals(
            InvoiceStatus.FAILED,
            InvoiceStateMachine.next(
                InvoiceType.NORMAL,
                InvoiceStatus.WAITING,
                InvoiceEventType.PAYMENT_VALIDATION_FAILED,
            ),
        )
    }

    @Test
    fun `refund invoice completes only after Apple refund`() {
        val requested = InvoiceStateMachine.next(
            InvoiceType.REFUND,
            InvoiceStatus.WAITING,
            InvoiceEventType.REFUND_REQUESTED,
        )
        val completed = InvoiceStateMachine.next(
            InvoiceType.REFUND,
            requested,
            InvoiceEventType.REFUNDED,
        )

        assertEquals(InvoiceStatus.WAITING, requested)
        assertEquals(InvoiceStatus.COMPLETED, completed)
    }

    @Test
    fun `refund decline fails refund invoice without changing normal invoice`() {
        assertEquals(
            InvoiceStatus.FAILED,
            InvoiceStateMachine.next(
                InvoiceType.REFUND,
                InvoiceStatus.WAITING,
                InvoiceEventType.REFUND_DECLINED,
            ),
        )
        assertFailsWith<IllegalInvoiceTransition> {
            InvoiceStateMachine.next(
                InvoiceType.NORMAL,
                InvoiceStatus.COMPLETED,
                InvoiceEventType.REFUND_DECLINED,
            )
        }
    }

    @Test
    fun `normal invoice cannot complete without fulfillment event`() {
        assertFailsWith<IllegalInvoiceTransition> {
            InvoiceStateMachine.next(
                InvoiceType.NORMAL,
                InvoiceStatus.WAITING,
                InvoiceEventType.REFUNDED,
            )
        }
    }
}
