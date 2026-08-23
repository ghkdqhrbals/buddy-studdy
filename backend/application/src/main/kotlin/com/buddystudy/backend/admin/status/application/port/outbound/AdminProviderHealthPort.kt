package com.buddystudy.backend.admin.status.application.port.outbound

import com.buddystudy.backend.admin.status.application.model.AdminTranslationProviderHealthResponse

interface AdminProviderHealthPort {
    suspend fun checkTranslationProviders(): AdminTranslationProviderHealthResponse
}
