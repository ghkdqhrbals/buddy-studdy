package com.buddystudy.avatar.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("avatar_items")
class AvatarItemEntity(
    @Id
    @Column("item_key")
    var key: String = "",
    @Column("category_key")
    var category: String = "",
    var slot: AvatarSlot = AvatarSlot.BASE,
    var displayNameKo: String = "",
    var displayNameEn: String = "",
    var assetName: String = "",
    var colorHex: String = "#8B5CF6",
    var defaultGrant: Boolean = false,
    var compatibleBases: String = "[]",
    var zIndex: Int = 0,
    var sortOrder: Int = 0,
    var active: Boolean = true,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
