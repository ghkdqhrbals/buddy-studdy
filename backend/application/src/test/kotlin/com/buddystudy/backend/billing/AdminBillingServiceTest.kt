package com.buddystudy.backend.billing

import com.buddystudy.backend.billing.application.model.AdminBillingInvoicePage
import com.buddystudy.backend.billing.application.model.AdminBillingProcessingFailurePage
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.billing.application.service.AdminBillingService
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AdminBillingServiceTest {
    private val now = Instant.parse("2026-08-03T00:00:00Z")

    @Test
    fun `invalid invoice status fails before querying the ledger`() {
        var called = false
        val service = service { _, _ -> called = true; error("must not be called") }

        assertThrows(ApiRuntimeException::class.java) {
            runBlocking { service.invoices(null, "paid-ish", 30, 0) }
        }

        assertEquals(false, called)
    }

    @Test
    fun `list filters are normalized and pagination is bounded`() = runBlocking {
        var captured: List<Any?> = emptyList()
        val service = service { method, args ->
            if (method == "adminInvoices") {
                captured = args.take(4)
                AdminBillingInvoicePage(100, 0, 0, emptyList())
            } else {
                error("Unexpected ledger method $method")
            }
        }

        service.invoices("  buyer@example.com  ", " completed ", 500, -4)

        assertEquals(listOf("buyer@example.com", "COMPLETED", 100, 0), captured)
    }

    @Test
    fun `admin action requires a stable idempotency key`() {
        val service = service { method, _ -> error("Unexpected ledger method $method") }

        assertThrows(ApiRuntimeException::class.java) {
            runBlocking {
                service.requestRefund(11, RequestBillingActionCommand("short", "duplicate charge"))
            }
        }
    }

    @Test
    fun `processing failure filters are normalized and pagination is bounded`() = runBlocking {
        var captured: List<Any?> = emptyList()
        val service = service { method, args ->
            if (method == "adminProcessingFailures") {
                captured = args.take(4)
                AdminBillingProcessingFailurePage(100, 0, 0, emptyList())
            } else {
                error("Unexpected ledger method $method")
            }
        }

        service.processingFailures(" revenuecat_event ", " exhausted ", 500, -4)

        assertEquals(listOf("REVENUECAT_EVENT", "EXHAUSTED", 100, 0), captured)
    }

    private fun service(handler: (String, List<Any?>) -> Any?): AdminBillingService {
        val ledger = Proxy.newProxyInstance(
            BillingLedgerPort::class.java.classLoader,
            arrayOf(BillingLedgerPort::class.java),
        ) { _, method, arguments ->
            handler(method.name, arguments?.dropLast(1).orEmpty())
        } as BillingLedgerPort
        return AdminBillingService(ledger, Clock.fixed(now, ZoneOffset.UTC))
    }
}
