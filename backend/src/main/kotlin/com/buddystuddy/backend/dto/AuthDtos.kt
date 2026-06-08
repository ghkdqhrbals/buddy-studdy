package com.buddystuddy.backend.dto

import jakarta.validation.constraints.Email
import java.time.Instant

data class DeviceRegisterRequest(
    val apnsToken: String = "",
    val platform: String = "ios",
    val apnsEnvironment: String = "production",
    val language: String = "ko",
    val timezone: String = "Asia/Seoul",
)

data class DeviceRegisterResponse(
    val deviceId: String,
    val clientSecret: String,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
)

data class AccessTokenResponse(val accessToken: String, val accessTokenExpiresAt: Instant)
data class PushTokenRequest(val apnsToken: String, val apnsEnvironment: String = "production")
data class GoogleLoginRequest(val idToken: String)
data class EmailVerificationCodeRequest(@field:Email val email: String)
data class EmailVerificationCodeResponse(val email: String, val expiresInSeconds: Long)
data class EmailLoginRequest(@field:Email val email: String, val password: String, val verificationCode: String? = null)
data class GoogleLoginResponse(val profile: UserProfileResponse, val accessToken: String, val accessTokenExpiresAt: Instant)
typealias EmailLoginResponse = GoogleLoginResponse
