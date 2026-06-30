package com.buddystudy.backend.auth.adapter.inbound.web.dto

import jakarta.validation.constraints.Email

data class DeviceRegisterRequest(
    val apnsToken: String = "",
    val platform: String = "ios",
    val apnsEnvironment: String = "production",
    val language: String = "ko",
    val timezone: String = "Asia/Seoul",
)

data class PushTokenRequest(val apnsToken: String, val apnsEnvironment: String = "production")
data class GoogleLoginRequest(val idToken: String)
data class EmailVerificationCodeRequest(@field:Email val email: String)
data class EmailLoginRequest(@field:Email val email: String, val password: String, val verificationCode: String? = null)
