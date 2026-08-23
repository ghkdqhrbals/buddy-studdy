package com.buddystudy.backend.admin.status.application.model

import java.time.Instant

data class AdminTranslationProviderHealthResponse(
    val checkedAt: Instant,
    val providers: List<AdminTranslationProviderHealth>,
)

data class AdminTranslationProviderHealth(
    val provider: String,
    val status: String,
    val enabled: Boolean,
    val latencyMs: Long?,
    val detail: String,
)
