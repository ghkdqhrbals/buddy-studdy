package com.buddystudy.backend.admin.status.application.service

import com.buddystudy.backend.admin.status.application.model.AdminTranslationProviderHealthResponse
import com.buddystudy.backend.admin.status.application.port.inbound.AdminProviderHealthUseCase
import com.buddystudy.backend.admin.status.application.port.outbound.AdminProviderHealthPort
import org.springframework.stereotype.Service

@Service
class AdminProviderHealthService(
    private val health: AdminProviderHealthPort,
) : AdminProviderHealthUseCase {
    override suspend fun checkTranslationProviders(): AdminTranslationProviderHealthResponse =
        health.checkTranslationProviders()
}
