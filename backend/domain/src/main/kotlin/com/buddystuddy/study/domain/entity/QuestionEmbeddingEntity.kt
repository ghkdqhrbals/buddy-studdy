package com.buddystuddy.study.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "question_embeddings",
    indexes = [
        Index(name = "idx_question_embeddings_study_topic_created", columnList = "study_id,topic_key,created_at"),
        Index(name = "idx_question_embeddings_user_topic_created", columnList = "user_id,topic_key,created_at"),
    ],
)
class QuestionEmbeddingEntity(
    @Id
    @Column(name = "question_id", nullable = false)
    var questionId: Long = 0,
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(name = "study_id", nullable = false)
    var studyId: Long = 0,
    @Column(nullable = false, length = 255)
    var topic: String = "",
    @Column(name = "topic_key", nullable = false, length = 255)
    var topicKey: String = "",
    @Column(nullable = false, columnDefinition = "text")
    var question: String = "",
    @Column(nullable = false, columnDefinition = "text")
    var embedding: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
