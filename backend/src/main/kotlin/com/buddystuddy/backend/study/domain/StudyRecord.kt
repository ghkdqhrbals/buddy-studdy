package com.buddystuddy.backend.study.domain

import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.domain.QuestionStatsEntity
import java.time.Instant

class StudyRecord private constructor(
    val question: QuestionEntity,
    private val stats: QuestionStatsEntity?,
) {
    val id: Long get() = question.id
    val topic: String get() = question.topic
    val difficultyLevel: Int get() = question.difficultyLevel
    val prompt: String get() = question.question

    fun answer(answer: String, now: Instant = Instant.now()) {
        question.answer = answer
        question.answeredAt = now
        question.updatedAt = now
    }

    fun grade(score: Int, isCorrect: Boolean, feedback: String, explanation: String, now: Instant = Instant.now()) {
        question.score = score
        question.correct = isCorrect
        question.feedback = feedback
        question.explanation = explanation
        question.gradedAt = now
        question.status = "graded"
        question.updatedAt = now
    }

    fun skip(now: Instant = Instant.now()) {
        question.skippedAt = now
        question.status = "skipped"
        question.updatedAt = now
    }

    fun restrictPublicity(isPublic: Boolean, now: Instant = Instant.now()) {
        question.publicQuestion = isPublic && question.score != null
        question.updatedAt = now
    }

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
    )

    companion object {
        fun of(question: QuestionEntity, stats: QuestionStatsEntity? = null) = StudyRecord(question, stats)
    }
}

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
)
