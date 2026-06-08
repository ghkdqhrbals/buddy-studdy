package com.buddystuddy.backend.dto

import java.time.Instant

data class ReportQuestionRequest(val reason: String, val message: String = "")
data class ReportQuestionResponse(val ok: Boolean = true)
data class CommunityCommentRequest(val body: String)

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
)

data class CommunityQuestionsResponse(val questions: List<CommunityQuestionResponse>, val totalCount: Long, val limit: Int, val offset: Int)
data class CommunityLikeResponse(val questionId: String, val likeCount: Int, val isLikedByMe: Boolean)
data class CommunityCommentResponse(val id: String, val questionId: String, val body: String, val createdAt: Instant, val author: UserProfileResponse)
data class CommunityCommentsResponse(val comments: List<CommunityCommentResponse>, val totalCount: Long, val limit: Int, val offset: Int)
