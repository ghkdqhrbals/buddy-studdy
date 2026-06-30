package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.dto.HealthResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "Health", description = "Health-check APIs.")
class HealthController {
    @Operation(summary = "Health check", description = "Returns a lightweight health response for load balancers and deployment smoke tests.")
    @GetMapping("/health", "/api/v1/health")
    fun health() = HealthResponse()
}
