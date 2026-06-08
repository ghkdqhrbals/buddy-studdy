package com.buddystuddy.backend.profile.adapter.inbound.web

import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystuddy.backend.profile.adapter.inbound.web.dto.ProfileUpdateRequest
import com.buddystuddy.backend.profile.application.port.inbound.ProfileUpdateCommand
import com.buddystuddy.backend.profile.application.port.inbound.ProfileUseCase
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class ProfileController(
    private val profiles: ProfileWebPort,
) {
    @GetMapping("/me/profile")
    fun profile(authentication: Authentication) = profiles.profile(authentication)

    @PatchMapping("/me/profile")
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
