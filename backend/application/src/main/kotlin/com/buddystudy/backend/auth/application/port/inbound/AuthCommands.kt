package com.buddystudy.backend.auth.application.port.inbound

data class RegisterDeviceCommand(
    val installationId: String = "",
    val apnsToken: String = "",
    val platform: String = "ios",
    val apnsEnvironment: String = "production",
    val language: String = "ko",
    val timezone: String = "Asia/Seoul",
    val appVersion: String? = null,
    val appBuild: String? = null,
)

data class EmailLoginCommand(
    val email: String,
    val password: String,
    val verificationCode: String? = null,
    val referralCode: String? = null,
)
data class PushTokenCommand(val apnsToken: String, val apnsEnvironment: String = "production")
