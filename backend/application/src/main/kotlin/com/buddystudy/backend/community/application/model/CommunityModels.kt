package com.buddystudy.backend.community.application.model

import com.buddystudy.backend.common.application.model.PageResponse
import com.buddystudy.backend.profile.application.model.UserProfileResponse
import com.buddystudy.backend.study.application.model.GradingResultResponse
import com.buddystudy.backend.study.application.model.ContentLocalizationResponse
import com.buddystudy.backend.study.application.model.RecordLocalizationResponse
import java.time.Instant

data class ReportQuestionResponse(val ok: Boolean = true)
data class UserBlockResponse(val userId: Long, val blocked: Boolean)

data class FeedbackResponse(
    val id: Long,
    val createdAt: Instant,
)

data class CommunityQuestionResponse(
    val id: String,
    val question: String,
    val answer: String?,
    val gradingResult: GradingResultResponse?,
    val topic: String,
    val difficultyLevel: Int,
    val status: String,
    val source: String,
    val createdAt: Instant,
    val answeredAt: Instant?,
    val author: UserProfileResponse?,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val viewCount: Int = 0,
    val isLikedByMe: Boolean = false,
    val localization: RecordLocalizationResponse? = null,
)

data class CommunityQuestionsResponse(
    val questions: List<CommunityQuestionResponse>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse
data class CommunityLikeResponse(val questionId: String, val likeCount: Int, val isLikedByMe: Boolean)
data class CommunityCommentResponse(
    val id: String,
    val questionId: String,
    val body: String,
    val createdAt: Instant,
    val author: UserProfileResponse,
    val localization: ContentLocalizationResponse? = null,
)
data class CommunityCommentDeleteResponse(val id: String, val questionId: String, val ok: Boolean = true)
data class CommunityCommentsResponse(
    val comments: List<CommunityCommentResponse>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse
