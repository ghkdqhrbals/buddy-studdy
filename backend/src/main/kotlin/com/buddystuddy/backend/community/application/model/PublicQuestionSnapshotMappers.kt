package com.buddystuddy.backend.community.application.model

import com.buddystuddy.backend.community.domain.PublicQuestionSnapshot
import com.buddystuddy.backend.profile.application.model.CommunityPageAccess
import com.buddystuddy.backend.profile.application.model.UserProfileResponse
import com.buddystuddy.backend.study.application.model.GradingResultResponse

fun PublicQuestionSnapshot.toCommunityQuestionResponse() = CommunityQuestionResponse(
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
            pageAccess = CommunityPageAccess(publicQuestions = it.publicQuestionsAllowed),
        )
    },
    likeCount = likeCount,
    commentCount = commentCount,
    viewCount = viewCount,
    isLikedByMe = isLikedByMe,
)
