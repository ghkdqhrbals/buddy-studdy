package com.buddystudy.backend.externalapi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ExternalApiHistoryCoverageTest {
    @Test
    fun `every implemented external provider adapter records request and response history`() {
        EXTERNAL_PROVIDER_ADAPTERS.forEach { relativePath ->
            val source = Files.readString(repositoryRoot().resolve(relativePath))
            assertThat(source)
                .describedAs("$relativePath must use the shared external API history recorder")
                .contains("ExternalApiHistoryRecorder")
                .contains("ExternalApiRequest")
                .contains("ExternalApiResponse")
        }
    }

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(6) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Backend repository root was not found.")
    }

    private companion object {
        val EXTERNAL_PROVIDER_ADAPTERS = listOf(
            "infra/src/main/kotlin/com/buddystudy/backend/admin/status/adapter/outbound/health/AdminTranslationProviderHealthProbe.kt",
            "infra/src/main/kotlin/com/buddystudy/backend/appupdate/adapter/outbound/firebase/FirebaseAppControlRemoteConfigAdapter.kt",
            "infra/src/main/kotlin/com/buddystudy/backend/auth/adapter/outbound/email/SmtpEmailVerificationSender.kt",
            "infra/src/main/kotlin/com/buddystudy/backend/auth/adapter/outbound/apple/AppleIdentityJwtAdapter.kt",
            "infra/src/main/kotlin/com/buddystudy/backend/auth/adapter/outbound/google/GoogleIdentityWebClientAdapter.kt",
            "infra/src/main/kotlin/com/buddystudy/backend/billing/adapter/outbound/revenuecat/RevenueCatCustomerInfoAdapter.kt",
            "infra/src/main/kotlin/com/buddystudy/backend/billing/adapter/outbound/apple/AppleStoreSignedDataAdapter.kt",
            "infra/src/main/kotlin/com/buddystudy/backend/billing/adapter/outbound/revenuecat/RevenueCatTransactionVerificationAdapter.kt",
            "infra/src/main/kotlin/com/buddystudy/backend/study/adapter/outbound/apns/ApnsPushNotificationAdapter.kt",
            "infra/src/main/kotlin/com/buddystudy/backend/study/adapter/outbound/openai/OpenAIRequestExecutor.kt",
            "infra/src/main/kotlin/com/buddystudy/backend/study/adapter/outbound/translation/LibreTranslateQuestionTranslationProvider.kt",
        )
    }
}
