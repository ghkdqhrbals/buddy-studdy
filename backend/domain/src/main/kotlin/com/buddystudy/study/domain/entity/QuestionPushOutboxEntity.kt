package com.buddystudy.study.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("question_push_outbox")
class QuestionPushOutboxEntity(
    @Id
    var id: Long = 0,
    var recordId: Long = 0,
    var studyId: Long? = null,
    var deviceId: String = "",
    var userId: Long? = null,
    var question: String = "",
    var expectedAnswerHint: String? = null,
    var topic: String = "",
    var difficultyLevel: Int = 5,
    var language: String = "ko",
    var sound: String? = null,
    var intervalMinutes: Int = 15,
    var status: String = "PENDING",
    var attempts: Int = 0,
    var nextAttemptAt: Instant = Instant.now(),
    var claimedAt: Instant? = null,
    var claimToken: String? = null,
    var streamKey: String? = null,
    var redisRecordId: String? = null,
    var publishedAt: Instant? = null,
    var lastError: String? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
