package com.buddystuddy.backend.community.adapter.inbound.web

import com.buddystuddy.backend.common.adapter.inbound.web.optionalPrincipal
import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystuddy.backend.community.application.port.inbound.CommunityUseCase
import com.buddystuddy.backend.community.adapter.inbound.web.dto.CommunityCommentRequest
import com.buddystuddy.backend.community.adapter.inbound.web.dto.ReportQuestionRequest
import com.buddystuddy.backend.community.application.port.inbound.ReportQuestionCommand
import com.buddystuddy.backend.community.application.model.ReportQuestionResponse
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.math.max
import kotlin.math.min

@RestController
@RequestMapping("/api/v1")
class CommunityController(
    private val community: CommunityWebPort,
) {
    @GetMapping("/public/questions")
    fun publicQuestions(
        @RequestParam(required = false) topic: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        authentication: Authentication?,
    ) = community.publicQuestions(topic, limit, offset, authentication)

    @GetMapping("/public/questions/{id}")
    fun publicQuestion(@PathVariable id: Long, authentication: Authentication?) = community.publicQuestion(id, authentication)

    @PutMapping("/public/questions/{id}/like")
    fun like(@PathVariable id: Long, authentication: Authentication) = community.like(id, authentication)

    @DeleteMapping("/public/questions/{id}/like")
    fun unlike(@PathVariable id: Long, authentication: Authentication) = community.unlike(id, authentication)

    @GetMapping("/public/questions/{id}/comments")
    fun comments(@PathVariable id: Long, @RequestParam(defaultValue = "30") limit: Int, @RequestParam(defaultValue = "0") offset: Int) =
        community.comments(id, limit, offset)

    @PostMapping("/public/questions/{id}/comments")
    fun comment(@PathVariable id: Long, @RequestBody body: CommunityCommentRequest, authentication: Authentication) =
        community.comment(id, body, authentication)

    @PostMapping("/public/questions/{id}/report")
    fun report(@PathVariable id: Long, @RequestBody body: ReportQuestionRequest, authentication: Authentication): ReportQuestionResponse =
        community.report(id, body, authentication)
}

interface CommunityWebPort {
    fun publicQuestions(topic: String?, limit: Int, offset: Int, authentication: Authentication?): Any
    fun publicQuestion(id: Long, authentication: Authentication?): Any
    fun like(id: Long, authentication: Authentication): Any
    fun unlike(id: Long, authentication: Authentication): Any
    fun comments(id: Long, limit: Int, offset: Int): Any
    fun comment(id: Long, body: CommunityCommentRequest, authentication: Authentication): Any
    fun report(id: Long, body: ReportQuestionRequest, authentication: Authentication): ReportQuestionResponse
}

@Component
class CommunityWebAdapter(
    private val community: CommunityUseCase,
) : CommunityWebPort {
    override fun publicQuestions(topic: String?, limit: Int, offset: Int, authentication: Authentication?) =
        community.publicQuestions(authentication.optionalPrincipal(), topic, safeLimit(limit, 100), max(0, offset))

    override fun publicQuestion(id: Long, authentication: Authentication?) = community.publicQuestion(authentication.optionalPrincipal(), id)

    override fun like(id: Long, authentication: Authentication) = community.setLike(authentication.principalOrThrow(), id, true)

    override fun unlike(id: Long, authentication: Authentication) = community.setLike(authentication.principalOrThrow(), id, false)

    override fun comments(id: Long, limit: Int, offset: Int) =
        community.comments(id, safeLimit(limit, 100), max(0, offset))

    override fun comment(id: Long, body: CommunityCommentRequest, authentication: Authentication) =
        community.comment(authentication.principalOrThrow(), id, body.body)

    override fun report(id: Long, body: ReportQuestionRequest, authentication: Authentication): ReportQuestionResponse {
        community.report(authentication.principalOrThrow(), id, body.toCommand())
        return ReportQuestionResponse()
    }

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}

private fun ReportQuestionRequest.toCommand() = ReportQuestionCommand(
    reason = reason,
    message = message,
)
