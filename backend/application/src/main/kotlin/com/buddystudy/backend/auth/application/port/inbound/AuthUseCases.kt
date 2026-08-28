package com.buddystudy.backend.auth.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.AccessTokenResponse
import com.buddystudy.backend.auth.application.model.DeviceRegisterResponse
import com.buddystudy.backend.auth.application.model.EmailVerificationCodeResponse
import com.buddystudy.backend.auth.application.model.GoogleLoginResponse
import com.buddystudy.backend.auth.application.model.LoggedInDevicesResponse

interface RegisterDeviceUseCase {
    suspend fun register(command: RegisterDeviceCommand): DeviceRegisterResponse
}

interface IssueDeviceTokenUseCase {
    suspend fun token(deviceId: String, clientSecret: String): AccessTokenResponse
    suspend fun authenticateDevice(deviceId: String, clientSecret: String): Principal
}

interface LoginUseCase {
    suspend fun appleLogin(principal: Principal, idToken: String, referralCode: String? = null): GoogleLoginResponse
    suspend fun googleLogin(principal: Principal, idToken: String, referralCode: String? = null): GoogleLoginResponse
    suspend fun emailLogin(principal: Principal, command: EmailLoginCommand): GoogleLoginResponse
    suspend fun emailCode(email: String): EmailVerificationCodeResponse
    suspend fun logout(principal: Principal)
    suspend fun loggedInDevices(principal: Principal): LoggedInDevicesResponse
}

interface UpdatePushTokenUseCase {
    suspend fun updatePushToken(principal: Principal, command: PushTokenCommand)
}
