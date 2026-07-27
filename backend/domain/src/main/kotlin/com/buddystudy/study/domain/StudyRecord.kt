package com.buddystudy.study.domain

import java.time.Instant

class StudyRecord private constructor(
    val question: StudyRecordState,
    private val stats: StudyRecordStats?,
) {
    val id: Long get() = question.id
    val topic: String get() = question.topic
    val difficultyLevel: Int get() = question.difficultyLevel
    val prompt: String get() = question.question

    fun answer(answer: String, now: Instant = Instant.now()) = StudyRecordAnswerUpdate(
        answer = answer,
        answeredAt = now,
        updatedAt = now,
    )

    fun grade(score: Int, isCorrect: Boolean, feedback: String, explanation: String, now: Instant = Instant.now()) = StudyRecordGradeUpdate(
        score = score,
        correct = isCorrect,
        feedback = feedback,
        explanation = explanation,
        gradedAt = now,
        status = "graded",
        updatedAt = now,
    )

    fun skip(now: Instant = Instant.now()) = StudyRecordSkipUpdate(
        skippedAt = now,
        status = "skipped",
        updatedAt = now,
    )

    fun restrictPublicity(isPublic: Boolean, now: Instant = Instant.now()) = StudyRecordPublicityUpdate(
        publicQuestion = isPublic && question.score != null,
        updatedAt = now,
    )

    fun toProjection() = StudyRecordProjection(
        id = question.id.toString(),
        question = question.question,
        expectedAnswerHint = question.hint,
        createdAt = question.createdAt,
        answer = question.answer,
        score = question.score,
        correct = question.correct,
        feedback = question.feedback,
        explanation = question.explanation,
        topic = question.topic,
        difficulty = question.difficultyLevel,
        answeredAt = question.answeredAt,
        isPublic = question.publicQuestion,
        likeCount = stats?.likeCount ?: 0,
        commentCount = stats?.commentCount ?: 0,
        viewCount = stats?.viewCount ?: 0,
        studyId = question.studyId,
        gradingVerdict = question.gradingVerdict,
        gradingConfidence = question.gradingConfidence,
        gradingPolicyVersion = question.gradingPolicyVersion,
        gradingModel = question.gradingModel,
        gradingAssessmentJson = question.gradingAssessmentJson,
        gradingRequestId = question.gradingRequestId,
        gradingStatus = question.gradingStatus,
        gradingError = question.gradingError,
    )

    companion object {
        fun of(question: StudyRecordState, stats: StudyRecordStats? = null) = StudyRecord(question, stats)
    }
}

data class StudyRecordState(
    val id: Long,
    val question: String,
    val hint: String?,
    val createdAt: Instant,
    val answer: String?,
    val score: Int?,
    val correct: Boolean?,
    val feedback: String?,
    val explanation: String?,
    val topic: String,
    val difficultyLevel: Int,
    val answeredAt: Instant?,
    val publicQuestion: Boolean,
    val studyId: Long? = null,
    val gradingVerdict: String? = null,
    val gradingConfidence: Double? = null,
    val gradingPolicyVersion: String? = null,
    val gradingModel: String? = null,
    val gradingAssessmentJson: String? = null,
    val gradingRequestId: String? = null,
    val gradingStatus: String? = null,
    val gradingError: String? = null,
)

data class StudyRecordStats(
    val likeCount: Int,
    val commentCount: Int,
    val viewCount: Int,
)

data class StudyRecordAnswerUpdate(
    val answer: String,
    val answeredAt: Instant,
    val updatedAt: Instant,
)

data class StudyRecordGradeUpdate(
    val score: Int,
    val correct: Boolean,
    val feedback: String,
    val explanation: String,
    val gradedAt: Instant,
    val status: String,
    val updatedAt: Instant,
)

data class StudyRecordSkipUpdate(
    val skippedAt: Instant,
    val status: String,
    val updatedAt: Instant,
)

data class StudyRecordPublicityUpdate(
    val publicQuestion: Boolean,
    val updatedAt: Instant,
)

data class StudyRecordProjection(
    val id: String,
    val question: String,
    val expectedAnswerHint: String?,
    val createdAt: Instant,
    val answer: String?,
    val score: Int?,
    val correct: Boolean?,
    val feedback: String?,
    val explanation: String?,
    val topic: String,
    val difficulty: Int,
    val answeredAt: Instant?,
    val isPublic: Boolean,
    val likeCount: Int,
    val commentCount: Int,
    val viewCount: Int,
    val studyId: Long? = null,
    val gradingVerdict: String? = null,
    val gradingConfidence: Double? = null,
    val gradingPolicyVersion: String? = null,
    val gradingModel: String? = null,
    val gradingAssessmentJson: String? = null,
    val gradingRequestId: String? = null,
    val gradingStatus: String? = null,
    val gradingError: String? = null,
)
