package com.buddystuddy.backend.community.domain

import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.domain.QuestionStatsEntity
import java.time.Instant

class PublicQuestionAggregate private constructor(
    private val question: QuestionEntity,
    private val author: PublicQuestionAuthorProjection?,
    private val stats: QuestionStatsEntity?,
    private val likedByMe: Boolean,
) {
    fun toProjection() = PublicQuestionProjection(
        id = question.id.toString(),
        question = question.question,
        answer = question.answer,
        score = question.score,
        correct = question.correct,
        feedback = question.feedback,
        explanation = question.explanation,
        topic = question.topic,
        difficultyLevel = question.difficultyLevel,
        status = question.status,
        source = question.source,
        createdAt = question.createdAt,
        answeredAt = question.answeredAt,
        author = author,
        likeCount = stats?.likeCount ?: 0,
        commentCount = stats?.commentCount ?: 0,
        viewCount = stats?.viewCount ?: 0,
        isLikedByMe = likedByMe,
    )

    companion object {
        fun of(
            question: QuestionEntity,
            author: PublicQuestionAuthorProjection?,
            stats: QuestionStatsEntity?,
            likedByMe: Boolean,
        ) = PublicQuestionAggregate(question, author, stats, likedByMe)
    }
}

data class PublicQuestionAuthorProjection(
    val id: Long,
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val avatarSymbolName: String = "pixel-buddy",
    val avatarColorSeed: String = "avatar-color-mint",
    val publicQuestionsAllowed: Boolean = true,
)

data class PublicQuestionProjection(
    val id: String,
    val question: String,
    val answer: String?,
    val score: Int?,
    val correct: Boolean?,
    val feedback: String?,
    val explanation: String?,
    val topic: String,
    val difficultyLevel: Int,
    val status: String,
    val source: String,
    val createdAt: Instant,
    val answeredAt: Instant?,
    val author: PublicQuestionAuthorProjection?,
    val likeCount: Int,
    val commentCount: Int,
    val viewCount: Int,
    val isLikedByMe: Boolean,
)
