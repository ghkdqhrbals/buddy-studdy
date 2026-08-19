package com.buddystudy.backend.admin.status

import com.buddystudy.backend.admin.status.application.model.AdminTranslationProviderHealth
import com.buddystudy.backend.admin.status.application.model.AdminTranslationProviderHealthResponse
import com.buddystudy.backend.admin.status.application.port.outbound.AdminProviderHealthPort
import com.buddystudy.backend.admin.status.application.service.AdminProviderHealthService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AdminProviderHealthServiceTest {
    @Test
    fun `returns the independent provider results from the health port`() = runBlocking<Unit> {
        val expected = AdminTranslationProviderHealthResponse(
            checkedAt = Instant.parse("2026-08-19T01:00:00Z"),
            providers = listOf(
                AdminTranslationProviderHealth("libretranslate", "DOWN", true, 250, "Connection failed."),
                AdminTranslationProviderHealth("openai", "UP", true, 80, "Provider API responded successfully."),
            ),
        )
        val service = AdminProviderHealthService(object : AdminProviderHealthPort {
            override suspend fun checkTranslationProviders() = expected
        })

        assertThat(service.checkTranslationProviders()).isEqualTo(expected)
    }
}
