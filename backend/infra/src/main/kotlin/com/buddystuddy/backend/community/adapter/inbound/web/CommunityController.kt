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
    fun getPublicQuestions(
        @Parameter(description = "Optional topic keyword filter.", example = "Swift")
        @RequestParam(required = false) topic: String?,
        @Parameter(description = "Optional DB-backed public question search query. Searches topic, question, answer, feedback, explanation, and author.", example = "Swift")
        @RequestParam(required = false) query: String?,
        @Parameter(description = "Maximum number of items to return. Server clamps this to 1..100.", example = "20")
        @RequestParam(defaultValue = "20") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        authentication: Authentication?,
    ) = community.getPublicQuestions(query ?: topic, limit, offset, authentication)

    @Operation(summary = "Fetch one public question", description = "Returns a single public completed question with author, answer, feedback, explanation, and current reaction statistics. Viewing may publish a view event for delayed aggregation.")
    @GetMapping("/public/questions/{id}")
    fun getPublicQuestion(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        authentication: Authentication?,
    ) = community.getPublicQuestion(id, authentication)

    @Operation(summary = "Like a public question", description = "Adds the authenticated user's like. Like counts may be aggregated asynchronously.")
    @PutMapping("/public/questions/{id}/like")
    @RequirePermission(Permissions.PUBLIC_QUESTION_LIKE)
    fun likePublicQuestion(@Parameter(description = "Public question id.", example = "42") @PathVariable id: Long, authentication: Authentication) =
        community.likePublicQuestion(id, authentication)

    @Operation(summary = "Unlike a public question", description = "Removes the authenticated user's like. Like counts may be aggregated asynchronously.")
    @DeleteMapping("/public/questions/{id}/like")
    @RequirePermission(Permissions.PUBLIC_QUESTION_LIKE)
    fun unlikePublicQuestion(@Parameter(description = "Public question id.", example = "42") @PathVariable id: Long, authentication: Authentication) =
        community.unlikePublicQuestion(id, authentication)

    @Operation(summary = "List public question comments", description = "Returns paginated comments for a public question.")
    @GetMapping("/public/questions/{id}/comments")
    fun getComments(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @Parameter(description = "Maximum number of comments to return. Server clamps this to 1..100.", example = "30")
        @RequestParam(defaultValue = "30") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
    ) =
        community.getComments(id, limit, offset)

    @Operation(summary = "Create a comment", description = "Creates a comment on a public question as the authenticated user. Comment counts may be aggregated asynchronously.")
    @PostMapping("/public/questions/{id}/comments")
    @RequirePermission(Permissions.PUBLIC_QUESTION_COMMENT)
    fun createComment(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @RequestBody body: CommunityCommentRequest,
        authentication: Authentication,
    ) =
        community.createComment(id, body, authentication)

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
    fun reportQuestion(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @RequestBody body: ReportQuestionRequest,
        authentication: Authentication,
    ): ReportQuestionResponse =
        community.reportQuestion(id, body, authentication)
}

@RestController
@RequestMapping("/api/v2")
@Tag(name = "Public Questions V2", description = "Full-text public completed-question search APIs.")
class CommunitySearchV2Controller(
    private val community: CommunityWebPort,
) {
    @Operation(
        summary = "Search public completed questions v2",
        description = "Searches the question_search read model backed by PostgreSQL full-text search. V1 public question listing remains unchanged.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "Public questions returned from search v2."))
    @GetMapping("/public/questions/search")
    fun getPublicQuestionsV2(
        @Parameter(description = "Full-text search query.", example = "Swift state management")
        @RequestParam(required = false) query: String?,
        @Parameter(description = "Maximum number of items to return. Server clamps this to 1..100.", example = "20")
        @RequestParam(defaultValue = "20") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        authentication: Authentication?,
    ) = community.getPublicQuestionsV2(query, limit, offset, authentication)
}

interface CommunityWebPort {
    fun getPublicQuestions(query: String?, limit: Int, offset: Int, authentication: Authentication?): Any
    fun getPublicQuestionsV2(query: String?, limit: Int, offset: Int, authentication: Authentication?): Any
    fun getPublicQuestion(id: Long, authentication: Authentication?): Any
    fun likePublicQuestion(id: Long, authentication: Authentication): Any
    fun unlikePublicQuestion(id: Long, authentication: Authentication): Any
    fun getComments(id: Long, limit: Int, offset: Int): Any
    fun createComment(id: Long, body: CommunityCommentRequest, authentication: Authentication): Any
    fun deleteComment(id: Long, commentId: Long, authentication: Authentication): Any
    fun reportQuestion(id: Long, body: ReportQuestionRequest, authentication: Authentication): ReportQuestionResponse
}

@Component
class CommunityWebAdapter(
    private val community: CommunityUseCase,
) : CommunityWebPort {
    override fun getPublicQuestions(query: String?, limit: Int, offset: Int, authentication: Authentication?) =
        community.getPublicQuestions(authentication.optionalPrincipal(), query, safeLimit(limit, 100), max(0, offset))

    override fun getPublicQuestionsV2(query: String?, limit: Int, offset: Int, authentication: Authentication?) =
        community.getPublicQuestionsV2(authentication.optionalPrincipal(), query, safeLimit(limit, 100), max(0, offset))

    override fun getPublicQuestion(id: Long, authentication: Authentication?) = community.getPublicQuestion(authentication.optionalPrincipal(), id)

    override fun likePublicQuestion(id: Long, authentication: Authentication) = community.setLike(authentication.principalOrThrow(), id, true)

    override fun unlikePublicQuestion(id: Long, authentication: Authentication) = community.setLike(authentication.principalOrThrow(), id, false)

    override fun getComments(id: Long, limit: Int, offset: Int) =
        community.getComments(id, safeLimit(limit, 100), max(0, offset))

    override fun createComment(id: Long, body: CommunityCommentRequest, authentication: Authentication) =
        community.createComment(authentication.principalOrThrow(), id, body.body)

    override fun deleteComment(id: Long, commentId: Long, authentication: Authentication) =
        community.deleteComment(authentication.principalOrThrow(), id, commentId)

    override fun reportQuestion(id: Long, body: ReportQuestionRequest, authentication: Authentication): ReportQuestionResponse {
        community.reportQuestion(authentication.principalOrThrow(), id, body.toCommand())
        return ReportQuestionResponse()
    }

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}

private fun ReportQuestionRequest.toCommand() = ReportQuestionCommand(
    reason = reason,
    message = message,
)
