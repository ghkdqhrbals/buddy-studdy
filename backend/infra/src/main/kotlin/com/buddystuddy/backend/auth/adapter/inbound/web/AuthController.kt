package com.buddystuddy.backend.auth.adapter.inbound.web

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
import com.buddystuddy.backend.common.adapter.inbound.web.optionalPrincipal
import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.stereotype.Component
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Auth", description = "Device registration, access-token bootstrap, Google login, and email login APIs.")
class AuthController(
    private val auth: AuthWebPort,
) {
    @Operation(summary = "Register an iOS device", description = "Creates or refreshes an anonymous device session and returns device credentials plus an access token. This is the only app API call that cannot send X-Device-Id and X-Client-Secret because they do not exist yet.")
    @PostMapping("/devices/register")
    fun register(@Valid @RequestBody body: DeviceRegisterRequest) = auth.register(body)

    @Operation(summary = "Issue an access token from device credentials", description = "Returns a fresh access token for a known device. iOS should send X-Device-Id and X-Client-Secret headers.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Access token issued."),
        ApiResponse(responseCode = "401", description = "Invalid device credentials."),
    )
    @PostMapping("/auth/token")
    fun token(
        @Parameter(description = "Registered device id.", required = true)
        @RequestHeader("X-Device-Id") deviceId: String,
        @Parameter(description = "Device client secret returned only at registration.", required = true)
        @RequestHeader("X-Client-Secret") clientSecret: String,
    ) = auth.token(deviceId, clientSecret)

    @Operation(summary = "Sign in with Google", description = "Links the current device session to a Google account and returns a user profile plus a 90-day access token. Send device credentials on every login request; a stale bearer token is ignored on this public endpoint.")
    @PostMapping("/auth/google")
    fun google(
        @RequestBody body: GoogleLoginRequest,
        authentication: Authentication?,
        @RequestHeader("X-Device-Id", required = false) deviceId: String?,
        @RequestHeader("X-Client-Secret", required = false) clientSecret: String?,
    ) = auth.google(body, authentication, deviceId, clientSecret)

    @Operation(summary = "Request email verification code", description = "Sends a short-lived email verification code for sign-up or email login. Verification sessions are held in Redis with a short TTL.")
    @PostMapping("/auth/email/code")
    fun emailCode(
        @Valid @RequestBody body: EmailVerificationCodeRequest,
        authentication: Authentication?,
        @RequestHeader("X-Device-Id", required = false) deviceId: String?,
        @RequestHeader("X-Client-Secret", required = false) clientSecret: String?,
    ): EmailVerificationCodeResponse = auth.emailCode(body, authentication, deviceId, clientSecret)

    @Operation(summary = "Sign in with email", description = "Signs in with email/password and optional verification code, then links the authenticated account to the current device session.")
    @PostMapping("/auth/email")
    fun email(
        @Valid @RequestBody body: EmailLoginRequest,
        authentication: Authentication?,
        @RequestHeader("X-Device-Id", required = false) deviceId: String?,
        @RequestHeader("X-Client-Secret", required = false) clientSecret: String?,
    ) = auth.email(body, authentication, deviceId, clientSecret)

    @Operation(summary = "Update push token", description = "Stores the latest APNs token and environment for the authenticated device.")
    @PutMapping("/me/push-token")
    fun pushToken(@RequestBody body: PushTokenRequest, authentication: Authentication): ResponseEntity<Unit> =
        auth.pushToken(body, authentication)
}

interface AuthWebPort {
    fun register(body: DeviceRegisterRequest): Any
    fun token(deviceId: String, clientSecret: String): Any
    fun google(body: GoogleLoginRequest, authentication: Authentication?, deviceId: String?, clientSecret: String?): Any
    fun emailCode(body: EmailVerificationCodeRequest, authentication: Authentication?, deviceId: String?, clientSecret: String?): EmailVerificationCodeResponse
    fun email(body: EmailLoginRequest, authentication: Authentication?, deviceId: String?, clientSecret: String?): Any
    fun pushToken(body: PushTokenRequest, authentication: Authentication): ResponseEntity<Unit>
}

@Component
class AuthWebAdapter(
    private val registerDevice: RegisterDeviceUseCase,
    private val issueDeviceToken: IssueDeviceTokenUseCase,
    private val login: LoginUseCase,
    private val updatePushToken: UpdatePushTokenUseCase,
) : AuthWebPort {
    override fun register(body: DeviceRegisterRequest) = registerDevice.register(body.toCommand())

    override fun token(deviceId: String, clientSecret: String) = issueDeviceToken.token(deviceId, clientSecret)

    override fun google(body: GoogleLoginRequest, authentication: Authentication?, deviceId: String?, clientSecret: String?) =
        login.googleLogin(loginPrincipal(authentication, deviceId, clientSecret), body.idToken)

    override fun emailCode(body: EmailVerificationCodeRequest, authentication: Authentication?, deviceId: String?, clientSecret: String?): EmailVerificationCodeResponse {
        loginPrincipal(authentication, deviceId, clientSecret)
        return login.emailCode(body.email)
    }

    override fun email(body: EmailLoginRequest, authentication: Authentication?, deviceId: String?, clientSecret: String?) =
        login.emailLogin(loginPrincipal(authentication, deviceId, clientSecret), body.toCommand())

    override fun pushToken(body: PushTokenRequest, authentication: Authentication): ResponseEntity<Unit> {
        updatePushToken.updatePushToken(authentication.principalOrThrow(), body.toCommand())
        return ResponseEntity.noContent().build()
    }

    private fun loginPrincipal(authentication: Authentication?, deviceId: String?, clientSecret: String?): Principal =
        authentication.optionalPrincipal()
            ?: run {
                if (deviceId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
                    throw ApiException(
                        HttpStatus.UNAUTHORIZED,
                        ApiErrorCode.AUTH_DEVICE_CREDENTIALS_REQUIRED,
                        "Device credentials are required.",
                    )
                }
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
