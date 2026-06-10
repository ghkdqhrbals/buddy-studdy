package com.buddystuddy.backend.study.application.service

import com.buddystuddy.study.domain.StudyRecord
import com.buddystuddy.study.domain.StudyRecordAnswerUpdate
import com.buddystuddy.study.domain.StudyRecordGradeUpdate
import com.buddystuddy.study.domain.StudyRecordPublicityUpdate
import com.buddystuddy.study.domain.StudyRecordSkipUpdate
import com.buddystuddy.study.domain.StudyRecordState
import com.buddystuddy.study.domain.StudyRecordStats
import com.buddystuddy.study.domain.StudyRoomQuestionDraft
import com.buddystuddy.study.domain.StudyRoomSchedule
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.study.domain.entity.StudyEntity

internal fun StudyEntity.toStudyRoomSchedule(appLanguage: String) = StudyRoomSchedule(
    id = id,
    deviceId = deviceId,
    userId = userId,
    topic = topic,
    difficultyLevel = difficultyLevel,
    openaiModel = openaiModel,
    appLanguage = appLanguage,
    customPrompt = customPrompt,
)

internal fun StudyRoomQuestionDraft.toQuestionEntity() = QuestionEntity(
    studyId = studyId,
    deviceId = deviceId,
    userId = userId,
    question = question,
    hint = hint,
    topic = topic,
    difficultyLevel = difficultyLevel,
    scheduledFor = scheduledFor,
    sentAt = sentAt,
    status = status,
    source = source,
    publicQuestion = publicQuestion,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun QuestionEntity.toStudyRecord(stats: QuestionStatsEntity? = null) = StudyRecord.of(
    StudyRecordState(
        id = id,
        question = question,
        hint = hint,
        createdAt = createdAt,
        answer = answer,
        score = score,
        correct = correct,
        feedback = feedback,
        explanation = explanation,
        topic = topic,
        difficultyLevel = difficultyLevel,
        answeredAt = answeredAt,
        publicQuestion = publicQuestion,
    ),
    stats?.let { StudyRecordStats(it.likeCount, it.commentCount, it.viewCount) },
)

internal fun QuestionEntity.apply(update: StudyRecordAnswerUpdate) {
    answer = update.answer
    answeredAt = update.answeredAt
    updatedAt = update.updatedAt
}

internal fun QuestionEntity.apply(update: StudyRecordGradeUpdate) {
    score = update.score
    correct = update.correct
    feedback = update.feedback
    explanation = update.explanation
    gradedAt = update.gradedAt
    status = update.status
    updatedAt = update.updatedAt
}

internal fun QuestionEntity.apply(update: StudyRecordSkipUpdate) {
    skippedAt = update.skippedAt
    status = update.status
    updatedAt = update.updatedAt
}

internal fun QuestionEntity.apply(update: StudyRecordPublicityUpdate) {
    publicQuestion = update.publicQuestion
    updatedAt = update.updatedAt
}
