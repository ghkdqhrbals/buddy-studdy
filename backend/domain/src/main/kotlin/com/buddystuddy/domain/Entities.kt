package com.buddystuddy.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "users",
    uniqueConstraints = [UniqueConstraint(name = "uq_users_provider_provider_id", columnNames = ["provider", "provider_id"])],
    indexes = [Index(name = "idx_users_provider_id", columnList = "provider_id")]
)
class UserEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(nullable = false, length = 32)
    var provider: String = "ANONYMOUS",
    @Column(name = "provider_id", nullable = false, length = 191)
    var providerId: String = "",
    @Column(name = "password_hash", length = 64)
    var passwordHash: String? = null,
    @Column(nullable = false, length = 32)
    var status: String = "ANONYMOUS",
    @Column(nullable = false, length = 320)
    var email: String = "",
    @Column(name = "display_name", nullable = false, length = 120)
    var displayName: String = "Buddy",
    @Column(name = "avatar_url", length = 1000)
    var avatarUrl: String? = null,
    @Column(name = "avatar_symbol_name", nullable = false, length = 64)
    var avatarSymbolName: String = "pixel-buddy",
    @Column(name = "avatar_color_seed", nullable = false, length = 64)
    var avatarColorSeed: String = "avatar-color-mint",
    @Column(nullable = false, length = 500)
    var bio: String = "",
    @Column(name = "allow_public_questions", nullable = false)
    var allowPublicQuestions: Boolean = true,
    @Column(name = "openai_api_key_cipher", columnDefinition = "text")
    var openaiApiKeyCipher: String? = null,
    @Column(name = "openai_model", nullable = false, length = 64, columnDefinition = "varchar(64) default 'gpt-5.4'")
    var openaiModel: String = "gpt-5.4",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(
    name = "devices",
    indexes = [
        Index(name = "idx_devices_device_id", columnList = "device_id"),
        Index(name = "idx_devices_user_id", columnList = "user_id"),
    ]
)
class DeviceEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "device_id", nullable = false, unique = true, length = 191)
    var deviceId: String = "",
    @Column(name = "client_secret_hash", nullable = false, length = 191)
    var clientSecretHash: String = "",
    @Column(name = "user_id")
    var userId: Long? = null,
    @Column(name = "google_session_expires_at")
    var googleSessionExpiresAt: Instant? = null,
    @Column(name = "apns_token", nullable = false, length = 191)
    var apnsToken: String = "",
    @Column(nullable = false, length = 32)
    var platform: String = "ios",
    @Column(name = "apns_environment", nullable = false, length = 32)
    var apnsEnvironment: String = "production",
    @Column(nullable = false, length = 16)
    var language: String = "ko",
    @Column(nullable = false, length = 64)
    var timezone: String = "Asia/Seoul",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now(),
)

@Entity
@Table(
    name = "user_devices",
    uniqueConstraints = [UniqueConstraint(name = "uq_user_devices_user_device", columnNames = ["user_id", "device_id"])],
    indexes = [
        Index(name = "idx_user_devices_user_id", columnList = "user_id"),
        Index(name = "idx_user_devices_device_id", columnList = "device_id"),
    ]
)
class UserDeviceEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(name = "device_id", nullable = false, length = 191)
    var deviceId: String = "",
    @Column(name = "session_expires_at")
    var sessionExpiresAt: Instant? = null,
    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now(),
)

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
    var questionPublic: Boolean = false,
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

@Entity
@Table(
    name = "questions",
    indexes = [
        Index(name = "idx_questions_user_created", columnList = "user_id,created_at"),
        Index(name = "idx_questions_device_created", columnList = "device_id,created_at"),
        Index(name = "idx_questions_public", columnList = "is_public,deleted_at,created_at"),
        Index(name = "idx_questions_pending_study", columnList = "device_id,user_id,topic,deleted_at,skipped_at,score,status"),
    ]
)
class QuestionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "device_id", nullable = false, length = 191)
    var deviceId: String = "",
    @Column(name = "user_id")
    var userId: Long? = null,
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
    var publicQuestion: Boolean = false,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "question_stats")
class QuestionStatsEntity(
    @Id
    @Column(name = "question_id")
    var questionId: Long = 0,
    @Column(name = "like_count", nullable = false)
    var likeCount: Int = 0,
    @Column(name = "comment_count", nullable = false)
    var commentCount: Int = 0,
    @Column(name = "view_count", nullable = false)
    var viewCount: Int = 0,
    @Column(name = "verified_at")
    var verifiedAt: Instant? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

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

@Entity
@Table(name = "reports")
class ReportEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "question_id")
    var questionId: Long? = null,
    @Column(name = "reporter_device_id", length = 191)
    var reporterDeviceId: String? = null,
    @Column(name = "reporter_user_id")
    var reporterUserId: Long? = null,
    @Column(nullable = false, length = 120)
    var reason: String = "",
    @Column(nullable = false, length = 1000)
    var message: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
