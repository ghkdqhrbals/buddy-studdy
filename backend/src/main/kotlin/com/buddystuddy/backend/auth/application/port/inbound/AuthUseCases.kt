package com.buddystuddy.backend.auth.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.dto.AccessTokenResponse
import com.buddystuddy.backend.dto.DeviceRegisterRequest
import com.buddystuddy.backend.dto.DeviceRegisterResponse
import com.buddystuddy.backend.dto.EmailLoginRequest
import com.buddystuddy.backend.dto.EmailVerificationCodeResponse
import com.buddystuddy.backend.dto.GoogleLoginResponse
import com.buddystuddy.backend.dto.PushTokenRequest

interface RegisterDeviceUseCase {
    fun register(payload: DeviceRegisterRequest): DeviceRegisterResponse
}

interface IssueDeviceTokenUseCase {
    fun token(deviceId: String, clientSecret: String): AccessTokenResponse
    fun authenticateDevice(deviceId: String, clientSecret: String): Principal
}

interface LoginUseCase {
    fun googleLogin(principal: Principal, idToken: String): GoogleLoginResponse
    fun emailLogin(principal: Principal, payload: EmailLoginRequest): GoogleLoginResponse
    fun emailCode(email: String): EmailVerificationCodeResponse
}

interface UpdatePushTokenUseCase {
    fun updatePushToken(principal: Principal, payload: PushTokenRequest)
}
