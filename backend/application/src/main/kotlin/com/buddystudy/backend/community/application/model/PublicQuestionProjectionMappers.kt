package com.buddystudy.backend.community.application.model

import com.buddystudy.community.domain.PublicQuestionProjection
import com.buddystudy.backend.profile.application.model.UserProfileResponse
import com.buddystudy.backend.study.application.model.GradingResultResponse

fun PublicQuestionProjection.toCommunityQuestionResponse() = CommunityQuestionResponse(
    id = id,
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
    author = author?.let {
        UserProfileResponse(
            id = it.id,
            displayName = it.displayName,
            bio = it.bio,
            avatarUrl = it.avatarUrl,
            avatarSymbolName = it.avatarSymbolName,
            avatarColorSeed = it.avatarColorSeed,
        )
    },
    likeCount = likeCount,
    commentCount = commentCount,
    viewCount = viewCount,
    isLikedByMe = isLikedByMe,
)
