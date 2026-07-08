package com.buddystudy.backend.profile.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.profile.adapter.inbound.web.dto.AvatarUpdateRequest
import com.buddystudy.backend.profile.adapter.inbound.web.dto.ProfileUpdateRequest
import com.buddystudy.backend.profile.application.port.inbound.AvatarUpdateCommand
import com.buddystudy.backend.profile.application.port.inbound.ProfileUpdateCommand
import com.buddystudy.backend.profile.application.port.inbound.ProfileUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Profile", description = "Authenticated user profile and public-question preference APIs.")
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
    fun updateProfile(@RequestBody body: ProfileUpdateRequest, authentication: Authentication) =
        profiles.updateProfile(body, authentication)

    @Operation(summary = "Fetch avatar catalog", description = "Returns avatar builder categories, available items, and the user's selected avatar configuration.")
    @GetMapping("/profile/avatar/catalog")
    fun avatarCatalog(authentication: Authentication) = profiles.avatarCatalog(authentication)

    @Operation(summary = "Update avatar builder configuration", description = "Updates the selected avatar slots for the active user's builder avatar.")
    @PatchMapping("/profile/avatar")
    fun updateAvatar(@RequestBody body: AvatarUpdateRequest, authentication: Authentication) =
        profiles.updateAvatar(body, authentication)

    @Operation(summary = "Delete my account", description = "Deletes the active member account and reconnects the current device as anonymous.")
    @DeleteMapping("/profile")
    fun withdrawProfile(authentication: Authentication) = profiles.withdrawProfile(authentication)
}

interface ProfileWebPort {
    fun profile(authentication: Authentication): Any
    fun avatarCatalog(authentication: Authentication): Any
    fun updateAvatar(body: AvatarUpdateRequest, authentication: Authentication): Any
    fun updateProfile(body: ProfileUpdateRequest, authentication: Authentication): Any
    fun withdrawProfile(authentication: Authentication): Any
}

@Component
class ProfileWebAdapter(
    private val profiles: ProfileUseCase,
) : ProfileWebPort {
    override fun profile(authentication: Authentication) = profiles.profile(authentication.principalOrThrow())

    override fun avatarCatalog(authentication: Authentication) =
        profiles.avatarCatalog(authentication.principalOrThrow())

    override fun updateAvatar(body: AvatarUpdateRequest, authentication: Authentication) =
        profiles.updateAvatar(authentication.principalOrThrow(), body.toCommand())

    override fun updateProfile(body: ProfileUpdateRequest, authentication: Authentication) =
        profiles.updateProfile(authentication.principalOrThrow(), body.toCommand())

    override fun withdrawProfile(authentication: Authentication) =
        profiles.withdrawProfile(authentication.principalOrThrow())
}

private fun ProfileUpdateRequest.toCommand() = ProfileUpdateCommand(
    displayName = displayName,
    bio = bio,
    avatarSymbolName = avatarSymbolName,
    avatarColorSeed = avatarColorSeed,
    avatarMode = avatarMode,
    avatarConfig = avatarConfig,
)

private fun AvatarUpdateRequest.toCommand() = AvatarUpdateCommand(
    avatarMode = avatarMode,
    avatarConfig = avatarConfig,
    avatarColorSeed = avatarColorSeed,
)
