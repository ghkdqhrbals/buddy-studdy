package com.buddystudy.backend.common.adapter.inbound.web.dto

data class HealthResponse(val ok: Boolean = true)

data class ReadinessResponse(
    val ok: Boolean,
    val checks: Map<String, ReadinessCheckResponse>,
)

data class ReadinessCheckResponse(
    val ok: Boolean,
    val message: String? = null,
)
