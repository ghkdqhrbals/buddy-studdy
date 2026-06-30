package com.buddystudy.backend.auth.application.model

import com.buddystudy.backend.profile.application.model.UserProfileResponse
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

data class LoggedInDeviceResponse(
    val deviceId: String,
    val platform: String,
    val apnsEnvironment: String,
    val timezone: String,
    val lastLoginAt: Instant?,
    val lastSeenAt: Instant,
    val current: Boolean,
)

data class LoggedInDevicesResponse(
    val devices: List<LoggedInDeviceResponse>,
)
