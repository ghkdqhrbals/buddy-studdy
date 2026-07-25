package com.buddystudy.backend.study.application.model

import com.buddystudy.study.domain.StudyRecordProjection

fun StudyRecordProjection.toRecordResponse() = StudyRecordResponse(
    id = id,
    question = QuestionItemResponse(question = question, expectedAnswerHint = expectedAnswerHint, createdAt = createdAt),
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
    difficulty = difficulty,
    answeredAt = answeredAt,
    isPublic = isPublic,
    likeCount = likeCount,
    commentCount = commentCount,
    viewCount = viewCount,
    studyId = studyId,
)
