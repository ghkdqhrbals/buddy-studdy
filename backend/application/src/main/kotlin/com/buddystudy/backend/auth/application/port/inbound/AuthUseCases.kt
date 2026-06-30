package com.buddystudy.backend.auth.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.AccessTokenResponse
import com.buddystudy.backend.auth.application.model.DeviceRegisterResponse
import com.buddystudy.backend.auth.application.model.EmailVerificationCodeResponse
import com.buddystudy.backend.auth.application.model.GoogleLoginResponse
import com.buddystudy.backend.auth.application.model.LoggedInDevicesResponse

interface RegisterDeviceUseCase {
    fun register(command: RegisterDeviceCommand): DeviceRegisterResponse
}

interface IssueDeviceTokenUseCase {
    fun token(deviceId: String, clientSecret: String): AccessTokenResponse
    fun authenticateDevice(deviceId: String, clientSecret: String): Principal
}

interface LoginUseCase {
    fun googleLogin(principal: Principal, idToken: String): GoogleLoginResponse
    fun emailLogin(principal: Principal, command: EmailLoginCommand): GoogleLoginResponse
    fun emailCode(email: String): EmailVerificationCodeResponse
    fun logout(principal: Principal)
    fun loggedInDevices(principal: Principal): LoggedInDevicesResponse
}

interface UpdatePushTokenUseCase {
    fun updatePushToken(principal: Principal, command: PushTokenCommand)
}
