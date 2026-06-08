package com.buddystuddy.backend.community.adapter.inbound.web

import com.buddystuddy.backend.auth.PrincipalService
import com.buddystuddy.backend.community.application.port.inbound.CommunityUseCase
import com.buddystuddy.backend.dto.CommunityCommentRequest
import com.buddystuddy.backend.dto.ReportQuestionRequest
import com.buddystuddy.backend.dto.ReportQuestionResponse
import jakarta.servlet.http.HttpServletRequest
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
    private val community: CommunityUseCase,
    private val principals: PrincipalService,
) {
    @GetMapping("/public/questions")
    fun publicQuestions(
        @RequestParam(required = false) topic: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        request: HttpServletRequest,
    ) = community.publicQuestions(principals.optional(request), topic, safeLimit(limit, 100), max(0, offset))

    @GetMapping("/public/questions/{id}")
    fun publicQuestion(@PathVariable id: Long, request: HttpServletRequest) = community.publicQuestion(principals.optional(request), id)

    @PutMapping("/public/questions/{id}/like")
    fun like(@PathVariable id: Long, request: HttpServletRequest) = community.setLike(principals.authenticate(request), id, true)

    @DeleteMapping("/public/questions/{id}/like")
    fun unlike(@PathVariable id: Long, request: HttpServletRequest) = community.setLike(principals.authenticate(request), id, false)

    @GetMapping("/public/questions/{id}/comments")
    fun comments(@PathVariable id: Long, @RequestParam(defaultValue = "30") limit: Int, @RequestParam(defaultValue = "0") offset: Int) =
        community.comments(id, safeLimit(limit, 100), max(0, offset))

    @PostMapping("/public/questions/{id}/comments")
    fun comment(@PathVariable id: Long, @RequestBody body: CommunityCommentRequest, request: HttpServletRequest) =
        community.comment(principals.authenticate(request), id, body.body)

    @PostMapping("/public/questions/{id}/report")
    fun report(@PathVariable id: Long, @RequestBody body: ReportQuestionRequest, request: HttpServletRequest): ReportQuestionResponse {
        community.report(principals.authenticate(request), id, body)
        return ReportQuestionResponse()
    }

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}
