package com.buddystudy.study.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("questions")
class QuestionEntity(
    @Id
    var id: Long = 0,
    var deviceId: String = "",
    var userId: Long? = null,
    var studyId: Long? = null,
    var conceptId: Long? = null,
    var conceptKey: String? = null,
    var angleKey: String? = null,
    var question: String = "",
    var hint: String? = null,
    var topic: String = "",
    var language: String = "ko",
    var difficultyLevel: Int = 5,
    var scheduledFor: Instant = Instant.now(),
    var sentAt: Instant? = null,
    var status: String = "ungraded",
    var error: String? = null,
    var answer: String? = null,
    var score: Int? = null,
    @Column("is_correct")
    var correct: Boolean? = null,
    var feedback: String? = null,
    var explanation: String? = null,
    var answeredAt: Instant? = null,
    var gradedAt: Instant? = null,
    var skippedAt: Instant? = null,
    var deletedAt: Instant? = null,
    var source: String = "scheduled",
    @Column("is_public")
    var publicQuestion: Boolean = true,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
