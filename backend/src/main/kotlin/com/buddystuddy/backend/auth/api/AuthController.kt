package com.buddystuddy.backend.auth.api

import com.buddystuddy.backend.auth.PrincipalService
import com.buddystuddy.backend.auth.service.LoginService
import com.buddystuddy.backend.dto.DeviceRegisterRequest
import com.buddystuddy.backend.dto.EmailLoginRequest
import com.buddystuddy.backend.dto.EmailVerificationCodeRequest
import com.buddystuddy.backend.dto.EmailVerificationCodeResponse
import com.buddystuddy.backend.dto.GoogleLoginRequest
import com.buddystuddy.backend.dto.PushTokenRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
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
    private val login: LoginService,
    private val principals: PrincipalService,
) {
    @PostMapping("/devices/register")
    fun register(@Valid @RequestBody body: DeviceRegisterRequest) = login.register(body)

    @PostMapping("/auth/token")
    fun token(
        @RequestHeader("X-Device-Id") deviceId: String,
        @RequestHeader("X-Client-Secret") clientSecret: String,
    ) = login.token(deviceId, clientSecret)

    @PostMapping("/auth/google")
    fun google(@RequestBody body: GoogleLoginRequest, request: HttpServletRequest) =
        login.googleLogin(principals.authenticate(request), body.idToken)

    @PostMapping("/auth/email/code")
    fun emailCode(@Valid @RequestBody body: EmailVerificationCodeRequest, request: HttpServletRequest): EmailVerificationCodeResponse {
        principals.authenticate(request)
        return login.emailCode(body.email)
    }

    @PostMapping("/auth/email")
    fun email(@Valid @RequestBody body: EmailLoginRequest, request: HttpServletRequest) =
        login.emailLogin(principals.authenticate(request), body)

    @PutMapping("/me/push-token")
    fun pushToken(@RequestBody body: PushTokenRequest, request: HttpServletRequest): ResponseEntity<Unit> {
        login.updatePushToken(principals.authenticate(request), body)
        return ResponseEntity.noContent().build()
    }
}
