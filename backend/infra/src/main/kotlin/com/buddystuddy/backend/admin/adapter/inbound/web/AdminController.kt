package com.buddystuddy.backend.admin.adapter.inbound.web

import com.buddystuddy.backend.admin.application.port.inbound.AdminUseCase
import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@Tag(name = "OpenAI", description = "OpenAI model options and authenticated API-key status APIs.")
class AdminController(
    private val admin: AdminWebPort,
) {
    @Operation(summary = "List supported OpenAI models", description = "Returns the OpenAI model options that the app allows users to choose for each study room.")
    @GetMapping("/openai/models")
    fun models() = admin.models()

    @Operation(summary = "Fetch OpenAI API status", description = "Returns whether the authenticated user has an OpenAI API key configured and whether the backend currently considers it valid.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "API status returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @GetMapping("/api")
    fun api(authentication: Authentication) = admin.api(authentication)

    @Operation(summary = "Validate saved OpenAI API key", description = "Tests the authenticated user's saved OpenAI API key and returns the current validation result.")
    @PostMapping("/api/validate")
    fun validateApi(authentication: Authentication) = admin.validateApi(authentication)
}

interface AdminWebPort {
    fun models(): Any
    fun api(authentication: Authentication): Any
    fun validateApi(authentication: Authentication): Any
}

@Component
class AdminWebAdapter(
    private val admin: AdminUseCase,
) : AdminWebPort {
    override fun models() = admin.models()

    override fun api(authentication: Authentication) = admin.apiStatus(authentication.principalOrThrow())

    override fun validateApi(authentication: Authentication) = admin.validateApi(authentication.principalOrThrow())
}
