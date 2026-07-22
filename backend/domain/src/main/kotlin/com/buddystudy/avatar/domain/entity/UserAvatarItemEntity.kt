package com.buddystudy.avatar.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("user_avatar_items")
class UserAvatarItemEntity(
    @Id
    var id: Long = 0,
    var userId: Long = 0,
    var itemKey: String = "",
    var grantedSource: String = "SYSTEM",
    var createdAt: Instant = Instant.now(),
)
