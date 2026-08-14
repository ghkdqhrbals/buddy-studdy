package com.buddystudy.backend.community.adapter.inbound.web

import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.adapter.inbound.web.optionalPrincipal
import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.community.application.port.inbound.CommunityUseCase
import com.buddystudy.backend.community.adapter.inbound.web.dto.CommunityCommentRequest
import com.buddystudy.backend.community.adapter.inbound.web.dto.ReportQuestionRequest
import com.buddystudy.backend.community.adapter.inbound.web.dto.SubmitFeedbackRequest
import com.buddystudy.backend.community.application.port.inbound.ReportQuestionCommand
import com.buddystudy.backend.community.application.port.inbound.SubmitFeedbackCommand
import com.buddystudy.backend.community.application.model.ReportQuestionResponse
import com.buddystudy.backend.community.application.model.FeedbackResponse
import com.buddystudy.backend.community.application.model.UserBlockResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.http.HttpStatus
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
    suspend fun getPublicQuestions(
        @Parameter(description = "Optional topic keyword filter.", example = "Swift")
        @RequestParam(required = false) topic: String?,
        @Parameter(description = "Optional DB-backed public question search query. Searches topic, question, answer, feedback, explanation, and author.", example = "Swift")
        @RequestParam(required = false) query: String?,
        @Parameter(description = "Maximum number of items to return. Server clamps this to 1..100.", example = "20")
        @RequestParam(defaultValue = "20") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        @Parameter(description = "Target language for translated content. Supports ko, en, and ja.", example = "ko")
        @RequestParam(required = false) tl: String?,
        @Parameter(description = "Deprecated target-language alias kept for older clients.", example = "ko", deprecated = true)
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication?,
    ) = community.getPublicQuestions(query ?: topic, targetLanguage(tl, language), view, limit, offset, authentication)

    @Operation(summary = "Fetch one public question", description = "Returns a single public completed question with author, answer, feedback, explanation, and current reaction statistics. Viewing may publish a view event for delayed aggregation.")
    @GetMapping("/public/questions/{id}")
    suspend fun getPublicQuestion(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @Parameter(description = "Target language for translated content. Supports ko, en, and ja.", example = "ko")
        @RequestParam(required = false) tl: String?,
        @Parameter(description = "Deprecated target-language alias kept for older clients.", example = "ko", deprecated = true)
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication?,
    ) = community.getPublicQuestion(id, targetLanguage(tl, language), view, authentication)

    @Operation(summary = "Like a public question", description = "Adds the authenticated user's like. Like counts may be aggregated asynchronously.")
    @PutMapping("/public/questions/{id}/like")
    @RequirePermission(Permissions.PUBLIC_QUESTION_LIKE)
    suspend fun likePublicQuestion(@Parameter(description = "Public question id.", example = "42") @PathVariable id: Long, authentication: Authentication): Any =
        community.likePublicQuestion(id, authentication)

    @Operation(summary = "Unlike a public question", description = "Removes the authenticated user's like. Like counts may be aggregated asynchronously.")
    @DeleteMapping("/public/questions/{id}/like")
    @RequirePermission(Permissions.PUBLIC_QUESTION_LIKE)
    suspend fun unlikePublicQuestion(@Parameter(description = "Public question id.", example = "42") @PathVariable id: Long, authentication: Authentication): Any =
        community.unlikePublicQuestion(id, authentication)

    @Operation(summary = "List public question comments", description = "Returns paginated comments for a public question.")
    @GetMapping("/public/questions/{id}/comments")
    suspend fun getComments(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @Parameter(description = "Maximum number of comments to return. Server clamps this to 1..100.", example = "30")
        @RequestParam(defaultValue = "30") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) tl: String?,
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication?,
    ) =
        community.getComments(id, targetLanguage(tl, language), view, limit, offset, authentication)

    @Operation(summary = "Create a comment", description = "Creates a comment on a public question as the authenticated user. Comment counts may be aggregated asynchronously.")
    @PostMapping("/public/questions/{id}/comments")
    @RequirePermission(Permissions.PUBLIC_QUESTION_COMMENT)
    suspend fun createComment(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @Valid @RequestBody body: CommunityCommentRequest,
        authentication: Authentication,
    ): Any = community.createComment(id, body, authentication)

    @Operation(summary = "Delete a comment", description = "Soft-deletes the authenticated user's own comment on a public question. Comment counts may be aggregated asynchronously.")
    @DeleteMapping("/public/questions/{id}/comments/{commentId}")
    @RequirePermission(Permissions.COMMENT_DELETE)
    suspend fun deleteComment(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @Parameter(description = "Comment id.", example = "7")
        @PathVariable commentId: Long,
        authentication: Authentication,
    ): Any = community.deleteComment(id, commentId, authentication)

    @Operation(summary = "Report a public question", description = "Submits a moderation report for a public question. The backend records the report for review.")
    @PostMapping("/public/questions/{id}/report")
    @RequirePermission(Permissions.PUBLIC_QUESTION_REPORT)
    suspend fun reportQuestion(
        @Parameter(description = "Public question id.", example = "42")
        @PathVariable id: Long,
        @Valid @RequestBody body: ReportQuestionRequest,
        authentication: Authentication,
    ): ReportQuestionResponse = community.reportQuestion(id, body, authentication)

    @Operation(summary = "Block a community user", description = "Hides the selected user's public questions and comments from the authenticated user.")
    @PutMapping("/community/users/{userId}/block")
    @RequirePermission(Permissions.PUBLIC_USER_BLOCK)
    suspend fun blockUser(
        @Parameter(description = "User id to block.", example = "42")
        @PathVariable userId: Long,
        authentication: Authentication,
    ): UserBlockResponse = community.setUserBlocked(userId, true, authentication)

    @Operation(summary = "Unblock a community user", description = "Makes the selected user's public questions and comments visible again.")
    @DeleteMapping("/community/users/{userId}/block")
    @RequirePermission(Permissions.PUBLIC_USER_BLOCK)
    suspend fun unblockUser(
        @Parameter(description = "User id to unblock.", example = "42")
        @PathVariable userId: Long,
        authentication: Authentication,
    ): UserBlockResponse = community.setUserBlocked(userId, false, authentication)

    @Operation(summary = "Submit app feedback", description = "Stores product feedback from either a member or a registered device.")
    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun submitFeedback(
        @Valid @RequestBody body: SubmitFeedbackRequest,
        @RequestHeader("X-Device-Id", required = false) deviceId: String?,
        authentication: Authentication?,
    ): FeedbackResponse = community.submitFeedback(body, deviceId, authentication)
}

