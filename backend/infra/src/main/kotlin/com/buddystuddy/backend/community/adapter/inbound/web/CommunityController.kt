package com.buddystuddy.backend.community.adapter.inbound.web

import com.buddystuddy.backend.auth.application.permission.Permissions
import com.buddystuddy.backend.auth.application.permission.RequirePermission
import com.buddystuddy.backend.common.adapter.inbound.web.optionalPrincipal
import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystuddy.backend.community.application.port.inbound.CommunityUseCase
import com.buddystuddy.backend.community.adapter.inbound.web.dto.CommunityCommentRequest
import com.buddystuddy.backend.community.adapter.inbound.web.dto.ReportQuestionRequest
import com.buddystuddy.backend.community.application.port.inbound.ReportQuestionCommand
import com.buddystuddy.backend.community.application.model.ReportQuestionResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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
@Tag(name = "Public Questions", description = "Public completed-question browsing, reactions, comments, and report APIs.")
class CommunityController(
    private val community: CommunityWebPort,
) {
    @Operation(
        summary = "List public completed questions",
        description = "Returns completed questions that are public both at the user level and at the individual record level. The list includes author profile data, answer, grading feedback, and aggregated view/like/comment counts.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "Public questions returned."))
    @GetMapping("/public/questions")
    fun publicQuestions(
        @Parameter(description = "Optional topic keyword filter.", example = "Swift")
        @RequestParam(required = false) topic: String?,
        @Parameter(description = "Optional DB-backed public question search query. Searches topic, question, answer, feedback, explanation, and author.", example = "Swift")
        @RequestParam(required = false) query: String?,
        @Parameter(description = "Maximum number of items to return. Server clamps this to 1..100.", example = "20")
        @RequestParam(defaultValue = "20") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        authentication: Authentication?,
    ) = community.publicQuestions(query ?: topic, limit, offset, authentication)

    @Operation(summary = "Fetch one public question", description = "Returns a single public completed question with author, answer, feedback, explanation, and current reaction statistics. Viewing may publish a view event for delayed aggregation.")
    @GetMapping("/public/questions/{id}")
    fun publicQuestion(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        authentication: Authentication?,
    ) = community.publicQuestion(id, authentication)

    @Operation(summary = "Like a public question", description = "Adds the authenticated user's like. Like counts may be aggregated asynchronously.")
    @PutMapping("/public/questions/{id}/like")
    @RequirePermission(Permissions.PUBLIC_QUESTION_LIKE)
    fun like(@Parameter(description = "Public question id.", example = "42") @PathVariable id: Long, authentication: Authentication) =
        community.like(id, authentication)

    @Operation(summary = "Unlike a public question", description = "Removes the authenticated user's like. Like counts may be aggregated asynchronously.")
    @DeleteMapping("/public/questions/{id}/like")
    @RequirePermission(Permissions.PUBLIC_QUESTION_LIKE)
    fun unlike(@Parameter(description = "Public question id.", example = "42") @PathVariable id: Long, authentication: Authentication) =
        community.unlike(id, authentication)

    @Operation(summary = "List public question comments", description = "Returns paginated comments for a public question.")
    @GetMapping("/public/questions/{id}/comments")
    fun comments(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @Parameter(description = "Maximum number of comments to return. Server clamps this to 1..100.", example = "30")
        @RequestParam(defaultValue = "30") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
    ) =
        community.comments(id, limit, offset)

    @Operation(summary = "Create a comment", description = "Creates a comment on a public question as the authenticated user. Comment counts may be aggregated asynchronously.")
    @PostMapping("/public/questions/{id}/comments")
    @RequirePermission(Permissions.PUBLIC_QUESTION_COMMENT)
    fun comment(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @RequestBody body: CommunityCommentRequest,
        authentication: Authentication,
    ) =
        community.comment(id, body, authentication)

    @Operation(summary = "Delete a comment", description = "Soft-deletes the authenticated user's own comment on a public question. Comment counts may be aggregated asynchronously.")
    @DeleteMapping("/public/questions/{id}/comments/{commentId}")
    @RequirePermission(Permissions.COMMENT_DELETE)
    fun deleteComment(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @Parameter(description = "Comment id.", example = "7")
        @PathVariable commentId: Long,
        authentication: Authentication,
    ) =
        community.deleteComment(id, commentId, authentication)

    @Operation(summary = "Report a public question", description = "Submits a moderation report for a public question. The backend records the report for review.")
    @PostMapping("/public/questions/{id}/report")
    @RequirePermission(Permissions.PUBLIC_QUESTION_REPORT)
    fun report(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @RequestBody body: ReportQuestionRequest,
        authentication: Authentication,
    ): ReportQuestionResponse =
        community.report(id, body, authentication)
}

interface CommunityWebPort {
    fun publicQuestions(query: String?, limit: Int, offset: Int, authentication: Authentication?): Any
    fun publicQuestion(id: Long, authentication: Authentication?): Any
    fun like(id: Long, authentication: Authentication): Any
    fun unlike(id: Long, authentication: Authentication): Any
    fun comments(id: Long, limit: Int, offset: Int): Any
    fun comment(id: Long, body: CommunityCommentRequest, authentication: Authentication): Any
    fun deleteComment(id: Long, commentId: Long, authentication: Authentication): Any
    fun report(id: Long, body: ReportQuestionRequest, authentication: Authentication): ReportQuestionResponse
}

@Component
class CommunityWebAdapter(
    private val community: CommunityUseCase,
) : CommunityWebPort {
    override fun publicQuestions(query: String?, limit: Int, offset: Int, authentication: Authentication?) =
        community.publicQuestions(authentication.optionalPrincipal(), query, safeLimit(limit, 100), max(0, offset))

    override fun publicQuestion(id: Long, authentication: Authentication?) = community.publicQuestion(authentication.optionalPrincipal(), id)

    override fun like(id: Long, authentication: Authentication) = community.setLike(authentication.principalOrThrow(), id, true)

    override fun unlike(id: Long, authentication: Authentication) = community.setLike(authentication.principalOrThrow(), id, false)

    override fun comments(id: Long, limit: Int, offset: Int) =
        community.comments(id, safeLimit(limit, 100), max(0, offset))

    override fun comment(id: Long, body: CommunityCommentRequest, authentication: Authentication) =
        community.comment(authentication.principalOrThrow(), id, body.body)

    override fun deleteComment(id: Long, commentId: Long, authentication: Authentication) =
        community.deleteComment(authentication.principalOrThrow(), id, commentId)

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
