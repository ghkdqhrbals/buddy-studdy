package com.buddystudy.backend.admin.status.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.admin.status.application.model.AdminTranslationProviderHealthResponse
import com.buddystudy.backend.admin.status.application.port.inbound.AdminProviderHealthUseCase
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/provider-health")
class AdminTranslationProviderHealthController(
    private val health: AdminTranslationProviderHealthWebPort,
) {
    @PostMapping("/translation/check")
    suspend fun checkTranslationProviders(
        @RequestHeader("Authorization") authorization: String?,
    ): AdminTranslationProviderHealthResponse =
        health.checkTranslationProviders(authorization.adminBearerToken())
}

interface AdminTranslationProviderHealthWebPort {
    suspend fun checkTranslationProviders(adminToken: String): AdminTranslationProviderHealthResponse
}

@Component
class AdminTranslationProviderHealthWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val healthChecks: AdminProviderHealthUseCase,
) : AdminTranslationProviderHealthWebPort {
    override suspend fun checkTranslationProviders(adminToken: String): AdminTranslationProviderHealthResponse {
        authentication.validate(adminToken)
        return healthChecks.checkTranslationProviders()
    }
}

private fun String?.adminBearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
