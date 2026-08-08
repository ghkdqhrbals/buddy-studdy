package com.buddystudy.backend.billing.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.billing.application.model.BillingAction
import com.buddystudy.backend.billing.application.model.BillingCatalog
import com.buddystudy.backend.billing.application.model.BillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.BillingInvoicePage
import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.BillingStatusResponse
import com.buddystudy.backend.billing.application.model.CreateBillingCheckoutCommand
import com.buddystudy.backend.billing.application.model.ConfirmRevenueCatTransactionCommand
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.RevenueCatWebhookRequest
import com.buddystudy.backend.billing.application.model.SyncAppleTransactionCommand
import com.buddystudy.backend.billing.application.port.inbound.AppleBillingNotificationUseCase
import com.buddystudy.backend.billing.application.port.inbound.BillingUseCase
import com.buddystudy.backend.billing.application.port.inbound.RevenueCatBillingNotificationUseCase
import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.billing.domain.BillingEnvironment
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/billing")
class BillingController(
    private val billing: BillingWebPort,
) {
    @GetMapping("/status")
    suspend fun status(authentication: Authentication): BillingStatusResponse =
        billing.status(authentication.principalOrThrow())

    @GetMapping("/catalog")
    suspend fun catalog(authentication: Authentication): BillingCatalog =
        billing.catalog(authentication.principalOrThrow())

    @PostMapping("/checkouts")
    suspend fun createCheckout(
        authentication: Authentication,
        @Valid @RequestBody request: CreateBillingCheckoutRequest,
    ): BillingInvoiceSummary = billing.createCheckout(authentication.principalOrThrow(), request)

    @PostMapping("/checkouts/{invoiceNumber}/abandon")
    suspend fun abandonCheckout(
        authentication: Authentication,
        @PathVariable invoiceNumber: UUID,
    ): BillingInvoiceSummary = billing.abandonCheckout(authentication.principalOrThrow(), invoiceNumber)

    @PostMapping("/invoices/{invoiceNumber}/confirm")
    suspend fun confirmRevenueCatTransaction(
        authentication: Authentication,
        @PathVariable invoiceNumber: UUID,
        @Valid @RequestBody request: ConfirmRevenueCatTransactionRequest,
    ): BillingInvoiceSummary = billing.confirmRevenueCatTransaction(
        authentication.principalOrThrow(),
        invoiceNumber,
        request,
    )

    @PostMapping("/apple/transactions")
    suspend fun syncAppleTransaction(
        authentication: Authentication,
        @Valid @RequestBody request: SyncAppleTransactionRequest,
    ): BillingInvoiceSummary = billing.syncAppleTransaction(authentication.principalOrThrow(), request)

    @GetMapping("/invoices")
    suspend fun invoices(
        authentication: Authentication,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): BillingInvoicePage = billing.invoices(authentication.principalOrThrow(), limit, offset)

    @GetMapping("/invoices/{invoiceId}")
    suspend fun invoice(
        authentication: Authentication,
        @PathVariable invoiceId: Long,
    ): BillingInvoiceDetail = billing.invoice(authentication.principalOrThrow(), invoiceId)

    @PostMapping("/payments/{paymentId}/refund-requests")
    suspend fun requestRefund(
        authentication: Authentication,
        @PathVariable paymentId: Long,
        @Valid @RequestBody request: BillingActionRequest,
    ): BillingAction = billing.requestRefund(authentication.principalOrThrow(), paymentId, request)

    @PostMapping("/subscriptions/{originalTransactionId}/cancellation-requests")
    suspend fun requestCancellation(
        authentication: Authentication,
        @PathVariable originalTransactionId: String,
        @Valid @RequestBody request: BillingActionRequest,
    ): BillingAction = billing.requestCancellation(
        authentication.principalOrThrow(),
        originalTransactionId,
        request,
    )
}

@RestController
@RequestMapping("/api/v1/billing/apple/notifications")
class AppleBillingNotificationController(
    private val notifications: AppleBillingNotificationWebPort,
) {
    @PostMapping
    suspend fun receive(
        @Valid @RequestBody request: AppleServerNotificationRequest,
    ): ResponseEntity<Unit> {
        notifications.receive(request)
        return ResponseEntity.ok().build()
    }
}

@RestController
@RequestMapping("/api/v1/billing/revenuecat/webhooks")
class RevenueCatBillingNotificationController(
    private val notifications: RevenueCatBillingNotificationWebPort,
) {
    @PostMapping
    suspend fun receive(
        @RequestHeader("X-RevenueCat-Webhook-Signature") signature: String,
        @RequestBody rawBody: ByteArray,
    ): ResponseEntity<Unit> {
        notifications.receive(rawBody, signature)
        return ResponseEntity.ok().build()
    }
}

data class SyncAppleTransactionRequest(
    @field:NotBlank
    @field:Size(min = 64, max = 200_000)
    var signedTransaction: String = "",
    @field:NotBlank
    @field:Pattern(regexp = "SANDBOX|PRODUCTION|XCODE")
    var environment: String = "PRODUCTION",
    var invoiceNumber: UUID? = null,
)

data class ConfirmRevenueCatTransactionRequest(
    @field:NotBlank
    @field:Size(max = 191)
    @field:Pattern(regexp = "[A-Za-z0-9._:-]+")
    var transactionId: String = "",
)

data class CreateBillingCheckoutRequest(
    @field:NotBlank
    @field:Size(max = 191)
    @field:Pattern(regexp = "[A-Za-z0-9._-]+")
    var productId: String = "",
    @field:NotBlank
    @field:Size(min = 8, max = 191)
    @field:Pattern(regexp = "[A-Za-z0-9._:-]+")
    var idempotencyKey: String = "",
)

