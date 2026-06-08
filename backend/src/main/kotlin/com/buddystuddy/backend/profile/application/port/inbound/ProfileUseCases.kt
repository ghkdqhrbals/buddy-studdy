package com.buddystuddy.backend.profile.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.dto.ProfileUpdateRequest
import com.buddystuddy.backend.dto.UserProfileResponse

interface ProfileUseCase {
    fun profile(principal: Principal): UserProfileResponse
    fun updateProfile(principal: Principal, payload: ProfileUpdateRequest): UserProfileResponse
}
