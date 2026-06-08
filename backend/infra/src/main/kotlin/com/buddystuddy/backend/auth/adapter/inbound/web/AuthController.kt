package com.buddystuddy.backend.auth.adapter.inbound.web

import com.buddystuddy.backend.auth.PrincipalResolver
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.inbound.IssueDeviceTokenUseCase
import com.buddystuddy.backend.auth.application.port.inbound.EmailLoginCommand
import com.buddystuddy.backend.auth.application.port.inbound.LoginUseCase
import com.buddystuddy.backend.auth.application.port.inbound.RegisterDeviceUseCase
import com.buddystuddy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystuddy.backend.auth.application.port.inbound.PushTokenCommand
import com.buddystuddy.backend.auth.application.port.inbound.UpdatePushTokenUseCase
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.auth.adapter.inbound.web.dto.DeviceRegisterRequest
import com.buddystuddy.backend.auth.adapter.inbound.web.dto.EmailLoginRequest
import com.buddystuddy.backend.auth.adapter.inbound.web.dto.EmailVerificationCodeRequest
import com.buddystuddy.backend.auth.application.model.EmailVerificationCodeResponse
import com.buddystuddy.backend.auth.adapter.inbound.web.dto.GoogleLoginRequest
import com.buddystuddy.backend.auth.adapter.inbound.web.dto.PushTokenRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.stereotype.Component
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class AuthController(
    private val auth: AuthWebPort,
) {
    @PostMapping("/devices/register")
    fun register(@Valid @RequestBody body: DeviceRegisterRequest) = auth.register(body)

    @PostMapping("/auth/token")
    fun token(
        @RequestHeader("X-Device-Id") deviceId: String,
        @RequestHeader("X-Client-Secret") clientSecret: String,
    ) = auth.token(deviceId, clientSecret)

    @PostMapping("/auth/google")
    fun google(
        @RequestBody body: GoogleLoginRequest,
        request: HttpServletRequest,
        @RequestHeader("X-Device-Id", required = false) deviceId: String?,
        @RequestHeader("X-Client-Secret", required = false) clientSecret: String?,
    ) = auth.google(body, request, deviceId, clientSecret)

    @PostMapping("/auth/email/code")
    fun emailCode(
        @Valid @RequestBody body: EmailVerificationCodeRequest,
        request: HttpServletRequest,
        @RequestHeader("X-Device-Id", required = false) deviceId: String?,
        @RequestHeader("X-Client-Secret", required = false) clientSecret: String?,
    ): EmailVerificationCodeResponse = auth.emailCode(body, request, deviceId, clientSecret)

    @PostMapping("/auth/email")
    fun email(
        @Valid @RequestBody body: EmailLoginRequest,
        request: HttpServletRequest,
        @RequestHeader("X-Device-Id", required = false) deviceId: String?,
        @RequestHeader("X-Client-Secret", required = false) clientSecret: String?,
    ) = auth.email(body, request, deviceId, clientSecret)

    @PutMapping("/me/push-token")
    fun pushToken(@RequestBody body: PushTokenRequest, request: HttpServletRequest): ResponseEntity<Unit> =
        auth.pushToken(body, request)
}

interface AuthWebPort {
    fun register(body: DeviceRegisterRequest): Any
    fun token(deviceId: String, clientSecret: String): Any
    fun google(body: GoogleLoginRequest, request: HttpServletRequest, deviceId: String?, clientSecret: String?): Any
    fun emailCode(body: EmailVerificationCodeRequest, request: HttpServletRequest, deviceId: String?, clientSecret: String?): EmailVerificationCodeResponse
    fun email(body: EmailLoginRequest, request: HttpServletRequest, deviceId: String?, clientSecret: String?): Any
    fun pushToken(body: PushTokenRequest, request: HttpServletRequest): ResponseEntity<Unit>
}

@Component
class AuthWebAdapter(
    private val registerDevice: RegisterDeviceUseCase,
    private val issueDeviceToken: IssueDeviceTokenUseCase,
    private val login: LoginUseCase,
    private val updatePushToken: UpdatePushTokenUseCase,
    private val principals: PrincipalResolver,
) : AuthWebPort {
    override fun register(body: DeviceRegisterRequest) = registerDevice.register(body.toCommand())

    override fun token(deviceId: String, clientSecret: String) = issueDeviceToken.token(deviceId, clientSecret)

    override fun google(body: GoogleLoginRequest, request: HttpServletRequest, deviceId: String?, clientSecret: String?) =
        login.googleLogin(loginPrincipal(request, deviceId, clientSecret), body.idToken)

    override fun emailCode(body: EmailVerificationCodeRequest, request: HttpServletRequest, deviceId: String?, clientSecret: String?): EmailVerificationCodeResponse {
        loginPrincipal(request, deviceId, clientSecret)
        return login.emailCode(body.email)
    }

    override fun email(body: EmailLoginRequest, request: HttpServletRequest, deviceId: String?, clientSecret: String?) =
        login.emailLogin(loginPrincipal(request, deviceId, clientSecret), body.toCommand())

    override fun pushToken(body: PushTokenRequest, request: HttpServletRequest): ResponseEntity<Unit> {
        updatePushToken.updatePushToken(principals.authenticate(request), body.toCommand())
        return ResponseEntity.noContent().build()
    }

    private fun loginPrincipal(request: HttpServletRequest, deviceId: String?, clientSecret: String?): Principal =
        try {
            principals.authenticate(request)
        } catch (error: ApiException) {
            if (deviceId.isNullOrBlank() || clientSecret.isNullOrBlank()) throw error
            issueDeviceToken.authenticateDevice(deviceId, clientSecret)
        }
}

private fun DeviceRegisterRequest.toCommand() = RegisterDeviceCommand(
    apnsToken = apnsToken,
    platform = platform,
    apnsEnvironment = apnsEnvironment,
    language = language,
    timezone = timezone,
)

private fun EmailLoginRequest.toCommand() = EmailLoginCommand(
    email = email,
    password = password,
    verificationCode = verificationCode,
)

private fun PushTokenRequest.toCommand() = PushTokenCommand(
    apnsToken = apnsToken,
    apnsEnvironment = apnsEnvironment,
)
