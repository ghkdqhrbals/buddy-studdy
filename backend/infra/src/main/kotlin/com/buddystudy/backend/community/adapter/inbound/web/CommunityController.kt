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
import jakarta.validation.constraints.NotBlank
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
import org.springframework.http.ResponseEntity
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

    @Operation(
        summary = "List liked public questions",
        description = "Returns only public graded questions liked by the authenticated user. Results are ordered by the time they were liked and never contain advertisements.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "Liked public questions returned."))
    @GetMapping("/public/questions/liked")
    @RequirePermission(Permissions.PUBLIC_QUESTION_LIKE)
    suspend fun getLikedPublicQuestions(
        @Parameter(description = "Optional localized public-question search query.", example = "Swift")
        @RequestParam(required = false) query: String?,
        @Parameter(description = "Maximum number of items to return. Server clamps this to 1..100.", example = "20")
        @RequestParam(defaultValue = "20") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        @Parameter(description = "Target language for translated content and search. Supports ko, en, and ja.", example = "ko")
        @RequestParam(required = false) tl: String?,
        @Parameter(description = "Deprecated target-language alias kept for older clients.", example = "ko", deprecated = true)
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication,
    ) = community.getLikedPublicQuestions(
        query,
        targetLanguage(tl, language),
        view,
        limit,
        offset,
        authentication,
    )

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

    @Operation(summary = "Record native-ad view", description = "Queues an idempotent view event when the authenticated device opens a server-selected advertisement deep link.")
    @PostMapping("/native-ad-selections/{selectionId}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun recordNativeAdvertisementView(
        @PathVariable selectionId: String,
        authentication: Authentication,
    ) = community.recordNativeAdvertisementView(selectionId, authentication)

    @Operation(summary = "Record native-ad impression", description = "Idempotently records that at least half of a server-selected advertisement stayed visible for one second.")
    @PostMapping("/native-ad-selections/{selectionId}/impression")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun recordNativeAdvertisementImpression(
        @PathVariable selectionId: String,
        authentication: Authentication,
    ) = community.recordNativeAdvertisementImpression(selectionId, authentication)

    @Operation(summary = "Hide a native advertisement", description = "Permanently excludes the selected campaign from ranking for the authenticated user.")
    @PostMapping("/native-ad-selections/{selectionId}/not-interested")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun suppressNativeAdvertisement(
        @PathVariable selectionId: String,
        authentication: Authentication,
    ) = community.suppressNativeAdvertisement(selectionId, authentication)
}

@RestController
@RequestMapping("/api/v2")
@Tag(name = "Public Questions V2", description = "Full-text public completed-question search APIs.")
class CommunitySearchV2Controller(
    private val community: CommunityWebPort,
) {
    @Operation(
        summary = "List public completed questions with native-ad slots",
        description = "Returns the unfiltered public feed with at most one server-governed native-ad slot.",
    )
    @GetMapping("/public/questions")
    suspend fun getPublicQuestionFeedV2(
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) tl: String?,
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication?,
    ) = community.getPublicQuestionFeedV2(targetLanguage(tl, language), view, limit, offset, authentication)

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

    @Operation(summary = "Resolve a native-ad slot fallback")
    @PostMapping("/native-ad-slots/{slotId}/fallback")
    suspend fun nativeAdSlotFallback(
        @PathVariable slotId: String,
        authentication: Authentication,
    ): ResponseEntity<Any> = community.nativeAdSlotFallback(slotId, authentication)?.let { ResponseEntity.ok(it) }
        ?: ResponseEntity.noContent().build()

    @Operation(summary = "Record an AdMob native-ad impression")
    @PostMapping("/native-ad-slots/{slotId}/impression")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun recordNativeAdSlotImpression(
        @PathVariable slotId: String,
        @Valid @RequestBody body: NativeAdSlotProviderRequest,
        authentication: Authentication,
    ) = community.recordNativeAdSlotImpression(slotId, body.provider, authentication)

    @Operation(summary = "Record an AdMob native-ad click")
    @PostMapping("/native-ad-slots/{slotId}/click")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun recordNativeAdSlotClick(
        @PathVariable slotId: String,
        @Valid @RequestBody body: NativeAdSlotProviderRequest,
        authentication: Authentication,
    ) = community.recordNativeAdSlotClick(slotId, body.provider, authentication)
}

