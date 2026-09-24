package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.study.application.model.QuestionGenerationAcceptedResponse
import com.buddystudy.backend.study.application.port.inbound.FollowUpQuestionUseCase
import com.buddystudy.backend.study.application.model.QuestionThreadResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

interface FollowUpQuestionWebPort {
    suspend fun request(recordId: Long, idempotencyKey: String, authentication: Authentication): QuestionGenerationAcceptedResponse
    suspend fun thread(recordId: Long, language: String, view: String, authentication: Authentication): QuestionThreadResponse
}

@Component
class FollowUpQuestionWebAdapter(private val followUps: FollowUpQuestionUseCase) : FollowUpQuestionWebPort {
    override suspend fun request(recordId: Long, idempotencyKey: String, authentication: Authentication) =
        followUps.request(authentication.principalOrThrow(), recordId, idempotencyKey)

    override suspend fun thread(recordId: Long, language: String, view: String, authentication: Authentication) =
        followUps.thread(authentication.principalOrThrow(), recordId, language, view)
}

@RestController
@RequestMapping("/api/v1/records")
class FollowUpQuestionController(private val followUps: FollowUpQuestionWebPort) {
    @PostMapping("/{recordId}/follow-ups")
    @RequirePermission(Permissions.QUESTION_FOLLOW_UP)
    suspend fun request(
        @PathVariable recordId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        authentication: Authentication,
    ): ResponseEntity<QuestionGenerationAcceptedResponse> =
        ResponseEntity.accepted().body(followUps.request(recordId, idempotencyKey, authentication))

    @GetMapping("/{recordId}/thread")
    suspend fun thread(
        @PathVariable recordId: Long,
        @RequestParam(required = false) tl: String?,
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication,
    ) = followUps.thread(recordId, resolveTargetLanguage(tl, language), view, authentication)
}
