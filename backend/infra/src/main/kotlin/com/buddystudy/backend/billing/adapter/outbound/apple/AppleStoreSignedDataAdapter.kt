package com.buddystudy.backend.billing.adapter.outbound.apple

import com.apple.itunes.storekit.model.Environment
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload
import com.apple.itunes.storekit.verification.SignedDataVerifier
import com.apple.itunes.storekit.verification.VerificationException
import com.buddystudy.backend.billing.application.model.VerifiedAppleNotification
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.port.outbound.AppleBillingVerificationPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingProductType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

@Component
class AppleStoreSignedDataAdapter(
    private val properties: BuddyStudyProperties,
    private val resourceLoader: ResourceLoader,
) : AppleBillingVerificationPort {
    override suspend fun verifyTransaction(
        signedTransaction: String,
        environment: BillingEnvironment,
    ): VerifiedAppleTransaction = withContext(Dispatchers.IO) {
        try {
            verifier(environment).verifyAndDecodeTransaction(signedTransaction).toVerified(signedTransaction)
        } catch (error: VerificationException) {
            throw invalidPayload("Apple transaction signature verification failed: ${error.status}", error)
        } catch (error: IllegalArgumentException) {
            throw configurationError(error)
        }
    }

    override suspend fun verifyNotification(signedPayload: String): VerifiedAppleNotification = withContext(Dispatchers.IO) {
        val environments = buildList {
            add(BillingEnvironment.PRODUCTION)
            add(BillingEnvironment.SANDBOX)
            if (properties.billing.apple.allowXcodeEnvironment) add(BillingEnvironment.XCODE)
        }
        var lastError: Exception? = null
        for (environment in environments) {
            try {
                val verifier = verifier(environment)
                val decoded = verifier.verifyAndDecodeNotification(signedPayload)
                val data = decoded.data
                val signedTransaction = data?.signedTransactionInfo
                val transaction = signedTransaction?.let {
                    verifier.verifyAndDecodeTransaction(it).toVerified(it)
                }
                return@withContext VerifiedAppleNotification(
                    notificationUUID = decoded.notificationUUID
                        ?: throw invalidPayload("Apple notification UUID is missing."),
                    notificationType = decoded.rawNotificationType
                        ?: throw invalidPayload("Apple notification type is missing."),
                    subtype = decoded.rawSubtype,
                    environment = environment,
                    signedAt = decoded.signedDate?.let(Instant::ofEpochMilli)
                        ?: throw invalidPayload("Apple notification signedDate is missing."),
                    signedPayloadSha256 = signedPayload.sha256(),
                    transaction = transaction,
                )
            } catch (error: VerificationException) {
                lastError = error
            } catch (error: IllegalArgumentException) {
                lastError = error
                if (environment == BillingEnvironment.PRODUCTION && properties.billing.apple.appAppleId <= 0) {
                    // Production cannot be verified without an App Store numeric app id, but sandbox may still be valid.
                    continue
                }
            }
        }
        throw invalidPayload("Apple notification signature verification failed.", lastError)
    }

    private fun verifier(environment: BillingEnvironment): SignedDataVerifier {
        val config = properties.billing.apple
        if (config.bundleId.isBlank()) throw configurationError(IllegalStateException("APPLE_IAP_BUNDLE_ID is missing."))
        if (environment == BillingEnvironment.PRODUCTION && config.appAppleId <= 0) {
            throw configurationError(IllegalStateException("APPLE_IAP_APP_APPLE_ID is required in production."))
        }
        if (environment == BillingEnvironment.XCODE && !config.allowXcodeEnvironment) {
            throw invalidPayload("Xcode StoreKit transactions are disabled.")
        }
        val encodedRoots = config.rootCertificatesBase64.filter(String::isNotBlank).ifEmpty {
            config.rootCertificateResources.map { location ->
                val resource = resourceLoader.getResource(location)
                if (!resource.exists()) {
                    throw configurationError(IllegalStateException("Apple root certificate resource is missing: $location"))
                }
                resource.inputStream.bufferedReader().use { it.readText() }
            }
        }
        if (encodedRoots.isEmpty()) {
            throw configurationError(IllegalStateException("Apple root certificates are missing."))
        }
        val roots = encodedRoots
            .map { encoded ->
                val normalized = encoded
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .filterNot(Char::isWhitespace)
                ByteArrayInputStream(Base64.getDecoder().decode(normalized))
            }
            .toSet()
        return SignedDataVerifier(
            roots,
            config.bundleId,
            config.appAppleId.takeIf { environment == BillingEnvironment.PRODUCTION && it > 0 },
            environment.apple(),
            config.enableOnlineChecks,
        )
    }

    private fun JWSTransactionDecodedPayload.toVerified(signedPayload: String): VerifiedAppleTransaction {
        val type = type ?: throw invalidPayload("Apple transaction product type is missing.")
        return VerifiedAppleTransaction(
            transactionId = transactionId ?: throw invalidPayload("Apple transactionId is missing."),
            originalTransactionId = originalTransactionId
                ?: throw invalidPayload("Apple originalTransactionId is missing."),
            appTransactionId = appTransactionId,
            webOrderLineItemId = webOrderLineItemId,
            appAccountToken = appAccountToken ?: throw invalidPayload("Apple appAccountToken is missing."),
            productId = productId ?: throw invalidPayload("Apple productId is missing."),
            productType = BillingProductType.valueOf(type.name),
            environment = environment?.billing() ?: throw invalidPayload("Apple environment is missing."),
            quantity = quantity ?: 1,
            priceMilliunits = price,
            currency = currency,
            purchaseAt = purchaseDate?.let(Instant::ofEpochMilli)
                ?: throw invalidPayload("Apple purchaseDate is missing."),
            originalPurchaseAt = originalPurchaseDate?.let(Instant::ofEpochMilli),
            expiresAt = expiresDate?.let(Instant::ofEpochMilli),
            revocationAt = revocationDate?.let(Instant::ofEpochMilli),
            revocationReason = rawRevocationReason,
            signedAt = signedDate?.let(Instant::ofEpochMilli)
                ?: throw invalidPayload("Apple signedDate is missing."),
            signedPayloadSha256 = signedPayload.sha256(),
        )
    }

    private fun BillingEnvironment.apple(): Environment = when (this) {
        BillingEnvironment.SANDBOX -> Environment.SANDBOX
        BillingEnvironment.PRODUCTION -> Environment.PRODUCTION
        BillingEnvironment.XCODE -> Environment.XCODE
    }

    private fun Environment.billing(): BillingEnvironment = when (this) {
        Environment.SANDBOX -> BillingEnvironment.SANDBOX
        Environment.PRODUCTION -> BillingEnvironment.PRODUCTION
        Environment.XCODE, Environment.LOCAL_TESTING -> BillingEnvironment.XCODE
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun invalidPayload(message: String, cause: Throwable? = null): ApiException =
        ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ApiErrorCode.BILLING_TRANSACTION_INVALID,
            message,
        ).also { if (cause != null) it.initCause(cause) }

    private fun configurationError(cause: Throwable): ApiException =
        ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            ApiErrorCode.BILLING_CONFIGURATION_ERROR,
            "Apple billing verification is not configured.",
        ).also { it.initCause(cause) }
}