data class NativeAdSlotProviderRequest(
    @field:NotBlank var provider: String = "",
)

internal fun targetLanguage(tl: String?, legacyLanguage: String?): String =
    tl?.trim()?.takeIf(String::isNotEmpty)
        ?: legacyLanguage?.trim()?.takeIf(String::isNotEmpty)
        ?: "ko"

interface CommunityWebPort {
    suspend fun getPublicQuestions(query: String?, language: String, view: String, limit: Int, offset: Int, authentication: Authentication?): Any
    suspend fun getPublicQuestionsV2(query: String?, language: String, view: String, limit: Int, offset: Int, authentication: Authentication?): Any
    suspend fun getPublicQuestionFeedV2(language: String, view: String, limit: Int, offset: Int, authentication: Authentication?): Any
    suspend fun getLikedPublicQuestions(query: String?, language: String, view: String, limit: Int, offset: Int, authentication: Authentication): Any
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
    suspend fun recordNativeAdvertisementView(
        selectionId: String,
        authentication: Authentication,
    )
    suspend fun recordNativeAdvertisementImpression(
        selectionId: String,
        authentication: Authentication,
    )
    suspend fun suppressNativeAdvertisement(
        selectionId: String,
        authentication: Authentication,
    )
    suspend fun nativeAdSlotFallback(slotId: String, authentication: Authentication): Any?
    suspend fun recordNativeAdSlotImpression(slotId: String, provider: String, authentication: Authentication)
    suspend fun recordNativeAdSlotClick(slotId: String, provider: String, authentication: Authentication)
}

@Component
class CommunityWebAdapter(
    private val community: CommunityUseCase,
) : CommunityWebPort {
    override suspend fun getPublicQuestions(query: String?, language: String, view: String, limit: Int, offset: Int, authentication: Authentication?) =
        community.getPublicQuestions(authentication.optionalPrincipal(), query, language, view, safeLimit(limit, 100), max(0, offset))

    override suspend fun getPublicQuestionsV2(query: String?, language: String, view: String, limit: Int, offset: Int, authentication: Authentication?) =
        community.getPublicQuestionsV2(authentication.optionalPrincipal(), query, language, view, safeLimit(limit, 100), max(0, offset))

    override suspend fun getPublicQuestionFeedV2(language: String, view: String, limit: Int, offset: Int, authentication: Authentication?) =
        community.getPublicQuestionFeedV2(authentication.optionalPrincipal(), language, view, safeLimit(limit, 100), max(0, offset))

    override suspend fun getLikedPublicQuestions(
        query: String?,
        language: String,
        view: String,
        limit: Int,
        offset: Int,
        authentication: Authentication,
    ) = community.getLikedPublicQuestions(
        authentication.principalOrThrow(),
        query,
        language,
        view,
        safeLimit(limit, 100),
        max(0, offset),
    )

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

    override suspend fun recordNativeAdvertisementView(
        selectionId: String,
        authentication: Authentication,
    ) = community.recordNativeAdvertisementView(
        authentication.principalOrThrow(),
        selectionId,
    )

    override suspend fun recordNativeAdvertisementImpression(
        selectionId: String,
        authentication: Authentication,
    ) = community.recordNativeAdvertisementImpression(
        authentication.principalOrThrow(),
        selectionId,
    )

    override suspend fun suppressNativeAdvertisement(
        selectionId: String,
        authentication: Authentication,
    ) = community.suppressNativeAdvertisement(
        authentication.principalOrThrow(),
        selectionId,
    )

    override suspend fun nativeAdSlotFallback(slotId: String, authentication: Authentication) =
        community.nativeAdSlotFallback(authentication.principalOrThrow(), slotId)

    override suspend fun recordNativeAdSlotImpression(slotId: String, provider: String, authentication: Authentication) =
        community.recordNativeAdSlotImpression(authentication.principalOrThrow(), slotId, provider)

    override suspend fun recordNativeAdSlotClick(slotId: String, provider: String, authentication: Authentication) =
        community.recordNativeAdSlotClick(authentication.principalOrThrow(), slotId, provider)

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}

private fun ReportQuestionRequest.toCommand() = ReportQuestionCommand(
    reason = reason,
    message = message,
)
