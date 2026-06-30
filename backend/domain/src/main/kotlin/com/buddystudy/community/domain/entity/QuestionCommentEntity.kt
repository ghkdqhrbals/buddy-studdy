package com.buddystudy.community.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "question_comments")
class QuestionCommentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "question_id", nullable = false)
    var questionId: Long = 0,
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(nullable = false, length = 1000)
    var body: String = "",
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
