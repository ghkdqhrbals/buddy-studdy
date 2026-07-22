package com.buddystudy.study.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

enum class StudyQuestionJobStatus {
    SCHEDULED,
    PROCESSING,
    COMPLETED,
    CANCELED,
    FAILED,
}

@Table("study_question_jobs")
class StudyQuestionJobEntity(
    @Id
    var id: Long = 0,
    var studyId: Long = 0,
    var deviceId: String = "",
    var userId: Long = 0,
    var scheduledAt: Instant = Instant.now(),
    var status: StudyQuestionJobStatus = StudyQuestionJobStatus.SCHEDULED,
    var attemptCount: Int = 0,
    var lockedAt: Instant? = null,
    var lockedBy: String? = null,
    var completedAt: Instant? = null,
    var canceledAt: Instant? = null,
    var lastError: String? = null,
    var createdQuestionId: Long? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
