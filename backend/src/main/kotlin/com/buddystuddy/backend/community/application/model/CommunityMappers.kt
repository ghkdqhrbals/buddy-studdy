package com.buddystuddy.backend.community.application.model

import com.buddystuddy.backend.domain.QuestionCommentEntity
import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.domain.QuestionStatsEntity
import com.buddystuddy.backend.profile.application.model.UserProfileResponse
import com.buddystuddy.backend.study.application.model.GradingResultResponse

fun QuestionEntity.toCommunity(author: UserProfileResponse?, stats: QuestionStatsEntity?, likedByMe: Boolean) = CommunityQuestionResponse(
    id = id.toString(),
    question = question,
    answer = answer,
    gradingResult = score?.let {
        GradingResultResponse(it, correct ?: (it >= 70), feedback ?: "", explanation ?: "")
    },
    topic = topic,
    difficultyLevel = difficultyLevel,
    status = status,
    source = source,
    createdAt = createdAt,
    answeredAt = answeredAt,
    author = author,
    likeCount = stats?.likeCount ?: 0,
    commentCount = stats?.commentCount ?: 0,
    viewCount = stats?.viewCount ?: 0,
    isLikedByMe = likedByMe,
)

fun QuestionCommentEntity.toResponse(author: UserProfileResponse) =
    CommunityCommentResponse(id.toString(), questionId.toString(), body, createdAt, author)
