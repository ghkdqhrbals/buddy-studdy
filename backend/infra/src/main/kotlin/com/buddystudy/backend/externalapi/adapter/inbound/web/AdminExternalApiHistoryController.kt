package com.buddystudy.backend.externalapi.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.externalapi.application.model.ExternalApiCallHistory
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryPage
import com.buddystudy.backend.externalapi.application.port.inbound.AdminExternalApiHistoryUseCase
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/external-api-history")
class AdminExternalApiHistoryController(
    private val history: AdminExternalApiHistoryWebPort,
) {
    @GetMapping
    suspend fun page(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) query: String?,
    ): ExternalApiHistoryPage = history.page(
        authorization.adminBearerToken(), cursor, limit, provider, status, query,
    )

    @GetMapping("/{id}")
    suspend fun detail(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable id: Long,
    ): ExternalApiCallHistory = history.detail(authorization.adminBearerToken(), id)
}

interface AdminExternalApiHistoryWebPort {
    suspend fun page(
        adminToken: String,
        cursor: String?,
        limit: Int,
        provider: String?,
        status: String?,
        query: String?,
    ): ExternalApiHistoryPage

    suspend fun detail(adminToken: String, id: Long): ExternalApiCallHistory
}

@Component
class AdminExternalApiHistoryWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val history: AdminExternalApiHistoryUseCase,
) : AdminExternalApiHistoryWebPort {
    override suspend fun page(
        adminToken: String,
        cursor: String?,
        limit: Int,
        provider: String?,
        status: String?,
        query: String?,
    ): ExternalApiHistoryPage {
        authentication.validate(adminToken)
        return history.page(cursor, limit, provider, status, query)
    }

    override suspend fun detail(adminToken: String, id: Long): ExternalApiCallHistory {
        authentication.validate(adminToken)
        return history.detail(id)
    }
}

private fun String?.adminBearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
