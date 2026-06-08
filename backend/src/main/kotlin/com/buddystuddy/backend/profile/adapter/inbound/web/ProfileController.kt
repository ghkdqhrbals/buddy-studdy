package com.buddystuddy.backend.profile.adapter.inbound.web

import com.buddystuddy.backend.auth.PrincipalService
import com.buddystuddy.backend.profile.adapter.inbound.web.dto.ProfileUpdateRequest
import com.buddystuddy.backend.profile.application.port.inbound.ProfileUpdateCommand
import com.buddystuddy.backend.profile.application.port.inbound.ProfileUseCase
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class ProfileController(
    private val profiles: ProfileUseCase,
    private val principals: PrincipalService,
) {
    @GetMapping("/me/profile")
    fun profile(request: HttpServletRequest) = profiles.profile(principals.authenticate(request))

    @PatchMapping("/me/profile")
    fun updateProfile(@RequestBody body: ProfileUpdateRequest, request: HttpServletRequest) =
        profiles.updateProfile(principals.authenticate(request), body.toCommand())
}

private fun ProfileUpdateRequest.toCommand() = ProfileUpdateCommand(
    displayName = displayName,
    bio = bio,
    avatarSymbolName = avatarSymbolName,
    avatarColorSeed = avatarColorSeed,
    pageAccess = pageAccess,
)
