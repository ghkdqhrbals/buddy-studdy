package com.buddystudy.community.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.io.Serializable
import java.time.Instant

@Table("question_search")
class QuestionSearchEntity(
    @Id
    var questionId: Long = 0,
    var language: String = "ko",
    var userId: Long = 0,
    var topic: String = "",
    var question: String = "",
    var answer: String? = null,
    var feedback: String? = null,
    var explanation: String? = null,
    var authorDisplayName: String = "",
    var publicQuestion: Boolean = true,
    var score: Int? = null,
    var answeredAt: Instant? = null,
    var deletedAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)

data class QuestionSearchId(
    var questionId: Long = 0,
    var language: String = "ko",
) : Serializable
