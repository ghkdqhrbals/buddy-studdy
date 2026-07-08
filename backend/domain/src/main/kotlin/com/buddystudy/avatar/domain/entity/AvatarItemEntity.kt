package com.buddystudy.avatar.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "avatar_items",
    indexes = [
        Index(name = "idx_avatar_items_category", columnList = "category_key, sort_order"),
        Index(name = "idx_avatar_items_slot", columnList = "slot"),
    ],
)
class AvatarItemEntity(
    @Id
    @Column(name = "item_key", nullable = false, length = 96)
    var key: String = "",
    @Column(name = "category_key", nullable = false, length = 64)
    var category: String = "",
    @Column(nullable = false, length = 64)
    var slot: String = "",
    @Column(name = "display_name_ko", nullable = false, length = 120)
    var displayNameKo: String = "",
    @Column(name = "display_name_en", nullable = false, length = 120)
    var displayNameEn: String = "",
    @Column(name = "asset_name", nullable = false, length = 160)
    var assetName: String = "",
    @Column(name = "color_hex", nullable = false, length = 16)
    var colorHex: String = "#8B5CF6",
    @Column(name = "default_grant", nullable = false)
    var defaultGrant: Boolean = false,
    @Column(name = "compatible_bases", nullable = false, columnDefinition = "text")
    var compatibleBases: String = "[]",
    @Column(name = "z_index", nullable = false)
    var zIndex: Int = 0,
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
    @Column(nullable = false)
    var active: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
