package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.port.inbound.CreateCustomQuestionUseCase
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CustomQuestionRequest(
    @field:NotBlank @field:Size(max = 4_000) var question: String = "",
    @field:NotBlank @field:Size(max = 12_000) var answer: String = "",
    @field:NotBlank var language: String = "",
)

interface CustomQuestionWebPort {
    suspend fun create(studyId: Long, key: String, body: CustomQuestionRequest, authentication: Authentication): StudyRecordResponse
}

@Component
class CustomQuestionWebAdapter(private val customQuestions: CreateCustomQuestionUseCase) : CustomQuestionWebPort {
    override suspend fun create(studyId: Long, key: String, body: CustomQuestionRequest, authentication: Authentication) =
        customQuestions.create(authentication.principalOrThrow(), studyId, key, body.question, body.answer, body.language)
}

@RestController
@RequestMapping("/api/v1/studies/{studyId}/custom-questions")
class CustomQuestionController(private val customQuestions: CustomQuestionWebPort) {
    @PostMapping
    @RequirePermission(Permissions.RECORD_UPDATE)
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable studyId: Long,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: CustomQuestionRequest,
        authentication: Authentication,
    ) = customQuestions.create(studyId, key, body, authentication)
}
