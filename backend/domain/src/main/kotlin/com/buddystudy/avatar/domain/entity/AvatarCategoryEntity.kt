package com.buddystudy.avatar.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("avatar_categories")
class AvatarCategoryEntity(
    @Id
    @Column("category_key")
    var key: String = "",
    var titleKo: String = "",
    var titleEn: String = "",
    var slot: AvatarSlot = AvatarSlot.BASE,
    var required: Boolean = false,
    var singleSelect: Boolean = true,
    var zIndex: Int = 0,
    var sortOrder: Int = 0,
    var active: Boolean = true,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
