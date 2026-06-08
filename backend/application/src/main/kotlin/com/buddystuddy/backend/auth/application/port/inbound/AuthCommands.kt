package com.buddystuddy.backend.auth.application.port.inbound

data class RegisterDeviceCommand(
    val apnsToken: String = "",
    val platform: String = "ios",
    val apnsEnvironment: String = "production",
    val language: String = "ko",
    val timezone: String = "Asia/Seoul",
)

data class EmailLoginCommand(val email: String, val password: String, val verificationCode: String? = null)
data class PushTokenCommand(val apnsToken: String, val apnsEnvironment: String = "production")
