package com.buddystudy.backend.common.adapter.inbound.web.dto

import java.time.Instant

data class HealthResponse(val ok: Boolean = true)

data class ReadinessResponse(
    val ok: Boolean,
    val checkedAt: Instant,
    val service: String,
    val environment: String,
    val checks: Map<String, ReadinessCheckResponse>,
)

data class ReadinessCheckResponse(
    val ok: Boolean,
    val message: String? = null,
)