data class BillingActionRequest(
    @field:NotBlank
    @field:Size(min = 8, max = 191)
    @field:Pattern(regexp = "[A-Za-z0-9._:-]+")
    var idempotencyKey: String = "",
    @field:Size(max = 1000)
    var reason: String? = null,
)

data class AppleServerNotificationRequest(
    @field:NotBlank
    @field:Size(min = 64, max = 200_000)
    var signedPayload: String = "",
)

interface BillingWebPort {
    suspend fun status(principal: Principal): BillingStatusResponse
    suspend fun catalog(principal: Principal): BillingCatalog
    suspend fun createCheckout(principal: Principal, request: CreateBillingCheckoutRequest): BillingInvoiceSummary
    suspend fun abandonCheckout(principal: Principal, invoiceNumber: UUID): BillingInvoiceSummary
    suspend fun confirmRevenueCatTransaction(
        principal: Principal,
        invoiceNumber: UUID,
        request: ConfirmRevenueCatTransactionRequest,
    ): BillingInvoiceSummary
    suspend fun syncAppleTransaction(principal: Principal, request: SyncAppleTransactionRequest): BillingInvoiceSummary
    suspend fun invoices(principal: Principal, limit: Int, offset: Int): BillingInvoicePage
    suspend fun invoice(principal: Principal, invoiceId: Long): BillingInvoiceDetail
    suspend fun requestRefund(principal: Principal, paymentId: Long, request: BillingActionRequest): BillingAction
    suspend fun requestCancellation(
        principal: Principal,
        originalTransactionId: String,
        request: BillingActionRequest,
    ): BillingAction
}

interface AppleBillingNotificationWebPort {
    suspend fun receive(request: AppleServerNotificationRequest)
}

interface RevenueCatBillingNotificationWebPort {
    suspend fun receive(rawBody: ByteArray, signature: String)
}

@Component
class BillingWebAdapter(
    private val billing: BillingUseCase,
    private val meterRegistry: MeterRegistry,
) : BillingWebPort {
    override suspend fun status(principal: Principal): BillingStatusResponse = billing.status(principal)

    override suspend fun catalog(principal: Principal): BillingCatalog = billing.catalog(principal)

    override suspend fun createCheckout(
        principal: Principal,
        request: CreateBillingCheckoutRequest,
    ): BillingInvoiceSummary = billing.createCheckout(
        principal,
        CreateBillingCheckoutCommand(request.productId.trim(), request.idempotencyKey.trim()),
    )

    override suspend fun abandonCheckout(principal: Principal, invoiceNumber: UUID): BillingInvoiceSummary =
        billing.abandonCheckout(principal, invoiceNumber)

    override suspend fun confirmRevenueCatTransaction(
        principal: Principal,
        invoiceNumber: UUID,
        request: ConfirmRevenueCatTransactionRequest,
    ): BillingInvoiceSummary = try {
        billing.confirmRevenueCatTransaction(
            principal,
            invoiceNumber,
            ConfirmRevenueCatTransactionCommand(request.transactionId.trim()),
        )
    } catch (error: ApiException) {
        if (error.code == ApiErrorCode.BILLING_TRANSACTION_CONFLICT) {
            meterRegistry.counter("billing.lifecycle.ownership.conflicts").increment()
        }
        throw error
    }

    override suspend fun syncAppleTransaction(
        principal: Principal,
        request: SyncAppleTransactionRequest,
    ): BillingInvoiceSummary = try {
        billing.syncAppleTransaction(
            principal,
            SyncAppleTransactionCommand(
                signedTransaction = request.signedTransaction.trim(),
                environment = request.environment.toBillingEnvironment(),
                invoiceNumber = request.invoiceNumber,
            ),
        )
    } catch (error: ApiException) {
        if (error.code == ApiErrorCode.BILLING_TRANSACTION_CONFLICT) {
            meterRegistry.counter("billing.lifecycle.ownership.conflicts").increment()
        }
        throw error
    }

    override suspend fun invoices(principal: Principal, limit: Int, offset: Int): BillingInvoicePage =
        billing.invoices(principal, limit, offset)

    override suspend fun invoice(principal: Principal, invoiceId: Long): BillingInvoiceDetail =
        billing.invoice(principal, invoiceId)

    override suspend fun requestRefund(
        principal: Principal,
        paymentId: Long,
        request: BillingActionRequest,
    ): BillingAction = billing.requestRefund(
        principal,
        paymentId,
        RequestBillingActionCommand(request.idempotencyKey, request.reason),
    )

    override suspend fun requestCancellation(
        principal: Principal,
        originalTransactionId: String,
        request: BillingActionRequest,
    ): BillingAction = billing.requestCancellation(
        principal,
        originalTransactionId,
        RequestBillingActionCommand(request.idempotencyKey, request.reason),
    )

    private fun String.toBillingEnvironment(): BillingEnvironment = try {
        BillingEnvironment.valueOf(trim().uppercase())
    } catch (_: IllegalArgumentException) {
        throw ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ApiErrorCode.VALIDATION_ERROR,
            "Billing environment is invalid.",
        )
    }
}

@Component
class AppleBillingNotificationWebAdapter(
    private val notifications: AppleBillingNotificationUseCase,
) : AppleBillingNotificationWebPort {
    override suspend fun receive(request: AppleServerNotificationRequest) =
        notifications.receive(request.signedPayload.trim())
}

@Component
class RevenueCatBillingNotificationWebAdapter(
    private val notifications: RevenueCatBillingNotificationUseCase,
) : RevenueCatBillingNotificationWebPort {
    override suspend fun receive(rawBody: ByteArray, signature: String) =
        notifications.receive(RevenueCatWebhookRequest(rawBody, signature.trim()))
}
