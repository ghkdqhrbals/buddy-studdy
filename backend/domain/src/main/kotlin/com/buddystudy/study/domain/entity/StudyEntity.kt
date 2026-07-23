package com.buddystudy.study.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("studies")
class StudyEntity(
    @Id
    var id: Long = 0,
    var deviceId: String = "",
    var userId: Long = 0,
    var topic: String = "",
    var difficultyLevel: Int = 5,
    var intervalMinutes: Int = 15,
    var enabled: Boolean = true,
    var notificationSound: String? = null,
    var customPrompt: String = "",
    var openaiModel: String = "gpt-5.4",
    var maxHistoryCount: Int = 100,
    var nextDueAt: Instant? = null,
    var scheduleClaimedUntil: Instant? = null,
    var lastSentAt: Instant? = null,
    var lastError: String? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