@RestController
@RequestMapping("/api/v2")
@Tag(name = "Public Questions V2", description = "Full-text public completed-question search APIs.")
class CommunitySearchV2Controller(
    private val community: CommunityWebPort,
) {
    @Operation(
        summary = "Search public completed questions v2",
        description = "Searches public completed questions directly from the canonical questions table.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "Public questions returned from search v2."))
    @GetMapping("/public/questions/search")
    suspend fun getPublicQuestionsV2(
        @Parameter(description = "Full-text search query.", example = "Swift state management")
        @RequestParam(required = false) query: String?,
        @Parameter(description = "Maximum number of items to return. Server clamps this to 1..100.", example = "20")
        @RequestParam(defaultValue = "20") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        @Parameter(description = "Target language for translated search results. Supports ko, en, and ja.", example = "ko")
        @RequestParam(required = false) tl: String?,
        @Parameter(description = "Deprecated target-language alias kept for older clients.", example = "ko", deprecated = true)
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication?,
    ) = community.getPublicQuestionsV2(query, targetLanguage(tl, language), view, limit, offset, authentication)
}

internal fun targetLanguage(tl: String?, legacyLanguage: String?): String =
    tl?.trim()?.takeIf(String::isNotEmpty)
        ?: legacyLanguage?.trim()?.takeIf(String::isNotEmpty)
        ?: "ko"

interface CommunityWebPort {
    suspend fun getPublicQuestions(query: String?, language: String, view: String, limit: Int, offset: Int, authentication: Authentication?): Any
    suspend fun getPublicQuestionsV2(query: String?, language: String, view: String, limit: Int, offset: Int, authentication: Authentication?): Any
    suspend fun getPublicQuestion(id: Long, language: String, view: String, authentication: Authentication?): Any
    suspend fun likePublicQuestion(id: Long, authentication: Authentication): Any
    suspend fun unlikePublicQuestion(id: Long, authentication: Authentication): Any
    suspend fun getComments(
        id: Long,
        language: String,
        view: String,
        limit: Int,
        offset: Int,
        authentication: Authentication?,
    ): Any
    suspend fun createComment(id: Long, body: CommunityCommentRequest, authentication: Authentication): Any
    suspend fun deleteComment(id: Long, commentId: Long, authentication: Authentication): Any
    suspend fun reportQuestion(id: Long, body: ReportQuestionRequest, authentication: Authentication): ReportQuestionResponse
    suspend fun setUserBlocked(userId: Long, blocked: Boolean, authentication: Authentication): UserBlockResponse
    suspend fun submitFeedback(body: SubmitFeedbackRequest, deviceId: String?, authentication: Authentication?): FeedbackResponse
}

@Component
class CommunityWebAdapter(
    private val community: CommunityUseCase,
) : CommunityWebPort {
    override suspend fun getPublicQuestions(query: String?, language: String, view: String, limit: Int, offset: Int, authentication: Authentication?) =
        community.getPublicQuestions(authentication.optionalPrincipal(), query, language, view, safeLimit(limit, 100), max(0, offset))

    override suspend fun getPublicQuestionsV2(query: String?, language: String, view: String, limit: Int, offset: Int, authentication: Authentication?) =
        community.getPublicQuestionsV2(authentication.optionalPrincipal(), query, language, view, safeLimit(limit, 100), max(0, offset))

    override suspend fun getPublicQuestion(id: Long, language: String, view: String, authentication: Authentication?) =
        community.getPublicQuestion(authentication.optionalPrincipal(), id, language, view)

    override suspend fun likePublicQuestion(id: Long, authentication: Authentication) = community.setLike(authentication.principalOrThrow(), id, true)

    override suspend fun unlikePublicQuestion(id: Long, authentication: Authentication) = community.setLike(authentication.principalOrThrow(), id, false)

    override suspend fun getComments(
        id: Long,
        language: String,
        view: String,
        limit: Int,
        offset: Int,
        authentication: Authentication?,
    ) = community.getComments(
        id,
        language,
        view,
        safeLimit(limit, 100),
        max(0, offset),
        authentication.optionalPrincipal(),
    )

    override suspend fun createComment(id: Long, body: CommunityCommentRequest, authentication: Authentication) =
        community.createComment(authentication.principalOrThrow(), id, body.body, body.sourceLanguage)

    override suspend fun deleteComment(id: Long, commentId: Long, authentication: Authentication) =
        community.deleteComment(authentication.principalOrThrow(), id, commentId)

    override suspend fun reportQuestion(id: Long, body: ReportQuestionRequest, authentication: Authentication): ReportQuestionResponse {
        community.reportQuestion(authentication.principalOrThrow(), id, body.toCommand())
        return ReportQuestionResponse()
    }

    override suspend fun setUserBlocked(
        userId: Long,
        blocked: Boolean,
        authentication: Authentication,
    ): UserBlockResponse =
        community.setUserBlocked(authentication.principalOrThrow(), userId, blocked)

    override suspend fun submitFeedback(
        body: SubmitFeedbackRequest,
        deviceId: String?,
        authentication: Authentication?,
    ): FeedbackResponse =
        community.submitFeedback(
            authentication.optionalPrincipal(),
            deviceId,
            SubmitFeedbackCommand(body.content),
        )

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}

private fun ReportQuestionRequest.toCommand() = ReportQuestionCommand(
    reason = reason,
    message = message,
)
