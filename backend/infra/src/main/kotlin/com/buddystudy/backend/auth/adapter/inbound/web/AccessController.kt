package com.buddystudy.backend.auth.adapter.inbound.web

import com.buddystudy.backend.auth.application.model.AccessResponse
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.auth.application.port.inbound.AccessUseCase
import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Access", description = "Authenticated page-access state for the iOS app.")
class AccessController(
    private val access: AccessWebPort,
) {
    @Operation(summary = "Fetch page access", description = "Returns backend-computed page access. The app should not infer role or permission details locally.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Access state returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @RequirePermission(Permissions.PROFILE_READ)
    @GetMapping("/access")
    fun access(authentication: Authentication): AccessResponse = access.access(authentication)
}

interface AccessWebPort {
    fun access(authentication: Authentication): AccessResponse
}

@Component
class AccessWebAdapter(
    private val access: AccessUseCase,
) : AccessWebPort {
    override fun access(authentication: Authentication): AccessResponse =
        access.access(authentication.principalOrThrow())
}
