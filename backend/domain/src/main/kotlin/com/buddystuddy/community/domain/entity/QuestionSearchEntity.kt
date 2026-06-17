package com.buddystuddy.community.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Entity
@Table(
    name = "question_search",
    indexes = [
        Index(name = "idx_question_search_public_created", columnList = "public_question,score,deleted_at,created_at"),
        Index(name = "idx_question_search_user", columnList = "user_id"),
    ],
)
@IdClass(QuestionSearchId::class)
class QuestionSearchEntity(
    @Id
    @Column(name = "question_id")
    var questionId: Long = 0,
    @Id
    @Column(nullable = false, length = 16)
    var language: String = "ko",
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(nullable = false, length = 255)
    var topic: String = "",
    @Column(nullable = false, columnDefinition = "text")
    var question: String = "",
    @Column(columnDefinition = "text")
    var answer: String? = null,
    @Column(columnDefinition = "text")
    var feedback: String? = null,
    @Column(columnDefinition = "text")
    var explanation: String? = null,
    @Column(name = "author_display_name", nullable = false, length = 255)
    var authorDisplayName: String = "",
    @Column(name = "public_question", nullable = false)
    var publicQuestion: Boolean = true,
    var score: Int? = null,
    @Column(name = "answered_at")
    var answeredAt: Instant? = null,
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

data class QuestionSearchId(
    var questionId: Long = 0,
    var language: String = "ko",
) : Serializable
