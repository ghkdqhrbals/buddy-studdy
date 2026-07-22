package com.buddystudy.study.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("question_embeddings")
class QuestionEmbeddingEntity(
    @Id
    var questionId: Long = 0,
    var userId: Long = 0,
    var studyId: Long = 0,
    var topic: String = "",
    var topicKey: String = "",
    var question: String = "",
    var embedding: String = "",
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
