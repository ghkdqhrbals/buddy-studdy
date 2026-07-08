package com.buddystudy.backend.profile.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.AccessTokenResponse
import com.buddystudy.backend.profile.application.model.UserProfileResponse

interface ProfileUseCase {
    fun profile(principal: Principal): UserProfileResponse
    fun updateProfile(principal: Principal, command: ProfileUpdateCommand): UserProfileResponse
    fun withdrawProfile(principal: Principal): AccessTokenResponse
}
