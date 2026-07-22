package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.dto.HealthResponse
import com.buddystudy.backend.common.adapter.inbound.web.dto.ReadinessResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "Health", description = "Health-check APIs.")
class HealthController(
    private val readiness: ReadinessChecker,
) {
    @Operation(
        summary = "Health check",
        description = "Returns a lightweight health response for load balancers and local diagnostics. Runtime monitoring must use the Cloudflare Health Monitor Worker with /api/v1/health/readiness, not GitHub Actions.",
    )
    @GetMapping("/health", "/api/v1/health")
    fun health() = HealthResponse()

    @Operation(
        summary = "Readiness check",
        description = "Checks required backend dependencies and scheduler freshness for external Slack monitoring.",
    )
    @GetMapping("/health/readiness", "/api/v1/health/readiness")
    suspend fun readiness(): ResponseEntity<ReadinessResponse> {
        val response = readiness.check()
        return readinessResponse(response)
    }

    @Operation(
        summary = "Dependency readiness check",
        description = "Checks only hard serving dependencies for Kubernetes readiness probes. Scheduler freshness is excluded.",
    )
    @GetMapping("/health/dependencies", "/api/v1/health/dependencies")
    suspend fun dependencyReadiness(): ResponseEntity<ReadinessResponse> {
        val response = readiness.check(includeScheduler = false)
        return readinessResponse(response)
    }

    private fun readinessResponse(response: ReadinessResponse): ResponseEntity<ReadinessResponse> {
        return ResponseEntity
            .status(if (response.ok) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE)
            .body(response)
    }
}
