package com.buddystudy.backend.billing.adapter.outbound.apple

import com.buddystudy.backend.test.testExternalApiHistoryRecorder

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.billing.domain.BillingEnvironment
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.util.Base64
import java.util.UUID

class AppleStoreSignedDataAdapterTest {
    @Test
    fun `development verifier accepts Xcode StoreKit transaction payload`() = runBlocking {
        val adapter = adapter(allowXcodeEnvironment = true)
        val token = UUID.fromString("791a9a18-91a8-4a8f-a96c-fb8a297690fd")

        val transaction = adapter.verifyTransaction(xcodeJws(token), BillingEnvironment.XCODE)

        assertThat(transaction.environment).isEqualTo(BillingEnvironment.XCODE)
        assertThat(transaction.appAccountToken).isEqualTo(token)
        assertThat(transaction.productId)
            .isEqualTo("io.github.ghkdqhrbals.StudyMate.tier2.monthly")
        assertThat(transaction.transactionId).isEqualTo("xcode-transaction-1")
    }

    @Test
    fun `production policy rejects Xcode StoreKit transaction payload`() {
        val adapter = adapter(allowXcodeEnvironment = false)

        assertThatThrownBy {
            runBlocking {
                adapter.verifyTransaction(
                    xcodeJws(UUID.fromString("791a9a18-91a8-4a8f-a96c-fb8a297690fd")),
                    BillingEnvironment.XCODE,
                )
            }
        }
            .isInstanceOfSatisfying(ApiException::class.java) { error ->
                assertThat(error.code)
                    .isEqualTo(ApiErrorCode.BILLING_TRANSACTION_INVALID)
            }
    }

    private fun adapter(allowXcodeEnvironment: Boolean): AppleStoreSignedDataAdapter {
        val properties = BuddyStudyProperties().apply {
            billing.apple.allowXcodeEnvironment = allowXcodeEnvironment
        }
        return AppleStoreSignedDataAdapter(
            properties, DefaultResourceLoader(), testExternalApiHistoryRecorder(),
        )
    }

    private fun xcodeJws(appAccountToken: UUID): String {
        val header = base64Url("""{"alg":"none","typ":"JWT"}""")
        val payload = base64Url(
            """
            {
              "transactionId":"xcode-transaction-1",
              "originalTransactionId":"xcode-original-1",
              "bundleId":"io.github.ghkdqhrbals.StudyMate",
              "productId":"io.github.ghkdqhrbals.StudyMate.tier2.monthly",
              "type":"Auto-Renewable Subscription",
              "appAccountToken":"$appAccountToken",
              "environment":"Xcode",
              "quantity":1,
              "price":7900000,
              "currency":"KRW",
              "purchaseDate":1785772800000,
              "originalPurchaseDate":1785772800000,
              "expiresDate":1788451200000,
              "signedDate":1785772801000
            }
            """.trimIndent(),
        )
        return "$header.$payload.local-signature"
    }

    private fun base64Url(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray())
}
