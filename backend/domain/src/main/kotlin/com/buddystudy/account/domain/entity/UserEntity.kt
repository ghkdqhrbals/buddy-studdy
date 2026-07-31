package com.buddystudy.account.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import com.buddystudy.common.domain.SupportedLanguage
import java.time.Instant

@Table("users")
class UserEntity(
    @Id
    var id: Long = 0,
    var provider: UserProvider = UserProvider.ANONYMOUS,
    var providerId: String = "",
    var passwordHash: String? = null,
    var status: UserStatus = UserStatus.ANONYMOUS,
    var email: String = "",
    var displayName: String = "Buddy",
    var avatarUrl: String? = null,
    var avatarSymbolName: String = "pixel-buddy",
    var avatarColorSeed: String = "avatar-color-mint",
    var avatarMode: AvatarMode = AvatarMode.BUILDER,
    var avatarConfig: String? = null,
    var bio: String = "",
    var allowPublicQuestions: Boolean = true,
    var appLanguage: SupportedLanguage = SupportedLanguage.KOREAN,
    var openaiApiKeyCipher: String? = null,
    var freeSystemQuestionCount: Int = 0,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
