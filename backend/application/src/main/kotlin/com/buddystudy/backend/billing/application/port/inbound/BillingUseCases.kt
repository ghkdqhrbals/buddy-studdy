package com.buddystudy.backend.billing.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.billing.application.model.BillingAction
import com.buddystudy.backend.billing.application.model.BillingCatalog
import com.buddystudy.backend.billing.application.model.BillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.BillingInvoicePage
import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.BillingRecoveryResult
import com.buddystudy.backend.billing.application.model.AdminBillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.AdminBillingInvoicePage
import com.buddystudy.backend.billing.application.model.CreateBillingCheckoutCommand
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.RevenueCatWebhookRequest
import com.buddystudy.backend.billing.application.model.SyncAppleTransactionCommand

interface BillingUseCase {
    suspend fun catalog(principal: Principal): BillingCatalog
    suspend fun createCheckout(principal: Principal, command: CreateBillingCheckoutCommand): BillingInvoiceSummary
    suspend fun abandonCheckout(principal: Principal, invoiceNumber: java.util.UUID): BillingInvoiceSummary
    suspend fun syncAppleTransaction(principal: Principal, command: SyncAppleTransactionCommand): BillingInvoiceSummary
    suspend fun invoices(principal: Principal, limit: Int, offset: Int): BillingInvoicePage
    suspend fun invoice(principal: Principal, invoiceId: Long): BillingInvoiceDetail
    suspend fun requestRefund(
        principal: Principal,
        paymentId: Long,
        command: RequestBillingActionCommand,
    ): BillingAction
    suspend fun requestCancellation(
        principal: Principal,
        originalTransactionId: String,
        command: RequestBillingActionCommand,
    ): BillingAction
}

interface AppleBillingNotificationUseCase {
    suspend fun receive(signedPayload: String)
}

interface RevenueCatBillingNotificationUseCase {
    suspend fun receive(request: RevenueCatWebhookRequest)
}

interface BillingRecoveryUseCase {
    suspend fun recoverDueFulfillments(): BillingRecoveryResult
}

interface AdminBillingUseCase {
    suspend fun invoices(query: String?, status: String?, limit: Int, offset: Int): AdminBillingInvoicePage
    suspend fun invoice(invoiceId: Long): AdminBillingInvoiceDetail
    suspend fun requestRefund(invoiceId: Long, command: RequestBillingActionCommand): BillingAction
    suspend fun requestCancellation(invoiceId: Long, command: RequestBillingActionCommand): BillingAction
}
