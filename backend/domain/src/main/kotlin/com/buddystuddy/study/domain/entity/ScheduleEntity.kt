package com.buddystuddy.study.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "schedules",
    uniqueConstraints = [UniqueConstraint(name = "idx_schedules_device_user_topic", columnNames = ["device_id", "user_id", "topic"])],
    indexes = [
        Index(name = "idx_schedules_due", columnList = "enabled,next_due_at"),
        Index(name = "idx_schedules_device_user", columnList = "device_id,user_id"),
    ]
)
class ScheduleEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "device_id", nullable = false, length = 191)
    var deviceId: String = "",
    @Column(name = "user_id")
    var userId: Long? = null,
    @Column(nullable = false, length = 255)
    var topic: String = "",
    @Column(name = "difficulty_level", nullable = false)
    var difficultyLevel: Int = 5,
    @Column(name = "interval_minutes", nullable = false)
    var intervalMinutes: Int = 15,
    @Column(nullable = false)
    var enabled: Boolean = false,
    @Column(name = "notification_sound", length = 64)
    var notificationSound: String? = null,
    @Column(name = "custom_prompt", nullable = false, columnDefinition = "text")
    var customPrompt: String = "",
    @Column(name = "app_language", nullable = false, length = 16)
    var appLanguage: String = "ko",
    @Column(name = "openai_model", nullable = false, length = 64)
    var openaiModel: String = "gpt-5.4",
    @Column(name = "max_history_count", nullable = false)
    var maxHistoryCount: Int = 100,
    @Column(name = "is_question_public", nullable = false)
    var questionPublic: Boolean = true,
    @Column(name = "openai_api_key_cipher", columnDefinition = "text")
    var openaiApiKeyCipher: String? = null,
    @Column(name = "next_due_at")
    var nextDueAt: Instant? = null,
    @Column(name = "last_sent_at")
    var lastSentAt: Instant? = null,
    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
