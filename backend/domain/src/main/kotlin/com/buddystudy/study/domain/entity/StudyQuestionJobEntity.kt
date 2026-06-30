package com.buddystudy.study.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

enum class StudyQuestionJobStatus {
    SCHEDULED,
    PROCESSING,
    COMPLETED,
    CANCELED,
    FAILED,
}

@Entity
@Table(
    name = "study_question_jobs",
    indexes = [
        Index(name = "idx_study_question_jobs_due", columnList = "status,scheduled_at,id"),
        Index(name = "idx_study_question_jobs_study_status", columnList = "study_id,status,scheduled_at"),
        Index(name = "idx_study_question_jobs_user", columnList = "user_id,updated_at"),
    ],
)
class StudyQuestionJobEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "study_id", nullable = false)
    var studyId: Long = 0,
    @Column(name = "device_id", nullable = false, length = 191)
    var deviceId: String = "",
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(name = "scheduled_at", nullable = false)
    var scheduledAt: Instant = Instant.now(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: StudyQuestionJobStatus = StudyQuestionJobStatus.SCHEDULED,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,
    @Column(name = "locked_at")
    var lockedAt: Instant? = null,
    @Column(name = "locked_by", length = 128)
    var lockedBy: String? = null,
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
    @Column(name = "canceled_at")
    var canceledAt: Instant? = null,
    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,
    @Column(name = "created_question_id")
    var createdQuestionId: Long? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
