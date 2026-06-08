package com.buddystuddy.backend.common.adapter.inbound.web

import com.buddystuddy.backend.dto.HealthResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController {
    @GetMapping("/health", "/api/v1/health")
    fun health() = HealthResponse()
}
