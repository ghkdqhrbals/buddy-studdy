package com.buddystuddy.backend.profile.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.profile.application.model.UserProfileResponse

interface ProfileUseCase {
    fun profile(principal: Principal): UserProfileResponse
    fun updateProfile(principal: Principal, command: ProfileUpdateCommand): UserProfileResponse
}
