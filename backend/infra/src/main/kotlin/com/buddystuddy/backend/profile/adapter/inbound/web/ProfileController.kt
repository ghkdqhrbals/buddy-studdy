package com.buddystuddy.backend.profile.adapter.inbound.web

import com.buddystuddy.backend.auth.application.permission.Permissions
import com.buddystuddy.backend.auth.application.permission.RequirePermission
import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystuddy.backend.profile.adapter.inbound.web.dto.ProfileUpdateRequest
import com.buddystuddy.backend.profile.application.port.inbound.ProfileUpdateCommand
import com.buddystuddy.backend.profile.application.port.inbound.ProfileUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Profile", description = "Authenticated user profile and public-question preference APIs.")
@RequirePermission(Permissions.PROFILE_READ)
class ProfileController(
    private val profiles: ProfileWebPort,
) {
    @Operation(summary = "Fetch my profile", description = "Returns the authenticated user's profile, avatar symbol/color, login state, and public-question preference.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Profile returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @GetMapping("/profile")
    fun profile(authentication: Authentication) = profiles.profile(authentication)

    @Operation(summary = "Update my profile", description = "Updates editable profile fields such as display name, avatar choice/color, and public-question preference.")
    @PatchMapping("/profile")
    @RequirePermission(Permissions.PROFILE_UPDATE)
    fun updateProfile(@RequestBody body: ProfileUpdateRequest, authentication: Authentication) =
        profiles.updateProfile(body, authentication)
}

interface ProfileWebPort {
    fun profile(authentication: Authentication): Any
    fun updateProfile(body: ProfileUpdateRequest, authentication: Authentication): Any
}

@Component
class ProfileWebAdapter(
    private val profiles: ProfileUseCase,
) : ProfileWebPort {
    override fun profile(authentication: Authentication) = profiles.profile(authentication.principalOrThrow())

    override fun updateProfile(body: ProfileUpdateRequest, authentication: Authentication) =
        profiles.updateProfile(authentication.principalOrThrow(), body.toCommand())
}

private fun ProfileUpdateRequest.toCommand() = ProfileUpdateCommand(
    displayName = displayName,
    bio = bio,
    avatarSymbolName = avatarSymbolName,
    avatarColorSeed = avatarColorSeed,
    pageAccess = pageAccess,
)
