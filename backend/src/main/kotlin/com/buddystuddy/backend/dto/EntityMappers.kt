package com.buddystuddy.backend.dto

import com.buddystuddy.backend.domain.QuestionCommentEntity
import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.domain.QuestionStatsEntity
import com.buddystuddy.backend.domain.ScheduleEntity
import com.buddystuddy.backend.domain.UserEntity

fun UserEntity.toProfile() = UserProfileResponse(
    id = id,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarUrl,
    avatarSymbolName = avatarSymbolName,
    avatarColorSeed = avatarColorSeed,
    pageAccess = CommunityPageAccess(publicQuestions = allowPublicQuestions),
)

fun ScheduleEntity?.toSettings() = this?.let {
    BackendSettingsResponse(
        topic = it.topic,
        difficultyLevel = it.difficultyLevel,
        intervalMinutes = it.intervalMinutes,
        enabled = it.enabled,
        notificationSound = it.notificationSound,
        customPrompt = it.customPrompt,
        appLanguage = it.appLanguage,
        openaiModel = it.openaiModel,
        maxHistoryCount = it.maxHistoryCount,
        isQuestionPublic = it.questionPublic,
        openaiKeyConfigured = !it.openaiApiKeyCipher.isNullOrBlank(),
        nextDueAt = it.nextDueAt,
        lastError = it.lastError,
    )
} ?: BackendSettingsResponse()

fun QuestionEntity.toRecord(stats: QuestionStatsEntity? = null) = StudyRecordResponse(
    id = id.toString(),
    question = QuestionItemResponse(question = question, expectedAnswerHint = hint, createdAt = createdAt),
    answer = answer,
    gradingResult = score?.let {
        GradingResultResponse(
            score = it,
            isCorrect = correct ?: (it >= 70),
            feedback = feedback ?: "",
            explanation = explanation ?: "",
        )
    },
    topic = topic,
    difficulty = difficultyLevel,
    answeredAt = answeredAt,
    isPublic = publicQuestion,
    likeCount = stats?.likeCount ?: 0,
    commentCount = stats?.commentCount ?: 0,
    viewCount = stats?.viewCount ?: 0,
)

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
