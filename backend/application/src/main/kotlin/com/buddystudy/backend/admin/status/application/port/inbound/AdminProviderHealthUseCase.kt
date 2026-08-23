package com.buddystudy.backend.admin.status.application.port.inbound

import com.buddystudy.backend.admin.status.application.model.AdminTranslationProviderHealthResponse

interface AdminProviderHealthUseCase {
    suspend fun checkTranslationProviders(): AdminTranslationProviderHealthResponse
}
