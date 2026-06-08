package com.buddystuddy.community.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "question_likes",
    uniqueConstraints = [UniqueConstraint(name = "uq_question_likes_question_user", columnNames = ["question_id", "user_id"])],
)
class QuestionLikeEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "question_id", nullable = false)
    var questionId: Long = 0,
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
