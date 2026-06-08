package com.buddystuddy.backend.api

import com.buddystuddy.backend.auth.PrincipalService
import com.buddystuddy.backend.dto.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.math.max
import kotlin.math.min

@RestController
class HealthController {
    @GetMapping("/health", "/api/v1/health")
    fun health() = HealthResponse()
}

@RestController
@RequestMapping("/api/v1")
class ApiController(
    private val service: ApiService,
    private val principals: PrincipalService,
) {
    @GetMapping("/openai/models")
    fun models() = listOf(
        OpenAIModelOptionResponse("gpt-5.4", "GPT-5.4"),
        OpenAIModelOptionResponse("gpt-5.2", "GPT-5.2"),
        OpenAIModelOptionResponse("gpt-4.1", "GPT-4.1", supportsTextVerbosity = false, supportsReasoning = false, defaultReasoningEffort = null),
    )

    @PostMapping("/devices/register")
    fun register(@Valid @RequestBody body: DeviceRegisterRequest) = service.register(body)

    @PostMapping("/auth/token")
    fun token(
        @RequestHeader("X-Device-Id") deviceId: String,
        @RequestHeader("X-Client-Secret") clientSecret: String,
    ) = service.token(deviceId, clientSecret)

    @PostMapping("/auth/google")
    fun google(@RequestBody body: GoogleLoginRequest, request: HttpServletRequest) =
        service.googleLogin(principals.authenticate(request), body.idToken)

    @PostMapping("/auth/email/code")
    fun emailCode(@Valid @RequestBody body: EmailVerificationCodeRequest, request: HttpServletRequest): EmailVerificationCodeResponse {
        principals.authenticate(request)
        return service.emailCode(body.email)
    }

    @PostMapping("/auth/email")
    fun email(@Valid @RequestBody body: EmailLoginRequest, request: HttpServletRequest) =
        service.emailLogin(principals.authenticate(request), body)

    @PutMapping("/me/push-token")
    fun pushToken(@RequestBody body: PushTokenRequest, request: HttpServletRequest): ResponseEntity<Unit> {
        service.updatePushToken(principals.authenticate(request), body)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me/profile")
    fun profile(request: HttpServletRequest) = service.profile(principals.authenticate(request))

    @PatchMapping("/me/profile")
    fun updateProfile(@RequestBody body: ProfileUpdateRequest, request: HttpServletRequest) =
        service.updateProfile(principals.authenticate(request), body)

    @DeleteMapping("/me/profile")
    fun withdrawProfile(request: HttpServletRequest): AccessTokenResponse {
        val principal = principals.authenticate(request)
        return service.token(principal.deviceId, "")
    }

    @PutMapping("/me/schedule", "/me/settings")
    fun schedule(@Valid @RequestBody body: ScheduleRequest, request: HttpServletRequest) =
        service.upsertSchedule(principals.authenticate(request), body)

    @GetMapping("/me/settings")
    fun settings(request: HttpServletRequest) = service.settings(principals.authenticate(request))

    @GetMapping("/me/api")
    fun api(request: HttpServletRequest) = service.apiStatus(principals.authenticate(request))

    @PostMapping("/me/api/validate")
    fun validateApi(request: HttpServletRequest): APIValidationResponse {
        val principal = principals.authenticate(request)
        val status = service.apiStatus(principal)
        return APIValidationResponse(status.openaiKeyConfigured, status.openaiKeyConfigured, status.openaiModel)
    }

    @GetMapping("/me/snapshot")
    fun snapshot(@RequestParam(defaultValue = "500") limit: Int, @RequestParam(defaultValue = "0") offset: Int, request: HttpServletRequest) =
        service.snapshot(principals.authenticate(request), safeLimit(limit, 1000), max(0, offset))

    @GetMapping("/me/records")
    fun records(@RequestParam(defaultValue = "100") limit: Int, @RequestParam(defaultValue = "0") offset: Int, request: HttpServletRequest) =
        service.records(principals.authenticate(request), safeLimit(limit, 500), max(0, offset))

    @DeleteMapping("/me/records")
    fun clearRecords(request: HttpServletRequest): ResponseEntity<Unit> = ResponseEntity.noContent().build()

    @GetMapping("/me/records/{id}")
    fun record(@PathVariable id: Long, request: HttpServletRequest) = service.record(principals.authenticate(request), id)

    @PatchMapping("/me/records/{id}/answer")
    fun saveAnswer(@PathVariable id: Long, @RequestBody body: AnswerRequest, request: HttpServletRequest) =
        service.answer(principals.authenticate(request), id, body.answer, grade = false)

    @PostMapping("/me/records/{id}/answer")
    fun grade(@PathVariable id: Long, @RequestBody body: AnswerRequest, request: HttpServletRequest) =
        service.answer(principals.authenticate(request), id, body.answer, grade = true)

    @PostMapping("/me/records/{id}/skip")
    fun skip(@PathVariable id: Long, request: HttpServletRequest) = service.skip(principals.authenticate(request), id)

    @DeleteMapping("/me/records/{id}")
    fun delete(@PathVariable id: Long, request: HttpServletRequest): ResponseEntity<Unit> {
        service.delete(principals.authenticate(request), id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/me/records/{id}/publicity")
    fun publicity(@PathVariable id: Long, @RequestBody body: RecordPublicityRequest, request: HttpServletRequest) =
        service.publicity(principals.authenticate(request), id, body.isPublic)

    @GetMapping("/me/stats")
    fun stats(@RequestParam(defaultValue = "8") limit: Int, @RequestParam(defaultValue = "0") offset: Int, request: HttpServletRequest) =
        service.stats(principals.authenticate(request), safeLimit(limit, 100), max(0, offset))

    @PostMapping("/me/questions")
    fun createQuestion(@RequestBody body: CreateQuestionRequest, request: HttpServletRequest) =
        service.createQuestion(principals.authenticate(request), body.topic)

    @GetMapping("/public/questions")
    fun publicQuestions(
        @RequestParam(required = false) topic: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        request: HttpServletRequest,
    ) = service.publicQuestions(principals.optional(request), topic, safeLimit(limit, 100), max(0, offset))

    @GetMapping("/public/questions/{id}")
    fun publicQuestion(@PathVariable id: Long, request: HttpServletRequest) = service.publicQuestion(principals.optional(request), id)

    @PutMapping("/public/questions/{id}/like")
    fun like(@PathVariable id: Long, request: HttpServletRequest) = service.setLike(principals.authenticate(request), id, true)

    @DeleteMapping("/public/questions/{id}/like")
    fun unlike(@PathVariable id: Long, request: HttpServletRequest) = service.setLike(principals.authenticate(request), id, false)

    @GetMapping("/public/questions/{id}/comments")
    fun comments(@PathVariable id: Long, @RequestParam(defaultValue = "30") limit: Int, @RequestParam(defaultValue = "0") offset: Int) =
        service.comments(id, safeLimit(limit, 100), max(0, offset))

    @PostMapping("/public/questions/{id}/comments")
    fun comment(@PathVariable id: Long, @RequestBody body: CommunityCommentRequest, request: HttpServletRequest) =
        service.comment(principals.authenticate(request), id, body.body)

    @PostMapping("/public/questions/{id}/report")
    fun report(@PathVariable id: Long, @RequestBody body: ReportQuestionRequest, request: HttpServletRequest): ReportQuestionResponse {
        service.report(principals.authenticate(request), id, body)
        return ReportQuestionResponse()
    }

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}
