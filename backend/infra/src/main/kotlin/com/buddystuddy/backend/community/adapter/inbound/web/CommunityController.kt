package com.buddystuddy.backend.community.adapter.inbound.web

import com.buddystuddy.backend.auth.PrincipalResolver
import com.buddystuddy.backend.community.application.port.inbound.CommunityUseCase
import com.buddystuddy.backend.community.adapter.inbound.web.dto.CommunityCommentRequest
import com.buddystuddy.backend.community.adapter.inbound.web.dto.ReportQuestionRequest
import com.buddystuddy.backend.community.application.port.inbound.ReportQuestionCommand
import com.buddystuddy.backend.community.application.model.ReportQuestionResponse
import jakarta.servlet.http.HttpServletRequest
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
        request: HttpServletRequest,
    ) = community.publicQuestions(topic, limit, offset, request)

    @GetMapping("/public/questions/{id}")
    fun publicQuestion(@PathVariable id: Long, request: HttpServletRequest) = community.publicQuestion(id, request)

    @PutMapping("/public/questions/{id}/like")
    fun like(@PathVariable id: Long, request: HttpServletRequest) = community.like(id, request)

    @DeleteMapping("/public/questions/{id}/like")
    fun unlike(@PathVariable id: Long, request: HttpServletRequest) = community.unlike(id, request)

    @GetMapping("/public/questions/{id}/comments")
    fun comments(@PathVariable id: Long, @RequestParam(defaultValue = "30") limit: Int, @RequestParam(defaultValue = "0") offset: Int) =
        community.comments(id, limit, offset)

    @PostMapping("/public/questions/{id}/comments")
    fun comment(@PathVariable id: Long, @RequestBody body: CommunityCommentRequest, request: HttpServletRequest) =
        community.comment(id, body, request)

    @PostMapping("/public/questions/{id}/report")
    fun report(@PathVariable id: Long, @RequestBody body: ReportQuestionRequest, request: HttpServletRequest): ReportQuestionResponse =
        community.report(id, body, request)
}

interface CommunityWebPort {
    fun publicQuestions(topic: String?, limit: Int, offset: Int, request: HttpServletRequest): Any
    fun publicQuestion(id: Long, request: HttpServletRequest): Any
    fun like(id: Long, request: HttpServletRequest): Any
    fun unlike(id: Long, request: HttpServletRequest): Any
    fun comments(id: Long, limit: Int, offset: Int): Any
    fun comment(id: Long, body: CommunityCommentRequest, request: HttpServletRequest): Any
    fun report(id: Long, body: ReportQuestionRequest, request: HttpServletRequest): ReportQuestionResponse
}

@Component
class CommunityWebAdapter(
    private val community: CommunityUseCase,
    private val principals: PrincipalResolver,
) : CommunityWebPort {
    override fun publicQuestions(topic: String?, limit: Int, offset: Int, request: HttpServletRequest) =
        community.publicQuestions(principals.optional(request), topic, safeLimit(limit, 100), max(0, offset))

    override fun publicQuestion(id: Long, request: HttpServletRequest) = community.publicQuestion(principals.optional(request), id)

    override fun like(id: Long, request: HttpServletRequest) = community.setLike(principals.authenticate(request), id, true)

    override fun unlike(id: Long, request: HttpServletRequest) = community.setLike(principals.authenticate(request), id, false)

    override fun comments(id: Long, limit: Int, offset: Int) =
        community.comments(id, safeLimit(limit, 100), max(0, offset))

    override fun comment(id: Long, body: CommunityCommentRequest, request: HttpServletRequest) =
        community.comment(principals.authenticate(request), id, body.body)

    override fun report(id: Long, body: ReportQuestionRequest, request: HttpServletRequest): ReportQuestionResponse {
        community.report(principals.authenticate(request), id, body.toCommand())
        return ReportQuestionResponse()
    }

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}

private fun ReportQuestionRequest.toCommand() = ReportQuestionCommand(
    reason = reason,
    message = message,
)
