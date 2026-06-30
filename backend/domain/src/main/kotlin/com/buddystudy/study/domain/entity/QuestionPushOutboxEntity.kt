package com.buddystudy.study.domain.entity

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
    name = "question_push_outbox",
    indexes = [
        Index(name = "idx_question_push_outbox_pending", columnList = "status,next_attempt_at,created_at"),
        Index(name = "idx_question_push_outbox_record", columnList = "record_id"),
    ],
)
class QuestionPushOutboxEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "record_id", nullable = false)
    var recordId: Long = 0,
    @Column(name = "study_id")
    var studyId: Long? = null,
    @Column(name = "device_id", nullable = false, length = 191)
    var deviceId: String = "",
    @Column(name = "user_id")
    var userId: Long? = null,
    @Column(nullable = false, columnDefinition = "text")
    var question: String = "",
    @Column(name = "expected_answer_hint", columnDefinition = "text")
    var expectedAnswerHint: String? = null,
    @Column(nullable = false, length = 255)
    var topic: String = "",
    @Column(name = "difficulty_level", nullable = false)
    var difficultyLevel: Int = 5,
    @Column(nullable = false, length = 16)
    var language: String = "ko",
    @Column(length = 64)
    var sound: String? = null,
    @Column(name = "interval_minutes", nullable = false)
    var intervalMinutes: Int = 15,
    @Column(nullable = false, length = 32)
    var status: String = "PENDING",
    @Column(nullable = false)
    var attempts: Int = 0,
    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now(),
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
