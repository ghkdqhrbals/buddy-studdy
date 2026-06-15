package com.buddystuddy.study.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "questions",
    indexes = [
        Index(name = "idx_questions_user_created", columnList = "user_id,created_at"),
        Index(name = "idx_questions_device_created", columnList = "device_id,created_at"),
        Index(name = "idx_questions_study_created", columnList = "study_id,created_at"),
        Index(name = "idx_questions_public", columnList = "is_public,deleted_at,created_at"),
        Index(name = "idx_questions_pending_study", columnList = "study_id,deleted_at,skipped_at,score,status"),
        Index(name = "idx_questions_user_topic_graded_latest", columnList = "user_id,topic,deleted_at,score,answered_at,created_at,id"),
    ]
)
class QuestionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "device_id", nullable = false, length = 191)
    var deviceId: String = "",
    @Column(name = "user_id")
    var userId: Long? = null,
    @Column(name = "study_id")
    var studyId: Long? = null,
    @Column(nullable = false, columnDefinition = "text")
    var question: String = "",
    @Column(columnDefinition = "text")
    var hint: String? = null,
    @Column(nullable = false, length = 255)
    var topic: String = "",
    @Column(name = "difficulty_level", nullable = false)
    var difficultyLevel: Int = 5,
    @Column(name = "scheduled_for", nullable = false)
    var scheduledFor: Instant = Instant.now(),
    @Column(name = "sent_at")
    var sentAt: Instant? = null,
    @Column(nullable = false, length = 32)
    var status: String = "ungraded",
    @Column(columnDefinition = "text")
    var error: String? = null,
    @Column(columnDefinition = "text")
    var answer: String? = null,
    var score: Int? = null,
    @Column(name = "is_correct")
    var correct: Boolean? = null,
    @Column(columnDefinition = "text")
    var feedback: String? = null,
    @Column(columnDefinition = "text")
    var explanation: String? = null,
    @Column(name = "answered_at")
    var answeredAt: Instant? = null,
    @Column(name = "graded_at")
    var gradedAt: Instant? = null,
    @Column(name = "skipped_at")
    var skippedAt: Instant? = null,
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
    @Column(nullable = false, length = 64)
    var source: String = "scheduled",
    @Column(name = "is_public", nullable = false)
    var publicQuestion: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
