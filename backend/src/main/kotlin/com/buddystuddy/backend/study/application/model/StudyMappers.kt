package com.buddystuddy.backend.study.application.model

import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.domain.QuestionStatsEntity

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
