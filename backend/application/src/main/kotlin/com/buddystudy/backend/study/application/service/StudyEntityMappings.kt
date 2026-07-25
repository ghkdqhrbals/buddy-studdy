package com.buddystudy.backend.study.application.service

import com.buddystudy.study.domain.StudyRecord
import com.buddystudy.study.domain.StudyRecordAnswerUpdate
import com.buddystudy.study.domain.StudyRecordGradeUpdate
import com.buddystudy.study.domain.StudyRecordPublicityUpdate
import com.buddystudy.study.domain.StudyRecordSkipUpdate
import com.buddystudy.study.domain.StudyRecordState
import com.buddystudy.study.domain.StudyRecordStats
import com.buddystudy.study.domain.StudyRoomQuestionDraft
import com.buddystudy.study.domain.StudyRoomSchedule
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity

internal fun StudyEntity.toStudyRoomSchedule(
    appLanguage: String,
    questionStudyId: Long = id,
    questionSettings: StudyEntity = this,
) = StudyRoomSchedule(
    id = questionStudyId,
    deviceId = deviceId,
    userId = userId,
    topic = topic,
    difficultyLevel = difficultyLevel,
    openaiModel = questionSettings.openaiModel,
    appLanguage = appLanguage,
    customPrompt = questionSettings.customPrompt,
)

internal fun StudyRoomQuestionDraft.toQuestionEntity() = QuestionEntity(
    studyId = studyId,
    deviceId = deviceId,
    userId = userId,
    question = question,
    hint = hint,
    topic = topic,
    language = language,
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
        studyId = studyId,
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
