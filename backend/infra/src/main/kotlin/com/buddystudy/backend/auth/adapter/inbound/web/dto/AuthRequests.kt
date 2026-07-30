package com.buddystudy.backend.auth.adapter.inbound.web.dto

import jakarta.validation.constraints.Email

data class DeviceRegisterRequest(
    var installationId: String = "",
    var apnsToken: String = "",
    var platform: String = "ios",
    var apnsEnvironment: String = "production",
    var language: String = "ko",
    var timezone: String = "Asia/Seoul",
    var appVersion: String? = null,
    var appBuild: String? = null,
)

data class PushTokenRequest(var apnsToken: String = "", var apnsEnvironment: String = "production")
data class AppleLoginRequest(var idToken: String = "")
data class GoogleLoginRequest(var idToken: String = "")
data class EmailVerificationCodeRequest(@field:Email var email: String = "")
data class EmailLoginRequest(@field:Email var email: String = "", var password: String = "", var verificationCode: String? = null)
