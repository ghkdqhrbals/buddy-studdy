package com.buddystuddy.account.domain.entity

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
    @Column(name = "app_language", nullable = false, length = 16)
    var appLanguage: String = "ko",
    @Column(name = "openai_api_key_cipher", columnDefinition = "text")
    var openaiApiKeyCipher: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
