package com.buddystuddy.backend.auth.application.model

import com.buddystuddy.backend.profile.application.model.UserProfileResponse
import java.time.Instant

data class DeviceRegisterResponse(
    val deviceId: String,
    val clientSecret: String,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
)

data class AccessTokenResponse(val accessToken: String, val accessTokenExpiresAt: Instant)
data class EmailVerificationCodeResponse(val email: String, val expiresInSeconds: Long)
data class GoogleLoginResponse(val profile: UserProfileResponse, val accessToken: String, val accessTokenExpiresAt: Instant)
typealias EmailLoginResponse = GoogleLoginResponse
